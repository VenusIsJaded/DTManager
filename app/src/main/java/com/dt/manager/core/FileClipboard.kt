package com.dt.manager.core

import java.io.File

/**
 * Singleton clipboard holding a file (or directory) plus a flag for whether
 * the operation is copy or cut. Used to move files between the two panes
 * of the dual-pane file browser.
 */
class FileClipboard private constructor() {

    enum class Action { COPY, CUT }

    var source: File? = null
        private set
    var action: Action? = null
        private set

    val isEmpty: Boolean get() = source == null

    fun set(source: File?, action: Action?) {
        this.source = source
        this.action = action
    }

    fun clear() {
        source = null
        action = null
    }

    companion object {
        @Volatile
        private var instance: FileClipboard? = null

        @JvmStatic
        fun getInstance(): FileClipboard {
            return instance ?: synchronized(this) {
                instance ?: FileClipboard().also { instance = it }
            }
        }
    }
}
