package com.materiallab.electronics;

import com.materiallab.model.ConnectionType;
import com.materiallab.model.PortDef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in component library (spec section 6). All electrical parameter values
 * are simulation approximations unless stated otherwise.
 */
public final class ComponentsCatalog {
    private static final ComponentsCatalog INSTANCE = new ComponentsCatalog();
    private final Map<String, ComponentDef> defs = new LinkedHashMap<>();

    public static ComponentsCatalog get() { return INSTANCE; }

    private ComponentsCatalog() {
        // ---- power sources ----
        src("battery", "Батарейка", "Постоянный источник напряжения (DC)", "power", 1.5, 0.02);
        src("accumulator", "Аккумулятор", "Перезаряжаемый источник, ёмкость в мАч", "power", 9.0, 0.05);
        src("powersupply", "Источник питания", "Регулируемый лабораторный источник", "power", 12.0, 0.1);
        // ---- consumers ----
        twoPin("lamp", "Лампочка", "Лампа накаливания, светится при токе", "light", 30.0, 0.015);
        twoPin("led", "Светодиод", "Светит при прямом включении, порог ~2В", "light", 20.0, 0.008);
        twoPin("resistor", "Резистор", "Сопротивление, Ом", "passive", 100.0, 0.005);
        twoPin("capacitor", "Конденсатор", "Ёмкость, мкФ (упрощённо: RC-заряд)", "passive", 1e6, 0.006);
        twoPin("inductor", "Катушка индуктивности", "Индуктивность, мГн (в DC — малое R)", "passive", 0.5, 0.008);
        twoPin("diode", "Диод", "Пропускает ток только в одном направлении", "passive", 0.7, 0.004);
        twoPin("button", "Кнопка", "Замыкает цепь, пока удерживается (state.press)", "switch", 0.01, 0.006);
        twoPin("switch", "Переключатель", "Двухпозиционный (param on=1/0)", "switch", 0.01, 0.006);
        twoPin("fuse", "Предохранитель", "Рвётся при превышении тока (maxCurrent)", "safety", 0.05, 0.004);
        twoPin("motor", "Электродвигатель", "Крутящий момент пропорционален току", "machine", 8.0, 0.05);
        twoPin("generator", "Генератор", "Вырабатывает ЭДС при вращении (state.rpm)", "machine", 0.0, 0.05);
        twoPin("servo", "Сервопривод", "Поворачвает к targetAngle по сигналу", "machine", 5.0, 0.03);
        twoPin("rheostat", "Реостат (резистивный датчик)", "Сопротивление меняется параметром ratio", "sensor", 100.0, 0.01);
        twoPin("speaker", "Динамик", "Преобразует сигнал в звук (упрощённо)", "audio", 8.0, 0.02);
        twoPin("microphone", "Микрофон", "Слабый источник сигнала при звуке", "audio", 2200.0, 0.008);
        // ---- sensors (info ports) ----
        sensor("lightsensor", "Датчик света (LDR)", "Сопротивление зависит от освещённости");
        sensor("tempsensor", "Датчик температуры (термистор)", "Сопротивление зависит от температуры");
        sensor("distsensor", "Датчик расстояния", "Информационный выход distance (см)");
        sensor("photosensor", "Световой сенсор (цифровой)", "Цифровой выход: свет/тьма");
        // ---- logic (phase B, declared now for catalog completeness) ----
        gate("and", "Логический элемент AND", 2);
        gate("or", "Логический элемент OR", 2);
        gate("not", "Логический элемент NOT", 1);
        gate("xor", "Логический элемент XOR", 2);
        timer("timer", "Таймер", "Период мс, импульсный выход");
        counter("counter", "Счётчик", "Считает фронт на входе, выход value");
        mcu("microcontroller", "Микроконтроллер", "Цифровые/аналоговые порты, события");
        chip("chip", "Микросхема (программируемая)", "Пользовательская логика в params");
        display("display", "Дисплей", "Показывает значение state.value");
        ioPort("din", "Цифровой вход", ConnectionType.LOGICAL, true);
        ioPort("dout", "Цифровой выход", ConnectionType.LOGICAL, false);
        ioPort("ain", "Аналоговый вход", ConnectionType.INFORMATION, true);
        ioPort("aout", "Аналоговый выход", ConnectionType.INFORMATION, false);
        relay();
        mechanicalBlocks();
    }

