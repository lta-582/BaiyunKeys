package cn.baiyunkeys.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import cn.baiyunkeys.app.ui.BaiyunKeysApp
import cn.baiyunkeys.app.ui.MainViewModel
import cn.baiyunkeys.app.ui.theme.BaiyunKeysTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BaiyunKeysTheme {
                BaiyunKeysApp(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 快速开锁：每次打开 APP 自动执行一次
        vm.triggerQuickUnlockIfEnabled()
    }
}
