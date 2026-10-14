package app.esp32cam.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.esp32cam.R
import app.esp32cam.vm.CamerasViewModel

/** 画面の切り替え: カメラ（起点）→ 写真一覧 → プレビュー、カメラ → 設定 */
object Routes {
    const val CAMERAS = "cameras"
    const val PHOTOS = "photos"
    const val PREVIEW = "preview/{index}"
    const val SETTINGS = "settings"
    const val FULL = "full/{index}"
    fun preview(index: Int) = "preview/$index"
    fun full(index: Int) = "full/$index"
}

@Composable
fun MainScreen(vm: CamerasViewModel) {
    val nav = rememberNavController()
    val ui by vm.ui.collectAsStateWithLifecycle()

    NavHost(navController = nav, startDestination = Routes.CAMERAS) {
        composable(Routes.CAMERAS) {
            CamerasScreen(vm, onOpenPhotos = { nav.navigate(Routes.PHOTOS) }, onOpenSettings = { nav.navigate(Routes.SETTINGS) }, onOpenFull = { nav.navigate(Routes.full(it)) })
        }
        composable(Routes.FULL, arguments = listOf(navArgument("index") { type = NavType.IntType })) { entry ->
            FullScreen(vm, index = entry.arguments?.getInt("index") ?: 0, onBack = { nav.popBackStack() })
        }
        composable(Routes.PHOTOS) { PhotosScreen(vm, onBack = { nav.popBackStack() }, onOpen = { nav.navigate(Routes.preview(it)) }) }
        composable(Routes.PREVIEW, arguments = listOf(navArgument("index") { type = NavType.IntType })) { entry ->
            PreviewScreen(vm, startIndex = entry.arguments?.getInt("index") ?: 0, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) { SettingsScreen(vm, onBack = { nav.popBackStack() }) }
    }
    // まとめて 1 つのダイアログで知らせるもの（接続エラー・撮影エラーなど）
    ui.notice?.let { n ->
        AlertDialog(
            onDismissRequest = vm::clearNotice,
            title = { Text(n.title) },
            // 6 台分の失敗が並ぶと画面に収まらないので、本文はスクロールできるようにする（実機で下が切れた）
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(n.text) } },
            confirmButton = { TextButton(onClick = vm::clearNotice) { Text(stringResource(R.string.ok)) } },
        )
    }
}
