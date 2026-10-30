package app.mt5mon.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.mt5mon.R
import app.mt5mon.vm.MainViewModel

/** 画面の一覧（下のナビゲーションバー。幅が広いときは左のレール。CLAUDE.md「事前の注意事項」） */
private enum class Dest(val route: String, val label: Int, val icon: ImageVector) {
    Monitor("monitor", R.string.nav_monitor, Icons.Filled.Home),
    Nippo("nippo", R.string.nav_nippo, Icons.AutoMirrored.Filled.List),
    Attack("attack", R.string.nav_attack, Icons.Filled.Warning),
    Chat("chat", R.string.nav_chat, Icons.AutoMirrored.Filled.Send),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings),
}

@Composable
fun MainScreen(vm: MainViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val ui by vm.ui.collectAsStateWithLifecycle()
    // 画面スリープ禁止(設定)。アプリが前にある間だけ効く(View.keepScreenOn は窓が見えている間だけ有効)
    val view = LocalView.current
    DisposableEffect(ui.prefs.keepScreenOn) {
        view.keepScreenOn = ui.prefs.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    fun go(d: Dest) {
        nav.navigate(d.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun selected(d: Dest) = current?.hierarchy?.any { it.route == d.route } == true

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (!wide) NavigationBar {
                    Dest.entries.forEach { d ->
                        NavigationBarItem(
                            selected = selected(d),
                            onClick = { go(d) },
                            icon = { Icon(d.icon, contentDescription = null) },
                            label = { Text(stringResource(d.label)) },
                        )
                    }
                }
            },
        ) { inner ->
            Row(Modifier.fillMaxSize().padding(inner)) {
                if (wide) NavigationRail {
                    Dest.entries.forEach { d ->
                        NavigationRailItem(
                            selected = selected(d),
                            onClick = { go(d) },
                            icon = { Icon(d.icon, contentDescription = null) },
                            label = { Text(stringResource(d.label)) },
                        )
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    NavHost(navController = nav, startDestination = Dest.Monitor.route, modifier = Modifier.fillMaxSize()) {
                        composable(Dest.Monitor.route) { MonitorScreen(vm, onOpenSettings = { go(Dest.Settings) }) }
                        composable(Dest.Nippo.route) { NippoScreen() }
                        composable(Dest.Attack.route) { AttackScreen() }
                        composable(Dest.Chat.route) { ChatScreen() }
                        composable(Dest.Settings.route) { SettingsScreen(vm) }
                    }
                }
            }
        }
    }
    // VPS からの知らせ（どの画面でも重ねて出す）
    if (ui.noticeOpen) ui.notice?.let { n ->
        NoticeDialog(n, secondsLeft = if (ui.prefs.noticeSeconds > 0) (ui.prefs.noticeSeconds - (ui.nowMillis - ui.noticeOpenedAt) / 1000).toInt().coerceAtLeast(0) else -1, onClose = vm::closeNotice)
    }
}
