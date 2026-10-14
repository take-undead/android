package app.esp32cam.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "esp32cam")

/**
 * 6 台分の IP アドレスとフラッシュの設定（元の Python の settings.json に相当）。
 * 保存する項目のキー（CLAUDE.md G3: 変えるときは移行を書く）: host_0 … host_5, flash
 */
class HostStore(private val context: Context) {
    companion object {
        const val CAMERA_COUNT = 6
        const val DEFAULT_BASE_HOST = "192.168.1.141" // config.txt を書き換えていない個体の初期値。カメラ 1 がこの値で以降 1 ずつ増える

        fun defaultHosts(base: String = DEFAULT_BASE_HOST, count: Int = CAMERA_COUNT): List<String> {
            val dot = base.lastIndexOf('.')
            val last = if (dot >= 0) base.substring(dot + 1).toIntOrNull() else null
            if (dot < 0 || last == null) return List(count) { base }
            val prefix = base.substring(0, dot)
            return List(count) { i -> "$prefix.${minOf(last + i, 255)}" }
        }
    }

    private val hostKeys = List(CAMERA_COUNT) { stringPreferencesKey("host_$it") }
    private val flashKey = booleanPreferencesKey("flash")

    data class Settings(val hosts: List<String>, val flash: Boolean)

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        val defaults = defaultHosts()
        Settings(
            hosts = List(CAMERA_COUNT) { i -> p[hostKeys[i]]?.takeIf { it.isNotEmpty() } ?: defaults[i] },
            flash = p[flashKey] ?: false,
        )
    }

    private fun clean(h: String) = CameraApi.toHalfWidth(h).replace(" ", "").trim()
    suspend fun setHost(index: Int, host: String) = context.dataStore.edit { it[hostKeys[index]] = clean(host) }
    suspend fun setHosts(hosts: List<String>) = context.dataStore.edit { p -> hosts.forEachIndexed { i, h -> if (i < CAMERA_COUNT) p[hostKeys[i]] = clean(h) } }
    suspend fun setFlash(on: Boolean) = context.dataStore.edit { it[flashKey] = on }
}
