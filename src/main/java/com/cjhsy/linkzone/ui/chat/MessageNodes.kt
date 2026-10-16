package com.cjhsy.linkzone.ui.chat

import com.cjhsy.linkzone.data.model.MessageItem
import com.cjhsy.linkzone.data.model.MessageSegment
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 消息版本链（借鉴 RikkaHub MessageNode）。
 *
 * 同一会话中，对消息的「编辑重发 / 重新生成 / 多模型重答」不再删除旧消息，
 * 而是在原消息所在节点（node）追加一个新版本，节点上可通过 ‹ n/m › 切换器自由切换，
 * 旧版本完整保留（仅本地智能体会话开放；云端会话不提供这些功能）。
 * 切换版本时，其下方整条分支随之切换（对齐 RikkaHub 分支语义）。
 *
 * 版本元数据承载在 [VERSION_SEG]（sys.ver）消息段中（无需 Room 迁移、随消息 JSON 落库）：
 * - node：节点锚点 id（首版消息 id）
 * - idx ：本版本序号（首版为 0，不带 sys.ver 段）
 * - sel ：当前选中的版本序号（每个版本消息同步携带，默认/缺失即 0）
 *
 * 版本消息时间戳 = 锚点时间戳 + idx（微秒），保证扁平存储按时间排序时版本聚集在
 * 锚点位置、不会漂到会话末尾。
 */
const val VERSION_SEG = "sys.ver"

/**
 * 分支约束段（sys.br）：标记一条消息属于「锚点 of 的第 idx 个版本」这条分支。
 * 一条消息可携带多个 sys.br（分支嵌套）。可见性规则：消息上每个约束 (of, idx)
 * 都要求锚点 of 当前选中版本 == idx，否则该消息（连同其所在节点）被隐藏——
 * 切换版本时，其下方整条分支随之切换（对齐 RikkaHub 分支语义）。
 */
const val BRANCH_SEG = "sys.br"

/** 版本元数据（仅 idx>=1 的版本消息携带 sys.ver 段） */
data class VersionMeta(
    val node: String,
    val idx: Int,
    val sel: Int,
    /** 该版本实际生成/重发时间（微秒）；null 时 UI 回退到消息时间戳 */
    val displayTs: Long? = null
)

/** 构造版本元数据段（sel=0 时省略字段，读取侧默认 0） */
internal fun versionSegment(
    node: String,
    idx: Int,
    sel: Int,
    displayTs: Long? = null
): MessageSegment {
    val data = linkedMapOf<String, JsonElement>()
    data["node"] = JsonPrimitive(node)
    data["idx"] = JsonPrimitive(idx)
    if (sel != 0) data["sel"] = JsonPrimitive(sel)
    displayTs?.let { data["ts"] = JsonPrimitive(it) }
    return MessageSegment(VERSION_SEG, data)
}

/** 构造分支约束段：本消息属于锚点 of 的第 idx 版分支 */
internal fun branchSegment(of: String, idx: Int): MessageSegment {
    val data = linkedMapOf<String, JsonElement>()
    data["of"] = JsonPrimitive(of)
    data["idx"] = JsonPrimitive(idx)
    return MessageSegment(BRANCH_SEG, data)
}

/** 读取消息上的版本元数据（首版消息返回 null） */
fun MessageItem.versionMeta(): VersionMeta? {
    val seg = segments?.firstOrNull { it.type == VERSION_SEG } ?: return null
    val node = seg.get("node").ifBlank { return null }
    val idx = seg.data["idx"]?.jsonPrimitive?.intOrNull ?: return null
    val sel = seg.data["sel"]?.jsonPrimitive?.intOrNull ?: 0
    val displayTs = seg.data["ts"]?.jsonPrimitive?.longOrNull
    return VersionMeta(node = node, idx = idx, sel = sel, displayTs = displayTs)
}

/**
 * 版本消息的**显示时间**：新版本取 sys.ver 中记录的实际重发生成时间，
 * 首版/无元数据时回退消息自身时间戳。
 * 注意：消息排序/分页仍用 [MessageItem.timestamp]（= 锚点时间 + idx 微秒），
 * 保证版本聚集在锚点位置、落在同一分页，仅展示层用此时间。
 */
fun MessageItem.displayTimestamp(): Long = versionMeta()?.displayTs ?: timestamp

