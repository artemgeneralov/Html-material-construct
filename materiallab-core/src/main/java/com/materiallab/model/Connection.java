package com.materiallab.model;

/** Weld / joint record between two port endpoints (spec sections 7-9). */
public final class Connection {
    public String id;
    public String aObj, aPort;
    public String bObj, bPort;
    public ConnectionType type = ConnectionType.ELECTRICAL;
    public boolean welded = true;       // false => mere contact, no electrical link (spec 7)
    public String weldKind = "metal";   // metal | temporary | mechanical | glue
    public double strength = 100;       // N-equivalent load limit (sim parameter)
    public double meltTemp = 1450;      // Celsius of the seam (steel weld default)
    public double resistance = 1e-3;    // contact resistance, Ohm

    public Connection() {}
    public Connection(String id, String aObj, String aPort, String bObj, String bPort, ConnectionType type) {
        this.id = id; this.aObj = aObj; this.aPort = aPort;
        this.bObj = bObj; this.bPort = bPort; this.type = type;
    }
    /** Does this connection touch the given object/port endpoint? */
    public boolean involves(String objId, String portId) {
        return (aObj.equals(objId) && aPort.equals(portId))
            || (bObj.equals(objId) && bPort.equals(portId));
    }
    public String otherObj(String objId) {
        return aObj.equals(objId) ? bObj : aObj;
    }
}
