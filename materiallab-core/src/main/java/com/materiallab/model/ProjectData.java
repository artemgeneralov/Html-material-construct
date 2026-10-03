package com.materiallab.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The complete in-memory project document (data only).
 * This is what serialization stores and what engines read. UI never owns state.
 */
public final class ProjectData {
    public int formatVersion = 1;
    public Map<String, Object> metadata = new LinkedHashMap<>();
    public List<ProjectObject> objects = new ArrayList<>();
    public List<Connection> connections = new ArrayList<>();
    public List<Map<String, Object>> customMaterials = new ArrayList<>();
    public List<Map<String, Object>> customComponents = new ArrayList<>();
    public List<Map<String, Object>> modules = new ArrayList<>();
    public List<Map<String, Object>> planTasks = new ArrayList<>();
    public SimulationSettings simulation = new SimulationSettings();
    public CameraState camera = new CameraState();
    public GridState grid = new GridState();

        public java.util.Map<String, ProjectObject> indexById() {
        java.util.Map<String, ProjectObject> m = new java.util.LinkedHashMap<>();
        for (ProjectObject o : objects) m.put(o.id, o);
        return m;
    }

    public ProjectObject byId(String id) {
        for (ProjectObject o : objects) if (o.id.equals(id)) return o;
        return null;
    }

    public String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public void addObject(ProjectObject o) { objects.add(o); }

    public boolean removeObject(String id) {
        boolean removed = objects.removeIf(o -> o.id.equals(id));
        if (removed) connections.removeIf(c -> c.aObj.equals(id) || c.bObj.equals(id));
        return removed;
    }

    /** Find connection between two endpoints, or null. */
    public Connection findConnection(String objA, String portA, String objB, String portB) {
        for (Connection c : connections) {
            if (c.involves(objA, portA) && c.involves(objB, portB)) return c;
        }
        return null;
    }

    /** Weld two endpoints; replaces an existing connection between them. Returns the weld. */
    public Connection weld(String idA, String portA, String idB, String portB, ConnectionType type) {
        ProjectObject a = byId(idA), b = byId(idB);
        if (a == null || b == null) throw new IllegalArgumentException("Unknown object in weld: " + idA + "/" + idB);
        Connection existing = findConnection(idA, portA, idB, portB);
        if (existing != null) return existing;
        Connection c = new Connection(newId("weld"), idA, portA, idB, portB, type);
        connections.add(c);
        return c;
    }

    public boolean cutWeld(String connectionId) {
        return connections.removeIf(c -> c.id.equals(connectionId));
    }
}
