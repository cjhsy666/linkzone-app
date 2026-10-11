package com.cjhsy.linkzone.data.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * WebDAV 同步配置。
 * 注意：password 以明文存储在本地偏好中（SharedPreferences 无敏感级别要求时的折衷），
 * UI 层需向用户展示明文存储提示。
 */
@Serializable
data class WebDavConfig(
    /** 服务器地址，如 https://dav.example.com/dav */
    val url: String = "",
    /** 用户名（可留空表示匿名） */
    val username: String = "",
    /** 密码（明文存储） */
    val password: String = "",
    /** 远端目录（相对服务器根路径，如 linkzone/backup） */
    val directory: String = DEFAULT_DIRECTORY
) {
    /** 配置是否填写完整（可发起上传/下载） */
    val isReady: Boolean get() = url.isNotBlank()

    companion object {
        const val DEFAULT_DIRECTORY = "linkzone"
    }
}

/** WebDAV 操作失败（携带用户可读的中文信息） */
class WebDavException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * WebDAV 客户端（参考 RikkaHub data/sync/webdav，改用 okhttp 实现）。
 *
 * - PUT 上传 / GET 下载，Basic 认证；
 * - 目录不存在时先逐级 MKCOL（容错：已存在的层级返回 405 直接忽略）；
 * - testConnection 用 PROPFIND（Depth 0）探测目录可达性；
 * - 统一 60s 超时；禁止挂 HttpLoggingInterceptor（防止 Basic 凭证泄露进 logcat）。
 *
 * @param tempDir 下载临时文件的存放目录（建议传应用 cacheDir 子目录）；
 *                缺省时使用 File.createTempFile 的系统临时目录
 *                （API 26+ 的 java.io.tmpdir 默认即应用 cache 目录）。
 */
class WebDavSync(client: OkHttpClient, private val tempDir: File? = null) {

