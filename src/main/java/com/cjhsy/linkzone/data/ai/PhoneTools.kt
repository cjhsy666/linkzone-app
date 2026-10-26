package com.cjhsy.linkzone.data.ai

import com.cjhsy.linkzone.core.error.toUserMessage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.cjhsy.linkzone.ai.core.InputSchema
import com.cjhsy.linkzone.ai.core.Tool
import com.cjhsy.linkzone.ai.ui.UIMessagePart
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId

/**
 * 手机操作类本地工具：
 * - phone_control：打开 App / 网页 / 拨号盘 / 系统设置、系统分享、手电筒
 * - device_info：设备信息（型号/系统/电量/网络/屏幕/存储）
 * - get_location：定位（ACCESS_FINE/COARSE_LOCATION）
 * - contacts_query：通讯录检索（READ_CONTACTS）
 * - sms_query：短信检索（READ_SMS）
 * - call_log_query：通话记录检索（READ_CALL_LOG）
 *
 * 全部为读操作或显式跳转（拨号只打开拨号盘不自动拨出），不做静默外呼/发短信等高风险动作。
 */

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun noPermissionResult(permissionName: String, hint: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("error", "NO_PERMISSION")
        put("permission", permissionName)
        put("message", hint)
    }.toString()))

// ===================== phone_control =====================

