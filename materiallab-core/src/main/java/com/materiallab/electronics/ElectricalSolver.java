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

    /**
     * Full MNA-style DC solve (conductance nodal analysis with ideal voltage
     * sources via Modified Nodal Analysis). Ground = the source's "gnd" pin.
     * Node voltages and per-branch currents are exact for the resistive model;
     * this is still an approximation of real electronics (no AC, no transistors).
     */
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

        // ---- collect nodes & ground ----
        java.util.LinkedHashSet<String> nodeSet = new java.util.LinkedHashSet<>();
        for (Branch br : branches) { nodeSet.add(br.n1); nodeSet.add(br.n2); }
        nodeSet.remove(GROUND);
        List<String> nodes = new ArrayList<>(nodeSet);
        int nN = nodes.size();
        java.util.function.Function<String, Integer> idxOf = nodes::indexOf;

        // ---- build MNA system: [G B; B^T 0] [V; I_src] = [I; E] ----
        int nS = 0;
        for (Branch br : branches) if (br.isSource && br.emf > 0) nS++;
        boolean hasAnySource = false;
        for (Branch br : branches) if (br.isSource && br.emf > 0) { hasAnySource = true; break; }
        if (!hasAnySource || nN == 0) { zeroAll(p); return r; }

        int dim = nN + nS;
        double[][] A = new double[dim][dim];
        double[] rhs = new double[dim];

        // pass 1: stamp conductances of ALL branches (sources get a parallel
        // resistance too — models internal R realistically and lets shorts blow)
        int sIdx = -1;
        for (Branch br : branches) {
            if (br.resistance <= 0) continue;
            addCond(A, idxOf, br.n1, br.n2, 1.0 / br.resistance);
        }
        // pass 2: stamp ideal voltage sources (EMF) as MNA source columns
        sIdx = -1;
        for (Branch br : branches) {
            if (!br.isSource || br.emf <= 0) continue;
            sIdx++;
            int i1 = idxOf.apply(br.n1), i2 = idxOf.apply(br.n2);
            if (i1 >= 0) A[i1][nN + sIdx] += 1; else {/* n1 is GND */}
            if (i2 >= 0) A[i2][nN + sIdx] -= 1;
            if (i1 >= 0) A[nN + sIdx][i1] += 1;
            if (i2 >= 0) A[nN + sIdx][i2] -= 1;
            rhs[nN + sIdx] = br.emf;
        }

        double[] sol;
        try { sol = solveLinear(A, rhs); }
        catch (IllegalStateException singular) {   // floating/degenerate circuit
            zeroAll(p);
            return r;
        }

        // ---- short-circuit detection: path conductance between source pins ----
        Branch src = null;
        for (Branch br : branches)
            if (br.isSource && br.emf > 0 && (src == null || br.emf > src.emf)) src = br;
        boolean directShort = src != null && src.n1.equals(src.n2);
        // current through the source branch itself:
        double srcCurrent = src != null ? Math.abs(sol.length > nN ? lastSourceCurrent(sol, nN, nS) : 0) : 0;
        double expectedMax = src != null ? Math.abs(src.emf) / Math.max(src.resistance, 1e-9) : 0;
        if (directShort || (src != null && srcCurrent > Math.max(expectedMax * 0.9, 8.0))) {
            r.hasShort = true;
            r.shortCircuitObjects.add(src.obj.id);
            double i = directShort ? src.emf / Math.max(WIRE_R, src.resistance) : srcCurrent;
            r.current.put(src.obj.id, i); r.voltage.put(src.obj.id, src.emf);
            r.totalCurrent = i;
            src.obj.state.put("current", i); src.obj.state.put("voltage", src.emf);
            src.obj.state.put("short", 1.0);
            for (Branch br : branches) if (br != src) br.obj.state.put("current", 0.0);
            return r;
        }
        if (src != null) src.obj.state.remove("short");

        // ---- distribute results to every branch by its node voltages ----
        java.util.Set<String> touched = new java.util.HashSet<>();
        for (Branch br : branches) {
            double v1 = br.n1.equals(GROUND) ? 0 : val(sol, idxOf.apply(br.n1));
            double v2 = br.n2.equals(GROUND) ? 0 : val(sol, idxOf.apply(br.n2));
            double u = v1 - v2;
            double cur = u / br.resistance;
            setObj(br.obj, cur, Math.abs(u), r);
            touched.add(br.obj.id);
        }
        r.totalCurrent = src != null ? Math.abs(val2(sol, idxOf, src.n1) - val2(sol, idxOf, src.n2)) / Math.max(src.resistance, 1e-9) : 0;
        zeroOthers(p, touched);
        return r;
    }

    private static double lastSourceCurrent(double[] sol, int nN, int nS) {
        return nS > 0 ? Math.abs(sol[nN + nS - 1]) : 0;
    }

    private void setObj(ProjectObject o, double cur, double vol, Result r) {
        o.state.put("current", cur); o.state.put("voltage", vol);
        r.current.put(o.id, cur); r.voltage.put(o.id, vol);
    }

    private void zeroAll(ProjectData p) {
        for (ProjectObject o : p.objects)
            if (o.kind == BodyKind.COMPONENT) { o.state.put("current", 0.0); o.state.put("voltage", 0.0); }
    }
    private void zeroOthers(ProjectData p, java.util.Set<String> touched) {
        for (ProjectObject o : p.objects)
            if (o.kind == BodyKind.COMPONENT && !touched.contains(o.id)) {
                o.state.put("current", 0.0); o.state.put("voltage", 0.0);
            }
    }

    private static void addCond(double[][] A, java.util.function.Function<String, Integer> idxOf,
                                String a, String b, double cond) {
        int ia = a.equals(GROUND) ? -1 : idxOf.apply(a);
        int ib = b.equals(GROUND) ? -1 : idxOf.apply(b);
        if (ia >= 0) A[ia][ia] += cond;
        if (ib >= 0) A[ib][ib] += cond;
        if (ia >= 0 && ib >= 0) { A[ia][ib] -= cond; A[ib][ia] -= cond; }
    }

    private static double val(double[] sol, int i) { return i < 0 ? 0 : sol[i]; }

    private static double val2(double[] sol, java.util.function.Function<String, Integer> idxOf, String node) {
        return node.equals(GROUND) ? 0 : sol[idxOf.apply(node)];
    }

    /** Gaussian elimination with partial pivoting. Throws on singular matrix. */
    static double[] solveLinear(double[][] A, double[] rhs) {
        int n = rhs.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, m[i], 0, n);
            m[i][n] = rhs[i];
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++)
                if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            if (Math.abs(m[piv][col]) < 1e-12) throw new IllegalStateException("singular");
            double[] tmp = m[col]; m[col] = m[piv]; m[piv] = tmp;
            for (int r = col + 1; r < n; r++) {
                double f = m[r][col] / m[col][col];
                if (f == 0) continue;
                for (int c = col; c <= n; c++) m[r][c] -= f * m[col][c];
            }
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double s2 = m[i][n];
            for (int j = i + 1; j < n; j++) s2 -= m[i][j] * x[j];
            x[i] = s2 / m[i][i];
        }
        return x;
    }

    private static boolean flag(ProjectObject o, String k) {
        Double v = o.state.get(k); return v != null && v > 0.5;
    }

    private String resolve(NodeMap nm, String obj, String port, String groundPin) {
        if (groundPin != null && groundPin.equals(port)) return GROUND;
        return "N" + nm.nodeId(obj, port);
    }
}
