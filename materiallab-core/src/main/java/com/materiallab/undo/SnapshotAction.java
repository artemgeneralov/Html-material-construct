package com.materiallab.undo;

import com.materiallab.model.ProjectData;
import com.materiallab.simulation.ProjectSerializer;

/**
 * Snapshot-based undo action. Simple, robust and complete for all edit types
 * (add/move/weld/delete...). Memory-cheap for typical project sizes; a command
 * pattern can replace it later without changing UndoManager API.
 */
public final class SnapshotAction implements Action {
    private final ProjectData target;      // live document mutated in place
    private final ProjectData beforeState; // snapshot taken BEFORE redo applied
    private final ProjectData afterState;  // snapshot to apply on redo
    private final String description;

    /** Capture 'before' now, then caller mutates target, then calls seal(). */
    public static SnapshotAction begin(ProjectData target, String description) {
        return new SnapshotAction(target, ProjectSerializer.copy(target), null, description);
    }

    private SnapshotAction(ProjectData target, ProjectData before, ProjectData after, String d) {
        this.target = target; this.beforeState = before; this.afterState = after; this.description = d;
    }

    /** Call AFTER the mutation has been applied to target. */
    public SnapshotAction seal() {
        return new SnapshotAction(target, beforeState, ProjectSerializer.copy(target), description);
    }

    /** Build a finished action from explicit snapshots (used for drag gestures
     *  where 'before' is captured at gesture start and 'after' at gesture end). */
    public static SnapshotAction of(ProjectData target, ProjectData before, ProjectData after, String description) {
        return new SnapshotAction(target, before, after, description);
    }

    @Override public void redo() { replaceWith(afterState != null ? afterState : target); }
    @Override public void undo() { replaceWith(beforeState); }
    @Override public String describe() { return description; }

    private void replaceWith(ProjectData snap) {
        ProjectData fresh = ProjectSerializer.copy(snap);
        target.objects.clear(); target.objects.addAll(fresh.objects);
        target.connections.clear(); target.connections.addAll(fresh.connections);
        target.customMaterials.clear(); target.customMaterials.addAll(fresh.customMaterials);
        target.customComponents.clear(); target.customComponents.addAll(fresh.customComponents);
        target.modules.clear(); target.modules.addAll(fresh.modules);
    }
}
