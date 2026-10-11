package com.cjhsy.linkzone.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.cjhsy.linkzone.tts.model.AudioChunk
import com.cjhsy.linkzone.tts.model.AudioFormat
import com.cjhsy.linkzone.tts.model.TTSRequest
import com.cjhsy.linkzone.tts.provider.TTSProvider
import com.cjhsy.linkzone.tts.provider.TTSProviderException
import com.cjhsy.linkzone.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "OpenAITTSProvider"

class OpenAITTSProvider(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) : TTSProvider<TTSProviderSetting.OpenAI> {

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.OpenAI,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = JSONObject().apply {
            put("model", providerSetting.model)
            put("input", request.text)
            put("voice", providerSetting.voice)
            put("response_format", "mp3") // Default to MP3
        }

        Log.i(TAG, "generateSpeech: $requestBody")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/audio/speech")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            val statusCode = response.code
            val statusMessage = response.message
            response.close()
            throw TTSProviderException(
                message = "TTS request failed: $statusCode $statusMessage",
                statusCode = statusCode
            )
        }

        val audioData = response.body?.bytes()
            ?: throw TTSProviderException(message = "OpenAI TTS returned empty body", statusCode = response.code)

        emit(
            AudioChunk(
                data = audioData,
                format = AudioFormat.MP3,
                isLast = true,
                metadata = mapOf(
                    "provider" to "openai",
                    "model" to providerSetting.model,
                    "voice" to providerSetting.voice
                )
            )
        )
    }
}
