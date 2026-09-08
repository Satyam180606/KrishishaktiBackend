package com.farmshield.backend

import com.farmshield.backend.model.*
import com.farmshield.backend.service.AdvisoryService
import com.farmshield.backend.service.GenerativeAIService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdvisoryServiceTest {

    private fun createMockAIService(response: String = VALID_RESPONSE): GenerativeAIService {
        return object : GenerativeAIService {
            override suspend fun generateAdvisory(userPrompt: String, systemPrompt: String): String = response
            override suspend fun analyzeImage(imageBase64: String, mimeType: String, prompt: String, systemInstruction: String): String = response
            override fun close() {}
        }
    }

    companion object {
        val VALID_RESPONSE = """
        {
            "problem": "Yellowing of tomato leaves",
            "likelyCause": "Nitrogen deficiency or early blight infection",
            "actions": [
                "Check lower leaves for concentric ring patterns indicating early blight",
                "Apply balanced NPK fertilizer if nitrogen deficiency is suspected",
                "Remove severely affected leaves and dispose safely"
            ],
            "prevention": [
                "Maintain balanced fertilization schedule",
                "Use drip irrigation to avoid leaf wetness"
            ],
            "expertHelp": {
                "recommended": true,
                "reason": "If symptoms spread rapidly, consult your local KVK"
            },
            "confidence": "medium"
        }
        """.trimIndent()
    }

    @Test
    fun `empty query is rejected`() = runTest {
        val service = AdvisoryService(createMockAIService())
        val request = AdvisoryRequest(query = "", language = "en")

        assertThrows<IllegalArgumentException> {
            service.getAdvisory(request)
        }
    }

    @Test
    fun `blank query is rejected`() = runTest {
        val service = AdvisoryService(createMockAIService())
        val request = AdvisoryRequest(query = "   ", language = "en")

        assertThrows<IllegalArgumentException> {
            service.getAdvisory(request)
        }
    }

    @Test
    fun `query exceeding 2000 characters is rejected`() = runTest {
        val service = AdvisoryService(createMockAIService())
        val longQuery = "a".repeat(2001)
        val request = AdvisoryRequest(query = longQuery, language = "en")

        assertThrows<IllegalArgumentException> {
            service.getAdvisory(request)
        }
    }

    @Test
    fun `unsupported language is rejected`() = runTest {
        val service = AdvisoryService(createMockAIService())
        val request = AdvisoryRequest(query = "Help me", language = "fr")

        assertThrows<IllegalArgumentException> {
            service.getAdvisory(request)
        }
    }

    @Test
    fun `successful advisory with full farm context`() = runTest {
        val service = AdvisoryService(createMockAIService())
        val request = AdvisoryRequest(
            query = "My tomato leaves are turning yellow",
            language = "en",
            farmContext = FarmContext(
                crop = "Tomato",
                variety = "Pusa Ruby",
                stage = "Vegetative",
                location = "Meerut, Uttar Pradesh",
                soilType = "Loamy",
                irrigationType = "Drip",
                area = 2.5,
                unit = "acres"
            )
        )

        val response = service.getAdvisory(request)

        assertNotNull(response)
        assertEquals("Yellowing of tomato leaves", response.problem)
        assertTrue(response.actions.isNotEmpty())
        assertEquals(3, response.actions.size)
        assertTrue(response.expertHelp.recommended)
        assertEquals("medium", response.confidence)
    }

    @Test
    fun `Hindi language is supported`() = runTest {
        val service = AdvisoryService(createMockAIService())
        val request = AdvisoryRequest(
            query = "Mere tamatar ke patte peele ho rahe hain",
            language = "hi"
        )

        val response = service.getAdvisory(request)
        assertNotNull(response)
    }

    @Test
    fun `Hinglish language is supported`() = runTest {
        val service = AdvisoryService(createMockAIService())
        val request = AdvisoryRequest(
            query = "Meri crop mein yellow spots aa rahe hain",
            language = "hinglish"
        )

        val response = service.getAdvisory(request)
        assertNotNull(response)
    }

    @Test
    fun `Marathi language is supported`() = runTest {
        val service = AdvisoryService(createMockAIService())
        val request = AdvisoryRequest(
            query = "माझ्या टोमॅटोच्या पानांवर पिवळे डाग आहेत",
            language = "mr"
        )

        val response = service.getAdvisory(request)
        assertNotNull(response)
    }

    @Test
    fun `context builder includes crop and farm details`() {
        val service = AdvisoryService(createMockAIService())
        val request = AdvisoryRequest(
            query = "Help with my crop",
            language = "en",
            farmContext = FarmContext(
                crop = "Wheat",
                variety = "PBW 343",
                stage = "Vegetative",
                location = "Meerut, UP",
                soilType = "Loamy"
            )
        )

        val prompt = service.buildUserPrompt(request)

        assertTrue(prompt.contains("Wheat"))
        assertTrue(prompt.contains("PBW 343"))
        assertTrue(prompt.contains("Vegetative"))
        assertTrue(prompt.contains("Meerut, UP"))
        assertTrue(prompt.contains("Loamy"))
        assertTrue(prompt.contains("English"))
    }

    @Test
    fun `malformed LLM response returns fallback advisory`() {
        val service = AdvisoryService(createMockAIService())
        val fallback = service.parseAdvisoryResponse("this is not valid json at all")

        assertEquals("low", fallback.confidence)
        assertTrue(fallback.expertHelp.recommended)
        assertTrue(fallback.actions.isNotEmpty())
    }

    @Test
    fun `structured response validation rejects missing required fields`() {
        val service = AdvisoryService(createMockAIService())
        val incompleteJson = """{ "problem": "", "likelyCause": "test", "actions": [], "prevention": [], "expertHelp": { "recommended": false, "reason": "" }, "confidence": "high" }"""

        val result = service.parseAdvisoryResponse(incompleteJson)
        // Empty problem and empty actions trigger fallback
        assertEquals("low", result.confidence)
    }

    @Test
    fun `expert escalation for serious conditions`() = runTest {
        val seriousResponse = """
        {
            "problem": "Suspected late blight outbreak",
            "likelyCause": "Phytophthora infestans infection",
            "actions": ["Immediately isolate affected plants", "Contact agricultural officer"],
            "prevention": ["Resistant varieties", "Proper drainage"],
            "expertHelp": {
                "recommended": true,
                "reason": "Late blight can destroy entire crop within days. Immediate expert intervention needed."
            },
            "confidence": "high"
        }
        """.trimIndent()

        val service = AdvisoryService(createMockAIService(seriousResponse))
        val request = AdvisoryRequest(query = "My potato crop is dying rapidly", language = "en")
        val response = service.getAdvisory(request)

        assertTrue(response.expertHelp.recommended)
        assertTrue(response.expertHelp.reason.contains("expert", ignoreCase = true))
    }

    @Test
    fun `input sanitization removes control characters`() {
        val service = AdvisoryService(createMockAIService())
        val dirty = "Hello\u0000World\u0007Test"
        val clean = service.sanitizeInput(dirty)

        assertTrue(!clean.contains("\u0000"))
        assertTrue(!clean.contains("\u0007"))
        assertTrue(clean.contains("Hello"))
        assertTrue(clean.contains("World"))
    }
}
