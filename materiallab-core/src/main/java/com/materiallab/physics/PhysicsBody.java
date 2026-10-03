package com.materiallab.physics;

import com.materiallab.model.ProjectObject;
import com.materiallab.model.Vec2;

/** Runtime dynamic state of one object. Kept OUTSIDE the data model (spec 5). */
public final class PhysicsBody {
    public final ProjectObject owner;
    public Vec2 velocity = new Vec2();
    public double angularVelocity;
    public double mass;
    public double inertia;
    public boolean fixed;
    public double temperature = 20;   // Celsius, simplified lumped model

    public PhysicsBody(ProjectObject owner, double mass, double inertia, boolean fixed) {
        this.owner = owner; this.mass = mass; this.inertia = inertia; this.fixed = fixed;
    }
}
