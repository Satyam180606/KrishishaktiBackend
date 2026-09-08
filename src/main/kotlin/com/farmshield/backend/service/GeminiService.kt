package com.farmshield.backend.service

import com.farmshield.backend.config.AppConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

class GeminiService(
    private val config: AppConfig,
    httpClientOverride: HttpClient? = null
) : GenerativeAIService {

    private val logger = LoggerFactory.getLogger(GeminiService::class.java)

    private val httpClient = httpClientOverride ?: HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

    override suspend fun generateAdvisory(userPrompt: String, systemPrompt: String): String {
        val request = GeminiRequest(
            systemInstruction = Content(parts = listOf(Part(text = systemPrompt + "\nIMPORTANT: Return ONLY valid JSON."))),
            contents = listOf(
                Content(parts = listOf(Part(text = userPrompt)))
            ),
            generationConfig = GenerationConfig(responseMimeType = "application/json")
        )
        return executeGeminiRequest(request)
    }

    override suspend fun analyzeImage(
        imageBase64: String,
        mimeType: String,
        prompt: String,
        systemInstruction: String
    ): String {
        val request = GeminiRequest(
            systemInstruction = Content(parts = listOf(Part(text = systemInstruction + "\nIMPORTANT: Return ONLY valid JSON."))),
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = prompt),
                        Part(inlineData = InlineData(mimeType = mimeType, data = imageBase64))
                    )
                )
            ),
            generationConfig = GenerationConfig(responseMimeType = "application/json")
        )
        return executeGeminiRequest(request)
    }

    private suspend fun executeGeminiRequest(request: GeminiRequest): String {
        val model = config.aiModel
        val apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${config.aiApiKey}"
        logger.info("Sending request to Gemini model: $model")

        try {
            val response = httpClient.post(apiUrl) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status != HttpStatusCode.OK) {
                val errorBody = response.bodyAsText()
                logger.error("Gemini API error: ${response.status.value} - $errorBody")
                throw GenerativeApiException("Gemini API returned error: ${response.status.value}")
            }

            val geminiResponse = response.body<GeminiResponse>()
            val textContent = geminiResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text

            if (textContent.isNullOrBlank()) {
                logger.error("Gemini returned empty response")
                throw GenerativeApiException("Gemini returned an empty response")
            }

            return textContent

        } catch (e: Exception) {
            if (e is GenerativeApiException) throw e
            logger.error("Unexpected error calling Gemini: ${e.message}")
            throw GenerativeApiException("Failed to connect to the AI service.", e)
        }
    }

    override fun close() {
        httpClient.close()
    }
}

class GenerativeApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Serializable
data class GeminiRequest(
    val contents: List<Content>,
    val systemInstruction: Content? = null,
    val generationConfig: GenerationConfig? = null
)

@Serializable
data class GenerationConfig(
    val responseMimeType: String? = null
)

@Serializable
data class Content(
    val role: String? = null,
    val parts: List<Part>
)

@Serializable
data class Part(
    val text: String? = null,
    val inlineData: InlineData? = null
)

@Serializable
data class InlineData(
    val mimeType: String,
    val data: String
)

@Serializable
data class GeminiResponse(
    val candidates: List<Candidate>? = null
)

@Serializable
data class Candidate(
    val content: Content? = null
)
