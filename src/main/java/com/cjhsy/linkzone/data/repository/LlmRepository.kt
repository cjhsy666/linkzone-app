package com.cjhsy.linkzone.data.repository

import com.cjhsy.linkzone.ai.provider.ProviderSetting
import com.cjhsy.linkzone.ai.util.json
import com.cjhsy.linkzone.ai.util.json as jsonFormat
import com.cjhsy.linkzone.data.ai.LocalToolOption
import com.cjhsy.linkzone.data.ai.mcp.McpServerConfig
import com.cjhsy.linkzone.data.db.AppDatabase
import com.cjhsy.linkzone.data.db.CloudQuickCommandEntity
import com.cjhsy.linkzone.data.db.LlmAgentEntity
import com.cjhsy.linkzone.data.db.LlmLorebookEntity
import com.cjhsy.linkzone.data.db.LlmMcpServerEntity
import com.cjhsy.linkzone.data.db.LlmMemoryEntity
import com.cjhsy.linkzone.data.db.LlmPromptEntity
import com.cjhsy.linkzone.data.db.LlmProviderEntity
import com.cjhsy.linkzone.data.db.LlmQuickMessageEntity
import com.cjhsy.linkzone.data.db.LlmSearchProviderEntity
import com.cjhsy.linkzone.data.db.LlmSkillEntity
import com.cjhsy.linkzone.data.model.AssistantMemory
import com.cjhsy.linkzone.data.model.CloudQuickCommand
import com.cjhsy.linkzone.data.model.LlmAgent
import com.cjhsy.linkzone.data.model.LlmLorebook
import com.cjhsy.linkzone.data.model.LlmPrompt
import com.cjhsy.linkzone.data.model.LlmQuickMessage
import com.cjhsy.linkzone.data.model.LlmSkill
import com.cjhsy.linkzone.data.search.SearchServiceOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * LLM 本地配置仓库：Provider / Agent 的 CRUD 与 Room 流。
 *
 * 序列化策略：
 * - ProviderSetting 为 sealed 多态类，必须用基类 serializer 编解码才能带上
 *   "type" 判别字段；子类自身 serializer 会丢判别器导致反序列化失败
 * - Agent 用 [LlmAgent] 直接编解码
 * 均使用 [com.cjhsy.linkzone.ai.util.json]（ignoreUnknownKeys + encodeDefaults），
 * 字段演进向后兼容。
 */
