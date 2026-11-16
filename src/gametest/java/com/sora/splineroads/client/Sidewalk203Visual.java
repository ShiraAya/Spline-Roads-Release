package com.sora.splineroads.client;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.config.RoadClientConfig;
import com.sora.splineroads.world.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Opt-in fresh-world fixture: -Droad.sidewalk203Visual=true. No input automation. */
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class Sidewalk203Visual {
 private static final Path OUT=Path.of("feedback203-sidewalk");
 private static boolean started,done;private static int ticks,ready,stage,checks;private static long deadline,marked;
 private static UUID bridgeId;private static CompletableFuture<?> pending;private static Sidewalk203VisualScene.Scene scene;
 private static void ok(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 private static void next(int n,int delay){stage=n;ready=ticks+delay;}
 private static net.minecraft.server.level.ServerPlayer player(Minecraft mc){return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());}
 private static net.minecraft.world.level.levelgen.WorldDimensions dimensions(net.minecraft.core.RegistryAccess r){var flat=net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings.getDefault(r.registryOrThrow(Registries.BIOME).asLookup(),r.registryOrThrow(Registries.STRUCTURE_SET).asLookup(),r.registryOrThrow(Registries.PLACED_FEATURE).asLookup());return r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions().replaceOverworldGenerator(r,new net.minecraft.world.level.levelgen.FlatLevelSource(flat));}
 private static void camera(Minecraft mc){camera(mc,-17,scene.ground()+3.8,7,-7,scene.ground()+.7,16);}
 private static void camera(Minecraft mc,double x,double y,double z,double tx,double ty,double tz){
  float yaw=(float)Math.toDegrees(Math.atan2(x-tx,tz-z)),pitch=(float)Math.toDegrees(Math.atan2(y-ty,Math.hypot(x-tx,z-tz)));
  mc.getSingleplayerServer().execute(()->{var p=player(mc);p.getAbilities().flying=true;p.setNoGravity(true);p.onUpdateAbilities();p.teleportTo(p.serverLevel(),x,y,z,yaw,pitch);});
  mc.player.setPos(x,y,z);mc.player.setYRot(yaw);mc.player.yRotO=yaw;mc.player.setXRot(pitch);mc.player.xRotO=pitch;
 }
 private static Object field(Class<?> c,String name)throws Exception{var f=c.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
 private static boolean settled()throws Exception{return ((Map<?,?>)field(RoadRenderer.class,"jobs")).isEmpty()&&field(RoadRenderer.class,"pendingUpload")==null&&((Collection<?>)field(RoadRenderer.class,"dirty")).isEmpty();}
 private static double minimum(Minecraft mc,BlockPos p){
  var state=mc.level.getBlockState(p);var model=mc.getBlockRenderer().getBlockModel(state);var data=model.getModelData(mc.level,p,state,ModelData.EMPTY);double low=Double.POSITIVE_INFINITY;
  var directions=new ArrayList<Direction>();directions.add(null);directions.addAll(List.of(Direction.values()));
  for(var layer:model.getRenderTypes(state,RandomSource.create(1),data))for(var face:directions)for(var q:model.getQuads(state,face,RandomSource.create(1),data,layer)){
   var v=q.getVertices();for(int i=0;i<4;i++)low=Math.min(low,Float.intBitsToFloat(v[i*8+1]));
  }
  return low;
 }
 private static void contacts(Minecraft mc){
  for(var p:scene.blocks()){ok(Math.abs(minimum(mc,p)+.8)<1e-5,"native model has air gap "+p);var lower=p.below();var shape=mc.level.getBlockState(lower).getCollisionShape(mc.level,lower);ok(shape.toAabbs().stream().anyMatch(box->box.contains(.5,.6,.5)),"native contact collision missing "+p);}
  ok(Math.abs(minimum(mc,scene.outside()))<1e-6,"outside negative control stretched");
 }
 private static void shot(Minecraft mc,String name)throws Exception{try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(OUT.resolve(name+".png"));}System.out.println("SR203 SIDEWALK CAPTURE "+name);}
 private static Object value(Object instance,String name)throws Exception{var f=instance.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(instance);}
 private static Object call(Object instance,String name)throws Exception{var m=instance.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(instance);}
 private static net.minecraft.client.gui.components.Button button(RoadScreen screen,String prefix){return screen.children().stream().filter(v->v instanceof net.minecraft.client.gui.components.Button c&&c.getMessage().getString().startsWith(prefix)).map(v->(net.minecraft.client.gui.components.Button)v).findFirst().orElseThrow(()->new AssertionError("missing button "+prefix));}
 private static void press(RoadScreen screen,String prefix){var b=button(screen,prefix);ok(b.active,"inactive button "+prefix);b.onPress();}
 private static void page(RoadScreen screen,int page)throws Exception{for(int n=0;n<9&&(int)value(screen,"optionsPage")!=page;n++)press(screen,"▶");ok((int)value(screen,"optionsPage")==page,"could not navigate options page "+page);}
 private static RoadProfile.Options options(RoadScreen screen)throws Exception{return (RoadProfile.Options)value(screen,"options");}
 private static void finish(Minecraft mc)throws Exception{Files.writeString(OUT.resolve("passed.txt"),"PASS checks="+checks+"; VBO/TERRAIN contacts; outside negative; cube removal; real packet resync; real editor TEMPLATE/support three-state/AUTO retention/apron three-state; disabled-support template bridge built; screenshots require inspection\n");done=true;System.out.println("SR203 SIDEWALK NATIVE PASS checks="+checks);mc.stop();}
 @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
  if(!Boolean.getBoolean("road.sidewalk203Visual")||done||e.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();ticks++;
  try{
   if(!started){if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;started=true;Files.createDirectories(OUT);deadline=System.nanoTime()+480_000_000_000L;mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);mc.options.pauseOnLostFocus=false;mc.options.enableVsync().set(false);mc.options.guiScale().set(2);mc.options.renderDistance().set(6);mc.options.framerateLimit().set(60);mc.resizeDisplay();mc.createWorldOpenFlows().createFreshLevel("sr203-sidewalk-"+System.currentTimeMillis(),new LevelSettings("SR203 isolated contacts",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(203L,false,false),Sidewalk203Visual::dimensions);return;}
   if(System.nanoTime()>deadline)throw new AssertionError("timeout stage="+stage);
   if(mc.level==null||mc.player==null||mc.getOverlay()!=null||ticks<ready)return;
   mc.mouseHandler.releaseMouse();mc.player.setDeltaMovement(0,0,0);mc.player.getAbilities().flying=true;mc.player.setNoGravity(true);
   if(stage==0){mc.setScreen(null);RoadClientConfig.SURFACE_BACKEND.set(RoadClientConfig.SurfaceBackend.VBO);pending=mc.getSingleplayerServer().submit(()->Sidewalk203VisualScene.create(player(mc)));next(1,20);return;}
   if(stage==1){if(!pending.isDone())return;scene=(Sidewalk203VisualScene.Scene)pending.join();camera(mc);mc.options.hideGui=true;next(2,140);return;}
   if(stage==2){if(!ClientRoads.INDEX.roads.containsKey(scene.road())||!settled())return;contacts(mc);shot(mc,"01-vbo-cubes-contact-and-outside-control");RoadClientConfig.SURFACE_BACKEND.set(RoadClientConfig.SurfaceBackend.TERRAIN);next(3,160);return;}
   if(stage==3){if(!settled()||!RoadTerrainModels.status().startsWith("terrain"))return;contacts(mc);shot(mc,"02-terrain-cubes-contact-and-outside-control");pending=mc.getSingleplayerServer().submit(()->{player(mc).serverLevel().setBlock(scene.blocks().get(0),Blocks.AIR.defaultBlockState(),3);return true;});next(4,40);return;}
   if(stage==4){if(!pending.isDone()||!mc.level.getBlockState(scene.blocks().get(0)).isAir())return;pending.join();var lower=scene.blocks().get(0).below();ok(mc.level.getBlockState(lower).getCollisionShape(mc.level,lower).toAabbs().stream().noneMatch(box->box.contains(.5,.6,.5)),"removing cube left stale native collision");shot(mc,"03-cube-removed");pending=mc.getSingleplayerServer().submit(()->{player(mc).serverLevel().setBlock(scene.blocks().get(0),Blocks.BLUE_WOOL.defaultBlockState(),3);return true;});next(5,50);return;}
   if(stage==5){if(!pending.isDone()||!mc.level.getBlockState(scene.blocks().get(0)).is(Blocks.BLUE_WOOL))return;pending.join();RoadClientConfig.SURFACE_BACKEND.set(RoadClientConfig.SurfaceBackend.VBO);marked=SidewalkBlockModels.markedSections();ClientRoads.INDEX.remove(scene.road());RoadRenderer.changed(Set.of(scene.road()));ok(Math.abs(minimum(mc,scene.blocks().get(0)))<1e-6,"no-road model must keep vanilla base");ok(SidewalkBlockModels.markedSections()>marked,"removing road did not invalidate native contact sections");next(6,80);return;}
   if(stage==6){shot(mc,"04-client-road-evicted-negative-control");marked=SidewalkBlockModels.markedSections();com.sora.splineroads.net.RoadNetwork.requestRoadSync();pending=CompletableFuture.completedFuture(true);next(7,140);return;}
   if(stage==7){if(!pending.isDone()||!ClientRoads.INDEX.roads.containsKey(scene.road())||!settled())return;pending.join();ok(SidewalkBlockModels.markedSections()>marked,"road packet did not invalidate already-loaded cube models");contacts(mc);shot(mc,"05-vbo-road-resync-rebuilt-contacts");pending=mc.getSingleplayerServer().submit(()->{var p=player(mc);RoadTool.openRoad(p,RoadData.get(p.serverLevel()).index.roads.get(scene.road()).record,false);return true;});next(8,20);return;}
   if(stage==8){if(!pending.isDone()||!(mc.screen instanceof RoadScreen screen))return;pending.join();mc.options.hideGui=false;page(screen,2);for(int n=0;n<RoadInfrastructure.Bridge.values().length&&options(screen).infrastructure().bridge()!=RoadInfrastructure.Bridge.TEMPLATE;n++)press(screen,options(screen).infrastructure().bridge().label+" ▸");ok(options(screen).infrastructure().bridge()==RoadInfrastructure.Bridge.TEMPLATE,"template not selected");ok(options(screen).infrastructure().supportsEnabled()&&options(screen).infrastructure().autoSpan(),"initial span not automatic");next(9,30);return;}
   if(stage==9){var screen=(RoadScreen)mc.screen;shot(mc,"06-ui-template-auto");press(screen,"配跨：自动");ok(options(screen).infrastructure().supportsEnabled()&&!options(screen).infrastructure().autoSpan(),"manual span state not retained");next(10,25);return;}
   if(stage==10){var screen=(RoadScreen)mc.screen;shot(mc,"07-ui-template-manual");press(screen,"配跨：手动");ok(!options(screen).infrastructure().supportsEnabled(),"span not disabled");next(11,25);return;}
   if(stage==11){var screen=(RoadScreen)mc.screen;shot(mc,"08-ui-template-no-supports");press(screen,(String)call(screen,"structureName"));press(screen,(String)call(screen,"structureName"));ok(value(screen,"structure")==Structure.AUTO,"structure did not return to AUTO");ok(!options(screen).infrastructure().supportsEnabled(),"AUTO discarded disabled supports");var settings=(Settings)call(screen,"settings");ok(!settings.options().infrastructure().supportsEnabled(),"settings capture discarded disabled supports");next(12,30);return;}
   if(stage==12){var screen=(RoadScreen)mc.screen;shot(mc,"09-ui-auto-no-supports");page(screen,6);ok(button(screen,"人行道包边：").getMessage().getString().contains("同人行道"),"same sidewalk label missing");next(13,25);return;}
   if(stage==13){var screen=(RoadScreen)mc.screen;shot(mc,"10-ui-apron-same-sidewalk");press(screen,"人行道包边：");ok(options(screen).streetscape().apron()==RoadStreetscape.Apron.TERRAIN,"terrain apron not selected");next(14,25);return;}
   if(stage==14){var screen=(RoadScreen)mc.screen;shot(mc,"11-ui-apron-terrain");press(screen,"人行道包边：");ok(options(screen).streetscape().apron()==RoadStreetscape.Apron.NONE,"none apron not selected");next(15,25);return;}
   if(stage==15){var screen=(RoadScreen)mc.screen;shot(mc,"12-ui-apron-none");press(screen,"人行道包边：");ok(options(screen).streetscape().apron()==RoadStreetscape.Apron.ROAD,"apron cycle did not return to same sidewalk");press(screen,"取消");mc.setScreen(null);mc.options.hideGui=true;pending=mc.getSingleplayerServer().submit(()->Sidewalk203VisualScene.bridge(player(mc)));next(16,20);return;}
   if(stage==16){if(!pending.isDone())return;bridgeId=(UUID)pending.join();camera(mc,64,scene.ground()+18,-14,45,scene.ground()+10,40);next(17,140);return;}
   if(stage==17){var built=ClientRoads.INDEX.roads.get(bridgeId);if(built==null||!settled())return;ok(built.record.settings().options().infrastructure().bridge()==RoadInfrastructure.Bridge.TEMPLATE,"client template identity lost");ok(!built.record.settings().options().infrastructure().supportsEnabled(),"client disabled supports lost");ok(built.record.structures().stream().noneMatch(RoadStructures.Part::pier),"client received unexpected template pier");shot(mc,"13-template-bridge-no-supports-overview");camera(mc,61,scene.ground()+6,25,45,scene.ground()+10,44);next(18,100);return;}
   if(stage==18){if(!settled())return;shot(mc,"14-template-bridge-no-supports-under-deck");finish(mc);}

  }catch(Throwable failure){done=true;failure.printStackTrace();try{Files.writeString(OUT.resolve("failure.txt"),"stage="+stage+" "+failure);}catch(Exception ignored){}mc.stop();}
 }
}
