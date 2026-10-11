package com.cjhsy.linkzone.tts.controller

import java.util.UUID

/**
 * Split long text into speakable chunks with basic punctuation-aware grouping.
 */
class TextChunker(
    private val maxChunkLength: Int = 150
) {
    fun split(text: String): List<TtsChunk> {
        if (text.isBlank()) return emptyList()

        val paragraphs = text.split("\n\n")
        val punctuationRegex = "(?<=[。！？，、：;.!?:,\n])".toRegex()

        val chunks = paragraphs.flatMap { paragraph ->
            if (paragraph.isBlank()) emptyList() else {
                paragraph
                    .split(punctuationRegex)
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    // 无标点的超长段（代码/长英文句）按上限硬切，
                    // 避免超过 TTS 服务单次合成的字数限制
                    .flatMap { seg ->
                        if (seg.length > maxChunkLength) {
                            seg.chunked(maxChunkLength).asSequence()
                        } else {
                            sequenceOf(seg)
                        }
                    }
                    .fold(mutableListOf<StringBuilder>()) { acc, seg ->
                        if (acc.isEmpty() || acc.last().length + seg.length > maxChunkLength) {
                            acc.add(StringBuilder(seg))
                        } else {
                            acc.last().append(seg)
                        }
                        acc
                    }
                    .map { it.toString() }
            }
        }

        return chunks.mapIndexed { index, value ->
            TtsChunk(text = value, index = index)
        }
    }
}

data class TtsChunk(
    val id: UUID = UUID.randomUUID(),
    val index: Int,
    val text: String
)
