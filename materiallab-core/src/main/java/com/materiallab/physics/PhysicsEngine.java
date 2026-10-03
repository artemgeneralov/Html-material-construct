package com.materiallab.physics;

import com.materiallab.electronics.ComponentDef;
import com.materiallab.electronics.ComponentsCatalog;
import com.materiallab.materials.MaterialsRegistry;
import com.materiallab.model.BodyKind;
import com.materiallab.model.Connection;
import com.materiallab.model.ConnectionType;
import com.materiallab.model.PortDef;
import com.materiallab.model.ProjectData;
import com.materiallab.model.ProjectObject;
import com.materiallab.model.SimulationSettings;
import com.materiallab.model.Vec2;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom lightweight 2D rigid-body engine (see ARCHITECTURE.md for the
 * "why not JBox2D" decision). Semi-implicit Euler + iterative constraint
 * solver + AABB collisions + lumped thermal model. Physics never touches UI.
 */
public final class PhysicsEngine {

    public final Map<String, PhysicsBody> bodies = new HashMap<>();
    public final List<Constraint> constraints = new ArrayList<>();
    public final List<RopeSegmentChain> chains = new ArrayList<>();
    private final List<String> brokenConnections = new ArrayList<>();

    public List<String> consumeBrokenConnections() {
        List<String> out = new ArrayList<>(brokenConnections);
        brokenConnections.clear();
        return out;
    }

    public void reset(ProjectData p) {
        bodies.clear(); constraints.clear(); chains.clear(); brokenConnections.clear();
        for (ProjectObject o : p.objects) {
            if (o.kind == BodyKind.WIRE || o.kind == BodyKind.ROPE) {
                chains.add(new RopeSegmentChain(o, o.kind == BodyKind.WIRE ? 8 : 12));
                continue;
            }
            double mass = o.mass();
            if (mass <= 0) mass = density(o) * o.width * o.height * 0.01; // thin slab approx
            double inertia = Math.max(mass * (o.width * o.width + o.height * o.height) / 12.0, 1e-9);
            boolean fixed = o.fixed || isAnchor(o);
            bodies.put(o.id, new PhysicsBody(o, mass, inertia, fixed));
        }
        for (Connection c : p.connections) {
            if (c.type == ConnectionType.ELECTRICAL) continue;
            if (!c.welded && !"spring".equals(c.weldKind)) continue;
            PhysicsBody ba = bodies.get(c.aObj), bb = bodies.get(c.bObj);
            if (ba == null || bb == null) continue;
            Vec2 la = localAnchor(ba.owner, c.aPort), lb = localAnchor(bb.owner, c.bPort);
            Constraint con;
            ProjectObject ao = ba.owner, bo = bb.owner;
            if (isSpring(ao) || isSpring(bo)) {
                DistanceConstraint dc = new DistanceConstraint(ba, bb, la, lb,
                        ao.param("restLength", worldDist(ba, la, bb, lb)), false);
                dc.stiffness = Math.min(1.0, ao.param("stiffness", 200) / 400.0);
                con = dc;
            } else if (isHinge(ao) || isHinge(bo) || "hinge".equals(c.weldKind)) {
                con = new RevoluteJoint(ba, bb, la, lb);
            } else {
                WeldJoint wj = new WeldJoint(ba, bb, la, lb);
                wj.meltTemp = c.meltTemp;
                con = wj;
            }
            con.breakForce = c.strength;
            con.connectionId = c.id;
            constraints.add(con);
        }
    }

    public void step(ProjectData p, double dt) {
        SimulationSettings s = p.simulation;
        List<ProjectObject> objs = new ArrayList<>();
        for (ProjectObject o : p.objects) if (o.kind == BodyKind.COMPONENT) objs.add(o);
        for (ProjectObject o : objs) {
            PhysicsBody b = bodies.get(o.id);
            if (b == null || b.fixed) continue;
            b.velocity.y -= s.gravity * dt;
            b.velocity = b.velocity.mul(Math.max(0, 1 - s.airDrag * dt));
            o.pos = o.pos.add(b.velocity.mul(dt));
            o.rotation += b.angularVelocity * dt;
            Double tq = o.state.get("torque");
            if (tq != null && Math.abs(tq) > 1e-9)
                b.angularVelocity += tq / b.inertia * dt * 0.02; // scaled for stability
        }
        for (int it = 0; it < 6; it++)
            for (Constraint c : constraints) c.solve(dt);
        detectBroken();
        for (RopeSegmentChain ch : chains) ch.step(s.gravity, dt, s.airDrag);
        collide(objs, s);
        double ground = -0.5;
        for (ProjectObject o : objs) {
            PhysicsBody b = bodies.get(o.id);
            if (b == null || b.fixed) continue;
            double half = o.height / 2;
            if (o.pos.y - half < ground) {
                o.pos.y = ground + half;
                if (b.velocity.y < 0) b.velocity.y = -b.velocity.y * s.restitution;
                b.velocity.x *= (1 - Math.min(0.9, s.friction * dt * 10));
            }
        }
        // thermal: Joule heating + Newton cooling (simplified)
        for (ProjectObject o : objs) {
            PhysicsBody b = bodies.get(o.id);
            if (b == null) continue;
            double i = o.state.getOrDefault("current", 0.0);
            double u = o.state.getOrDefault("voltage", 0.0);
            double watts = u * i;
            double heatCap = Math.max(b.mass * 500, 1e-3);
            b.temperature += (watts * dt) / heatCap;
            b.temperature += (s.ambientTemp - b.temperature) * 0.05 * dt;
            o.state.put("temperature", b.temperature);
        }
    }