    private void src(String id, String ru, String desc, String category, double v, double mass) {
        ComponentDef d = base(id, ru, desc, category);
        d.ports.add(new PortDef("v+", "Плюс", ConnectionType.ELECTRICAL, -0.04, 0.0));
        d.ports.add(new PortDef("gnd", "Минус", ConnectionType.ELECTRICAL, 0.04, 0.0));
        d.defaultParams.put("voltage", v);
        d.defaultParams.put("internalResistance", 0.15);
        d.hasGround = true; d.massKg = mass; d.materialId = "steel";
    }

    private void twoPin(String id, String ru, String desc, String category, double r, double mass) {
        ComponentDef d = base(id, ru, desc, category);
        d.ports.add(new PortDef("p1", "Вывод 1", ConnectionType.ELECTRICAL, -0.05, 0.0));
        d.ports.add(new PortDef("p2", "Вывод 2", ConnectionType.ELECTRICAL, 0.05, 0.0));
        if (!id.equals("generator")) d.defaultParams.put("resistance", r);
        d.massKg = mass; d.materialId = metalFor(id);
    }

    private void sensor(String id, String ru, String desc) {
        ComponentDef d = base(id, ru, desc, "sensor");
        d.ports.add(new PortDef("p1", "Питание", ConnectionType.ELECTRICAL, -0.05, 0.0));
        d.ports.add(new PortDef("p2", "Минус", ConnectionType.ELECTRICAL, 0.05, 0.0));
        d.ports.add(new PortDef("sig", "Сигнал", ConnectionType.INFORMATION, 0.0, -0.04));
        d.defaultParams.put("resistance", 1000.0);
        d.massKg = 0.004; d.materialId = "silicon";
    }

    private void gate(String id, String ru, int inputs) {
        ComponentDef d = base(id, ru, "Логический элемент, уровни 0/5 В", "logic");
        for (int i = 0; i < inputs; i++)
            d.ports.add(new PortDef("in" + (i + 1), "Вход " + (i + 1), ConnectionType.LOGICAL, -0.05, -0.02 + i * 0.02));
        d.ports.add(new PortDef("out", "Выход", ConnectionType.LOGICAL, 0.05, 0.0));
        d.massKg = 0.002; d.materialId = "silicon";
    }

    private void timer(String id, String ru, String desc) {
        ComponentDef d = base(id, ru, desc, "logic");
        d.ports.add(new PortDef("in", "Разрешение", ConnectionType.LOGICAL, -0.05, 0.0));
        d.ports.add(new PortDef("out", "Импульс", ConnectionType.LOGICAL, 0.05, 0.0));
        d.defaultParams.put("periodMs", 1000.0);
        d.massKg = 0.003; d.materialId = "plastic";
    }

    private void counter(String id, String ru, String desc) {
        ComponentDef d = base(id, ru, desc, "logic");
        d.ports.add(new PortDef("in", "Счёт", ConnectionType.LOGICAL, -0.05, 0.0));
        d.ports.add(new PortDef("out", "Значение", ConnectionType.INFORMATION, 0.05, 0.0));
        d.defaultParams.put("max", 255.0);
        d.massKg = 0.003; d.materialId = "silicon";
    }

    private void mcu(String id, String ru, String desc) {
        ComponentDef d = base(id, ru, desc, "programmable");
        d.width = 0.2; d.height = 0.14;
        d.ports.add(new PortDef("vin", "Питание", ConnectionType.ELECTRICAL, -0.08, -0.05));
        d.ports.add(new PortDef("gnd", "Земля", ConnectionType.ELECTRICAL, -0.08, 0.05));
        d.ports.add(new PortDef("d1", "D1", ConnectionType.LOGICAL, 0.08, -0.05));
        d.ports.add(new PortDef("d2", "D2", ConnectionType.LOGICAL, 0.08, -0.02));
        d.ports.add(new PortDef("a1", "A1", ConnectionType.INFORMATION, 0.08, 0.02));
        d.ports.add(new PortDef("a2", "A2", ConnectionType.INFORMATION, 0.08, 0.05));
        d.defaultParams.put("voltage", 5.0);
        d.hasGround = true;
        d.massKg = 0.005; d.materialId = "silicon";
    }

    private void chip(String id, String ru, String desc) {
        ComponentDef d = base(id, ru, desc, "programmable");
        d.ports.add(new PortDef("in", "Вход", ConnectionType.LOGICAL, -0.05, 0.0));
        d.ports.add(new PortDef("out", "Выход", ConnectionType.LOGICAL, 0.05, 0.0));
        d.massKg = 0.002; d.materialId = "silicon";
    }

