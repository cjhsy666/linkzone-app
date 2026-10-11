package com.cjhsy.linkzone.ai.provider

import com.cjhsy.linkzone.ai.registry.ModelRegistry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
)

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()
}

/**
 * 模型是否支持函数工具（决定是否向 API 发送 tools 参数）。
 * 优先级：模型显式声明的 abilities > 内置模型注册表 > 未知模型默认支持。
 *
 * 背景：自定义 provider 的模型（/models 拉取或手动添加）abilities 恒为空，
 * 若 gate 直接判空会导致 tools 从不发送——模型收不到工具定义，
 * 只能凭预训练幻觉输出文本式 tool_call，所有本地工具静默失效。
 */
fun Model.supportsTools(): Boolean {
    if (ModelAbility.TOOL in abilities) return true
    if (abilities.isNotEmpty()) return false
    val registered = ModelRegistry.MODEL_ABILITIES.getData(modelId)
    return registered.isEmpty() || ModelAbility.TOOL in registered
}
