package cn.baiyunkeys.app.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.baiyunkeys.app.ble.BleManager
import cn.baiyunkeys.app.core.LockBiz
import cn.baiyunkeys.app.data.ConfigRepository
import cn.baiyunkeys.app.data.DbExtractor
import cn.baiyunkeys.app.data.DoorConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ConfigRepository(app)
    private var bleManager: BleManager? = null
    private var quickUnlockUsed = false

    var doors by mutableStateOf(repo.readDoorConfigList())
        private set
    var currentDoor by mutableStateOf(repo.readDoorConfig())
        private set
    val logs = mutableStateListOf<String>()
    var statusMessage by mutableStateOf("")
        private set
    var loading by mutableStateOf(false)
        private set
    var quickUnlock by mutableStateOf(repo.quickUnlockEnabled)
        private set
    var disclaimerVisible by mutableStateOf(!repo.disclaimerAccepted)
        private set
    var disclaimerCountdown by mutableStateOf(10)
        private set

    var extractState by mutableStateOf<ExtractState>(ExtractState.Idle)
        private set

    sealed class ExtractState {
        data object Idle : ExtractState()
        data object Loading : ExtractState()
        data class Success(val items: List<DbExtractor.ExtractedDoor>) : ExtractState()
        data class Error(val msg: String) : ExtractState()
    }

    // ---------- 开锁 ----------

    fun unlock() {
        val door = currentDoor
        if (loading) return
        if (!LockBiz.isValidMac(door.mac) || !LockBiz.isValidKey(door.key)) {
            statusMessage = "门禁参数不完整，请先在配置页检查 MAC 与 Key"
            return
        }
        loading = true
        statusMessage = "正在准备..."
        addLog("开始蓝牙开锁流程")
        val manager = BleManager(getApplication(), object : BleManager.Listener {
            override fun onLog(msg: String) = addLog(msg)
            override fun onStatus(msg: String) {
                statusMessage = msg
            }
            override fun onFinished(success: Boolean, msg: String) {
                loading = false
                statusMessage = msg
            }
        })
        bleManager?.release()
        bleManager = manager
        manager.unlock(door.mac, door.key, door.bluetoothName)
    }

    fun cancelUnlock() {
        bleManager?.release()
        bleManager = null
        loading = false
        statusMessage = "已取消"
    }

    /** 快速开锁：每次打开 APP 仅自动执行一次 */
    fun triggerQuickUnlockIfEnabled() {
        if (quickUnlock && !quickUnlockUsed && !loading &&
            LockBiz.isValidMac(currentDoor.mac) && LockBiz.isValidKey(currentDoor.key)
        ) {
            quickUnlockUsed = true
            unlock()
        }
    }

    // ---------- 配置管理 ----------

    fun saveDoor(door: DoorConfig) {
        val saved = repo.saveDoorConfig(door)
        doors = repo.readDoorConfigList()
        currentDoor = repo.readDoorConfig()
        statusMessage = "已保存「${saved.doorName.ifEmpty { saved.mac }}」"
    }

    fun deleteDoor(id: String) {
        repo.deleteDoorConfig(id)
        doors = repo.readDoorConfigList()
        currentDoor = repo.readDoorConfig()
    }

    fun setActiveDoor(id: String) {
        currentDoor = repo.setActiveDoorConfig(id)
    }

    fun updateQuickUnlock(enabled: Boolean) {
        quickUnlock = enabled
        repo.quickUnlockEnabled = enabled
    }

    fun acceptDisclaimer() {
        repo.disclaimerAccepted = true
        disclaimerVisible = false
    }

    fun startDisclaimerCountdown() {
        viewModelScope.launch {
            disclaimerCountdown = 10
            while (disclaimerCountdown > 0) {
                kotlinx.coroutines.delay(1000)
                disclaimerCountdown -= 1
            }
        }
    }

    // ---------- 数据库提取 ----------

    /** 文件导入：解析用户授权的文件夹中的 .db */
    fun importFromTree(treeUri: Uri) {
        extractState = ExtractState.Loading
        viewModelScope.launch {
            try {
                val doors = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val cacheDir = extractCacheDir()
                    val files = DbExtractor.copyDatabaseFromTree(resolver, treeUri, cacheDir)
                    DbExtractor.parseDatabases(files)
                }
                extractState = if (doors.isEmpty()) {
                    ExtractState.Error("未在数据库中找到门禁记录")
                } else {
                    ExtractState.Success(doors)
                }
            } catch (e: Exception) {
                extractState = ExtractState.Error(extractErrorMessage(e))
            }
        }
    }

    private fun extractCacheDir(): File {
        val dir = File(getApplication<Application>().cacheDir, "db_extract")
        if (dir.exists()) dir.listFiles()?.forEach { it.delete() } else dir.mkdirs()
        return dir
    }

    private fun extractErrorMessage(e: Exception): String {
        return when (e) {
            is DbExtractor.ExtractException -> e.message ?: "提取失败"
            else -> "提取失败：${e.message ?: "未知错误"}"
        }
    }

    fun importExtractedDoor(door: DbExtractor.ExtractedDoor) {
        saveDoor(
            DoorConfig(
                id = "",
                doorName = door.doorName,
                mac = door.mac,
                key = door.key,
                bluetoothName = door.bluetoothName
            )
        )
        extractState = ExtractState.Idle
    }

    fun resetExtract() {
        extractState = ExtractState.Idle
    }

    // ---------- 日志 ----------

    private val maxLogs = 50

    private fun addLog(msg: String) {
        val now = System.currentTimeMillis()
        val stamp = android.text.format.DateFormat.format("HH:mm:ss", now).toString()
        logs.add(0, "$stamp $msg")
        while (logs.size > maxLogs) logs.removeAt(logs.size - 1)
    }

    fun clearLogs() {
        logs.clear()
    }

    fun copyLogs(): String = logs.reversed().joinToString("\n")

    override fun onCleared() {
        bleManager?.release()
        bleManager = null
        super.onCleared()
    }
}
