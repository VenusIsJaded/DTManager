package com.dt.manager.core;

import java.io.File;

/**
 * Singleton clipboard holding a file (or directory) plus a flag for whether
 * the operation is copy or cut. Used to move files between the two panes
 * of the dual-pane file browser.
 */
public class FileClipboard {

    public enum Action { COPY, CUT }

    private static FileClipboard instance;
    private File source;
    private Action action;

    private FileClipboard() {}

    public static synchronized FileClipboard getInstance() {
        if (instance == null) instance = new FileClipboard();
        return instance;
    }

    public void set(File source, Action action) {
        this.source = source;
        this.action = action;
    }

    public File getSource() { return source; }
    public Action getAction() { return action; }

    public boolean isEmpty() { return source == null; }

    public void clear() {
        source = null;
        action = null;
    }
}
