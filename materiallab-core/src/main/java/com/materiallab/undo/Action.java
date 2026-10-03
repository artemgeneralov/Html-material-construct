package com.materiallab.undo;

/** A reversible edit action (spec 3: Undo/Redo). */
public interface Action {
    void redo();
    void undo();
    String describe();
}