class LlmRepository(
    private val appContext: android.content.Context,
    private val db: AppDatabase
) {

    /** 技能文件存储：filesDir/skills/<name>/（磁盘为文件唯一事实源，Room 仅存元数据） */
    private val skillStore = com.cjhsy.linkzone.data.files.SkillFileStore(appContext)

    private val migrationMutex = Mutex()

    // ============ Provider ============

    fun observeProviders(): Flow<List<ProviderSetting>> =
        db.llmProviderDao().observeAll().map { list -> list.mapNotNull { it.toSetting() } }

    suspend fun getProviders(): List<ProviderSetting> =
        db.llmProviderDao().getAll().mapNotNull { it.toSetting() }

    suspend fun getProvider(id: String): ProviderSetting? =
        db.llmProviderDao().getById(id)?.toSetting()

    suspend fun upsertProvider(setting: ProviderSetting) {
        val dao = db.llmProviderDao()
        val existing = dao.getById(setting.id)
        dao.upsert(
            LlmProviderEntity(
                id = setting.id,
                type = setting.typeName(),
                name = setting.name,
                enabled = setting.enabled,
                sort = existing?.sort ?: System.currentTimeMillis(),
                json = json.encodeToString(ProviderSetting.serializer(), setting)
            )
        )
    }

    suspend fun deleteProvider(id: String) {
        db.llmProviderDao().deleteById(id)
    }

    // ============ Agent ============

    fun observeAgents(): Flow<List<LlmAgent>> =
        db.llmAgentDao().observeAll().map { list -> list.mapNotNull { it.toAgent() } }

    suspend fun getAgents(): List<LlmAgent> =
        db.llmAgentDao().getAll().mapNotNull { it.toAgent() }

    suspend fun getAgent(id: String): LlmAgent? =
        db.llmAgentDao().getById(id)?.toAgent()

    suspend fun upsertAgent(agent: LlmAgent, builtIn: Boolean = false) {
        val dao = db.llmAgentDao()
        val existing = dao.getById(agent.id)
        dao.upsert(
            LlmAgentEntity(
                id = agent.id,
                name = agent.name,
                sort = existing?.sort ?: agent.createdAt,
                builtIn = existing?.builtIn ?: builtIn,
                localTools = encodeLocalTools(agent.localTools),
                json = json.encodeToString(LlmAgent.serializer(), agent)
            )
        )
    }

    suspend fun deleteAgent(id: String) {
        db.llmAgentDao().deleteById(id)
    }

    suspend fun isAgentBuiltIn(id: String): Boolean =
        db.llmAgentDao().getById(id)?.builtIn ?: false

    // ============ Skill（SKILL.md 技能） ============
    // 存储分层：Room 存元数据（id/name/createdAt/SKILL.md 文本备份），
    // 多文件实体在 filesDir/skills/<name>/（SkillFileStore），读取时以磁盘为准水合。

    fun observeSkills(): Flow<List<LlmSkill>> =
        db.llmSkillDao().observeAll().map { list ->
            migrateLegacyDbSkillsIfNeeded()
            list.mapNotNull { it.toSkill()?.hydratedFromDisk() }
        }

    suspend fun getSkills(): List<LlmSkill> {
        migrateLegacyDbSkillsIfNeeded()
        return db.llmSkillDao().getAll().mapNotNull { it.toSkill()?.hydratedFromDisk() }
    }

    suspend fun upsertSkill(
        skill: LlmSkill,
        binaryFiles: Map<String, ByteArray> = emptyMap(),
    ) {
        migrateLegacyDbSkillsIfNeeded()
        val dao = db.llmSkillDao()
        val oldName = dao.getById(skill.id)?.let { e ->
            runCatching { jsonFormat.decodeFromString(LlmSkill.serializer(), e.json).name }.getOrNull()
        }
        val previousDirName = oldName?.let { com.cjhsy.linkzone.data.files.SkillFileStore.sanitize(it) }
        // 文件原子落盘（含 shebang 可执行位恢复）；失败则不更新元数据
        skillStore.write(skill, previousDirName, binaryFiles) ?: return
        // Room 仅存元数据 + SKILL.md 备份，files 映射不再入库
        val toPersist = skill.copy(files = emptyMap())
        dao.upsert(
            LlmSkillEntity(
                id = skill.id,
                name = skill.name,
                sort = skill.createdAt,
                json = json.encodeToString(LlmSkill.serializer(), toPersist)
            )
        )
    }

    suspend fun deleteSkill(id: String) {
        skillStore.deleteById(id)
        db.llmSkillDao().deleteById(id)
    }

    /** 磁盘水合：优先 .skill_id 定位，其次按当前名字定位；找不到磁盘目录时保留 DB 内容 */
    private fun LlmSkill.hydratedFromDisk(): LlmSkill {
        val byId = skillStore.findDirById(id)
        val dir = byId ?: run {
            val candidate = skillStore.root.resolve(
                com.cjhsy.linkzone.data.files.SkillFileStore.sanitize(name)
            )
            candidate.takeIf { it.isDirectory }
        }
        val onDisk = dir?.let { skillStore.read(it.name) } ?: return this
        return copy(content = onDisk.skillMd, files = onDisk.files)
    }

    private val migrationPrefs by lazy {
        appContext.getSharedPreferences("llm_skill_migration", android.content.Context.MODE_PRIVATE)
    }

    /**
     * 一次性迁移：旧版本把多文件存在 Room JSON 的 files 字段中，
     * 首次访问时把它们导出到 filesDir/skills/。之后磁盘为唯一事实源。
     */
    private suspend fun migrateLegacyDbSkillsIfNeeded() {
        migrationMutex.withLock {
        if (migrationPrefs.getBoolean(KEY_FILES_TO_DISK, false)) return
        runCatching {
            val dao = db.llmSkillDao()
            val entities = dao.getAll()
            entities.forEach { e ->
                val skill = runCatching {
                    jsonFormat.decodeFromString(LlmSkill.serializer(), e.json)
                }.getOrNull() ?: return@forEach
                val hasDisk = skillStore.findDirById(skill.id) != null ||
                    skillStore.root.resolve(
                        com.cjhsy.linkzone.data.files.SkillFileStore.sanitize(skill.name)
                    ).isDirectory
                if (hasDisk) return@forEach
                if (skill.files.isNotEmpty() || skill.content.isNotBlank()) {
                    skillStore.write(
                        skill,
                        previousDirName = com.cjhsy.linkzone.data.files.SkillFileStore.sanitize(skill.name)
                    )
                }
            }
            migrationPrefs.edit().putBoolean(KEY_FILES_TO_DISK, true).apply()
        }
        }
    }

    // ============ 模式注入 / 世界书 ============

    fun observePrompts(): Flow<List<LlmPrompt>> =
        db.llmPromptDao().observeAll().map { list -> list.mapNotNull { it.toPrompt() } }

    suspend fun getPrompts(): List<LlmPrompt> =
        db.llmPromptDao().getAll().mapNotNull { it.toPrompt() }

    suspend fun upsertPrompt(prompt: LlmPrompt) {
        db.llmPromptDao().upsert(
            LlmPromptEntity(
                id = prompt.id,
                name = prompt.name,
                enabled = prompt.enabled,
                sort = prompt.createdAt,
                json = json.encodeToString(LlmPrompt.serializer(), prompt)
            )
        )
    }

    suspend fun deletePrompt(id: String) {
        db.llmPromptDao().deleteById(id)
    }

    fun observeLorebooks(): Flow<List<LlmLorebook>> =
        db.llmLorebookDao().observeAll().map { list -> list.mapNotNull { it.toLorebook() } }

    suspend fun getLorebooks(): List<LlmLorebook> =
        db.llmLorebookDao().getAll().mapNotNull { it.toLorebook() }

    suspend fun upsertLorebook(book: LlmLorebook) {
        db.llmLorebookDao().upsert(
            LlmLorebookEntity(
                id = book.id,
                name = book.name,
                enabled = book.enabled,
                sort = book.createdAt,
                json = json.encodeToString(LlmLorebook.serializer(), book)
            )
        )
    }

    suspend fun deleteLorebook(id: String) {
        db.llmLorebookDao().deleteById(id)
    }

    // ============ 快捷指令 ============

    fun observeQuickMessages(): Flow<List<LlmQuickMessage>> =
        db.llmQuickMessageDao().observeAll().map { list -> list.mapNotNull { it.toQuickMessage() } }

    suspend fun getQuickMessages(): List<LlmQuickMessage> =
        db.llmQuickMessageDao().getAll().mapNotNull { it.toQuickMessage() }

    suspend fun upsertQuickMessage(item: LlmQuickMessage) {
        db.llmQuickMessageDao().upsert(
            LlmQuickMessageEntity(
                id = item.id,
                title = item.title,
                sort = item.createdAt,
                json = json.encodeToString(LlmQuickMessage.serializer(), item)
            )
        )
    }

    suspend fun deleteQuickMessage(id: String) {
        db.llmQuickMessageDao().deleteById(id)
    }

    // ============ 云端快捷指令（云端助手主会话，独立于本地快捷指令） ============

    fun observeCloudQuickCommands(): Flow<List<CloudQuickCommand>> =
        db.cloudQuickCommandDao().observeAll().map { list -> list.mapNotNull { it.toCloudQuickCommand() } }

    suspend fun upsertCloudQuickCommand(item: CloudQuickCommand) {
        db.cloudQuickCommandDao().upsert(
            CloudQuickCommandEntity(
                id = item.id,
                title = item.title,
                sort = item.createdAt,
                json = json.encodeToString(CloudQuickCommand.serializer(), item)
            )
        )
    }

    suspend fun deleteCloudQuickCommand(id: String) {
        db.cloudQuickCommandDao().deleteById(id)
    }

    // ============ MCP 服务器 ============

    fun observeMcpServers(): Flow<List<McpServerConfig>> =
        db.llmMcpServerDao().observeAll().map { list -> list.mapNotNull { it.toMcpServer() } }

    suspend fun getMcpServers(): List<McpServerConfig> =
        db.llmMcpServerDao().getAll().mapNotNull { it.toMcpServer() }

    suspend fun getMcpServer(id: String): McpServerConfig? =
        db.llmMcpServerDao().getById(id)?.toMcpServer()

    suspend fun upsertMcpServer(config: McpServerConfig) {
        val dao = db.llmMcpServerDao()
        val existing = dao.getById(config.id)
        dao.upsert(
            LlmMcpServerEntity(
                id = config.id,
                name = config.commonOptions.name,
                enabled = config.commonOptions.enable,
                sort = existing?.sort ?: System.currentTimeMillis(),
                json = json.encodeToString(McpServerConfig.serializer(), config)
            )
        )
    }

    /** 原位更新（连接状态机回写工具清单/OAuth 令牌），配置不存在时忽略 */
    suspend fun updateMcpServer(id: String, transform: (McpServerConfig) -> McpServerConfig) {
        val dao = db.llmMcpServerDao()
        val current = dao.getById(id)?.toMcpServer() ?: return
        upsertMcpServer(transform(current))
    }

    suspend fun deleteMcpServer(id: String) {
        db.llmMcpServerDao().deleteById(id)
    }

    // ============ 联网搜索服务 ============

    fun observeSearchProviders(): Flow<List<Pair<SearchServiceOptions, LlmSearchProviderEntity>>> =
        db.llmSearchProviderDao().observeAll().map { list ->
            list.mapNotNull { entity -> entity.toSearchOptions()?.let { it to entity } }
        }

    suspend fun getSearchProviders(): List<Pair<SearchServiceOptions, LlmSearchProviderEntity>> =
        db.llmSearchProviderDao().getAll().mapNotNull { entity ->
            entity.toSearchOptions()?.let { it to entity }
        }

    /** 当前选中的可用搜索服务（未配置/未启用返回 null） */
    suspend fun getSelectedSearchOptions(): SearchServiceOptions? =
        getSearchProviders().firstOrNull { it.second.selected && it.second.enabled }?.first

    suspend fun upsertSearchProvider(options: SearchServiceOptions, enabled: Boolean, selected: Boolean) {
        val dao = db.llmSearchProviderDao()
        if (selected) dao.clearSelected()
        val existing = dao.getById(options.id)
        dao.upsert(
            LlmSearchProviderEntity(
                id = options.id,
                type = options.typeName(),
                enabled = enabled,
                selected = selected,
                sort = existing?.sort ?: System.currentTimeMillis(),
                json = json.encodeToString(SearchServiceOptions.serializer(), options)
            )
        )
    }

    suspend fun deleteSearchProvider(id: String) {
        db.llmSearchProviderDao().deleteById(id)
    }

    // ============ 长期记忆 ============

    fun observeMemories(agentId: String): Flow<List<AssistantMemory>> =
        db.llmMemoryDao().observeByAgent(agentId).map { list ->
            list.map { AssistantMemory(id = it.id, content = it.content) }
        }

    suspend fun getMemoriesOfAgent(agentId: String): List<AssistantMemory> =
        db.llmMemoryDao().getByAgent(agentId).map {
            AssistantMemory(id = it.id, content = it.content)
        }

    suspend fun getGlobalMemories(): List<AssistantMemory> =
        getMemoriesOfAgent(MEMORY_GLOBAL_ID)

    suspend fun updateMemoryContent(id: Int, content: String): AssistantMemory {
        val old = db.llmMemoryDao().getById(id) ?: error("Memory record #$id not found")
        db.llmMemoryDao().update(old.copy(content = content))
        return AssistantMemory(id = old.id, content = content)
    }

    suspend fun addMemory(agentId: String, content: String): AssistantMemory {
        val newId = db.llmMemoryDao().insert(
            LlmMemoryEntity(agentId = agentId, content = content, createdAt = System.currentTimeMillis())
        ).toInt()
        return AssistantMemory(id = newId, content = content)
    }

    suspend fun deleteMemory(id: Int) {
        db.llmMemoryDao().deleteById(id)
    }

    // ============ 种子数据 ============

    /** 首次启动确保存在一个内置默认智能体（不可删除，占位 sort=0） */
    suspend fun ensureDefaultAgent() {
        val dao = db.llmAgentDao()
        if (dao.count() > 0) return
        val agent = LlmAgent(name = DEFAULT_AGENT_NAME, avatarEmoji = "🤖", createdAt = 0L)
        dao.upsert(
            LlmAgentEntity(
                id = agent.id,
                name = agent.name,
                sort = 0L,
                builtIn = true,
                json = json.encodeToString(LlmAgent.serializer(), agent)
            )
        )
    }

    // ============ 转换 ============

    private fun ProviderSetting.typeName(): String = when (this) {
        is ProviderSetting.OpenAI -> "openai"
        is ProviderSetting.Google -> "google"
        is ProviderSetting.Claude -> "claude"
    }

    private fun LlmProviderEntity.toSetting(): ProviderSetting? =
        runCatching {
            jsonFormat.decodeFromString(ProviderSetting.serializer(), json)
        }.getOrNull()

    private fun LlmAgentEntity.toAgent(): LlmAgent? {
        // 早期版本存过已废弃的 "chart_display" 开关（图表已改为 ```chart 围栏渲染），
        // 剔除后才能安全解码，否则未知子类会让整个智能体反序列化失败
        val base = runCatching { jsonFormat.decodeFromString(LlmAgent.serializer(), stripLegacyChartTool(json)) }
            .getOrNull()
            ?: return null
        // localTools 以独立列为准（v5 起），JSON 列仅冗余
        return base.copy(localTools = decodeLocalTools(localTools))
    }

    /** 从 agent 整行 JSON 的 localTools 数组剔除已废弃的 chart_display（无该值时原样返回） */
    private fun stripLegacyChartTool(raw: String): String {
        val obj = runCatching { jsonFormat.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return raw
        val arr = obj["localTools"] as? JsonArray ?: return raw
        val cleaned = arr.filterNot { (it as? JsonPrimitive)?.contentOrNull == "chart_display" }
        if (cleaned.size == arr.size) return raw
        return runCatching {
            jsonFormat.encodeToString(JsonObject.serializer(), JsonObject(obj + ("localTools" to JsonArray(cleaned))))
        }.getOrDefault(raw)
    }

    /** 本地工具开关列表 → 列存储 JSON（失败回退空数组） */
    private fun encodeLocalTools(list: List<LocalToolOption>): String =
        runCatching {
            json.encodeToString(ListSerializer(LocalToolOption.serializer()), list)
        }.getOrDefault("[]")

    /** 列存储 JSON → 本地工具开关列表（剔除已废弃的 chart_display；损坏回退空数组） */
    private fun decodeLocalTools(raw: String): List<LocalToolOption> =
        runCatching {
            val arr = json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
            val cleaned = arr.filterNot { (it as? JsonPrimitive)?.contentOrNull == "chart_display" }
            json.decodeFromString(
                ListSerializer(LocalToolOption.serializer()),
                JsonArray(cleaned).toString()
            )
        }.getOrDefault(emptyList())

    private fun LlmSkillEntity.toSkill(): LlmSkill? =
        runCatching { jsonFormat.decodeFromString(LlmSkill.serializer(), json) }.getOrNull()

    private fun LlmPromptEntity.toPrompt(): LlmPrompt? =
        runCatching { jsonFormat.decodeFromString(LlmPrompt.serializer(), json) }.getOrNull()

    private fun LlmLorebookEntity.toLorebook(): LlmLorebook? =
        runCatching { jsonFormat.decodeFromString(LlmLorebook.serializer(), json) }.getOrNull()

    private fun LlmQuickMessageEntity.toQuickMessage(): LlmQuickMessage? =
        runCatching { jsonFormat.decodeFromString(LlmQuickMessage.serializer(), json) }.getOrNull()

    private fun CloudQuickCommandEntity.toCloudQuickCommand(): CloudQuickCommand? =
        runCatching { jsonFormat.decodeFromString(CloudQuickCommand.serializer(), json) }.getOrNull()

    private fun LlmMcpServerEntity.toMcpServer(): McpServerConfig? =
        runCatching { jsonFormat.decodeFromString(McpServerConfig.serializer(), json) }.getOrNull()

    private fun LlmSearchProviderEntity.toSearchOptions(): SearchServiceOptions? =
        runCatching { jsonFormat.decodeFromString(SearchServiceOptions.serializer(), json) }.getOrNull()

    private fun SearchServiceOptions.typeName(): String = when (this) {
        is SearchServiceOptions.TavilyOptions -> "tavily"
        is SearchServiceOptions.ExaOptions -> "exa"
        is SearchServiceOptions.BraveOptions -> "brave"
        is SearchServiceOptions.SearXNGOptions -> "searxng"
    }

    companion object {
        const val DEFAULT_AGENT_NAME = "默认智能体"

        /** 全局共享记忆的归属 id（对齐 RikkaHub MemoryRepository.GLOBAL_MEMORY_ID） */
        const val MEMORY_GLOBAL_ID = "__global__"

        /** 旧版 Room JSON files → 文件系统 skill 目录的一次性迁移标记 */
        private const val KEY_FILES_TO_DISK = "skill_files_to_disk_v1"
    }
}
