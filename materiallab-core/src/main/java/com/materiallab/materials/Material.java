package com.materiallab.materials;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Material record. Densities/conductivity/melting points are reference values
 * (see MaterialsRegistry javadoc for sources). Hardness/strength/cost/friction
 * are SIMULATION APPROXIMATIONS unless noted — the UI must label them as such.
 */
public final class Material {
    public String id, nameRu, description;
    public double density;            // kg/m^3 (reference)
    public double conductivity;       // S/m at 20C (reference for pure metals)
    public double thermalConductivity;// W/(m*K) (approximate)
    public double meltingPoint;       // Celsius (reference)
    public double failureTemp;        // Celsius (sim parameter)
    public double hardness;           // sim scale 0..100
    public double strength;           // sim scale MPa-equivalent (approximate)
    public double elasticity;         // sim scale 0..1
    public double brittleness;        // sim scale 0..1
    public double friction;           // Coulomb coefficient (approximate)
    public double corrosion;          // sim scale 0..1 (higher = resistant)
    public boolean magnetic;          // ferromagnetic at room temperature
    public double cost;               // relative units (approximate market ratios)
    public int colorRgb;              // display color
    public String texture = "plain";
    /** weld compatibility classes: "metal-ferrous","metal-nonferrous","thermoplastic", "wood", "ceramic", etc. */
    public String weldClass = "none";
    public boolean weldable = false, glueable = true, boltable = true;
    public Map<String, Boolean> allowedConnections = new LinkedHashMap<>();
    public boolean custom = false;    // user-defined material/alloy

    public Material(String id, String nameRu) {
        this.id = id; this.nameRu = nameRu;
        allowedConnections.put("ELECTRICAL", true);
        allowedConnections.put("MECHANICAL", true);
        allowedConnections.put("WELD", false);
    }

    /** Weld compatibility rule (spec section 9). */
    public boolean canWeldWith(Material other) {
        if (!weldable || !other.weldable) return false;
        if (weldClass.equals(other.weldClass)) return true;
        // dissimilar metals: allowed but weaker (handled by caller via strength factor)
        return weldClass.startsWith("metal") && other.weldClass.startsWith("metal");
    }

    public boolean isDissimilarMetalPair(Material other) {
        return canWeldWith(other) && !weldClass.equals(other.weldClass);
    }
}
