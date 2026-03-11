package com.redblue.red.core;

import com.redblue.red.core.config.ConfigParam;
import net.minecraft.client.Minecraft;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Self-contained attack module interface.
 * Each module handles the full exploit lifecycle: detect → decide → execute.
 */
public interface AttackModule {

    String id();

    String name();

    String description();

    /** Module self-checks if the target is available */
    boolean isAvailable();

    /** One-shot manual execution from GUI "Execute" button */
    void execute(Minecraft mc);

    /** Called every client tick while module is enabled. Override for auto-loop modules. */
    default void tick(Minecraft mc) {}

    /** Config parameters for this module's GUI. Override to add params. */
    default List<ConfigParam> getConfigParams() { return Collections.emptyList(); }

    /** Convenience: get a param by key */
    default Optional<ConfigParam> getParam(String key) {
        return getConfigParams().stream().filter(p -> p.key.equals(key)).findFirst();
    }

    /** Whether this module is enabled (checks the "enabled" param if present) */
    default boolean isEnabled() {
        return getParam("enabled").map(ConfigParam::getBool).orElse(true);
    }

    /** Optional hotkey GLFW key code. Return -1 for no hotkey. */
    default int keyCode() { return -1; }

    /** Tutorial text for this module. Shown in a dedicated tutorial screen. */
    default String getTutorial() { return ""; }
}
