package com.farmshield.backend.model

import kotlinx.serialization.Serializable

@Serializable
data class FarmContext(
    val crop: String? = null,
    val variety: String? = null,
    val stage: String? = null,
    val location: String? = null,
    val soilType: String? = null,
    val irrigationType: String? = null,
    val area: Double? = null,
    val unit: String? = null
)

@Serializable
data class AdvisoryRequest(
    val query: String,
    val language: String = "en",
    val farmContext: FarmContext? = null
)

@Serializable
data class ExpertHelp(
    val recommended: Boolean = false,
    val reason: String = ""
)

@Serializable
data class AdvisoryResponse(
    val problem: String,
    val likelyCause: String,
    val actions: List<String>,
    val prevention: List<String>,
    val expertHelp: ExpertHelp,
    val confidence: String,
    val disclaimer: String = "This is AI-generated guidance. Always verify with local agricultural experts and follow approved product labels for any chemical treatments."
)

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String
)

@Serializable
data class StructuredDiagnosis(
    val crop: String = "Crop",
    val issue: String,
    val confidence: String = "Medium",
    val confidenceScore: Double = 0.0,
    val symptoms: List<String> = emptyList(),
    val possibleCause: String = "",
    val recommendedActions: List<String> = emptyList(),
    val prevention: List<String> = emptyList(),
    val expertReviewRecommended: Boolean = false,
    val expertReviewReason: String = "",
    val imageQuality: String = "Good",
    val language: String = "English"
)

@Serializable
data class DiagnosisAnalysisRequest(
    val crop: String? = null,
    val cropVariety: String? = null,
    val growthStage: String? = null,
    val location: String? = null,
    val soilType: String? = null,
    val weather: String? = null,
    val description: String? = null,
    val language: String = "en",
    val imageBase64: String? = null,
    val mimeType: String? = null
)

@Serializable
data class DiagnosisApiResponse(
    val success: Boolean,
    val diagnosis: StructuredDiagnosis? = null,
    val error: String? = null,
    val message: String? = null
)
