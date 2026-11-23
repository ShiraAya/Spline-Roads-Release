package com.sora.splineroads.client;
import java.nio.file.*;
import java.util.*;
import com.sora.splineroads.core.*;
import com.sora.splineroads.world.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.nbt.*;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Real server payload, actual editor completion and actual Xaero writer sampling. */
@Mod.EventBusSubscriber(modid="splineroads",value=Dist.CLIENT)
public final class FeedbackFix7Visual {
 static final Path OUT=Path.of("visual-fix7-delivery");static boolean started,done;static int stage,ticks,ready,readyStage=-1;static long deadline,begin;
 static volatile Throwable error;static volatile CompoundTag payload;
 static final UUID A=UUID.fromString("e2589901-56f4-4ba3-99d2-9a67b8a1379a"),B=UUID.fromString("3358edf8-f03b-4c96-b307-9417c5ab9e3b");
 static void log(String s)throws Exception{System.out.println("FIX7_CLIENT "+s);Files.writeString(OUT.resolve("results.txt"),s+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
 static void shot(Minecraft mc,String name)throws Exception{try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(OUT.resolve(name+".png"));}}
 static void biome(Minecraft mc,String label)throws Exception{
  var pos=new net.minecraft.core.BlockPos(1248,57,950);var value=mc.level.getBiome(pos);
  int tint=net.minecraft.client.renderer.BiomeColors.getAverageFoliageColor(mc.level,pos);
  log("BIOME "+label+" "+value.unwrapKey().orElseThrow().location()+" foliage="+Integer.toHexString(tint));
  if(label.equals("plains")&&!value.is(net.minecraft.world.level.biome.Biomes.PLAINS))throw new AssertionError("plains biome packet not applied");
 }
 @SuppressWarnings("unchecked") static void lifecycle(Minecraft mc)throws Exception{
  var type=XaeroRoadMap.class;var get=type.getDeclaredMethod("tile",long.class);get.setAccessible(true);
  var cf=type.getDeclaredField("chunks");cf.setAccessible(true);var chunks=(Map<Long,List<RoadMapTile.Source>>)cf.get(null);
  var cacheField=type.getDeclaredField("cache");cacheField.setAccessible(true);var cache=(Map<?,?>)cacheField.get(null);
  var countField=type.getDeclaredField("retainedPixels");countField.setAccessible(true);
  var roads=List.copyOf(ClientRoads.INDEX.roads.values());var road=roads.stream().filter(r->!r.chunks.isEmpty()).findFirst().orElseThrow();long key=road.chunks.iterator().next();
  var probe=new net.minecraft.world.level.chunk.LevelChunk(mc.level,new net.minecraft.world.level.ChunkPos(key));
  var clean=(java.lang.reflect.Field)Class.forName("xaero.common.core.XaeroMinimapCore").getField("chunkCleanField").get(null);clean.set(probe,true);
  if(!Boolean.FALSE.equals(XaeroRoadMap.miniChunkClean(clean,probe))||!Boolean.FALSE.equals(XaeroRoadMap.miniChunkClean(clean,probe)))throw new AssertionError("deferred refresh consumed too early");
  XaeroRoadMap.readMini(probe,net.minecraft.core.BlockPos.ZERO);
  if(!Boolean.TRUE.equals(XaeroRoadMap.miniChunkClean(clean,probe)))throw new AssertionError("sampled generation remains dirty");
  Object before=get.invoke(null,key);XaeroRoadMap.changed(road.record.id(),road);Object after=get.invoke(null,key);
  if(!Boolean.FALSE.equals(XaeroRoadMap.miniChunkClean(clean,probe)))throw new AssertionError("new geometry did not refresh minimap");
  XaeroRoadMap.readMini(probe,new net.minecraft.core.BlockPos(1,0,1));
  if(!Boolean.FALSE.equals(XaeroRoadMap.miniChunkClean(clean,probe)))throw new AssertionError("partial tile acknowledged new geometry");
  XaeroRoadMap.readMini(probe,net.minecraft.core.BlockPos.ZERO);
  if(!Boolean.TRUE.equals(XaeroRoadMap.miniChunkClean(clean,probe)))throw new AssertionError("full scan not acknowledged");
  if(before==null||after==null||before==after)throw new AssertionError("replacement retained old tile");
  XaeroRoadMap.changed(road.record.id(),null);if(chunks.values().stream().flatMap(List::stream).anyMatch(s->s.id().equals(road.record.id())))throw new AssertionError("deleted owner retained");
  XaeroRoadMap.changed(null,null);synchronized(cache){if(!chunks.isEmpty()||!cache.isEmpty()||countField.getInt(null)!=0)throw new AssertionError("reset retained map data");}
  var source=List.of(new RoadMapTile.Source(road.record.id(),road.mesh,road.record.structures()));
  for(int i=0;i<530;i++){long synthetic=net.minecraft.world.level.ChunkPos.asLong(100000+i,100000);chunks.put(synthetic,source);get.invoke(null,synthetic);}
  synchronized(cache){if(cache.size()>512||countField.getInt(null)>262144)throw new AssertionError("cache limit exceeded");}
  XaeroRoadMap.changed(null,null);for(var saved:roads)XaeroRoadMap.changed(saved.record.id(),saved);
  // A chunk from the server/different level must never use this client's tile.
  var foreign=new net.minecraft.world.level.chunk.LevelChunk(mc.getSingleplayerServer().overworld(),new net.minecraft.world.level.ChunkPos(key));
  var pos=new net.minecraft.core.BlockPos(0,64,0);var original=com.sora.splineroads.SplineRoads.COLLIDER.get().defaultBlockState();
  foreign.getSections()[foreign.getSectionIndex(64)].setBlockState(0,0,0,original);
  if(XaeroRoadMap.read(foreign,pos)!=original)throw new AssertionError("cross-level sampling");
  log("MAP_LIFECYCLE_PASS replace delete reset bounded-cache foreign-level deferred-refresh roads="+roads.size());
 }
 @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
  if(!Boolean.getBoolean("road.feedbackFix7Client")||done||event.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();ticks++;long now=System.nanoTime();
  try{
   if(!started){if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;started=true;Files.createDirectories(OUT);deadline=now+600_000_000_000L;mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(10);mc.options.framerateLimit().set(60);mc.options.enableVsync().set(false);mc.options.cloudStatus().set(CloudStatus.OFF);org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(),1280,720);mc.createWorldOpenFlows().loadLevel(mc.screen,"fix7-regression");return;}
   if(now>deadline)throw new AssertionError("timeout stage="+stage);if(error!=null)throw new AssertionError("server",error);
   if(mc.level==null||mc.player==null||mc.getOverlay()!=null)return;mc.mouseHandler.releaseMouse();mc.player.setDeltaMovement(0,0,0);
   if(ticks%200==0)log("stage="+stage+" maps="+XaeroRoadMap.metrics()+" error="+ClientRoads.error);if(ticks<ready)return;
   if(stage==0&&Boolean.getBoolean("road.feedbackFix7MapsOnly")){mc.setScreen(null);stage=5;}
   if(stage==0){mc.setScreen(null);FeedbackFix3Visual.camera(mc,3082,130,326,3083,56,326);mc.getSingleplayerServer().execute(()->{try{var server=mc.getSingleplayerServer();var p=server.getPlayerList().getPlayer(mc.player.getUUID());server.getPlayerList().op(p.getGameProfile());p.setGameMode(GameType.CREATIVE);p.getAbilities().flying=true;p.onUpdateAbilities();p.serverLevel().setDayTime(6000);var data=RoadData.get(p.serverLevel());var a=data.index.roads.get(A).record;var b=data.index.roads.get(B).record;payload=Interchanges.payload(p.serverLevel(),p,new long[]{a.b().asLong(),a.a().asLong(),b.a().asLong(),b.b().asLong()},null);NbtIo.writeCompressed(payload,OUT.resolve("actual-payload.dat").toFile());}catch(Throwable failure){error=failure;}});stage=1;ready=ticks+160;return;}
   if(stage==1||stage==3){if(payload==null)return;var value=payload.copy();var preset=stage==1?InterchangePlanner.Preset.STACK:InterchangePlanner.Preset.TURBINE;value.put("Options",Interchanges.write(new InterchangePlanner.Options(preset,false,1,96,20,5,1,5,true)));InterchangeScreen.clear();InterchangeScreen.open(value);begin=now;stage++;return;}
   if(stage==2||stage==4){if(!(mc.screen instanceof InterchangeScreen screen))return;var save=screen.children().stream().filter(w->w instanceof Button b&&b.getMessage().getString().equals("整体建造")&&b.active).findFirst();
    if(save.isEmpty()){if(now-begin>150_000_000_000L){shot(mc,"failed-plan-"+stage);throw new AssertionError("preview/build never ready");}return;}
    if(readyStage!=stage){log("EDITOR_READY "+(stage==2?"STACK":"TURBINE")+" ms="+(now-begin)/1e6);readyStage=stage;ready=ticks+5;return;}
    shot(mc,stage==2?"01-stack-ready":"02-turbine-ready");mc.setScreen(null);stage++;ready=ticks+20;return;}
   if(stage==5){FeedbackFix3Visual.camera(mc,1230,95,950,1231,56,950);stage=6;ready=ticks+400;return;}
   if(stage==6){shot(mc,"03-xaero-minimap");log("MAPS "+XaeroRoadMap.metrics());biome(mc,"original");stage=7;
    // Invoke Xaero's real bound world-map key; it opens the native screen.
    net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(org.lwjgl.glfw.GLFW.GLFW_KEY_M));
    ready=ticks+160;return;}
   if(stage==7){shot(mc,"04-xaero-worldmap");log("SCREEN "+(mc.screen==null?"null":mc.screen.getClass().getName()));if(mc.screen==null||!mc.screen.getClass().getName().startsWith("xaero."))throw new AssertionError("World map did not open");
    mc.setScreen(null);mc.getSingleplayerServer().execute(()->{try{var server=mc.getSingleplayerServer();for(int x=1184;x<1312;x+=64)for(int z=880;z<1008;z+=32)for(int y=-64;y<320;y+=16){
      int changed=server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),"fillbiome "+x+" "+y+" "+z+" "+(x+63)+" "+(y+15)+" "+(z+31)+" minecraft:plains");if(changed<=0)throw new AssertionError("fillbiome failed");
    }}catch(Throwable failure){error=failure;}});stage=8;ready=ticks+200;return;}
   if(stage==8){biome(mc,"plains");shot(mc,"05-plains-minimap");net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(org.lwjgl.glfw.GLFW.GLFW_KEY_M));stage=9;ready=ticks+160;return;}
   if(stage==9){shot(mc,"06-plains-worldmap");lifecycle(mc);log("PASS maps="+XaeroRoadMap.metrics());done=true;mc.stop();}
  }catch(Throwable failure){done=true;failure.printStackTrace();try{log("FAIL "+failure);}catch(Exception ignored){}mc.stop();}
 }
}
