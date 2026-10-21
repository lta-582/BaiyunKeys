package cn.baiyunkeys.app.core

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * DES/ECB/NoPadding 单块与多块加解密。
 *
 * 移植自小程序 utils/des.js：
 *  - 密钥取十六进制串前 16 个字符（即 8 字节，与 CryptoJS.enc.Hex.parse 行为一致）
 *  - 数据长度须为 8 字节的倍数；对不足 8 字节的短载荷按 0 补齐
 *    （对应小程序 utils/util.js 中 CryptoJS 默认的 \0 填充行为，
 *     原 bleProtocol.js 的 buildCommKeyCommand / buildTimeSyncCommand
 *     传入 6/7 字节短块，依赖该补齐才能实际执行）
 */
object DesCrypto {

    private fun normalizeKeyHex(keyHex: String?): String {
        require(!keyHex.isNullOrEmpty()) { "缺少 DES 密钥" }
        val clean = keyHex.replace(Regex("[^0-9A-Fa-f]"), "").uppercase()
        require(clean.length >= 16) { "密钥长度不足 8 字节" }
        return clean.substring(0, 16)
    }

    /** 数据规整为 8 字节倍数，不足补 0 */
    private fun normalizeDataHex(dataHex: String?): String {
        require(!dataHex.isNullOrEmpty()) { "缺少 DES 数据块" }
        val clean = dataHex.replace(Regex("[^0-9A-Fa-f]"), "").uppercase()
        require(clean.isNotEmpty()) { "DES 数据块为空" }
        return if (clean.length % 16 == 0) clean else clean.padEnd(
            (clean.length / 16 + 1) * 16, '0'
        )
    }

    private fun cipher(mode: Int, keyHex: String, dataHex: String): ByteArray {
        val keyBytes = HexUtil.hexToBytes(normalizeKeyHex(keyHex))
        val dataBytes = HexUtil.hexToBytes(normalizeDataHex(dataHex))
        val c = Cipher.getInstance("DES/ECB/NoPadding")
        c.init(mode, SecretKeySpec(keyBytes, "DES"))
        return c.doFinal(dataBytes)
    }

    fun desEncryptBlockHex(keyHex: String, dataHex: String): String =
        HexUtil.bytesToHex(cipher(Cipher.ENCRYPT_MODE, keyHex, dataHex))

    fun desDecryptBlockHex(keyHex: String, dataHex: String): String =
        HexUtil.bytesToHex(cipher(Cipher.DECRYPT_MODE, keyHex, dataHex))
}
