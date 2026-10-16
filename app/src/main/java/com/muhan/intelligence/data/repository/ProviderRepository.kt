package com.muhan.intelligence.data.repository

import com.muhan.intelligence.BuildConfig
import com.muhan.intelligence.data.local.ProviderDao
import com.muhan.intelligence.data.local.ProviderEntity
import com.muhan.intelligence.data.local.SecureKeyStore
import com.muhan.intelligence.data.remote.ChatRemoteDataSource
import com.muhan.intelligence.data.remote.ProviderPresets
import com.muhan.intelligence.domain.model.ApiFlavor
import com.muhan.intelligence.domain.model.ConnectionTestResult
import com.muhan.intelligence.domain.model.MessageStatus
import com.muhan.intelligence.domain.model.ProviderConfig
import com.muhan.intelligence.domain.model.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Owns provider configuration and forwards chat calls to the network layer. */
@Singleton
class ProviderRepository @Inject constructor(
    private val providerDao: ProviderDao,
    private val secureKeyStore: SecureKeyStore,
    private val remote: ChatRemoteDataSource,
) {

    fun observeProviders(): Flow<List<ProviderConfig>> =
        providerDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeActiveProvider(): Flow<ProviderConfig?> =
        providerDao.observeActive().map { it?.toDomain() }

    suspend fun getActiveProvider(): ProviderConfig? = providerDao.getActive()?.toDomain()

    suspend fun saveProvider(config: ProviderConfig, apiKey: String?): ProviderConfig {
        val id = config.id.ifBlank { UUID.randomUUID().toString() }
        val entity = config.copy(id = id).toEntity()
        if (config.isActive) {
            providerDao.activate(entity)
        } else {
            providerDao.upsert(entity)
        }
        if (apiKey != null) {
            secureKeyStore.putApiKey(id, apiKey)
        }
        return entity.toDomain()
    }

    suspend fun setActive(id: String) {
        providerDao.clearActive()
        providerDao.markActive(id)
    }

    suspend fun deleteProvider(id: String) {
        providerDao.deleteById(id)
        secureKeyStore.removeApiKey(id)
    }

    fun getApiKey(id: String): String? = secureKeyStore.getApiKey(id)

    fun maskedApiKey(id: String): String? = secureKeyStore.maskedApiKey(id)

    fun hasApiKey(id: String): Boolean = secureKeyStore.hasApiKey(id)

    /** Streams a completion for [provider], resolving its key from encrypted storage. */
    fun streamCompletion(
        provider: ProviderConfig,
        messages: List<com.muhan.intelligence.data.remote.RequestBodyFactory.OutboundMessage>,
        systemPrompt: String,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        thinkingEnabled: Boolean = false,
        webSearchEnabled: Boolean = false,
    ): Flow<StreamEvent> {
        val key = secureKeyStore.getApiKey(provider.id)
            ?: return kotlinx.coroutines.flow.flow {
                emit(StreamEvent.Failed("尚未为该模型配置 API Key，请前往「设置 → 模型服务」填写。"))
            }

        return remote.streamChat(
            provider = provider,
            apiKey = key,
            messages = messages,
            systemPrompt = systemPrompt,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            thinkingEnabled = thinkingEnabled,
            webSearchEnabled = webSearchEnabled,
        )
    }

    /** Runs an image-generation request with the provider's optional image model. */
    suspend fun generateImage(
        provider: ProviderConfig,
        prompt: String,
    ): Result<com.muhan.intelligence.data.remote.RequestBodyFactory.ImageReply> {
        val imageModel = provider.imageModel
            ?: return Result.failure(IllegalStateException("当前服务未配置生图模型。"))
        val key = secureKeyStore.getApiKey(provider.id)
            ?: return Result.failure(IllegalStateException("尚未为该模型配置 API Key。"))
        return remote.generateImage(provider, key, imageModel, prompt)
    }

    suspend fun testConnection(config: ProviderConfig, apiKeyOverride: String? = null): ConnectionTestResult {
        val key = apiKeyOverride?.takeIf { it.isNotBlank() }
            ?: secureKeyStore.getApiKey(config.id)
            ?: return ConnectionTestResult.Failure("请先填写 API Key。")

        return remote.testConnection(config, key)
    }

    /** Convenience used by onboarding: create + activate + verify in one step. */
    suspend fun provisionAndActivate(
        displayName: String,
        baseUrl: String,
        modelName: String,
        apiKey: String,
        flavor: ApiFlavor? = null,
    ): Result<ProviderConfig> {
        val resolvedFlavor = flavor ?: ProviderPresets.guessFlavor(baseUrl)
        val id = UUID.randomUUID().toString()
        val draft = ProviderConfig(
            id = id,
            displayName = displayName,
            baseUrl = baseUrl,
            modelName = modelName,
            flavor = resolvedFlavor,
            isActive = true,
        )

        val probe = remote.testConnection(draft, apiKey.trim())
        if (probe is ConnectionTestResult.Failure) {
            return Result.failure(IllegalStateException(probe.message))
        }

        val saved = saveProvider(draft, apiKey.trim())
        return Result.success(saved)
    }

    private fun ProviderEntity.toDomain() = ProviderConfig(
        id = id,
        displayName = displayName,
        baseUrl = baseUrl,
        modelName = modelName,
        flavor = runCatching { ApiFlavor.valueOf(flavor) }.getOrDefault(ApiFlavor.OPENAI),
        isActive = isActive,
        createdAt = createdAt,
        imageModel = imageModel?.takeIf { it.isNotBlank() },
        displayModelName = displayModelName?.takeIf { it.isNotBlank() },
    )

    private fun ProviderConfig.toEntity() = ProviderEntity(
        id = id,
        displayName = displayName,
        baseUrl = baseUrl,
        modelName = modelName,
        flavor = flavor.name,
        isActive = isActive,
        createdAt = createdAt,
        imageModel = imageModel,
        displayModelName = displayModelName,
    )

    /**
     * 首次启动时种子化内置「慕寒智能」服务，使其成为默认激活模型，跳过引导。
     * 仅在数据库里还没有任何服务商时才写入（幂等；已装旧版且自配模型的用户不受影响）。
     * 所有接入信息来自 BuildConfig（由 gitignore 的 local.properties 注入），明文不进源码。
     */
    suspend fun ensureDefaultProvider() {
        if (providerDao.observeAll().first().isNotEmpty()) return
        val baseUrl = BuildConfig.MHI_BASE_URL
        val model = BuildConfig.MHI_MODEL
        val apiKey = BuildConfig.MHI_API_KEY_A + BuildConfig.MHI_API_KEY_B
        if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) return
        saveProvider(
            ProviderConfig(
                id = "mhi-builtin",
                displayName = "慕寒智能",
                baseUrl = baseUrl,
                modelName = model,
                flavor = ApiFlavor.OPENAI,
                isActive = true,
                displayModelName = "mhi-1.0-ultra",
            ),
            apiKey = apiKey,
        )
    }
}
