package com.sora.splineroads.world;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Dedicated server startup with neither optional client map mod installed. */
@Mod.EventBusSubscriber(modid="splineroads")
public final class FeedbackFix7Server {
  private static int ticks;
  @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event){
    if(!Boolean.getBoolean("road.feedbackFix7Server")||event.phase!=TickEvent.Phase.END||++ticks!=20)return;
    var mods=net.minecraftforge.fml.ModList.get();
    boolean ok=!mods.isLoaded("xaeroworldmap")&&!mods.isLoaded("xaerominimap")&&event.getServer().isDedicatedServer();
    System.out.println(ok?"FIX7_SERVER_PASS dedicated=true xaero=false":"FIX7_SERVER_FAIL");
    event.getServer().halt(false);
  }
}
