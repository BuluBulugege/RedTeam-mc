package com.redblue.red.client.gui;

import com.redblue.red.core.AttackModule;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

@OnlyIn(Dist.CLIENT)
public class ModuleListScreen extends Screen {

    private final Screen parent;
    private final String categoryPrefix;
    private final String categoryDisplayName;
    private final List<AttackModule> modules;

    private static final int COLS = 3;
    private static final int CELL_W = 150;
    private static final int CELL_H = 28;
    private static final int GAP_X = 8;
    private static final int GAP_Y = 6;
    private static final int ROWS_PER_PAGE = 3;
    private static final int PER_PAGE = COLS * ROWS_PER_PAGE;

    private int page = 0;
    private int totalPages = 1;

    public ModuleListScreen(Screen parent, String categoryPrefix, String categoryDisplayName,
                            List<AttackModule> modules) {
        super(Component.literal(categoryDisplayName));
        this.parent = parent;
        this.categoryPrefix = categoryPrefix;
        this.categoryDisplayName = categoryDisplayName;
        this.modules = modules;
    }

    @Override
    protected void init() {
        super.init();
        rebuildWidgets();
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();

        totalPages = Math.max(1, (modules.size() + PER_PAGE - 1) / PER_PAGE);
        if (page >= totalPages) page = totalPages - 1;

        int gridW = COLS * CELL_W + (COLS - 1) * GAP_X;
        int gridLeft = (this.width - gridW) / 2;
        int gridTop = 50;

        int start = page * PER_PAGE;
        int end = Math.min(start + PER_PAGE, modules.size());

        for (int i = start; i < end; i++) {
            AttackModule module = modules.get(i);
            int idx = i - start;
            int col = idx % COLS;
            int row = idx / COLS;

            int x = gridLeft + col * (CELL_W + GAP_X);
            int y = gridTop + row * (CELL_H + GAP_Y);

            String status = module.isEnabled() ? "\u00a7a\u25CF " : "\u00a7c\u25CB ";
            String avail = module.isAvailable() ? "" : " \u00a78[N/A]";
            String label = status + module.name() + avail;

            addRenderableWidget(Button.builder(
                Component.literal(label),
                btn -> openModuleConfig(module)
            ).bounds(x, y, CELL_W, CELL_H - 2).build());
        }

        // Navigation area
        int navY = gridTop + ROWS_PER_PAGE * (CELL_H + GAP_Y) + 16;
        int navCenterX = this.width / 2;

        if (totalPages > 1) {
            addRenderableWidget(Button.builder(
                Component.literal("<"),
                btn -> { page = Math.max(0, page - 1); rebuildWidgets(); }
            ).bounds(navCenterX - 80, navY, 20, 20).build());

            addRenderableWidget(Button.builder(
                Component.literal(">"),
                btn -> { page = Math.min(totalPages - 1, page + 1); rebuildWidgets(); }
            ).bounds(navCenterX + 60, navY, 20, 20).build());
        }

        // Back button
        addRenderableWidget(Button.builder(
            Component.literal("\u8fd4\u56de"),
            btn -> this.minecraft.setScreen(parent)
        ).bounds(navCenterX - 40, navY + 28, 80, 20).build());
    }

    private void openModuleConfig(AttackModule module) {
        this.minecraft.setScreen(new ModuleConfigScreen(this, module));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        // Title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFF5555);

        // Subtitle
        String sub = modules.size() + " \u4e2a\u6a21\u5757 | \u7b2c " + (page + 1) + "/" + totalPages + " \u9875";
        graphics.drawCenteredString(this.font, Component.literal(sub), this.width / 2, 26, 0xAAAAAA);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (delta > 0 && page > 0) {
            page--;
            rebuildWidgets();
        } else if (delta < 0 && page < totalPages - 1) {
            page++;
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
