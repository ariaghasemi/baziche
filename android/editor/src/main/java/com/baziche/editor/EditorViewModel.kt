package com.baziche.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baziche.core.common.ApiResult
import com.baziche.core.data.repo.ProjectRepository
import com.baziche.core.data.repo.SaveResult
import com.baziche.editor.core.EdButton
import com.baziche.editor.core.EdText
import com.baziche.editor.core.EditorDoc
import com.baziche.editor.core.ObjItem
import com.baziche.editor.core.ObjTransform
import com.baziche.editor.core.SceneItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

data class ConflictInfo(val serverRev: Int, val serverCopy: JsonObject?)

data class EditorUi(
    val loading: Boolean = true,
    val projectName: String = "",
    val gameType: String = "",
    val rev: Int = 0,
    val offline: Boolean = false,
    val scenes: List<SceneItem> = emptyList(),
    val activeSceneId: String? = null,
    val objects: List<ObjItem> = emptyList(),
    val selectedId: String? = null,
    val orientation: String = "portrait",
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val savedTick: Long = 0L,
    val error: String? = null,
    val conflict: ConflictInfo? = null,
    val mergeNote: List<String>? = null,
    val pendingSceneMenu: String? = null,
    val renamingScene: String? = null,
    val addingScene: Boolean = false,
) {
    val activeScene: SceneItem? get() = scenes.firstOrNull { it.id == activeSceneId }
    val selected: ObjItem? get() = objects.firstOrNull { it.id == selectedId }
}

