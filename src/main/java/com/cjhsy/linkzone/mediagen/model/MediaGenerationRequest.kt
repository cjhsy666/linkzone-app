package com.cjhsy.linkzone.mediagen.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject

/**
 * 与供应商无关的媒体生成请求，图像和视频共用。
 *
 * [resolution] 对视频是清晰度档位（如 `1080P`），对图像是像素尺寸（如 `1536x1024`）；[count] 是期望的
 * 产出数量。适配器不支持的公共字段会在提交前直接报错，而不是静默忽略。
 *
 * [extraParameters] 用于尚未进入公共抽象的供应商参数。适配器会先写入这些参数，再用公共字段
 * 覆盖同名项，避免调用方绕过公共字段的统一语义。
 */
@Serializable
data class MediaGenerationRequest(
    val prompt: String? = null,
    val inputs: List<MediaGenerationInput> = emptyList(),
    val count: Int? = null,
    val resolution: String? = null,
    val aspectRatio: String? = null,
    val durationSeconds: Int? = null,
    val generateAudio: Boolean? = null,
    val watermark: Boolean? = null,
    val seed: Long? = null,
    val promptEnhancement: Boolean? = null,
    val callbackUrl: String? = null,
    val extraParameters: JsonObject = JsonObject(emptyMap()),
) {
    init {
        require(!prompt.isNullOrBlank() || inputs.isNotEmpty()) {
            "prompt and inputs cannot both be empty"
        }
        require(count == null || count > 0) { "count must be positive" }
        require(durationSeconds == null || durationSeconds == -1 || durationSeconds > 0) {
            "durationSeconds must be positive or -1"
        }
        require(seed == null || seed >= 0) { "seed must be non-negative" }
    }
}

@Serializable
sealed class MediaGenerationInput {
    abstract val extra: JsonObject

    /**
     * 视频接口要求 [url] 是公网地址。图像接口也接受本地文件路径：OpenAI 以文件形式上传（只接受本地文件），
     * 火山、阿里和 OpenRouter 把本地文件编码成 data URI 内联进请求，公网地址和 data URI 则原样下发。
     */
    @Serializable
    @SerialName("image")
    data class Image(
        val url: String,
        val role: ImageRole = ImageRole.REFERENCE,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : MediaGenerationInput()

    @Serializable
    @SerialName("video")
    data class Video(
        val url: String,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : MediaGenerationInput()

    @Serializable
    @SerialName("audio")
    data class Audio(
        val url: String,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : MediaGenerationInput()

    /** 阿里百炼万相 3.0 支持的文件输入。 */
    @Serializable
    @SerialName("document")
    data class Document(
        val url: String,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : MediaGenerationInput()

    /** 阿里百炼万相 3.0 支持的公开网页输入。 */
    @Serializable
    @SerialName("web_page")
    data class WebPage(
        val url: String,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : MediaGenerationInput()

    /**
     * 尚未进入公共抽象的供应商输入项，例如模型特有的样片任务引用。
     * 该对象会作为一个 content/media 元素原样交给适配器。
     */
    @Serializable
    @SerialName("raw")
    data class Raw(
        val value: JsonObject,
        override val extra: JsonObject = JsonObject(emptyMap()),
    ) : MediaGenerationInput()
}

@Serializable
enum class ImageRole {
    @SerialName("first_frame")
    FIRST_FRAME,

    @SerialName("last_frame")
    LAST_FRAME,

    @SerialName("reference")
    REFERENCE,
}
