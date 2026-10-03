package com.materiallab.presets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.materiallab.model.ProjectData;
import com.materiallab.storage.ProjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Preset loader (spec section 12). Presets are JSON data files in resources
 * (/presets/*.json) — never hard-coded scene graphs. Custom presets can be
 * dropped into <userdir>/materiallab/presets/.
 */
public final class Presets {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public static final class PresetInfo {
        public String file, name, description;
        public PresetInfo(String file, String name, String description) {
            this.file = file; this.name = name; this.description = description;
        }
    }

    /** Built-in preset index (files must exist under resources /presets/). */
    public static List<PresetInfo> list() {
        List<PresetInfo> out = new ArrayList<>();
        for (String f : new String[]{
                "circuit-battery-lamp.json", "switch-lamp.json", "dimmer.json",
                "motor-battery.json", "pendulum.json", "spring-mass.json"}) {
            // metadata read lazily from the file itself
            out.add(new PresetInfo(f, f.replace(".json", ""), ""));
        }
        return out;
    }

    public static ProjectData loadBuiltin(String fileName) throws IOException {
        try (InputStream in = Presets.class.getResourceAsStream("/presets/" + fileName)) {
            if (in == null) throw new IOException("Пресет не найден: " + fileName);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            ProjectData p = ProjectStorage.fromJson(json);
            p.metadata.put("preset", fileName);
            return p;
        }
    }

    /** Load a custom preset saved by the user (same .mlab or plain JSON). */
    public static ProjectData loadFile(java.nio.file.Path file) throws IOException {
        if (file.toString().endsWith(".mlab")) return ProjectStorage.load(file);
        String json = new String(java.nio.file.Files.readAllBytes(file), StandardCharsets.UTF_8);
        return ProjectStorage.fromJson(json);
    }

    /** Validate that a preset resource is parseable (spec 15: damaged presets). */
    public static boolean checkBuiltin(String fileName) {
        try { loadBuiltin(fileName); return true; } catch (Exception e) { return false; }
    }
}
