package cn.baiyunkeys.app.core

import java.util.Calendar

/**
 * 蓝牙门禁协议帧构建与解析。
 *
 * 1:1 移植自小程序 utils/bleProtocol.js：
 *  - buildHandshakeCommandWithHeader : 握手指令（A5 14 05 ... 00 5A）
 *  - buildCommKeyCommand             : 通讯密钥协商指令
 *  - buildTimeSyncCommand            : 时间同步指令
 *  - decryptCommKey / decryptWithSessionKey : 会话密钥解密
 *  - decodeOpenResult                : 开锁回执解析
 *
 * 帧格式：A5 | 长度/命令 | ... | 负载(DES 密文) | 校验(和取反) | 5A
 */
object BleProtocol {

    // ---------- 基础工具 ----------

    fun hexToArray(hex: String): IntArray {
        val clean = HexUtil.normalizeHex(hex)
        val result = IntArray(clean.length / 2)
        for (i in result.indices) {
            result[i] = clean.substring(i * 2, i * 2 + 2).toInt(16)
        }
        return result
    }

    fun arrayToHex(bytes: IntArray): String =
        HexUtil.bytesToHex(ByteArray(bytes.size) { bytes[it].toByte() })

    fun bufferToHexUpper(buffer: ByteArray): String = HexUtil.bytesToHex(buffer)

    private fun complementByte(sum: Int): Int = (sum and 0xFF).inv() and 0xFF

    private fun sumBytes(bytes: ByteArray): Int {
        var total = 0
        for (b in bytes) total += b.toInt() and 0xFF
        return total
    }

    private fun xorBytes(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        var acc = bytes[0].toInt() and 0xFF
        for (i in 1 until bytes.size) acc = acc xor (bytes[i].toInt() and 0xFF)
        return acc and 0xFF
    }

    /**
     * 从蓝牙广播名解析设备标识 4 字节。
     * 与 JS extractDeviceIdParts 一致：取名称第 3/5/7/9 位起的两个十六进制字符。
     */
    fun extractDeviceIdParts(name: String?): IntArray {
        val clean = (name ?: "").uppercase().replace(Regex("[^0-9A-Z]"), "")
        require(clean.length >= 11) { "蓝牙名称格式不合法，无法解析设备标识" }
        return intArrayOf(
            clean.substring(3, 5).toInt(16),
            clean.substring(5, 7).toInt(16),
            clean.substring(7, 9).toInt(16),
            clean.substring(9, 11).toInt(16)
        )
    }

    // ---------- 指令构建 ----------

    /**
     * 握手指令（带显式头部，主页流程使用）：
     * A5 14 05 | ids(4) | 00 01 07 | DES8(sum(seed)+sum(key), seed[0..3]+00 00) | 00 5A
     */
    fun buildHandshakeCommandWithHeader(
        random: ByteArray,
        headerBytes: ByteArray,
        productKey: String
    ): ByteArray {
        require(headerBytes.size == 4) { "握手指令头部需提供 4 个字节" }
        require(random.size >= 4) { "随机数至少需要 4 字节" }
        var sum = sumBytes(random)
        sum += sumBytes(HexUtil.hexToBytes(HexUtil.normalizeHex(productKey)))
        val low = sum and 0xFF
        val high = (sum shr 8) and 0xFF
        val temp = byteArrayOf(
            low.toByte(), high.toByte(),
            random[0], random[1], random[2], random[3],
            0, 0
        )
        val encryptedHex = DesCrypto.desEncryptBlockHex(
            productKey, HexUtil.bytesToHex(temp)
        )
        val enc = HexUtil.hexToBytes(encryptedHex)
        val frame = ByteArray(20)
        frame[0] = 0xA5.toByte()
        frame[1] = 0x14
        frame[2] = 0x05
        System.arraycopy(headerBytes, 0, frame, 3, 4)
        frame[7] = 0x00
        frame[8] = 0x01
        frame[9] = 0x07
        System.arraycopy(enc, 0, frame, 10, enc.size)
        frame[18] = 0x00
        frame[19] = 0x5A
        frame[18] = complementByte(sumBytes(frame)).toByte()
        return frame
    }

    /** 握手指令（从蓝牙名称解析头部） */
    fun buildHandshakeCommand(
        random: ByteArray,
        bluetoothName: String,
        productKey: String
    ): ByteArray {
        val ids = extractDeviceIdParts(bluetoothName)
        val header = ByteArray(4) { ids[it].toByte() }
        return buildHandshakeCommandWithHeader(random, header, productKey)
    }

