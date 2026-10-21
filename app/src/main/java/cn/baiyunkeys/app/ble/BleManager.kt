package cn.baiyunkeys.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import cn.baiyunkeys.app.core.BleProtocol
import cn.baiyunkeys.app.core.HexUtil
import cn.baiyunkeys.app.core.LockBiz
import java.util.Locale
import java.util.UUID

/**
 * 蓝牙门禁开锁管理器。
 *
 * 流程移植自小程序 pages/index/index.js：
 *   权限/适配器 -> 连接(优先 MAC 直连，失败回退扫描) -> 服务发现 -> 特征值识别
 *   -> 开启通知 -> 读取随机数种子 -> 发送 DES 握手指令 -> 监听回执
 *
 * 同时吸收 SafeBaiyun(Android 原版) 的经验：按 MAC 直连、写入成功即视为执行成功。
 */
class BleManager(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onLog(msg: String)
        fun onStatus(msg: String)
        fun onFinished(success: Boolean, msg: String)
    }

    companion object {
        val SERVICE_CANDIDATES = listOf(
            "0734594a-a8e7-4b1a-a6b1-cd5243059a57",
            "14839ac4-7d7e-415c-9a42-167340cf2339"
        )
        const val DISCOVERY_TIMEOUT = 10_000L
        const val READ_TIMEOUT = 5_000L
        const val NOTIFY_TIMEOUT = 3_000L
        const val USER_ACK_TIMEOUT = 3_500L
        const val FLOW_TIMEOUT = 45_000L
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var currentKey: String = ""

    // 协议状态（与 index.js createBleState 对应）
    private var handshakeOk = false
    private var communicateBuffer = ""
    private var timeSyncBuffer = ""
    private var cardSyncBuffer = ""
    private var tempCommKey = ""

    // 流程控制
    private var stage = Stage.IDLE
    private var flowTimeoutHandle: Runnable? = null
    private var ackTimeoutHandle: Runnable? = null
    private var seedTimeoutHandle: Runnable? = null
    private var writeAckHandle: Runnable? = null
    private var notifyFallbackHandle: Runnable? = null
    private var finished = false
    private var scanCallback: ScanCallback? = null

    // 通知开启队列与种子读取目标（Android GATT 操作串行，必须逐个等回调）
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var seedReadChar: BluetoothGattCharacteristic? = null

    // 直连回退扫描（部分门锁不支持 MAC 直连，需扫描后发现设备）
    private var currentMac = ""
    private var currentBtName = ""
    private var directConnectAttempted = false
    private var scanFallbackStarted = false

    private enum class Stage { IDLE, WAITING_SEED, WAITING_ACK }

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter

    // ---------- 权限 ----------

    fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else true
    }

    fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    // ---------- 主入口 ----------

    @SuppressLint("MissingPermission")
    fun unlock(mac: String, key: String, bluetoothName: String) {
        if (finished) resetState()
        finished = false
        currentMac = mac
        currentBtName = bluetoothName
        directConnectAttempted = false
        scanFallbackStarted = false
        currentKey = LockBiz.sanitizeKey(key)
        currentMacBytes = try {
            LockBiz.macToBytes(mac)
        } catch (_: Exception) {
            null
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            fail("请先在手机系统设置中开启蓝牙功能")
            return
        }
        log("开始蓝牙开锁流程")
        startFlowTimeout()

        // 优先按 MAC 直连（Android 特权，无需扫描）
        if (LockBiz.isValidMac(mac)) {
            try {
                val device = adapter.getRemoteDevice(mac.trim().uppercase(Locale.ROOT))
                log("尝试直连门锁 $mac")
                status("正在连接门锁...")
                directConnectAttempted = true
                gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, false, gattCallback)
                }
                return
            } catch (e: IllegalArgumentException) {
                log("MAC 直连失败：${e.message}，回退扫描")
            } catch (e: SecurityException) {
                fail("缺少蓝牙权限：${e.message}")
                return
            }
        }
        // 回退：扫描匹配
        startScan(mac, bluetoothName)
    }

    // ---------- 扫描回退（移植 index.js discoverDevice 的匹配逻辑） ----------

    @SuppressLint("MissingPermission")
    private fun startScan(mac: String, bluetoothName: String) {
        if (!hasScanPermission()) {
            fail("缺少蓝牙扫描权限，请在系统设置中授予")
            return
        }
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            fail("当前设备不支持蓝牙扫描")
            return
        }
        val target = mac.replace(Regex("[^0-9A-F]"), "").uppercase(Locale.ROOT)
        val reversed = StringBuilder(target).reverse().toString()
        val targetName = bluetoothName.uppercase(Locale.ROOT)
        val targetNameHex = if (targetName.isNotEmpty()) {
            HexUtil.bytesToHex(targetName.toByteArray(Charsets.US_ASCII))
        } else ""

        status("正在扫描门锁...")
        log("开始扫描蓝牙设备")
        val cb = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
                mainHandler.post { fail("蓝牙扫描失败（错误码 $errorCode）") }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { handleScanResult(it, target, reversed, targetName, targetNameHex) }
            }

            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { mainHandler.post { handleScanResult(it, target, reversed, targetName, targetNameHex) } }
            }
        }
        scanCallback = cb
        scanner.startScan(cb)
        mainHandler.postDelayed({
            if (!finished) fail("扫描蓝牙设备超时，请靠近门锁后重试")
        }, DISCOVERY_TIMEOUT)
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(
        result: ScanResult,
        target: String,
        reversed: String,
        targetName: String,
        targetNameHex: String
    ) {
        if (finished) return
        val device = result.device ?: return
        val address = device.address?.replace(Regex("[^0-9A-F]"), "")?.uppercase(Locale.ROOT) ?: ""
        val name = (device.name ?: "").uppercase(Locale.ROOT)
        var matched = false
        if (target.isNotEmpty() && address == target) matched = true
        if (!matched && targetName.isNotEmpty() && name == targetName) {
            log("匹配成功: 蓝牙名称 $name")
            matched = true
        }
        if (!matched) {
            val record = result.scanRecord?.bytes
            if (record != null) {
                val hex = HexUtil.bytesToHex(record)
                if (target.isNotEmpty() && hex.contains(target)) matched = true
                if (!matched && reversed.isNotEmpty() && hex.contains(reversed)) matched = true
                if (!matched && targetNameHex.isNotEmpty() && hex.contains(targetNameHex)) matched = true
            }
        }
        if (matched) {
            log("找到设备：${device.address}")
            stopScan()
            status("正在连接门锁...")
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanCallback?.let { cb ->
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(cb)
            } catch (_: Exception) {
            }
            scanCallback = null
        }
    }

    // ---------- GATT 回调 ----------
    // 回调实现在 Java 类 GattCallback 中（规避 Kotlin K2 对
    // onCharacteristicRead/onCharacteristicChanged 新旧重载同时覆盖的解析缺陷），
    // 此处仅提供转发入口方法，由 Java 类在 binder 线程调用，内部统一切主线程。

    private val gattCallback = GattCallback(this)

    /** 连接状态变化（GattCallback 转发） */
    fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
        mainHandler.post {
            log("连接状态改变 status=$status, newState=$newState")
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    log("开始搜索服务")
                    g?.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    if (!finished && status != BluetoothGatt.GATT_SUCCESS &&
                        directConnectAttempted && !scanFallbackStarted
                    ) {
                        // MAC 直连在 GATT 层失败（如 status 133/62）：回退扫描匹配，
                        // 与小程序“先扫描发现设备再连接”的流程一致
                        scanFallbackStarted = true
                        log("MAC 直连失败（status=$status），回退扫描匹配")
                        closeGatt()
                        startScan(currentMac, currentBtName)
                    } else if (!finished) {
                        fail("蓝牙连接已断开")
                    }
                }
            }
        }
    }

    /** 服务发现完成（GattCallback 转发） */
    fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
        mainHandler.post {
            if (status != BluetoothGatt.GATT_SUCCESS || g == null) {
                fail("蓝牙服务发现失败（status=$status）")
                return@post
            }
            log("搜索到服务：${g.services.map { it.uuid }}")
            handleService(g)
        }
    }

    /** 特征值读取回调（新旧两个重载最终都汇聚到这里，GattCallback 转发） */
    fun onCharacteristicRead(value: ByteArray?, status: Int) {
        mainHandler.post {
            if (stage == Stage.WAITING_SEED) {
                seedTimeoutHandle?.let { mainHandler.removeCallbacks(it) }
                stage = Stage.IDLE
                if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
                    handleSeed(value)
                } else {
                    fail("读取随机数失败（status=$status）")
                }
            }
        }
    }

    /** 特征值写入回调（GattCallback 转发） */
    fun onCharacteristicWrite(status: Int) {
        mainHandler.post {
            log("特征码写入回调 status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("指令写入成功，等待门锁回执")
                status("指令已发送，等待门锁响应...")
                // SafeBaiyun 行为：写入成功即视为执行；给 3.5s 等协议回执，超时则按成功收尾
                writeAckHandle?.let { mainHandler.removeCallbacks(it) }
                writeAckHandle = Runnable {
                    if (!finished) {
                        success("开锁指令已执行")
                    }
                }.also { mainHandler.postDelayed(it, USER_ACK_TIMEOUT) }
            } else {
                fail("指令写入失败（status=$status）")
            }
        }
    }

    /** 特征值变更通知（新旧两个重载最终都汇聚到这里，GattCallback 转发） */
    fun onCharacteristicChanged(value: ByteArray) {
        mainHandler.post {
            // 与小程序 handleValueChange 一致：等待种子期间，通知数据即种子。
            // 微信的 readBLECharacteristicValue 结果经 value-change 事件返回；
            // 部分门锁固件同样只推送种子、不响应读请求，两条路径都兼容。
            if (stage == Stage.WAITING_SEED) {
                seedTimeoutHandle?.let { mainHandler.removeCallbacks(it) }
                stage = Stage.IDLE
                handleSeed(value)
                return@post
            }
            processNotification(value)
        }
    }

    /** 描述符写入回调（GattCallback 转发）——驱动通知开启队列前进 */
    fun onDescriptorWrite(status: Int) {
        mainHandler.post {
            log("通知描述符写入回调 status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("通知描述符写入失败 status=$status，继续后续流程")
            }
            val g = gatt
            if (g == null || finished) return@post
            // 已进入读种子阶段：迟到的回调不再触发新操作
            if (stage == Stage.WAITING_SEED) return@post
            enableNextNotify(g)
        }
    }

    // ---------- 服务与特征值（移植 prepareChannel / handleService） ----------

    @SuppressLint("MissingPermission")
    private fun handleService(gatt: BluetoothGatt) {
        val service = gatt.services.firstOrNull { s ->
            SERVICE_CANDIDATES.any { it.equals(s.uuid.toString(), ignoreCase = true) }
        }
        if (service == null) {
            fail("未找到目标蓝牙服务，请确认门锁已开启蓝牙广播")
            return
        }
        log("开始处理服务，共${service.characteristics.size}个特征")
        var readable: BluetoothGattCharacteristic? = null
        var writable: BluetoothGattCharacteristic? = null
        val notifyChars = ArrayList<BluetoothGattCharacteristic>()
        service.characteristics.forEach { ch ->
            val p = ch.properties
            if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) readable = ch
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            ) writable = ch
            if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            ) notifyChars.add(ch)
        }
        val readChar = readable
        val writeChar = writable
        if (readChar == null || writeChar == null) {
            fail("未找到可读写的蓝牙特征")
            return
        }
        log("读取蓝牙特征成功")
        seedReadChar = readChar

        if (notifyChars.isEmpty()) {
            startSeedRead(gatt, readChar)
            return
        }
        // 关键：Android GATT 栈同时只执行一个操作。必须逐个开启通知
        // （等每个 CCCD 描述符写入回调）全部完成后，才能读随机数；
        // 否则 readCharacteristic 会被栈直接丢弃，回调永不来 → "读取随机数超时"。
        // 对应小程序：await enableNotifications(...) 全部完成后才 await readSeed(...)。
        notifyQueue.clear()
        notifyQueue.addAll(notifyChars)
        notifyFallbackHandle?.let { mainHandler.removeCallbacks(it) }
        notifyFallbackHandle = Runnable {
            if (!finished && stage != Stage.WAITING_SEED) {
                log("开启通知等待超时，继续读取随机数")
                notifyQueue.clear()
                seedReadChar?.let { startSeedRead(gatt, it) }
            }
        }.also { mainHandler.postDelayed(it, NOTIFY_TIMEOUT) }
        enableNextNotify(gatt)
    }

    /** 从队首取一个通知特征开启；队列空 = 全部完成，开始读随机数 */
    @SuppressLint("MissingPermission")
    private fun enableNextNotify(gatt: BluetoothGatt) {
        if (finished) return
        val ch = notifyQueue.removeFirstOrNull() ?: run {
            notifyFallbackHandle?.let { mainHandler.removeCallbacks(it) }
            notifyFallbackHandle = null
            if (stage != Stage.WAITING_SEED) {
                val readChar = seedReadChar ?: return
                startSeedRead(gatt, readChar)
            }
            return
        }
        gatt.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            // 无 CCCD 描述符：本地开关已足够，直接处理下一个
            log("特征 ${ch.uuid} 无 CCCD 描述符，跳过")
            enableNextNotify(gatt)
            return
        }
        cccd.value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val ok = gatt.writeDescriptor(cccd)
        log("开启通知 ${ch.uuid}，描述符写入请求=$ok")
        if (!ok) {
            // 写入被栈拒绝：不等回调，直接处理下一个
            enableNextNotify(gatt)
        }
    }

    /** 读取随机数种子（独立方法：通知流程结束或无需通知时调用） */
    @SuppressLint("MissingPermission")
    private fun startSeedRead(gatt: BluetoothGatt, readChar: BluetoothGattCharacteristic) {
        if (finished || stage == Stage.WAITING_SEED) return
        status("正在读取随机数...")
        stage = Stage.WAITING_SEED
        seedTimeoutHandle?.let { mainHandler.removeCallbacks(it) }
        seedTimeoutHandle = Runnable {
            if (stage == Stage.WAITING_SEED) {
                stage = Stage.IDLE
                fail("读取蓝牙随机数超时")
            }
        }.also { mainHandler.postDelayed(it, READ_TIMEOUT) }
        val started = gatt.readCharacteristic(readChar)
        log("随机数读取请求结果=$started")
        if (!started) {
            // 个别机型 GATT 栈繁忙会直接拒绝读取：400ms 后重试一次
            mainHandler.postDelayed({
                if (stage == Stage.WAITING_SEED && !finished) {
                    val retry = gatt.readCharacteristic(readChar)
                    log("随机数读取重试结果=$retry")
                }
            }, 400L)
        }
    }

    // ---------- 种子 -> 握手 -> 等待回执 ----------

    @SuppressLint("MissingPermission")
    private fun handleSeed(seed: ByteArray) {
        val seedHex = HexUtil.bytesToHex(seed)
        log("随机数：$seedHex")
        val macBytes = currentMacBytes
        if (macBytes == null) {
            fail("缺少 MAC 地址，无法构造握手指令")
            return
        }
        if (currentKey.isEmpty()) {
            fail("缺少可用的解锁 Key")
            return
        }
        val headerBytes = macBytes.copyOfRange(2, 6)
        val handshake = try {
            BleProtocol.buildHandshakeCommandWithHeader(seed, headerBytes, currentKey)
        } catch (e: Exception) {
            fail("构造握手指令失败：${e.message}")
            return
        }
        log("握手指令：${HexUtil.bytesToHex(handshake)}")
        val service = gatt?.services?.firstOrNull { s ->
            SERVICE_CANDIDATES.any { it.equals(s.uuid.toString(), ignoreCase = true) }
        } ?: run {
            fail("蓝牙连接已丢失")
            return
        }
        val writeChar = service.characteristics.firstOrNull { ch ->
            ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        } ?: run {
            fail("未找到可写特征")
            return
        }
        // 按特征属性选择写入类型：支持带响应写入用 DEFAULT，否则用 NO_RESPONSE
        // （对应小程序 wx.writeBLECharacteristicValue 的自动选择）
        writeChar.writeType = if (
            writeChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        ) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        writeChar.value = handshake
        stage = Stage.WAITING_ACK
        startAckTimer("握手指令已发送，等待门锁响应...")
        val result = gatt?.writeCharacteristic(writeChar) ?: false
        log("握手写入结果=$result")
        if (!result) fail("握手指令发送失败")
    }

    private var currentMacBytes: ByteArray? = null

    // ---------- 协议通知处理（1:1 移植 index.js processBleNotification） ----------

    private fun processNotification(value: ByteArray) {
        val hex = HexUtil.bytesToHex(value).uppercase(Locale.ROOT)
        log("收到通知：$hex")
        if (hex.length < 2 || !hex.startsWith("A5")) return

        val command = if (hex.length >= 6) hex.substring(4, 6) else ""
        val tail = hex.takeLast(2)
        val type = if (hex.length >= 20) hex.substring(18, 20) else ""

        // 1. 握手响应
        if (command == "04") {
            val statusField = if (hex.length >= 18) hex.substring(14, 18) else ""
            val statusWord = if (statusField.length == 4) {
                (statusField.substring(2, 4) + statusField.substring(0, 2))
            } else ""
            val isSuccess = hex.length > 24
            if (!isSuccess) {
                val hint = if (statusWord == "000B") "（密钥或 SN 可能不匹配，设备拒绝握手）" else ""
                fail("握手失败，设备返回码 0x$statusWord$hint")
            } else {
                handshakeOk = true
                log("握手成功，已发送开锁指令")
                success("握手成功，等待门锁执行")
            }
            return
        }

        // 2. 通讯密钥协商失败
        if (command == "08") {
            val codeWord = safeSub(hex, hex.length - 6, hex.length - 2)
            val hint = if (codeWord == "E36F") "（门锁拒绝协商，通常是密钥或授权信息不匹配）" else ""
            fail("通讯密钥协商失败，设备返回码 0x$codeWord$hint")
            return
        }

        // 3. 开锁执行结果
        if (type == "87" && tail == "5A") {
            val result = BleProtocol.decodeOpenResult(hex, currentKey)
            if (result.code == "00" || result.code == "02") {
                success(result.message)
            } else {
                fail(result.message)
            }
            return
        }

        if (!handshakeOk) {
            if (command == "04" && tail == "5A") {
                val codeWord = safeSub(hex, hex.length - 4, hex.length - 2) +
                    safeSub(hex, hex.length - 6, hex.length - 4)
                fail("握手失败，设备返回 0x$codeWord")
            }
            return
        }

        // 4. 通讯密钥分片
        if (type == "89") {
            communicateBuffer = hex
            log("设备开始下发通讯密钥片段")
            return
        }

        // 5. 时间同步分片
        if (type == "86") {
            timeSyncBuffer = hex
            log("设备返回时间同步片段")
            return
        }

        // 6. 黑名单同步分片
        if (type == "82") {
            cardSyncBuffer = hex
            log("设备返回黑名单同步片段")
            return
        }

        // 7. 通讯密钥协商完成（第二帧拼接解密）
        if (tail == "5A" && communicateBuffer.isNotEmpty()) {
            val concat = communicateBuffer + hex
            val bodyHex = safeSub(concat, 24, 56)
            val tempKey = if (bodyHex.length == 32) {
                BleProtocol.decryptCommKey(bodyHex, currentKey)
            } else ""
            if (tempKey.isNotEmpty()) {
                tempCommKey = tempKey
                communicateBuffer = ""
                log("通信密钥协商成功")
            } else {
                communicateBuffer = concat
            }
            return
        }

        // 8. 时间同步完成（拼接解密校验）
        if (tail == "5A" && timeSyncBuffer.isNotEmpty() && tempCommKey.isNotEmpty()) {
            val concat = timeSyncBuffer + hex
            val bodyHex = safeSub(concat, 24, 40)
            val resultHex = if (bodyHex.length == 16) {
                BleProtocol.decryptWithSessionKey(bodyHex, tempCommKey)
            } else ""
            if (resultHex == "0000000000000000") {
                log("门锁时间同步成功")
                timeSyncBuffer = ""
                startAckTimer("时间同步完成，等待门锁最终回执...")
            } else {
                timeSyncBuffer = concat
            }
            return
        }
    }

    private fun safeSub(s: String, from: Int, to: Int): String =
        if (from >= s.length) "" else s.substring(from, minOf(to, s.length))

    // ---------- 定时器与收尾 ----------

    private fun startFlowTimeout() {
        flowTimeoutHandle?.let { mainHandler.removeCallbacks(it) }
        flowTimeoutHandle = Runnable {
            if (!finished) fail("未收到门锁响应，请确认门锁状态后再次尝试")
        }.also { mainHandler.postDelayed(it, FLOW_TIMEOUT) }
    }

    private fun startAckTimer(message: String) {
        status(message)
        ackTimeoutHandle?.let { mainHandler.removeCallbacks(it) }
        ackTimeoutHandle = Runnable {
            log("等待门锁响应中超时提醒")
        }.also { mainHandler.postDelayed(it, USER_ACK_TIMEOUT) }
    }

    private fun clearTimers() {
        flowTimeoutHandle?.let { mainHandler.removeCallbacks(it) }
        ackTimeoutHandle?.let { mainHandler.removeCallbacks(it) }
        seedTimeoutHandle?.let { mainHandler.removeCallbacks(it) }
        writeAckHandle?.let { mainHandler.removeCallbacks(it) }
        notifyFallbackHandle?.let { mainHandler.removeCallbacks(it) }
        flowTimeoutHandle = null
        ackTimeoutHandle = null
        seedTimeoutHandle = null
        writeAckHandle = null
        notifyFallbackHandle = null
    }

    private fun success(msg: String) = finish(true, msg)
    private fun fail(msg: String) = finish(false, msg)

    @Synchronized
    private fun finish(success: Boolean, msg: String) {
        if (finished) return
        finished = true
        clearTimers()
        stopScan()
        mainHandler.post {
            log(msg)
            listener.onFinished(success, msg)
        }
        closeGatt()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    fun resetState() {
        clearTimers()
        stopScan()
        notifyQueue.clear()
        seedReadChar = null
        directConnectAttempted = false
        scanFallbackStarted = false
        finished = false
        handshakeOk = false
        communicateBuffer = ""
        timeSyncBuffer = ""
        cardSyncBuffer = ""
        tempCommKey = ""
        stage = Stage.IDLE
    }

    /** 完全释放（页面销毁时调用，静默断开，不弹提示） */
    fun release() {
        finished = true
        clearTimers()
        stopScan()
        closeGatt()
    }

    private fun log(msg: String) = listener.onLog(msg)
    private fun status(msg: String) = listener.onStatus(msg)
}
