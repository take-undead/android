package app.thermalcam

import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.runtime.DisposableEffect
import app.thermalcam.ui.MainScreen
import app.thermalcam.ui.NightScreen
import app.thermalcam.vm.NightViewModel
import app.thermalcam.ui.PhotoViewerScreen
import app.thermalcam.ui.PhotosScreen
import app.thermalcam.vm.PhotosViewModel
import app.thermalcam.ui.theme.ThermalTheme
import app.thermalcam.vm.ThermalViewModel

class MainActivity : ComponentActivity() {
    private val vm: ThermalViewModel by viewModels()
    private val photos: PhotosViewModel by viewModels()
    private val night: NightViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // 測っている間は画面を消さない
        setContent {
            ThermalTheme {
                // 画面: カメラ → 写真の一覧 → 写真の表示（CLAUDE.md G4）。写真を見ている間もカメラは動かしたまま（戻ったときに待たない）
                val nav = rememberNavController()
                val degrees = screenDegrees()
                NavHost(nav, startDestination = "camera") {
                    composable("camera") {
                        MainScreen(vm, onOpenPhotos = { nav.navigate("photos") }, onOpenNight = { nav.navigate("night") })
                    }
                    composable("night") {
                        // 暗所カメラの間は熱カメラを止める（カメラの取り合いと電池のため）。戻ったら動かす
                        DisposableEffect(Unit) { vm.setPaused(true); onDispose { vm.setPaused(false) } }
                        NightScreen(night, degrees, onThermal = { nav.popBackStack() })
                    }
                    composable("photos") {
                        PhotosScreen(photos, onBack = { nav.popBackStack() }, onOpen = { i -> nav.navigate("photo/$i") })
                    }
                    composable("photo/{i}", arguments = listOf(navArgument("i") { type = NavType.IntType })) { e ->
                        PhotoViewerScreen(photos, e.arguments?.getInt("i") ?: 0, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    // 画面が見えている間だけカメラを動かす（裏に回ったら電源を切り、他のアプリ（MyFLIR など）が使えるようにする）
    override fun onStart() {
        super.onStart()
        vm.setScreenRotation(screenDegrees())
        vm.start()
    }

    /** 今の画面の回転（0 / 90 / 180 / 270）。回転すると Activity が作り直され、onStart でここが呼ばれる */
    private fun screenDegrees(): Int = when (ContextCompat.getDisplayOrDefault(this).rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    override fun onStop() {
        // 画面の回転では止めない（開き直すと起動に 6 秒かかる。ViewModel は回転をまたいで残る）
        if (!isChangingConfigurations) vm.stop()
        super.onStop()
    }
}
