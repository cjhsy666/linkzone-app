package com.cjhsy.linkzone.data.model

import kotlinx.serialization.Serializable

/** 长期记忆条目（RikkaHub AssistantMemory 对齐，id 为 llm_memory 表自增主键） */
@Serializable
data class AssistantMemory(
    val id: Int,
    val content: String,
)
