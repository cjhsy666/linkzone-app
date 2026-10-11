package com.cjhsy.linkzone.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.cjhsy.linkzone.ai.provider.CustomBody
import com.cjhsy.linkzone.ai.provider.Model
import com.cjhsy.linkzone.ai.provider.ProviderManager
import com.cjhsy.linkzone.ai.provider.ProviderSetting
import com.cjhsy.linkzone.ai.provider.TextGenerationParams
import com.cjhsy.linkzone.ai.registry.ModelRegistry
import com.cjhsy.linkzone.ai.ui.StreamChunkHandler
import com.cjhsy.linkzone.ai.ui.UIMessage

/**
 * 翻译处理器（参考 RikkaHub TranslationHandler 适配）：
 *
 * - 常规模型：拼一条带占位变量的翻译提示词，走 Provider 流式接口，边生成边发射译文；
 * - Qwen MT 系列（ModelRegistry.QWEN_MT）：走非流式 generateText，
 *   通过 translation_options 自定义 body 声明 source_lang=auto / target_lang。
 *
 * 目标语言以英文名（English / Japanese...）传入：常规提示词直接可读，
 * Qwen MT 的 translation_options 也要求英文名。
 */
class TranslationHandler(
    private val providerManager: ProviderManager,
) {

    fun translateText(
        providerSetting: ProviderSetting,
        model: Model,
        sourceText: String,
        targetLanguage: String,
    ): Flow<String> = flow {
        val provider = providerManager.getProviderByType(providerSetting)

        if (ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Qwen MT 专用翻译协议：原文裸传 + translation_options 声明目标语言
            val messages = listOf(UIMessage.user(sourceText))
            val result = provider.generateText(
                providerSetting = providerSetting,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put("target_lang", JsonPrimitive(targetLanguage))
                            },
                        )
                    ),
                ),
            )
            val translated = result.message.toText()
            if (translated.isNotBlank()) emit(translated)
        } else {
            // 常规模型：翻译提示词 + 流式输出
            val prompt = TRANSLATE_PROMPT
                .replace("{{source_text}}", sourceText)
                .replace("{{target_lang}}", targetLanguage)
            val handler = StreamChunkHandler(model)
            var messages = listOf(UIMessage.user(prompt))
            var lastEmitted = ""
            provider.streamText(
                providerSetting = providerSetting,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                ),
            ).collect { chunk ->
                messages = handler.handle(messages, chunk)
                // 每次取助手消息当前全文发射，UI 直接整段覆盖展示
                val translated = messages.lastOrNull()?.toText().orEmpty()
                if (translated.isNotBlank() && translated != lastEmitted) {
                    lastEmitted = translated
                    emit(translated)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        val TRANSLATE_PROMPT = """
            You are a translation engine. Translate the user's text into {{target_lang}}.
            Output ONLY the translation, without explanations, quotes, or the original text.
            Preserve formatting (line breaks, lists, code blocks) as much as possible.

            Text to translate:
            {{source_text}}
        """.trimIndent()
    }
}
