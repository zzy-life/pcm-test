package com.hr.digitalhuman.model.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * robot_display / robot_speak.sections 的区块。
 * kind: text | list | table | flow | form | qrcode
 * <p>兼容模型常见别名：bullets/points → items，content/body/url → text；
 * sections 整体被序列化成 JSON 字符串时也会解析回来。</p>
 */
public class DisplaySection {
    public static final String TEXT = "text";
    public static final String LIST = "list";
    public static final String TABLE = "table";
    public static final String FLOW = "flow";
    public static final String FORM = "form";
    public static final String QRCODE = "qrcode";

    public String kind;
    public String text;
    public String title;
    public String caption;
    /** 二维码旁操作按钮文案（如「查看简历」） */
    public String actionLabel;
    /** 二维码旁操作按钮目标（如 PDF 下载完整 URL） */
    public String actionUrl;
    public List<String> items = new ArrayList<>();
    public List<String> columns = new ArrayList<>();
    public List<List<String>> rows = new ArrayList<>();
    public List<String> steps = new ArrayList<>();
    public Integer current;
    public JsonObject schema;

    public static DisplaySection fromJson(JsonObject o) {
        if (o == null) {
            return null;
        }
        DisplaySection s = new DisplaySection();
        s.kind = str(o, "kind");
        if (s.kind != null) {
            s.kind = s.kind.trim().toLowerCase();
        }
        s.text = firstNonEmpty(
                str(o, "text"), str(o, "content"), str(o, "body"),
                str(o, "desc"), str(o, "description"), str(o, "url"), str(o, "value"));
        s.title = firstNonEmpty(str(o, "title"), str(o, "heading"), str(o, "name"));
        s.caption = str(o, "caption");
        s.items = strList(o, "items");
        if (s.items.isEmpty()) {
            s.items = strList(o, "bullets");
        }
        if (s.items.isEmpty()) {
            s.items = strList(o, "points");
        }
        if (s.items.isEmpty()) {
            s.items = strList(o, "list");
        }
        s.columns = strList(o, "columns");
        s.steps = strList(o, "steps");
        if (s.steps.isEmpty()) {
            s.steps = strList(o, "flow");
        }
        if (o.has("current") && o.get("current").isJsonPrimitive()) {
            try {
                s.current = o.get("current").getAsInt();
            } catch (Exception ignored) {
            }
        }
        if (o.has("schema") && o.get("schema").isJsonObject()) {
            s.schema = o.getAsJsonObject("schema");
        }
        if (s.schema == null && o.has("fields") && o.get("fields").isJsonArray()) {
            s.schema = fieldsToSchema(o.getAsJsonArray("fields"));
        }
        if (o.has("rows") && o.get("rows").isJsonArray()) {
            for (JsonElement rowEl : o.getAsJsonArray("rows")) {
                List<String> row = new ArrayList<>();
                if (rowEl != null && rowEl.isJsonArray()) {
                    for (JsonElement cell : rowEl.getAsJsonArray()) {
                        row.add(cellText(cell));
                    }
                } else if (rowEl != null && !rowEl.isJsonNull()) {
                    row.add(cellText(rowEl));
                }
                s.rows.add(row);
            }
        }
        if (s.kind == null || s.kind.isEmpty()) {
            if (s.schema != null) {
                s.kind = FORM;
            } else if (!s.columns.isEmpty() || !s.rows.isEmpty()) {
                s.kind = TABLE;
            } else if (!s.steps.isEmpty()) {
                s.kind = FLOW;
            } else if (!s.items.isEmpty()) {
                s.kind = LIST;
            } else {
                s.kind = TEXT;
            }
        }
        return s;
    }

