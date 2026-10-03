package com.materiallab.model;

/** Global simulation parameters, persisted with the project. */
public final class SimulationSettings {
    public double gravity = 9.81;          // m/s^2
    public double timeStep = 1.0 / 60.0;   // s per tick
    public double airDrag = 0.02;          // 1/s, simplified linear drag
    public double ambientTemp = 20;        // Celsius
    public double restitution = 0.3;       // default bounce factor (sim parameter)
    public double friction = 0.4;          // default Coulomb friction (sim parameter)
}
