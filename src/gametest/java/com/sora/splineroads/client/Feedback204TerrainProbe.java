package com.sora.splineroads.client;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.config.RoadClientConfig;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.world.Feedback204VisualScene.BridgeCase;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
/** Read-only world-copy control: identical camera with SR draw registered/unregistered and vanilla rebuilt. */
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class Feedback204TerrainProbe {
  static final Path OUT=Path.of("feedback204/probe");static boolean started,done,hidden;static int ticks,stage,ready;static BridgeCase target;
  static void log(String s)throws Exception{Files.writeString(OUT.resolve("results.txt"),s+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
  static void shot(Minecraft mc,String name)throws Exception{try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(OUT.resolve(name+".png"));}log(name+" "+Feedback204SceneReady.detail());}
  @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
    if(!Boolean.getBoolean("road.feedback204TerrainProbe")||done||e.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();ticks++;
    try{
      if(!started){if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;started=true;Files.createDirectories(OUT);
        mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(16);mc.options.framerateLimit().set(60);mc.options.enableVsync().set(false);mc.options.guiScale().set(2);mc.resizeDisplay();
        mc.createWorldOpenFlows().loadLevel(mc.screen,System.getProperty("road.feedback204ProbeWorld"));return;}
      if(ticks>12000)throw new AssertionError("probe timeout stage="+stage);
      if(mc.level==null||mc.player==null||mc.getOverlay()!=null||ticks<ready)return;
      mc.mouseHandler.releaseMouse();mc.player.setDeltaMovement(0,0,0);mc.player.getAbilities().flying=true;mc.player.setNoGravity(true);
      if(stage==0){mc.setScreen(null);mc.options.hideGui=true;RoadClientConfig.SURFACE_BACKEND.set(RoadClientConfig.SurfaceBackend.VBO);
        var server=mc.getSingleplayerServer();server.execute(()->{var p=server.getPlayerList().getPlayer(mc.player.getUUID());p.getAbilities().flying=true;p.setNoGravity(true);p.onUpdateAbilities();p.teleportTo(p.serverLevel(),2526.5,-12,1966.5,28.141602f,13.613949f);});
        mc.player.setPos(2526.5,-12,1966.5);mc.player.setYRot(28.141602f);mc.player.yRotO=28.141602f;mc.player.setXRot(13.613949f);mc.player.xRotO=13.613949f;stage=1;ready=ticks+200;return;}
      if(stage==1){var b=ClientRoads.INDEX.roads.values().stream().filter(v->Math.abs(v.record.start().position().x()-2480.5)<.01&&Math.abs(v.record.start().position().z()-2000.5)<.01).findFirst();if(b.isEmpty())return;
        target=new BridgeCase("suspension-off",b.get().record.id(),RoadInfrastructure.Bridge.SUSPENSION,false,true,2480.5,2000.5,-60,-42,120);stage=2;return;}
      if(stage==2){if(!Feedback204SceneReady.ready(target))return;shot(mc,"01-baseline-sr");MinecraftForge.EVENT_BUS.unregister(RoadRenderer.class);hidden=true;stage=3;ready=ticks+60;return;}
      if(stage==3){shot(mc,"02-vanilla-without-sr-draw");mc.levelRenderer.allChanged();stage=4;ready=ticks+200;return;}
      if(stage==4){if(!Feedback204SceneReady.ready(target))return;shot(mc,"03-vanilla-rebuilt-without-sr-draw");MinecraftForge.EVENT_BUS.register(RoadRenderer.class);hidden=false;stage=5;ready=ticks+200;return;}
      if(stage==5){if(!Feedback204SceneReady.ready(target))return;shot(mc,"04-sr-restored");Files.writeString(OUT.resolve("complete.txt"),"Four same-camera diagnostic controls captured; no construction or road-data edit. Visual interpretation required.\n");done=true;mc.stop();}
    }catch(Throwable f){try{log("FAIL "+f);if(hidden)MinecraftForge.EVENT_BUS.register(RoadRenderer.class);}catch(Exception ignored){}done=true;mc.stop();}
  }
}