    /**
     * 通讯密钥协商指令：
     * A5 16 01 | ids(4) | 00 00 | sumBody xorBody | 09 | DES8(key, seed[0..3]+00 00) | 00 5A
     */
    fun buildCommKeyCommand(
        random: ByteArray,
        derivedName: String,
        productKey: String
    ): ByteArray {
        require(random.size >= 4) { "随机数至少需要 4 字节" }
        val ids = extractDeviceIdParts(derivedName)
        val pivot = byteArrayOf(random[0], random[1], random[2], random[3], 0, 0)
        val encryptedHex = DesCrypto.desEncryptBlockHex(
            productKey, HexUtil.bytesToHex(pivot)
        )
        val deviceHex = arrayToHex(ids)
        val bodyBytes = HexUtil.hexToBytes(deviceHex + "0000" + bufferToHexUpper(random.copyOf(4)))
        val sumBody = sumBytes(bodyBytes) and 0xFF
        val xorBody = xorBytes(bodyBytes)
        val enc = HexUtil.hexToBytes(encryptedHex)
        val frame = ByteArray(22)
        frame[0] = 0xA5.toByte()
        frame[1] = 0x16
        frame[2] = 0x01
        for (i in 0..3) frame[3 + i] = ids[i].toByte()
        frame[7] = 0x00
        frame[8] = 0x00
        frame[9] = sumBody.toByte()
        frame[10] = xorBody.toByte()
        frame[11] = 0x09
        System.arraycopy(enc, 0, frame, 12, enc.size)
        frame[20] = 0x00
        frame[21] = 0x5A
        frame[20] = complementByte(sumBytes(frame)).toByte()
        return frame
    }

    /**
     * 时间同步指令：
     * A5 16 01 | ids(4) | 00 00 | sumBody xorBody | 06 | DES8(sessionKey, time(7)+00) | 00 5A
     */
    fun buildTimeSyncCommand(
        timeBytes: ByteArray,
        derivedName: String,
        sessionKey: String
    ): ByteArray {
        require(timeBytes.size == 7) { "时间字段需 7 字节" }
        val ids = extractDeviceIdParts(derivedName)
        val payload = ByteArray(8)
        System.arraycopy(timeBytes, 0, payload, 0, 7)
        val encryptedHex = DesCrypto.desEncryptBlockHex(
            sessionKey, HexUtil.bytesToHex(payload)
        )
        val deviceHex = arrayToHex(ids)
        val bodyBytes = HexUtil.hexToBytes(deviceHex + "0000" + HexUtil.bytesToHex(timeBytes))
        val sumBody = sumBytes(bodyBytes) and 0xFF
        val xorBody = xorBytes(bodyBytes)
        val enc = HexUtil.hexToBytes(encryptedHex)
        val frame = ByteArray(22)
        frame[0] = 0xA5.toByte()
        frame[1] = 0x16
        frame[2] = 0x01
        for (i in 0..3) frame[3 + i] = ids[i].toByte()
        frame[7] = 0x00
        frame[8] = 0x00
        frame[9] = sumBody.toByte()
        frame[10] = xorBody.toByte()
        frame[11] = 0x06
        System.arraycopy(enc, 0, frame, 12, enc.size)
        frame[20] = 0x00
        frame[21] = 0x5A
        frame[20] = complementByte(sumBytes(frame)).toByte()
        return frame
    }

    /** 生成 BCD 时间hex：YYMMDDHHmmss + 星期（与 JS generateTimeHex 一致） */
    fun generateTimeHex(date: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = date
        val year = cal.get(Calendar.YEAR) - 2000
        val yearHigh = year / 10
        val yearLow = year % 10
        val yearHex = ((yearHigh shl 4) or yearLow).toString(16).padStart(2, '0')
        val month = (cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        val day = cal.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        val hour = cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val minute = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
        val second = cal.get(Calendar.SECOND).toString().padStart(2, '0')
        val weekdayMap = arrayOf("00", "01", "02", "03", "04", "05", "06")
        val weekday = weekdayMap[cal.get(Calendar.DAY_OF_WEEK) - 1]
        return "$yearHex$month$day$hour$minute$second$weekday".uppercase()
    }

    // ---------- 解密与回执 ----------

    /**
     * 从设备下发的通讯密钥分片中解出会话密钥。
     * 与 JS decryptCommKey 一致：解密后取第二个 8 字节块。
     */
    fun decryptCommKey(bodyHex: String, productKey: String): String {
        val decrypted = DesCrypto.desDecryptBlockHex(productKey, bodyHex)
        if (decrypted.length < 32) return ""
        return decrypted.substring(16, 32).uppercase()
    }

    fun decryptWithSessionKey(bodyHex: String, sessionKey: String): String =
        DesCrypto.desDecryptBlockHex(sessionKey, bodyHex)

    data class OpenResult(val code: String, val message: String)

    /**
     * 解析开锁回执：密文取帧偏移 10 起的 8 字节，解密后第 3 字节为状态：
     * 00 = 开门成功，02 = 门已打开。
     */
    fun decodeOpenResult(frameHex: String, productKey: String): OpenResult {
        val clean = HexUtil.normalizeHex(frameHex)
        if (clean.length < 36) return OpenResult("FF", "数据长度不足，无法解析回执")
        val msgBodyHex = clean.substring(20, 36)
        val decrypted = DesCrypto.desDecryptBlockHex(productKey, msgBodyHex)
        if (decrypted.length != 16) return OpenResult("FF", "回执解密失败")
        val status = decrypted.substring(4, 6)
        return when (status) {
            "00" -> OpenResult(status, "开门成功")
            "02" -> OpenResult(status, "门已打开")
            else -> OpenResult(status, "开门失败，密码无效")
        }
    }
}
