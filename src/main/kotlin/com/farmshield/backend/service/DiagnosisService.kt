package com.farmshield.backend.service

import com.farmshield.backend.model.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.Base64

class DiagnosisService(private val aiService: GenerativeAIService) {

    private val logger = LoggerFactory.getLogger(DiagnosisService::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    companion object {
        const val MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024 // 10MB
        val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/jpg", "image/png", "image/webp")

        private val SUPPORTED_LANGUAGES = mapOf(
            "en" to "English",
            "hi" to "Hindi",
            "hinglish" to "Hinglish (mix of Hindi and English)",
            "mr" to "Marathi"
        )

        val SYSTEM_INSTRUCTION = """
            You are KrishiShakti, an agricultural AI assistant designed to help Indian farmers identify possible crop health problems from images and farmer-provided context.

            Analyze the provided crop image carefully.

            Your task is NOT to claim a guaranteed diagnosis.

            Identify the most likely visible crop health issue based on the image and available context.

            Consider possible:
            - diseases
            - fungal infections
            - bacterial infections
            - viral symptoms
            - pest damage
            - nutrient deficiency
            - environmental stress
            - physical damage

            Assess the image quality honestly: "Good", "Acceptable", or "Poor".
            If the image is blurry, too dark, out of focus, or unclear, set imageQuality to "Poor", set issue to "Unable to reliably identify the problem", set confidence to "Low", and explain what additional information or a clearer close-up image is required.

            If the image does not contain a crop, leaf, fruit, stem, pest, or relevant agricultural subject, set imageQuality to "Poor", set issue to "Image is not suitable for crop diagnosis", and state that a crop or plant photo is required.

            Output ONLY a JSON object with EXACTLY this structure (no outside commentary, pure JSON):
            {
              "crop": "Identified or provided crop name",
              "issue": "Identified disease, pest, nutrient deficiency, or 'Healthy' or 'Unable to reliably identify'",
              "confidence": "High" | "Medium" | "Low",
              "confidenceScore": 0.0 to 0.95,
              "symptoms": ["visible symptom 1", "visible symptom 2"],
              "possibleCause": "farmer-friendly explanation of cause",
              "recommendedActions": ["immediate practical next step 1", "step 2"],
              "prevention": ["preventive practice 1", "practice 2"],
              "expertReviewRecommended": true | false,
              "expertReviewReason": "reason for expert review, or empty string",
              "imageQuality": "Good" | "Acceptable" | "Poor",
              "language": "English"
            }

            Use simple farmer-friendly language.
            Do not invent pesticide names, chemical dosages, government schemes, weather information, market prices, or scientific facts.
            Do not claim 100% certainty.
            Do not provide dangerous or irresponsible chemical instructions.
            If chemical treatment is mentioned, instruct the farmer to follow the product label and locally approved agricultural guidance.
            Prefer Integrated Pest Management (IPM) where appropriate.

            Respond in the requested language.
        """.trimIndent()
    }

    fun validateImage(imageBytes: ByteArray, mimeType: String?) {
        require(imageBytes.isNotEmpty()) { "Image data cannot be empty" }
        require(imageBytes.size <= MAX_IMAGE_SIZE_BYTES) {
            "Image size (${imageBytes.size / 1024} KB) exceeds maximum allowed limit of ${MAX_IMAGE_SIZE_BYTES / (1024 * 1024)} MB"
        }

        val normalizedMime = mimeType?.lowercase()?.trim() ?: "image/jpeg"
        require(normalizedMime in ALLOWED_MIME_TYPES) {
            "Unsupported image format '$mimeType'. Supported formats: JPG, PNG, WEBP."
        }
    }

    fun buildUserPrompt(request: DiagnosisAnalysisRequest): String {
        val langName = SUPPORTED_LANGUAGES[request.language.lowercase()] ?: "English"

        return buildString {
            appendLine("LANGUAGE: Respond in $langName.")
            appendLine("TASK: Analyze the attached crop image and diagnose potential crop health issues.")
            
            val hasContext = !request.crop.isNullOrBlank() ||
                !request.cropVariety.isNullOrBlank() ||
                !request.growthStage.isNullOrBlank() ||
                !request.location.isNullOrBlank() ||
                !request.soilType.isNullOrBlank() ||
                !request.weather.isNullOrBlank() ||
                !request.description.isNullOrBlank()

            if (hasContext) {
                appendLine("\n--- FARM AND CROP CONTEXT ---")
                request.crop?.takeIf { it.isNotBlank() }?.let { appendLine("Crop: $it") }
                request.cropVariety?.takeIf { it.isNotBlank() }?.let { appendLine("Crop Variety: $it") }
                request.growthStage?.takeIf { it.isNotBlank() }?.let { appendLine("Growth Stage: $it") }
                request.location?.takeIf { it.isNotBlank() }?.let { appendLine("Location: $it") }
                request.soilType?.takeIf { it.isNotBlank() }?.let { appendLine("Soil Type: $it") }
                request.weather?.takeIf { it.isNotBlank() }?.let { appendLine("Weather Conditions: $it") }
                request.description?.takeIf { it.isNotBlank() }?.let { 
                    val sanitized = it.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "").trim()
                    appendLine("Farmer's Observations / Query: $sanitized") 
                }
                appendLine("--- END CONTEXT ---")
            } else {
                appendLine("Note: No specific farm context provided by the farmer. Please identify the crop and visible symptoms directly from the image.")
            }
        }
    }

