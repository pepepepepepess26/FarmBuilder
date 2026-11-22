package com.example.farmbuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Кнопка с дверью над окном инвентаря. */
@EventBusSubscriber(modid = FarmBuilderMod.MODID, value = Dist.CLIENT)
public class FarmHooks {

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post e) {
        if (!(e.getScreen() instanceof InventoryScreen inv)) return;
        int x = inv.getGuiLeft() + inv.getXSize() - 20;
        int y = Math.max(2, inv.getGuiTop() - 22);
        e.addListener(new IconButton(x, y, 20, 20, new ItemStack(Items.OAK_DOOR), "", "Автофермы",
                b -> Minecraft.getInstance().setScreen(new FarmMenuScreen()), null));
    }
}
