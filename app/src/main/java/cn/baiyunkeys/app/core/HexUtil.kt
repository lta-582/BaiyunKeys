package cn.baiyunkeys.app.core

/**
 * 十六进制与字节数组互转工具。
 * 移植自小程序 utils/lockBiz.js 的 hexToBytes / bytesToHex / normalizeHex。
 */
object HexUtil {

    private val HEX_REGEX = Regex("[^0-9A-Fa-f]")

    /** 去掉非十六进制字符并大写；长度为奇数时前面补 0（与 JS normalizeHex 一致） */
    fun normalizeHex(hex: String?): String {
        if (hex.isNullOrEmpty()) return ""
        val clean = hex.replace(HEX_REGEX, "").uppercase()
        return if (clean.length % 2 == 0) clean else "0$clean"
    }

    /** 十六进制字符串 -> 字节数组 */
    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "十六进制字符串长度必须为偶数" }
        val out = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            val hi = Character.digit(hex[i], 16)
            val lo = Character.digit(hex[i + 1], 16)
            require(hi >= 0 && lo >= 0) { "包含非十六进制字符" }
            out[i / 2] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** 字节数组 -> 大写十六进制字符串 */
    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v shr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX_DIGITS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    )
}