    // 派生客户端：共享连接池/线程池，仅覆盖超时
    private val client: OkHttpClient = client.newBuilder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 上传文件到 {directory}/{remoteName}。
     * @throws WebDavException 网络/服务器错误
     */
    suspend fun upload(file: File, config: WebDavConfig, remoteName: String = file.name) =
        withContext(Dispatchers.IO) {
            if (!file.exists()) throw WebDavException("待上传的文件不存在")
            ensureDirectories(config)
            val targetUrl = buildUrl(config, remoteName)
            val body: RequestBody = file.asRequestBody(ZIP_MEDIA_TYPE.toMediaType())
            val request = Request.Builder()
                .url(targetUrl)
                .headers(authHeaders(config))
                .put(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw WebDavException("上传失败（HTTP ${response.code}）")
                }
            }
        }

    /**
     * 下载 {directory}/{remoteName} 到 cacheDir 临时文件。
     * @return 下载完成的临时文件（调用方负责消费/删除）
     * @throws WebDavException 网络/服务器错误
     */
    suspend fun download(config: WebDavConfig, remoteName: String = REMOTE_BACKUP_FILE): File =
        withContext(Dispatchers.IO) {
            val targetUrl = buildUrl(config, remoteName)
            val request = Request.Builder()
                .url(targetUrl)
                .headers(authHeaders(config))
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 404) throw WebDavException("远端还没有备份文件")
                if (!response.isSuccessful) {
                    throw WebDavException("下载失败（HTTP ${response.code}）")
                }
                val tmp: File = if (tempDir != null) {
                    if (!tempDir.exists()) tempDir.mkdirs()
                    File.createTempFile("webdav_download_", ".zip", tempDir)
                } else {
                    // API 26+：java.io.tmpdir 默认指向应用 cache 目录
                    File.createTempFile("webdav_download_", ".zip")
                }
                val input = response.body?.byteStream()
                    ?: run {
                        tmp.delete()
                        throw WebDavException("服务器未返回数据")
                    }
                try {
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                } catch (e: Exception) {
                    tmp.delete()
                    throw WebDavException("下载中断：${e.message ?: "IO 错误"}", e)
                }
                if (tmp.length() == 0L) {
                    tmp.delete()
                    throw WebDavException("下载的文件为空")
                }
                tmp
            }
        }

    /**
     * 测试连接：PROPFIND（Depth 0）探测远端目录。
     * - 207 / 200：目录存在，连接正常；
     * - 404：服务器可达但目录尚未创建（上传时会自动 MKCOL），同样视为连接成功；
     * - 401 / 403：认证失败；
     * - 其他：抛出错误。
     * @return 成功时的提示文案
     * @throws WebDavException 连接/认证失败
     */
    suspend fun testConnection(config: WebDavConfig): String = withContext(Dispatchers.IO) {
        val dirUrl = directoryUrl(config)
        val propfindBody: RequestBody =
            PROPFIND_BODY.toByteArray(Charsets.UTF_8).toRequestBody("application/xml".toMediaType())
        val request = Request.Builder()
            .url(dirUrl)
            .headers(authHeaders(config).newBuilder().add("Depth", "0").build())
            .method("PROPFIND", propfindBody)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 ->
                        throw WebDavException("认证失败（HTTP ${response.code}），请检查账号与密码")
                    response.code == 404 ->
                        "连接成功（目录尚不存在，上传时会自动创建）"
                    response.isSuccessful ->
                        "连接成功"
                    else ->
                        throw WebDavException("服务器响应异常（HTTP ${response.code}）")
                }
            }
        } catch (e: WebDavException) {
            throw e
        } catch (e: Exception) {
            throw WebDavException("无法连接服务器：${e.message ?: "网络错误"}", e)
        }
    }

    // ---------- 内部 ----------

    /** Basic 认证请求头（经 Headers.Builder 构建） */
    private fun authHeaders(config: WebDavConfig): Headers {
        val builder = Headers.Builder()
        if (config.username.isNotBlank() || config.password.isNotBlank()) {
            builder.set("Authorization", Credentials.basic(config.username, config.password))
        }
        return builder.build()
    }

    /** 服务器根 URL（去掉结尾斜杠） */
    private fun baseUrl(config: WebDavConfig): String = config.url.trim().trimEnd('/')

    /** 远端目录的完整 URL */
    private fun directoryUrl(config: WebDavConfig): String = buildUrl(config, null)

    /** 拼接 {url}/{directory}/{name}；name 为 null 时返回目录本身 */
    private fun buildUrl(config: WebDavConfig, name: String?): String {
        val base = baseUrl(config)
        if (base.isBlank()) throw WebDavException("请先填写服务器地址")
        val segments = config.directory.trim('/')
            .split('/')
            .filter { it.isNotBlank() }
        val path = StringBuilder(base)
        segments.forEach { path.append('/').append(it) }
        if (!name.isNullOrBlank()) path.append('/').append(name.trim('/'))
        return path.toString()
    }

    /** 逐级 MKCOL 创建目录；已存在（405）等失败直接忽略 */
    private fun ensureDirectories(config: WebDavConfig) {
        val base = baseUrl(config)
        if (base.isBlank()) throw WebDavException("请先填写服务器地址")
        val segments = config.directory.trim('/')
            .split('/')
            .filter { it.isNotBlank() }
        if (segments.isEmpty()) return
        val cumulative = StringBuilder(base)
        for (segment in segments) {
            cumulative.append('/').append(segment)
            val request = Request.Builder()
                .url(cumulative.toString())
                .headers(authHeaders(config))
                .method("MKCOL", null)
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    // 201 创建成功；405 已存在；其余（如 301 重定向到带斜杠路径）均忽略
                    response.code
                }
            }
        }
    }

    companion object {
        private const val ZIP_MEDIA_TYPE = "application/zip"
        private const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><propfind xmlns=\"DAV:\"/>"

        /** 上传/下载使用的固定远端文件名（时间戳由本地备份文件名体现，同步走固定名） */
        const val REMOTE_BACKUP_FILE = "linkzone_backup.zip"
    }
}
