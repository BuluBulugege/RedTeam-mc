package com.redblue.red.client;

import com.redblue.red.client.gui.RedMainScreen;
import com.redblue.red.core.AttackModule;
import com.redblue.red.core.HotkeyManager;
import com.redblue.red.core.ModuleRegistry;
import com.redblue.red.util.ResponseSniffer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@OnlyIn(Dist.CLIENT)
public class KeyDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("ModCompat");

    public static final KeyMapping OPEN_PANEL = new KeyMapping(
        "key.misc.quickmenu", GLFW.GLFW_KEY_K, "key.categories.misc"
    );

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_PANEL);
    }

    /** Track previous Shift+number key states to detect press edges */
    private static final boolean[] prevHotkeyState = new boolean[10];

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // K opens the main panel
        if (mc.screen == null && OPEN_PANEL.consumeClick()) {
            mc.setScreen(new RedMainScreen());
            return;
        }

        // Shift+1-0 hotkey dispatch — works ANYWHERE (including inside container GUIs)
        // This allows modules like EXT02 to be triggered while a backpack is open
        {
            long window = mc.getWindow().getWindow();
            boolean shiftHeld = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                             || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;

            int[] glfwKeys = {
                GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_4, GLFW.GLFW_KEY_5,
                GLFW.GLFW_KEY_6, GLFW.GLFW_KEY_7, GLFW.GLFW_KEY_8, GLFW.GLFW_KEY_9, GLFW.GLFW_KEY_0
            };

            for (int slot = 0; slot < 10; slot++) {
                boolean pressed = shiftHeld && GLFW.glfwGetKey(window, glfwKeys[slot]) == GLFW.GLFW_PRESS;
                // Trigger on rising edge only
                if (pressed && !prevHotkeyState[slot]) {
                    HotkeyManager.trigger(slot, mc);
                }
                prevHotkeyState[slot] = pressed;
            }
        }

        // Flush pending inventory responses (GUI opening)
        ResponseSniffer.tickFlush();

        // Tick all enabled modules (auto-loop)
        for (AttackModule module : ModuleRegistry.getAll()) {
            if (!module.isAvailable() || !module.isEnabled()) continue;
            try {
                module.tick(mc);
            } catch (Exception e) {
                LOGGER.error("Module {} tick failed", module.id(), e);
            }
        }
    }
}
