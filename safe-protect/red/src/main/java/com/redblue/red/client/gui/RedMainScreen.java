package com.redblue.red.client.gui;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.ModuleRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@OnlyIn(Dist.CLIENT)
public class RedMainScreen extends Screen {

    private static final int COLS = 3;
    private static final int CELL_W = 180;
    private static final int CELL_H = 28;
    private static final int GAP_X = 8;
    private static final int GAP_Y = 6;

    /** Category prefix -> display name */
    private static final Map<String, String> CATEGORY_NAMES = new LinkedHashMap<>();

    static {
        CATEGORY_NAMES.put("lrt",      "LR Tactical");
        CATEGORY_NAMES.put("tacz",     "TACZ \u6c38\u6052\u67aa\u68b0\u5de5\u574a");
        CATEGORY_NAMES.put("taczadd",  "TACZ Addon");
        CATEGORY_NAMES.put("ctd",      "Citadel");
        CATEGORY_NAMES.put("am",       "Alex's Mobs");
        CATEGORY_NAMES.put("corpse",   "Corpse");
        CATEGORY_NAMES.put("kjs",      "KubeJS");
        CATEGORY_NAMES.put("jm",       "JourneyMap");
        CATEGORY_NAMES.put("opac",     "Open Parties & Claims");
        CATEGORY_NAMES.put("cnpc",     "CustomNPCs Reforged");
        CATEGORY_NAMES.put("curios",   "Curios API");
        CATEGORY_NAMES.put("pcool",    "ParCool!");
        CATEGORY_NAMES.put("lv",       "Limitless Vehicle");
        CATEGORY_NAMES.put("ia",       "Immersive Aircraft");
        CATEGORY_NAMES.put("cata",     "Cataclysm");
        CATEGORY_NAMES.put("fid",      "Flavor Immersed Daily");
        CATEGORY_NAMES.put("ftq",      "FTB Quests");
        CATEGORY_NAMES.put("lso",      "Legendary Survival");
        CATEGORY_NAMES.put("aw",       "Armourer's Workshop");
        CATEGORY_NAMES.put("if",       "Item Filters");
        CATEGORY_NAMES.put("pw",       "PingWheel");
        CATEGORY_NAMES.put("ext",      "ExtinctionZ");
        CATEGORY_NAMES.put("vc",       "Simple Voice Chat");
    }

    public static String getCategoryDisplayName(String prefix) {
        return CATEGORY_NAMES.getOrDefault(prefix, prefix);
    }

    public RedMainScreen() {
        super(Component.literal("\u7ea2\u961f - \u653b\u51fb\u6a21\u5757"));
    }

    @Override
    protected void init() {
        super.init();
        rebuildWidgets();
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();

        Map<String, List<AttackModule>> categories = ModuleRegistry.getByCategory();
        List<String> prefixes = new java.util.ArrayList<>(categories.keySet());

        int gridW = COLS * CELL_W + (COLS - 1) * GAP_X;
        int gridLeft = (this.width - gridW) / 2;
        int gridTop = 62;

        for (int i = 0; i < prefixes.size(); i++) {
            String prefix = prefixes.get(i);
            List<AttackModule> mods = categories.get(prefix);
            int col = i % COLS;
            int row = i / COLS;

            int x = gridLeft + col * (CELL_W + GAP_X);
            int y = gridTop + row * (CELL_H + GAP_Y);

            String displayName = getCategoryDisplayName(prefix);
            String label = displayName + " (" + mods.size() + ")";

            addRenderableWidget(Button.builder(
                Component.literal(label),
                btn -> openCategoryList(prefix, displayName, mods)
            ).bounds(x, y, CELL_W, CELL_H - 2).build());
        }

        // Close button
        int rows = (prefixes.size() + COLS - 1) / COLS;
        int closeY = gridTop + rows * (CELL_H + GAP_Y) + 16;
        addRenderableWidget(Button.builder(
            Component.literal("\u5173\u95ed"),
            btn -> onClose()
        ).bounds((this.width - 80) / 2, closeY, 80, 20).build());
    }

    private void openCategoryList(String prefix, String displayName, List<AttackModule> modules) {
        this.minecraft.setScreen(new ModuleListScreen(this, prefix, displayName, modules));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        // Title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFF5555);

        // Subtitle
        int total = ModuleRegistry.getAll().size();
        int catCount = ModuleRegistry.getByCategory().size();
        String sub = total + " \u4e2a\u6a21\u5757 | " + catCount + " \u4e2a\u5206\u7c7b";
        graphics.drawCenteredString(this.font, Component.literal(sub), this.width / 2, 26, 0xAAAAAA);

        // Author & skill tag
        String author = "Author: BlueDog (2052774863)  |  mc-mod-protocol-auditor \u00b7 red-payload-builder";
        graphics.drawCenteredString(this.font, Component.literal(author), this.width / 2, 38, 0x888888);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
