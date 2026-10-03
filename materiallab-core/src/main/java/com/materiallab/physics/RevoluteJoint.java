package com.materiallab.physics;

import com.materiallab.model.Vec2;

/** Pin/hinge: keeps two anchors coincident (point-to-point). */
public final class RevoluteJoint extends Constraint {
    public RevoluteJoint(PhysicsBody a, PhysicsBody b, Vec2 la, Vec2 lb) { super(a, b, la, lb); }

    @Override public void solve(double dt) {
        if (broken) return;
        Vec2 pa = world(a, anchorA), pb = world(b, anchorB);
        Vec2 d = pb.sub(pa);
        double dist = d.len();
        if (dist * 1e4 > breakForce) { broken = true; return; }
        double wa = a.fixed ? 0 : 1.0 / Math.max(a.mass, 1e-9);
        double wb = b.fixed ? 0 : 1.0 / Math.max(b.mass, 1e-9);
        double wsum = wa + wb;
        if (wsum == 0 || dist < 1e-12) return;
        Vec2 corr = d.mul(1.0 / wsum);
        if (!a.fixed) a.owner.pos = a.owner.pos.add(corr.mul(wa));
        if (!b.fixed) b.owner.pos = b.owner.pos.sub(corr.mul(wb));
    }
}
