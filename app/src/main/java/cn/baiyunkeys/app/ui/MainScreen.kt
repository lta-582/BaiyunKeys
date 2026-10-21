package cn.baiyunkeys.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.baiyunkeys.app.core.LockBiz
import cn.baiyunkeys.app.data.DoorConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaiyunKeysApp(vm: MainViewModel = viewModel()) {
    var tab by remember { mutableStateOf(0) }

    // 免责声明倒计时（仅在声明展示时启动一次）
    LaunchedEffect(vm.disclaimerVisible) {
        if (vm.disclaimerVisible) vm.startDisclaimerCountdown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BaiyunKeys · 白云门禁") },
                actions = {
                    if (vm.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("开锁") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("配置") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    label = { Text("帮助") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> MainTab(vm)
                1 -> ConfigTab(vm)
                else -> HelpTab()
            }
        }
    }

    if (vm.disclaimerVisible) {
        DisclaimerDialog(vm)
    }
}

// ==================== 开锁页 ====================

@Composable
private fun MainTab(vm: MainViewModel) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> vm.unlock() }

    fun ensurePermsAndUnlock() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(context, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) vm.unlock() else permissionLauncher.launch(missing.toTypedArray())
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 门禁选择器
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("选择门禁", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                DoorSelector(vm)
            }
        }

        Spacer(Modifier.height(16.dp))

        // 开锁按钮
        Button(
            onClick = { ensurePermsAndUnlock() },
            enabled = !vm.loading && vm.currentDoor.isValid,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
        ) {
            Text(
                if (vm.loading) "正在开锁..." else "开 锁",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(8.dp))

        // 状态
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (vm.statusMessage.contains("成功") ||
                    vm.statusMessage.contains("已执行") || vm.statusMessage.contains("门已打开")
                ) Color(0xFFDCFCE7) else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                vm.statusMessage.ifEmpty { "准备就绪" },
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Spacer(Modifier.height(16.dp))

        // 日志
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("实时日志", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("logs", vm.copyLogs()))
            }) { Text("复制") }
            TextButton(onClick = { vm.clearLogs() }) { Text("清空") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                if (vm.logs.isEmpty()) {
                    Text(
                        "暂无日志",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
                vm.logs.forEach { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DoorSelector(vm: MainViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            val d = vm.currentDoor
            val label = if (d.mac.isEmpty()) {
                "未配置门禁，请前往配置页添加"
            } else {
                "${d.doorName.ifEmpty { "门禁" }}  ${d.mac}"
            }
            Text(label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            vm.doors.forEach { door ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(door.doorName.ifEmpty { "门禁" })
                            Text(
                                door.mac,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        vm.setActiveDoor(door.id)
                        expanded = false
                    },
                    trailingIcon = if (door.id == vm.currentDoor.id) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null
                )
            }
        }
    }
}

// ==================== 配置页 ====================

@Composable
private fun ConfigTab(vm: MainViewModel) {
    var editing by remember { mutableStateOf<DoorConfig?>(null) }
    var showDbGuide by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { vm.importFromTree(it) } }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 快速开锁开关
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("快速开锁", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "打开 APP 时自动执行一次开锁",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = vm.quickUnlock, onCheckedChange = { vm.updateQuickUnlock(it) })
            }
        }

        Spacer(Modifier.height(12.dp))

        // 数据库提取
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("数据库提取配置", style = MaterialTheme.typography.titleMedium)
                Text(
                    "从官方 App 本地数据库直接读取门禁参数，无需联网，不受服务端限制",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { folderPicker.launch(null) },
                    enabled = vm.extractState !is MainViewModel.ExtractState.Loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (vm.extractState is MainViewModel.ExtractState.Loading) "解析中..." else "选择数据库文件夹")
                }
                Text(
                    "先用文件管理器等工具导出数据库文件夹，再在此选择",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { showDbGuide = !showDbGuide }) {
                    Text(if (showDbGuide) "收起导出方法" else "查看导出方法")
                }
                if (showDbGuide) {
                    Text("方式 A（Root 手机）：用 MT管理器等工具进入", fontSize = 12.sp)
                    Text(
                        "/data/data/com.huacheng.baiyunuser/databases/",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    Text("把整个 databases 文件夹复制到「下载」目录，然后点上方按钮选择该文件夹。", fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("方式 B（MIUI 等）：设置 → 备份与恢复 → 仅勾选“平安回家”备份，然后在", fontSize = 12.sp)
                    Text(
                        "/sdcard/MIUI/backup/AllBackup/备份时间/",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    Text("解压 com.huacheng.baiyunuser.bak，取出其中的数据库文件夹后选择。", fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("方式 C（电脑，任意机型）：手机开启 USB 调试，执行", fontSize = 12.sp)
                    Text(
                        "adb backup -f baiyun.ab com.huacheng.baiyunuser",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    Text("用 abe（Android Backup Extractor）解包取出数据库，传回手机后选择。", fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "提示：导出前请在官方 App 中登录并打开门禁列表，确保数据库内有数据。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                when (val st = vm.extractState) {
                    is MainViewModel.ExtractState.Error -> Text(
                        st.msg,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    is MainViewModel.ExtractState.Success -> {
                        Spacer(Modifier.height(8.dp))
                        Text("提取到 ${st.items.size} 套门禁，点击导入：")
                        st.items.forEach { item ->
                            Card(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.doorName, fontWeight = FontWeight.Medium)
                                        Text(
                                            "MAC: ${item.mac}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (item.bluetoothName.isNotEmpty()) {
                                            Text(
                                                "蓝牙名: ${item.bluetoothName}",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    TextButton(onClick = { vm.importExtractedDoor(item) }) { Text("导入") }
                                }
                            }
                        }
                        TextButton(onClick = { vm.resetExtract() }) { Text("关闭列表") }
                    }
                    else -> {}
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 门禁列表
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("门禁列表", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { editing = DoorConfig() }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(" 新增")
            }
        }
        if (vm.doors.isEmpty()) {
            Text(
                "还没有门禁配置，请点击右上角新增，或使用上方的数据库提取。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        vm.doors.forEach { door ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            door.doorName.ifEmpty { "未命名门禁" },
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        if (door.id == vm.currentDoor.id) {
                            Text(
                                "使用中",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp
                            )
                        }
                        IconButton(onClick = { editing = door }) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑")
                        }
                        IconButton(onClick = { vm.deleteDoor(door.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                    Text(
                        "MAC: ${door.mac}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Key: ${maskKey(door.key)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (door.bluetoothName.isNotEmpty()) {
                        Text(
                            "蓝牙名: ${door.bluetoothName}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!door.isValid) {
                        Text(
                            "参数无效：MAC 或 Key 格式不正确",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    // 编辑对话框
    editing?.let { door ->
        DoorEditDialog(
            initial = door,
            onDismiss = { editing = null },
            onSave = {
                vm.saveDoor(it)
                editing = null
            }
        )
    }
}

private fun maskKey(key: String): String =
    if (key.length <= 8) key else key.take(4) + "****" + key.takeLast(4)

@Composable
private fun DoorEditDialog(
    initial: DoorConfig,
    onDismiss: () -> Unit,
    onSave: (DoorConfig) -> Unit
) {
    var name by remember { mutableStateOf(initial.doorName) }
    var mac by remember { mutableStateOf(initial.mac) }
    var key by remember { mutableStateOf(initial.key) }
    var btName by remember { mutableStateOf(initial.bluetoothName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isEmpty()) "新增门禁" else "编辑门禁") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("门禁名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = mac,
                    onValueChange = { mac = LockBiz.sanitizeMacInput(it) },
                    label = { Text("MAC 地址 (MAC_NUM)") },
                    placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = LockBiz.sanitizeKey(it) },
                    label = { Text("加密 Key (PRODUCT_KEY)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = btName,
                    onValueChange = { btName = it.uppercase().replace(Regex("[^0-9A-Z]"), "") },
                    label = { Text("蓝牙名称 (bluetoothName，iOS 必填)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (mac.isNotEmpty() && !LockBiz.isValidMac(mac)) {
                    Text(
                        "MAC 格式应为 AA:BB:CC:DD:EE:FF",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
                if (key.isNotEmpty() && !LockBiz.isValidKey(key)) {
                    Text(
                        "Key 应为 16-32 位十六进制字符",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        initial.copy(
                            doorName = name.trim(),
                            mac = mac.trim(),
                            key = key.trim(),
                            bluetoothName = btName.trim()
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ==================== 帮助页 ====================

@Composable
private fun HelpTab() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("免责声明", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("· 本程序不访问任何网络服务，所有门禁参数仅保存在本机，可随时通过清除应用数据移除。")
                Spacer(Modifier.height(4.dp))
                Text("· 门禁参数来自您本机官方 App 的数据库，提取过程完全离线完成。")
                Spacer(Modifier.height(4.dp))
                Text("· 本程序永久完全免费，仅供学习交流使用，禁止用于其他任何商业或非法用途。")
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("使用前准备", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("1. 认识关键字段", fontWeight = FontWeight.Medium)
                Text("   门禁 MAC → MAC_NUM")
                Text("   门禁 Key → PRODUCT_KEY")
                Text("   蓝牙名称 → bluetoothName（iOS 必填，安卓可忽略但建议保留）")
                Spacer(Modifier.height(8.dp))
                Text("2. 获取配置的方式（任选其一）", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("方式一：导入数据库文件（应用内自动解析，推荐）", fontWeight = FontWeight.Medium)
                Text("  先在官方 App 登录并打开门禁列表，确保数据库有数据，然后：")
                Text("  Root 手机：MT管理器进入 /data/data/com.huacheng.baiyunuser/databases/，")
                Text("  复制整个文件夹到「下载」，再在配置页选择该文件夹")
                Text("  MIUI 等：设置 → 备份与恢复 → 仅勾选“平安回家”备份，")
                Text("  在 /sdcard/MIUI/backup/AllBackup/备份时间/ 解压 com.huacheng.baiyunuser.bak")
                Text("  电脑：adb backup -f baiyun.ab com.huacheng.baiyunuser，")
                Text("  用 abe 解包取出数据库文件夹，传回手机后选择")
                Spacer(Modifier.height(4.dp))
                Text("方式二：手动录入", fontWeight = FontWeight.Medium)
                Text("  从数据库或抓包中取得 MAC_NUM、PRODUCT_KEY、bluetoothName，")
                Text("  在配置页点击「新增」手动填写")
                Spacer(Modifier.height(8.dp))
                Text("3. 开锁", fontWeight = FontWeight.Medium)
                Text("  保存参数后在首页选择门禁，点击开锁；可开启快速开锁免点击。")
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("参考", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text("github.com/Soufock/BaiyunKeys-preview（小程序原版）")
                Text("github.com/dogproton/SafeBaiyun（Android 原版）")
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ==================== 免责声明弹窗 ====================

@Composable
private fun DisclaimerDialog(vm: MainViewModel) {
    AlertDialog(
        onDismissRequest = { /* 不允许点击外部关闭 */ },
        title = { Text("免责声明") },
        text = {
            Column {
                Text("· 本程序没有任何云端和后门，所有门禁参数仅保存在本机。")
                Spacer(Modifier.height(4.dp))
                Text("· 门禁参数来自您本机官方 App 的数据库，提取过程完全离线完成。")
                Spacer(Modifier.height(4.dp))
                Text("· 仅供学习交流使用，禁止用于其他任何商业或非法用途。")
                Spacer(Modifier.height(8.dp))
                if (vm.disclaimerCountdown > 0) {
                    Text(
                        "请仔细阅读（${vm.disclaimerCountdown}s）",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.acceptDisclaimer() },
                enabled = vm.disclaimerCountdown <= 0
            ) { Text("我已阅读并同意") }
        }
    )
}
