package app.thermalcam.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.thermalcam.lepton.Gain
import app.thermalcam.thermal.Alignment
import app.thermalcam.thermal.OverlayMode
import app.thermalcam.thermal.Palette
import app.thermalcam.thermal.Renderer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "thermalcam")

data class Settings(
    val palette: Palette = Palette.IRON,
    val gain: Gain = Gain.HIGH,
    val overlay: OverlayMode = OverlayMode.OFF,
    val align: Alignment = Alignment(),
    val autoRange: Boolean = true,
    val rangeLo: Float = 20f,
    val rangeHi: Float = 40f,
    val edgeLevel: Float = Renderer.DEFAULT_EDGE_LEVEL,
    val smooth: Smooth = Smooth.OFF,
    val emissivity: Float = 1.0f,        // Lepton の既定（実機で読んだ値 8192 = 1.0）
)

/** 熱画像のざらつき減らし: 新しいフレームを混ぜる重み（小さいほど強く平均する。動く物は尾を引く） */
enum class Smooth(val weight: Float) { OFF(1f), LOW(0.5f), HIGH(0.25f) }

/**
 * 設定の保存（CLAUDE.md G3: キーを変えるときは移行を書く）。
 * キー: palette, gain, overlay（列挙の名前）, align_scale, align_x, align_y, auto_range, range_lo, range_hi, edge_level, smooth, emissivity,
 *       preset_1 … preset_5（記憶。[encode] の文字列）
 */
class SettingsStore(private val context: Context) {
    private val kPalette = stringPreferencesKey("palette")
    private val kGain = stringPreferencesKey("gain")
    private val kOverlay = stringPreferencesKey("overlay")
    private val kScale = floatPreferencesKey("align_scale")
    private val kX = floatPreferencesKey("align_x")
    private val kY = floatPreferencesKey("align_y")
    private val kAuto = booleanPreferencesKey("auto_range")
    private val kLo = floatPreferencesKey("range_lo")
    private val kHi = floatPreferencesKey("range_hi")
    private val kEdge = floatPreferencesKey("edge_level")
    private val kSmooth = stringPreferencesKey("smooth")
    private val kEmis = floatPreferencesKey("emissivity")
    private val kPresets = List(PRESET_COUNT) { stringPreferencesKey("preset_${it + 1}") }

    companion object {
        const val PRESET_COUNT = 5

        /**
         * 記憶 1 つ分の文字列。先頭は形の版。
         *  v1（2026-10-18）: 色;測定範囲;重ね;大きさ;左右;上下;自動範囲;下限;上限 の 10 項目
         *  v2（2026-10-18）: v1 の後ろに 輪郭の強さ を足した 11 項目。v1 は輪郭の強さを既定値として読む
         *  v3（2026-10-18）: v2 の後ろに ざらつき減らし・放射率 を足した 13 項目。v1・v2 はこの 2 つを既定値として読む
         * Float.toString は端末の言語設定によらず小数点が「.」なので、そのまま書ける。
         */
        fun encode(s: Settings): String = listOf(
            "v3", s.palette.name, s.gain.name, s.overlay.name,
            s.align.scale, s.align.offsetX, s.align.offsetY, s.autoRange, s.rangeLo, s.rangeHi, s.edgeLevel, s.smooth.name, s.emissivity,
        ).joinToString(";")

        fun decode(text: String?): Settings? {
            val f = text?.split(';') ?: return null
            val ok = (f.size == 10 && f[0] == "v1") || (f.size == 11 && f[0] == "v2") || (f.size == 13 && f[0] == "v3")
            if (!ok) return null
            return Settings(
                palette = Palette.entries.firstOrNull { it.name == f[1] } ?: return null,
                gain = Gain.entries.firstOrNull { it.name == f[2] } ?: return null,
                overlay = OverlayMode.entries.firstOrNull { it.name == f[3] } ?: return null,
                align = Alignment(f[4].toFloatOrNull() ?: return null, f[5].toFloatOrNull() ?: return null, f[6].toFloatOrNull() ?: return null),
                autoRange = f[7].toBooleanStrictOrNull() ?: return null,
                rangeLo = f[8].toFloatOrNull() ?: return null,
                rangeHi = f[9].toFloatOrNull() ?: return null,
                edgeLevel = if (f[0] != "v1") f[10].toFloatOrNull() ?: return null else Renderer.DEFAULT_EDGE_LEVEL,
                smooth = if (f[0] == "v3") Smooth.entries.firstOrNull { it.name == f[11] } ?: return null else Smooth.OFF,
                emissivity = if (f[0] == "v3") f[12].toFloatOrNull() ?: return null else 1.0f,
            )
        }
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        val d = Settings()
        Settings(
            palette = enumOf(p[kPalette], d.palette),
            gain = enumOf(p[kGain], d.gain),
            overlay = enumOf(p[kOverlay], d.overlay),
            align = Alignment(p[kScale] ?: d.align.scale, p[kX] ?: d.align.offsetX, p[kY] ?: d.align.offsetY),
            autoRange = p[kAuto] ?: d.autoRange,
            rangeLo = p[kLo] ?: d.rangeLo,
            rangeHi = p[kHi] ?: d.rangeHi,
            edgeLevel = p[kEdge] ?: d.edgeLevel,
            smooth = enumOf(p[kSmooth], d.smooth),
            emissivity = p[kEmis] ?: d.emissivity,
        )
    }

    /** 記憶 1〜5（空きは null） */
    val presets: Flow<List<Settings?>> = context.dataStore.data.map { p -> kPresets.map { decode(p[it]) } }

    suspend fun savePreset(index: Int, s: Settings) = context.dataStore.edit { it[kPresets[index]] = encode(s) }

    /** 記憶を呼び出す: 今の設定をまとめて書き換える */
    suspend fun setAll(s: Settings) = context.dataStore.edit {
        it[kPalette] = s.palette.name; it[kGain] = s.gain.name; it[kOverlay] = s.overlay.name
        it[kScale] = s.align.scale; it[kX] = s.align.offsetX; it[kY] = s.align.offsetY
        it[kAuto] = s.autoRange; it[kLo] = s.rangeLo; it[kHi] = s.rangeHi; it[kEdge] = s.edgeLevel
        it[kSmooth] = s.smooth.name; it[kEmis] = s.emissivity
    }

    suspend fun setPalette(v: Palette) = context.dataStore.edit { it[kPalette] = v.name }
    suspend fun setGain(v: Gain) = context.dataStore.edit { it[kGain] = v.name }
    suspend fun setOverlay(v: OverlayMode) = context.dataStore.edit { it[kOverlay] = v.name }
    suspend fun setAlign(a: Alignment) = context.dataStore.edit { it[kScale] = a.scale; it[kX] = a.offsetX; it[kY] = a.offsetY }
    suspend fun setSmooth(v: Smooth) = context.dataStore.edit { it[kSmooth] = v.name }
    suspend fun setEmissivity(v: Float) = context.dataStore.edit { it[kEmis] = v }
    suspend fun setEdgeLevel(v: Float) = context.dataStore.edit { it[kEdge] = v }
    suspend fun setRange(auto: Boolean, lo: Float, hi: Float) = context.dataStore.edit { it[kAuto] = auto; it[kLo] = lo; it[kHi] = hi }
}
