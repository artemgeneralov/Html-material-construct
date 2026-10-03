package com.materiallab.model;

/** Immutable-ish 2D vector used across model, physics and electronics. */
public final class Vec2 {
    public double x, y;
    public Vec2() {}
    public Vec2(double x, double y) { this.x = x; this.y = y; }
    public Vec2 copy() { return new Vec2(x, y); }
    public Vec2 add(Vec2 o) { return new Vec2(x + o.x, y + o.y); }
    public Vec2 sub(Vec2 o) { return new Vec2(x - o.x, y - o.y); }
    public Vec2 mul(double s) { return new Vec2(x * s, y * s); }
    public double dot(Vec2 o) { return x * o.x + y * o.y; }
    public double len() { return Math.hypot(x, y); }
    public double dist(Vec2 o) { return Math.hypot(x - o.x, y - o.y); }
    public void set(double x, double y) { this.x = x; this.y = y; }
    @Override public String toString() { return String.format("(%.2f, %.2f)", x, y); }
}
