package com.farmshield.backend.config

import io.github.cdimascio.dotenv.dotenv

data class AppConfig(
    val aiApiKey: String,
    val aiModel: String,
    val port: Int
) {
    companion object {
        fun load(): AppConfig {
            val dotenv = dotenv {
                ignoreIfMissing = true
            }
            
            val apiKey = dotenv["GEMINI_API_KEY"]
                ?: System.getenv("GEMINI_API_KEY")
                ?: throw IllegalStateException(
                    "GEMINI_API_KEY is not configured. Set it in .env file or as environment variable."
                )
            
            require(apiKey.isNotBlank() && apiKey != "your_gemini_api_key_here") {
                "GEMINI_API_KEY is not set to a valid value. Please configure your Gemini API key."
            }
            
            val model = dotenv["GEMINI_MODEL"]
                ?: System.getenv("GEMINI_MODEL")
                ?: "gemini-3.6-flash"
            
            val port = (dotenv["PORT"] ?: System.getenv("PORT") ?: "8080").toIntOrNull() ?: 8080
            
            return AppConfig(
                aiApiKey = apiKey,
                aiModel = model,
                port = port
            )
        }
    }
}
