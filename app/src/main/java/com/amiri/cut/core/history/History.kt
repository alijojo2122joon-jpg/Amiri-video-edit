package com.amiri.cut.core.history

import com.amiri.cut.core.model.Project

/**
 * Snapshot-based, project-scoped Undo/Redo. Projects are immutable values, so a
 * history entry is just a reference — cheap even at the 100-step limit because
 * unchanged tracks/clips are shared between snapshots.
 */
class History(private val limit: Int = 100) {
    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    data class Entry(val project: Project, val label: String)

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoLabel: String? get() = undoStack.lastOrNull()?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.label

    /** Records [before] as the state to return to; clears the redo branch. */
    fun record(before: Project, label: String) {
        undoStack.addLast(Entry(before, label))
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(current: Project): Entry? {
        val e = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(Entry(current, e.label))
        return e
    }

    fun redo(current: Project): Entry? {
        val e = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(Entry(current, e.label))
        return e
    }

    fun clear() {
        undoStack.clear(); redoStack.clear()
    }
}