internal fun buildPhoneControlTool(context: Context): Tool = Tool(
    name = "phone_control",
    description = """
        Control the Android phone through explicit actions:
        - open_app: launch an installed app by package name (e.g. com.tencent.mm).
        - open_url: open a web page or custom scheme URL in the corresponding app/browser.
        - dial: open the dialer with a phone number pre-filled (the call is NOT placed automatically).
        - open_settings: open a system settings screen (section: general, wifi, bluetooth, location, apps, display, sound, storage, notification).
        - share: bring up the system share sheet with plain text.
        - flashlight: turn the torch/flashlight on or off.
        Only perform these actions when the user explicitly requests them.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("open_app"); add("open_url"); add("dial"); add("open_settings")
                        add("share"); add("flashlight")
                    })
                    put("description", "The phone action to perform.")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "App package name for open_app.")
                })
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "URL or scheme for open_url (http/https/market/...).")
                })
                put("phone_number", buildJsonObject {
                    put("type", "string")
                    put("description", "Phone number for dial.")
                })
                put("settings_section", buildJsonObject {
                    put("type", "string")
                    put("description", "Section for open_settings: general/wifi/bluetooth/location/apps/display/sound/storage/notification. Defaults to general.")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Text content for share.")
                })
                put("on", buildJsonObject {
                    put("type", "boolean")
                    put("description", "For flashlight: true = turn on (default), false = turn off.")
                })
            },
            required = listOf("action")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val appContext = context.applicationContext
        when (val action = params["action"]?.jsonPrimitive?.contentOrNull) {
            "open_app" -> {
                val pkg = params["package_name"]?.jsonPrimitive?.contentOrNull
                    ?: error("package_name is required for open_app")
                val intent = appContext.packageManager.getLaunchIntentForPackage(pkg)
                if (intent == null) {
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", false)
                        put("error", "APP_NOT_FOUND")
                        put("message", "未安装包名为 $pkg 的应用，或该应用无可启动入口。")
                    }.toString()))
                } else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(intent)
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", true)
                        put("action", "open_app")
                        put("package_name", pkg)
                    }.toString()))
                }
            }

            "open_url" -> {
                val url = params["url"]?.jsonPrimitive?.contentOrNull
                    ?: error("url is required for open_url")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { appContext.startActivity(intent) }.fold(
                    onSuccess = {
                        listOf(UIMessagePart.Text(buildJsonObject {
                            put("success", true); put("action", "open_url"); put("url", url)
                        }.toString()))
                    },
                    onFailure = { e ->
                        listOf(UIMessagePart.Text(buildJsonObject {
                            put("success", false)
                            put("error", "NO_HANDLER")
                            put("message", "没有可打开该链接的应用：${e.toUserMessage("")}")
                        }.toString()))
                    }
                )
            }

            "dial" -> {
                val number = params["phone_number"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: error("phone_number is required for dial")
                // ACTION_DIAL 只弹拨号盘，用户确认后才会拨出，无需 CALL_PHONE 权限
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", true); put("action", "dial"); put("phone_number", number)
                }.toString()))
            }

            "open_settings" -> {
                val section = params["settings_section"]?.jsonPrimitive?.contentOrNull ?: "general"
                val intent = settingsIntent(section).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { appContext.startActivity(intent) }.getOrElse {
                    val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(fallback)
                }
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", true); put("action", "open_settings"); put("section", section)
                }.toString()))
            }

            "share" -> {
                val text = params["text"]?.jsonPrimitive?.contentOrNull
                    ?: error("text is required for share")
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(Intent.createChooser(intent, "分享").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", true); put("action", "share")
                }.toString()))
            }

            "flashlight" -> {
                val on = params["on"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                val cameraId = cm?.cameraIdList?.firstOrNull {
                    cm.getCameraCharacteristics(it)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (cm == null || cameraId == null) {
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", false)
                        put("error", "NO_FLASH")
                        put("message", "设备没有可用的闪光灯，无法${if (on) "打开" else "关闭"}手电筒。")
                    }.toString()))
                } else {
                    // setTorchMode 无需相机运行时权限；进程被杀后系统可能自动关闭手电筒
                    runCatching { cm.setTorchMode(cameraId, on) }.fold(
                        onSuccess = {
                            listOf(UIMessagePart.Text(buildJsonObject {
                                put("success", true)
                                put("action", "flashlight")
                                put("on", on)
                            }.toString()))
                        },
                        onFailure = { e ->
                            listOf(UIMessagePart.Text(buildJsonObject {
                                put("success", false)
                                put("error", "TORCH_FAILED")
                                put("message", "手电筒操作失败：${e.toUserMessage("")}")
                            }.toString()))
                        }
                    )
                }
            }

            else -> error("unknown action: $action")
        }
    }
)

/** 系统设置页面 section → Intent 映射，未知值回退主设置页 */
private fun settingsIntent(section: String): Intent = when (section.lowercase()) {
    "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
    "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
    "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    "apps" -> Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
    "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
    "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
    "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
    "notification" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, "com.cjhsy.linkzone")
    else -> Intent(Settings.ACTION_SETTINGS)
}

/** 汇总设备/系统/电池/网络/屏幕/存储信息 */
private fun collectDeviceInfo(context: Context) = buildJsonObject {
    put("brand", Build.BRAND)
    put("manufacturer", Build.MANUFACTURER)
    put("model", Build.MODEL)
    put("device", Build.DEVICE)
    put("android_release", Build.VERSION.RELEASE)
    put("sdk_int", Build.VERSION.SDK_INT)
    put("supported_abis", buildJsonArray { Build.SUPPORTED_ABIS.forEach { add(it) } })

    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    put("battery_percent", bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
    val isCharging = bm?.isCharging == true
    put("battery_charging", isCharging)

    // 网络类型（需要 ACCESS_NETWORK_STATE，已在 Manifest 声明）
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
    val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
    val networkType = when {
        caps == null || !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "none"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }
    put("network_type", networkType)
    put("online", networkType != "none" && caps?.hasCapability(
        android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
    ) == true)

    val dm = context.resources.displayMetrics
    put("screen_width_px", dm.widthPixels)
    put("screen_height_px", dm.heightPixels)
    put("density_dpi", dm.densityDpi)
    put("density", dm.density.toDouble())

    // 应用私有目录所在分区可用/总容量
    val stat = android.os.StatFs(context.filesDir.path)
    put("storage_available_bytes", stat.availableBytes)
    put("storage_total_bytes", stat.totalBytes)

    put("locale", java.util.Locale.getDefault().toLanguageTag())
    put("timezone", java.util.TimeZone.getDefault().id)
    @Suppress("DEPRECATION")
    put("external_storage_state", Environment.getExternalStorageState())
}

/**
 * 获取当前位置：优先单次新鲜定位（API 30+，8 秒超时），失败回退最近一次缓存位置。
 * READ 定位权限由调用方保证。
 */
@Suppress("MissingPermission")
private suspend fun currentLocation(context: Context): Location? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = buildList {
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
    }
    if (providers.isEmpty()) return null

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        for (provider in providers) {
            val fresh = withTimeoutOrNull(8_000L) {
                suspendCancellableCoroutine<Location?> { cont ->
                    lm.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                        cont.resumeWith(Result.success(location))
                    }
                }
            }
            if (fresh != null) return fresh
        }
    }
    // 回退：最近缓存位置（取时间最新的一个）
    return providers.mapNotNull { lm.getLastKnownLocation(it) }
        .maxByOrNull { it.time }
}

private fun locationJson(loc: Location) = buildJsonObject {
    put("latitude", loc.latitude)
    put("longitude", loc.longitude)
    put("accuracy_m", loc.accuracy.toDouble())
    put("altitude", if (loc.hasAltitude()) loc.altitude else null as Double?)
    put("speed_mps", if (loc.hasSpeed()) loc.speed else null as Float?)
    put("bearing", if (loc.hasBearing()) loc.bearing else null as Float?)
    put("provider", loc.provider ?: "")
    put("timestamp", Instant.ofEpochMilli(loc.time).atZone(ZoneId.systemDefault()).toString())
}

// ===================== device_info =====================

internal fun buildDeviceInfoTool(context: Context): Tool = Tool(
    name = "device_info",
    description = "Read the device's hardware and system info: brand, model, Android version, battery level and charging state, network type and online state, screen size and density, storage capacity, locale and timezone.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { }
        )
    },
    execute = {
        listOf(UIMessagePart.Text(collectDeviceInfo(context.applicationContext).toString()))
    }
)

// ===================== get_location =====================

internal fun buildLocationTool(context: Context): Tool = Tool(
    name = "get_location",
    description = """
        Get the device's current location (GPS/network based): latitude, longitude, accuracy,
        optional altitude/speed/bearing, provider and timestamp.
        Requires location permission; if it is not granted, an error is returned.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { }
        )
    },
    execute = {
        val appContext = context.applicationContext
        if (!hasPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) &&
            !hasPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            noPermissionResult(
                "ACCESS_FINE_LOCATION",
                "未授予定位权限，请在开启「定位」工具时允许，或到系统设置中授权。"
            )
        } else {
            val loc = currentLocation(appContext)
            if (loc == null) {
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "LOCATION_UNAVAILABLE")
                    put("message", "暂时无法获取定位，请确认已开启定位服务（GPS/网络定位）并在室外重试。")
                }.toString()))
            } else {
                listOf(UIMessagePart.Text(locationJson(loc).toString()))
            }
        }
    }
)

