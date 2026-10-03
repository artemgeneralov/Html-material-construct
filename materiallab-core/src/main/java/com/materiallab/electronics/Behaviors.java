package com.materiallab.electronics;

import com.materiallab.model.ProjectObject;
import java.util.HashMap;
import java.util.Map;

/** Factory of per-type behaviour strategies. Phase A implements the core set;
 *  remaining catalog types fall back to a simple fixed-resistance behaviour. */
public final class Behaviors {

    public static Map<String, ComponentBehavior> createAll() {
        Map<String, ComponentBehavior> m = new HashMap<>();

        // generic passive: R from params (covers resistor, inductor, speaker, capacitor-in-Dc-steady...)
        ComponentBehavior passive = new ComponentBehavior() {
            @Override public void step(ProjectObject o, ElectricalSolver.Result e, Map<String, ProjectObject> idx, double t, double dt) {}
            @Override public double branchResistance(ProjectObject o, Map<String, ProjectObject> idx) {
                return o.param("resistance", 1000);
            }
        };
        for (String id : new String[]{"resistor","inductor","capacitor","speaker","microphone","powersupply"})
            m.put(id, passive);
        // sources also need branch resistance = internal resistance
        ComponentBehavior source = new ComponentBehavior() {
            @Override public void step(ProjectObject o, ElectricalSolver.Result e, Map<String, ProjectObject> idx, double t, double dt) {}
            @Override public double branchResistance(ProjectObject o, Map<String, ProjectObject> idx) {
                return o.param("internalResistance", 0.15);
            }
        };
        for (String id : new String[]{"battery","accumulator"}) m.put(id, source);

        m.put("lamp", new LampBehavior());
        m.put("led", new LedBehavior());
        m.put("diode", new DiodeBehavior());
        m.put("button", new ButtonBehavior());
        m.put("switch", new SwitchToggleBehavior());
        m.put("fuse", new FuseBehavior());
        m.put("motor", new MotorBehavior());
        m.put("rheostat", new RheostatBehavior());
        m.put("lightsensor", new LightSensorBehavior());
        m.put("tempsensor", new TempSensorBehavior());
        m.put("photosensor", new PhotoDigitalBehavior());
        m.put("distsensor", passive);

        // fallback for everything else in catalog: passive-like
        for (ComponentDef def : ComponentsCatalog.get().all())
            m.putIfAbsent(def.id, passive);
        return m;
    }

    // ---------- concrete behaviours ----------

    /** Incandescent lamp: lights when current passes; filament can burn out on overload. */
    public static final class LampBehavior implements ComponentBehavior {
        @Override public void step(ProjectObject o, ElectricalSolver.Result e, Map<String, ProjectObject> idx, double t, double dt) {
            double i = o.state.getOrDefault("current", 0.0);
            double maxI = o.param("maxCurrent", 1.0);
            o.state.put("brightness", Math.min(1.0, i / Math.max(maxI * 0.6, 1e-9)));
            if (i > maxI * 2) o.state.put("fault", 1.0);   // burnt out
        }
        @Override public double branchResistance(ProjectObject o, Map<String, ProjectObject> idx) {
            return o.param("resistance", 30);
        }
    }

    /** LED: conducts only above ~2 V forward drop (approximated by high R below threshold). */
    public static final class LedBehavior implements ComponentBehavior {
        @Override public void step(ProjectObject o, ElectricalSolver.Result e, Map<String, ProjectObject> idx, double t, double dt) {
            double u = o.state.getOrDefault("voltage", 0.0);
            double i = o.state.getOrDefault("current", 0.0);
            o.state.put("brightness", (u >= 1.8 && i > 1e-4) ? Math.min(1.0, i / 0.02) : 0.0);
            double maxI = o.param("maxCurrent", 0.03);
            if (i > maxI * 3) o.state.put("fault", 1.0);
        }
        @Override public double branchResistance(ProjectObject o, Map<String, ProjectObject> idx) {
            return o.param("resistance", 20);
        }
    }

    /** Diode: ideal switch — forward small R, reverse open. Direction p1->p2. */
    public static final class DiodeBehavior implements ComponentBehavior {
        @Override public void step(ProjectObject o, ElectricalSolver.Result e, Map<String, ProjectObject> idx, double t, double dt) {}
        @Override public double branchResistance(ProjectObject o, Map<String, ProjectObject> idx) {
            Double rev = o.state.get("reverseBias");
            if (rev != null && rev > 0.5) return -1;      // blocked
            return 0.7;                                    // approximated forward branch
        }
    }

    /** Push button: closed while state.press==1. */
    public static final class ButtonBehavior implements ComponentBehavior {
        @Override public void step(ProjectObject o, ElectricalSolver.Result e, Map<String, ProjectObject> idx, double t, double dt) {}
        @Override public double branchResistance(ProjectObject o, Map<String, ProjectObject> idx) {
            double press = o.state.getOrDefault("press", 0.0);
            return press > 0.5 ? o.param("resistance", 0.01) : -1;
        }
    }

