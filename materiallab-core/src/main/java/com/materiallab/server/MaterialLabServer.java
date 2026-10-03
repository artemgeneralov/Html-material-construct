package com.materiallab.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.materiallab.electronics.ComponentDef;
import com.materiallab.electronics.ComponentsCatalog;
import com.materiallab.materials.Material;
import com.materiallab.materials.MaterialsRegistry;
import com.materiallab.model.BodyKind;
import com.materiallab.model.ConnectionType;
import com.materiallab.model.ProjectData;
import com.materiallab.model.ProjectObject;
import com.materiallab.presets.Presets;
import com.materiallab.simulation.ProjectSerializer;
import com.materiallab.simulation.SimulationController;
import com.materiallab.storage.ProjectStorage;
import com.materiallab.validation.ProjectValidator;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Embedded REST+static server built on JDK's com.sun.net.httpserver — zero
 * external dependencies, works in the same JAR as desktop mode (spec 2.2).
 * Spring Boot is intentionally NOT used to keep the fat-JAR small and startup
 * instant; endpoints follow REST conventions so a later swap is trivial.
 */
public final class MaterialLabServer {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final int port;
    private HttpServer http;
    private ProjectData project = new ProjectData();
    private SimulationController sim;
    private final List<String> events = new ArrayList<>();

    public MaterialLabServer(int port) { this.port = port; }

    public synchronized void start() throws IOException {
        if (http != null) return;
        sim = new SimulationController(project);
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        http.createContext("/", this::serveStatic);
        http.createContext("/api/state", ex -> api(ex, this::stateJson));
        http.createContext("/api/project", this::apiProject);
        http.createContext("/api/add", this::apiAdd);
        http.createContext("/api/move", this::apiMove);
        http.createContext("/api/remove", this::apiRemove);
        http.createContext("/api/weld", this::apiWeld);
        http.createContext("/api/cut", this::apiCut);
        http.createContext("/api/wire", this::apiWire);
        http.createContext("/api/sim/start", ex -> api(ex, () -> { sim.start(); return "{\"ok\":true}"; }));
        http.createContext("/api/sim/stop", ex -> api(ex, () -> { sim.stop(); return "{\"ok\":true}"; }));
        http.createContext("/api/sim/pause", ex -> api(ex, () -> { sim.pause(); return "{\"ok\":true}"; }));
        http.createContext("/api/sim/step", ex -> api(ex, () -> { sim.stepOnce(); return "{\"ok\":true}"; }));
        http.createContext("/api/materials", ex -> api(ex, this::materialsJson));
        http.createContext("/api/components", ex -> api(ex, this::componentsJson));
        http.createContext("/api/presets", ex -> api(ex, this::presetsJson));
        http.createContext("/api/preset/load", this::apiPresetLoad);
        http.createContext("/api/validate", ex -> api(ex, this::validateJson));
        http.setExecutor(null);
        http.start();
        System.out.println("[MaterialLab] сервер запущен: http://localhost:" + port);
    }

    public synchronized void stop() {
        if (http != null) { http.stop(0); http = null; }
    }

    public int getPort() { return port; }
    public ProjectData getProject() { return project; }
    public void setProject(ProjectData p) {
        this.project = p;
        this.sim = new SimulationController(p);
    }

    // ---------- endpoints ----------