// ===================== contacts_query =====================

internal fun buildContactsQueryTool(context: Context): Tool = Tool(
    name = "contacts_query",
    description = """
        Search the phone's contacts by name or phone number.
        Pass an optional keyword (matches name or number substring) and a limit (default 20, max 100).
        Returns contact id, display name, phone numbers with type labels. Read-only.
        Requires READ_CONTACTS permission; if not granted, an error is returned.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("keyword", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional name or number fragment. Omit to list recent contacts.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max contacts to return. Default 20, max 100.")
                })
            }
        )
    },
    execute = { args ->
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) {
            return@Tool noPermissionResult(
                "READ_CONTACTS",
                "未授予通讯录权限，请在开启「通讯录」工具时允许，或到系统设置中授权。"
            )
        }
        val params = args.jsonObject
        val keyword = params["keyword"]?.jsonPrimitive?.contentOrNull?.trim()
        val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 100) ?: 20

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
        )
        val selection: String?
        val argsArr: Array<String>?
        if (!keyword.isNullOrBlank()) {
            selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? " +
                "OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            argsArr = arrayOf("%$keyword%", "%$keyword%")
        } else {
            selection = null
            argsArr = null
        }

        // 一个联系人可能有多个号码：按 contactId 聚合成一条
        data class Row(val id: Long, val name: String, val number: String, val typeLabel: String)
        val rows = ArrayList<Row>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, selection, argsArr,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
            val labelIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL)
            while (cursor.moveToNext()) {
                val type = cursor.getInt(typeIdx)
                val custom = cursor.getString(labelIdx)
                @Suppress("DEPRECATION")
                val typeLabel = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                    context.resources, type, custom
                )?.toString().orEmpty()
                rows.add(
                    Row(
                        id = cursor.getLong(idIdx),
                        name = cursor.getString(nameIdx).orEmpty(),
                        number = cursor.getString(numIdx).orEmpty(),
                        typeLabel = typeLabel
                    )
                )
            }
        }

        val contacts = buildJsonArray {
            rows.groupBy { it.id }.entries.take(limit).forEach { (id, group) ->
                add(buildJsonObject {
                    put("contact_id", id)
                    put("name", group.first().name)
                    put("phones", buildJsonArray {
                        group.forEach { r ->
                            add(buildJsonObject {
                                put("number", r.number)
                                put("type", r.typeLabel)
                            })
                        }
                    })
                })
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("count", contacts.size)
            put("contacts", contacts)
        }.toString()))
    }
)

// ===================== sms_query =====================

internal fun buildSmsQueryTool(context: Context): Tool = Tool(
    name = "sms_query",
    description = """
        Query SMS messages on the phone (read-only).
        Parameters: folder (inbox/sent, default inbox), optional keyword (matches message body),
        limit (default 20, max 100). Returns address (sender/recipient number), body, timestamp,
        read state. Requires READ_SMS permission; if not granted, an error is returned.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("folder", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("inbox"); add("sent") })
                    put("description", "inbox (default) or sent.")
                })
                put("keyword", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional keyword matched against message body.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max messages to return. Default 20, max 100.")
                })
            }
        )
    },
    execute = { args ->
        if (!hasPermission(context, Manifest.permission.READ_SMS)) {
            return@Tool noPermissionResult(
                "READ_SMS",
                "未授予短信读取权限，请在开启「短信与通话记录」工具时允许，或到系统设置中授权。"
            )
        }
        val params = args.jsonObject
        val folder = params["folder"]?.jsonPrimitive?.contentOrNull ?: "inbox"
        val keyword = params["keyword"]?.jsonPrimitive?.contentOrNull?.trim()
        val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 100) ?: 20

        val uri = when (folder) {
            "sent" -> Telephony.Sms.Sent.CONTENT_URI
            else -> Telephony.Sms.Inbox.CONTENT_URI
        }
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ
        )
        val selection = if (!keyword.isNullOrBlank()) "${Telephony.Sms.BODY} LIKE ?" else null
        val selectionArgs = if (!keyword.isNullOrBlank()) arrayOf("%$keyword%") else null

        val messages = buildJsonArray {
            context.contentResolver.query(
                uri, projection, selection, selectionArgs,
                "${Telephony.Sms.DATE} DESC LIMIT $limit"
            )?.use { cursor ->
                val addrIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val readIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                while (cursor.moveToNext()) {
                    add(buildJsonObject {
                        put("address", cursor.getString(addrIdx).orEmpty())
                        put("body", cursor.getString(bodyIdx).orEmpty())
                        val dateMs = cursor.getLong(dateIdx)
                        put(
                            "timestamp",
                            if (dateMs > 0) Instant.ofEpochMilli(dateMs).atZone(ZoneId.systemDefault()).toString() else ""
                        )
                        put("type", smsTypeLabel(cursor.getInt(typeIdx)))
                        put("read", cursor.getInt(readIdx) == 1)
                    })
                }
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("folder", folder)
            put("count", messages.size)
            put("messages", messages)
        }.toString()))
    }
)

