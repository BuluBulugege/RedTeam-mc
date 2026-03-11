package com.redblue.red.core.config;

/**
 * A single configurable parameter for an attack module.
 * Supports bool, int, float, string, and enum types.
 */
public class ConfigParam {

    public enum Type { BOOL, INT, FLOAT, STRING, ENUM, ENTITY, PLAYER, ACTION, ITEM, BLOCK }

    public final String key;
    public final String label;
    public final String tooltip;
    public final Type type;

    private Object value;
    private final Object defaultValue;

    // numeric bounds
    public final double min;
    public final double max;
    // enum options (mutable for dynamic ENUM selectors)
    public String[] options;
    // action callback (for ACTION type)
    public transient Runnable action;

    // conditional visibility
    private String visibleWhenKey;
    private String[] visibleWhenValues;

    private ConfigParam(String key, String label, String tooltip, Type type,
                        Object defaultValue, double min, double max, String[] options) {
        this.key = key;
        this.label = label;
        this.tooltip = tooltip;
        this.type = type;
        this.value = defaultValue;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.options = options;
    }

    // --- Getters ---

    public boolean getBool() { return (Boolean) value; }
    public int getInt() { return ((Number) value).intValue(); }
    public float getFloat() { return ((Number) value).floatValue(); }
    public String getString() { return (String) value; }
    public long getLong() { return ((Number) value).longValue(); }

    public void set(Object v) { this.value = v; }
    public Object get() { return value; }
    public void reset() { this.value = defaultValue; }

    /** Fluent: only show this param when another param equals one of the given values */
    public ConfigParam visibleWhen(String paramKey, String... values) {
        this.visibleWhenKey = paramKey;
        this.visibleWhenValues = values;
        return this;
    }

    /** Check if this param should be visible given the current state of all params */
    public boolean isVisible(java.util.List<ConfigParam> allParams) {
        if (visibleWhenKey == null) return true;
        for (ConfigParam p : allParams) {
            if (p.key.equals(visibleWhenKey)) {
                String current = String.valueOf(p.get());
                for (String v : visibleWhenValues) {
                    if (v.equals(current)) return true;
                }
                return false;
            }
        }
        return true;
    }

    // --- Builders ---

    public static ConfigParam ofBool(String key, String label, boolean def) {
        return new ConfigParam(key, label, null, Type.BOOL, def, 0, 0, null);
    }

    public static ConfigParam ofBool(String key, String label, String tooltip, boolean def) {
        return new ConfigParam(key, label, tooltip, Type.BOOL, def, 0, 0, null);
    }

    public static ConfigParam ofInt(String key, String label, int def, int min, int max) {
        return new ConfigParam(key, label, null, Type.INT, def, min, max, null);
    }

    public static ConfigParam ofFloat(String key, String label, float def, float min, float max) {
        return new ConfigParam(key, label, null, Type.FLOAT, def, min, max, null);
    }

    public static ConfigParam ofString(String key, String label, String def) {
        return new ConfigParam(key, label, null, Type.STRING, def, 0, 0, null);
    }

    public static ConfigParam ofEnum(String key, String label, String def, String... options) {
        return new ConfigParam(key, label, null, Type.ENUM, def, 0, 0, options);
    }

    /** Entity ID selector — GUI shows crosshair pick button + manual fallback */
    public static ConfigParam ofEntity(String key, String label) {
        return new ConfigParam(key, label, null, Type.ENTITY, 0, 0, Integer.MAX_VALUE, null);
    }

    /** Player name selector — GUI cycles through online players */
    public static ConfigParam ofPlayer(String key, String label) {
        return new ConfigParam(key, label, null, Type.PLAYER, "", 0, 0, null);
    }

    /** Item selector — GUI shows item name + "手持选取" button. Stores registry name string. */
    public static ConfigParam ofItem(String key, String label, String def) {
        return new ConfigParam(key, label, null, Type.ITEM, def, 0, 0, null);
    }

    /** Action button — GUI renders a clickable button, executes the Runnable */
    public static ConfigParam ofAction(String key, String label, Runnable action) {
        ConfigParam p = new ConfigParam(key, label, null, Type.ACTION, null, 0, 0, null);
        p.action = action;
        return p;
    }

    /** Block position selector — stores BlockPos.asLong(), GUI shows coords + crosshair pick button */
    public static ConfigParam ofBlock(String key, String label) {
        return new ConfigParam(key, label, null, Type.BLOCK, 0L, 0, 0, null);
    }
}
