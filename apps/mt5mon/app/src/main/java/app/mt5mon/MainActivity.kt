package app.mt5mon

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mt5mon.data.ConfigError
import app.mt5mon.data.ConfigParser
import app.mt5mon.ui.MainScreen
import app.mt5mon.ui.theme.Mt5Theme
import app.mt5mon.vm.MainViewModel

class MainActivity : ComponentActivity() {
    private val mainVm: MainViewModel by viewModels()

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
                    for (intent in vm.vpnPermission) {
                        try {
                            launcher.launch(intent)
                        } catch (e: ActivityNotFoundException) {
                            // MECHEN H1（Android 6.0）には com.android.vpndialogs が無く、ここで落ちた（2026-10-14）
                            vm.onVpnPermissionUnavailable(getString(R.string.vpn_dialog_missing))
                        }
                    }
                }
                MainScreen(vm)
            }
        }
        handleConfigIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleConfigIntent(intent)
    }

    /** 「開く」「共有」で渡された config.txt を読み込む（内容が config.txt として読めたときだけ保存する） */
    private fun handleConfigIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }
        val text: String = try {
            if (uri != null) {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { b ->
                    if (b.size > 64 * 1024) throw IllegalArgumentException("大きすぎる")
                    String(b, Charsets.UTF_8)
                } ?: ""
            } else if (intent.action == Intent.ACTION_SEND) {
                intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            } else ""
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.set_config_import_err, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            return
        }
        if (text.isBlank()) return
        try {
            ConfigParser.parse(text) // 読めなければ ConfigError
        } catch (e: ConfigError) {
            Toast.makeText(this, getString(R.string.set_config_bad, e.message ?: ""), Toast.LENGTH_LONG).show()
            return
        }
        mainVm.saveConfigText(text)
        Toast.makeText(this, getString(R.string.set_config_imported), Toast.LENGTH_LONG).show()
        // 同じ Intent を回転のたびに読み直さないよう消しておく
        setIntent(Intent(this, MainActivity::class.java))
    }
}
