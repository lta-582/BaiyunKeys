package cn.baiyunkeys.app.data

import android.content.ContentResolver
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import cn.baiyunkeys.app.core.LockBiz
import java.io.File

/**
 * 从“平安回家”官方 App 的本地数据库提取门禁参数。
 *
 * 数据来源：/data/data/com.huacheng.baiyunuser/databases/(32位hash).db 中的 t_device 表，
 * 关键字段 MAC_NUM / PRODUCT_KEY / bluetoothName（与原版小程序指导页一致）。
 *
 * 提取路径：用户先用文件管理器 / 系统备份 / 电脑 adb 导出数据库文件夹，
 * 再通过系统文件夹选择器授权，由本应用解析其中的 .db（自动兼容 -wal 日志）。
 */
object DbExtractor {

    data class ExtractedDoor(
        val mac: String,
        val key: String,
        val bluetoothName: String,
        val doorName: String
    )

    class ExtractException(message: String) : Exception(message)

    // 列名候选（大小写不敏感匹配，按优先级排列）
    private val MAC_CANDIDATES =
        listOf("MAC_NUM", "macNum", "mac_num", "mac", "MAC", "deviceId", "device_id")
    private val KEY_CANDIDATES =
        listOf("PRODUCT_KEY", "productKey", "product_key", "key", "KEY", "productkey")
    private val BT_NAME_CANDIDATES =
        listOf("bluetoothName", "BLUETOOTH_NAME", "bluetooth_name", "btName", "bt_name")
    private val NAME_CANDIDATES = listOf(
        "deviceName", "device_name", "DEVICE_NAME",
        "roomName", "room_name", "ROOM_NAME",
        "address", "ADDRESS", "doorName", "door_name", "name", "NAME"
    )

    // ---------- SAF 文件夹导入 ----------

    /**
     * 把用户授权文件夹（含子目录）内的数据库相关文件复制到 destDir。
     * @return 复制成功的本地文件列表
     */
    fun copyDatabaseFromTree(
        resolver: ContentResolver,
        treeUri: Uri,
        destDir: File
    ): List<File> {
        if (!destDir.exists()) destDir.mkdirs()
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val entries = ArrayList<Pair<String, String>>() // (documentId, 文件名)
        walkTree(resolver, treeUri, rootDocId, 0, entries)
        val wanted = entries.filter { (_, name) ->
            name.endsWith(".db") || name.endsWith(".db-wal") || name.endsWith(".db-shm")
        }
        if (wanted.isEmpty()) {
            throw ExtractException("所选文件夹中没有 .db 文件，请确认导出的是 databases 文件夹")
        }
        val out = ArrayList<File>()
        for ((docId, name) in wanted) {
            try {
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                resolver.openInputStream(uri)?.use { input ->
                    val f = File(destDir, name)
                    f.outputStream().use { output -> input.copyTo(output) }
                    out.add(f)
                }
            } catch (_: Exception) {
                // 单个文件复制失败不影响其他文件
            }
        }
        if (out.none { it.name.endsWith(".db") }) {
            throw ExtractException("数据库文件复制失败，请重新选择文件夹")
        }
        return out
    }

    private fun walkTree(
        resolver: ContentResolver,
        treeUri: Uri,
        dirDocId: String,
        depth: Int,
        out: MutableList<Pair<String, String>>
    ) {
        if (depth > 3) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        try {
            resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    if (idIdx < 0) continue
                    val id = c.getString(idIdx) ?: continue
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walkTree(resolver, treeUri, id, depth + 1, out)
                    } else {
                        out.add(id to id.substringAfterLast('/'))
                    }
                }
            }
        } catch (_: Exception) {
            // 部分设备对子目录枚举支持不佳，忽略即可
        }
    }

    // ---------- 解析 ----------

    /** 解析 .db 文件列表，返回提取到的门禁（按 MAC 去重） */
    fun parseDatabases(dbFiles: List<File>): List<ExtractedDoor> {
        val out = LinkedHashMap<String, ExtractedDoor>()
        for (f in dbFiles) {
            if (!f.name.endsWith(".db")) continue
            try {
                parseOneDb(f, out)
            } catch (_: Exception) {
                // 非门禁数据库（如 WebView 缓存），跳过
            }
        }
        return out.values.toList()
    }

    private fun parseOneDb(file: File, out: LinkedHashMap<String, ExtractedDoor>) {
        // 用读写方式打开副本：若存在 -wal，SQLite 会在关闭时自动 checkpoint 合并
        val db = SQLiteDatabase.openDatabase(
            file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
        )
        try {
            val tables = ArrayList<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) tables.add(c.getString(0))
            }
            val table = tables.firstOrNull { it.equals("t_device", true) }
                ?: tables.firstOrNull { t -> tableHasDoorColumns(db, t) }
                ?: return
            db.rawQuery("SELECT * FROM `$table`", null).use { c ->
                val cols = c.columnNames.toList()
                val macCol = pickColumn(cols, MAC_CANDIDATES) ?: return
                val keyCol = pickColumn(cols, KEY_CANDIDATES) ?: return
                val btCol = pickColumn(cols, BT_NAME_CANDIDATES)
                val nameCol = pickColumn(cols, NAME_CANDIDATES)
                val macIdx = c.getColumnIndex(macCol)
                val keyIdx = c.getColumnIndex(keyCol)
                val btIdx = if (btCol != null) c.getColumnIndex(btCol) else -1
                val nameIdx = if (nameCol != null) c.getColumnIndex(nameCol) else -1
                var seq = 0
                while (c.moveToNext()) {
                    seq++
                    val mac = normalizeMac(c.getString(macIdx) ?: "")
                    val key = LockBiz.sanitizeKey(c.getString(keyIdx) ?: "")
                    if (!LockBiz.isValidMac(mac) || !LockBiz.isValidKey(key)) continue
                    val bt = if (btIdx >= 0) (c.getString(btIdx) ?: "").trim() else ""
                    val name = when {
                        nameIdx >= 0 && !c.getString(nameIdx).isNullOrBlank() ->
                            c.getString(nameIdx).trim()
                        bt.isNotEmpty() -> bt
                        else -> "门禁$seq"
                    }
                    out[mac] = ExtractedDoor(mac, key, bt.uppercase(), name)
                }
            }
        } finally {
            db.close()
        }
    }

    /** 兜底定位门禁表：存在 MAC 与 Key 两类列的任意表 */
    private fun tableHasDoorColumns(db: SQLiteDatabase, table: String): Boolean {
        return try {
            db.rawQuery("SELECT * FROM `$table` LIMIT 0", null).use { c ->
                val cols = c.columnNames.toList()
                pickColumn(cols, MAC_CANDIDATES) != null && pickColumn(cols, KEY_CANDIDATES) != null
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun pickColumn(cols: List<String>, candidates: List<String>): String? {
        for (c in candidates) {
            cols.firstOrNull { it.equals(c, true) }?.let { return it }
        }
        return null
    }

    /** 归一化为 AA:BB:CC:DD:EE:FF（安卓连接与校验要求冒号分隔） */
    fun normalizeMac(raw: String): String {
        val sanitized = LockBiz.sanitizeMacInput(raw)
        val hex = sanitized.replace(":", "")
        return if (hex.length == 12) hex.chunked(2).joinToString(":") else sanitized
    }
}
