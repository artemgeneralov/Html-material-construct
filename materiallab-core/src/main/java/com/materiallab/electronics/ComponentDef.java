package com.materiallab.electronics;

import com.materiallab.model.PortDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Catalog entry describing a component type (spec section 6). */
public final class ComponentDef {
    public String id, nameRu, description, category;
    public List<PortDef> ports = new ArrayList<>();
    public Map<String, Double> defaultParams = new LinkedHashMap<>();
    public double width = 0.12, height = 0.08;   // meters
    public double massKg = 0.01;                 // kg (approximate for sim)
    public String materialId = "plastic";
    public boolean hasGround = false;
    public boolean fixedAnchor = false;  // anchor component is always fixed
    // battery-style: second pin is ground node

    public PortDef port(String pid) {
        for (PortDef p : ports) if (p.id.equals(pid)) return p;
        return null;
    }
}
