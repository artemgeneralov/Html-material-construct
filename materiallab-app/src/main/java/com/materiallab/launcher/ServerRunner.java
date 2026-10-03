package com.materiallab.launcher;

import com.materiallab.model.ProjectData;
import com.materiallab.server.MaterialLabServer;
import com.materiallab.storage.ProjectStorage;
import java.io.File;
import java.nio.file.Path;

/** Starts the embedded HTTP server; loads --project if given. */
public final class ServerRunner {

    public static MaterialLabServer run(int port, String projectFile) {
        try {
            MaterialLabServer server = new MaterialLabServer(port);
            if (projectFile != null) {
                File f = new File(projectFile);
                if (f.exists()) {
                    ProjectData p = ProjectStorage.load(f.toPath());
                    server.setProject(p);
                    System.out.println("[MaterialLab] загружен проект: " + f);
                } else System.err.println("[MaterialLab] файл проекта не найден: " + projectFile);
            }
            server.start();
            // keep JVM alive
            Thread.currentThread().join();
            return server;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            System.err.println("[MaterialLab] ошибка запуска сервера: " + e.getMessage());
            return null;
        }
    }

    /** Non-blocking variant used by the start window / tests. */
    public static MaterialLabServer startNonBlocking(int port, Path project) throws Exception {
        MaterialLabServer server = new MaterialLabServer(port);
        if (project != null && project.toFile().exists())
            server.setProject(ProjectStorage.load(project));
        server.start();
        return server;
    }
}
