package com.materiallab.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.materiallab.model.BodyKind;
import com.materiallab.model.CameraState;
import com.materiallab.model.Connection;
import com.materiallab.model.ConnectionType;
import com.materiallab.model.GridState;
import com.materiallab.model.ProjectData;
import com.materiallab.model.ProjectObject;
import com.materiallab.model.SimulationSettings;
import com.materiallab.model.Vec2;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * .mlab format: ZIP container with
 *   project.json  — main document (versioned)
 *   meta.json     — metadata + format history
 *   README.txt    — human note
 * Atomic save via temp file + move, plus .bak backup & recovery (spec 13).
 */
public final class ProjectStorage {

    public static final int CURRENT_FORMAT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // ---------- JSON model (explicit DTOs -> stable format, migration-friendly) ----------

    public static final class JsonVec { double x, y; JsonVec(double x, double y){this.x=x;this.y=y;} }
    public static final class JsonPointList { List<JsonVec> pts; }

    public static final class JsonObjectDto {
        String id, typeId, kind, name, materialId, parentModuleId;
        JsonVec pos; double rotation, width, height; boolean fixed;
        Map<String, Double> params; Map<String, String> stringParams;
        List<JsonVec> points;
    }

    public static final class JsonConnectionDto {
        String id, aObj, aPort, bObj, bPort, type, weldKind;
        boolean welded; double strength, meltTemp, resistance;
    }

    public static final class JsonProject {
        int formatVersion = CURRENT_FORMAT_VERSION;
        List<JsonObjectDto> objects = new ArrayList<>();
        List<JsonConnectionDto> connections = new ArrayList<>();
        List<Map<String, Object>> customMaterials = new ArrayList<>();
        List<Map<String, Object>> customComponents = new ArrayList<>();
        List<Map<String, Object>> modules = new ArrayList<>();
        List<Map<String, Object>> planTasks = new ArrayList<>();
        SimulationSettings simulation;
        CameraState camera;
        GridState grid;
    }

    // ---------- serialization ----------

    public static String toJson(ProjectData p) {
        return GSON.toJson(toDto(p));
    }

    public static ProjectData fromJson(String json) {
        JsonProject dto = GSON.fromJson(json, JsonProject.class);
        if (dto == null) throw new IllegalArgumentException("Пустой или повреждённый JSON проекта");
        if (dto.formatVersion > CURRENT_FORMAT_VERSION)
            throw new IllegalArgumentException("Неподдерживанная версия формата: " + dto.formatVersion
                    + " (максимум " + CURRENT_FORMAT_VERSION + "). Обновите приложение.");
        migrate(dto);
        return fromDto(dto);
    }

    /** Version migrations chain. v0 (legacy without formatVersion) -> v1. */
    static void migrate(JsonProject dto) {
        if (dto.formatVersion <= 0) {
            // legacy: fields defaulted; nothing to rename in v1
            dto.formatVersion = 1;
        }
        // future: if (dto.formatVersion == 1) { ...upgrade to 2... }
    }

    static JsonProject toDto(ProjectData p) {
        JsonProject d = new JsonProject();
        d.formatVersion = CURRENT_FORMAT_VERSION;
        for (ProjectObject o : p.objects) {
            JsonObjectDto j = new JsonObjectDto();
            j.id = o.id; j.typeId = o.typeId; j.kind = o.kind.name();
            j.name = o.name; j.materialId = o.materialId; j.parentModuleId = o.parentModuleId;
            j.pos = new JsonVec(o.pos.x, o.pos.y);
            j.rotation = o.rotation; j.width = o.width; j.height = o.height; j.fixed = o.fixed;
            j.params = new java.util.LinkedHashMap<>(o.params);
            j.stringParams = new java.util.LinkedHashMap<>(o.stringParams);
            if (o.points != null) {
                j.points = new ArrayList<>();
                for (Vec2 v : o.points) j.points.add(new JsonVec(v.x, v.y));
            }
            d.objects.add(j);
        }
        for (Connection c : p.connections) {
            JsonConnectionDto j = new JsonConnectionDto();
            j.id = c.id; j.aObj = c.aObj; j.aPort = c.aPort; j.bObj = c.bObj; j.bPort = c.bPort;
            j.type = c.type.name(); j.weldKind = c.weldKind; j.welded = c.welded;
            j.strength = c.strength; j.meltTemp = c.meltTemp; j.resistance = c.resistance;
            d.connections.add(j);
        }
        d.customMaterials.addAll(p.customMaterials);
        d.customComponents.addAll(p.customComponents);
        d.modules.addAll(p.modules);
        d.planTasks.addAll(p.planTasks);
        d.simulation = p.simulation;
        d.camera = p.camera;
        d.grid = p.grid;
        return d;
    }

