package com.redblue.red.core;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Manages Shift+1 through Shift+0 hotkey bindings for module actions.
 * 10 slots total: index 0 = Shift+1, index 9 = Shift+0.
 */
public class HotkeyManager {

    public enum BindType { TOGGLE_ENABLED, EXECUTE }

    public record Binding(String moduleId, BindType type) {}

    private static final Binding[] slots = new Binding[10];

    public static void bind(int slot, String moduleId, BindType type) {
        if (slot < 0 || slot >= 10) return;
        slots[slot] = new Binding(moduleId, type);
    }

    public static void unbind(int slot) {
        if (slot < 0 || slot >= 10) return;
        slots[slot] = null;
    }

    public static Binding get(int slot) {
        if (slot < 0 || slot >= 10) return null;
        return slots[slot];
    }

    /**
     * Find which slot (0-9) a given moduleId+type is bound to, or -1 if not bound.
     */
    public static int findSlot(String moduleId, BindType type) {
        for (int i = 0; i < 10; i++) {
            if (slots[i] != null && slots[i].moduleId().equals(moduleId) && slots[i].type() == type) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Trigger the action bound to the given slot.
     * @param slot 0-9 (Shift+1 through Shift+0)
     */
    public static void trigger(int slot, Minecraft mc) {
        if (slot < 0 || slot >= 10) return;
        Binding b = slots[slot];
        if (b == null) return;

        ModuleRegistry.getById(b.moduleId()).ifPresent(module -> {
            int displaySlot = (slot + 1) % 10; // slot 0 -> display "1", slot 9 -> display "0"
            String keyLabel = "Shift+" + displaySlot;

            if (b.type() == BindType.TOGGLE_ENABLED) {
                // Flip the "enabled" config param
                module.getParam("enabled").ifPresent(p -> {
                    boolean newVal = !p.getBool();
                    p.set(newVal);
                    String state = newVal ? "\u00a7a已启用" : "\u00a7c已禁用";
                    mc.player.displayClientMessage(
                        Component.literal("\u00a76" + keyLabel + ": " + module.name() + " " + state),
                        false
                    );
                });
            } else if (b.type() == BindType.EXECUTE) {
                if (module.isAvailable()) {
                    module.execute(mc);
                    mc.player.displayClientMessage(
                        Component.literal("\u00a76" + keyLabel + ": 执行 " + module.name()),
                        false
                    );
                }
            }
        });
    }
}
