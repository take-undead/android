package app.esp32cam

import android.app.Application
import app.esp32cam.data.HostStore

/** アプリ全体で 1 つ持つもの（設定の保存） */
class Esp32CamApp : Application() {
    val store by lazy { HostStore(this) }
}
