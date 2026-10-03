package com.materiallab.simulation;

import com.materiallab.electronics.Behaviors;
import com.materiallab.electronics.ComponentBehavior;
import com.materiallab.electronics.ElectricalSolver;
import com.materiallab.model.BodyKind;
import com.materiallab.model.ProjectData;
import com.materiallab.model.ProjectObject;
import com.materiallab.model.Vec2;
import com.materiallab.physics.PhysicsEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Facade that coordinates electronics + physics per tick (spec section 5).
 * The simulation runs on a deep copy of the project document, so editing
 * state and simulation state never mix (architecture requirement).
 */
public final class SimulationController {

    public enum Mode { STOPPED, RUNNING, PAUSED }

    private final ProjectData source;
    private ProjectData runtime;
    private final PhysicsEngine physics = new PhysicsEngine();
    private final ElectricalSolver solver = new ElectricalSolver();
    private final Map<String, ComponentBehavior> behaviors = Behaviors.createAll();
    private Mode mode = Mode.STOPPED;
    private double time;
    private long tickCount;
    private final List<String> log = new ArrayList<>();

    public SimulationController(ProjectData source) {
        this.source = source;
        this.runtime = ProjectSerializer.copy(source);
    }

    public ProjectData runtimeState() { return runtime; }
    public Mode mode() { return mode; }
    public double time() { return time; }
    public long tickCount() { return tickCount; }
    public List<String> log() { return log; }
    public PhysicsEngine physics() { return physics; }

    public void start() {
        resetRuntime();
        mode = Mode.RUNNING;
        log.add("Симуляция запущена");
    }

    public void pause() {
        if (mode == Mode.RUNNING) { mode = Mode.PAUSED; log.add("Пауза"); }
    }

    public void resume() {
        if (mode == Mode.PAUSED) { mode = Mode.RUNNING; log.add("Продолжение"); }
    }

    public void stop() {
        mode = Mode.STOPPED;
        log.add("Симуляция остановлена");
    }

    /** Advance exactly one tick — used for step-by-step debugging (spec 3). */
    public void stepOnce() {
        if (mode == Mode.STOPPED) resetRuntime();
        mode = Mode.PAUSED;
        tick();
        log.add(String.format("Шаг #%d, t=%.3f с", tickCount, time));
    }

    public void resetRuntime() {
        runtime = ProjectSerializer.copy(source);
        time = 0; tickCount = 0;
        physics.reset(runtime);
        log.add("Сброс симуляции");
    }

    /** One simulation tick: electrical solve -> behaviour steps -> physics step. */
    public void tick() {
        double dt = runtime.simulation.timeStep;
        ElectricalSolver.Result res = solver.solve(runtime, behaviors);
        if (res.hasShort && tickCount % 60 == 0)
            log.add("КОРОТКОЕ ЗАМЫКАНИЕ в " + res.shortCircuitObjects);
        Map<String, ProjectObject> idx = runtime.indexById();
        for (ProjectObject o : runtime.objects) {
            if (o.kind != BodyKind.COMPONENT) continue;
            ComponentBehavior b = behaviors.get(o.typeId);
            if (b != null) b.step(o, res, idx, time, dt);
        }
        // wire overheat/break model (spec 7): P=I^2*R vs maxCurrent
        for (ProjectObject o : runtime.objects)
            if (o.kind == BodyKind.WIRE) updateWire(o, res, dt);
        physics.step(runtime, dt);
        // propagate broken welds into runtime connections list
        for (String cid : physics.consumeBrokenConnections()) {
            if (runtime.cutWeld(cid)) log.add("Разрушено соединение " + cid);
        }
        time += dt;
        tickCount++;
    }

    private void updateWire(ProjectObject w, ElectricalSolver.Result res, double dt) {
        Double i = res.current.get(w.id);
        if (i == null) i = w.state.getOrDefault("current", 0.0);
        double maxI = w.param("maxCurrent", 5.0);
        double temp = w.state.getOrDefault("temperature", runtime.simulation.ambientTemp);
        temp += Math.max(0, (i * i * w.param("resistance", 0.01)) * dt * 50 - (temp - runtime.simulation.ambientTemp) * dt);
        w.state.put("temperature", temp);
        if (i > maxI || temp > w.param("meltTemp", 400)) {
            w.state.put("broken", 1.0);
            log.add("Провод " + w.name + " оборван (I=" + String.format("%.1fА", i) + ", T=" + String.format("%.0f°", temp) + ")");
        }
    }

    /** Snap runtime positions back to source is NOT done automatically —
     *  the editor keeps its document untouched while simulating. */
    public void applyPositionsToSource() {
        for (ProjectObject srcObj : source.objects) {
            ProjectObject r = runtime.byId(srcObj.id);
            if (r != null) {
                srcObj.pos = new Vec2(r.pos.x, r.pos.y);
                srcObj.rotation = r.rotation;
                if (r.points != null) srcObj.points = new ArrayList<>(r.points);
            }
        }
    }
}
