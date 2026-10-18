package app.thermalcam.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.thermalcam.data.Photo
import app.thermalcam.data.PhotoStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 削除の結果（画面で知らせる） */
data class DeleteResult(val deleted: Int, val failed: Int)

/** 保存した写真の一覧（新しい順）と、削除のための選択。写真の画面を開くたびに読み直す */
class PhotosViewModel(app: Application) : AndroidViewModel(app) {
    private val _photos = MutableStateFlow<List<Photo>?>(null) // null = 読み込み中
    val photos: StateFlow<List<Photo>?> = _photos

    /** 選んだ写真（空なら選択モードではない） */
    private val _selected = MutableStateFlow<Set<Uri>>(emptySet())
    val selected: StateFlow<Set<Uri>> = _selected

    private val _result = MutableStateFlow<DeleteResult?>(null)
    val result: StateFlow<DeleteResult?> = _result

    fun refresh() = viewModelScope.launch {
        _photos.value = runCatching { PhotoStore.list(getApplication()) }.getOrDefault(emptyList())
        // 消えた写真は選択から外す
        val now = _photos.value.orEmpty().map { it.uri }.toSet()
        _selected.update { it intersect now }
    }

    fun toggle(uri: Uri) = _selected.update { if (uri in it) it - uri else it + uri }
    fun selectAll() { _selected.value = _photos.value.orEmpty().map { it.uri }.toSet() }
    fun clearSelection() { _selected.value = emptySet() }

    fun deleteSelected() = viewModelScope.launch {
        val sel = _selected.value
        val targets = _photos.value.orEmpty().filter { it.uri in sel }
        if (targets.isEmpty()) return@launch
        val failed = PhotoStore.delete(getApplication(), targets)
        _selected.value = emptySet()
        _result.value = DeleteResult(targets.size - failed, failed)
        refresh()
    }

    fun resultShown() { _result.value = null }
}
