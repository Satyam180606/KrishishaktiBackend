package com.farmshield.backend

import com.farmshield.backend.model.*
import com.farmshield.backend.service.AdvisoryService
import com.farmshield.backend.service.GenerativeAIService
import com.farmshield.backend.service.OpenRouterApiException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdvisoryRoutesTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun createTestService(response: String? = null, shouldThrow: Boolean = false): AdvisoryService {
        val mockAI = object : GenerativeAIService {
            override suspend fun generateAdvisory(userPrompt: String, systemPrompt: String): String {
                if (shouldThrow) throw OpenRouterApiException("OpenRouter is down")
                return response ?: """
                    {
                        "problem": "Test problem",
                        "likelyCause": "Test cause",
                        "actions": ["Action 1", "Action 2"],
                        "prevention": ["Prevention 1"],
                        "expertHelp": { "recommended": false, "reason": "" },
                        "confidence": "high"
                    }
                """.trimIndent()
            }
            override suspend fun analyzeImage(imageBase64: String, mimeType: String, prompt: String, systemInstruction: String): String = ""
            override fun close() {}
        }
        return AdvisoryService(mockAI)
    }

    @Test
    fun `health endpoint returns ok`() = testApplication {
        application { configureServer(createTestService()) }

        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ok"))
    }

    @Test
    fun `advisory endpoint returns 200 with valid request`() = testApplication {
        application { configureServer(createTestService()) }

        val response = client.post("/api/advisory/query") {
            contentType(ContentType.Application.Json)
            setBody("""{ "query": "My tomato leaves are yellow", "language": "en" }""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("problem"))
        assertTrue(body.contains("Test problem"))
    }

    @Test
    fun `advisory endpoint returns 400 for empty query`() = testApplication {
        application { configureServer(createTestService()) }

        val response = client.post("/api/advisory/query") {
            contentType(ContentType.Application.Json)
            setBody("""{ "query": "", "language": "en" }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `advisory endpoint returns 503 when AI service is unavailable`() = testApplication {
        application { configureServer(createTestService(shouldThrow = true)) }

        val response = client.post("/api/advisory/query") {
            contentType(ContentType.Application.Json)
            setBody("""{ "query": "Help me with my crop", "language": "en" }""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("ai_service_unavailable"))
        // Must not expose internal error messages
        assertTrue(!body.contains("OpenRouter is down"))
    }

    @Test
    fun `advisory endpoint with full farm context`() = testApplication {
        application { configureServer(createTestService()) }

        val response = client.post("/api/advisory/query") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "query": "My crop has yellow spots",
                    "language": "hi",
                    "farmContext": {
                        "crop": "Wheat",
                        "variety": "PBW 343",
                        "stage": "Vegetative",
                        "location": "Meerut, UP",
                        "soilType": "Loamy",
                        "irrigationType": "Tube Well",
                        "area": 2.5,
                        "unit": "acres"
                    }
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `internal server error returns safe message`() = testApplication {
        val brokenService = object : GenerativeAIService {
            override suspend fun generateAdvisory(userPrompt: String, systemPrompt: String): String {
                throw RuntimeException("Database connection failed on port 5432")
            }
            override suspend fun analyzeImage(imageBase64: String, mimeType: String, prompt: String, systemInstruction: String): String = ""
            override fun close() {}
        }

        application { configureServer(AdvisoryService(brokenService)) }

        val response = client.post("/api/advisory/query") {
            contentType(ContentType.Application.Json)
            setBody("""{ "query": "Help me", "language": "en" }""")
        }

        // Should not expose internal error details
        val body = response.bodyAsText()
        assertTrue(!body.contains("Database"))
        assertTrue(!body.contains("5432"))
    }
}
