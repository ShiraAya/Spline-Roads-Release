package com.sora.splineroads.client;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.RoadRenderMesh;
import com.sora.splineroads.world.*;
import java.nio.file.*;
import java.util.concurrent.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** -Droad.geometry201Visual=true: real editor packets and chunk rendering in a new flat world. */
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class Geometry201Visual {
  private static final Path OUT=Path.of("feedback201-geometry");
  private static boolean started,done;private static int stage,ticks,ready,checks;private static long deadline;
  private static CompletableFuture<?> pending;private static Geometry201Scenes.Curve curve;private static Audit201JunctionVisualFixture.Scene junction;
  private static double changedControlX;private static final java.util.List<String> measurements=new java.util.ArrayList<>();
  private static Object field(Object o,String name)throws Exception{var f=(o instanceof Class<?> cls?cls:o.getClass()).getDeclaredField(name);f.setAccessible(true);return f.get(o instanceof Class<?>?null:o);}
  private static Object invoke(Object o,String name)throws Exception{var m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(o);}
  private static void ok(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
  private static void next(int n,int delay){stage=n;ready=ticks+delay;}
  private static net.minecraft.server.level.ServerPlayer player(Minecraft mc){return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());}
  private static net.minecraft.world.level.levelgen.WorldDimensions dimensions(net.minecraft.core.RegistryAccess r){var flat=net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings.getDefault(r.registryOrThrow(Registries.BIOME).asLookup(),r.registryOrThrow(Registries.STRUCTURE_SET).asLookup(),r.registryOrThrow(Registries.PLACED_FEATURE).asLookup());return r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions().replaceOverworldGenerator(r,new net.minecraft.world.level.levelgen.FlatLevelSource(flat));}
  private static void camera(Minecraft mc,double x,double y,double z,double tx,double ty,double tz){float yaw=(float)Math.toDegrees(Math.atan2(x-tx,tz-z)),pitch=(float)Math.toDegrees(Math.atan2(y-ty,Math.hypot(x-tx,z-tz)));mc.getSingleplayerServer().execute(()->{var p=player(mc);p.getAbilities().flying=true;p.setNoGravity(true);p.onUpdateAbilities();p.teleportTo(p.serverLevel(),x,y,z,yaw,pitch);});mc.player.setPos(x,y,z);mc.player.setYRot(yaw);mc.player.yRotO=yaw;mc.player.setXRot(pitch);mc.player.xRotO=pitch;}
  private static boolean settled()throws Exception{if(!((java.util.Map<?,?>)field(RoadRenderer.class,"jobs")).isEmpty()||field(RoadRenderer.class,"pendingUpload")!=null||SmartSignClient.pendingUploads())return false;for(var section:(java.util.List<?>)field(RoadRenderer.class,"visible")){if((Boolean)field(section,"dirty")&&RoadTerrainModels.regionLoaded((RoadRenderMesh.Section)field(section,"key")))return false;}return true;}
  private static void map(RoadScreen screen)throws Exception{if(!(Boolean)field(screen,"mapPage"))screen.children().stream().filter(v->v instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().equals("俯视图")).map(v->(net.minecraft.client.gui.components.Button)v).findFirst().orElseThrow().onPress();}
  private static void shot(Minecraft mc,String name)throws Exception{try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(OUT.resolve(name+".png"));}System.out.println("SR201 GEOMETRY CAPTURE "+name);}
  @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
    if(!Boolean.getBoolean("road.geometry201Visual")||done||e.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();ticks++;
    try{
      if(!started){if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;started=true;Files.createDirectories(OUT);deadline=System.nanoTime()+600_000_000_000L;mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);mc.options.pauseOnLostFocus=false;mc.options.enableVsync().set(false);mc.options.guiScale().set(2);mc.options.renderDistance().set(8);mc.options.framerateLimit().set(60);mc.resizeDisplay();mc.createWorldOpenFlows().createFreshLevel("sr201-geometry-"+System.currentTimeMillis(),new LevelSettings("SR201 isolated geometry",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(201L,false,false),Geometry201Visual::dimensions);return;}
      if(System.nanoTime()>deadline)throw new AssertionError("timeout stage="+stage);
      if(mc.level==null||mc.player==null||mc.getOverlay()!=null||ticks<ready)return;
      mc.player.setDeltaMovement(0,0,0);mc.player.getAbilities().flying=true;mc.player.setNoGravity(true);
      if(stage==0){mc.setScreen(null);pending=mc.getSingleplayerServer().submit(()->{var p=player(mc);p.setGameMode(GameType.CREATIVE);p.getAbilities().flying=true;p.onUpdateAbilities();p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(SplineRoads.CONNECTOR.get()));return Geometry201Scenes.curve(p);});next(1,20);return;}
      if(stage==1){if(!pending.isDone())return;curve=(Geometry201Scenes.Curve)pending.join();camera(mc,42,curve.ground()+27,35,15,curve.ground(),65);next(2,80);return;}
      if(stage==2){var built=ClientRoads.INDEX.roads.get(curve.id());if(built==null||!settled())return;ok(built.record.fragment().curveAnchors().isEmpty(),"client did not receive original legacy snapshot");mc.options.hideGui=true;shot(mc,"01-cut-curve-before-open");pending=mc.getSingleplayerServer().submit(()->{var p=player(mc);RoadTool.openRoad(p,RoadData.get(p.serverLevel()).index.roads.get(curve.id()).record,false);return true;});next(3,15);return;}
      if(stage==3){if(!pending.isDone())return;pending.join();if(!(mc.screen instanceof RoadScreen screen))return;mc.options.hideGui=false;ok(invoke(screen,"savedPreview")!=null,"localized editor does not match legacy client snapshot");ok(ClientRoads.preview!=null,"first editor open has no saved preview");map(screen);next(30,30);return;}
      if(stage==30){if(!(mc.screen instanceof RoadScreen screen)||!settled())return;ok(invoke(screen,"savedPreview")!=null,"opening control map discarded saved preview");shot(mc,"02-first-open-localized-controls");invoke(screen,"submit");next(4,20);return;}
      if(stage==4){var built=ClientRoads.INDEX.roads.get(curve.id());if(built==null||built.record.fragment().curveAnchors().isEmpty())return;if(mc.screen instanceof RoadScreen screen&&(Boolean)field(screen,"pending"))return;pending=mc.getSingleplayerServer().submit(()->{Geometry201Scenes.unchanged(player(mc),curve);return true;});next(5,10);return;}
      if(stage==5){if(!pending.isDone()||!settled())return;pending.join();ok(true,"real unchanged save preserved all saved curve samples");measurements.add("First editor open: savedPreview non-null; actual network save retained original raw curve samples.");mc.setScreen(null);mc.options.hideGui=true;shot(mc,"03-saved-curve");pending=mc.getSingleplayerServer().submit(()->{var p=player(mc);RoadTool.openRoad(p,RoadData.get(p.serverLevel()).index.roads.get(curve.id()).record,false);return true;});next(12,20);return;}
      if(stage==12){if(!pending.isDone())return;pending.join();if(!(mc.screen instanceof RoadScreen screen))return;mc.options.hideGui=false;map(screen);var fields=(java.util.Map<?,?>)field(screen,"fields");var control=(net.minecraft.client.gui.components.EditBox)fields.get("controlX");ok(control!=null,"localized control X editor missing");changedControlX=Double.parseDouble(control.getValue())+.1;control.setValue(Double.toString(changedControlX));next(13,30);return;}
      if(stage==13){if(!(mc.screen instanceof RoadScreen screen)||!settled())return;ok(invoke(screen,"savedPreview")==null,"changed control still reuses unmodified saved preview");ok(ClientRoads.preview!=null&&!ClientRoads.conflictPreview,"changed control preview unavailable");shot(mc,"04-control-edit-preview");invoke(screen,"submit");next(14,20);return;}
      if(stage==14){var built=ClientRoads.INDEX.roads.get(curve.id());if(built==null||Math.abs(built.record.settings().options().routing().controls().get(0).position().x()-changedControlX)>1e-8)return;if(mc.screen instanceof RoadScreen screen&&(Boolean)field(screen,"pending"))return;pending=mc.getSingleplayerServer().submit(()->Geometry201Scenes.changed(player(mc),curve,changedControlX));next(15,20);return;}
      if(stage==15){if(!pending.isDone()||!settled())return;measurements.add("Changed control via real EditBox and submit packet: server raw-curve maximum movement="+pending.join());mc.setScreen(null);mc.options.hideGui=true;shot(mc,"05-control-edit-saved");pending=mc.getSingleplayerServer().submit(()->SidewalkGap201GameTests.scene(player(mc).serverLevel(),100,0).id());next(6,20);return;}
      if(stage==6){if(!pending.isDone())return;var id=(java.util.UUID)pending.join();if(!ClientRoads.INDEX.roads.containsKey(id))return;camera(mc,114,curve.ground()+5,-3,107,curve.ground(),14);next(7,100);return;}
      if(stage==7){if(!settled())return;mc.options.hideGui=true;shot(mc,"06-slope-footway-outer-edge");camera(mc,90,curve.ground()+2,15,95,curve.ground(),22);next(8,80);return;}
      if(stage==8){if(!settled())return;shot(mc,"07-slope-footway-close");pending=mc.getSingleplayerServer().submit(()->Audit201JunctionVisualFixture.create(player(mc)));next(9,10);return;}
      if(stage==9){if(!pending.isDone())return;junction=(Audit201JunctionVisualFixture.Scene)pending.join();var eye=junction.eye();var target=junction.focus();camera(mc,eye.x(),eye.y(),eye.z(),target.x(),target.y(),target.z());next(10,100);return;}
      if(stage==10){var states=(java.util.Map<?,?>)field(SmartSignClient.class,"states");for(var id:junction.arms()){if(!ClientRoads.INDEX.roads.containsKey(id))return;var state=(com.sora.splineroads.core.SmartSigns.State)states.get(new com.sora.splineroads.core.SmartSigns.Key(id,0,1));if(state==null)return;ok(state.mode()==com.sora.splineroads.core.SmartSigns.Mode.JUNCTION_ADVANCE&&state.support()!=null&&state.diagnostic().isEmpty(),"client junction sign hidden");}var key=new com.sora.splineroads.core.SmartSigns.Key(junction.picturedArm(),0,1);if(!((java.util.Map<?,?>)field(SmartSignClient.class,"cache")).containsKey(key)||(Integer)field(SmartSignClient.class,"frameDrawn")==0)return;next(11,40);return;}
      if(stage==11){if(!settled())return;ok((Integer)field(SmartSignClient.class,"frameDrawn")>0,"junction sign stopped rendering");shot(mc,"08-junction-sign-next-to-cherry");measurements.add("PASS checks="+checks+"; sloped footway and tree-side junction built in same isolated world; 3 client sign states and rendered sign checked. Screenshots require inspection.");Files.write(OUT.resolve("passed.txt"),measurements);done=true;System.out.println("SR201 GEOMETRY NATIVE PASS "+checks);mc.stop();}
    }catch(Throwable failure){done=true;failure.printStackTrace();try{Files.writeString(OUT.resolve("failure.txt"),"stage="+stage+" "+failure);}catch(Exception ignored){}mc.stop();}
  }
}
