package com.cjhsy.linkzone.ai.provider.providers.openai

import kotlinx.coroutines.flow.Flow
import com.cjhsy.linkzone.ai.provider.ProviderSetting
import com.cjhsy.linkzone.ai.provider.TextGenerationResult
import com.cjhsy.linkzone.ai.provider.TextGenerationParams
import com.cjhsy.linkzone.ai.ui.StreamChunk
import com.cjhsy.linkzone.ai.ui.UIMessage

interface OpenAIImpl {
    suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult

    suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk>
}