    private void detectBroken() {
        for (Constraint c : constraints)
            if (c.broken && c.connectionId != null && !c.connectionId.contains("#"))
                brokenConnections.add(c.connectionId);
    }

    private void collide(List<ProjectObject> objs, SimulationSettings s) {
        for (int i = 0; i < objs.size(); i++)
            for (int j = i + 1; j < objs.size(); j++) {
                ProjectObject a = objs.get(i), b = objs.get(j);
                PhysicsBody ba = bodies.get(a.id), bb = bodies.get(b.id);
                if (ba == null || bb == null) continue;
                if (areMechanicallyLinked(ba, bb)) continue;
                double ox = (a.width + b.width) / 2 - Math.abs(a.pos.x - b.pos.x);
                double oy = (a.height + b.height) / 2 - Math.abs(a.pos.y - b.pos.y);
                if (ox > 0 && oy > 0) {
                    if (ox < oy) push(ba, bb, (a.pos.x < b.pos.x ? -1 : 1) * ox, 0);
                    else push(ba, bb, 0, (a.pos.y < b.pos.y ? -1 : 1) * oy);
                }
            }
    }

    private void push(PhysicsBody a, PhysicsBody b, double dx, double dy) {
        double wa = a.fixed ? 0 : 1, wb = b.fixed ? 0 : 1;
        double sum = wa + wb;
        if (sum == 0) return;
        if (!a.fixed) a.owner.pos = a.owner.pos.add(new Vec2(-dx * wa / sum, -dy * wa / sum));
        if (!b.fixed) b.owner.pos = b.owner.pos.add(new Vec2(dx * wb / sum, dy * wb / sum));
        Vec2 n = new Vec2(dx, dy);
        double len = n.len();
        if (len < 1e-12) return;
        n = n.mul(1 / len);
        Vec2 rel = b.velocity.sub(a.velocity);
        double vn = rel.dot(n);
        if (vn < 0) {
            double ima = a.fixed ? 0 : 1 / Math.max(a.mass, 1e-6);
            double imb = b.fixed ? 0 : 1 / Math.max(b.mass, 1e-6);
            if (ima + imb == 0) return;
            double imp = -(1 + 0.3) * vn / (ima + imb);
            Vec2 i = n.mul(imp);
            if (!a.fixed) a.velocity = a.velocity.sub(i.mul(ima));
            if (!b.fixed) b.velocity = b.velocity.add(i.mul(imb));
        }
    }

    private boolean areMechanicallyLinked(PhysicsBody a, PhysicsBody b) {
        for (Constraint c : constraints) {
            if (c.broken) continue;
            if ((c.a == a && c.b == b) || (c.a == b && c.b == a)) return true;
        }
        return false;
    }

    private static boolean isAnchor(ProjectObject o) {
        ComponentDef def = ComponentsCatalog.get().get(o.typeId);
        return def != null && def.fixedAnchor;
    }
    private static boolean isSpring(ProjectObject o) { return "spring".equals(o.typeId); }
    private static boolean isHinge(ProjectObject o) { return "hinge".equals(o.typeId); }

    private static double density(ProjectObject o) {
        return MaterialsRegistry.get().get(o.materialId).density;
    }

    private static Vec2 localAnchor(ProjectObject o, String portId) {
        ComponentDef def = ComponentsCatalog.get().get(o.typeId);
        if (def != null) {
            PortDef pd = def.port(portId);
            if (pd != null) return new Vec2(pd.localX, pd.localY);
        }
        return new Vec2();
    }

    private static double worldDist(PhysicsBody a, Vec2 la, PhysicsBody b, Vec2 lb) {
        return Constraint.world(a, la).dist(Constraint.world(b, lb));
    }
}
