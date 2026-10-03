package com.materiallab.simulation;

import com.materiallab.model.BodyKind;
import com.materiallab.model.CameraState;
import com.materiallab.model.Connection;
import com.materiallab.model.GridState;
import com.materiallab.model.ProjectData;
import com.materiallab.model.ProjectObject;
import com.materiallab.model.SimulationSettings;
import com.materiallab.model.Vec2;
import java.util.ArrayList;

/** Deep copy helper used to isolate simulation state from the document (spec 5). */
public final class ProjectSerializer {

    private ProjectSerializer() {}

    public static ProjectData copy(ProjectData src) {
        ProjectData d = new ProjectData();
        d.formatVersion = src.formatVersion;
        d.metadata.putAll(src.metadata);
        for (ProjectObject o : src.objects) d.objects.add(copyObject(o));
        for (Connection c : src.connections) d.connections.add(copyConn(c));
        d.customMaterials.addAll(src.customMaterials);
        d.customComponents.addAll(src.customComponents);
        d.modules.addAll(src.modules);
        d.planTasks.addAll(src.planTasks);
        d.simulation = copySettings(src.simulation);
        d.camera = copyCamera(src.camera);
        d.grid = copyGrid(src.grid);
        return d;
    }

    private static ProjectObject copyObject(ProjectObject o) {
        ProjectObject n = new ProjectObject(o.id, o.typeId, o.kind);
        n.name = o.name;
        n.pos = new Vec2(o.pos.x, o.pos.y);
        n.rotation = o.rotation;
        n.width = o.width; n.height = o.height;
        n.materialId = o.materialId;
        n.fixed = o.fixed;
        n.params.putAll(o.params);
        n.state.putAll(o.state);
        n.stringParams.putAll(o.stringParams);
        if (o.points != null) {
            n.points = new ArrayList<>();
            for (Vec2 p : o.points) n.points.add(p.copy());
        }
        n.parentModuleId = o.parentModuleId;
        return n;
    }

    private static Connection copyConn(Connection c) {
        Connection n = new Connection(c.id, c.aObj, c.aPort, c.bObj, c.bPort, c.type);
        n.welded = c.welded; n.weldKind = c.weldKind;
        n.strength = c.strength; n.meltTemp = c.meltTemp; n.resistance = c.resistance;
        return n;
    }

    private static SimulationSettings copySettings(SimulationSettings s) {
        SimulationSettings n = new SimulationSettings();
        n.gravity = s.gravity; n.timeStep = s.timeStep; n.airDrag = s.airDrag;
        n.ambientTemp = s.ambientTemp; n.restitution = s.restitution; n.friction = s.friction;
        return n;
    }

    private static CameraState copyCamera(CameraState c) {
        CameraState n = new CameraState();
        n.centerX = c.centerX; n.centerY = c.centerY; n.zoom = c.zoom;
        return n;
    }

    private static GridState copyGrid(GridState g) {
        GridState n = new GridState();
        n.size = g.size; n.visible = g.visible; n.snap = g.snap;
        return n;
    }

    /** Convenience: create a wire object between two world points. */
    public static ProjectObject makeWire(String id, Vec2 from, Vec2 to, String materialId) {
        ProjectObject w = new ProjectObject(id, "wire", BodyKind.WIRE);
        w.name = "Провод";
        w.materialId = materialId;
        w.points = new ArrayList<>();
        w.points.add(from.copy());
        w.points.add(to.copy());
        double len = from.dist(to);
        double radius = 0.001; // 1 mm default
        double rho = com.materiallab.materials.MaterialsRegistry.get().get(materialId).density;
        double volume = Math.PI * radius * radius * len;
        w.params.put("length", len);
        w.params.put("radius", radius);
        w.params.put("mass", rho * volume);
        // R = rho_len * L / A ; copper resistivity 1.68e-8 Ohm*m (reference)
        double cond = com.materiallab.materials.MaterialsRegistry.get().get(materialId).conductivity;
        w.params.put("resistance", cond > 0 ? len / (cond * Math.PI * radius * radius) : 1e9);
        w.params.put("maxCurrent", 5.0);       // sim parameter
        w.params.put("meltTemp", com.materiallab.materials.MaterialsRegistry.get().get(materialId).meltingPoint);
        return w;
    }

    public static ProjectObject makeRope(String id, Vec2 from, Vec2 to) {
        ProjectObject r = new ProjectObject(id, "rope", BodyKind.ROPE);
        r.name = "Верёвка";
        r.materialId = "rubber";
        r.points = new ArrayList<>();
        r.points.add(from.copy());
        r.points.add(to.copy());
        r.params.put("length", from.dist(to));
        r.params.put("mass", 0.05);
        r.params.put("strength", 50.0);   // sim parameter
        return r;
    }
}
