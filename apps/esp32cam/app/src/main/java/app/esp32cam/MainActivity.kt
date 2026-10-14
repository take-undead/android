package app.esp32cam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.esp32cam.ui.MainScreen
import app.esp32cam.ui.theme.CamTheme
import app.esp32cam.vm.CamerasViewModel

class MainActivity : ComponentActivity() {
    private val vm: CamerasViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CamTheme { MainScreen(vm) }
        }
    }

    // 画面が裏に回ったら映像を止め、戻ったら同じ台をつなぎ直す（電池と WiFi のため）
    override fun onStart() {
        super.onStart()
        vm.resumeFromBackground()
    }

    override fun onStop() {
        vm.pauseForBackground()
        super.onStop()
    }
}
