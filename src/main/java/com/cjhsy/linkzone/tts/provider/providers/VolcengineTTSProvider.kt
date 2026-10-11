package com.cjhsy.linkzone.tts.provider.providers

import android.content.Context
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.cjhsy.linkzone.tts.provider.TtsSseEvent
import com.cjhsy.linkzone.tts.provider.ttsSseFlow
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
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

private val volcengineJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class VolcengineResponse(
    val code: Int,
    val message: String = "",
    val data: String? = null,
)

internal fun buildVolcengineTTSRequest(
    setting: TTSProviderSetting.Volcengine,
    text: String,
): Request {
    require(setting.apiKey.isNotBlank()) { "Volcengine TTS API Key is required" }
    require(text.isNotBlank()) { "Volcengine TTS text is empty" }
    require(setting.resourceId.isNotBlank()) { "Volcengine TTS Resource ID is required" }
    require(setting.speaker.isNotBlank()) { "Volcengine TTS speaker is required" }
    val body = buildJsonObject {
        put("user", buildJsonObject { put("uid", setting.id) })
        put("req_params", buildJsonObject {
            put("text", text)
            put("speaker", setting.speaker.trim())
            put("audio_params", buildJsonObject {
                put("format", "mp3")
                put("speech_rate", setting.speechRate.coerceIn(-50, 100))
            })
        })
    }
    return Request.Builder()
        .url("${setting.baseUrl.trim().trimEnd('/')}/api/v3/tts/unidirectional/sse")
        .header("Accept", "text/event-stream")
        .header("X-Api-Resource-Id", setting.resourceId.trim())
        .header("X-Api-Request-Id", UUID.randomUUID().toString())
        .header("X-Api-Key", setting.apiKey.trim())
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()
}

internal class VolcengineTTSStreamProcessor {
    private var hasAudio = false

    fun process(event: TtsSseEvent): AudioChunk? = when (event) {
        TtsSseEvent.Open -> null
        is TtsSseEvent.Event -> {
            val response = volcengineJson.decodeFromString<VolcengineResponse>(event.data)
            check(response.code == 0 || response.code == 20000000) {
                "Volcengine TTS error ${response.code}: ${response.message}"
            }
            val audio = response.data?.takeIf { it.isNotBlank() }
                ?.let { Base64.getDecoder().decode(it) } ?: byteArrayOf()
            hasAudio = hasAudio || audio.isNotEmpty()
            val finished = response.code == 20000000
            if (finished) check(hasAudio) { "Volcengine TTS returned no audio" }
            if (audio.isEmpty() && !finished) null else AudioChunk(
                data = audio,
                format = AudioFormat.MP3,
                isLast = finished,
                metadata = mapOf("provider" to "volcengine"),
            )
        }
        TtsSseEvent.Closed -> error("Volcengine TTS stream closed before synthesis completed")
        is TtsSseEvent.Failure -> throw TTSProviderException(
            message = "Volcengine TTS streaming failed: ${event.response?.code ?: "network error"}",
            statusCode = event.response?.code ?: 503,
            cause = event.throwable,
        )
    }
}

// V3 SSE: https://www.volcengine.com/docs/6561/1598757
class VolcengineTTSProvider(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) : TTSProvider<TTSProviderSetting.Volcengine> {

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Volcengine,
        request: TTSRequest,
    ): Flow<AudioChunk> = flow {
        val processor = VolcengineTTSStreamProcessor()
        httpClient.ttsSseFlow(buildVolcengineTTSRequest(providerSetting, request.text))
            // ttsSseFlow uses trySend; fuse an unlimited buffer so audio bursts are not dropped.
            .buffer(Channel.UNLIMITED)
            .transformWhile { event ->
                val chunk = processor.process(event)
                if (chunk != null) emit(chunk)
                chunk?.isLast != true
            }
            .collect { emit(it) }
    }
}