class EditorViewModel(
    private val repo: ProjectRepository,
    private val projectId: String,
) : ViewModel() {
    private val _ui = MutableStateFlow(EditorUi())
    val ui: StateFlow<EditorUi> = _ui

    private var doc: EditorDoc? = null
    private var baseRev: Int = 0

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null, conflict = null)
            when (val r = repo.getProject(projectId)) {
                is ApiResult.Success -> {
                    val json = r.data.json
                    if (json == null) {
                        _ui.value = _ui.value.copy(loading = false, error = "empty project")
                        return@launch
                    }
                    doc = EditorDoc(json)
                    baseRev = r.data.project.rev
                    val scenes = doc!!.scenes()
                    _ui.value = EditorUi(
                        loading = false,
                        projectName = r.data.project.name,
                        gameType = r.data.project.gameType,
                        rev = r.data.project.rev,
                        scenes = scenes,
                        activeSceneId = scenes.firstOrNull { it.entry }?.id ?: scenes.firstOrNull()?.id,
                        objects = emptyList(),
                        orientation = doc!!.orientation(),
                    )
                    emit()
                }
                is ApiResult.Error -> {
                    _ui.value = _ui.value.copy(loading = false, error = r.message)
                }
            }
        }
    }

    private fun emit() {
        val d = doc ?: return
        val u = _ui.value
        val scenes = d.scenes()
        val active = u.activeSceneId?.takeIf { id -> scenes.any { it.id == id } }
            ?: scenes.firstOrNull { it.entry }?.id ?: scenes.firstOrNull()?.id
        _ui.value = u.copy(
            scenes = scenes,
            activeSceneId = active,
            objects = active?.let { d.objectsIn(it) } ?: emptyList(),
            orientation = d.orientation(),
        )
    }

    private fun markDirty() {
        _ui.value = _ui.value.copy(dirty = true)
    }

    fun dismissError() {
        _ui.value = _ui.value.copy(error = null)
    }

    fun dismissMergeNote() {
        _ui.value = _ui.value.copy(mergeNote = null)
    }

    // ---------- scenes ----------
    fun selectScene(id: String) {
        _ui.value = _ui.value.copy(activeSceneId = id, selectedId = null)
        emit()
    }

    fun addScene(name: String) {
        val d = doc ?: return
        val item = d.addScene(name)
        _ui.value = _ui.value.copy(activeSceneId = item.id, selectedId = null, addingScene = false)
        markDirty(); emit()
    }

    fun renameScene(id: String, name: String) {
        val d = doc ?: return
        if (d.renameScene(id, name)) {
            _ui.value = _ui.value.copy(renamingScene = null)
            markDirty(); emit()
        }
    }

    fun deleteScene(id: String) {
        val d = doc ?: return
        if (d.deleteScene(id)) {
            val u = _ui.value
            if (u.activeSceneId == id) _ui.value = u.copy(activeSceneId = null, selectedId = null)
            _ui.value = _ui.value.copy(pendingSceneMenu = null)
            markDirty(); emit()
        }
    }

    fun duplicateScene(id: String) {
        val d = doc ?: return
        val copy = d.duplicateScene(id) ?: return
        _ui.value = _ui.value.copy(activeSceneId = copy.id, selectedId = null, pendingSceneMenu = null)
        markDirty(); emit()
    }

    fun setEntry(id: String) {
        val d = doc ?: return
        if (d.setEntry(id)) {
            _ui.value = _ui.value.copy(pendingSceneMenu = null)
            markDirty(); emit()
        }
    }

    fun moveScene(id: String, dir: Int) {
        val d = doc ?: return
        if (d.moveScene(id, dir)) markDirty()
        _ui.value = _ui.value.copy(pendingSceneMenu = null)
        emit()
    }

    fun openSceneMenu(id: String) {
        _ui.value = _ui.value.copy(pendingSceneMenu = id)
    }

    fun closeSceneMenu() {
        _ui.value = _ui.value.copy(pendingSceneMenu = null)
    }

    fun promptAddScene() {
        _ui.value = _ui.value.copy(addingScene = true)
    }

    fun cancelAddScene() {
        _ui.value = _ui.value.copy(addingScene = false)
    }

    fun promptRenameScene(id: String) {
        _ui.value = _ui.value.copy(renamingScene = id, pendingSceneMenu = null)
    }

    fun cancelRenameScene() {
        _ui.value = _ui.value.copy(renamingScene = null)
    }

    // ---------- objects ----------
    fun addObject(kind: String, x: Float, y: Float) {
        val d = doc ?: return
        val sceneId = _ui.value.activeSceneId ?: return
        val obj = d.addObject(sceneId, kind, x, y) ?: return
        _ui.value = _ui.value.copy(selectedId = obj.id)
        markDirty(); emit()
    }

    fun select(id: String?) {
        _ui.value = _ui.value.copy(selectedId = id)
    }

    fun moveSelected(dx: Float, dy: Float) {
        val d = doc ?: return
        val sel = _ui.value.selected ?: return
        val t = sel.transform
        if (d.updateTransform(sel.id, t.copy(x = t.x + dx, y = t.y + dy))) {
            markDirty(); emit()
        }
    }

    fun setTransform(t: ObjTransform) {
        val d = doc ?: return
        val sel = _ui.value.selected ?: return
        if (d.updateTransform(sel.id, t)) {
            markDirty(); emit()
        }
    }

    fun toggleVisible() {
        val d = doc ?: return
        val sel = _ui.value.selected ?: return
        if (d.updateObject(sel.id, visible = !sel.visible)) {
            markDirty(); emit()
        }
    }

    fun changeLayer(delta: Int) {
        val d = doc ?: return
        val sel = _ui.value.selected ?: return
        if (d.updateObject(sel.id, layer = (sel.layer + delta).coerceAtLeast(0))) {
            markDirty(); emit()
        }
    }

    fun deleteSelected() {
        val d = doc ?: return
        val sel = _ui.value.selected ?: return
        if (d.deleteObject(sel.id)) {
            _ui.value = _ui.value.copy(selectedId = null)
            markDirty(); emit()
        }
    }

    // ---------- text / button content ----------
    fun selectedText(): EdText? {
        val id = _ui.value.selectedId ?: return null
        return doc?.getText(id)
    }

    fun setText(text: String, size: Float, color: String) {
        val d = doc ?: return
        val sel = _ui.value.selected ?: return
        if (d.setText(sel.id, text, size, color)) {
            markDirty(); emit()
        }
    }

    fun selectedButton(): EdButton? {
        val id = _ui.value.selectedId ?: return null
        return doc?.getButton(id)
    }

    fun setButtonLabel(label: String) {
        val d = doc ?: return
        val sel = _ui.value.selected ?: return
        val cur = d.getButton(sel.id)
        if (d.setButton(sel.id, label, cur?.enabled ?: true)) {
            markDirty(); emit()
        }
    }

    fun setButtonEnabled(enabled: Boolean) {
        val d = doc ?: return
        val sel = _ui.value.selected ?: return
        val cur = d.getButton(sel.id)
        if (d.setButton(sel.id, cur?.label ?: "", enabled)) {
            markDirty(); emit()
        }
    }

    // ---------- save / sync ----------
    fun save() {
        val d = doc ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(saving = true, error = null)
            when (val r = repo.saveProject(projectId, baseRev, d.toJson())) {
                is SaveResult.Saved -> {
                    baseRev = r.rev
                    _ui.value = _ui.value.copy(saving = false, dirty = false, rev = r.rev, savedTick = System.currentTimeMillis())
                }
                is SaveResult.Conflict -> {
                    _ui.value = _ui.value.copy(
                        saving = false,
                        conflict = ConflictInfo(r.serverRev, r.serverCopy),
                    )
                }
                is SaveResult.OfflineCached -> {
                    _ui.value = _ui.value.copy(saving = false, offline = true, error = "offline_saved")
                }
                is SaveResult.Failed -> {
                    _ui.value = _ui.value.copy(saving = false, error = r.message)
                }
            }
        }
    }

    /** Keep mine: overwrite the server copy (base = server rev). */
    fun forceSave() {
        val conflict = _ui.value.conflict ?: return
        baseRev = conflict.serverRev
        _ui.value = _ui.value.copy(conflict = null)
        save()
    }

    /** Use server: discard my edits, load the server copy. */
    fun useServer() {
        val conflict = _ui.value.conflict ?: return
        val copy = conflict.serverCopy
        if (copy == null) {
            _ui.value = _ui.value.copy(conflict = null)
            load()
            return
        }
        doc = EditorDoc(copy)
        baseRev = conflict.serverRev
        _ui.value = _ui.value.copy(
            conflict = null, dirty = false, rev = conflict.serverRev,
            selectedId = null, activeSceneId = null,
        )
        emit()
    }

    /** Auto-merge: three-way merge on the server, then continue editing the merged doc. */
    fun autoMerge() {
        val d = doc ?: return
        _ui.value.conflict ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(saving = true)
            try {
                val res = repo.mergeProject(projectId, baseRev, d.toJson())
                doc = EditorDoc(res.merged)
                baseRev = res.serverRev
                _ui.value = _ui.value.copy(
                    saving = false, conflict = null, dirty = true,
                    rev = res.serverRev, mergeNote = res.conflicts,
                )
                emit()
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(saving = false, error = t.message ?: "merge failed")
            }
        }
    }
}
