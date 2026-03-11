package com.redblue.red.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * Scrollable tutorial text screen for attack modules.
 */
@OnlyIn(Dist.CLIENT)
public class TutorialScreen extends Screen {

    private final Screen parent;
    private final String tutorialText;
    private List<String> wrappedLines = new ArrayList<>();
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private static final int LINE_HEIGHT = 11;
    private static final int MARGIN = 30;

    public TutorialScreen(Screen parent, String title, String tutorialText) {
        super(Component.literal(title + " - 教程"));
        this.parent = parent;
        this.tutorialText = tutorialText;
    }

    @Override
    protected void init() {
        super.init();
        // Word-wrap tutorial text to fit screen width
        int maxWidth = this.width - MARGIN * 2;
        wrappedLines.clear();
        for (String rawLine : tutorialText.split("\n")) {
            if (rawLine.isEmpty()) {
                wrappedLines.add("");
                continue;
            }
            // Let MC font handle wrapping
            List<String> split = splitLine(rawLine, maxWidth);
            wrappedLines.addAll(split);
        }

        int visibleArea = this.height - 80;
        int totalHeight = wrappedLines.size() * LINE_HEIGHT;
        maxScroll = Math.max(0, totalHeight - visibleArea);

        addRenderableWidget(Button.builder(
            Component.literal("返回"),
            btn -> this.minecraft.setScreen(parent)
        ).bounds((this.width - 80) / 2, this.height - 30, 80, 20).build());
    }

    private List<String> splitLine(String line, int maxWidth) {
        List<String> result = new ArrayList<>();
        while (!line.isEmpty()) {
            int fit = this.font.plainSubstrByWidth(line, maxWidth).length();
            if (fit <= 0) fit = 1;
            if (fit < line.length()) {
                result.add(line.substring(0, fit));
                line = line.substring(fit);
            } else {
                result.add(line);
                break;
            }
        }
        return result;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        // Title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFF5555);

        // Scrollable text area
        int textTop = 30;
        int visibleArea = this.height - 80;

        // Clip rendering to visible area
        for (int i = 0; i < wrappedLines.size(); i++) {
            int y = textTop + i * LINE_HEIGHT - scrollOffset;
            if (y < textTop - LINE_HEIGHT) continue;
            if (y > textTop + visibleArea) break;

            String line = wrappedLines.get(i);
            // Section headers (lines starting with [) get highlight color
            int color = line.startsWith("[") ? 0xFFFF55 : 0xDDDDDD;
            graphics.drawString(this.font, line, MARGIN, y, color);
        }

        // Scroll indicator
        if (maxScroll > 0) {
            float pct = (float) scrollOffset / maxScroll;
            int barH = Math.max(10, visibleArea * visibleArea / (wrappedLines.size() * LINE_HEIGHT));
            int barY = textTop + (int) ((visibleArea - barH) * pct);
            graphics.fill(this.width - 8, barY, this.width - 4, barY + barH, 0x80FFFFFF);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scrollOffset = (int) Math.max(0, Math.min(maxScroll, scrollOffset - delta * LINE_HEIGHT * 3));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
