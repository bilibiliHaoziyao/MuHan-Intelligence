package com.muhan.intelligence.data.remote

import com.muhan.intelligence.domain.model.ConnectionTestResult
import com.muhan.intelligence.domain.model.ProviderConfig
import com.muhan.intelligence.domain.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends a minimal, non-streaming request to verify that a provider configuration
 * actually works before it is saved.
 *
 * Deliberately separate from [ChatRemoteDataSource]: probes must fail fast (short
 * timeouts, tiny token budget) so the wizard never leaves the user staring at a
 * spinner, whereas real completions are allowed to run long.
 */
@Singleton
class ConnectionTester @Inject constructor() {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun test(
        config: ProviderConfig,
        apiKey: String,
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext ConnectionTestResult.Failure("请先填写 API Key。")
        }
        if (!config.baseUrl.startsWith("http://") && !config.baseUrl.startsWith("https://")) {
            return@withContext ConnectionTestResult.Failure("接口地址必须以 http:// 或 https:// 开头。")
        }
        if (config.baseUrl.startsWith("http://") &&
            !config.baseUrl.contains("127.0.0.1") &&
            !config.baseUrl.contains("localhost") &&
            !config.baseUrl.contains("192.168.") &&
            !config.baseUrl.contains("10.0.") &&
            !config.baseUrl.contains("172.")
        ) {
            // Warn about plaintext to a public host — users pasting a LAN address
            // are fine, but sending a key in the clear to the internet is not.
            return@withContext ConnectionTestResult.Failure(
                "出于安全考虑，公网接口请使用 https。若为本地部署（如 Ollama）请确认地址正确。",
            )
        }

        val started = System.currentTimeMillis()
        val request = buildProbeRequest(config, apiKey)

        try {
            client.newCall(request).execute().use { response ->
                val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
                val latency = System.currentTimeMillis() - started

                if (response.isSuccessful) {
                    val echoed = RequestBodyFactory.extractModelEcho(body)
                    ConnectionTestResult.Success(latency, echoed)
                } else {
                    ConnectionTestResult.Failure(
                        message = ApiErrorMapper.describe(
                            IOException("HTTP ${response.code}"),
                            httpCode = response.code,
                            rawBody = body,
                        ),
                        httpCode = response.code,
                    )
                }
            }
        } catch (e: Exception) {
            ConnectionTestResult.Failure(ApiErrorMapper.describe(e))
        }
    }

    private fun buildProbeRequest(config: ProviderConfig, apiKey: String): Request {
        val url = EndpointResolver.resolve(config, stream = false)
        val payload = RequestBodyFactory.build(
            flavor = config.flavor,
            model = config.modelName,
            messages = listOf(Role.USER to "hi"),
            systemPrompt = "",
            temperature = 0f,
            topP = 1f,
            // Minimal budget keeps the probe cheap on paid providers.
            maxTokens = 16,
            stream = false,
        )

        return Request.Builder()
            .url(url)
            .post(payload.toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .apply {
                when (config.flavor) {
                    com.muhan.intelligence.domain.model.ApiFlavor.OPENAI ->
                        header("Authorization", "Bearer $apiKey")
                    com.muhan.intelligence.domain.model.ApiFlavor.ANTHROPIC -> {
                        header("x-api-key", apiKey)
                        header("anthropic-version", "2023-06-01")
                    }
                    com.muhan.intelligence.domain.model.ApiFlavor.GEMINI ->
                        header("x-goog-api-key", apiKey)
                }
            }
            .build()
    }
}