    suspend fun diagnoseImage(
        imageBytes: ByteArray,
        mimeType: String?,
        context: DiagnosisAnalysisRequest
    ): StructuredDiagnosis {
        validateImage(imageBytes, mimeType)

        val normalizedMime = mimeType?.lowercase()?.trim() ?: "image/jpeg"
        val imageBase64 = Base64.getEncoder().encodeToString(imageBytes)
        val userPrompt = buildUserPrompt(context)

        logger.info(
            "Sending crop image diagnosis request (crop='${context.crop ?: "unspecified"}', size=${imageBytes.size} bytes, mime=$normalizedMime)"
        )

        val rawResponse = aiService.analyzeImage(
            imageBase64 = imageBase64,
            mimeType = normalizedMime,
            prompt = userPrompt,
            systemInstruction = SYSTEM_INSTRUCTION
        )

        return parseDiagnosisResponse(rawResponse, context.crop)
    }

    fun parseDiagnosisResponse(rawJson: String, fallbackCrop: String?): StructuredDiagnosis {
        return try {
            val cleaned = rawJson.trim()
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val parsed = json.decodeFromString<StructuredDiagnosis>(cleaned)
            val cropName = if (parsed.crop.isNotBlank() && !parsed.crop.equals("Crop", ignoreCase = true)) {
                parsed.crop
            } else {
                fallbackCrop ?: "Crop"
            }
            val confidenceScore = when {
                parsed.confidenceScore > 0.0 -> parsed.confidenceScore
                parsed.confidence.equals("High", ignoreCase = true) -> 0.85
                parsed.confidence.equals("Low", ignoreCase = true) -> 0.35
                else -> 0.65
            }
            parsed.copy(
                crop = cropName,
                confidenceScore = confidenceScore
            )
        } catch (e: Exception) {
            logger.error("Failed to parse AI diagnosis response as structured JSON: ${e.message}")
            StructuredDiagnosis(
                crop = fallbackCrop ?: "Crop",
                issue = "Diagnosis analysis could not be fully parsed",
                confidence = "Low",
                confidenceScore = 0.30,
                symptoms = listOf("Symptoms could not be clearly extracted from the AI response."),
                possibleCause = "The AI service returned an unstructured response.",
                recommendedActions = listOf(
                    "Please try uploading another clear photo of the affected plant.",
                    "Consult your local Krishi Vigyan Kendra (KVK) for an in-person assessment."
                ),
                prevention = listOf("Regularly monitor crop foliage for signs of pests or lesions."),
                expertReviewRecommended = true,
                expertReviewReason = "AI diagnosis requires verification due to parsing uncertainty.",
                imageQuality = "Acceptable",
                language = "English"
            )
        }
    }
}
