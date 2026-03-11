package com.redblue.red.client.gui;

import com.redblue.red.core.AttackModule;
import com.redblue.red.core.HotkeyManager;
import com.redblue.red.core.config.ConfigParam;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

@OnlyIn(Dist.CLIENT)
public class ModuleConfigScreen extends Screen {

    private final Screen parent;
    private final AttackModule module;
    private static final int ROW_HEIGHT = 26;
    private static final int WIDGET_WIDTH = 200;
    private int scrollOffset = 0;
    private int maxScroll = 0;

    /** Captured before screen opens, since GameRenderer.pick() is skipped while a screen is active */
    private Entity lastCrosshairEntity;
    private BlockHitResult lastBlockHitResult;

    public ModuleConfigScreen(Screen parent, AttackModule module) {
        super(Component.literal(module.name()));
        this.parent = parent;
        this.module = module;
        // Capture crosshair entity NOW, before the screen steals mouse focus
        this.lastCrosshairEntity = Minecraft.getInstance().crosshairPickEntity;

        // Fallback: extended-range AABB ray cast for entities missed by vanilla pick
        if (this.lastCrosshairEntity == null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.level != null) {
                Vec3 eye = mc.player.getEyePosition(1.0f);
                Vec3 look = mc.player.getViewVector(1.0f);
                Vec3 end = eye.add(look.scale(64.0));
                AABB searchBox = mc.player.getBoundingBox().expandTowards(look.scale(64.0)).inflate(1.0);
                double closest = 64.0 * 64.0;
                for (Entity e : mc.level.getEntities(mc.player, searchBox)) {
                    AABB ebb = e.getBoundingBox().inflate(0.3);
                    var clip = ebb.clip(eye, end);
                    if (clip.isPresent()) {
                        double dist = eye.distanceToSqr(clip.get());
                        if (dist < closest) {
                            closest = dist;
                            this.lastCrosshairEntity = e;
                        }
                    }
                }
            }
        }

