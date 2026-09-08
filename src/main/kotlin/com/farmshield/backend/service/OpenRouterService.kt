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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerialName
import org.slf4j.LoggerFactory

interface GenerativeAIService {
    suspend fun generateAdvisory(userPrompt: String, systemPrompt: String): String
    suspend fun analyzeImage(imageBase64: String, mimeType: String, prompt: String, systemInstruction: String): String
    fun close()
}

class OpenRouterService(
    private val config: AppConfig,
    httpClientOverride: HttpClient? = null
) : GenerativeAIService {

    private val logger = LoggerFactory.getLogger(OpenRouterService::class.java)

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
        val request = OpenRouterRequest(
            model = config.openRouterModel,
            messages = listOf(
                OpenRouterMessage(role = "system", content = OpenRouterContent.TextContent(systemPrompt + "\nIMPORTANT: Return ONLY valid JSON.")),
                OpenRouterMessage(role = "user", content = OpenRouterContent.TextContent(userPrompt))
            ),
            // Some free models don't support response_format: { type: "json_object" }
            // So we rely on the system prompt for now.
            responseFormat = null 
        )

        return executeOpenRouterRequest(request)
    }

    override suspend fun analyzeImage(
        imageBase64: String,
        mimeType: String,
        prompt: String,
        systemInstruction: String
    ): String {
        val request = OpenRouterRequest(
            model = config.openRouterModel,
            messages = listOf(
                OpenRouterMessage(role = "system", content = OpenRouterContent.TextContent(systemInstruction + "\nIMPORTANT: Return ONLY valid JSON.")),
                OpenRouterMessage(
                    role = "user",
                    content = OpenRouterContent.ListContent(
                        listOf(
                            MessageContent(type = "text", text = prompt),
                            MessageContent(
                                type = "image_url",
                                imageUrl = ImageUrl(url = "data:$mimeType;base64,$imageBase64")
                            )
                        )
                    )
                )
            ),
            responseFormat = null
        )

        return executeOpenRouterRequest(request)
    }

    private suspend fun executeOpenRouterRequest(request: OpenRouterRequest): String {
        val apiUrl = "https://openrouter.ai/api/v1/chat/completions"
        logger.info("Sending request to OpenRouter model: ${request.model}")

        try {
            val response = httpClient.post(apiUrl) {
                header("Authorization", "Bearer ${config.openRouterApiKey}")
                header("HTTP-Referer", "https://farmshield.com")
                header("X-Title", "FarmShield")
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status != HttpStatusCode.OK) {
                val errorBody = response.bodyAsText()
                logger.error("OpenRouter API error: ${response.status.value} - $errorBody")
                throw OpenRouterApiException("OpenRouter API returned error: ${response.status.value}")
            }

            val openRouterResponse = response.body<OpenRouterResponse>()
            val textContent = openRouterResponse.choices.firstOrNull()?.message?.content

            if (textContent.isNullOrBlank()) {
                logger.error("OpenRouter returned empty response")
                throw OpenRouterApiException("OpenRouter returned an empty response")
            }

            return textContent

        } catch (e: Exception) {
            if (e is OpenRouterApiException) throw e
            logger.error("Unexpected error calling OpenRouter: ${e.message}")
            throw OpenRouterApiException("Failed to connect to the AI service.", e)
        }
    }

    override fun close() {
        httpClient.close()
    }
}

@Serializable
data class OpenRouterRequest(
    val model: String,
    val messages: List<OpenRouterMessage>,
    @SerialName("response_format")
    val responseFormat: ResponseFormat? = null
)

@Serializable
data class OpenRouterMessage(
    val role: String,
    val content: OpenRouterContent
)

@Serializable(with = OpenRouterContentSerializer::class)
sealed class OpenRouterContent {
    data class TextContent(val text: String) : OpenRouterContent()
    data class ListContent(val parts: List<MessageContent>) : OpenRouterContent()
}

object OpenRouterContentSerializer : KSerializer<OpenRouterContent> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("OpenRouterContent", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: OpenRouterContent) {
        val jsonEncoder = encoder as? JsonEncoder ?: throw SerializationException("Only JSON supported")
        when (value) {
            is OpenRouterContent.TextContent -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.text))
            is OpenRouterContent.ListContent -> {
                val array = buildJsonArray {
                    value.parts.forEach { part ->
                        addJsonObject {
                            put("type", part.type)
                            part.text?.let { put("text", it) }
                            part.imageUrl?.let { img ->
                                putJsonObject("image_url") {
                                    put("url", img.url)
                                }
                            }
                        }
                    }
                }
                jsonEncoder.encodeJsonElement(array)
            }
        }
    }

    override fun deserialize(decoder: Decoder): OpenRouterContent {
        throw UnsupportedOperationException("Deserialization not implemented for OpenRouterContent")
    }
}

@Serializable
data class MessageContent(
    val type: String,
    val text: String? = null,
    @SerialName("image_url")
    val imageUrl: ImageUrl? = null
)

@Serializable
data class ImageUrl(
    val url: String
)

@Serializable
data class ResponseFormat(
    val type: String
)

@Serializable
data class OpenRouterResponse(
    val choices: List<Choice>
)

@Serializable
data class Choice(
    val message: Message
)

@Serializable
data class Message(
    val content: String? = null
)

class OpenRouterApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
