package app.mt5mon.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mt5mon")

/** アプリ側の設定（PicoCalc の alert.txt に相当）。既定値は PicoCalc と同じ */
data class Prefs(
    val configText: String = "",       // config.txt の中身（秘密鍵を含む。アプリ専用領域にだけ置く）
    val alertOn: Boolean = true,
    val alertPips: Int = 15,
    val alertMinutes: Int = 5,
    val alertVolume: Int = 7,          // 1〜10（1 段 3dB）
    val noticeSeconds: Int = 20,       // 知らせのポップアップを自動で閉じる秒（0 = 閉じない）
    val tf: Int = 0,                   // 0 = M1、1 = M5、2 = M15、3 = M30、4 = H1
    val barWidth: Int = 3,             // ローソクの幅（PicoCalc の px。1〜4。画面では dp に換算）
    val autoVpn: Boolean = true,       // 起動時に VPN を自動でつなぐ
)

/**
 * 保存する項目のキー（CLAUDE.md G3: 変えるときは移行を書く）。
 *  config_text / alert_on / alert_pips / alert_minutes / alert_volume / notice_seconds / tf / bar_width / auto_vpn
 */
class ConfigStore(private val context: Context) {
    private object K {
        val configText = stringPreferencesKey("config_text")
        val alertOn = booleanPreferencesKey("alert_on")
        val alertPips = intPreferencesKey("alert_pips")
        val alertMinutes = intPreferencesKey("alert_minutes")
        val alertVolume = intPreferencesKey("alert_volume")
        val noticeSeconds = intPreferencesKey("notice_seconds")
        val tf = intPreferencesKey("tf")
        val barWidth = intPreferencesKey("bar_width")
        val autoVpn = booleanPreferencesKey("auto_vpn")
    }

    val prefs: Flow<Prefs> = context.dataStore.data.map { p ->
        Prefs(
            configText = p[K.configText] ?: "",
            alertOn = p[K.alertOn] ?: true,
            alertPips = p[K.alertPips] ?: 15,
            alertMinutes = p[K.alertMinutes] ?: 5,
            alertVolume = p[K.alertVolume] ?: 7,
            noticeSeconds = p[K.noticeSeconds] ?: 20,
            tf = p[K.tf] ?: 0,
            barWidth = p[K.barWidth] ?: 3,
            autoVpn = p[K.autoVpn] ?: true,
        )
    }

    suspend fun setConfigText(text: String) = context.dataStore.edit { it[K.configText] = text }
    suspend fun setAlert(on: Boolean, pips: Int, minutes: Int, volume: Int) = context.dataStore.edit {
        it[K.alertOn] = on
        it[K.alertPips] = pips.coerceIn(1, 100)
        it[K.alertMinutes] = minutes.coerceIn(1, 20)
        it[K.alertVolume] = volume.coerceIn(1, 10)
    }
    suspend fun setNoticeSeconds(sec: Int) = context.dataStore.edit { it[K.noticeSeconds] = sec.coerceIn(0, 300) }
    suspend fun setTf(tf: Int) = context.dataStore.edit { it[K.tf] = tf.coerceIn(0, 4) }
    suspend fun setBarWidth(w: Int) = context.dataStore.edit { it[K.barWidth] = w.coerceIn(1, 4) }
    suspend fun setAutoVpn(on: Boolean) = context.dataStore.edit { it[K.autoVpn] = on }
}
