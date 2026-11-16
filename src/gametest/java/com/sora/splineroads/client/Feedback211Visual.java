package com.sora.splineroads.client;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.config.RoadClientConfig;
import com.sora.splineroads.core.InterchangePlanner.TrumpetStyle;
import com.sora.splineroads.world.*;
import java.nio.file.*;
import java.util.concurrent.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class Feedback211Visual {
  static final Path OUT=Path.of("feedback211");static boolean started,done;static int ticks,stage,ready;static long deadline;static CompletableFuture<?> pending;
  static void log(String s)throws Exception{System.out.println("SR211 NATIVE "+s);Files.writeString(OUT.resolve("results.txt"),s+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
  static net.minecraft.server.level.ServerPlayer player(Minecraft mc){return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());}
  static void camera(Minecraft mc,double x,double y,double z,double tx,double ty,double tz){
    float yaw=(float)Math.toDegrees(Math.atan2(x-tx,tz-z)),pitch=(float)Math.toDegrees(Math.atan2(y-ty,Math.hypot(x-tx,z-tz)));
    mc.getSingleplayerServer().execute(()->{var p=player(mc);p.getAbilities().flying=true;p.setNoGravity(true);p.onUpdateAbilities();p.teleportTo(p.serverLevel(),x,y,z,yaw,pitch);});
    mc.player.setPos(x,y,z);mc.player.setYRot(yaw);mc.player.yRotO=yaw;mc.player.setXRot(pitch);mc.player.xRotO=pitch;
  }
  static void next(int n){stage=n;ready=ticks+180;}
  static void shot(Minecraft mc,String name)throws Exception{try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(OUT.resolve(name+".png"));}log("SCREENSHOT "+name);}
  @SubscribeEvent public static void fog(net.minecraftforge.client.event.ViewportEvent.RenderFog e){
    if(Boolean.getBoolean("road.feedback211Visual")&&java.util.List.of("review","final").contains(System.getProperty("road.feedback211Resume",""))){e.setNearPlaneDistance(10000);e.setFarPlaneDistance(11000);e.setCanceled(true);}
  }
  static void styleButton(Minecraft mc,String prefix){
    var button=mc.screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().startsWith(prefix.equals("a")?"主路定向左转":"主路左转回环")).map(c->(net.minecraft.client.gui.components.Button)c).findFirst().orElseThrow();
    button.onPress();
  }
  static net.minecraft.nbt.CompoundTag captured(Minecraft mc)throws Exception{
    var f=InterchangeScreen.class.getDeclaredField("payload");f.setAccessible(true);return ((net.minecraft.nbt.CompoundTag)f.get(mc.screen)).copy();
  }
  static void review(Minecraft mc)throws Exception{
    if(stage==30){if(!pending.isDone())return;pending.join();log("CONCAVE SINGLE A WORLD PASS");pending=mc.getSingleplayerServer().submit(()->Feedback211Scene.trumpet(player(mc).serverLevel(),TrumpetStyle.B));next(31);return;}
    if(stage==31){if(!pending.isDone())return;pending.join();log("CONCAVE SINGLE B WORLD PASS");camera(mc,630,76,1110,570,60,1200);next(20);ready=ticks+600;return;}
    if(stage==20){shot(mc,"08-supports-final");mc.options.renderDistance().set(32);mc.options.fov().set(90);camera(mc,3000,520,2800,3000,56,2800);next(21);ready=ticks+600;return;}
    if(stage==21){shot(mc,"06-trumpet-a");camera(mc,4600,520,2800,4600,56,2800);next(22);ready=ticks+600;return;}
    if(stage==22){shot(mc,"07-trumpet-b");pending=mc.getSingleplayerServer().submit(()->Feedback211Scene.existingTrumpet(player(mc).serverLevel()));next(23);return;}
    if(stage==23){if(!pending.isDone())return;mc.options.hideGui=false;mc.setScreen(new InterchangeScreen((net.minecraft.nbt.CompoundTag)pending.join()));next(24);return;}
    if(stage==24){shot(mc,"09-single-trumpet-style-a");styleButton(mc,"a");if(Interchanges.read(captured(mc).getCompound("Options")).trumpetStyle()!=TrumpetStyle.B)throw new AssertionError("single UI did not capture B");next(25);return;}
    if(stage==25){shot(mc,"10-single-trumpet-style-b");log("SINGLE UI A-B PASS");mc.setScreen(null);mc.options.hideGui=true;pending=mc.getSingleplayerServer().submit(()->Feedback211Scene.doubleTrumpet(player(mc).serverLevel()));next(26);return;}
    if(stage==26){if(!pending.isDone())return;var command=(net.minecraft.nbt.CompoundTag)pending.join();log("DOUBLE TRUMPET A WORLD PASS");mc.options.hideGui=false;mc.setScreen(new InterchangeScreen(command));next(27);return;}
    if(stage==27){shot(mc,"11-double-trumpet-style-a");styleButton(mc,"a");if(Interchanges.read(captured(mc).getCompound("Options")).trumpetStyle()!=TrumpetStyle.B)throw new AssertionError("double UI did not capture B");next(28);return;}
    if(stage==28){shot(mc,"12-double-trumpet-style-b");var command=captured(mc);mc.setScreen(null);mc.options.hideGui=true;pending=mc.getSingleplayerServer().submit(()->Interchanges.build(player(mc).serverLevel(),null,command));next(29);return;}
    if(stage==29){if(!pending.isDone())return;pending.join();log("DOUBLE TRUMPET B EDIT WORLD PASS");Files.writeString(OUT.resolve("review-complete.txt"),"PASS final supports, A/B single/double native UI, double build and style edit. Fog disabled only for overview; no shader claim.\n");done=true;mc.stop();}
  }
  @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
    if(java.util.Set.of("followup","second","third","performance","performance-round2").contains(System.getProperty("road.feedback211Resume",""))||!Boolean.getBoolean("road.feedback211Visual")||done||e.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();ticks++;
    try{
      if(!started){if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;started=true;Files.createDirectories(OUT);deadline=System.nanoTime()+1800_000_000_000L;
        mc.options.pauseOnLostFocus=false;mc.options.cloudStatus().set(CloudStatus.OFF);mc.options.renderDistance().set(16);mc.options.framerateLimit().set(60);mc.options.enableVsync().set(false);mc.options.guiScale().set(2);mc.resizeDisplay();
        mc.createWorldOpenFlows().loadLevel(mc.screen,"sr211-copy");log("OPEN ISOLATED WORLD");return;}
      if(System.nanoTime()>deadline)throw new AssertionError("timeout stage="+stage);
      if(mc.level==null||mc.player==null||mc.getOverlay()!=null||ticks<ready)return;
      mc.mouseHandler.releaseMouse();mc.player.setDeltaMovement(0,0,0);mc.player.getAbilities().flying=true;mc.player.setNoGravity(true);
      if(stage>=20){review(mc);return;}
      if(stage==0){mc.setScreen(null);mc.options.hideGui=true;RoadClientConfig.SURFACE_BACKEND.set(RoadClientConfig.SurfaceBackend.VBO);camera(mc,630,76,1110,570,60,1200);if("final".equals(System.getProperty("road.feedback211Resume"))){pending=mc.getSingleplayerServer().submit(()->Feedback211Scene.trumpet(player(mc).serverLevel(),TrumpetStyle.A));next(30);}else if("review".equals(System.getProperty("road.feedback211Resume"))){next(20);ready=ticks+600;}else if("supports".equals(System.getProperty("road.feedback211Resume"))){pending=mc.getSingleplayerServer().submit(()->Feedback211Scene.supports(player(mc).serverLevel()));next(6);}else next(1);return;}
      if(stage==1){shot(mc,"01-original-supports");pending=mc.getSingleplayerServer().submit(()->Feedback211Scene.remote(player(mc).serverLevel()));next(2);return;}
      if(stage==2){if(!pending.isDone())return;log(pending.join().toString());camera(mc,120,130,460,95,56,560);next(3);return;}
      if(stage==3){shot(mc,"02-remote-end-connected");pending=mc.getSingleplayerServer().submit(()->Feedback211Scene.corner(player(mc).serverLevel()));next(4);return;}
      if(stage==4){if(!pending.isDone())return;log(pending.join().toString());camera(mc,620,110,750,575,56,800);next(5);return;}
      if(stage==5){shot(mc,"03-filled-sidewalk-corner");pending=mc.getSingleplayerServer().submit(()->Feedback211Scene.supports(player(mc).serverLevel()));next(6);return;}
      if(stage==6){if(!pending.isDone())return;log(pending.join().toString());camera(mc,630,76,1110,570,60,1200);next(7);return;}
      if(stage==7){shot(mc,"04-median-and-centre-supports");if("supports".equals(System.getProperty("road.feedback211Resume"))){log("SUPPORT RECHECK COMPLETE");done=true;mc.stop();return;}pending=mc.getSingleplayerServer().submit(()->Feedback211Scene.spui(player(mc).serverLevel()));next(8);return;}
      if(stage==8){if(!pending.isDone())return;log(pending.join().toString());camera(mc,820,180,1190,610,62,1200);next(9);return;}
      if(stage==9){shot(mc,"05-direct-spui");pending=mc.getSingleplayerServer().submit(()->Feedback211Scene.trumpet(player(mc).serverLevel(),TrumpetStyle.A));next(10);return;}
      if(stage==10){if(!pending.isDone())return;pending.join();log("TRUMPET A WORLD PASS");camera(mc,3000,700,2800,3000,56,2800);next(11);return;}
      if(stage==11){shot(mc,"06-trumpet-a");pending=mc.getSingleplayerServer().submit(()->Feedback211Scene.trumpet(player(mc).serverLevel(),TrumpetStyle.B));next(12);return;}
      if(stage==12){if(!pending.isDone())return;pending.join();log("TRUMPET B WORLD PASS");camera(mc,4600,700,2800,4600,56,2800);next(13);return;}
      if(stage==13){shot(mc,"07-trumpet-b");Files.writeString(OUT.resolve("complete.txt"),"PASS actual copied-world construction and screenshots; no shader claim\n");log("COMPLETE");done=true;mc.stop();}
    }catch(Throwable f){done=true;f.printStackTrace();try{log("FAIL stage="+stage+" "+f);Files.writeString(OUT.resolve("failure.txt"),f.toString());}catch(Exception ignored){}mc.stop();}
  }
}

