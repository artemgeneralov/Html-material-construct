package com.materiallab.launcher;

import java.io.File;

/**
 * Single entry point for the materiallab.jar (spec section 2).
 *   java -jar materiallab.jar                  -> start window
 *   java -jar materiallab.jar --mode=desktop   -> editor immediately
 *   java -jar materiallab.jar --mode=server [--port=8080]
 *   java -jar materiallab.jar --project=file.mlab
 */
public final class Main {
    public static void main(String[] args) {
        String mode = arg(args, "--mode");
        int port = Integer.parseInt(argOrDefault(args, "--port", "8080"));
        String project = arg(args, "--project");

        File home = new File(System.getProperty("user.home"), "materiallab");
        for (String sub : new String[]{"projects", "presets", "materials", "logs", "backups"})
            if (!new File(home, sub).exists()) new File(home, sub).mkdirs();

        if ("server".equalsIgnoreCase(mode)) {
            ServerRunner.run(port, project);
        } else if ("desktop".equalsIgnoreCase(mode)) {
            DesktopRunner.run(project);
        } else {
            StartWindow.show(port, project);
        }
    }

    static String arg(String[] args, String key) {
        for (String a : args)
            if (a.startsWith(key + "=")) return a.substring(key.length() + 1);
        return null;
    }
    static String argOrDefault(String[] args, String key, String def) {
        String v = arg(args, key);
        return v == null ? def : v;
    }
}
