package com.muhan.intelligence.data.remote

import com.muhan.intelligence.domain.model.ApiFlavor
import com.muhan.intelligence.domain.model.ConnectionTestResult
import com.muhan.intelligence.domain.model.ProviderConfig
import com.muhan.intelligence.domain.model.Role
import com.muhan.intelligence.domain.model.StreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point for talking to a user-configured LLM provider.
 *
 * A dedicated [OkHttpClient] is used (rather than a shared DI singleton) because
 * streaming completions need a read timeout measured in minutes while the
 * connection handshake should still fail fast.
 */
@Singleton
class ChatRemoteDataSource @Inject constructor() {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Streams a completion. The returned flow is cold: collection starts the
     * request and cancelling it tears the underlying call down immediately, which
     * is what powers the "stop generating" button.
     */
    fun streamChat(
        provider: ProviderConfig,
        apiKey: String,
        messages: List<RequestBodyFactory.OutboundMessage>,
        systemPrompt: String,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        thinkingEnabled: Boolean = false,
        webSearchEnabled: Boolean = false,
    ): Flow<StreamEvent> = callbackFlow {
        val request = buildRequest(
            provider = provider,
            apiKey = apiKey,
            messages = messages,
            systemPrompt = systemPrompt,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            stream = true,
            thinkingEnabled = thinkingEnabled,
            webSearchEnabled = webSearchEnabled,
        )

        val call = client.newCall(request)
        val parser = SseStreamParser(provider.flavor)
        var finished = false

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!finished) {
                    finished = true
                    trySend(StreamEvent.Failed(ApiErrorMapper.describe(e)))
                }
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                // 0.2.0 Fix: 整个回调包一层兜底——此处任何未捕获异常都发生在
                // OkHttp 调度线程，会直接杀死进程且 UI 层无法捕获。
                runCatching { handleResponse(response) }
                    .onFailure { e ->
                        if (!finished) {
                            finished = true
                            trySend(
                                StreamEvent.Failed(
                                    ApiErrorMapper.describe(
                                        e as? IOException ?: IOException(e.message ?: e.javaClass.simpleName),
                                    ),
                                ),
                            )
                        }
                        close()
                    }
            }

            private fun handleResponse(response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        val body = runCatching { res.body?.string() }.getOrNull()
                        trySend(
                            StreamEvent.Failed(
                                ApiErrorMapper.describe(
                                    IOException("HTTP ${res.code}"),
                                    httpCode = res.code,
                                    rawBody = body,
                                ),
                            ),
                        )
                        close()
                        return
                    }

                    val source = res.body?.source()
                    if (source == null) {
                        trySend(StreamEvent.Failed("服务商返回了空响应。"))
                        close()
                        return
                    }

                    try {
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            val events = parser.feed(line + "\n")
                            for (event in events) {
                                if (event is StreamEvent.Completed) {
                                    if (!finished) {
                                        finished = true
                                        trySend(event)
                                    }
                                } else {
                                    trySend(event)
                                }
                            }
                            if (parser.completed) break
                        }
                        parser.flush().forEach { event ->
                            if (event is StreamEvent.Completed) {
                                if (!finished) {
                                    finished = true
                                    trySend(event)
                                }
                            } else {
                                trySend(event)
                            }
                        }
                        if (!finished) {
                            finished = true
                            trySend(StreamEvent.Completed)
                        }
                    } catch (e: Throwable) {
                        if (!finished) {
                            finished = true
                            // 0.2.0 Fix: 不只 IOException——解析器/类型转换抛出的
                            // RuntimeException 若逃逸到 OkHttp 回调线程会直接杀死进程。
                            // 截断或异常的流通常已交付可用文本，按完成处理。
                            trySend(StreamEvent.Completed)
                        }
                    } finally {
                        close()
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    /** One-shot connectivity probe used during onboarding and in settings. */
    suspend fun testConnection(
        provider: ProviderConfig,
        apiKey: String,
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val request = buildRequest(
            provider = provider,
            apiKey = apiKey,
            messages = listOf(RequestBodyFactory.OutboundMessage(Role.USER, "ping")),
            systemPrompt = "",
            temperature = 0f,
            topP = 1f,
            maxTokens = 8,
            stream = false,
        )

        try {
            client.newCall(request).execute().use { res ->
                val body = runCatching { res.body?.string() }.getOrNull().orEmpty()
                if (res.isSuccessful) {
                    ConnectionTestResult.Success(
                        latencyMs = System.currentTimeMillis() - started,
                        modelEcho = RequestBodyFactory.extractModelEcho(body),
                    )
                } else {
                    ConnectionTestResult.Failure(
                        message = ApiErrorMapper.describe(
                            IOException("HTTP ${res.code}"),
                            httpCode = res.code,
                            rawBody = body,
                        ),
                        httpCode = res.code,
                    )
                }
            }
        } catch (e: Exception) {
            ConnectionTestResult.Failure(ApiErrorMapper.describe(e))
        }
    }

    /**
     * One-shot image generation against the OpenAI-compatible
     * `POST {base}/v1/images/generations` endpoint. Works with any provider that
     * mirrors that shape (OpenAI, SiliconFlow, Zhipu CogView, DashScope compat…).
     */
    suspend fun generateImage(
        provider: ProviderConfig,
        apiKey: String,
        imageModel: String,
        prompt: String,
    ): Result<RequestBodyFactory.ImageReply> = withContext(Dispatchers.IO) {
        val url = EndpointResolver.resolveImages(provider.sanitizedBaseUrl)

        val request = Request.Builder()
            .url(url)
            .post(RequestBodyFactory.buildImageRequest(imageModel, prompt).toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .apply {
                when (provider.flavor) {
                    ApiFlavor.OPENAI -> header("Authorization", "Bearer $apiKey")
                    ApiFlavor.ANTHROPIC -> {
                        header("x-api-key", apiKey)
                        header("anthropic-version", "2023-06-01")
                    }
                    ApiFlavor.GEMINI -> header("x-goog-api-key", apiKey)
                }
            }
            .build()

        try {
            client.newCall(request).execute().use { res ->
                val body = runCatching { res.body?.string() }.getOrNull().orEmpty()
                if (!res.isSuccessful) {
                    return@use Result.failure(
                        IOException(
                            ApiErrorMapper.describe(
                                IOException("HTTP ${res.code}"),
                                httpCode = res.code,
                                rawBody = body,
                            ),
                        ),
                    )
                }
                val reply = RequestBodyFactory.extractImageReply(body)
                if (reply == null) {
                    Result.failure(IOException("服务商返回了无法解析的生图结果。"))
                } else {
                    Result.success(reply)
                }
            }
        } catch (e: Exception) {
            Result.failure(IOException(ApiErrorMapper.describe(e)))
        }
    }

    private fun buildRequest(
        provider: ProviderConfig,
        apiKey: String,
        messages: List<RequestBodyFactory.OutboundMessage>,
        systemPrompt: String,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        stream: Boolean,
        thinkingEnabled: Boolean = false,
        webSearchEnabled: Boolean = false,
    ): Request {
        val url = EndpointResolver.resolve(provider, stream)
        val payload = RequestBodyFactory.build(
            flavor = provider.flavor,
            model = provider.modelName,
            messages = messages,
            systemPrompt = systemPrompt,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            stream = stream,
            thinkingEnabled = thinkingEnabled,
            webSearchEnabled = webSearchEnabled,
        )

        return Request.Builder()
            .url(url)
            .post(payload.toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .apply {
                when (provider.flavor) {
                    ApiFlavor.OPENAI -> header("Authorization", "Bearer $apiKey")
                    ApiFlavor.ANTHROPIC -> {
                        header("x-api-key", apiKey)
                        header("anthropic-version", "2023-06-01")
                    }
                    ApiFlavor.GEMINI -> header("x-goog-api-key", apiKey)
                }
            }
            .build()
    }

    private companion object {
        /** Reasoning models can think for a long time before the first token. */
        const val READ_TIMEOUT_SECONDS = 300L
    }
}
