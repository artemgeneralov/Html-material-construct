package com.materiallab.physics;

import com.materiallab.model.Vec2;

/** Mechanical constraint solved iteratively (distance/revolute/weld). */
public abstract class Constraint {
    public final PhysicsBody a, b;
    public final Vec2 anchorA, anchorB; // local anchors
    public double breakForce = Double.MAX_VALUE; // N-equivalent; exceeded -> removed
    public boolean broken;
    public String connectionId;

    protected Constraint(PhysicsBody a, PhysicsBody b, Vec2 la, Vec2 lb) {
        this.a = a; this.b = b; this.anchorA = la; this.anchorB = lb;
    }
    public abstract void solve(double dt);

    static Vec2 world(PhysicsBody body, Vec2 local) {
        double c = Math.cos(body.owner.rotation), s = Math.sin(body.owner.rotation);
        return new Vec2(body.owner.pos.x + local.x * c - local.y * s,
                        body.owner.pos.y + local.x * s + local.y * c);
    }
}
