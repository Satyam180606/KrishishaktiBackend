package com.farmshield.backend

import com.farmshield.backend.config.AppConfig
import com.farmshield.backend.routes.advisoryRoutes
import com.farmshield.backend.routes.diagnosisRoutes
import com.farmshield.backend.service.AdvisoryService
import com.farmshield.backend.service.DiagnosisService
import com.farmshield.backend.service.GeminiService
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun main() {
    val logger = LoggerFactory.getLogger("Application")
    val config = AppConfig.load()
    
    logger.info("Starting KrishiShakti Backend on port ${config.port}")
    logger.info("AI model: ${config.aiModel}")
    // Never log the API key
    
    val aiService = GeminiService(config)
    val advisoryService = AdvisoryService(aiService)
    val diagnosisService = DiagnosisService(aiService)
    
    embeddedServer(Netty, port = config.port) {
        configureServer(advisoryService, diagnosisService)
    }.start(wait = true)
}

fun Application.configureServer(
    advisoryService: AdvisoryService,
    diagnosisService: DiagnosisService? = null
) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    
    install(CORS) {
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowHeader(HttpHeaders.ContentType)
        anyHost() // Restrict in production
    }
    
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "Invalid request")))
        }
        exception<Exception> { call, cause ->
            val logger = LoggerFactory.getLogger("StatusPages")
            logger.error("Unhandled error: ${cause.javaClass.simpleName}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "An internal error occurred. Please try again later.")
            )
        }
    }
    
    advisoryRoutes(advisoryService)
    if (diagnosisService != null) {
        diagnosisRoutes(diagnosisService)
    }
}
