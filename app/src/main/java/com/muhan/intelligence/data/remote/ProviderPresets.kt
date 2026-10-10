package com.muhan.intelligence.data.remote

import com.muhan.intelligence.domain.model.ApiFlavor

/**
 * A curated catalogue of known-good provider presets.
 *
 * Users can always enter a custom endpoint, but one-tap presets remove the most
 * common source of setup failure (guessing the Base URL and model id).
 */
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val flavor: ApiFlavor,
    val modelSuggestions: List<String>,
    val description: String,
    val apiKeyHint: String,
    val keyUrl: String,
)

object ProviderPresets {

    val all: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "deepseek",
            displayName = "DeepSeek（深度求索）",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-flash",
            flavor = ApiFlavor.OPENAI,
            modelSuggestions = listOf("deepseek-flash", "deepseek-chat", "deepseek-reasoner"),
            description = "官方 API，兼容 OpenAI 协议，性价比高",
            apiKeyHint = "sk-...",
            keyUrl = "https://platform.deepseek.com/api_keys",
        ),
        ProviderPreset(
            id = "moonshot",
            displayName = "Kimi（月之暗面）",
            baseUrl = "https://api.moonshot.cn/v1",
            defaultModel = "moonshot-v1-8k",
            flavor = ApiFlavor.OPENAI,
            modelSuggestions = listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
            description = "长文本能力强，支持 128K 上下文",
            apiKeyHint = "sk-...",
            keyUrl = "https://platform.moonshot.cn/console/api-keys",
        ),
        ProviderPreset(
            id = "zhipu",
            displayName = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            defaultModel = "glm-4-plus",
            flavor = ApiFlavor.OPENAI,
            modelSuggestions = listOf("glm-4-plus", "glm-4-air", "glm-4-flash"),
            description = "国产大模型，glm-4-flash 提供免费额度",
            apiKeyHint = "填写 API Key",
            keyUrl = "https://open.bigmodel.cn/usercenter/apikeys",
        ),
        ProviderPreset(
            id = "dashscope",
            displayName = "通义千问（阿里云百炼）",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen-plus",
            flavor = ApiFlavor.OPENAI,
            modelSuggestions = listOf("qwen-plus", "qwen-turbo", "qwen-max"),
            description = "阿里云百炼兼容模式，模型选择丰富",
            apiKeyHint = "sk-...",
            keyUrl = "https://bailian.console.aliyun.com/",
        ),
        ProviderPreset(
            id = "siliconflow",
            displayName = "SiliconFlow 硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            defaultModel = "deepseek-ai/DeepSeek-V3",
            flavor = ApiFlavor.OPENAI,
            modelSuggestions = listOf(
                "deepseek-ai/DeepSeek-V3",
                "deepseek-ai/DeepSeek-R1",
                "Qwen/Qwen2.5-72B-Instruct",
            ),
            description = "聚合多家开源模型，含免费额度",
            apiKeyHint = "sk-...",
            keyUrl = "https://cloud.siliconflow.cn/account/ak",
        ),
        ProviderPreset(
            id = "openai",
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
            flavor = ApiFlavor.OPENAI,
            modelSuggestions = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "o4-mini"),
            description = "官方接口，需自备网络环境",
            apiKeyHint = "sk-...",
            keyUrl = "https://platform.openai.com/api-keys",
        ),
        ProviderPreset(
            id = "anthropic",
            displayName = "Anthropic Claude",
            baseUrl = "https://api.anthropic.com",
            defaultModel = "claude-3-5-sonnet-latest",
            flavor = ApiFlavor.ANTHROPIC,
            modelSuggestions = listOf(
                "claude-3-5-sonnet-latest",
                "claude-3-5-haiku-latest",
                "claude-3-7-sonnet-latest",
            ),
            description = "原生 Messages API，长文本与代码能力突出",
            apiKeyHint = "sk-ant-...",
            keyUrl = "https://console.anthropic.com/settings/keys",
        ),
        ProviderPreset(
            id = "gemini",
            displayName = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com",
            defaultModel = "gemini-2.0-flash",
            flavor = ApiFlavor.GEMINI,
            modelSuggestions = listOf("gemini-2.0-flash", "gemini-2.5-flash", "gemini-2.5-pro"),
            description = "Google 原生接口，多模态能力强",
            apiKeyHint = "AIza...",
            keyUrl = "https://aistudio.google.com/app/apikey",
        ),
        ProviderPreset(
            id = "ollama",
            displayName = "Ollama（本地部署）",
            baseUrl = "http://127.0.0.1:11434/v1",
            defaultModel = "qwen2.5:7b",
            flavor = ApiFlavor.OPENAI,
            modelSuggestions = listOf("qwen2.5:7b", "llama3.1:8b", "deepseek-r1:7b"),
            description = "本地运行，数据不出设备；API Key 可随意填写",
            apiKeyHint = "ollama",
            keyUrl = "https://ollama.com/download",
        ),
    )

    fun byId(id: String): ProviderPreset? = all.firstOrNull { it.id == id }

    /** Inference used by the custom-endpoint flow to guess the right protocol. */
    fun guessFlavor(baseUrl: String): ApiFlavor {
        val url = baseUrl.lowercase()
        return when {
            url.contains("anthropic") -> ApiFlavor.ANTHROPIC
            url.contains("generativelanguage.googleapis.com") -> ApiFlavor.GEMINI
            url.contains(":generatecontent") || url.contains(":streamgeneratecontent") -> ApiFlavor.GEMINI
            else -> ApiFlavor.OPENAI
        }
    }
}
