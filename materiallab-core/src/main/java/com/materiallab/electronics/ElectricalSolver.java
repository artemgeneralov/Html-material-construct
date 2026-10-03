package com.materiallab.electronics;

import com.materiallab.model.BodyKind;
import com.materiallab.model.Connection;
import com.materiallab.model.ConnectionType;
import com.materiallab.model.ProjectData;
import com.materiallab.model.ProjectObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Simplified DC circuit solver (spec section 5: current/voltage/resistance/short).
 * Method: conductance-based node analysis with a single ideal source referenced to GROUND.
 * If several sources exist, the strongest one is used and others act as branches.
 * This is an educational approximation, NOT a full MNA engine — documented limitation.
 */
public final class ElectricalSolver {

    public static final class Branch {
        public ProjectObject obj; public String pin1, pin2;
        public double resistance = 1e9;   // Ohm (open by default)
        public boolean isSource; public double emf;
        public String n1, n2;             // resolved node names
        public Branch(ProjectObject o, String p1, String p2) { obj = o; pin1 = p1; pin2 = p2; }
    }

    public static final class Result {
        public Map<String, Double> current = new HashMap<>();
        public Map<String, Double> voltage = new HashMap<>();
        public List<String> shortCircuitObjects = new ArrayList<>();
        public double totalCurrent;
        public boolean hasShort;
    }

    public static final String GROUND = "GND";
    private static final double WIRE_R = 0.01;
    /** Path conductance above this (Siemens) => current > ~8A per volt => short. */
    public static final double SHORT_CONDUCTANCE_LIMIT = 8.0;

    public Result solve(ProjectData p, Map<String, ComponentBehavior> behaviors) {
        Result r = new Result();
        NodeMap nm = new NodeMap();
        for (Connection c : p.connections)
            if (c.type == ConnectionType.ELECTRICAL && c.welded)
                nm.link(c.aObj, c.aPort, c.bObj, c.bPort);
        for (ProjectObject o : p.objects)
            if (o.kind == BodyKind.WIRE && !flag(o, "broken") && o.points != null && o.points.size() >= 2)
                nm.link(o.id, "a", o.id, "b");

        Map<String, ProjectObject> index = p.indexById();
        List<Branch> branches = new ArrayList<>();
        for (ProjectObject o : p.objects) {
            if (o.kind != BodyKind.COMPONENT || flag(o, "fault")) continue;
            ComponentDef def = ComponentsCatalog.get().get(o.typeId);
            ComponentBehavior b = behaviors.get(o.typeId);
            if (def == null || b == null || def.ports.size() < 2) continue;
            Branch br = new Branch(o, def.ports.get(0).id, def.ports.get(1).id);
            double res = b.branchResistance(o, index);
            if (res <= 0) continue;                     // open switch etc. -> absent branch
            br.resistance = Math.max(res, 1e-9);
            br.isSource = def.defaultParams.containsKey("voltage");
            if (br.isSource) br.emf = o.param("voltage", 0);
            br.n1 = resolve(nm, o.id, br.pin1, def.hasGround ? br.pin2 : null);
            br.n2 = resolve(nm, o.id, br.pin2, def.hasGround ? br.pin2 : null);
            branches.add(br);
        }

        // pick dominant source
        Branch src = null;
        for (Branch br : branches)
            if (br.isSource && br.emf > 0 && (src == null || br.emf > src.emf)) src = br;
        if (src == null) { zeroAll(p); return r; }

        // build conductance graph of passive branches
        Map<String, Map<String, Double>> g = new HashMap<>();
        for (Branch br : branches) {
            if (br == src) continue;
            addEdge(g, br.n1, br.n2, 1.0 / br.resistance);
        }
        // short: source terminals directly connected, or external conductance too high
        double extCond = g.getOrDefault(src.n1, Map.of()).getOrDefault(src.n2, 0.0);
        boolean direct = src.n1.equals(src.n2);
        if (direct || extCond >= SHORT_CONDUCTANCE_LIMIT) {
            r.hasShort = true;
            r.shortCircuitObjects.add(src.obj.id);
            double i = src.emf / Math.max(WIRE_R, 1.0 / Math.max(extCond, 1e-9));
            r.current.put(src.obj.id, i); r.voltage.put(src.obj.id, src.emf);
            r.totalCurrent = i;
            src.obj.state.put("current", i); src.obj.state.put("voltage", src.emf);
            src.obj.state.put("short", 1.0);
            for (Branch br : branches) if (br != src) { br.obj.state.put("current", 0.0); }
            return r;
        }
        src.obj.state.remove("short");

        // node voltages via DFS along best-conductance path (approximation):
        // V(node) computed by walking from source node; simple approach: series drop along found path.
        List<Branch> path = findPath(branches, src);
        if (path == null) { src.obj.state.put("current", 0.0); zeroOthers(p, src); return r; }
        double sumR = WIRE_R;
        for (Branch br : path) sumR += br.resistance;
        double i = src.emf / sumR;
        r.totalCurrent = i;
        setObj(src.obj, i, src.emf, r);
        double v = src.emf;
        for (Branch br : path) {
            double u = i * br.resistance;
            v -= u;
            setObj(br.obj, i, u, r);
        }
        zeroOthers(p, src);
        return r;
    }

