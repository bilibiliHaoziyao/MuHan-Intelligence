package com.muhan.intelligence.data.remote

import android.util.Base64
import com.muhan.intelligence.BuildConfig
import com.muhan.intelligence.data.local.LogRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音能力数据源（2.0.0 Beta）。
 *
 * MiMo 的语音识别（mimo-v2.5-asr）与语音合成（mimo-v2.5-tts）都走
 * OpenAI 兼容的 `/v1/chat/completions`，与 LLM 共用同一套 base_url 与 API Key
 * （即内置「慕寒智能」的接入信息），因此这里直接读 BuildConfig 里的内置凭据。
 */
@Singleton
class AudioRemoteDataSource @Inject constructor(
    private val logRepository: LogRepository,
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val apiKey: String
        get() = BuildConfig.MHI_API_KEY_A + BuildConfig.MHI_API_KEY_B

    private val endpoint: String
        get() = BuildConfig.MHI_BASE_URL.trimEnd('/') + "/chat/completions"

    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && BuildConfig.MHI_BASE_URL.isNotBlank()

    /** 语音识别：PCM WAV 音频 → 文本。language 取值 auto/zh/en。 */
    suspend fun transcribe(wavBytes: ByteArray, language: String = "auto"): String {
        val dataUrl = "data:audio/wav;base64," + Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val body = buildJsonObject {
            put("model", ASR_MODEL)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "input_audio")
                            putJsonObject("input_audio") { put("data", dataUrl) }
                        }
                    }
                }
            }
            putJsonObject("asr_options") { put("language", language) }
        }.toString()

        val response = execute(body)
        val text = runCatching {
            response["choices"]!!.jsonArray[0].jsonObject["message"]!!
                .jsonObject["content"]!!.jsonPrimitive.content
        }.getOrNull().orEmpty()

        logRepository.info(
            "Audio",
            "ASR 完成，识别字数=${text.length}（lang=$language）",
        )
        return text
    }

    /** 语音合成：文本 → WAV 字节。voice 为预置音色 id。 */
    suspend fun synthesize(text: String, voice: String): ByteArray {
        val body = buildJsonObject {
            put("model", TTS_MODEL)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", "用自然、清晰的语气朗读下面的内容")
                }
                addJsonObject {
                    put("role", "assistant")
                    put("content", text)
                }
            }
            putJsonObject("audio") {
                put("format", "wav")
                put("voice", voice)
            }
        }.toString()

        val response = execute(body)
        val b64 = runCatching {
            response["choices"]!!.jsonArray[0].jsonObject["message"]!!
                .jsonObject["audio"]!!.jsonObject["data"]!!.jsonPrimitive.content
        }.getOrNull().orEmpty()
        if (b64.isBlank()) throw IllegalStateException("语音合成未返回音频数据")

        logRepository.info("Audio", "TTS 完成，音频字节=${b64.length / 4 * 3}（voice=$voice）")
        return Base64.decode(b64, Base64.NO_WRAP)
    }

    private suspend fun execute(body: String): kotlinx.serialization.json.JsonObject {
        if (!isConfigured) throw IllegalStateException("尚未配置内置语音服务")

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody(jsonMedia))
            .build()

        client.newCall(request).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                logRepository.warn("Audio", "语音接口 HTTP ${res.code} body=${raw.take(400)}")
                throw IllegalStateException("语音服务返回错误（HTTP ${res.code}）")
            }
            val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }
                .getOrElse {
                    throw IllegalStateException("语音服务响应解析失败")
                }
            return parsed
        }
    }

    private companion object {
        const val ASR_MODEL = "mimo-v2.5-asr"
        const val TTS_MODEL = "mimo-v2.5-tts"
    }
}
