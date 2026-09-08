package com.farmshield.backend.config

import io.github.cdimascio.dotenv.dotenv

data class AppConfig(
    val openRouterApiKey: String,
    val openRouterModel: String,
    val port: Int
) {
    companion object {
        fun load(): AppConfig {
            val dotenv = dotenv {
                ignoreIfMissing = true
            }
            
            val apiKey = dotenv["OPENROUTER_API_KEY"]
                ?: System.getenv("OPENROUTER_API_KEY")
                ?: throw IllegalStateException(
                    "OPENROUTER_API_KEY is not configured. Set it in .env file or as environment variable."
                )
            
            require(apiKey.isNotBlank() && apiKey != "your_openrouter_api_key_here") {
                "OPENROUTER_API_KEY is not set to a valid value. Please configure your OpenRouter API key."
            }
            
            val model = dotenv["OPENROUTER_MODEL"]
                ?: System.getenv("OPENROUTER_MODEL")
                ?: "nvidia/nemotron-3.5-lightning:free"
            
            val port = (dotenv["PORT"] ?: System.getenv("PORT") ?: "8080").toIntOrNull() ?: 8080
            
            return AppConfig(
                openRouterApiKey = apiKey,
                openRouterModel = model,
                port = port
            )
        }
    }
}
