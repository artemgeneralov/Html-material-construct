package com.materiallab.launcher;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.prefs.Preferences;

/**
 * Start window shown when the JAR runs without --mode (spec section 2):
 *  - Запустить настольное приложение
 *  - Запустить локальный сервер
 *  - Настройки
 *  - Открыть последний проект
 *  - Выход
 */
public final class StartWindow {

    private static final Preferences PREFS = Preferences.userNodeForPackage(StartWindow.class);
    private static final String KEY_LAST = "lastProject";
    private static final String KEY_PORT = "port";

    public static File userHome() {
        File home = new File(System.getProperty("user.home"), "materiallab");
        if (!home.exists()) home.mkdirs();
        return home;
    }

    public static void show(int defaultPort, String projectArg) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("MaterialLab Simulator — запуск");
            f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            JPanel p = new JPanel(new GridBagLayout());
            p.setPreferredSize(new Dimension(430, 330));
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = 0; c.insets = new Insets(6, 10, 6, 10); c.fill = GridBagConstraints.HORIZONTAL;

            JLabel title = new JLabel("⚛ MaterialLab Simulator", SwingConstants.CENTER);
            title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
            p.add(title, c); c.gridy++;

            JButton desktop = new JButton("Запустить настольное приложение");
            JButton server = new JButton("Запустить локальный сервер (порт " + port(defaultPort) + ")");
            JButton settings = new JButton("Настройки");
            String last = PREFS.get(KEY_LAST, projectArg);
            JButton openLast = new JButton("Открыть последний проект" + (last == null ? "" : ": " + new File(last).getName()));
            openLast.setEnabled(last != null && new File(last).exists());
            JButton exit = new JButton("Выход");

            for (JButton b : new JButton[]{desktop, server, settings, openLast, exit}) {
                p.add(b, c); c.gridy++;
            }

            desktop.addActionListener(e -> { f.dispose(); DesktopRunner.run(null); });
            server.addActionListener(e -> {
                int port = port(defaultPort);
                try {
                    ServerRunner.startNonBlocking(port, null);
                    JOptionPane.showMessageDialog(f,
                        "Сервер запущен: http://localhost:" + port + "\nОткройте этот адрес в браузере.",
                        "Локальный сервер", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(f, "Не удалось запустить сервер: " + ex.getMessage(),
                        "Ошибка", JOptionPane.ERROR_MESSAGE);
                }
            });
            settings.addActionListener(e -> showSettings(f, defaultPort));
            openLast.addActionListener(e -> {
                String path = PREFS.get(KEY_LAST, null);
                if (path != null && new File(path).exists()) { f.dispose(); DesktopRunner.run(path); }
                else JOptionPane.showMessageDialog(f, "Последний проект не найден.");
            });
            exit.addActionListener(e -> f.dispose());

            f.add(p);
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }

    private static int port(int fallback) {
        return PREFS.getInt(KEY_PORT, fallback);
    }

    private static void showSettings(JFrame parent, int defaultPort) {
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        JTextField portField = new JTextField(String.valueOf(port(defaultPort)));
        JTextField dirField = new JTextField(userHome().getAbsolutePath());
        form.add(new JLabel("Порт сервера:")); form.add(portField);
        form.add(new JLabel("Папка данных:")); form.add(dirField);
        int r = JOptionPane.showConfirmDialog(parent, form, "Настройки",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r == JOptionPane.OK_OPTION) {
            try { PREFS.putInt(KEY_PORT, Integer.parseInt(portField.getText().trim())); }
            catch (NumberFormatException ignored) {}
            PREFS.put("dataDir", dirField.getText().trim());
        }
    }

    /** Called by editor after save to remember the project (spec 2 «Открыть последний проект»). */
    public static void rememberProject(Path file) {
        PREFS.put(KEY_LAST, file.toAbsolutePath().toString());
    }
}