private fun smsTypeLabel(type: Int): String = when (type) {
    Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
    Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
    Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
    Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbox"
    else -> "other"
}

// ===================== call_log_query =====================

internal fun buildCallLogQueryTool(context: Context): Tool = Tool(
    name = "call_log_query",
    description = """
        Query the phone's call history (read-only).
        Parameters: call_type (incoming/outgoing/missed, omit for all), limit (default 20, max 100).
        Returns number, cached contact name, timestamp, duration in seconds, and call type.
        Requires READ_CALL_LOG permission; if not granted, an error is returned.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("call_type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("incoming"); add("outgoing"); add("missed") })
                    put("description", "Filter by call type. Omit to return all types.")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("description", "Max records to return. Default 20, max 100.")
                })
            }
        )
    },
    execute = { args ->
        if (!hasPermission(context, Manifest.permission.READ_CALL_LOG)) {
            return@Tool noPermissionResult(
                "READ_CALL_LOG",
                "未授予通话记录权限，请在开启「短信与通话记录」工具时允许，或到系统设置中授权。"
            )
        }
        val params = args.jsonObject
        val callType = params["call_type"]?.jsonPrimitive?.contentOrNull
        val limit = params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 100) ?: 20

        val typeInt = when (callType) {
            "incoming" -> CallLog.Calls.INCOMING_TYPE
            "outgoing" -> CallLog.Calls.OUTGOING_TYPE
            "missed" -> CallLog.Calls.MISSED_TYPE
            else -> null
        }
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE
        )
        val selection = if (typeInt != null) "${CallLog.Calls.TYPE} = ?" else null
        val selectionArgs = if (typeInt != null) arrayOf(typeInt.toString()) else null

        val records = buildJsonArray {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, selection, selectionArgs,
                "${CallLog.Calls.DATE} DESC LIMIT $limit"
            )?.use { cursor ->
                val numIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                while (cursor.moveToNext()) {
                    add(buildJsonObject {
                        put("number", cursor.getString(numIdx).orEmpty())
                        put("name", cursor.getString(nameIdx).orEmpty())
                        val dateMs = cursor.getLong(dateIdx)
                        put(
                            "timestamp",
                            if (dateMs > 0) Instant.ofEpochMilli(dateMs).atZone(ZoneId.systemDefault()).toString() else ""
                        )
                        put("duration_seconds", cursor.getLong(durIdx))
                        put("call_type", callTypeLabel(cursor.getInt(typeIdx)))
                    })
                }
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("count", records.size)
            put("calls", records)
        }.toString()))
    }
)

private fun callTypeLabel(type: Int): String = when (type) {
    CallLog.Calls.INCOMING_TYPE -> "incoming"
    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
    CallLog.Calls.MISSED_TYPE -> "missed"
    CallLog.Calls.REJECTED_TYPE -> "rejected"
    CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
    else -> "other"
}
