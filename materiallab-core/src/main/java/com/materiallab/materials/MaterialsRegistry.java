package com.materiallab.materials;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Built-in material library (spec section 10).
 *
 * REFERENCE DATA SOURCES for density / electrical conductivity / melting point:
 *   - Wikipedia articles on each element/alloy (checked against engineering tables);
 *   - ASM Handbook values; MatWeb open summaries.
 * Values rounded to 2-3 significant digits. Where a value is a gameplay/sim
 * approximation (hardness scale, cost, friction, strength) it is marked as such
 * and MUST be labeled "инженерное приближение" in the UI.
 */
public final class MaterialsRegistry {
    private static final MaterialsRegistry INSTANCE = new MaterialsRegistry();
    private final Map<String, Material> byId = new LinkedHashMap<>();

    public static MaterialsRegistry get() { return INSTANCE; }

    private MaterialsRegistry() {
        // id, nameRu, density kg/m3, cond S/m, thermal W/mK, melt C, magnetic, color
        addMetal("copper", "Медь", 8960, 5.96e7, 401, 1085, false, 0xB87333, "metal-nonferrous");
        addMetal("aluminium", "Алюминий", 2700, 3.5e7, 237, 660, false, 0xA8B0BA, "metal-nonferrous");
        addMetal("iron", "Железо", 7874, 1.0e7, 80, 1538, true, 0x6E7177, "metal-ferrous");
        addMetal("steel", "Сталь", 7850, 1.0e7, 50, 1425, true, 0x7F858C, "metal-ferrous");
        addMetal("stainless", "Нержавеющая сталь", 8000, 1.4e6, 16, 1450, false, 0xC0C7CE, "metal-ferrous");
        addMetal("brass", "Латунь", 8500, 1.5e7, 110, 930, false, 0xC9A227, "metal-nonferrous");
        addMetal("bronze", "Бронза", 8800, 8.0e6, 60, 913, false, 0xCD8032, "metal-nonferrous");
        addMetal("titanium", "Титан", 4500, 2.2e6, 22, 1668, false, 0x878683, "metal-nonferrous");
        addMetal("nickel", "Никель", 8908, 1.4e7, 91, 1455, true, 0x9A9AA0, "metal-ferrous");
        addMetal("lead", "Свинец", 11340, 4.8e6, 35, 327, false, 0x5B6068, "metal-nonferrous");
        addMetal("gold", "Золото", 19300, 4.5e7, 318, 1064, false, 0xFFD700, "metal-nonferrous");
        addMetal("silver", "Серебро", 10490, 6.30e7, 420, 962, false, 0xDDDDDD, "metal-nonferrous");
        addOther("graphite", "Графит", 2200, 1.0e5, 120, 3600, 0x3A3A3A);
        addOther("silicon", "Кремний", 2329, 1.0e-3, 150, 1414, 0x696A72);
        addPolymer("plastic", "Пластик", 1150, 0xEFEFEF, "thermoplastic");
        addPolymer("rubber", "Резина", 1100, 0x222222, "none");
        addPolymer("composite", "Композит", 1600, 0x9AA5A0, "thermoplastic");
        addPolymer("carbonfiber", "Углеродное волокно", 1600, 0x1E1E1E, "none");
        addOther("wood", "Дерево", 600, 1e-11, 0.15, 300, 0x9C6B30);
        addOther("glass", "Стекло", 2500, 1e-12, 1.0, 1400, 0xBFE3EA);
        addOther("ceramic", "Керамика", 2500, 1e-13, 20, 1600, 0xD9CFA6);
        addOther("concrete", "Бетон", 2400, 1e-9, 1.7, 1500, 0xA6A69C);
        registerCustomDefaults();
    }

