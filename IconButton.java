package com.example.farmbuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.function.BooleanSupplier;

/** Кнопка с иконкой предмета и (необязательно) подписью слева направо. */
public class IconButton extends Button {
    private final ItemStack icon;
    private final String label;
    private final BooleanSupplier selected;

    public IconButton(int x, int y, int w, int h, ItemStack icon, String label, String tip,
                      OnPress onPress, BooleanSupplier selected) {
        super(x, y, w, h, Component.empty(), onPress, DEFAULT_NARRATION);
        this.icon = icon;
        this.label = label == null ? "" : label;
        this.selected = selected;
        if (tip != null) setTooltip(Tooltip.create(Component.literal(tip)));
    }

    @Override
    public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        super.renderWidget(g, mx, my, pt);
        int ix = label.isEmpty() ? getX() + (width - 16) / 2 : getX() + 3;
        g.renderItem(icon, ix, getY() + (height - 16) / 2);
        if (!label.isEmpty()) {
            Font f = Minecraft.getInstance().font;
            g.drawString(f, label, getX() + 24, getY() + (height - 8) / 2, 0xFFFFFF);
        }
        if (selected != null && selected.getAsBoolean()) {
            g.renderOutline(getX(), getY(), width, height, 0xFF55FF55);
        }
    }
}
