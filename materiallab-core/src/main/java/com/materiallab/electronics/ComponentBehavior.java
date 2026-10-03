package com.materiallab.electronics;

import com.materiallab.model.ProjectObject;
import java.util.Map;

/** Strategy interface: per-type simulation behaviour. Keeps physics/UI separate. */
public interface ComponentBehavior {
    /** Called each tick to update component state (logic, sensors, timers). */
    void step(ProjectObject obj, ElectricalSolver.Result elec, Map<String, ProjectObject> index, double t, double dt);
    /** Effective branch resistance for the DC solver, Ohm. Return <=0 => open switch/off. */
    double branchResistance(ProjectObject obj, Map<String, ProjectObject> index);
}
