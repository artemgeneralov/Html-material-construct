package com.materiallab.undo;

import java.util.ArrayDeque;
import java.util.Deque;

/** Classic two-stack undo manager with capacity limit. */
public final class UndoManager {
    private final Deque<Action> undoStack = new ArrayDeque<>();
    private final Deque<Action> redoStack = new ArrayDeque<>();
    private final int capacity;

    public UndoManager() { this(200); }
    public UndoManager(int capacity) { this.capacity = capacity; }

    /** Execute and record an action. */
    public void perform(Action a) {
        a.redo();
        undoStack.push(a);
        if (undoStack.size() > capacity) undoStack.pollLast();
        redoStack.clear();
    }

    /** Record without executing (for already-applied changes). */
    public void record(Action a) {
        undoStack.push(a);
        redoStack.clear();
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    public String undo() {
        if (!canUndo()) return null;
        Action a = undoStack.pop();
        a.undo();
        redoStack.push(a);
        return a.describe();
    }

    public String redo() {
        if (!canRedo()) return null;
        Action a = redoStack.pop();
        a.redo();
        undoStack.push(a);
        return a.describe();
    }

    public void clear() { undoStack.clear(); redoStack.clear(); }
    public int undoDepth() { return undoStack.size(); }
}
