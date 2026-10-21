package cn.baiyunkeys.app.data

/** 门禁配置项（与小程序 config.js 的字段一致） */
data class DoorConfig(
    val id: String = "",
    val doorName: String = "",
    val mac: String = "",
    val key: String = "",
    val bluetoothName: String = "",
    val logEnabled: Boolean = false
) {
    val isValid: Boolean
        get() = cn.baiyunkeys.app.core.LockBiz.isValidMac(mac) &&
            cn.baiyunkeys.app.core.LockBiz.isValidKey(key)
}
