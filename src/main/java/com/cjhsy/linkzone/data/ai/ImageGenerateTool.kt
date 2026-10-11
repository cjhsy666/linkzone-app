package com.cjhsy.linkzone.data.ai

import android.content.Context
import android.util.Base64
import com.cjhsy.linkzone.ai.core.InputSchema
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.provider.ImageGenerationParams
import com.cjhsy.linkzone.ai.provider.Model
import com.cjhsy.linkzone.ai.provider.ProviderManager
import com.cjhsy.linkzone.ai.provider.ProviderSetting
import com.cjhsy.linkzone.ai.ui.ImageGenSize
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * image_generate 工具 —— 模型按需出图。
 *
 * 调 Provider.generateImage（OpenAI /images/generations 协议等），
 * 返回的 base64 图片落盘到 filesDir/generated_images/，
 * 输出同时包含：
 * - [UIMessagePart.Image]：工具结果中的图片 part，由 ChatViewModel 收集后追加到回复气泡并落库；
 * - [UIMessagePart.Text]：{success, path} JSON，回灌给模型告知生成结果。
 */
fun buildImageGenerateTool(
    context: Context,
    providerManager: ProviderManager,
    setting: ProviderSetting,
    imageModel: Model,
): Tool = Tool(
    name = "image_generate",
    description = """
        Generate an image from a text prompt using an image generation model.
        Call this when the user explicitly asks to draw, create, or generate an image.
        The generated image is shown to the user automatically; do not attempt to render it yourself.
        Parameters: prompt (required, detailed English description works best), size (optional).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "Detailed description of the image to generate")
                })
                put("size", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(ImageGenSize.AUTO.value)
                        add(ImageGenSize.SQUARE_1024.value)
                        add(ImageGenSize.LANDSCAPE_1536.value)
                        add(ImageGenSize.PORTRAIT_1536.value)
                        add(ImageGenSize.SQUARE_256.value)
                        add(ImageGenSize.SQUARE_512.value)
                    })
                    put("description", "Image size, default auto")
                })
            },
            required = listOf("prompt")
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val prompt = obj["prompt"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: error("prompt is required")
        val size = obj["size"]?.jsonPrimitive?.content ?: ImageGenSize.AUTO.value

        val item = withContext(Dispatchers.IO) {
            providerManager.getProviderByType(setting)
                .generateImage(
                    providerSetting = setting,
                    params = ImageGenerationParams(
                        model = imageModel,
                        prompt = prompt,
                        numOfImages = 1,
                        size = size
                    )
                )
                .firstOrNull()
        } ?: error("图片生成未返回结果")

        val ext = when {
            item.mimeType.contains("png") -> "png"
            item.mimeType.contains("webp") -> "webp"
            item.mimeType.contains("jpeg") || item.mimeType.contains("jpg") -> "jpg"
            else -> "png"
        }
        val dir = File(context.filesDir, "generated_images").apply { mkdirs() }
        val file = File(dir, "img_${System.currentTimeMillis()}.$ext")
        file.writeBytes(Base64.decode(item.data, Base64.DEFAULT))
        val fileUrl = "file://${file.absolutePath}"

        listOf(
            UIMessagePart.Image(fileUrl),
            UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("path", file.absolutePath)
                    put("mime_type", item.mimeType)
                }.toString()
            )
        )
    }
)
