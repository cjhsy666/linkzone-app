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

private const val TAG = "XAITTSProvider"

class XAITTSProvider(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) : TTSProvider<TTSProviderSetting.XAI> {

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.XAI,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = JSONObject().apply {
            put("text", request.text)
            put("voice_id", providerSetting.voiceId)
            put("language", providerSetting.language)
        }

        Log.i(TAG, "generateSpeech: $requestBody")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/tts")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string()
            Log.e(TAG, "generateSpeech: ${response.code} ${response.message}")
            Log.e(TAG, "generateSpeech: $errorBody")
            throw TTSProviderException(
                message = "xAI TTS request failed: ${response.code} ${response.message}",
                statusCode = response.code
            )
        }

        val audioData = response.body?.bytes()
            ?: throw TTSProviderException(message = "xAI TTS returned empty body", statusCode = response.code)

        emit(
            AudioChunk(
                data = audioData,
                format = AudioFormat.MP3,
                isLast = true,
                metadata = mapOf(
                    "provider" to "xai",
                    "voice_id" to providerSetting.voiceId,
                    "language" to providerSetting.language
                )
            )
        )
    }
}