    static ProjectData fromDto(JsonProject d) {
        ProjectData p = new ProjectData();
        p.formatVersion = d.formatVersion;
        for (JsonObjectDto j : d.objects) {
            ProjectObject o = new ProjectObject();
            o.id = j.id; o.typeId = j.typeId;
            o.kind = j.kind == null ? BodyKind.COMPONENT : BodyKind.valueOf(j.kind);
            o.name = j.name; o.materialId = j.materialId == null ? "plastic" : j.materialId;
            o.parentModuleId = j.parentModuleId;
            if (j.pos != null) o.pos = new Vec2(j.pos.x, j.pos.y);
            o.rotation = j.rotation; o.width = j.width; o.height = j.height; o.fixed = j.fixed;
            if (j.params != null) o.params.putAll(j.params);
            if (j.stringParams != null) o.stringParams.putAll(j.stringParams);
            if (j.points != null) {
                o.points = new ArrayList<>();
                for (JsonVec v : j.points) o.points.add(new Vec2(v.x, v.y));
            }
            p.objects.add(o);
        }
        for (JsonConnectionDto j : d.connections) {
            Connection c = new Connection();
            c.id = j.id; c.aObj = j.aObj; c.aPort = j.aPort; c.bObj = j.bObj; c.bPort = j.bPort;
            c.type = j.type == null ? ConnectionType.ELECTRICAL : ConnectionType.valueOf(j.type);
            c.weldKind = j.weldKind == null ? "metal" : j.weldKind;
            c.welded = j.welded;
            c.strength = j.strength; c.meltTemp = j.meltTemp; c.resistance = j.resistance;
            p.connections.add(c);
        }
        if (d.customMaterials != null) p.customMaterials.addAll(d.customMaterials);
        if (d.customComponents != null) p.customComponents.addAll(d.customComponents);
        if (d.modules != null) p.modules.addAll(d.modules);
        if (d.planTasks != null) p.planTasks.addAll(d.planTasks);
        if (d.simulation != null) p.simulation = d.simulation;
        if (d.camera != null) p.camera = d.camera;
        if (d.grid != null) p.grid = d.grid;
        return p;
    }

    // ---------- file I/O (ZIP container) ----------

    public static void save(Path file, ProjectData p) throws IOException {
        byte[] projectJson = toJson(p).getBytes(StandardCharsets.UTF_8);
        JsonObject meta = new JsonObject();
        meta.addProperty("formatVersion", CURRENT_FORMAT_VERSION);
        meta.addProperty("application", "MaterialLab Simulator");
        meta.addProperty("savedAt", java.time.Instant.now().toString());
        for (Map.Entry<String, Object> e : p.metadata.entrySet())
            if (e.getValue() != null) meta.addProperty(e.getKey(), String.valueOf(e.getValue()));

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp), StandardCharsets.UTF_8)) {
            put(zos, "project.json", projectJson);
            put(zos, "meta.json", GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));
            put(zos, "README.txt",
                ("MaterialLab project. Format version " + CURRENT_FORMAT_VERSION + "\n"
                 + "project.json - документ; meta.json - метаданные.\n")
                 .getBytes(StandardCharsets.UTF_8));
        }
        if (Files.exists(file)) {
            Path bak = file.resolveSibling(file.getFileName() + ".bak");
            Files.move(file, bak, StandardCopyOption.REPLACE_EXISTING);   // backup for recovery
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    public static ProjectData load(Path file) throws IOException {
        byte[] zip = Files.readAllBytes(file);
        try {
            return loadFromBytes(zip);
        } catch (Exception first) {
            Path bak = file.resolveSibling(file.getFileName() + ".bak");
            if (Files.exists(bak)) {          // corruption recovery (spec 13)
                ProjectData recovered = loadFromBytes(Files.readAllBytes(bak));
                recovered.metadata.put("recoveredFromBackup", file.toString());
                return recovered;
            }
            throw new IOException("Файл повреждён: " + first.getMessage(), first);
        }
    }

    public static ProjectData loadFromBytes(byte[] zipBytes) throws IOException {
        String projectJson = null, metaJson = null;
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().equals("project.json")) projectJson = readAll(zis);
                else if (e.getName().equals("meta.json")) metaJson = readAll(zis);
            }
        }
        if (projectJson == null) throw new IOException("В контейнере отсутствует project.json");
        ProjectData p = fromJson(projectJson);
        if (metaJson != null) {
            JsonObject m = JsonParser.parseString(metaJson).getAsJsonObject();
            for (String k : m.keySet())
                if (!k.equals("formatVersion")) p.metadata.put(k, m.get(k).getAsString());
        }
        return p;
    }

    /** Validate that bytes are a readable .mlab (used by validation & tests). */
    public static boolean isReadable(byte[] zipBytes) {
        try { loadFromBytes(zipBytes); return true; } catch (Exception ex) { return false; }
    }

    private static void put(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString(StandardCharsets.UTF_8);
    }
}