/** 读取消息携带的全部分支约束（of=锚点 id, idx=版本序号） */
fun MessageItem.branchConstraints(): List<Pair<String, Int>> =
    segments?.mapNotNull { seg ->
        if (seg.type != BRANCH_SEG) return@mapNotNull null
        val of = seg.get("of").ifBlank { return@mapNotNull null }
        val idx = seg.data["idx"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        of to idx
    }.orEmpty()

/**
 * 一个消息节点：同一位置的所有版本 + 当前选中序号。
 * @param anchorTs 节点锚点时间戳（首版时间，决定节点在会话中的位置）
 */
data class MessageNode(
    val anchorId: String,
    val anchorTs: Long,
    val versions: List<MessageItem>,
    val selectedIndex: Int
) {
    val selected: MessageItem get() = versions[selectedIndex.coerceIn(versions.indices)]
    val isSelf: Boolean get() = selected.isSelf
}

/**
 * 把扁平消息列表重建为版本节点列表（UI 渲染 / AI 上下文构建 / 分叉回放共用）。
 * 无版本的普通消息为单版本节点，结构恒等。
 */
fun List<MessageItem>.toMessageNodes(): List<MessageNode> {
    if (isEmpty()) return emptyList()
    // node 锚点 id -> 该节点的所有消息
    val groups = LinkedHashMap<String, MutableList<MessageItem>>()
    for (m in this) {
        val meta = m.versionMeta()
        val anchor = meta?.node ?: m.id
        groups.getOrPut(anchor) { mutableListOf() }.add(m)
    }
    return groups.values.mapNotNull { list ->
        if (list.isEmpty()) return@mapNotNull null
        val anchorId = list.first().versionMeta()?.node ?: list.first().id
        val sorted = list.sortedBy { it.versionMeta()?.idx ?: 0 }
        // 选中序号以最新版本上携带的 sel 为准（切换时全量同步）
        val selRaw = sorted.lastOrNull { it.versionMeta() != null }?.versionMeta()?.sel ?: 0
        val sel = selRaw.coerceIn(0, sorted.lastIndex)
        MessageNode(
            anchorId = anchorId,
            anchorTs = sorted.minOf { it.timestamp },
            versions = sorted,
            selectedIndex = sel
        )
    }.sortedWith(compareBy({ it.anchorTs }, { it.anchorId }))
}

/**
 * 按分支约束解析出当前可见的节点列表（RikkaHub 分支切换语义）：
 * - 消息携带的每个 sys.br 约束 (of, idx) 都要求锚点 of 当前选中版本 == idx，
 *   否则该版本被隐藏；节点所有版本均不可见时整个节点隐藏（整条分支换下）
 * - 节点选中版本被分支隐藏时，回落到最后一个可见版本展示
 * - 版本节点自身的 sys.br 约束豁免（否则节点永远选不了自己的版本）
 */
fun List<MessageNode>.resolveVisible(): List<MessageNode> {
    // 快速路径：没有任何多版本节点时不存在分支，原样返回
    if (none { it.versions.size > 1 }) return this
    val selMap = associate { it.anchorId to it.selectedIndex }
    return mapNotNull { node ->
        val visibleIdx = node.versions.indices.filter { i ->
            node.versions[i].branchConstraints().all { (of, req) ->
                of == node.anchorId || (selMap[of] ?: req) == req
            }
        }
        if (visibleIdx.isEmpty()) return@mapNotNull null
        val sel = if (node.selectedIndex in visibleIdx) node.selectedIndex else visibleIdx.last()
        if (sel == node.selectedIndex) node else node.copy(selectedIndex = sel)
    }
}

/** 取当前可见分支上每个节点应显示的消息（扁平回展），用于 AI 上下文构建 / 引擎切换回放 */
fun List<MessageNode>.selectedMessages(): List<MessageItem> = resolveVisible().map { it.selected }

/** 找到某条消息（任一版本）所属节点；未找到返回 null */
fun List<MessageNode>.nodeOf(messageId: String): MessageNode? =
    firstOrNull { node -> node.versions.any { it.id == messageId } }

/** 去掉渲染无关的内部段（版本元数据 / 分支约束 / 思考过程由专门面板渲染 / 中断标记由操作行渲染） */
fun MessageItem.visibleSegments(): List<MessageSegment> =
    segments?.filter {
        it.type != VERSION_SEG && it.type != BRANCH_SEG &&
            it.type != "thinking" && it.type != "sys.stop"
    }.orEmpty()

/** 思考过程文本（thinking 段拼接） */
fun MessageItem.thinkingText(): String =
    segments?.filter { it.type == "thinking" }?.joinToString("") { it.get("text") }.orEmpty()