    private void addMetal(String id, String ru, double dens, double cond, double th,
                          double melt, boolean mag, int color, String weldClass) {
        Material m = new Material(id, ru);
        m.description = "Металл. Плотность/проводимость/Т_плавления — справочные значения.";
        m.density = dens; m.conductivity = cond; m.thermalConductivity = th; m.meltingPoint = melt;
        m.failureTemp = melt; m.magnetic = mag; m.colorRgb = color;
        m.weldable = true; m.weldClass = weldClass;
        m.hardness = 60; m.strength = 250; m.elasticity = 0.6; m.brittleness = 0.15;
        m.friction = 0.3; m.corrosion = 0.5; m.cost = 1.0;
        m.allowedConnections.put("WELD", true);
        byId.put(id, m);
    }

    private void addOther(String id, String ru, double dens, double cond, double th,
                          double melt, int color) {
        Material m = new Material(id, ru);
        m.description = "Плотность и температура — справочные (округлённые) значения.";
        m.density = dens; m.conductivity = cond; m.thermalConductivity = th; m.meltingPoint = melt;
        m.failureTemp = melt; m.colorRgb = color;
        m.hardness = 40; m.strength = 40; m.elasticity = 0.3; m.brittleness = 0.6;
        m.friction = 0.5; m.corrosion = 0.8; m.cost = 0.5;
        byId.put(id, m);
    }

    private void addPolymer(String id, String ru, double dens, int color, String weldClass) {
        Material m = new Material(id, ru);
        m.description = "Полимер. Свойства — инженерное приближение.";
        m.density = dens; m.conductivity = 1e-14; m.thermalConductivity = 0.25;
        m.meltingPoint = id.equals("rubber") ? 180 : 240; m.failureTemp = 300;
        m.colorRgb = color; m.weldClass = weldClass;
        m.weldable = !"none".equals(weldClass); // thermoplastics can be welded
        m.hardness = 20; m.strength = 35; m.elasticity = id.equals("rubber") ? 0.9 : 0.4;
        m.brittleness = id.equals("rubber") ? 0.05 : 0.3; m.friction = id.equals("rubber") ? 0.9 : 0.4;
        m.corrosion = 0.95; m.cost = 0.3;
        if (m.weldable) m.allowedConnections.put("WELD", true);
        byId.put(id, m);
    }

    private void registerCustomDefaults() {
        // refinements over generic values (still sim scales where noted)
        Material steel = byId.get("steel"); steel.hardness = 70; steel.strength = 400; steel.cost = 0.8;
        Material ss = byId.get("stainless"); ss.hardness = 65; ss.strength = 500; ss.corrosion = 0.95; ss.cost = 3;
        Material ti = byId.get("titanium"); ti.hardness = 60; ti.strength = 900; ti.corrosion = 0.95; ti.cost = 25;
        Material gold = byId.get("gold"); gold.cost = 1000; gold.hardness = 25; gold.corrosion = 1.0;
        Material silver = byId.get("silver"); silver.cost = 80; silver.hardness = 25;
        Material cu = byId.get("copper"); cu.cost = 4; cu.hardness = 30; cu.corrosion = 0.6;
        Material alu = byId.get("aluminium"); alu.cost = 1.5; alu.hardness = 35; alu.corrosion = 0.7;
        Material wood = byId.get("wood"); wood.weldable = false; wood.boltable = true; wood.glueable = true;
        Material glass = byId.get("glass"); glass.brittleness = 0.95; glass.weldable = false;
        Material cer = byId.get("ceramic"); cer.brittleness = 0.9; cer.weldable = false; cer.glueable = true;
        Material lead = byId.get("lead"); lead.hardness = 10; lead.friction = 0.6;
        Material cf = byId.get("carbonfiber"); cf.strength = 600; cf.hardness = 55; cf.cost = 20;
        Material graphite = byId.get("graphite"); graphite.brittleness = 0.8;
        Material silicon = byId.get("silicon"); silicon.brittleness = 0.85; silicon.hardness = 65;
    }

    public Material get(String id) { return byId.getOrDefault(id, byId.get("plastic")); }
    public boolean known(String id) { return byId.containsKey(id); }
    public Map<String, Material> all() { return byId; }

    /** Register/add a user material or alloy (spec section 10 alloys). */
    public void register(Material m) { m.custom = true; byId.put(m.id, m); }
    public void remove(String id) { if (byId.get(id) != null && byId.get(id).custom) byId.remove(id); }
}
