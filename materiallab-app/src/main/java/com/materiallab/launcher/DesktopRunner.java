package com.materiallab.launcher;

import com.materiallab.electronics.ComponentDef;
import com.materiallab.materials.Material;
import com.materiallab.materials.MaterialsRegistry;
import com.materiallab.model.BodyKind;
import com.materiallab.model.Connection;
import com.materiallab.model.ConnectionType;
import com.materiallab.model.ProjectData;
import com.materiallab.model.ProjectObject;
import com.materiallab.model.Vec2;
import com.materiallab.presets.Presets;
import com.materiallab.simulation.ProjectSerializer;
import com.materiallab.simulation.SimulationController;
import com.materiallab.storage.ProjectStorage;
import com.materiallab.undo.SnapshotAction;
import com.materiallab.undo.UndoManager;
import com.materiallab.validation.ProjectValidator;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Desktop editor (Swing). Chosen instead of JavaFX deliberately: the app must
 * run from a single "java -jar materiallab.jar" on any JDK WITHOUT extra
 * modules or SDK downloads (JavaFX is not part of the JDK since v11).
 * The UI renders ONLY the model (ProjectData / SimulationController runtime)
 * and mutates it through UndoManager — no state lives inside Swing classes.
 */
public final class DesktopRunner {

    private final ProjectData project = new ProjectData();
    private final UndoManager undo = new UndoManager();
    private SimulationController sim = new SimulationController(project);
    private Path currentFile;

    // view state (camera/grid are persisted in the project itself)
    private double camX = 0.5, camY = 0.0, zoom = 420;
    private boolean showGrid = true, snapGrid = true;
    private String tool = "cursor";
    private Vec2 pendingPoint;          // wire/weld/measure first point
    private ProjectObject selected;
    private String measureA, measureB;
    private Clipboard kb;   // system clipboard, created lazily (headless-safe)

    private enum ViewMode { EDIT, SIMULATE, READONLY }
    private ViewMode viewMode = ViewMode.EDIT;

    private JFrame frame;
    private CanvasPanel canvas;
    private DefaultListModel<String> objList;
    private JTextArea logArea, issuesArea;
    private JLabel statusLabel, modeLabel;
    private JPanel propsPanel;
    private JTabbedPane rightTabs;
    private javax.swing.Timer uiTimer;

    public static void run(String projectFile) {
        SwingUtilities.invokeLater(() -> new DesktopRunner().show(projectFile));
    }

    /** Headless-safe construction for tests: builds model only. */
    static void smokeNoUi() {
        ProjectData p = new ProjectData();
        p.addObject(new ProjectObject("a", "battery", BodyKind.COMPONENT));
        if (p.objects.size() != 1) throw new IllegalStateException("smoke failed");
    }

