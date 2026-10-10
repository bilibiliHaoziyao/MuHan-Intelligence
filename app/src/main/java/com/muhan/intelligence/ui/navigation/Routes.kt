package com.muhan.intelligence.ui.navigation

/** Every destination in the app. Centralised so deep links and args stay in sync. */
object Routes {
    const val ARG_CONVERSATION_ID = "conversationId"
    const val ARG_PROVIDER_ID = "providerId"

    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val PROVIDERS = "settings/providers"
    const val PROVIDER_EDITOR = "settings/providers/edit"
    const val GENERATION = "settings/generation"
    const val LOGS = "settings/logs"
    const val ABOUT = "settings/about"

    fun chat(conversationId: String? = null): String =
        if (conversationId.isNullOrBlank()) CHAT else "$CHAT/$conversationId"

    fun providerEditor(providerId: String? = null): String =
        if (providerId.isNullOrBlank()) PROVIDER_EDITOR else "$PROVIDER_EDITOR/$providerId"
}
