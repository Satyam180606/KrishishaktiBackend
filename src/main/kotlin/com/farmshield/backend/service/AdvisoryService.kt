package com.farmshield.backend.service

import com.farmshield.backend.model.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class AdvisoryService(private val aiService: GenerativeAIService) {

    private val logger = LoggerFactory.getLogger(AdvisoryService::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    companion object {
        private val SUPPORTED_LANGUAGES = mapOf(
            "en" to "English",
            "hi" to "Hindi",
            "hinglish" to "Hinglish (mix of Hindi and English)",
            "mr" to "Marathi"
        )

        val SYSTEM_PROMPT = """
            You are KrishiShakti, an AI agricultural advisory assistant designed for Indian farmers.

            Your purpose is to help farmers understand crop-health problems and make safer, more informed agricultural decisions.

            Always consider the farmer's provided context before answering.

            Never claim certainty when the available information is insufficient.

            For crop disease or pest-related questions:
            - identify the most likely possibilities
            - explain the reasoning in simple language
            - provide practical next steps
            - mention preventive measures
            - recommend expert verification when confidence is low or the situation is serious

            Do not invent:
            - pesticide names
            - pesticide dosages
            - government schemes
            - scientific facts
            - weather information
            - market prices
            - agricultural statistics

            Only use information provided in the request or general model knowledge.
            Do not pretend to have live information.

            For chemical recommendations, prioritize safety and instruct farmers to follow the product label and locally approved agricultural guidance.

            Prefer Integrated Pest Management practices.

            Use simple language suitable for farmers.

            Return the answer in the requested language.
        """.trimIndent()
    }

    fun validateRequest(request: AdvisoryRequest) {
        require(request.query.isNotBlank()) { "Query cannot be empty" }
        require(request.query.length <= 2000) { "Query is too long. Maximum 2000 characters allowed." }
        
        val lang = request.language.lowercase()
        require(lang in SUPPORTED_LANGUAGES) {
            "Unsupported language '${request.language}'. Supported: ${SUPPORTED_LANGUAGES.keys.joinToString()}"
        }
    }

    fun sanitizeInput(input: String): String {
        return input
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
            .trim()
    }

    fun buildUserPrompt(request: AdvisoryRequest): String {
        val langName = SUPPORTED_LANGUAGES[request.language.lowercase()] ?: "English"
        
        val contextBlock = buildString {
            request.farmContext?.let { ctx ->
                appendLine("\n--- FARMER'S FARM CONTEXT ---")
                ctx.crop?.let { appendLine("Crop: $it") }
                ctx.variety?.let { appendLine("Crop Variety: $it") }
                ctx.stage?.let { appendLine("Growth Stage: $it") }
                ctx.location?.let { appendLine("Location: $it") }
                ctx.soilType?.let { appendLine("Soil Type: $it") }
                ctx.irrigationType?.let { appendLine("Irrigation: $it") }
                ctx.area?.let { area ->
                    val u = ctx.unit ?: "acres"
                    appendLine("Farm Area: $area $u")
                }
                appendLine("--- END CONTEXT ---")
            }
        }

        return buildString {
            appendLine("LANGUAGE: Respond in ${langName}.")
            appendLine()
            appendLine("FARMER'S QUESTION: ${sanitizeInput(request.query)}")
            if (contextBlock.isNotBlank()) {
                append(contextBlock)
            }
        }
    }

    suspend fun getAdvisory(request: AdvisoryRequest): AdvisoryResponse {
        validateRequest(request)

        val userPrompt = buildUserPrompt(request)
        logger.info("Processing advisory request (language=${request.language}, crop=${request.farmContext?.crop ?: "not specified"})")

        val rawResponse = aiService.generateAdvisory(userPrompt, SYSTEM_PROMPT)

        return parseAdvisoryResponse(rawResponse)
    }

    fun parseAdvisoryResponse(rawJson: String): AdvisoryResponse {
        return try {
            val cleaned = rawJson.trim()
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val parsed = json.decodeFromString<AdvisoryResponse>(cleaned)
            // Validate that essential fields are not empty
            require(parsed.problem.isNotBlank()) { "Missing problem field" }
            require(parsed.actions.isNotEmpty()) { "Missing actions" }
            parsed
        } catch (e: Exception) {
            logger.error("Failed to parse AI response as structured advisory: ${e.message}")
            // Fallback: create a best-effort response
            AdvisoryResponse(
                problem = "I received your question but had difficulty formatting a structured response.",
                likelyCause = "The AI response could not be properly parsed.",
                actions = listOf(
                    "Please try rephrasing your question.",
                    "Contact your local Krishi Vigyan Kendra (KVK) for immediate assistance."
                ),
                prevention = emptyList(),
                expertHelp = ExpertHelp(recommended = true, reason = "AI response was unclear. Expert consultation is recommended."),
                confidence = "low"
            )
        }
    }
}
