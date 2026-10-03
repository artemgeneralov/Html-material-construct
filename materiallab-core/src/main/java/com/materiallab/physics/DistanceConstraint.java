package com.materiallab.physics;

import com.materiallab.model.Vec2;

/** Maintains distance between two world anchors (rope segment, spring, stiff joint). */
public final class DistanceConstraint extends Constraint {
    public double restLength;
    public double stiffness = 1.0;     // 0..1 position correction factor
    public boolean rigid;              // rigid: enforce both ways; rope: only pull

    public DistanceConstraint(PhysicsBody a, PhysicsBody b, Vec2 la, Vec2 lb, double rest, boolean rigid) {
        super(a, b, la, lb);
        this.restLength = rest; this.rigid = rigid;
    }

    @Override public void solve(double dt) {
        if (broken) return;
        Vec2 pa = world(a, anchorA), pb = world(b, anchorB);
        Vec2 d = pb.sub(pa);
        double dist = d.len();
        if (dist < 1e-9) return;
        double diff = dist - restLength;
        if (!rigid && diff < 0) return;                 // rope goes slack
        double load = Math.abs(diff) * 1e4;             // sim force proxy for break test
        if (load > breakForce) { broken = true; return; }
        Vec2 n = d.mul(1.0 / dist);
        double wa = a.fixed ? 0 : 1.0 / Math.max(a.mass, 1e-9);
        double wb = b.fixed ? 0 : 1.0 / Math.max(b.mass, 1e-9);
        double wsum = wa + wb;
        if (wsum == 0) return;
        double corr = diff * stiffness / wsum;
        if (!a.fixed) a.owner.pos = a.owner.pos.add(n.mul(corr * wa));
        if (!b.fixed) b.owner.pos = b.owner.pos.sub(n.mul(corr * wb));
        Vec2 rel = b.velocity.sub(a.velocity);
        double vn = rel.dot(n);
        Vec2 imp = n.mul(vn / wsum * 0.5);
        if (!a.fixed) a.velocity = a.velocity.add(imp.mul(wa));
        if (!b.fixed) b.velocity = b.velocity.sub(imp.mul(wb));
    }
}
