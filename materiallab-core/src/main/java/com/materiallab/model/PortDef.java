package com.materiallab.model;

/** Static definition of a component port (from the catalog). */
public final class PortDef {
    public String id;
    public String name;
    public ConnectionType type = ConnectionType.ELECTRICAL;
    /** true for inputs / outputs of logic & info ports; pins use BOTH (null flags). */
    public Boolean input;
    public Boolean output;
    public double localX, localY; // position in local component coords (meters)

    public PortDef() {}
    public PortDef(String id, String name, ConnectionType type, Double lx, Double ly) {
        this.id = id; this.name = name; this.type = type;
        if (lx != null) this.localX = lx;
        if (ly != null) this.localY = ly;
    }
}
