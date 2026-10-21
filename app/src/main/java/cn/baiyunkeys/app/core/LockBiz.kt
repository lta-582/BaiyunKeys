package cn.baiyunkeys.app.core

import java.util.Locale

/**
 * 门禁参数校验与开锁指令组装。
 * 移植自小程序 utils/lockBiz.js。
 */
object LockBiz {

    const val MIN_KEY_LENGTH = 16
    const val MAX_KEY_LENGTH = 32

    private val HEX_PATTERN = Regex("^[0-9A-Fa-f]+$")
    private val MAC_PATTERN = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

    fun sanitizeMacInput(value: String): String =
        value.uppercase(Locale.ROOT).replace(Regex("[^0-9A-F:]"), "")

    fun isValidMac(value: String): Boolean =
        MAC_PATTERN.matches(value.trim().uppercase(Locale.ROOT))

    fun sanitizeKey(value: String): String =
        value.uppercase(Locale.ROOT).replace(Regex("[^0-9A-F]"), "")

    fun isValidKey(value: String): Boolean {
        val s = sanitizeKey(value)
        return s.length in MIN_KEY_LENGTH..MAX_KEY_LENGTH &&
            s.length % 2 == 0 && HEX_PATTERN.matches(s)
    }

    fun macToBytes(mac: String): ByteArray {
        require(isValidMac(mac)) { "MAC 格式错误" }
        return mac.trim().uppercase(Locale.ROOT).split(":")
            .map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * SafeBaiyun 风格的一次性开锁帧（与小程序 lockBiz.js encryptUnlockCommand 一致）：
     *   A5 | 长度 | 05 | MAC[2..6] | 00 01 07 | DES(sum(seed)+sum(key), seed) | 00 5A
     */
    fun encryptUnlockCommand(seed: ByteArray, mac: String, key: String): ByteArray {
        val macBytes = macToBytes(mac)
        val sanitizedKey = sanitizeKey(key)
        val keyBytes = HexUtil.hexToBytes(sanitizedKey)
        var sum = 0
        for (b in seed) sum += b.toInt() and 0xFF
        for (b in keyBytes) sum += b.toInt() and 0xFF
        val sumBytes = byteArrayOf(
            (sum and 0xFF).toByte(),
            ((sum shr 8) and 0xFF).toByte()
        )
        var paddedLength = sumBytes.size + seed.size
        if (paddedLength % 8 != 0) paddedLength = (paddedLength / 8 + 1) * 8
        val padded = ByteArray(paddedLength)
        System.arraycopy(sumBytes, 0, padded, 0, sumBytes.size)
        System.arraycopy(seed, 0, padded, sumBytes.size, seed.size)
        val encryptedBytes = HexUtil.hexToBytes(
            DesCrypto.desEncryptBlockHex(sanitizedKey, HexUtil.bytesToHex(padded))
        )
        val headerSubset = macBytes.copyOfRange(2, 6)
        val totalLength = encryptedBytes.size + 12
        val payload = ByteArray(totalLength)
        payload[0] = 0xA5.toByte()
        payload[1] = (totalLength and 0xFF).toByte()
        payload[2] = 0x05
        System.arraycopy(headerSubset, 0, payload, 3, 4)
        payload[7] = 0x00
        payload[8] = 0x01
        payload[9] = 0x07
        System.arraycopy(encryptedBytes, 0, payload, 10, encryptedBytes.size)
        payload[totalLength - 2] = 0x00
        payload[totalLength - 1] = 0x5A
        var checksum = 0
        for (b in payload) checksum += b.toInt() and 0xFF
        payload[totalLength - 2] = (checksum.inv() and 0xFF).toByte()
        return payload
    }
}
