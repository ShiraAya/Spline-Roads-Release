package com.sora.splineroads.client;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.world.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Isolated real map selection, network acknowledgement, geometry preview, build and history. */
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class Planner201InterchangeVisual {
  private static final Path OUT=Path.of("feedback201-interchange");
  private static boolean started,done;private static int stage,ticks,ready,checks;private static long deadline;
  private static CompletableFuture<?> pending;private static long[] originalPoints;private static int initialRoads;
  private static Object field(Object target,String name)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
  private static void set(Object target,String name,Object value)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
  private static Object invoke(Object target,String name,Class<?>[] types,Object...args)throws Exception{var m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}
  private static void ok(boolean value,String text){checks++;if(!value)throw new AssertionError(text);}
  private static void next(int value,int delay){stage=value;ready=ticks+delay;}
  private static net.minecraft.server.level.ServerPlayer player(Minecraft mc){return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());}
  private static net.minecraft.world.level.levelgen.WorldDimensions dimensions(net.minecraft.core.RegistryAccess r){var flat=net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings.getDefault(r.registryOrThrow(Registries.BIOME).asLookup(),r.registryOrThrow(Registries.STRUCTURE_SET).asLookup(),r.registryOrThrow(Registries.PLACED_FEATURE).asLookup());return r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions().replaceOverworldGenerator(r,new net.minecraft.world.level.levelgen.FlatLevelSource(flat));}
  private static BlockPos node(net.minecraft.server.level.ServerPlayer p,V at){var pos=BlockPos.containing(at.x(),at.y(),at.z());p.serverLevel().getChunkAt(pos);p.serverLevel().setBlock(pos,SplineRoads.NODE.get().defaultBlockState(),2);var node=(NodeEntity)p.serverLevel().getBlockEntity(pos);node.owner=p.getUUID();node.heightExplicit=true;node.apply(new Node(at,0,0));return pos;}
  private static void click(ManualPlannerScreen map,double x,double z)throws Exception{double sx=(Double)invoke(map,"sx",new Class<?>[]{double.class},x),sy=(Double)invoke(map,"sy",new Class<?>[]{double.class},z);for(int i=0;i<2;i++){map.mouseClicked(sx,sy,0);map.mouseReleased(sx,sy,0);}}
  private static int selected(ManualPlannerScreen map)throws Exception{return ((CompoundTag)field(map,"state")).getCompound("Selection").getLongArray("Points").length;}
  private static void action(ManualPlannerScreen map,String action){var t=new CompoundTag();t.putString("Action",action);t.putUUID("PlannerSession",map.session);ClientRoads.sendAction(t);}
  private static CompletableFuture<Integer> groups(Minecraft mc){return mc.getSingleplayerServer().submit(()->{try{return ((Map<?,?>)field(RoadData.get(player(mc).serverLevel()),"interchanges")).size();}catch(Exception e){throw new RuntimeException(e);}});}
  private static void shot(Minecraft mc,String name)throws Exception{try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(OUT.resolve(name+".png"));}}
  @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
    if(!Boolean.getBoolean("road.planner201Interchange")||done||event.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();ticks++;
    try{
      if(!started){if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;started=true;Files.createDirectories(OUT);deadline=System.nanoTime()+900_000_000_000L;mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);mc.options.pauseOnLostFocus=false;mc.options.enableVsync().set(false);mc.options.guiScale().set(2);mc.options.renderDistance().set(5);mc.options.framerateLimit().set(60);mc.resizeDisplay();mc.createWorldOpenFlows().createFreshLevel("sr201-interchange-"+System.currentTimeMillis(),new LevelSettings("SR201 isolated interchange",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(20115L,false,false),Planner201InterchangeVisual::dimensions);return;}
      if(System.nanoTime()>deadline)throw new AssertionError("timeout stage="+stage);
      if(mc.level==null||mc.player==null||mc.getOverlay()!=null||ticks<ready)return;
      if(stage==0){mc.setScreen(null);pending=mc.getSingleplayerServer().submit(()->{var p=player(mc);p.setGameMode(GameType.CREATIVE);p.getAbilities().flying=true;p.onUpdateAbilities();p.teleportTo(p.serverLevel(),.5,-25,.5,0,90);p.getPersistentData().putInt("SRPlannerRadius",512);p.getPersistentData().putInt("SRPlannerCacheSeconds",0);
        var o=RoadProfile.Options.DEFAULT.cycleFinish(RoadProfile.Options.CycleFinish.NONE).route(RoadProfile.Routing.DEFAULT.fit(false));o=o.infrastructure(o.infrastructure().gantry(RoadInfrastructure.Gantry.OFF));var s=new Settings(Mode.STRAIGHT,Style.H4_RAIL,Style.H4_RAIL.defaultWidth(),1,.35,90).options(o);var data=RoadData.get(p.serverLevel());
        var a=node(p,new V(.5,-59,-399.5));var b=node(p,new V(.5,-59,400.5));var c=node(p,new V(-399.5,-47,.5));var d=node(p,new V(400.5,-47,.5));data.connect(p.serverLevel(),p,a,b,s,null);data.connect(p.serverLevel(),p,c,d,s.structure(Structure.BRIDGE),null);originalPoints=new long[]{a.asLong(),b.asLong(),c.asLong(),d.asLong()};initialRoads=data.index.roads.size();return true;});next(1,20);return;}
      if(stage==1){if(!pending.isDone())return;pending.join();KeyMapping.click(ManualPlannerClient.KEY.getKey());next(2,20);return;}
      if(stage==2){var map=ManualPlannerClient.map;if(map==null||map.loading())return;set(map,"zoom",.5);map.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_5,0,0);next(3,20);return;}
      if(stage==3){var map=ManualPlannerClient.map;if(map.loading()||(Integer)field(map,"tool")!=5)return;ok(selected(map)==0,"new selection not empty");shot(mc,"01-main-roads");click(map,.5,120.5);ok((Boolean)field(map,"interchangePending"),"double-click did not wait for server ack");click(map,120.5,.5);next(4,20);return;}
      if(stage==4){var map=ManualPlannerClient.map;if(map.loading())return;ok(selected(map)==2,"rapid second-road clicks mixed with unacknowledged AB");ok(!(Boolean)field(map,"interchangePending"),"AB server ack did not unlock input");click(map,120.5,.5);next(5,10);return;}
      if(stage==5){if(!(mc.screen instanceof InterchangeScreen screen))return;var payload=((CompoundTag)field(screen,"payload")).copy();ok(Arrays.equals(payload.getLongArray("Points"),originalPoints),"map AB/CD order differs from selected roads");
        pending=mc.getSingleplayerServer().submit(()->{var p=player(mc);var hand=Interchanges.payload(p.serverLevel(),p,originalPoints,null);for(String key:List.of("Points","Nodes","Main1","Main2","ContinuousSources","ContinuousLimits"))if(!Objects.equals(hand.get(key),payload.get(key)))throw new AssertionError("map/hand connector planning input differs: "+key);return true;});next(6,20);return;}
      if(stage==6){if(!pending.isDone())return;pending.join();if(!(mc.screen instanceof InterchangeScreen screen))return;if(field(screen,"plan")==null||(Boolean)field(screen,"computing")||(Boolean)field(screen,"needsPlan"))return;ok(true,"map and hand share authoritative planner");shot(mc,"02-ready-preview");invoke(screen,"submit",new Class<?>[]{String.class},"interchange");pending=groups(mc);next(7,20);return;}
      if(stage==7){if(!pending.isDone())return;if((Integer)pending.join()==0){pending=groups(mc);next(7,20);return;}ok(true,"native map build created assembly");mc.setScreen(ManualPlannerClient.map);next(8,20);return;}
      if(stage==8){var map=ManualPlannerClient.map;if(map.loading())return;shot(mc,"03-built-range");pending=mc.getSingleplayerServer().submit(()->{try{var p=player(mc);var data=RoadData.get(p.serverLevel());var groups=(Map<UUID,CompoundTag>)field(data,"interchanges");var saved=groups.values().iterator().next();for(long end:saved.getLongArray("Points"))if(!(p.serverLevel().getBlockEntity(BlockPos.of(end)) instanceof NodeEntity))throw new AssertionError("cut lacks actual endpoint");long tails=data.index.roads.values().stream().filter(r->r.record.assembly()==null).count();if(tails<4)throw new AssertionError("missing ordinary outside tails "+tails);return true;}catch(Exception e){throw new RuntimeException(e);}});next(9,4);return;}
      if(stage==9){if(!pending.isDone())return;pending.join();ok(true,"cut nodes and four ordinary tails built");action(ManualPlannerClient.map,"manualUndo");pending=groups(mc);next(10,20);return;}
      if(stage==10){if(!pending.isDone())return;if((Integer)pending.join()!=0){pending=groups(mc);next(10,20);return;}ok(true,"map undo removed whole assembly");var map=ManualPlannerClient.map;if(map.loading())return;shot(mc,"04-undone");action(map,"manualRedo");pending=groups(mc);next(11,20);return;}
      if(stage==11){if(!pending.isDone())return;if((Integer)pending.join()==0){pending=groups(mc);next(11,20);return;}if(ManualPlannerClient.map.loading())return;next(12,10);return;}
      if(stage==12){shot(mc,"05-redone");ok(true,"map redo restored whole assembly");Files.writeString(OUT.resolve("passed.txt"),"SR201 INTERCHANGE NATIVE PASS checks="+checks+" initialRoads="+initialRoads+"\nReal map double clicks, acknowledgement race, shared hand/map payload, async preview, server build, four ordinary tails, real cut markers, map undo and redo. Screenshots require visual inspection.\n");done=true;System.out.println("SR201 INTERCHANGE NATIVE PASS "+checks);mc.stop();}
    }catch(Throwable failure){done=true;failure.printStackTrace();try{Files.writeString(OUT.resolve("failure.txt"),"stage="+stage+" "+failure);}catch(Exception ignored){}mc.stop();}
  }
}