    void show(String projectFile) {
        frame = new JFrame("MaterialLab Simulator — настольный редактор");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { confirmExit(); }
        });
        frame.setJMenuBar(buildMenuBar());
        JPanel top = buildToolbar();
        canvas = new CanvasPanel();
        JScrollPane left = buildLeftPanel();
        rightTabs = buildRightPanel();
        JPanel status = buildStatusBar();

        frame.setLayout(new BorderLayout());
        frame.add(top, BorderLayout.NORTH);
        JSplitPane lr = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, canvas);
        lr.setDividerLocation(230);
        JSplitPane rr = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, lr, rightTabs);
        rr.setDividerLocation(760);
        frame.add(rr, BorderLayout.CENTER);
        frame.add(status, BorderLayout.SOUTH);
        frame.setSize(1280, 800);
        frame.setLocationRelativeTo(null);
        installShortcuts();
        frame.setVisible(true);

        if (projectFile != null) openProject(Paths.get(projectFile));
        else refreshAll();

        uiTimer = new javax.swing.Timer(33, e -> {
            if (sim.mode() == SimulationController.Mode.RUNNING && viewMode == ViewMode.SIMULATE) {
                sim.tick();
                updateStatus();
            }
            canvas.repaint();
        });
        uiTimer.start();
    }

    // ================= MENU =================

    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu("Файл");
        file.add(item("Новый проект", e -> newProject()));
        file.add(item("Открыть…", e -> chooseOpen()));
        file.add(item("Сохранить", e -> saveProject()));
        file.add(item("Сохранить как…", e -> chooseSaveAs()));
        file.addSeparator();
        file.add(item("Загрузить пресет…", e -> choosePreset()));
        file.addSeparator();
        file.add(item("Выход", e -> confirmExit()));
        JMenu edit = new JMenu("Правка");
        edit.add(item("Отменить (Ctrl+Z)", e -> doUndo()));
        edit.add(item("Повторить (Ctrl+Y)", e -> doRedo()));
        edit.addSeparator();
        edit.add(item("Копировать (Ctrl+C)", e -> copySelection()));
        edit.add(item("Вставить (Ctrl+V)", e -> paste()));
        edit.add(item("Удалить (Del)", e -> deleteSelected()));
        JMenu view = new JMenu("Вид");
        view.add(item("Приблизить", e -> { zoom *= 1.25; canvas.repaint(); }));
        view.add(item("Отдалить", e -> { zoom /= 1.25; canvas.repaint(); }));
        view.add(item("Сетка вкл/выкл", e -> { showGrid = !showGrid; project.grid.visible = showGrid; }));
        view.add(item("Привязка к сетке", e -> { snapGrid = !snapGrid; project.grid.snap = snapGrid; }));
        JMenu simM = new JMenu("Симуляция");
        simM.add(item("Запуск (F5)", e -> startSim()));
        simM.add(item("Пауза (F6)", e -> sim.pause()));
        simM.add(item("Шаг (F7)", e -> { sim.stepOnce(); enterSimView(); }));
        simM.add(item("Стоп (F8)", e -> stopSim()));
        simM.add(item("Сброс (Ctrl+F5)", e -> { sim.resetRuntime(); enterSimView(); }));
        JMenu tools = new JMenu("Инструменты");
        for (String[] t : new String[][]{
            {"Курсор", "cursor"}, {"Провод", "wire"}, {"Верёвка", "rope"}, {"Сварка", "weld"},
            {"Разварить", "cut"}, {"Шарнир", "hinge"}, {"Жёсткое соединение", "stiff"},
            {"Пружина", "spring"}, {"Крепление", "anchor"}, {"Измерение", "measure"}}) {
            tools.add(item(t[0], e -> setTool(t[1])));
        }
        JMenu check = new JMenu("Проверка");
        check.add(item("Проверить схему (Ctrl+B)", e -> validate()));
        JMenu help = new JMenu("Справка");
        help.add(item("О программе", e -> JOptionPane.showMessageDialog(frame,
            "MaterialLab Simulator v0.1.0\nКонструктор электронных и механических систем.\n"
          + "Значения свойств материалов и параметров компонентов — инженерные приближения,\n"
          + "если не помечены как справочные данные.", "О программе", JOptionPane.INFORMATION_MESSAGE)));
        mb.add(file); mb.add(edit); mb.add(view); mb.add(simM); mb.add(tools); mb.add(check); mb.add(help);
        return mb;
    }

    private JMenuItem item(String text, java.awt.event.ActionListener l) {
        JMenuItem m = new JMenuItem(text);
        m.addActionListener(l);
        return m;
    }

    // ================= TOOLBAR =================

    private JPanel buildToolbar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        p.setBackground(new Color(0x262b34));
        JComboBox<String> comps = new JComboBox<>();
        for (ComponentDef d : com.materiallab.electronics.ComponentsCatalog.get().all())
            comps.addItem(d.id + " — " + d.nameRu);
        JButton addBtn = new JButton("+ Компонент");
        addBtn.addActionListener(e -> {
            String sel2 = (String) comps.getSelectedItem();
            if (sel2 == null) return;
            String id = sel2.split(" — ")[0];
            ComponentDef def = com.materiallab.electronics.ComponentsCatalog.get().get(id);
            addComponent(def);
        });
        JButton presetBtn = new JButton("Пресет…");
        presetBtn.addActionListener(e -> choosePreset());
        JButton play = new JButton("▶ Пуск"); play.addActionListener(e -> startSim());
        JButton pause = new JButton("⏸ Пауза"); pause.addActionListener(e -> sim.pause());
        JButton step = new JButton("⏭ Шаг"); step.addActionListener(e -> { sim.stepOnce(); enterSimView(); });
        JButton stop = new JButton("⏹ Стоп"); stop.addActionListener(e -> stopSim());
        JButton editMode = new JButton("✎ Редактирование");
        editMode.addActionListener(e -> { viewMode = ViewMode.EDIT; modeLabel.setText("Режим: редактирование"); });
        JButton roMode = new JButton("👁 Просмотр");
        roMode.addActionListener(e -> { viewMode = ViewMode.READONLY; modeLabel.setText("Режим: просмотр"); });
        JButton validateBtn = new JButton("✓ Проверка"); validateBtn.addActionListener(e -> validate());
        JButton saveBtn = new JButton("💾 Сохранить"); saveBtn.addActionListener(e -> saveProject());
        JButton openBtn = new JButton("📂 Открыть"); openBtn.addActionListener(e -> chooseOpen());
        p.add(comps);
        for (JButton b : new JButton[]{addBtn, presetBtn, play, pause, step, stop, editMode, roMode, validateBtn, saveBtn, openBtn}) {
            b.setForeground(new Color(0xdfe4ea));
            b.setBackground(new Color(0x323947));
            p.add(b);
        }
        return p;
    }

    // ================= LEFT PANEL =================

    private JScrollPane buildLeftPanel() {
        objList = new DefaultListModel<>();
        JList<String> list = new JList<>(objList);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.addListSelectionListener(e -> {
            int i = list.getSelectedIndex();
            if (i >= 0 && i < project.objects.size()) { select(project.objects.get(i)); }
        });
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(new JLabel(" Объекты проекта:"), BorderLayout.NORTH);
        wrap.add(new JScrollPane(list), BorderLayout.CENTER);

        DefaultComboBoxModel<String> matModel = new DefaultComboBoxModel<>();
        for (Material m : MaterialsRegistry.get().all().values()) matModel.addElement(m.id + " — " + m.nameRu);
        JComboBox<String> mats = new JComboBox<>(matModel);
        JButton applyMat = new JButton("Назначить материал выбранному");
        applyMat.addActionListener(e -> {
            if (selected == null) return;
            String id = ((String) mats.getSelectedItem()).split(" — ")[0];
            mutateAndRecord("материал " + id, () -> selected.materialId = id);
        });
        JPanel south = new JPanel(new BorderLayout());
        south.add(mats, BorderLayout.NORTH);
        south.add(applyMat, BorderLayout.SOUTH);
        wrap.add(south, BorderLayout.SOUTH);
        wrap.setPreferredSize(new Dimension(230, 400));
        return new JScrollPane(wrap);
    }

    // ================= RIGHT PANEL =================

    private JTabbedPane buildRightPanel() {
        propsPanel = new JPanel();
        logArea = new JTextArea(); logArea.setEditable(false);
        issuesArea = new JTextArea(); issuesArea.setEditable(false);
        JTextArea planArea = new JTextArea(
            "ПЛАН ПРОЕКТА (см. PROJECT_PLAN.md)\n"
          + "[DONE] Каркас Maven, модель проекта, сериализация .mlab\n"
          + "[DONE] Физика (собственный движок), DC-решатель, сварка\n"
          + "[DONE] Серверный режим + веб-UI\n"
          + "[IN_PROGRESS] Настольный редактор (этот экран)\n"
          + "[TODO] Полигоны, пользовательские модули, скрипты");
        planArea.setEditable(false);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Свойства", new JScrollPane(propsPanel));
        tabs.addTab("Лог симуляции", new JScrollPane(logArea));
        tabs.addTab("Ошибки", new JScrollPane(issuesArea));
        tabs.addTab("План проекта", new JScrollPane(planArea));
        tabs.setPreferredSize(new Dimension(300, 400));
        return tabs;
    }

    private JPanel buildStatusBar() {
        JPanel p = new JPanel(new BorderLayout());
        statusLabel = new JLabel(" Готово");
        modeLabel = new JLabel("Режим: редактирование   ");
        p.add(statusLabel, BorderLayout.CENTER);
        p.add(modeLabel, BorderLayout.EAST);
        return p;
    }

    // ================= ACTIONS =================

    private interface Mutation { void apply(); }

    private void mutateAndRecord(String description, Mutation m) {
        SnapshotAction act = SnapshotAction.begin(project, description);
        m.apply();
        undo.record(act.seal());
        sim = new SimulationController(project);
        if (currentFile != null) StartWindow.rememberProject(currentFile);
        refreshAll();
    }

    private void addComponent(ComponentDef def) {
        ProjectObject o = com.materiallab.server.MaterialLabServer.newObjectFromDef(def, camX, camY);
        mutateAndRecord("добавить " + def.nameRu, () -> project.addObject(o));
        select(o);
    }

    private void select(ProjectObject o) {
        selected = o;
        renderProps();
        statusLabel.setText("Выбрано: " + (o == null ? "нет" : o.name + " [" + o.id + "]")
            + String.format("  координаты: (%.2f, %.2f) м", o.pos.x, o.pos.y));
    }

    private void deleteSelected() {
        if (selected == null || viewMode != ViewMode.EDIT) return;
        String id = selected.id;
        mutateAndRecord("удалить " + selected.name, () -> project.removeObject(id));
        selected = null; renderProps();
    }

    private void copySelection() {
        if (GraphicsEnvironment.isHeadless()) return;
        if (kb == null) kb = Toolkit.getDefaultToolkit().getSystemClipboard();
        if (selected != null)
            kb.setContents(new StringSelection(ProjectStorage.toJson(singleProject(selected))), null);
    }

    private ProjectData singleProject(ProjectObject o) {
        ProjectData one = ProjectSerializer.copy(project);
        one.objects.clear(); one.connections.clear();
        one.objects.add(ProjectSerializer.copy(project).byId(o.id));
        return one;
    }

    private void paste() {
        if (GraphicsEnvironment.isHeadless()) return;
        if (kb == null) kb = Toolkit.getDefaultToolkit().getSystemClipboard();
        try {
            String s = (String) kb.getData(DataFlavor.stringFlavor);
            ProjectData one = ProjectStorage.fromJson(s);
            if (one.objects.isEmpty()) return;
            ProjectObject src = one.objects.get(0);
            ProjectObject copy = ProjectSerializer.copy(one).objects.get(0);
            copy.id = project.newId(src.typeId);
            copy.pos = new Vec2(src.pos.x + 0.05, src.pos.y - 0.05);
            mutateAndRecord("вставить " + copy.name, () -> project.addObject(copy));
            select(copy);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Буфер обмена не содержит объекта .mlab", "Вставка", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void doUndo() {
        String d = undo.undo();
        if (d != null) { sim = new SimulationController(project); refreshAll(); statusLabel.setText("Отменено: " + d); }
        canvas.repaint();
    }

    private void doRedo() {
        String d = undo.redo();
        if (d != null) { sim = new SimulationController(project); refreshAll(); statusLabel.setText("Повтор: " + d); }
        canvas.repaint();
    }

    private void startSim() {
        sim.start();          // physics is stepped by the UI timer loop below
        enterSimView();       // (SimulationController owns all simulation state)
    }

    private void stopSim() {
        sim.stop();
        viewMode = ViewMode.EDIT;
        modeLabel.setText("Режим: редактирование");
        canvas.repaint();
    }

    private void enterSimView() {
        viewMode = ViewMode.SIMULATE;
        modeLabel.setText("Режим: симуляция (" + sim.mode() + ")");
        StringBuilder sb = new StringBuilder();
        for (String s : sim.log()) sb.append(s).append('\n');
        logArea.setText(sb.toString());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void validate() {
        var issues = new ProjectValidator().validate(project);
        StringBuilder sb = new StringBuilder();
        for (var i : issues) sb.append(i).append("\n  подсказка: ").append(i.hint).append("\n\n");
        issuesArea.setText(sb.length() == 0 ? "Ошибок не найдено ✔" : sb.toString());
        rightTabs.setSelectedIndex(2);
        statusLabel.setText("Проверка: " + issues.size() + " замечаний");
        canvas.repaint();
    }

    // ---------- file ops ----------

    private void newProject() {
        project.objects.clear(); project.connections.clear();
        undo.clear(); sim = new SimulationController(project);
        currentFile = null; selected = null;
        refreshAll();
    }

    private void chooseOpen() {
        JFileChooser fc = userChooser();
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION)
            openProject(fc.getSelectedFile().toPath());
    }

    private void openProject(Path f) {
        try {
            ProjectData loaded = ProjectStorage.load(f);
            loadInto(loaded);
            currentFile = f;
            statusLabel.setText("Открыт: " + f);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Не удалось открыть: " + ex.getMessage(),
                "Ошибка загрузки", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadInto(ProjectData loaded) {
        project.objects = loaded.objects;
        project.connections = loaded.connections;
        project.customMaterials = loaded.customMaterials;
        project.customComponents = loaded.customComponents;
        project.modules = loaded.modules;
        project.planTasks = loaded.planTasks;
        project.simulation = loaded.simulation;
        project.camera = loaded.camera;
        project.grid = loaded.grid;
        camX = project.camera.centerX; camY = project.camera.centerY; zoom = project.camera.zoom;
        showGrid = project.grid.visible; snapGrid = project.grid.snap;
        sim = new SimulationController(project);
        undo.clear();
        refreshAll();
    }

    private void saveProject() {
        if (currentFile == null) { chooseSaveAs(); return; }
        doSave(currentFile);
    }

    private void chooseSaveAs() {
        JFileChooser fc = userChooser();
        if (fc.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            Path f = fc.getSelectedFile().toPath();
            if (!f.toString().endsWith(".mlab")) f = Paths.get(f + ".mlab");
            doSave(f);
            currentFile = f;
        }
    }

    private void doSave(Path f) {
        try {
            project.camera.centerX = camX; project.camera.centerY = camY; project.camera.zoom = zoom;
            project.metadata.put("lastSavedBy", "desktop");
            ProjectStorage.save(f, project);
            StartWindow.rememberProject(f);
            statusLabel.setText("Сохранено: " + f);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Ошибка сохранения: " + ex.getMessage(),
                "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private JFileChooser userChooser() {
        JFileChooser fc = new JFileChooser(StartWindow.userHome());
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("MaterialLab (*.mlab)", "mlab"));
        return fc;
    }

    private void choosePreset() {
        String[] names = Presets.list().stream().map(p -> p.file).toArray(String[]::new);
        String chosen = (String) JOptionPane.showInputDialog(frame, "Пресет:", "Пресеты",
            JOptionPane.PLAIN_MESSAGE, null, names, names[0]);
        if (chosen == null) return;
        try {
            loadInto(Presets.loadBuiltin(chosen));
            statusLabel.setText("Загружен пресет: " + chosen);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Повреждённый пресет: " + ex.getMessage());
        }
    }

    private void confirmExit() {
        int r = JOptionPane.showConfirmDialog(frame, "Выйти из MaterialLab?", "Выход", JOptionPane.YES_NO_OPTION);
        if (r == JOptionPane.YES_OPTION) System.exit(0);
    }

    private void refreshAll() {
        objList.clear();
        for (ProjectObject o : project.objects)
            objList.addElement(o.name + " [" + o.id + "] @ " + o.pos);
        renderProps();
        canvas.repaint();
    }

    private void renderProps() {
        propsPanel.removeAll();
        propsPanel.setLayout(new BoxLayout(propsPanel, BoxLayout.Y_AXIS));
        if (selected == null) {
            propsPanel.add(new JLabel("— выберите объект —"));
        } else {
            propsPanel.add(new JLabel("Имя: " + selected.name));
            propsPanel.add(new JLabel("Тип: " + selected.typeId + " (" + selected.kind + ")"));
            propsPanel.add(new JLabel("Материал: " + selected.materialId));
            propsPanel.add(new JLabel(String.format("Позиция: (%.3f, %.3f) м", selected.pos.x, selected.pos.y)));
            propsPanel.add(new JLabel(String.format("Поворот: %.1°", Math.toDegrees(selected.rotation))));
            propsPanel.add(new JLabel(String.format("Размер: %.3f × %.3f м", selected.width, selected.height)));
            propsPanel.add(new JLabel("Масса: " + selected.mass() + " кг"));
            propsPanel.add(new JLabel("--- Параметры ---"));
            selected.params.forEach((k, v) -> propsPanel.add(new JLabel(k + " = " + v)));
            propsPanel.add(new JLabel("--- Состояние ---"));
            selected.state.forEach((k, v) -> propsPanel.add(new JLabel(k + " = " + v)));
            JButton rotBtn = new JButton("Повернуть на 15°");
            rotBtn.addActionListener(e -> mutateAndRecord("поворот", () -> selected.rotation += Math.toRadians(15)));
            propsPanel.add(rotBtn);
            JButton flip = new JButton("Отразить по X");
            flip.addActionListener(e -> mutateAndRecord("отражение", () -> selected.pos.x = 2 * camX - selected.pos.x));
            propsPanel.add(flip);
        }
        propsPanel.revalidate();
    }

    private void updateStatus() {
        modeLabel.setText("Режим: симуляция | t=" + String.format("%.2fс", sim.time())
            + " | шаг " + sim.tickCount() + " | " + sim.mode());
    }

    // ================= SHORTCUTS =================

    private void installShortcuts() {
        KeyStroke ks;
        bind(ks = KeyStroke.getKeyStroke("control Z"), "undo", e -> doUndo());
        bind(KeyStroke.getKeyStroke("control Y"), "redo", e -> doRedo());
        bind(KeyStroke.getKeyStroke("DELETE"), "delete", e -> deleteSelected());
        bind(KeyStroke.getKeyStroke("control C"), "copy", e -> copySelection());
        bind(KeyStroke.getKeyStroke("control V"), "paste", e -> paste());
        bind(KeyStroke.getKeyStroke("control S"), "save", e -> saveProject());
        bind(KeyStroke.getKeyStroke("control O"), "open", e -> chooseOpen());
        bind(KeyStroke.getKeyStroke("F5"), "simstart", e -> startSim());
        bind(KeyStroke.getKeyStroke("F6"), "simpause", e -> sim.pause());
        bind(KeyStroke.getKeyStroke("F7"), "simstep", e -> { sim.stepOnce(); enterSimView(); });
        bind(KeyStroke.getKeyStroke("F8"), "simstop", e -> stopSim());
        bind(KeyStroke.getKeyStroke("control B"), "validate", e -> validate());
        bind(KeyStroke.getKeyStroke("ESCAPE"), "canceltool", e -> { tool = "cursor"; pendingPoint = null; measureA = measureB = null; });
    }

    private void bind(KeyStroke ks, String name, java.awt.event.ActionListener l) {
        InputMap im = frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = frame.getRootPane().getActionMap();
        im.put(ks, name);
        am.put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { l.actionPerformed(e); }
        });
    }

    private void setTool(String t) {
        tool = t; pendingPoint = null;
        if (t.equals("cut")) cutAtSelection();
        statusLabel.setText("Инструмент: " + t);
    }

    private void cutAtSelection() {
        if (selected == null) return;
        Connection c = null;
        for (Connection cc : project.connections)
            if (cc.aObj.equals(selected.id) || cc.bObj.equals(selected.id)) { c = cc; break; }
        if (c != null) {
            String id = c.id;
            mutateAndRecord("разварить " + id, () -> project.cutWeld(id));
        }
    }

    // ================= RENDERING =================

    final class CanvasPanel extends JPanel {

        CanvasPanel() {
            setBackground(new Color(0x171a20));
            MouseAdapter ma = new MouseAdapter() {
                double dragX, dragY; ProjectObject dragObj; boolean panning;
                double prevX, prevY;
                ProjectData pressSnapshot;   // model state captured at drag start (for Undo)

                @Override public void mousePressed(MouseEvent e) {
                    double[] w = CanvasPanel.this.toWorld(e.getX(), e.getY());
                    if (SwingUtilities.isRightMouseButton(e)) { CanvasPanel.this.popup(e, w); return; }
                    if (tool.equals("wire") || tool.equals("rope") || tool.equals("weld") || tool.equals("measure")) {
                        Vec2 p = CanvasPanel.this.snapped(w);
                        if (pendingPoint == null) {
                            pendingPoint = p;
                            if (tool.equals("measure")) { measureA = fmt(p); measureB = null; }
                        } else {
                            CanvasPanel.this.finishTwoPointTool(p);
                        }
                        return;
                    }
                    ProjectObject hit = CanvasPanel.this.pick(w[0], w[1]);
                    if (hit != null) {
                        select(hit);
                        if (viewMode == ViewMode.EDIT) {
                            dragObj = hit; dragX = w[0] - hit.pos.x; dragY = w[1] - hit.pos.y;
                            pressSnapshot = ProjectSerializer.copy(project); // undo: state before the move
                            prevX = hit.pos.x; prevY = hit.pos.y;
                        }
                    } else {
                        panning = true; dragX = e.getX(); dragY = e.getY();
                        select(null);
                    }
                }

                @Override public void mouseDragged(MouseEvent e) {
                    if (panning) {
                        camX -= (e.getX() - dragX) / zoom;
                        camY += (e.getY() - dragY) / zoom;
                        dragX = e.getX(); dragY = e.getY();
                        CanvasPanel.this.repaint();
                    } else if (dragObj != null && viewMode == ViewMode.EDIT) {
                        double[] w = CanvasPanel.this.toWorld(e.getX(), e.getY());
                        Vec2 p = CanvasPanel.this.snapped(w);
                        dragObj.pos.set(p.x, p.y);
                        if (dragObj.points != null) shiftPoints(dragObj, p.x - prevX, p.y - prevY);
                        prevX = p.x; prevY = p.y;
                        CanvasPanel.this.repaint();
                    }
                }

                @Override public void mouseReleased(MouseEvent e) {
                    if (dragObj != null && pressSnapshot != null) {
                        undo.record(SnapshotAction.of(project, pressSnapshot,
                            ProjectSerializer.copy(project), "переместить " + dragObj.name));
                        pressSnapshot = null;
                        sim = new SimulationController(project);
                        refreshAll();
                    }
                    dragObj = null; panning = false;
                }

                @Override public void mouseWheelMoved(MouseWheelEvent e) {
                    zoom *= e.getPreciseWheelRotation() > 0 ? 0.9 : 1.1;
                    CanvasPanel.this.repaint();
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addMouseWheelListener(ma);
        }

        private void shiftPoints(ProjectObject o, double dx, double dy) {
            if (o.points == null) return;
            for (Vec2 p : o.points) p.set(p.x + dx, p.y + dy);
        }

        private void popup(MouseEvent e, double[] w) {
            JPopupMenu pm = new JPopupMenu();
            ProjectObject hit = pick(w[0], w[1]);
            if (hit != null) {
                JMenuItem del = new JMenuItem("Удалить");
                del.addActionListener(a -> { select(hit); deleteSelected(); });
                JMenuItem dup = new JMenuItem("Дублировать");
                dup.addActionListener(a -> {
                    ProjectObject c = ProjectSerializer.copy(project).byId(hit.id);
                    c.id = project.newId(hit.typeId);
                    c.pos = new Vec2(hit.pos.x + 0.05, hit.pos.y);
                    mutateAndRecord("дублировать", () -> project.addObject(c));
                });
                pm.add(del); pm.add(dup);
            }
            JMenuItem addBat = new JMenuItem("Добавить ⮜ Батарейку здесь");
            addBat.addActionListener(a -> {
                ComponentDef def = com.materiallab.electronics.ComponentsCatalog.get().get("battery");
                ProjectObject o = com.materiallab.server.MaterialLabServer.newObjectFromDef(def, w[0], w[1]);
                mutateAndRecord("добавить батарейку", () -> project.addObject(o));
            });
            pm.add(addBat);
            pm.show(this, e.getX(), e.getY());
        }

        private Vec2 snapped(double[] w) {
            double g = project.grid.size;
            if (snapGrid) return new Vec2(Math.round(w[0] / g) * g, Math.round(w[1] / g) * g);
            return new Vec2(w[0], w[1]);
        }

        private void finishTwoPointTool(Vec2 end) {
            Vec2 start = pendingPoint;
            pendingPoint = null;
            switch (tool) {
                case "wire" -> {
                    ProjectObject w = ProjectSerializer.makeWire(project.newId("wire"), start, end, "copper");
                    w.name = "Провод";
                    mutateAndRecord("провод", () -> project.addObject(w));
                }
                case "rope" -> {
                    ProjectObject r = ProjectSerializer.makeRope(project.newId("rope"), start, end);
                    mutateAndRecord("верёвка", () -> project.addObject(r));
                }
                case "weld" -> {
                    ProjectObject a = pick(start.x, start.y), b = pick(end.x, end.y);
                    if (a != null && b != null && a != b) {
                        ConnectionType t = (a.kind == BodyKind.WIRE || b.kind == BodyKind.WIRE)
                            ? ConnectionType.ELECTRICAL : ConnectionType.MECHANICAL;
                        try {
                            mutateAndRecord("сварка", () -> {
                                Connection c = project.weld(a.id, portNear(a, start), b.id, portNear(b, end), t);
                                c.strength = ProjectValidator.weldStrengthFactor(a, b, c.strength);
                            });
                        } catch (IllegalArgumentException ex) {
                            JOptionPane.showMessageDialog(frame, ex.getMessage());
                        }
                    } else {
                        JOptionPane.showMessageDialog(frame,
                            "Сварка: укажите две точки на ДВУХ разных объектах (или объекте и проводе).",
                            "Сварка", JOptionPane.WARNING_MESSAGE);
                    }
                }
                case "measure" -> {
                    measureB = fmt(end);
                    double dist = start.dist(end);
                    statusLabel.setText(String.format("Измерение: %.4f м (%.1f см)", dist, dist * 100));
                    measureA = fmt(start);
                }
                default -> {}
            }
            tool = "cursor";
        }

        private String fmt(Vec2 p) { return String.format("(%.3f;%.3f)", p.x, p.y); }

        private String portNear(ProjectObject o, Vec2 w) {
            ComponentDef def = com.materiallab.electronics.ComponentsCatalog.get().get(o.typeId);
            if (def == null || def.ports.isEmpty()) return o.kind == BodyKind.WIRE ? (w.x < o.pos.x ? "a" : "b") : "p1";
            String best = def.ports.get(0).id;
            double bd = Double.MAX_VALUE;
            for (var pd : def.ports) {
                double px = o.pos.x + pd.localX, py = o.pos.y + pd.localY;
                double d = Math.hypot(px - w.x, py - w.y);
                if (d < bd) { bd = d; best = pd.id; }
            }
            return best;
        }

        private ProjectObject pick(double wx, double wy) {
            for (int i = project.objects.size() - 1; i >= 0; i--) {
                ProjectObject o = project.objects.get(i);
                if (o.points != null) {
                    for (Vec2 p : o.points)
                        if (Math.hypot(p.x - wx, p.y - wy) < 0.02) return o;
                    continue;
                }
                if (Math.abs(wx - o.pos.x) <= o.width / 2 && Math.abs(wy - o.pos.y) <= o.height / 2) return o;
            }
            return null;
        }

        double[] toWorld(int sx, int sy) {
            return new double[]{(sx - getWidth() / 2.0) / zoom + camX, (getHeight() / 2.0 - sy) / zoom + camY};
        }

        double[] toScreen(double wx, double wy) {
            return new double[]{(wx - camX) * zoom + getWidth() / 2.0, getHeight() / 2.0 - (wy - camY) * zoom};
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int W = getWidth(), H = getHeight();
            if (showGrid) drawGrid(g, W, H);
            // ground
            double[] gy = toScreen(0, -0.5);
            g.setColor(new Color(0x3a4150)); g.setStroke(new BasicStroke(2));
            g.drawLine(0, (int) gy[1], W, (int) gy[1]);
            // connections (welds) under objects
            for (Connection c : project.connections) drawConnection(g, c);
            // wires & ropes
            for (ProjectObject o : project.objects) {
                if (o.points == null) continue;
                boolean broken = o.state.getOrDefault("broken", 0.0) > 0.5;
                g.setColor(broken ? new Color(0xff5b5b) : (o.kind == BodyKind.WIRE ? new Color(0xd9863d) : new Color(0x9aa5a0)));
                g.setStroke(new BasicStroke(o.kind == BodyKind.WIRE ? 2.5f : 4f));
                for (int i = 1; i < o.points.size(); i++) {
                    double[] p1 = toScreen(o.points.get(i - 1).x, o.points.get(i - 1).y);
                    double[] p2 = toScreen(o.points.get(i).x, o.points.get(i).y);
                    g.drawLine((int) p1[0], (int) p1[1], (int) p2[0], (int) p2[1]);
                }
                for (Vec2 p : new Vec2[]{o.points.get(0), o.points.get(o.points.size() - 1)}) {
                    double[] s = toScreen(p.x, p.y);
                    boolean welded = project.connections.stream().anyMatch(c -> c.aObj.equals(o.id) || c.bObj.equals(o.id));
                    g.setColor(welded ? new Color(0x5bd45b) : new Color(0x8a93a6));
                    g.fillOval((int) s[0] - 4, (int) s[1] - 4, 8, 8);
                }
            }
            // components
            for (ProjectObject o : project.objects) {
                if (o.points != null) continue;
                drawComponent(g, o);
            }
            // measurement line
            if (measureA != null && measureB != null) {
                g.setColor(Color.YELLOW);
                g.drawString("|" + measureA + " → " + measureB + "|", 20, 20);
            }
            if (pendingPoint != null) {
                double[] s = toScreen(pendingPoint.x, pendingPoint.y);
                g.setColor(Color.CYAN);
                g.fillOval((int) s[0] - 3, (int) s[1] - 3, 6, 6);
            }
            if (selected != null && selected.points == null) {
                double[] s = toScreen(selected.pos.x, selected.pos.y);
                g.setColor(Color.CYAN);
                g.drawRect((int) (s[0] - selected.width * zoom / 2) - 3, (int) (s[1] - selected.height * zoom / 2) - 3,
                    (int) (selected.width * zoom) + 6, (int) (selected.height * zoom) + 6);
            }
            g.setColor(new Color(0x8a93a6));
            g.setFont(getFont().deriveFont(11f));
            g.drawString("инструмент: " + tool + " | ЛКМ — выбор/перенос, ПКМ — меню, колесо — зум", 10, H - 10);
        }

        private void drawGrid(Graphics2D g, int W, int H) {
            g.setColor(new Color(0x232833));
            double gs = project.grid.size;
            double x0 = camX - W / 2.0 / zoom, x1 = camX + W / 2.0 / zoom;
            double y0 = camY - H / 2.0 / zoom, y1 = camY + H / 2.0 / zoom;
            for (double x = Math.floor(x0 / gs) * gs; x <= x1; x += gs) {
                double[] s = toScreen(x, 0);
                g.drawLine((int) s[0], 0, (int) s[0], H);
            }
            for (double y = Math.floor(y0 / gs) * gs; y <= y1; y += gs) {
                double[] s = toScreen(0, y);
                g.drawLine(0, (int) s[1], W, (int) s[1]);
            }
        }

        private void drawConnection(Graphics2D g, Connection c) {
            ProjectObject a = project.byId(c.aObj), b = project.byId(c.bObj);
            if (a == null || b == null) return;
            double[] pa = toScreen(a.pos.x, a.pos.y), pb = toScreen(b.pos.x, b.pos.y);
            g.setColor(c.welded ? new Color(0xffb020) : new Color(0x556070));
            g.setStroke(new BasicStroke(c.welded ? 2f : 1f));
            g.drawLine((int) pa[0], (int) pa[1], (int) pb[0], (int) pb[1]);
            if (c.welded) {
                int mx = (int) (pa[0] + pb[0]) / 2, my = (int) (pa[1] + pb[1]) / 2;
                g.fillOval(mx - 4, my - 4, 8, 8); // weld bead marker
            }
        }

        private void drawComponent(Graphics2D g, ProjectObject o) {
            double[] s = toScreen(o.pos.x, o.pos.y);
            int w = Math.max(8, (int) (o.width * zoom)), h = Math.max(8, (int) (o.height * zoom));
            var mat = MaterialsRegistry.get().get(o.materialId);
            g.setColor(new Color(mat.colorRgb));
            g.fillRect((int) s[0] - w / 2, (int) s[1] - h / 2, w, h);
            g.setColor(o == selected ? Color.CYAN : Color.WHITE);
            g.setStroke(new BasicStroke(1.5f));
            g.drawRect((int) s[0] - w / 2, (int) s[1] - h / 2, w, h);
            // state indicators
            double bright = o.state.getOrDefault("brightness", 0.0);
            if (bright > 0.05) {
                g.setColor(new Color(0xffee66));
                g.fillOval((int) s[0] - 3, (int) s[1] - h / 2 - 8, 6, 6);
            }
            if (o.state.getOrDefault("fault", 0.0) > 0.5) {
                g.setColor(Color.RED);
                g.drawString("✕", (int) s[0] - 4, (int) s[1] - h / 2 - 2);
            }
            if (o.fixed) {
                g.setColor(Color.GRAY);
                g.drawLine((int) s[0] - w / 2, (int) s[1] + h / 2 + 4, (int) s[0] + w / 2, (int) s[1] + h / 2 + 4);
            }
            g.setColor(new Color(0xdfe4ea));
            g.setFont(getFont().deriveFont(10f));
            g.drawString(o.name, (int) s[0] - w / 2, (int) s[1] + h / 2 + 14);
        }
    }

    /** Show validation issues overlay after validate(): reuse repaint. */
    void repaintCanvas() { canvas.repaint(); }
}
