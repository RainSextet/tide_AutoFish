package com.deepseek.tideautofish;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = TideAutoFish.MODID, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        while (KeyBindings.TOGGLE.consumeClick()) {
            AutoFishController.toggle(minecraft);
        }
        AutoFishController.tick(minecraft);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        AutoFishController.render(event.getGuiGraphics());
    }
}
