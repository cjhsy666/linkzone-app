package com.cjhsy.linkzone.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class BalanceOption(
    val enabled: Boolean = false, // 是否开启余额获取功能
    val apiPath: String = "/credits", // 余额获取API路径
    val resultPath: String = "data.total_usage", // 余额获取JSON路径
)

@Serializable
enum class ClaudePromptCacheTtl(val apiValue: String?) {
    @SerialName("5m")
    FIVE_MINUTES(null),

    @SerialName("1h")
    ONE_HOUR("1h")
}

@Serializable
sealed class ProviderSetting {
    abstract val id: String
    abstract val enabled: Boolean
    abstract val name: String
    abstract val models: List<Model>
    abstract val balanceOption: BalanceOption
    abstract val customHeaders: List<CustomHeader>

    abstract val builtIn: Boolean

    abstract fun addModel(model: Model): ProviderSetting
    abstract fun editModel(model: Model): ProviderSetting
    abstract fun delModel(model: Model): ProviderSetting
    abstract fun moveMove(from: Int, to: Int): ProviderSetting
    abstract fun copyProvider(
        id: String = this.id,
        enabled: Boolean = this.enabled,
        name: String = this.name,
        models: List<Model> = this.models,
        balanceOption: BalanceOption = this.balanceOption,
        customHeaders: List<CustomHeader> = this.customHeaders,
        builtIn: Boolean = this.builtIn,
    ): ProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: String = java.util.UUID.randomUUID().toString(),
        override var enabled: Boolean = true,
        override var name: String = "OpenAI",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        var apiKey: String = "",
        var baseUrl: String = "https://api.openai.com/v1",
        var chatCompletionsPath: String = "/chat/completions",
        var useResponseApi: Boolean = false,
        var includeHistoryReasoning: Boolean = true,
        var responsesPath: String = "/responses",
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: String,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                builtIn = builtIn,
                balanceOption = balanceOption,
            )
        }
    }

    @Serializable
    @SerialName("google")
    data class Google(
        override var id: String = java.util.UUID.randomUUID().toString(),
        override var enabled: Boolean = true,
        override var name: String = "Google",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        var apiKey: String = "",
        var baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        var vertexAI: Boolean = false,
        var useServiceAccount: Boolean = false,
        var privateKey: String = "", // only for vertex AI service account
        var serviceAccountEmail: String = "", // only for vertex AI service account
        var location: String = "us-central1", // only for vertex AI service account
        var projectId: String = "", // only for vertex AI service account
        var useInteractionsApi: Boolean = false, // ignored when vertex AI is enabled
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: String,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                customHeaders = customHeaders,
                builtIn = builtIn,
                balanceOption = balanceOption
            )
        }
    }

    @Serializable
    @SerialName("claude")
    data class Claude(
        override var id: String = java.util.UUID.randomUUID().toString(),
        override var enabled: Boolean = true,
        override var name: String = "Claude",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        var apiKey: String = "",
        var baseUrl: String = "https://api.anthropic.com/v1",
        var promptCaching: Boolean = false,
        var promptCacheTtl: ClaudePromptCacheTtl = ClaudePromptCacheTtl.FIVE_MINUTES,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: String,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            builtIn: Boolean,
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                balanceOption = balanceOption,
                customHeaders = customHeaders,
                builtIn = builtIn,
            )
        }
    }

    companion object {
        val Types by lazy {
            listOf(
                OpenAI::class,
                Google::class,
                Claude::class,
            )
        }
    }
}

/**
 * 模型引用：`providerId/modelId`。
 *
 * 同一个 modelId 可能同时出现在多个服务商下（不同 key、不同 baseUrl、同名的中转渠道等），
 * 只存 modelId 无法定位到具体是哪一个，选择器里两条同 id 模型也无法区分。
 * 因此凡是需要记住「用户选了哪个模型」的地方（智能体配置、最近使用、重答选择器）
 * 都统一存这个复合引用。providerId 是 UUID，本身不含 '/'，所以用首个 '/' 拆分是安全的；
 * modelId 允许含 '/'（如 `anthropic/claude-sonnet-4`），拆分后原样保留。
 */
object ModelRef {
    fun encode(providerId: String, modelId: String): String = "$providerId/$modelId"

    /** 拆解引用；不是复合格式（旧数据只有 modelId）时返回 null */
    fun decode(ref: String?): Pair<String, String>? {
        if (ref.isNullOrBlank()) return null
        val i = ref.indexOf('/')
        if (i <= 0 || i >= ref.length - 1) return null
        return ref.substring(0, i) to ref.substring(i + 1)
    }
}

/**
 * 按引用解析到具体的服务商与模型。
 * 兼容旧数据：不满足复合格式时退回「按服务商顺序取首个同 modelId 的模型」。
 */
fun List<ProviderSetting>.resolveModel(ref: String?): Pair<ProviderSetting, Model>? {
    if (ref.isNullOrBlank()) return null
    val decoded = ModelRef.decode(ref)
    if (decoded != null) {
        val (providerId, modelId) = decoded
        // 复合引用必须精确命中：找不到就返回 null（模型已删除），
        // 不能再按 modelId 在别的服务商里兜底，否则会悄悄串到别的渠道
        val provider = firstOrNull { it.id == providerId } ?: return null
        val model = provider.models.firstOrNull { it.modelId == modelId } ?: return null
        return provider to model
    }
    forEach { p -> p.models.firstOrNull { it.modelId == ref }?.let { return p to it } }
    return null
}
