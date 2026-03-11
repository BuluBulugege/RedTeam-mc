package com.redblue.red.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.TreeMap;

/**
 * Read-only client-side viewer for remotely queried vehicle inventories.
 * Displays items in a grid layout with slot indices.
 */
@OnlyIn(Dist.CLIENT)
public class VehicleInventoryScreen extends Screen {

    private final int vehicleId;
    private final Map<Integer, ItemStack> slots;
    private static final int SLOT_SIZE = 18;
    private static final int COLS = 9;

    public VehicleInventoryScreen(int vehicleId, Map<Integer, ItemStack> slots) {
        super(Component.literal("载具物品栏 #" + vehicleId));
        this.vehicleId = vehicleId;
        this.slots = new TreeMap<>(slots);
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(
            Component.literal("关闭"),
            btn -> this.minecraft.setScreen(null)
        ).bounds((this.width - 80) / 2, this.height - 30, 80, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        // Title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFF5555);

        if (slots.isEmpty()) {
            graphics.drawCenteredString(this.font,
                Component.literal("(空载具或无响应)"),
                this.width / 2, this.height / 2, 0xAAAAAA);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        // Calculate grid layout
        int maxSlot = slots.keySet().stream().mapToInt(i -> i).max().orElse(0);
        int rows = (maxSlot / COLS) + 1;
        int gridW = COLS * SLOT_SIZE;
        int gridH = rows * SLOT_SIZE;
        int startX = (this.width - gridW) / 2;
        int startY = 28;

        // Draw slot backgrounds and items
        ItemStack hoveredStack = ItemStack.EMPTY;
        int hoveredSlot = -1;

        for (int slot = 0; slot <= maxSlot; slot++) {
            int col = slot % COLS;
            int row = slot / COLS;
            int x = startX + col * SLOT_SIZE;
            int y = startY + row * SLOT_SIZE;

            // Slot background
            graphics.fill(x, y, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, 0x40FFFFFF);

            ItemStack stack = slots.getOrDefault(slot, ItemStack.EMPTY);
            if (!stack.isEmpty()) {
                graphics.renderItem(stack, x + 1, y + 1);
                graphics.renderItemDecorations(this.font, stack, x + 1, y + 1);
            }

            // Hover detection
            if (mouseX >= x && mouseX < x + SLOT_SIZE &&
                mouseY >= y && mouseY < y + SLOT_SIZE) {
                graphics.fill(x, y, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, 0x60FFFFFF);
                if (!stack.isEmpty()) {
                    hoveredStack = stack;
                    hoveredSlot = slot;
                }
            }
        }

        // Item count summary
        long nonEmpty = slots.values().stream().filter(s -> !s.isEmpty()).count();
        graphics.drawCenteredString(this.font,
            Component.literal("共 " + nonEmpty + " 种物品, " + (maxSlot + 1) + " 个槽位"),
            this.width / 2, startY + gridH + 4, 0xAAAAAA);

        super.render(graphics, mouseX, mouseY, partialTick);

        // Tooltip (must be after super.render)
        if (!hoveredStack.isEmpty()) {
            graphics.renderTooltip(this.font, hoveredStack, mouseX, mouseY);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