    public static List<DisplaySection> listFrom(JsonElement el) {
        List<DisplaySection> list = new ArrayList<>();
        if (el == null || el.isJsonNull()) {
            return list;
        }
        if (el.isJsonPrimitive()) {
            JsonElement parsed = tryParseJson(el.getAsString());
            if (parsed != null && !parsed.isJsonPrimitive()) {
                return listFrom(parsed);
            }
            String raw = el.getAsString();
            if (raw != null && !raw.trim().isEmpty()) {
                DisplaySection s = new DisplaySection();
                s.kind = TEXT;
                s.text = raw.trim();
                list.add(s);
            }
            return list;
        }
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            List<String> loose = new ArrayList<>();
            boolean hasObject = false;
            for (JsonElement item : arr) {
                if (item == null || item.isJsonNull()) {
                    continue;
                }
                if (item.isJsonObject()) {
                    hasObject = true;
                    DisplaySection s = fromJson(item.getAsJsonObject());
                    if (s != null) {
                        list.add(s);
                    }
                } else if (item.isJsonPrimitive()) {
                    JsonElement nested = tryParseJson(item.getAsString());
                    if (nested != null && (nested.isJsonObject() || nested.isJsonArray())) {
                        hasObject = true;
                        list.addAll(listFrom(nested));
                    } else {
                        String t = item.getAsString();
                        if (t != null && !t.trim().isEmpty()) {
                            loose.add(t.trim());
                        }
                    }
                }
            }
            if (!loose.isEmpty()) {
                if (!hasObject) {
                    DisplaySection s = new DisplaySection();
                    s.kind = LIST;
                    s.items.addAll(loose);
                    list.add(s);
                } else {
                    for (String t : loose) {
                        DisplaySection s = new DisplaySection();
                        s.kind = TEXT;
                        s.text = t;
                        list.add(s);
                    }
                }
            }
            return list;
        }
        if (el.isJsonObject()) {
            DisplaySection s = fromJson(el.getAsJsonObject());
            if (s != null) {
                list.add(s);
            }
        }
        return list;
    }

    public boolean isEmpty() {
        boolean hasTitle = title != null && !title.trim().isEmpty();
        if (TEXT.equals(kind) || QRCODE.equals(kind)) {
            return !hasTitle && (text == null || text.trim().isEmpty());
        }
        if (LIST.equals(kind)) {
            return !hasTitle && (items == null || items.isEmpty());
        }
        if (TABLE.equals(kind)) {
            return !hasTitle && (columns == null || columns.isEmpty())
                    && (rows == null || rows.isEmpty());
        }
        if (FLOW.equals(kind)) {
            return !hasTitle && (steps == null || steps.isEmpty());
        }
        if (FORM.equals(kind)) {
            return schema == null && !hasTitle;
        }
        return !hasTitle && (text == null || text.trim().isEmpty())
                && (items == null || items.isEmpty());
    }

    private static JsonElement tryParseJson(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.length() < 2) {
            return null;
        }
        char c = s.charAt(0);
        if (c != '{' && c != '[') {
            return null;
        }
        try {
            return new JsonParser().parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject fieldsToSchema(JsonArray fields) {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        int i = 0;
        for (JsonElement el : fields) {
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject f = el.getAsJsonObject();
            String key = firstNonEmpty(str(f, "key"), str(f, "name"), str(f, "id"), "field" + i);
            JsonObject prop = new JsonObject();
            String label = firstNonEmpty(str(f, "title"), str(f, "label"), str(f, "text"), key);
            prop.addProperty("title", label);
            String type = firstNonEmpty(str(f, "type"), "string");
            prop.addProperty("type", type);
            String desc = str(f, "description");
            if (desc != null) {
                prop.addProperty("description", desc);
            }
            properties.add(key, prop);
            if (f.has("required") && f.get("required").isJsonPrimitive()
                    && f.get("required").getAsBoolean()) {
                required.add(key);
            }
            i++;
        }
        if (properties.size() == 0) {
            return null;
        }
        schema.add("properties", properties);
        if (required.size() > 0) {
            schema.add("required", required);
        }
        return schema;
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        try {
            JsonElement el = o.get(key);
            if (el.isJsonPrimitive()) {
                return el.getAsString();
            }
            return el.toString();
        } catch (Exception e) {
            return String.valueOf(o.get(key));
        }
    }

    private static List<String> strList(JsonObject o, String key) {
        List<String> list = new ArrayList<>();
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return list;
        }
        JsonElement raw = o.get(key);
        if (raw.isJsonPrimitive()) {
            JsonElement parsed = tryParseJson(raw.getAsString());
            if (parsed != null && parsed.isJsonArray()) {
                raw = parsed;
            } else {
                String t = raw.getAsString();
                if (t != null && !t.trim().isEmpty()) {
                    list.add(t.trim());
                }
                return list;
            }
        }
        if (!raw.isJsonArray()) {
            return list;
        }
        for (JsonElement el : raw.getAsJsonArray()) {
            String t = cellText(el);
            if (t != null && !t.isEmpty()) {
                list.add(t);
            }
        }
        return list;
    }

    private static String cellText(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return "";
        }
        if (el.isJsonPrimitive()) {
            return el.getAsString();
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            String t = firstNonEmpty(
                    str(o, "text"), str(o, "label"), str(o, "title"),
                    str(o, "content"), str(o, "name"), str(o, "value"));
            return t == null ? "" : t;
        }
        return el.toString();
    }

    private static String firstNonEmpty(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        }
        return null;
    }
}