    /** Toggle switch: param on = 1/0. */
    public static final class SwitchToggleBehavior implements ComponentBehavior {
        @Override public void step(ProjectObject o, ElectricalSolver.Result e, Map<String, ProjectObject> idx, double t, double dt) {
            o.state.put("closed", o.param("on", 0));
        }
        @Override public double branchResistance(ProjectObject o, Map<String, ProjectObject> idx) {
            return o.param("on", 0) > 0.5 ? o.param("resistance", 0.01) : -1;
        }
    }

    /** Fuse: breaks when current exceeds rating (spec section 6/7 overheat & break). */
    public static final class FuseBehavior implements ComponentBehavior {
        @Override public void step(ProjectObject o, ElectricalSolver.Result e, Map<String, ProjectObject> idx, double t, double dt) {
            double i = o.state.getOrDefault("current", 0.0);
            double maxI = o.param("maxCurrent", 1.0);
            if (i > maxI) { o.state.put("fault", 1.0); o.state.put("blown", 1.0); }
        }
        @Override public double branchResistance(ProjectObject o, Map<String, ProjectObject> idx) {
            Double blown = o.state.get("blown");
            if (blown != null && blown > 0.5) return -1;
            return o.param("resistance", 0.05);
        }
    }

    /** Motor: torque proportional to current; spins attached body (state.torque). */
    public static final class MotorBehavior implements ComponentBehavior {
        @Override public void step(ProjectObject o, ElectricalSolver.Result e, Map<String, ProjectObject> idx, double t, double dt) {
            double i = o.state.getOrDefault("current", 0.0);
            double k = o.param("torqueConstant", 0.05); // N*m/A, sim parameter
            o.state.put("torque", i * k);
            o.state.put("rpm", i * k * 1000);           // simplified no-load speed metric
            double maxI = o.param("maxCurrent", 2.0);
            if (i > maxI * 2.5) o.state.put("fault", 1.0);
        }
        @Override public double branchResistance(ProjectObject o, Map<String, ProjectObject> idx) {
            return o.param("resistance", 8);
        }
    }

    /** Rheostat: resistance = maxR * ratio (0..1). */
    public static final class RheostatBehavior implements ComponentBehavior {
        @Override public void step(ProjectObject o, ElectricalSolver.Result e, Map<String, ProjectObject> idx, double t, double dt) {}
        @Override public double branchResistance(ProjectObject o, Map<String, ProjectObject> idx) {
            double ratio = Math.max(0.0, Math.min(1.0, o.param("ratio", 0.5)));
            return Math.max(o.param("maxResistance", 100) * ratio, 1e-3);
        }
    }

    /** LDR light sensor: R = darkR / (1 + lux/10). Reads env.lux from itself or nearest lamp. */
    public static final class LightSensorBehavior implements ComponentBehavior {
        @Override public void step(ProjectObject o, ElectricalSolver.Result e, Map<String, ProjectObject> idx, double t, double dt) {
            double lux = o.param("lux", 0);
            for (ProjectObject p : idx.values()) {
                if ((p.typeId.equals("lamp") || p.typeId.equals("led")) && p.state.getOrDefault("brightness", 0.0) > 0.05) {
                    double d = p.pos.dist(o.pos);
                    lux = Math.max(lux, p.state.get("brightness") * 500 / Math.max(d * d, 0.05));
                }
            }
            o.state.put("lux", lux);
            o.state.put("sig", Math.min(1.0, lux / 500));
        }
        @Override public double branchResistance(ProjectObject o, Map<String, ProjectObject> idx) {
            double lux = o.state.getOrDefault("lux", o.param("lux", 0));
            return o.param("darkResistance", 1e6) / (1 + lux / 10);
        }
    }

    /** Thermistor: R decreases with temperature (NTC, simplified beta model). */
    public static final class TempSensorBehavior implements ComponentBehavior {
        @Override public void step(ProjectObject o, ElectricalSolver.Result e, Map<String, ProjectObject> idx, double t, double dt) {
            double temp = o.state.getOrDefault("temperature", o.param("ambientTemp", 20));
            o.state.put("sig", Math.max(0, Math.min(1, (temp - 20) / 100)));
        }
        @Override public double branchResistance(ProjectObject o, Map<String, ProjectObject> idx) {
            double temp = o.state.getOrDefault("temperature", o.param("ambientTemp", 20));
            double r0 = o.param("resistance", 10000);
            return r0 * Math.exp(-0.02 * (temp - 20));   // B-constant approximation
        }
    }

    /** Digital photosensor: outputs 0/1 logic level. */
    public static final class PhotoDigitalBehavior implements ComponentBehavior {
        @Override public void step(ProjectObject o, ElectricalSolver.Result e, Map<String, ProjectObject> idx, double t, double dt) {
            double lux = o.state.getOrDefault("lux", o.param("lux", 0));
            o.state.put("level", lux > 50 ? 1.0 : 0.0);
        }
        @Override public double branchResistance(ProjectObject o, Map<String, ProjectObject> idx) {
            return o.param("resistance", 1000);
        }
    }
}
