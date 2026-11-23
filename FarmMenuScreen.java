package com.example.farmbuilder;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Меню автоферм: выбор чертежа, список нужных блоков, кнопки Построить / Пауза / Отмена. */
public class FarmMenuScreen extends Screen {
    private static final int PAGE = 4;
    private final List<Farms.Blueprint> list;
    private int sel = 0;
    private int page = 0;

    public FarmMenuScreen() {
        super(Component.literal("Автофермы"));
        Farms.reload();
        list = Farms.all();
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int start = page * PAGE;
        for (int i = 0; i < PAGE && start + i < list.size(); i++) {
            final int idx = start + i;
            Farms.Blueprint b = list.get(idx);
            addRenderableWidget(new IconButton(cx - 150, 28 + i * 26, 104, 22, new ItemStack(b.icon), b.name,
                    b.title, btn -> sel = idx, () -> sel == idx));
        }
        if (list.size() > PAGE) {
            int py = 28 + PAGE * 26;
            Button prev = Button.builder(Component.literal("<"), b -> {
                page--;
                rebuildWidgets();
            }).bounds(cx - 150, py, 34, 18).build();
            prev.active = page > 0;
            addRenderableWidget(prev);
            Button next = Button.builder(Component.literal(">"), b -> {
                page++;
                rebuildWidgets();
            }).bounds(cx - 112, py, 34, 18).build();
            next.active = (page + 1) * PAGE < list.size();
            addRenderableWidget(next);
        }

        int y = height - 28;
        addRenderableWidget(Button.builder(Component.literal("Построить"), b -> {
            if (FarmBuilder.start(list.get(sel))) onClose();
        }).bounds(cx - 100, y, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Закрыть"), b -> onClose())
                .bounds(cx + 4, y, 96, 20).build());

        if (FarmBuilder.state != FarmBuilder.State.IDLE) {
            int y2 = y - 24;
            String t = FarmBuilder.state == FarmBuilder.State.RUNNING ? "Пауза" : "Продолжить";
            addRenderableWidget(Button.builder(Component.literal(t), b -> {
                FarmBuilder.toggle();
                onClose();
            }).bounds(cx - 100, y2, 96, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Отменить стройку"), b -> {
                FarmBuilder.cancel();
                onClose();
            }).bounds(cx + 4, y2, 96, 20).build());
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        int cx = width / 2;
        g.drawCenteredString(font, title, cx, 10, 0xFFFFFF);
        if (list.size() > PAGE) {
            int pages = (list.size() + PAGE - 1) / PAGE;
            g.drawString(font, (page + 1) + "/" + pages, cx - 72, 28 + PAGE * 26 + 5, 0xAAAAAA);
        }

        Farms.Blueprint b = list.get(sel);
        int x = cx - 38;
        int y = 28;
        int w = 188;
        g.drawString(font, b.title + "  (" + b.w + "x" + b.d + "x" + b.h + ")", x, y, 0xFFFF55);
        y += 12;
        if (!b.desc.isEmpty()) {
            g.drawWordWrap(font, Component.literal(b.desc), x, y, w, 0xCCCCCC);
            y += font.split(Component.literal(b.desc), w).size() * 9 + 4;
        }

        g.drawString(font, "Нужно:", x, y, 0xFFFFFF);
        y += 11;
        List<Farms.Req> reqs = b.requirements();
        boolean compact = reqs.size() > 6;
        for (Farms.Req r : reqs) {
            int have = FarmBuilder.countItems(r.entry().match());
            int color = have >= r.count() ? 0x55FF55 : 0xFF5555;
            if (compact) {
                g.drawString(font, r.entry().label() + "  " + have + "/" + r.count(), x, y, color);
                y += 11;
            } else {
                g.renderItem(new ItemStack(r.entry().icon()), x, y - 3);
                g.drawString(font, r.entry().label() + "  " + have + "/" + r.count(), x + 20, y + 1, color);
                y += 17;
            }
        }
        y += 2;
        g.drawWordWrap(font, Component.literal(
                "Наведите прицел на землю и нажмите «Построить». Эта точка - передний левый угол: "
                        + "постройка пойдёт вперёд и вправо. Двинули камеру - бот встанет на паузу."),
                x, y, w, 0x999999);

        String st = FarmBuilder.statusLine();
        if (!st.isEmpty()) g.drawCenteredString(font, st, cx, height - 62, 0x55FFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
