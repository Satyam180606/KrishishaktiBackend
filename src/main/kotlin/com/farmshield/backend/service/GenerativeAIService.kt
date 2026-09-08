package com.farmshield.backend.service

interface GenerativeAIService {
    suspend fun generateAdvisory(userPrompt: String, systemPrompt: String): String
    suspend fun analyzeImage(imageBase64: String, mimeType: String, prompt: String, systemInstruction: String): String
    fun close()
}
