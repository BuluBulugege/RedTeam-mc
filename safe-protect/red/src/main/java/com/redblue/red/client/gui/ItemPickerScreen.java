package com.redblue.red.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * JEI-style item picker: scrollable grid of ALL registered items with search.
 */
@OnlyIn(Dist.CLIENT)
public class ItemPickerScreen extends Screen {

    private final Screen parent;
    private final Consumer<String> onSelect;

    private static final int CELL = 18;
    private static final int GRID_COLS = 12;
    private static final int GRID_MARGIN = 20;

    private EditBox searchBox;
    private List<Item> allItems;
    private List<Item> filtered;
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private int gridTop;
    private int gridLeft;
    private int gridRows;

    public ItemPickerScreen(Screen parent, Consumer<String> onSelect) {
        super(Component.literal("选择物品"));
        this.parent = parent;
        this.onSelect = onSelect;
    }

    @Override
    protected void init() {
        super.init();

        // Collect all items
        allItems = new ArrayList<>(ForgeRegistries.ITEMS.getValues());
        filtered = new ArrayList<>(allItems);

        // Search box
        int boxW = GRID_COLS * CELL;
        gridLeft = (this.width - boxW) / 2;
        gridTop = 46;

        searchBox = new EditBox(this.font, gridLeft, 22, boxW, 18, Component.literal("搜索"));
        searchBox.setHint(Component.literal("输入物品名称搜索..."));
        searchBox.setResponder(this::onSearch);
        searchBox.setMaxLength(128);
        addRenderableWidget(searchBox);

        // Cancel button
        addRenderableWidget(Button.builder(
            Component.literal("取消"),
            btn -> this.minecraft.setScreen(parent)
        ).bounds((this.width - 60) / 2, this.height - 26, 60, 20).build());

        gridRows = (this.height - gridTop - 36) / CELL;
        updateScroll();
    }

    private void onSearch(String query) {
        filtered.clear();
        String q = query.toLowerCase().trim();
        if (q.isEmpty()) {
            filtered.addAll(allItems);
        } else {
            for (Item item : allItems) {
                String name = item.getDescription().getString().toLowerCase();
                String id = ForgeRegistries.ITEMS.getKey(item).toString().toLowerCase();
                if (name.contains(q) || id.contains(q)) {
                    filtered.add(item);
                }
            }
        }
        scrollOffset = 0;
        updateScroll();
    }

    private void updateScroll() {
        int totalRows = (filtered.size() + GRID_COLS - 1) / GRID_COLS;
        maxScroll = Math.max(0, (totalRows - gridRows) * CELL);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float pt) {
        renderBackground(g);

        // Title
        g.drawCenteredString(this.font, this.title, this.width / 2, 6, 0xFF5555);

        // Grid background
        int gridW = GRID_COLS * CELL;
        int gridH = gridRows * CELL;
        g.fill(gridLeft - 1, gridTop - 1,
               gridLeft + gridW + 1, gridTop + gridH + 1, 0x60000000);

        // Render items
        int hoverIdx = -1;
        for (int row = 0; row < gridRows + 1; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int pixelY = gridTop + row * CELL - (scrollOffset % CELL);
                int itemRow = (scrollOffset / CELL) + row;
                int idx = itemRow * GRID_COLS + col;
                if (idx < 0 || idx >= filtered.size()) continue;
                if (pixelY < gridTop - CELL || pixelY > gridTop + gridH) continue;

                int px = gridLeft + col * CELL;
                int py = pixelY;

                // Hover highlight
                if (mouseX >= px && mouseX < px + CELL &&
                    mouseY >= py && mouseY < py + CELL &&
                    mouseY >= gridTop && mouseY < gridTop + gridH) {
                    g.fill(px, py, px + CELL, py + CELL, 0x60FFFFFF);
                    hoverIdx = idx;
                }

                ItemStack stack = new ItemStack(filtered.get(idx));
                g.renderItem(stack, px + 1, py + 1);
            }
        }

        // Scrollbar
        if (maxScroll > 0) {
            int barX = gridLeft + gridW + 3;
            int barH = Math.max(10, gridH * gridH / ((maxScroll + gridH)));
            float pct = (float) scrollOffset / maxScroll;
            int barY = gridTop + (int) ((gridH - barH) * pct);
            g.fill(barX, gridTop, barX + 4, gridTop + gridH, 0x40FFFFFF);
            g.fill(barX, barY, barX + 4, barY + barH, 0xC0FFFFFF);
        }

        // Item count
        g.drawString(this.font,
            filtered.size() + " 个物品",
            gridLeft, gridTop + gridH + 4, 0x888888);

        super.render(g, mouseX, mouseY, pt);

        // Tooltip
        if (hoverIdx >= 0 && hoverIdx < filtered.size()) {
            Item item = filtered.get(hoverIdx);
            String id = ForgeRegistries.ITEMS.getKey(item).toString();
            g.renderTooltip(this.font,
                List.of(item.getDescription(), Component.literal("§7" + id)),
                java.util.Optional.empty(), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && my >= gridTop && my < gridTop + gridRows * CELL) {
            int col = (int) ((mx - gridLeft) / CELL);
            int pixelRow = (int) (my - gridTop + (scrollOffset % CELL));
            int itemRow = (scrollOffset / CELL) + pixelRow / CELL;
            int idx = itemRow * GRID_COLS + col;

            if (col >= 0 && col < GRID_COLS && idx >= 0 && idx < filtered.size()) {
                String id = ForgeRegistries.ITEMS.getKey(filtered.get(idx)).toString();
                onSelect.accept(id);
                this.minecraft.setScreen(parent);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scrollOffset = (int) Math.max(0, Math.min(maxScroll,
            scrollOffset - delta * CELL * 3));
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