    private void display(String id, String ru, String desc) {
        ComponentDef d = base(id, ru, desc, "output");
        d.width = 0.16; d.height = 0.1;
        d.ports.add(new PortDef("vin", "Питание", ConnectionType.ELECTRICAL, -0.06, -0.03));
        d.ports.add(new PortDef("gnd", "Земля", ConnectionType.ELECTRICAL, -0.06, 0.03));
        d.ports.add(new PortDef("sig", "Данные", ConnectionType.INFORMATION, 0.06, 0.0));
        d.defaultParams.put("voltage", 5.0);
        d.hasGround = true;
        d.massKg = 0.02; d.materialId = "glass";
    }

    private void ioPort(String id, String ru, ConnectionType type, boolean input) {
        ComponentDef d = base(id, ru, (input ? "Вход" : "Выход") + " для модулей и схем", "io");
        d.width = 0.05; d.height = 0.05;
        if (input) d.ports.add(new PortDef("in", "Сигнал", type, 0.0, 0.0));
        else d.ports.add(new PortDef("out", "Сигнал", type, 0.0, 0.0));
        d.massKg = 0.001; d.materialId = "plastic";
    }

    private void relay() {
        ComponentDef d = base("relay", "Реле", "Катушка замыкает контакт при токе > порога", "switch");
        d.width = 0.14; d.height = 0.1;
        d.ports.add(new PortDef("c1", "Катушка 1", ConnectionType.ELECTRICAL, -0.05, -0.03));
        d.ports.add(new PortDef("c2", "Катушка 2", ConnectionType.ELECTRICAL, -0.05, 0.03));
        d.ports.add(new PortDef("n1", "Контакт 1", ConnectionType.ELECTRICAL, 0.05, -0.03));
        d.ports.add(new PortDef("n2", "Контакт 2", ConnectionType.ELECTRICAL, 0.05, 0.03));
        d.defaultParams.put("coilResistance", 80.0);
        d.defaultParams.put("pullInCurrent", 0.05);
        d.massKg = 0.02; d.materialId = "plastic";
    }

    private void mechanicalBlocks() {
        ComponentDef hinge = base("hinge", "Шарнир", "Механическое вращательное соединение", "mechanical");
        hinge.ports.add(new PortDef("a", "Звено A", ConnectionType.MECHANICAL, -0.03, 0.0));
        hinge.ports.add(new PortDef("b", "Звено B", ConnectionType.MECHANICAL, 0.03, 0.0));
        hinge.defaultParams.put("frictionTorque", 0.02);
        ComponentDef spring = base("spring", "Пружина", "Линейная пружина, жёсткость Н/м", "mechanical");
        spring.width = 0.15;
        spring.ports.add(new PortDef("a", "Конец A", ConnectionType.MECHANICAL, -0.075, 0.0));
        spring.ports.add(new PortDef("b", "Конец B", ConnectionType.MECHANICAL, 0.075, 0.0));
        spring.defaultParams.put("stiffness", 200.0);
        spring.defaultParams.put("restLength", 0.15);
        spring.materialId = "steel";
        ComponentDef stiff = base("stiffjoint", "Жёсткое соединение", "Сварное/болтовое жёсткое звено", "mechanical");
        stiff.ports.add(new PortDef("a", "Звено A", ConnectionType.MECHANICAL, -0.02, 0.0));
        stiff.ports.add(new PortDef("b", "Звено B", ConnectionType.MECHANICAL, 0.02, 0.0));
        stiff.materialId = "steel";
        ComponentDef anchor = base("anchor", "Крепление", "Крепление объекта к земле", "mechanical");
        anchor.ports.add(new PortDef("obj", "Объект", ConnectionType.MECHANICAL, 0.0, 0.0));
        anchor.fixedAnchor = true;
    }

    private static String categoryOf(String id) {
        switch (id) {
            case "lamp": case "led": return "light";
            case "motor": case "servo": return "machine";
            case "button": case "switch": return "switch";
            case "fuse": return "safety";
            case "speaker": case "microphone": return "audio";
            default: return "passive";
        }
    }
    private static String metalFor(String id) {
        switch (id) {
            case "resistor": case "rheostat": return "graphite";
            case "capacitor": case "inductor": return "aluminium";
            case "motor": case "generator": case "servo": return "steel";
            case "lamp": case "led": return "glass";
            default: return "copper";
        }
    }

    private ComponentDef base(String id, String ru, String desc, String cat) {
        ComponentDef d = new ComponentDef();
        d.id = id; d.nameRu = ru; d.description = desc; d.category = cat;
        defs.put(id, d);
        return d;
    }

    public ComponentDef get(String id) { return defs.get(id); }
    public List<ComponentDef> all() { return List.copyOf(defs.values()); }

    /** Register a user-defined component (spec section 11). */
    public void register(ComponentDef d) { defs.put(d.id, d); }
}
