package cn.baiyunkeys.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 门禁配置存储。
 * 移植自小程序 utils/config.js：version=2 的 {currentId, items[]} 结构，
 * 含旧版单对象格式的迁移逻辑；另存免责声明与快速开锁偏好。
 */
class ConfigRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("baiyun_keys", Context.MODE_PRIVATE)

    fun generateId(): String =
        "door_${System.currentTimeMillis().toString(36)}_${(Math.random() * 1e6).toInt().toString(36)}"

    private fun sanitize(input: JSONObject, reuseId: String? = null): DoorConfig {
        val id = if (!input.optString("id").isNullOrEmpty()) input.optString("id")
        else reuseId ?: generateId()
        return DoorConfig(
            id = id,
            doorName = input.optString("doorName").trim().uppercase(),
            mac = input.optString("mac").trim().uppercase(),
            key = input.optString("key").trim().uppercase(),
            bluetoothName = input.optString("bluetoothName").trim().uppercase(),
            logEnabled = input.optBoolean("logEnabled", false)
        )
    }

    private fun readState(): State {
        val raw = prefs.getString(KEY_CONFIG, null) ?: return State()
        return try {
            val obj = JSONObject(raw)
            if (obj.optInt("version") == 2 && obj.has("items")) {
                val itemsArr = obj.getJSONArray("items")
                val items = ArrayList<DoorConfig>()
                for (i in 0 until itemsArr.length()) {
                    items.add(sanitize(itemsArr.getJSONObject(i)))
                }
                var currentId = obj.optString("currentId")
                if (currentId.isEmpty() || items.none { it.id == currentId }) {
                    currentId = items.firstOrNull()?.id ?: ""
                }
                State(currentId, items)
            } else {
                // 旧版单对象格式迁移
                val legacy = sanitize(obj)
                State(legacy.id, listOf(legacy))
            }
        } catch (e: Exception) {
            State()
        }
    }

    private fun writeState(state: State) {
        val obj = JSONObject()
        obj.put("version", 2)
        obj.put("currentId", state.currentId)
        val arr = JSONArray()
        state.items.forEach { d ->
            val o = JSONObject()
            o.put("id", d.id)
            o.put("doorName", d.doorName)
            o.put("mac", d.mac)
            o.put("key", d.key)
            o.put("bluetoothName", d.bluetoothName)
            o.put("logEnabled", d.logEnabled)
            arr.put(o)
        }
        obj.put("items", arr)
        prefs.edit().putString(KEY_CONFIG, obj.toString()).apply()
    }

    fun readDoorConfigList(): List<DoorConfig> = readState().items

    fun readDoorConfig(): DoorConfig {
        val s = readState()
        if (s.currentId.isEmpty()) return DoorConfig()
        return s.items.find { it.id == s.currentId } ?: DoorConfig()
    }

    fun saveDoorConfig(config: DoorConfig): DoorConfig {
        val state = readState()
        val normalized = if (config.id.isEmpty()) config.copy(id = generateId()) else config
        val idx = state.items.indexOfFirst { it.id == normalized.id }
        val items = state.items.toMutableList()
        if (idx >= 0) items[idx] = normalized else items.add(normalized)
        writeState(State(normalized.id, items))
        return normalized
    }

    fun deleteDoorConfig(id: String) {
        val state = readState()
        val items = state.items.filter { it.id != id }
        if (items.size == state.items.size) return
        val nextCurrent = if (state.currentId == id) "" else state.currentId
        writeState(State(nextCurrent, items))
    }

    fun setActiveDoorConfig(id: String): DoorConfig {
        val state = readState()
        if (state.items.none { it.id == id }) return readDoorConfig()
        writeState(state.copy(currentId = id))
        return state.items.find { it.id == id } ?: DoorConfig()
    }

    // ---------- 偏好 ----------

    var quickUnlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUICK_UNLOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_QUICK_UNLOCK, value).apply()

    var disclaimerAccepted: Boolean
        get() = prefs.getBoolean(KEY_DISCLAIMER, false)
        set(value) = prefs.edit().putBoolean(KEY_DISCLAIMER, value).apply()

    private data class State(
        val currentId: String = "",
        val items: List<DoorConfig> = emptyList()
    )

    companion object {
        private const val KEY_CONFIG = "doorConfig"
        private const val KEY_QUICK_UNLOCK = "quickUnlockEnabled"
        private const val KEY_DISCLAIMER = "globalDisclaimerAccepted"
    }
}
