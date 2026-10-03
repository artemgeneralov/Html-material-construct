package com.materiallab.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single physical object in the scene: placed component, wire or rope.
 * Data-only class; behaviour lives in simulation/engine packages.
 */
public final class ProjectObject {
    public String id;
    public BodyKind kind = BodyKind.COMPONENT;
    public String typeId;              // component catalog id ("battery","lamp",...) or "wire"/"rope"
    public String name;                // user-visible label
    public Vec2 pos = new Vec2();      // center, meters (world)
    public double rotation;            // radians
    public double width = 0.1, height = 0.1;   // bounding size, meters
    public String materialId = "plastic";
    public boolean fixed = false;      // attached to ground (anchor)
    public Map<String, Double> params = new LinkedHashMap<>(); // electrical/mechanical params
    public Map<String, Double> state = new LinkedHashMap<>();  // dynamic runtime state (not persisted as truth)
    public Map<String, String> stringParams = new LinkedHashMap<>();
    // wires/ropes: polyline points in world coordinates (first = start, last = end)
    public java.util.List<Vec2> points;
    public String parentModuleId;      // nesting for custom modules (phase B)

    public ProjectObject() {}
    public ProjectObject(String id, String typeId, BodyKind kind) {
        this.id = id; this.typeId = typeId; this.kind = kind;
    }

    public double param(String key, double def) {
        Double v = params.get(key);
        return v == null ? def : v;
    }

    public double mass() { return params.getOrDefault("mass", 0.0); }
}