    String stateJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        ProjectData view = sim.runtimeState();
        List<Map<String, Object>> objs = new ArrayList<>();
        for (ProjectObject o : view.objects) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.id); m.put("typeId", o.typeId); m.put("kind", o.kind.name());
            m.put("name", o.name); m.put("x", round(o.pos.x)); m.put("y", round(o.pos.y));
            m.put("rotation", round(o.rotation)); m.put("width", o.width); m.put("height", o.height);
            m.put("material", o.materialId);
            Map<String, Double> st = new LinkedHashMap<>();
            o.state.forEach((k, v) -> st.put(k, round(v)));
            m.put("state", st);
            if (o.points != null) {
                List<double[]> pts = new ArrayList<>();
                for (var p : o.points) pts.add(new double[]{round(p.x), round(p.y)});
                m.put("points", pts);
            }
            objs.add(m);
        }
        root.put("objects", objs);
        List<Map<String, Object>> conns = new ArrayList<>();
        for (var c : view.connections) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id); m.put("aObj", c.aObj); m.put("aPort", c.aPort);
            m.put("bObj", c.bObj); m.put("bPort", c.bPort);
            m.put("type", c.type.name()); m.put("welded", c.welded);
            conns.add(m);
        }
        root.put("connections", conns);
        root.put("mode", sim.mode().name());
        root.put("time", round(sim.time()));
        root.put("tick", sim.tickCount());
        root.put("log", sim.log().subList(Math.max(0, sim.log().size() - 30), sim.log().size()));
        return GSON.toJson(root);
    }

    String materialsJson() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Material m : MaterialsRegistry.get().all().values()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", m.id); mm.put("name", m.nameRu); mm.put("density", m.density);
            mm.put("conductivity", m.conductivity); mm.put("meltingPoint", m.meltingPoint);
            mm.put("hardness", m.hardness); mm.put("strength", m.strength);
            mm.put("friction", m.friction); mm.put("magnetic", m.magnetic);
            mm.put("weldable", m.weldable); mm.put("color", m.colorRgb);
            mm.put("custom", m.custom);
            out.add(mm);
        }
        return GSON.toJson(Map.of("materials", out,
            "note", "Значения hardness/strength/friction/cost — инженерные приближения симуляции."));
    }

    String componentsJson() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ComponentDef d : ComponentsCatalog.get().all()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", d.id); mm.put("name", d.nameRu); mm.put("category", d.category);
            mm.put("description", d.description);
            mm.put("ports", d.ports.stream().map(p -> p.id + ":" + p.type.name()).toList());
            mm.put("defaults", d.defaultParams);
            out.add(mm);
        }
        return GSON.toJson(Map.of("components", out));
    }

    String presetsJson() {
        return GSON.toJson(Presets.list());
    }

    String validateJson() {
        var issues = new ProjectValidator().validate(project);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var i : issues) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", i.severity.name()); m.put("code", i.code);
            m.put("message", i.message); m.put("objectId", i.objectId);
            m.put("x", i.x); m.put("y", i.y); m.put("hint", i.hint);
            out.add(m);
        }
        return GSON.toJson(Map.of("issues", out));
    }

    private void apiProject(HttpExchange ex) throws IOException {
        if ("GET".equals(ex.getRequestMethod())) {
            respond(ex, 200, ProjectStorage.toJson(project));
        } else if ("PUT".equals(ex.getRequestMethod())) {
            String body = readBody(ex);
            try {
                setProject(ProjectStorage.fromJson(body));
                respond(ex, 200, "{\"ok\":true}");
            } catch (Exception e) { respond(ex, 400, err(e)); }
        } else respond(ex, 405, "{\"error\":\"method\"}");
    }

    private void apiAdd(HttpExchange ex) throws IOException {
        JsonObject j = body(ex);
        if (j == null) { respond(ex, 400, "{\"error\":\"json expected\"}"); return; }
        String typeId = j.get("typeId").getAsString();
        ComponentDef def = ComponentsCatalog.get().get(typeId);
        if (def == null) { respond(ex, 400, err(new IllegalArgumentException("Неизвестный компонент: " + typeId))); return; }
        ProjectObject o = newObjectFromDef(def, num(j, "x", 0), num(j, "y", 0));
        if (j.has("material")) o.materialId = j.get("material").getAsString();
        project.addObject(o);
        events.add("add " + o.id);
        respond(ex, 200, "{\"id\":\"" + o.id + "\"}");
    }

    private void apiMove(HttpExchange ex) throws IOException {
        JsonObject j = body(ex);
        ProjectObject o = project.byId(j.get("id").getAsString());
        if (o == null) { respond(ex, 404, "{\"error\":\"not found\"}"); return; }
        o.pos.set(num(j, "x", o.pos.x), num(j, "y", o.pos.y));
        if (j.has("rotation")) o.rotation = j.get("rotation").getAsDouble();
        respond(ex, 200, "{\"ok\":true}");
    }

    private void apiRemove(HttpExchange ex) throws IOException {
        JsonObject j = body(ex);
        boolean ok = project.removeObject(j.get("id").getAsString());
        respond(ex, ok ? 200 : 404, "{\"ok\":" + ok + "}");
    }

    private void apiWeld(HttpExchange ex) throws IOException {
        JsonObject j = body(ex);
        try {
            ConnectionType type = j.has("type")
                ? ConnectionType.valueOf(j.get("type").getAsString()) : ConnectionType.ELECTRICAL;
            var c = project.weld(j.get("aObj").getAsString(), j.get("aPort").getAsString(),
                                  j.get("bObj").getAsString(), j.get("bPort").getAsString(), type);
            ProjectObject a = project.byId(c.aObj), b = project.byId(c.bObj);
            if (!ProjectValidator.isWeldIncompatible(a, b))
                c.strength = ProjectValidator.weldStrengthFactor(a, b, c.strength);
            respond(ex, 200, "{\"id\":\"" + c.id + "\"}");
        } catch (Exception e) { respond(ex, 400, err(e)); }
    }

    private void apiCut(HttpExchange ex) throws IOException {
        JsonObject j = body(ex);
        boolean ok = project.cutWeld(j.get("id").getAsString());
        respond(ex, ok ? 200 : 404, "{\"ok\":" + ok + "}");
    }

    private void apiWire(HttpExchange ex) throws IOException {
        JsonObject j = body(ex);
        var w = ProjectSerializer.makeWire(project.newId("wire"),
            new com.materiallab.model.Vec2(num(j, "x1", 0), num(j, "y1", 0)),
            new com.materiallab.model.Vec2(num(j, "x2", 1), num(j, "y2", 0)),
            j.has("material") ? j.get("material").getAsString() : "copper");
        project.addObject(w);
        respond(ex, 200, "{\"id\":\"" + w.id + "\"}");
    }

    private void apiPresetLoad(HttpExchange ex) throws IOException {
        JsonObject j = body(ex);
        try {
            setProject(Presets.loadBuiltin(j.get("file").getAsString()));
            respond(ex, 200, "{\"ok\":true}");
        } catch (Exception e) { respond(ex, 404, err(e)); }
    }

    /** Instantiate component object from catalog definition. Shared by UI & API. */
    public static ProjectObject newObjectFromDef(ComponentDef def, double x, double y) {
        ProjectObject o = new ProjectObject(java.util.UUID.randomUUID().toString().substring(0, 8),
                def.id, BodyKind.COMPONENT);
        o.name = def.nameRu;
        o.pos.set(x, y);
        o.width = def.width; o.height = def.height;
        o.materialId = def.materialId;
        o.params.putAll(def.defaultParams);
        o.params.put("mass", def.massKg);
        o.fixed = def.fixedAnchor;
        return o;
    }

    // ---------- plumbing ----------

    private interface Supplier { String get() throws Exception; }
    private void api(HttpExchange ex, Supplier s) {
        try { respond(ex, 200, s.get()); }
        catch (Exception e) { try { respond(ex, 500, err(e)); } catch (IOException ignored) {} }
    }

    private void serveStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        InputStream in = MaterialLabServer.class.getResourceAsStream("/web" + path);
        if (in == null) { respond(ex, 404, "404"); return; }
        byte[] data = in.readAllBytes();
        ex.getResponseHeaders().add("Content-Type", contentType(path));
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    private static String contentType(String p) {
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        return "application/octet-stream";
    }

    private void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    private JsonObject body(HttpExchange ex) throws IOException {
        String s = readBody(ex);
        if (s == null || s.isBlank()) return null;
        try { return JsonParser.parseString(s).getAsJsonObject(); } catch (Exception e) { return null; }
    }

    private String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static double num(JsonObject j, String k, double def) {
        return j.has(k) ? j.get(k).getAsDouble() : def;
    }

    private static String err(Exception e) {
        return "{\"error\":\"" + (e.getMessage() == null ? e.getClass().getSimpleName()
                : e.getMessage().replace("\"", "'")) + "\"}";
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
