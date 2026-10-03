package com.materiallab.materials;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Creates user alloys from base metals with percentage composition
 * (spec section 10). Properties are mixed: density by harmonic rule,
 * others weighted linearly. Results are engineering approximations.
 */
public final class AlloyBuilder {

    public static final class Ingredient {
        public final String materialId; public final double percent;
        public Ingredient(String id, double p) { materialId = id; percent = p; }
    }

    /** @throws IllegalArgumentException on bad composition */
    public Material createAlloy(String id, String name, java.util.List<Ingredient> ingredients, Map<String, Double> impurities) {
        if (ingredients == null || ingredients.isEmpty())
            throw new IllegalArgumentException("Alloy needs at least one ingredient");
        double total = 0;
        for (Ingredient i : ingredients) {
            if (i.percent <= 0) throw new IllegalArgumentException("Percent must be positive");
            if (!MaterialsRegistry.get().known(i.materialId))
                throw new IllegalArgumentException("Unknown material: " + i.materialId);
            total += i.percent;
        }
        if (Math.abs(total - 100.0) > 0.01)
            throw new IllegalArgumentException("Composition must sum to 100%, got " + total);

        Material alloy = new Material(id, name);
        alloy.description = "Сплав, рассчитан как инженерное приближение по составу.";
        alloy.custom = true;
        double wSum = 0;
        double densInv = 0;
        Map<String, Integer> weldClasses = new LinkedHashMap<>();
        for (Ingredient ing : ingredients) {
            Material m = MaterialsRegistry.get().get(ing.materialId);
            double w = ing.percent / 100.0;
            wSum += w;
            densInv += w / m.density;
            alloy.conductivity += m.conductivity * w;
            alloy.thermalConductivity += m.thermalConductivity * w;
            alloy.meltingPoint = Math.min(alloy.meltingPoint == 0 ? m.meltingPoint : alloy.meltingPoint, m.meltingPoint);
            alloy.hardness += m.hardness * w;
            alloy.strength += m.strength * w;
            alloy.elasticity += m.elasticity * w;
            alloy.brittleness += m.brittleness * w;
            alloy.friction += m.friction * w;
            alloy.corrosion += m.corrosion * w;
            alloy.cost += m.cost * w;
            // mix color
            int r = (int) (((m.colorRgb >> 16) & 0xFF) * w) + (alloy.colorRgb != 0 ? (int)(((alloy.colorRgb >> 16) & 0xFF)) : 0);
            alloy.colorRgb = blendColor(alloy.colorRgb, m.colorRgb, w);
            alloy.magnetic |= m.magnetic && w > 0.5;
            weldClasses.merge(m.weldClass, 1, Integer::sum);
        }
        alloy.density = 1.0 / densInv;
        alloy.failureTemp = alloy.meltingPoint;
        // dominant weld class decides weldability
        String dominant = weldClasses.keySet().iterator().next();
        int max = 0;
        for (Map.Entry<String, Integer> e : weldClasses.entrySet()) if (e.getValue() > max) { max = e.getValue(); dominant = e.getKey(); }
        alloy.weldClass = dominant.startsWith("metal") ? dominant : "none";
        alloy.weldable = alloy.weldClass.startsWith("metal");
        alloy.allowedConnections.put("WELD", alloy.weldable);
        // impurity penalty (sim rule): each % of impurity lowers strength/hardness slightly
        if (impurities != null) {
            double imp = impurities.values().stream().mapToDouble(Double::doubleValue).sum();
            alloy.strength *= Math.max(0.2, 1.0 - imp / 100.0);
            alloy.hardness *= Math.max(0.2, 1.0 - imp / 100.0);
            alloy.conductivity *= Math.max(0.1, 1.0 - imp / 50.0);
        }
        return alloy;
    }

    private static int blendColor(int a, int b, double w) {
        if (a == 0) return b;
        int r = (int) ((((b >> 16) & 0xFF) * w) + (((a >> 16) & 0xFF) * (1 - w)));
        int g = (int) ((((b >> 8) & 0xFF) * w) + (((a >> 8) & 0xFF) * (1 - w)));
        int bl = (int) (((b & 0xFF) * w) + ((a & 0xFF) * (1 - w)));
        return (r << 16) | (g << 8) | bl;
    }
}
