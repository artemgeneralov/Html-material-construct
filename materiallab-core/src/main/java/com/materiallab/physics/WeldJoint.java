package com.materiallab.physics;

import com.materiallab.model.Vec2;

/** Weld: locks relative position AND orientation until force/temp limits exceeded. */
public final class WeldJoint extends Constraint {
    public double meltTemp = 1450;      // Celsius - seam fails above this
    private final double initialRelAngle;

    public WeldJoint(PhysicsBody a, PhysicsBody b, Vec2 la, Vec2 lb) {
        super(a, b, la, lb);
        this.initialRelAngle = b.owner.rotation - a.owner.rotation;
    }

    @Override public void solve(double dt) {
        if (broken) return;
        if (Math.max(a.temperature, b.temperature) > meltTemp) { broken = true; return; }
        Vec2 pa = world(a, anchorA), pb = world(b, anchorB);
        Vec2 d = pb.sub(pa);
        double dist = d.len();
        if (dist * 1e4 > breakForce) { broken = true; return; }
        double wa = a.fixed ? 0 : 1.0 / Math.max(a.mass, 1e-9);
        double wb = b.fixed ? 0 : 1.0 / Math.max(b.mass, 1e-9);
        double wsum = wa + wb;
        if (wsum > 0 && dist > 1e-12) {
            Vec2 corr = d.mul(1.0 / wsum);
            if (!a.fixed) a.owner.pos = a.owner.pos.add(corr.mul(wa));
            if (!b.fixed) b.owner.pos = b.owner.pos.sub(corr.mul(wb));
        }
        double rel = b.owner.rotation - a.owner.rotation - initialRelAngle;
        double iwa = a.fixed ? 0 : 1.0 / Math.max(a.inertia, 1e-9);
        double iwb = b.fixed ? 0 : 1.0 / Math.max(b.inertia, 1e-9);
        double isum = iwa + iwb;
        if (isum > 0) {
            if (!a.fixed) a.owner.rotation += rel * iwa / isum;
            if (!b.fixed) b.owner.rotation -= rel * iwb / isum;
        }
    }
}
