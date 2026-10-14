package app.mt5mon

import android.app.Application
import app.mt5mon.data.AppConfig
import app.mt5mon.data.ConfigStore
import app.mt5mon.data.VpsApi
import app.mt5mon.data.WgTunnel
import kotlinx.coroutines.flow.MutableStateFlow

/** アプリ全体で 1 つずつ持つもの（設定の保存・HTTP・WireGuard）。画面（ViewModel）はここから借りる */
class Mt5App : Application() {
    val store by lazy { ConfigStore(this) }
    val tunnel by lazy { WgTunnel(this) }
    /** 読み込んだ設定（config.txt）。無い・不備なら null */
    val config = MutableStateFlow<AppConfig?>(null)
    val api by lazy { VpsApi { config.value } }
}
