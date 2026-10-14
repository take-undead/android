package app.esp32cam.vm

import android.app.Application
import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.esp32cam.Esp32CamApp
import app.esp32cam.data.ApiError
import app.esp32cam.data.CameraApi
import app.esp32cam.data.CameraStatus
import app.esp32cam.data.HostStore
import app.esp32cam.data.MjpegStream
import app.esp32cam.data.PhotoSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** タイル 1 枚の状態（映像は別の StateFlow で持つ。毎フレームの更新で一覧全体を作り直さないため） */
enum class TileState { IDLE, CONNECTING, WAITING, STREAMING, FAILED, DISCONNECTED }

data class Tile(val index: Int, val host: String, val state: TileState = TileState.IDLE, val message: String = "") {
    val connected: Boolean get() = state == TileState.CONNECTING || state == TileState.WAITING || state == TileState.STREAMING
}

/** 1 回だけ見せる知らせ（ダイアログ） */
data class Notice(val title: String, val text: String)

data class CamerasUi(
    val tiles: List<Tile> = List(HostStore.CAMERA_COUNT) { Tile(it, "") },
    val selected: Int = 0,
    val flash: Boolean = false,
    val busy: Boolean = false,
    val status: String = "",            // 下の状態欄
    val notice: Notice? = null,         // まとめて 1 つのダイアログで知らせるもの
    val photos: List<String> = emptyList(),
    val photosCamera: Int = -1,         // どのカメラの一覧か（対象を切り替えたら空にする）
    val lastStatus: CameraStatus? = null,
) {
    val anyConnected: Boolean get() = tiles.any { it.connected }
}

class CamerasViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as Esp32CamApp).store
    private val _ui = MutableStateFlow(CamerasUi())
    val ui: StateFlow<CamerasUi> = _ui

    /** タイルごとの最新の 1 枚 */
    val frames: List<MutableStateFlow<Bitmap?>> = List(HostStore.CAMERA_COUNT) { MutableStateFlow<Bitmap?>(null) }

    private val streams = arrayOfNulls<MjpegStream>(HostStore.CAMERA_COUNT)
    private var pausedByBackground: Set<Int> = emptySet()
    private val photoCache = LruCache<String, ByteArray>(12)

    init {
        viewModelScope.launch {
            store.settings.collect { s ->
                _ui.update { u -> u.copy(tiles = u.tiles.mapIndexed { i, t -> t.copy(host = s.hosts[i]) }, flash = s.flash) }
            }
        }
    }

    // ---- 設定 -----------------------------------------------------------------

    fun setHosts(hosts: List<String>) = viewModelScope.launch { store.setHosts(hosts) }
    fun setFlash(on: Boolean) = viewModelScope.launch { store.setFlash(on) }
    fun clearNotice() = _ui.update { it.copy(notice = null) }
    fun setStatus(text: String) = _ui.update { it.copy(status = text) }

    fun select(index: Int) {
        if (index == _ui.value.selected) return
        // 対象を切り替えると一覧は空にする（どのカメラの写真か取り違えないため）
        _ui.update { it.copy(selected = index, photos = emptyList(), photosCamera = -1) }
    }

    private fun updateTile(index: Int, f: (Tile) -> Tile) = _ui.update { u -> u.copy(tiles = u.tiles.mapIndexed { i, t -> if (i == index) f(t) else t }) }

    // ---- 映像 -----------------------------------------------------------------

    /** 1 台つなぐ。結果（null = 成功、文字列 = 失敗の理由）を onResult に返す */
    fun connect(index: Int, onResult: ((String?) -> Unit)? = null) {
        val tile = _ui.value.tiles[index]
        if (tile.connected) { onResult?.invoke(null); return }
        val url = try {
            CameraApi.streamUrl(tile.host)
        } catch (e: ApiError) {
            updateTile(index) { it.copy(state = TileState.FAILED, message = e.message ?: "") }
            onResult?.invoke(e.message)
            return
        }
        updateTile(index) { it.copy(state = TileState.CONNECTING, message = "") }
        var reported = false
        val stream = MjpegStream(
            url = url,
            onConnected = {
                updateTile(index) { it.copy(state = TileState.WAITING) }
                if (!reported) { reported = true; onResult?.invoke(null) }
            },
            onFrame = { bmp ->
                frames[index].value = bmp
                if (_ui.value.tiles[index].state == TileState.WAITING) updateTile(index) { it.copy(state = TileState.STREAMING) }
            },
            onError = { msg, beforeConnect ->
                if (beforeConnect) {
                    updateTile(index) { it.copy(state = TileState.FAILED, message = msg) }
                    if (!reported) { reported = true; onResult?.invoke(msg) }
                } else {
                    // 受信中に切れたカメラはダイアログを出さず、タイルと状態欄にだけ出す（6 台分が積み重なるのを避ける）
                    updateTile(index) { it.copy(state = TileState.DISCONNECTED, message = msg) }
                    _ui.update { it.copy(status = "カメラ ${index + 1}: $msg") }
                }
                // 受信スレッド自身から呼ばれるので stop()（join）はしない。スレッドはこの後自分で終わる
                streams[index] = null
            },
        )
        streams[index] = stream
        stream.start()
    }

    fun disconnect(index: Int) {
        val s = streams[index]
        streams[index] = null
        viewModelScope.launch(Dispatchers.IO) { s?.stop() }
        frames[index].value = null
        updateTile(index) { it.copy(state = TileState.IDLE, message = "") }
    }

    fun connectAll() {
        val targets = _ui.value.tiles.filter { !it.connected }.map { it.index }
        if (targets.isEmpty()) { setStatus("すべてのカメラが接続済みです。"); return }
        setStatus("${targets.size} 台に接続しています...")
        val errors = ArrayList<Pair<Int, String>>()
        val pending = java.util.concurrent.atomic.AtomicInteger(targets.size) // 結果は受信スレッドから返るので原子的に数える
        for (i in targets) connect(i) { err ->
            if (err != null) synchronized(errors) { errors.add(i to "カメラ ${i + 1} (${_ui.value.tiles[i].host}):\n$err") }
            if (pending.decrementAndGet() == 0) {
                val connected = _ui.value.tiles.count { it.connected }
                // 結果は届いた順に溜まるので、ダイアログではカメラの番号順に並べる
                val text = synchronized(errors) { errors.sortedBy { it.first }.joinToString("\n\n") { it.second } }
                _ui.update { it.copy(status = "$connected / ${it.tiles.size} 台を表示中です。", notice = if (text.isEmpty()) null else Notice("接続エラー", text)) }
            }
        }
    }

    fun disconnectAll() {
        for (i in 0 until HostStore.CAMERA_COUNT) disconnect(i)
        setStatus("すべて切断しました。")
    }

    /** 画面が裏に回ったら映像を止める（電池と WiFi のため）。復帰で同じ台をつなぎ直す */
    fun pauseForBackground() {
        pausedByBackground = _ui.value.tiles.filter { it.connected }.map { it.index }.toSet()
        for (i in pausedByBackground) disconnect(i)
    }

    fun resumeFromBackground() {
        val set = pausedByBackground
        pausedByBackground = emptySet()
        for (i in set) connect(i)
    }

    // ---- API（撮影・一覧・状態・写真） ------------------------------------------

    private fun selectedHost(): Pair<Int, String>? {
        val u = _ui.value
        val tile = u.tiles[u.selected]
        return try {
            tile.index to CameraApi.normalizeHost(tile.host)
        } catch (e: ApiError) {
            _ui.update { it.copy(notice = Notice("入力エラー", "カメラ ${tile.index + 1}: ${e.message}")) }
            null
        }
    }

    private fun run(busyMessage: String, block: suspend () -> Unit) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, status = busyMessage) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: ApiError) {
                _ui.update { it.copy(status = "エラーが発生しました。", notice = Notice("通信エラー", e.message ?: "")) }
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun capture() {
        val (index, host) = selectedHost() ?: return
        val flash = _ui.value.flash
        run("カメラ ${index + 1} で撮影しています...") {
            val name = CameraApi.capture(host, flash)
            _ui.update { it.copy(status = "SD カードに保存しました: $name") }
            // 撮影のあとは一覧を自動で更新する
            val photos = CameraApi.listPhotos(host)
            _ui.update { it.copy(photos = photos, photosCamera = index) }
        }
    }

    fun captureAll() {
        val u = _ui.value
        val targets = ArrayList<Pair<Int, String>>()
        val bad = ArrayList<String>()
        for (t in u.tiles) {
            try { targets.add(t.index to CameraApi.normalizeHost(t.host)) } catch (e: ApiError) { bad.add("カメラ ${t.index + 1}: ${e.message}") }
        }
        if (bad.isNotEmpty()) { _ui.update { it.copy(notice = Notice("入力エラー", bad.joinToString("\n"))) }; return }
        val flash = u.flash
        run("${targets.size} 台で撮影しています...") {
            val results = withContext(Dispatchers.IO) {
                targets.map { (i, host) -> async { try { CameraApi.capture(host, flash); null } catch (e: ApiError) { "カメラ ${i + 1} ($host):\n${e.message}" } } }.awaitAll()
            }
            val errors = results.filterNotNull()
            val ok = results.size - errors.size
            _ui.update { it.copy(status = "$ok / ${results.size} 台で撮影しました。", notice = if (errors.isEmpty()) null else Notice("撮影エラー", errors.joinToString("\n\n"))) }
        }
    }

    fun fetchStatus() {
        val (index, host) = selectedHost() ?: return
        run("カメラ ${index + 1} の状態を取得しています...") {
            val st = CameraApi.fetchStatus(host)
            val text = if (!st.mounted) "SD カード: 未マウント" else "SD カード: ${st.type} / 使用 ${st.usedMb}MB / 全体 ${st.totalMb}MB  IP: ${st.wifi}"
            _ui.update { it.copy(status = "カメラ ${index + 1}  $text", lastStatus = st) }
        }
    }

    fun refreshPhotos() {
        val (index, host) = selectedHost() ?: return
        run("カメラ ${index + 1} の写真一覧を取得しています...") {
            val photos = CameraApi.listPhotos(host)
            _ui.update { it.copy(photos = photos, photosCamera = index, status = "写真 ${photos.size} 件を取得しました。") }
        }
    }

    /** 写真 1 枚（キャッシュあり）。失敗は ApiError */
    suspend fun loadPhoto(cameraIndex: Int, name: String): ByteArray {
        val key = "$cameraIndex/$name"
        photoCache.get(key)?.let { return it }
        val host = CameraApi.normalizeHost(_ui.value.tiles[cameraIndex].host)
        val bytes = CameraApi.fetchPhoto(host, name)
        photoCache.put(key, bytes)
        return bytes
    }

    suspend fun savePhoto(cameraIndex: Int, name: String, bytes: ByteArray): String =
        PhotoSaver.save(getApplication(), cameraIndex, name, bytes)

    override fun onCleared() {
        for (i in streams.indices) { streams[i]?.let { s -> Thread { s.stop() }.start() }; streams[i] = null }
    }
}
