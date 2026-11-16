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

/** Isolated existing-combination editor, actual preset controls, error completion and preview return. */
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class Interchange202Visual {
  private static final Path OUT=Path.of("feedback202-interchange");
  private static boolean started,done;private static int stage,ticks,ready,checks;private static long deadline;
  private static final String[] PRESETS={"CLOVERLEAF","TURBINE","STACK","HYBRID","DIAMOND","SPUI","DOUBLE_TRUMPET","ROUNDABOUT"};
  private static int choice;private static long calculationStart;
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

  private static void choose(InterchangeScreen screen,String name)throws Exception {
    for(int i=0;i<12;i++){var current=(InterchangePlanner.Options)field(screen,"options");if(current.preset().name().equals(name))return;
      var button=screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().startsWith(current.preset().label)).map(c->(net.minecraft.client.gui.components.Button)c).findFirst().orElseThrow();button.onPress();
    }throw new AssertionError("preset unavailable "+name);
  }
  private static boolean incompatibleHighways(){return PRESETS[choice].equals("DIAMOND")||PRESETS[choice].equals("SPUI");}
  @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
    if(!Boolean.getBoolean("road.interchange202Visual")||done||event.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();ticks++;
    try{
      if(!started){if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;started=true;Files.createDirectories(OUT);deadline=System.nanoTime()+900_000_000_000L;mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);mc.options.pauseOnLostFocus=false;mc.options.enableVsync().set(false);mc.options.guiScale().set(2);mc.options.renderDistance().set(5);mc.options.framerateLimit().set(60);mc.resizeDisplay();mc.createWorldOpenFlows().createFreshLevel("sr202-interchange-"+System.currentTimeMillis(),new LevelSettings("SR202 isolated saved interchange",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(20201L,false,false),Interchange202Visual::dimensions);return;}
      if(System.nanoTime()>deadline)throw new AssertionError("timeout stage="+stage);
      if(mc.level==null||mc.player==null||mc.getOverlay()!=null||ticks<ready)return;
      if(stage==0){mc.setScreen(null);pending=mc.getSingleplayerServer().submit(()->{try{var p=player(mc);var root=NbtIo.readCompressed(Path.of(System.getProperty("road.interchange202Snapshot")).toFile()).getCompound("data");var data=RoadData.load(root);p.serverLevel().getDataStorage().set("splineroads",data);var saved=root.getList("Interchanges",Tag.TAG_COMPOUND).getCompound(1);var ns=saved.getList("Nodes",Tag.TAG_COMPOUND);var points=saved.getLongArray("Points");
        for(int i=0;i<points.length;i++){var pos=BlockPos.of(points[i]);p.serverLevel().getChunkAt(pos);p.serverLevel().setBlock(pos,SplineRoads.NODE.get().defaultBlockState(),2);var marker=(NodeEntity)p.serverLevel().getBlockEntity(pos);marker.owner=p.getUUID();marker.heightExplicit=true;marker.apply(RoadRecord.readNode(ns.getCompound(i)));}
        p.teleportTo(p.serverLevel(),-915,-15,-95,0,90);p.getAbilities().flying=true;p.onUpdateAbilities();return Interchanges.payload(p.serverLevel(),null,points,saved.getUUID("Id"));}catch(Exception e){throw new RuntimeException(e);}});next(1,20);return;}
      if(stage==1){if(!pending.isDone())return;var payload=(CompoundTag)pending.join();ok(payload.hasUUID("Id"),"not an existing saved combination");ok(HostAxes.presets(payload).stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()).equals(Set.of(PRESETS)),"native preset coverage differs from editor choices");var nodes=payload.getList("Nodes",Tag.TAG_COMPOUND);double sourceHeight=Math.abs(RoadRecord.readNode(nodes.getCompound(0)).position().y()-RoadRecord.readNode(nodes.getCompound(2)).position().y());Files.writeString(OUT.resolve("results.txt"),"saved input main-road height difference="+sourceHeight+"; feedback screenshot height difference=7; source copy is not identical to screenshot settings\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);InterchangeScreen.open(payload);next(2,4);return;}
      if(stage==2){if(!(mc.screen instanceof InterchangeScreen screen))throw new AssertionError("missing editor");choose(screen,PRESETS[choice]);var o=(InterchangePlanner.Options)field(screen,"options");if(o.aboveAvailable()){if(!o.allowAbove()){var button=screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().startsWith("允许匝道高于主路")).map(c->(net.minecraft.client.gui.components.Button)c).findFirst().orElseThrow();button.onPress();}ok(((InterchangePlanner.Options)field(screen,"options")).allowAbove(),"allowAbove must remain enabled after selecting "+PRESETS[choice]);}calculationStart=System.nanoTime();next(3,12);return;}
      if(stage==3){var screen=(InterchangeScreen)mc.screen;if((Boolean)field(screen,"computing")||(Boolean)field(screen,"needsPlan"))return;var o=(InterchangePlanner.Options)field(screen,"options");ok(o.preset().name().equals(PRESETS[choice]),"preset changed during planning");if(o.aboveAvailable())ok(o.allowAbove(),"allowAbove disabled before completion "+PRESETS[choice]);boolean preview=((net.minecraft.client.gui.components.Button)field(screen,"preview")).active,save=((net.minecraft.client.gui.components.Button)field(screen,"save")).active;if(incompatibleHighways()){ok(field(screen,"plan")==null,"incompatible highways unexpectedly accepted "+PRESETS[choice]);ok(field(screen,"status").toString().contains("AB、CD 均为高速"),"missing precise highway rejection: "+field(screen,"status"));ok(!preview&&!save,"rejected preset actions still enabled");}else{ok(field(screen,"plan")!=null,"failed "+PRESETS[choice]+": "+field(screen,"status"));ok(preview,"preview button disabled");ok(save,"update button disabled");}Files.writeString(OUT.resolve("results.txt"),PRESETS[choice]+(incompatibleHighways()?" rejected":" ready")+" allowAbove="+o.allowAbove()+" seconds="+(System.nanoTime()-calculationStart)/1e9+" status="+field(screen,"status")+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);next(4,8);return;}
      if(stage==4){shot(mc,String.format("%02d-%s-%s",choice,PRESETS[choice],incompatibleHighways()?"rejected":"ready"));if(++choice<PRESETS.length){next(2,1);return;}var screen=(InterchangeScreen)mc.screen;((net.minecraft.client.gui.components.Button)field(screen,"preview")).onPress();ok(mc.screen==null,"world preview did not leave editor");ok(!ClientRoads.nodePreviews.isEmpty(),"world preview geometry absent");var resume=new CompoundTag();resume.putString("Kind","interchangeResume");resume.putInt("GeneratorArms",4);InterchangeScreen.open(resume);next(5,12);return;}
      if(stage==5){ok(mc.screen instanceof InterchangeScreen,"cannot return to saved combination editor");Files.writeString(OUT.resolve("passed.txt"),"SR202 INTERCHANGE NATIVE PASS checks="+checks+"\nExisting saved combination payload from isolated data copy; all eight offered four-way UI presets; TURBINE/HYBRID allowAbove=true; six ready previews/updates and precise DIAMOND/SPUI dual-highway rejection; world preview and return. No original world mutation; no assembly commit in this UI probe.\n");done=true;System.out.println("SR202 INTERCHANGE NATIVE PASS "+checks);mc.stop();}
    }catch(Throwable e){done=true;e.printStackTrace();try{Files.writeString(OUT.resolve("failure.txt"),"stage="+stage+" choice="+choice+" "+e);}catch(Exception ignored){}mc.stop();}
  }
}
