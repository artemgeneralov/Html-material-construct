package com.materiallab.electronics;

import java.util.HashMap;
import java.util.Map;

/** Union-find over (objId, portId) endpoints -> electrical node ids. */
public final class NodeMap {
    private final Map<String, String> parent = new HashMap<>();

    private static String key(String obj, String port) { return obj + "\u0000" + port; }

    public void link(String aObj, String aPort, String bObj, String bPort) {
        String ka = key(aObj, aPort), kb = key(bObj, bPort);
        union(find(ka), find(kb));
    }

    public String find(String k) {
        return parent.computeIfAbsent(k, x -> x);
    }

    private void union(String ra, String rb) {
        if (!ra.equals(rb)) parent.put(ra, rb);
    }

    /** Electrical node id for an endpoint (ground endpoints share the ground node). */
    public int nodeId(String objId, String portId) {
        String root = find(key(objId, portId));
        return Math.abs(root.hashCode()) % 1_000_000;
    }
}
