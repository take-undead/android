package app.mt5mon

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mt5mon.ui.MainScreen
import app.mt5mon.ui.theme.Mt5Theme
import app.mt5mon.vm.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Mt5Theme {
                val vm: MainViewModel = viewModel()
                // VPN の許可（Android の確認画面）。許可されたらつなぐ
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                    vm.onVpnPermissionResult(r.resultCode == Activity.RESULT_OK)
                }
                LaunchedEffect(Unit) {
                    for (intent in vm.vpnPermission) launcher.launch(intent)
                }
                MainScreen(vm)
            }
        }
    }
}