        // Capture block hit result for BLOCK picker
        if (Minecraft.getInstance().hitResult instanceof BlockHitResult bhr) {
            this.lastBlockHitResult = bhr;
        }
    }

    @Override
    protected void init() {
        super.init();
        rebuildWidgets();
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();

        List<ConfigParam> params = module.getConfigParams();
        // Filter to only visible params based on conditional visibility
        List<ConfigParam> visible = new java.util.ArrayList<>();
        for (ConfigParam p : params) {
            if (p.isVisible(params)) visible.add(p);
        }

        int left = (this.width - WIDGET_WIDTH) / 2;
        int top = 50;
        int visibleArea = this.height - 110;
        int totalHeight = visible.size() * ROW_HEIGHT;
        maxScroll = Math.max(0, totalHeight - visibleArea);

        for (int i = 0; i < visible.size(); i++) {
            ConfigParam param = visible.get(i);
            int y = top + (i * ROW_HEIGHT) - scrollOffset;

            if (y < top - ROW_HEIGHT || y > top + visibleArea) continue;

            switch (param.type) {
                case BOOL -> addBoolWidget(param, left, y);
                case INT -> addIntSlider(param, left, y);
                case FLOAT -> addFloatSlider(param, left, y);
                case STRING -> addStringField(param, left, y);
                case ENUM -> addEnumCycle(param, left, y);
                case ENTITY -> addEntityPicker(param, left, y);
                case PLAYER -> addPlayerCycle(param, left, y);
                case ACTION -> addActionButton(param, left, y);
                case ITEM -> addItemPicker(param, left, y);
                case BLOCK -> addBlockPicker(param, left, y);
            }
        }

        // Tutorial button (only if module has tutorial text)
        int btnY = this.height - 52;
        boolean hasTutorial = !module.getTutorial().isEmpty();
        int btnCount = hasTutorial ? 3 : 2;
        int btnW = (WIDGET_WIDTH - (btnCount - 1) * 4) / btnCount;
        int bx = (this.width - WIDGET_WIDTH) / 2;

        // Execute button
        addRenderableWidget(Button.builder(
            Component.literal("执行"),
            btn -> {
                if (module.isAvailable() && module.isEnabled()) {
                    module.execute(Minecraft.getInstance());
                }
            }
        ).bounds(bx, btnY, btnW, 20).build());

        // Tutorial button
        if (hasTutorial) {
            addRenderableWidget(Button.builder(
                Component.literal("教程"),
                btn -> this.minecraft.setScreen(
                    new TutorialScreen(this, module.name(), module.getTutorial()))
            ).bounds(bx + btnW + 4, btnY, btnW, 20).build());
        }

        // Back button
        addRenderableWidget(Button.builder(
            Component.literal("返回"),
            btn -> this.minecraft.setScreen(parent)
        ).bounds(bx + (btnCount - 1) * (btnW + 4), btnY, btnW, 20).build());

        // Reset all
        addRenderableWidget(Button.builder(
            Component.literal("重置默认"),
            btn -> {
                params.forEach(ConfigParam::reset);
                rebuildWidgets();
            }
        ).bounds((this.width - 100) / 2, this.height - 28, 100, 20).build());
    }

    // --- Widget builders per type ---

    private void addBoolWidget(ConfigParam param, int x, int y) {
        addRenderableWidget(CycleButton.onOffBuilder(param.getBool())
            .create(x, y, WIDGET_WIDTH, 20,
                Component.literal(param.label),
                (btn, val) -> { param.set(val); rebuildWidgets(); }));
    }

    private void addIntSlider(ConfigParam param, int x, int y) {
        double range = param.max - param.min;
        double initial = (param.getInt() - param.min) / range;
        addRenderableWidget(new AbstractSliderButton(x, y, WIDGET_WIDTH, 20,
            Component.literal(param.label + ": " + param.getInt()), initial) {
            @Override
            protected void updateMessage() {
                int val = (int) (param.min + this.value * (param.max - param.min));
                this.setMessage(Component.literal(param.label + ": " + val));
            }
            @Override
            protected void applyValue() {
                param.set((int) (param.min + this.value * (param.max - param.min)));
            }
        });
    }

    private void addFloatSlider(ConfigParam param, int x, int y) {
        double range = param.max - param.min;
        double initial = (param.getFloat() - param.min) / range;
        addRenderableWidget(new AbstractSliderButton(x, y, WIDGET_WIDTH, 20,
            Component.literal(param.label + ": " + String.format("%.2f", param.getFloat())), initial) {
            @Override
            protected void updateMessage() {
                float val = (float) (param.min + this.value * (param.max - param.min));
                this.setMessage(Component.literal(param.label + ": " + String.format("%.2f", val)));
            }
            @Override
            protected void applyValue() {
                param.set((float) (param.min + this.value * (param.max - param.min)));
            }
        });
    }

    private void addStringField(ConfigParam param, int x, int y) {
        EditBox box = new EditBox(this.font, x, y, WIDGET_WIDTH, 20, Component.literal(param.label));
        box.setValue(param.getString());
        box.setResponder(param::set);
        box.setMaxLength(256);
        addRenderableWidget(box);
    }

    private void addEnumCycle(ConfigParam param, int x, int y) {
        String[] opts = param.options;
        int currentIdx = 0;
        for (int j = 0; j < opts.length; j++) {
            if (opts[j].equals(param.getString())) { currentIdx = j; break; }
        }
        final int startIdx = currentIdx;
        addRenderableWidget(Button.builder(
            Component.literal(param.label + ": " + opts[startIdx]),
            btn -> {
                String cur = param.getString();
                int idx = 0;
                for (int j = 0; j < opts.length; j++) {
                    if (opts[j].equals(cur)) { idx = j; break; }
                }
                int next = (idx + 1) % opts.length;
                param.set(opts[next]);
                rebuildWidgets();
            }
        ).bounds(x, y, WIDGET_WIDTH, 20).build());
    }

    private void addEntityPicker(ConfigParam param, int x, int y) {
        int halfW = WIDGET_WIDTH / 2 - 2;
        // Left: current entity ID display
        int currentId = param.getInt();
        String display = param.label + ": " + currentId;
        if (currentId > 0) {
            Entity e = Minecraft.getInstance().level != null
                    ? Minecraft.getInstance().level.getEntity(currentId) : null;
            if (e != null) display = param.label + ": " + currentId + " (" + e.getType().toShortString() + ")";
        }
        addRenderableWidget(Button.builder(
            Component.literal(display),
            btn -> {}
        ).bounds(x, y, halfW, 20).build());

        // Right: crosshair pick button — uses entity captured at screen open time
        String pickLabel = lastCrosshairEntity != null
                ? "选取: " + lastCrosshairEntity.getType().toShortString()
                        + " #" + lastCrosshairEntity.getId()
                : "准星选取(无目标)";
        addRenderableWidget(Button.builder(
            Component.literal(pickLabel),
            btn -> {
                if (lastCrosshairEntity != null) {
                    param.set(lastCrosshairEntity.getId());
                    rebuildWidgets();
                }
            }
        ).bounds(x + halfW + 4, y, halfW, 20).build());
    }

    private void addPlayerCycle(ConfigParam param, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        List<String> names = new java.util.ArrayList<>();
        // Use tab list (getOnlinePlayers) — contains ALL server players, not just render distance
        if (mc.getConnection() != null) {
            for (var info : mc.getConnection().getOnlinePlayers()) {
                String name = info.getProfile().getName();
                if (mc.player == null || !name.equals(mc.player.getGameProfile().getName())) {
                    names.add(name);
                }
            }
            java.util.Collections.sort(names);
        }
        if (names.isEmpty()) names.add("(无在线玩家)");

        String current = param.getString();
        addRenderableWidget(Button.builder(
            Component.literal(param.label + ": " + (current.isEmpty() ? names.get(0) : current)),
            btn -> {
                String cur = param.getString();
                int idx = names.indexOf(cur);
                int next = (idx + 1) % names.size();
                String selected = names.get(next);
                if (!"(无在线玩家)".equals(selected)) {
                    param.set(selected);
                }
                btn.setMessage(Component.literal(param.label + ": " + selected));
            }
        ).bounds(x, y, WIDGET_WIDTH, 20).build());
    }

    private void addActionButton(ConfigParam param, int x, int y) {
        addRenderableWidget(Button.builder(
            Component.literal(param.label),
            btn -> {
                if (param.action != null) {
                    param.action.run();
                    rebuildWidgets();
                }
            }
        ).bounds(x, y, WIDGET_WIDTH, 20).build());
    }

    private void addItemPicker(ConfigParam param, int x, int y) {
        int halfW = WIDGET_WIDTH / 2 - 2;
        String regName = param.getString();
        String display = regName.isEmpty() ? "(未选择)" : regName;
        // Truncate long names for display
        if (display.length() > 20) display = "..." + display.substring(display.length() - 17);

        addRenderableWidget(Button.builder(
            Component.literal(param.label + ": " + display),
            btn -> {}
        ).bounds(x, y, halfW, 20).build());

        addRenderableWidget(Button.builder(
            Component.literal("选择物品"),
            btn -> this.minecraft.setScreen(
                new ItemPickerScreen(this, id -> {
                    param.set(id);
                    rebuildWidgets();
                }))
        ).bounds(x + halfW + 4, y, halfW, 20).build());
    }

    private void addBlockPicker(ConfigParam param, int x, int y) {
        int halfW = WIDGET_WIDTH / 2 - 2;
        long packed = param.getLong();
        String display;
        if (packed == 0L) {
            display = param.label + ": 未选择";
        } else {
            BlockPos bp = BlockPos.of(packed);
            display = param.label + ": " + bp.getX() + ", " + bp.getY() + ", " + bp.getZ();
        }

        addRenderableWidget(Button.builder(
            Component.literal(display),
            btn -> {}
        ).bounds(x, y, halfW, 20).build());

        String pickLabel = lastBlockHitResult != null
                ? "准星选取 (" + lastBlockHitResult.getBlockPos().toShortString() + ")"
                : "准星选取(无目标)";
        addRenderableWidget(Button.builder(
            Component.literal(pickLabel),
            btn -> {
                if (lastBlockHitResult != null) {
                    param.set(lastBlockHitResult.getBlockPos().asLong());
                    rebuildWidgets();
                }
            }
        ).bounds(x + halfW + 4, y, halfW, 20).build());
    }

    // --- Hotkey binding ---

    /**
     * Maps GLFW key codes GLFW_KEY_1..GLFW_KEY_0 to slot indices 0..9.
     * Returns -1 if the key is not a number key.
     */
    private int keyToSlot(int keyCode) {
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
            return keyCode - GLFW.GLFW_KEY_1; // 0..8
        }
        if (keyCode == GLFW.GLFW_KEY_0) {
            return 9;
        }
        return -1;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Check for Shift + number key (1-0)
        if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
            int slot = keyToSlot(keyCode);
            if (slot >= 0) {
                // Determine bind type based on mouse position over buttons
                // If hovering over the execute button area, bind EXECUTE; otherwise bind TOGGLE_ENABLED
                HotkeyManager.BindType type = hoveredBindType;
                if (type != null) {
                    HotkeyManager.bind(slot, module.id(), type);
                    int displaySlot = (slot + 1) % 10;
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) {
                        String typeName = type == HotkeyManager.BindType.TOGGLE_ENABLED
                                ? "\u5f00\u5173" : "\u6267\u884c";
                        mc.player.displayClientMessage(
                            Component.literal("\u00a76Shift+" + displaySlot
                                + " \u2192 " + typeName + " " + module.name()),
                            false
                        );
                    }
                    return true;
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Tracks which bind type the mouse is hovering over (set during render) */
    private HotkeyManager.BindType hoveredBindType = null;

    // --- Render ---

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        // Module title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFF5555);

        // Description
        graphics.drawCenteredString(this.font,
            Component.literal(module.description()),
            this.width / 2, 24, 0xAAAAAA);

        // Status line
        String status = module.isAvailable() ? "\u00a7a\u76ee\u6807\u53ef\u7528" : "\u00a7c\u76ee\u6807\u672a\u627e\u5230";
        String enabled = module.isEnabled() ? " | \u00a7a\u5df2\u542f\u7528" : " | \u00a7c\u672a\u542f\u7528";
        graphics.drawCenteredString(this.font,
            Component.literal(status + enabled),
            this.width / 2, 38, 0xFFFFFF);

        super.render(graphics, mouseX, mouseY, partialTick);

        // Determine hovered bind type based on mouse position over bottom buttons
        hoveredBindType = null;
        int btnY = this.height - 52;
        boolean hasTutorial = !module.getTutorial().isEmpty();
        int btnCount = hasTutorial ? 3 : 2;
        int btnW = (WIDGET_WIDTH - (btnCount - 1) * 4) / btnCount;
        int bx = (this.width - WIDGET_WIDTH) / 2;

        // Execute button region
        if (mouseX >= bx && mouseX <= bx + btnW && mouseY >= btnY && mouseY <= btnY + 20) {
            hoveredBindType = HotkeyManager.BindType.EXECUTE;
        }

        // Check if hovering over the "enabled" toggle (first config param if it's a bool named "enabled")
        List<ConfigParam> params = module.getConfigParams();
        int left = (this.width - WIDGET_WIDTH) / 2;
        int top = 50;
        for (int i = 0; i < params.size(); i++) {
            ConfigParam p = params.get(i);
            if ("enabled".equals(p.key) && p.type == ConfigParam.Type.BOOL) {
                int py = top + (i * ROW_HEIGHT) - scrollOffset;
                if (mouseX >= left && mouseX <= left + WIDGET_WIDTH && mouseY >= py && mouseY <= py + 20) {
                    hoveredBindType = HotkeyManager.BindType.TOGGLE_ENABLED;
                }
                break;
            }
        }

        // Show hotkey hint when hovering over a bindable element
        if (hoveredBindType != null) {
            graphics.drawCenteredString(this.font,
                Component.literal("\u00a7e\u6309 Shift+\u6570\u5b57\u952e \u7ed1\u5b9a\u5feb\u6377\u952e"),
                this.width / 2, this.height - 68, 0xFFFF55);
        }

        // Show current bindings for this module
        int toggleSlot = HotkeyManager.findSlot(module.id(), HotkeyManager.BindType.TOGGLE_ENABLED);
        int execSlot = HotkeyManager.findSlot(module.id(), HotkeyManager.BindType.EXECUTE);
        StringBuilder bindInfo = new StringBuilder();
        if (toggleSlot >= 0) {
            bindInfo.append("\u5f00\u5173: Shift+").append((toggleSlot + 1) % 10);
        }
        if (execSlot >= 0) {
            if (bindInfo.length() > 0) bindInfo.append("  |  ");
            bindInfo.append("\u6267\u884c: Shift+").append((execSlot + 1) % 10);
        }
        if (bindInfo.length() > 0) {
            graphics.drawCenteredString(this.font,
                Component.literal("\u00a7b\u5feb\u6377\u952e: " + bindInfo),
                this.width / 2, this.height - 80, 0x55FFFF);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scrollOffset = (int) Math.max(0, Math.min(maxScroll, scrollOffset - delta * 10));
        rebuildWidgets();
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
