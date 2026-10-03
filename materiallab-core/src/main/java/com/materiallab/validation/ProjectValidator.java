package com.materiallab.validation;

import com.materiallab.electronics.ComponentDef;
import com.materiallab.electronics.ComponentsCatalog;
import com.materiallab.materials.Material;
import com.materiallab.materials.MaterialsRegistry;
import com.materiallab.model.BodyKind;
import com.materiallab.model.Connection;
import com.materiallab.model.ConnectionType;
import com.materiallab.model.ProjectData;
import com.materiallab.model.ProjectObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Static project validation (spec section 15). Returns a list of issues with
 * severity, object id and coordinates so the UI can show them on-scene.
 */
public final class ProjectValidator {

    public enum Severity { ERROR, WARNING, INFO }

    public static final class Issue {
        public final Severity severity;
        public final String code;       // machine-readable, stable
        public final String message;    // human-readable (RU)
        public final String objectId;   // may be null
        public final double x, y;
        public final String hint;       // possible fix

        public Issue(Severity s, String code, String msg, String objId, double x, double y, String hint) {
            severity = s; this.code = code; message = msg; objectId = objId;
            this.x = x; this.y = y; this.hint = hint;
        }

        @Override public String toString() {
            return "[" + severity + "] " + code + ": " + message
                 + (objectId != null ? " (" + objectId + ")" : "");
        }
    }

    public List<Issue> validate(ProjectData p) {
        List<Issue> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        boolean hasSource = false;

        for (ProjectObject o : p.objects) {
            if (!ids.add(o.id))
                out.add(new Issue(Severity.ERROR, "DUP_ID", "Дубликат идентификатора " + o.id, o.id, o.pos.x, o.pos.y,
                        "Удалите один из объектов"));
            if (o.kind == BodyKind.COMPONENT) {
                ComponentDef def = ComponentsCatalog.get().get(o.typeId);
                if (def == null)
                    out.add(new Issue(Severity.ERROR, "UNKNOWN_TYPE", "Неизвестный тип компонента: " + o.typeId,
                            o.id, o.pos.x, o.pos.y, "Проверьте библиотеку компонентов"));
                else {
                    if (def.defaultParams.containsKey("voltage")) hasSource = true;
                    for (String pid : o.params.keySet()) { /* param names free-form */ }
                }
                if (!MaterialsRegistry.get().known(o.materialId))
                    out.add(new Issue(Severity.WARNING, "UNKNOWN_MATERIAL", "Материал не найден: " + o.materialId,
                            o.id, o.pos.x, o.pos.y, "Выберите материал из библиотеки"));
            }
            if (Double.isNaN(o.pos.x) || Double.isNaN(o.pos.y))
                out.add(new Issue(Severity.ERROR, "BAD_COORD", "Координаты = NaN", o.id, 0, 0, "Задайте корректные координаты"));
            if (o.width <= 0 || o.height <= 0)
                out.add(new Issue(Severity.ERROR, "BAD_SIZE", "Некорректный размер объекта", o.id, o.pos.x, o.pos.y,
                        "Размеры должны быть > 0"));
        }

        if (!hasSource && !p.objects.isEmpty())
            out.add(new Issue(Severity.INFO, "NO_POWER", "В проекте нет источника питания",
                    null, 0, 0, "Добавьте батарейку или источник питания"));

        // connections: dangling references, weld compatibility, contacts without weld
        for (Connection c : p.connections) {
            ProjectObject a = p.byId(c.aObj), b = p.byId(c.bObj);
            if (a == null || b == null) {
                out.add(new Issue(Severity.ERROR, "DANGLING_REF", "Соединение ссылается на удалённый объект",
                        c.id, 0, 0, "Удалите соединение"));
                continue;
            }
            if (c.type == ConnectionType.ELECTRICAL && !c.welded)
                out.add(new Issue(Severity.INFO, "TOUCH_NO_WELD",
                        "Касание без сварки — электрического контакта нет", c.id,
                        a.pos.x, a.pos.y, "Используйте инструмент «Сварка» для активного соединения"));
            if (c.welded && isWeldIncompatible(a, b)) {
                Material ma = MaterialsRegistry.get().get(a.materialId);
                Material mb = MaterialsRegistry.get().get(b.materialId);
                out.add(new Issue(Severity.ERROR, "WELD_INCOMPATIBLE",
                        "Несовместимая сварка: " + ma.nameRu + " + " + mb.nameRu, c.id,
                        a.pos.x, a.pos.y, "Используйте механический крепёж или клей"));
            }
            if (c.strength <= 0)
                out.add(new Issue(Severity.WARNING, "ZERO_STRENGTH", "Нулевая прочность соединения", c.id,
                        a.pos.x, a.pos.y, "Укажите прочность сварного шва"));
        }

        // wires not connected to anything
        for (ProjectObject o : p.objects) {
            if (o.kind != BodyKind.WIRE) continue;
            boolean linked = p.connections.stream().anyMatch(c -> c.aObj.equals(o.id) || c.bObj.equals(o.id));
            if (!linked)
                out.add(new Issue(Severity.WARNING, "FLOATING_WIRE", "Провод ни к чему не подключён",
                        o.id, o.points != null && !o.points.isEmpty() ? o.points.get(0).x : 0,
                        o.points != null && !o.points.isEmpty() ? o.points.get(0).y : 0,
                        "Подсоедините и приварите концы провода"));
        }

        // module recursion check (phase B): cycles among parentModuleId
        Set<String> visiting = new HashSet<>();
        for (ProjectObject o : p.objects) {
            if (o.parentModuleId == null) continue;
            String cur = o.parentModuleId;
            visiting.clear();
            while (cur != null) {
                if (!visiting.add(cur)) {
                    out.add(new Issue(Severity.ERROR, "MODULE_CYCLE", "Циклическая вложенность модулей",
                            o.id, o.pos.x, o.pos.y, "Разорвите цикл вложенности"));
                    break;
                }
                ProjectObject po = p.byId(cur);
                cur = po == null ? null : po.parentModuleId;
            }
        }
        return out;
    }

    /** Weld material rule (spec 9): plastic/wood/ceramic/glass cannot metal-weld;
     *  two thermoplastics can; metals need shared weld class family. */
    public static boolean isWeldIncompatible(ProjectObject a, ProjectObject b) {
        Material ma = MaterialsRegistry.get().get(a.materialId);
        Material mb = MaterialsRegistry.get().get(b.materialId);
        return !(ma.canWeldWith(mb));
    }

    /** Strength factor for dissimilar metals (weaker seam). */
    public static double weldStrengthFactor(ProjectObject a, ProjectObject b, double base) {
        Material ma = MaterialsRegistry.get().get(a.materialId);
        Material mb = MaterialsRegistry.get().get(b.materialId);
        if (ma.isDissimilarMetalPair(mb)) return base * 0.6;
        return base;
    }
}
