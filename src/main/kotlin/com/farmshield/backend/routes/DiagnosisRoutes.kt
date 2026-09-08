package com.farmshield.backend.routes

import com.farmshield.backend.model.*
import com.farmshield.backend.service.DiagnosisService
import com.farmshield.backend.service.OpenRouterApiException
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.toByteArray
import org.slf4j.LoggerFactory
import java.util.Base64

fun Application.diagnosisRoutes(diagnosisService: DiagnosisService) {
    val logger = LoggerFactory.getLogger("DiagnosisRoutes")

    routing {
        suspend fun handleDiagnosis(call: ApplicationCall) {
            try {
                val contentType = call.request.contentType()
                var imageBytes: ByteArray? = null
                var mimeType: String? = null
                var context = DiagnosisAnalysisRequest()

                if (contentType.match(ContentType.MultiPart.FormData)) {
                    val multipart = call.receiveMultipart()
                    var crop: String? = null
                    var cropVariety: String? = null
                    var growthStage: String? = null
                    var location: String? = null
                    var soilType: String? = null
                    var weather: String? = null
                    var description: String? = null
                    var language = "en"

                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FileItem -> {
                                if (part.name == "image" || part.name == "file") {
                                    mimeType = part.contentType?.toString()
                                    imageBytes = part.provider().toByteArray()
                                }
                            }
                            is PartData.FormItem -> {
                                when (part.name) {
                                    "crop" -> crop = part.value.takeIf { it.isNotBlank() }
                                    "cropVariety", "variety" -> cropVariety = part.value.takeIf { it.isNotBlank() }
                                    "growthStage", "stage" -> growthStage = part.value.takeIf { it.isNotBlank() }
                                    "location" -> location = part.value.takeIf { it.isNotBlank() }
                                    "soilType" -> soilType = part.value.takeIf { it.isNotBlank() }
                                    "weather" -> weather = part.value.takeIf { it.isNotBlank() }
                                    "description", "notes", "query" -> description = part.value.takeIf { it.isNotBlank() }
                                    "language" -> language = part.value.ifBlank { "en" }
                                }
                            }
                            else -> {}
                        }
                        part.dispose()
                    }

                    context = DiagnosisAnalysisRequest(
                        crop = crop,
                        cropVariety = cropVariety,
                        growthStage = growthStage,
                        location = location,
                        soilType = soilType,
                        weather = weather,
                        description = description,
                        language = language
                    )
                } else {
                    // Assume JSON body
                    val req = call.receive<DiagnosisAnalysisRequest>()
                    context = req
                    if (!req.imageBase64.isNullOrBlank()) {
                        imageBytes = try {
                            Base64.getDecoder().decode(req.imageBase64)
                        } catch (e: Exception) {
                            throw IllegalArgumentException("Invalid base64 image data")
                        }
                        mimeType = req.mimeType ?: "image/jpeg"
                    }
                }

                if (imageBytes == null || imageBytes.isEmpty()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        DiagnosisApiResponse(
                            success = false,
                            error = "missing_image",
                            message = "No crop image was provided. Please upload or capture an image of the affected plant."
                        )
                    )
                    return
                }

                val diagnosis = diagnosisService.diagnoseImage(imageBytes, mimeType, context)
                call.respond(HttpStatusCode.OK, DiagnosisApiResponse(success = true, diagnosis = diagnosis))

            } catch (e: IllegalArgumentException) {
                logger.warn("Validation error in diagnosis request: ${e.message}")
                call.respond(
                    HttpStatusCode.BadRequest,
                    DiagnosisApiResponse(
                        success = false,
                        error = "invalid_request",
                        message = e.message ?: "Invalid diagnosis request"
                    )
                )
            } catch (e: OpenRouterApiException) {
                logger.error("OpenRouter API error during diagnosis: ${e.message}")
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    DiagnosisApiResponse(
                        success = false,
                        error = "ai_service_unavailable",
                        message = "The AI diagnosis service is temporarily unavailable. Please try again in a few minutes."
                    )
                )
            } catch (e: Exception) {
                logger.error("Unexpected error in diagnosis endpoint", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    DiagnosisApiResponse(
                        success = false,
                        error = "internal_error",
                        message = "An unexpected error occurred while analyzing the image. Please try again."
                    )
                )
            }
        }

        post("/api/diagnosis/analyze") {
            handleDiagnosis(call)
        }

        post("/api/diagnosis") {
            handleDiagnosis(call)
        }
    }
}
