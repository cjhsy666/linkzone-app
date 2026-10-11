package com.cjhsy.linkzone.mediagen.provider.providers.volcengine

import com.cjhsy.linkzone.mediagen.model.MediaGenerationModel
import com.cjhsy.linkzone.mediagen.model.MediaGenerationRequest
import com.cjhsy.linkzone.mediagen.model.MediaGenerationTask
import com.cjhsy.linkzone.mediagen.model.MediaKind
import com.cjhsy.linkzone.mediagen.provider.MediaGenerationProvider
import com.cjhsy.linkzone.mediagen.provider.MediaGenerationProviderSetting
import okhttp3.OkHttpClient

/**
 * 火山方舟：Seedream 图像同步返回结果，Seedance 视频是异步任务。
 */
class VolcengineMediaGenerationProvider(
    client: OkHttpClient,
) : MediaGenerationProvider<MediaGenerationProviderSetting.Volcengine> {
    override val id: String = PROVIDER_ID

    private val image = VolcengineImageGeneration(client)
    private val video = VolcengineVideoGeneration(client)

    override suspend fun create(
        setting: MediaGenerationProviderSetting.Volcengine,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask> = runCatching {
        when (model.kind) {
            MediaKind.IMAGE -> image.create(setting, model, request)
            MediaKind.VIDEO -> video.create(setting, model, request)
        }
    }

    override suspend fun query(
        setting: MediaGenerationProviderSetting.Volcengine,
        model: MediaGenerationModel,
        taskId: String,
    ): Result<MediaGenerationTask> = when (model.kind) {
        MediaKind.IMAGE -> super.query(setting, model, taskId)
        MediaKind.VIDEO -> runCatching { video.query(setting, model, taskId) }
    }

    internal companion object {
        const val PROVIDER_ID = "volcengine"
    }
}
