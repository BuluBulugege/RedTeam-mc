package com.redblue.red.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class ModuleRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("ModCompat");
    private static final List<AttackModule> modules = new ArrayList<>();

    public static void register(AttackModule module) {
        if (!module.isAvailable()) {
            LOGGER.info("Skipped module {} ({}) — target mod not loaded",
                    module.id(), module.name());
            return;
        }
        modules.add(module);
        LOGGER.info("Registered attack module: {} ({})", module.id(), module.name());
    }

    public static void clear() {
        modules.clear();
        LOGGER.info("All attack modules cleared");
    }

    public static List<AttackModule> getAll() {
        return Collections.unmodifiableList(modules);
    }

    public static Optional<AttackModule> getById(String id) {
        return modules.stream().filter(m -> m.id().equals(id)).findFirst();
    }

    /**
     * Extract the category prefix from a module ID.
     * E.g. "tacz01_xxx" -> "tacz", "ia03_xxx" -> "ia"
     */
    public static String getCategoryPrefix(AttackModule m) {
        String id = m.id();
        int i = 0;
        while (i < id.length() && !Character.isDigit(id.charAt(i))) i++;
        return id.substring(0, i);
    }

    /**
     * Group all registered modules by their category prefix.
     * Keys are lowercase prefixes (e.g. "tacz", "ia").
     */
    public static Map<String, List<AttackModule>> getByCategory() {
        return modules.stream().collect(Collectors.groupingBy(
            ModuleRegistry::getCategoryPrefix,
            LinkedHashMap::new,
            Collectors.toList()
        ));
    }
}
