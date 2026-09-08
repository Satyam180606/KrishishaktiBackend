package com.farmshield.backend.routes

import com.farmshield.backend.model.AdvisoryRequest
import com.farmshield.backend.model.ErrorResponse
import com.farmshield.backend.service.AdvisoryService
import com.farmshield.backend.service.OpenRouterApiException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

fun Application.advisoryRoutes(advisoryService: AdvisoryService) {
    val logger = LoggerFactory.getLogger("AdvisoryRoutes")

    routing {
        get("/api/health") {
            call.respond(mapOf("status" to "ok", "service" to "KrishiShakti Backend"))
        }

        post("/api/advisory/query") {
            try {
                val request = call.receive<AdvisoryRequest>()
                val response = advisoryService.getAdvisory(request)
                call.respond(HttpStatusCode.OK, response)
            } catch (e: IllegalArgumentException) {
                logger.warn("Validation error: ${e.message}")
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "invalid_request", message = e.message ?: "Invalid request")
                )
            } catch (e: OpenRouterApiException) {
                logger.error("OpenRouter API error: ${e.message}")
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse(
                        error = "ai_service_unavailable",
                        message = "Our AI advisory service is temporarily unavailable. Please try again in a few minutes."
                    )
                )
            } catch (e: Exception) {
                logger.error("Unexpected error in advisory route", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(
                        error = "internal_error",
                        message = "An unexpected error occurred. Please try again later."
                    )
                )
            }
        }
    }
}
