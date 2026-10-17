package com.muhan.intelligence.ui.navigation

/** Every destination in the app. Centralised so deep links and args stay in sync. */
object Routes {
    const val ARG_CONVERSATION_ID = "conversationId"
    const val ARG_PROVIDER_ID = "providerId"

    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"

    /**
     * 会话路由采用「单一路由 + 可选参数」：不论新建会话还是打开已有会话，
     * 都走同一条 `chat?conversationId={...}`。这样 `popUpTo(CHAT_ROUTE)` 才能
     * 稳定命中并出栈当前会话，切换/新建会话是「替换」而非「堆叠」。
     *
     * 旧实现用了 `chat` 与 `chat/{conversationId}` 两条路由，但
     * `popUpTo("chat")`（无参路由字符串）在 Navigation 2.8 中无法匹配带参路由
     * `chat/{conversationId}`，导致每次切换都把新会话压在旧会话之上，多份
     * ChatViewModel 共存、流式覆盖层与生成任务互相干扰，表现为「切换后记录错乱/
     * 消失 + 闪退」。
     */
    const val CHAT = "chat"
    const val CHAT_ROUTE = "$CHAT?$ARG_CONVERSATION_ID={$ARG_CONVERSATION_ID}"
    const val CHAT_START = CHAT_ROUTE

    /** 3.0.0：Agent 模式与 Chat 平级，会话独立。 */
    const val AGENT = "agent"
    const val AGENT_ROUTE = "$AGENT?$ARG_CONVERSATION_ID={$ARG_CONVERSATION_ID}"

    const val SETTINGS = "settings"
    const val PROVIDERS = "settings/providers"
    const val PROVIDER_EDITOR = "settings/providers/edit"
    const val LOGS = "settings/logs"
    const val ABOUT = "settings/about"

    fun chat(conversationId: String? = null): String =
        if (conversationId.isNullOrBlank()) CHAT else "$CHAT?$ARG_CONVERSATION_ID=$conversationId"

    fun agent(conversationId: String? = null): String =
        if (conversationId.isNullOrBlank()) AGENT else "$AGENT?$ARG_CONVERSATION_ID=$conversationId"

    fun providerEditor(providerId: String? = null): String =
        if (providerId.isNullOrBlank()) PROVIDER_EDITOR else "$PROVIDER_EDITOR/$providerId"
}