    private void setObj(ProjectObject o, double cur, double vol, Result r) {
        o.state.put("current", cur); o.state.put("voltage", vol);
        r.current.put(o.id, cur); r.voltage.put(o.id, vol);
    }

    private void zeroAll(ProjectData p) {
        for (ProjectObject o : p.objects)
            if (o.kind == BodyKind.COMPONENT) { o.state.put("current", 0.0); o.state.put("voltage", 0.0); }
    }
    private void zeroOthers(ProjectData p, Branch src) {
        for (ProjectObject o : p.objects)
            if (o.kind == BodyKind.COMPONENT && o != src.obj && !o.state.containsKey("voltage"))
                { o.state.put("current", 0.0); o.state.put("voltage", 0.0); }
    }

    private static void addEdge(Map<String, Map<String, Double>> g, String a, String b, double cond) {
        if (a.equals(b)) return;
        g.computeIfAbsent(a, k -> new HashMap<>()).merge(b, cond, Double::sum);
        g.computeIfAbsent(b, k -> new HashMap<>()).merge(a, cond, Double::sum);
    }

    /** BFS through passive branches from src.n1 to src.n2 returning branch sequence. */
    private List<Branch> findPath(List<Branch> all, Branch src) {
        Map<String, List<Branch>> byNode = new HashMap<>();
        for (Branch br : all) {
            if (br == src) continue;
            byNode.computeIfAbsent(br.n1, k -> new ArrayList<>()).add(br);
            byNode.computeIfAbsent(br.n2, k -> new ArrayList<>()).add(br);
        }
        Set<String> visited = new HashSet<>();
        List<Branch> acc = new ArrayList<>();
        return dfs(byNode, src.n1, src.n2, visited, acc) ? acc : null;
    }

    private boolean dfs(Map<String, List<Branch>> byNode, String node, String goal,
                        Set<String> visited, List<Branch> acc) {
        if (node.equals(goal)) return true;
        if (!visited.add(node)) return false;
        for (Branch br : byNode.getOrDefault(node, List.of())) {
            if (acc.contains(br)) continue;
            String next = br.n1.equals(node) ? br.n2 : br.n1;
            acc.add(br);
            if (dfs(byNode, next, goal, visited, acc)) return true;
            acc.remove(acc.size() - 1);
        }
        return false;
    }

    private static boolean flag(ProjectObject o, String k) {
        Double v = o.state.get(k); return v != null && v > 0.5;
    }

    private String resolve(NodeMap nm, String obj, String port, String groundPin) {
        if (groundPin != null && groundPin.equals(port)) return GROUND;
        return "N" + nm.nodeId(obj, port);
    }
}
