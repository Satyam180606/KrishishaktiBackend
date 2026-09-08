package com.farmshield.backend

import com.farmshield.backend.model.*
import com.farmshield.backend.service.AdvisoryService
import com.farmshield.backend.service.DiagnosisService
import com.farmshield.backend.service.OpenRouterApiException
import com.farmshield.backend.service.GenerativeAIService
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiagnosisRoutesTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun createTestServices(
        diagnosisResponse: String? = null,
        shouldThrow: Boolean = false
    ): Pair<AdvisoryService, DiagnosisService> {
        val mockAI = object : GenerativeAIService {
            override suspend fun generateAdvisory(userPrompt: String, systemPrompt: String): String = "{}"
            override suspend fun analyzeImage(
                imageBase64: String,
                mimeType: String,
                prompt: String,
                systemInstruction: String
            ): String {
                if (shouldThrow) throw OpenRouterApiException("AI service is unavailable")
                return diagnosisResponse ?: """
                    {
                        "crop": "Tomato",
                        "issue": "Early Blight",
                        "confidence": "High",
                        "confidenceScore": 0.85,
                        "symptoms": ["Brown circular leaf spots", "Yellow halos"],
                        "possibleCause": "Alternaria solani fungal infection",
                        "recommendedActions": ["Prune lower leaves", "Apply copper fungicide per label"],
                        "prevention": ["Avoid overhead watering", "Ensure good airflow"],
                        "expertReviewRecommended": true,
                        "expertReviewReason": "Verify if symptoms overlap with Septoria leaf spot",
                        "imageQuality": "Good",
                        "language": "English"
                    }
                """.trimIndent()
            }
            override fun close() {}
        }
        return Pair(AdvisoryService(mockAI), DiagnosisService(mockAI))
    }

    @Test
    fun `diagnosis endpoint returns 200 with multipart request`() = testApplication {
        val (advisoryService, diagnosisService) = createTestServices()
        application { configureServer(advisoryService, diagnosisService) }

        val dummyImageBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        val response = client.submitFormWithBinaryData(
            url = "/api/diagnosis/analyze",
            formData = formData {
                append("crop", "Tomato")
                append("location", "Maharashtra")
                append("growthStage", "Flowering")
                append("description", "Spots on leaves")
                append(
                    "image",
                    dummyImageBytes,
                    Headers.build {
                        append(HttpHeaders.ContentType, "image/jpeg")
                        append(HttpHeaders.ContentDisposition, "filename=\"crop.jpg\"")
                    }
                )
            }
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val parsed = json.decodeFromString<DiagnosisApiResponse>(body)
        assertTrue(parsed.success)
        assertEquals("Early Blight", parsed.diagnosis?.issue)
        assertEquals("Tomato", parsed.diagnosis?.crop)
    }

    @Test
    fun `diagnosis endpoint returns 200 with json request`() = testApplication {
        val (advisoryService, diagnosisService) = createTestServices()
        application { configureServer(advisoryService, diagnosisService) }

        val base64Img = Base64.getEncoder().encodeToString(byteArrayOf(10, 20, 30, 40))

        val response = client.post("/api/diagnosis/analyze") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "crop": "Tomato",
                    "location": "Pune",
                    "growthStage": "Fruiting",
                    "description": "Leaf spots",
                    "imageBase64": "$base64Img",
                    "mimeType": "image/jpeg"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val parsed = json.decodeFromString<DiagnosisApiResponse>(body)
        assertTrue(parsed.success)
        assertEquals("Early Blight", parsed.diagnosis?.issue)
    }

    @Test
    fun `diagnosis endpoint returns 400 for missing image`() = testApplication {
        val (advisoryService, diagnosisService) = createTestServices()
        application { configureServer(advisoryService, diagnosisService) }

        val response = client.post("/api/diagnosis/analyze") {
            contentType(ContentType.Application.Json)
            setBody("""{ "crop": "Wheat" }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("missing_image"))
    }

    @Test
    fun `diagnosis endpoint returns 400 for unsupported mime type`() = testApplication {
        val (advisoryService, diagnosisService) = createTestServices()
        application { configureServer(advisoryService, diagnosisService) }

        val base64Img = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))

        val response = client.post("/api/diagnosis/analyze") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "crop": "Tomato",
                    "imageBase64": "$base64Img",
                    "mimeType": "application/pdf"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("invalid_request"))
    }

    @Test
    fun `diagnosis endpoint returns 503 when AI service is unavailable`() = testApplication {
        val (advisoryService, diagnosisService) = createTestServices(shouldThrow = true)
        application { configureServer(advisoryService, diagnosisService) }

        val base64Img = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))

        val response = client.post("/api/diagnosis/analyze") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "crop": "Tomato",
                    "imageBase64": "$base64Img",
                    "mimeType": "image/jpeg"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("ai_service_unavailable"))
        // Must never leak internal exception message
        assertTrue(!body.contains("AI service is unavailable"))
    }

    @Test
    fun `diagnosis endpoint handles poor image quality honestly`() = testApplication {
        val poorQualityResponse = """
            {
                "crop": "Unknown",
                "issue": "Unable to reliably identify the problem",
                "confidence": "Low",
                "confidenceScore": 0.20,
                "symptoms": ["Image is blurry and out of focus"],
                "possibleCause": "Insufficient visual detail to assess plant pathology",
                "recommendedActions": ["Upload a clearer close-up image of affected leaves in good natural light"],
                "prevention": ["Regular crop inspections"],
                "expertReviewRecommended": false,
                "expertReviewReason": "Clearer image required before expert review",
                "imageQuality": "Poor",
                "language": "English"
            }
        """.trimIndent()

        val (advisoryService, diagnosisService) = createTestServices(diagnosisResponse = poorQualityResponse)
        application { configureServer(advisoryService, diagnosisService) }

        val base64Img = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))

        val response = client.post("/api/diagnosis/analyze") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "imageBase64": "$base64Img",
                    "mimeType": "image/jpeg"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val parsed = json.decodeFromString<DiagnosisApiResponse>(body)
        assertTrue(parsed.success)
        assertEquals("Unable to reliably identify the problem", parsed.diagnosis?.issue)
        assertEquals("Poor", parsed.diagnosis?.imageQuality)
    }
}
