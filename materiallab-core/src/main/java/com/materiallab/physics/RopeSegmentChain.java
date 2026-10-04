package com.materiallab.physics;

import com.materiallab.model.BodyKind;
import com.materiallab.model.ProjectObject;
import com.materiallab.model.Vec2;
import java.util.ArrayList;
import java.util.List;

/** Rope/wire as a chain of point masses connected by distance constraints (spec 7-8). */
public final class RopeSegmentChain {
    public final ProjectObject owner;
    public final List<Vec2> nodes = new ArrayList<>();
    public final List<DistanceConstraint> links = new ArrayList<>();
    private final List<PhysicsBody> bodies = new ArrayList<>();
    private PhysicsBody pinnedA, pinnedB;   // welded endpoints follow their parent body

    /** Attach a chain endpoint to a dynamic body's world anchor (welded wire/rope). */
    public void pinEnd(boolean start, PhysicsBody parent, Vec2 local) {
        PhysicsBody stubNode = start ? bodies.get(0) : bodies.get(bodies.size() - 1);
        if (start) pinnedA = parent; else pinnedB = parent;
        stubNode.fixedLocal = local;
        stubNode.followParent = parent;
    }

    public RopeSegmentChain(ProjectObject owner, int segments) {
        this.owner = owner;
        List<Vec2> pts = owner.points;
        if (pts == null || pts.size() < 2) return;
        double totalLen = 0;
        for (int i = 1; i < pts.size(); i++) totalLen += pts.get(i).dist(pts.get(i - 1));
        int n = Math.max(segments, pts.size());
        for (int i = 0; i < n; i++) nodes.add(samplePolyline(pts, i / (double) (n - 1)));
        double segLen = totalLen / (n - 1);
        for (int i = 0; i < n; i++) {
            ProjectObject stub = new ProjectObject(owner.id + "#node" + i, "rope-node", BodyKind.ROPE);
            stub.pos = nodes.get(i).copy();
            stub.params.put("mass", Math.max(owner.mass(), 1e-4) / n);
            bodies.add(new PhysicsBody(stub, stub.mass(), 1e-6, false));
        }
        for (int i = 0; i < n - 1; i++) {
            DistanceConstraint dc = new DistanceConstraint(
                bodies.get(i), bodies.get(i + 1), new Vec2(), new Vec2(), segLen, false);
            dc.connectionId = owner.id + "-seg" + i;
            links.add(dc);
        }
    }

    public void step(double gravity, double dt, double drag) {
        for (PhysicsBody b : bodies) {
            if (b.followParent != null) continue;   // welded endpoint: driven by parent below
            b.velocity.y -= gravity * dt;
            b.velocity = b.velocity.mul(Math.max(0, 1 - drag * dt));
            b.owner.pos = b.owner.pos.add(b.velocity.mul(dt));
        }
        // drive welded endpoints from their parent body's world anchor
        if (pinnedA != null && !bodies.isEmpty())
            bodies.get(0).owner.pos = Constraint.world(pinnedA, bodies.get(0).fixedLocal);
        if (pinnedB != null && !bodies.isEmpty()) {
            PhysicsBody last = bodies.get(bodies.size() - 1);
            last.owner.pos = Constraint.world(pinnedB, last.fixedLocal);
        }
        for (int it = 0; it < 4; it++)
            for (DistanceConstraint c : links) c.solve(dt);
        List<Vec2> pts = new ArrayList<>();
        for (PhysicsBody b : bodies) pts.add(b.owner.pos.copy());
        if (!pts.isEmpty()) owner.points = pts;
    }

    private static Vec2 samplePolyline(List<Vec2> pts, double f) {
        double total = 0;
        double[] segs = new double[pts.size() - 1];
        for (int i = 0; i < pts.size() - 1; i++) { segs[i] = pts.get(i).dist(pts.get(i + 1)); total += segs[i]; }
        double target = total * f, acc = 0;
        for (int i = 0; i < segs.length; i++) {
            if (acc + segs[i] >= target || i == segs.length - 1) {
                double t = segs[i] < 1e-9 ? 0 : (target - acc) / segs[i];
                Vec2 a = pts.get(i), b = pts.get(i + 1);
                return new Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
            }
            acc += segs[i];
        }
        return pts.get(pts.size() - 1).copy();
    }
}
