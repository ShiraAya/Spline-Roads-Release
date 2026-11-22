package com.sora.splineroads.client;
import java.nio.file.*;
import java.util.*;
import com.sora.splineroads.world.*;
import com.sora.splineroads.core.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid="splineroads",value=Dist.CLIENT)
public final class FeedbackFix2Visual {
 static final Path OUT=Path.of(System.getProperty("road.visualOutput","visual-fix2"));static boolean started,done;static int ticks,stage,ready;static long deadline,lastTick,maxTick;static int previewTicks;
 static final String[] SHOTS={"01-rail","02-wall","03-infill","04-cycle","05-edge","06-open-cut"};
 static final double[][] CAMERAS={{1207,60,1001,1200,56,998},{1217,65,939,1232,55,938},{1221,85,913,1222,53,928},{3,82,1488,-18,56,1492},{2702,56.5,22,2703,56.3,30},{1567,74,705,1490,52,714}};
 static void log(String s)throws Exception{System.out.println("FIX2_CLIENT "+s);Files.writeString(OUT.resolve("results.txt"),s+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
 static Object value(String name)throws Exception{var f=RoadRenderer.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
 static void camera(Minecraft mc,double x,double y,double z,double tx,double ty,double tz){Feedback212Visual.camera(mc,x,y,z,tx,ty,tz);}
 static void shot(Minecraft mc,String name)throws Exception{try(var i=Screenshot.takeScreenshot(mc.getMainRenderTarget())){i.writeToFile(OUT.resolve(name+".png"));}log("SCREENSHOT "+name);}
 static void fills(Minecraft mc)throws Exception{
  int count=0,maximum=0;
  for(int x=73;x<=78;x++)for(int z=55;z<=63;z++){
   var chunk=mc.level.getChunkSource().getChunk(x,z,net.minecraft.world.level.chunk.ChunkStatus.FULL,false);
   if(chunk==null)continue;var loaded=chunk;
   for(var entity:loaded.getBlockEntities().values())if(entity instanceof RoadFillEntity fill){
    var tag=fill.getUpdateTag();var surfaces=tag.getList("TerrainSurfaces",net.minecraft.nbt.Tag.TAG_LIST);if(surfaces.isEmpty())continue;
    maximum=Math.max(maximum,surfaces.size());if(surfaces.size()>2048||tag.getList("TerrainEdge",net.minecraft.nbt.Tag.TAG_LIST).size()>256)throw new AssertionError("infill exceeds codec bounds");
    var copy=new RoadFillEntity(fill.getBlockPos(),fill.getBlockState());copy.load(tag);
    if(!tag.equals(copy.getUpdateTag()))throw new AssertionError("infill reload changes geometry at "+fill.getBlockPos());count++;
   }
  }
  if(count<50)throw new AssertionError("exact fill roundtrip did not exercise saved cells: "+count);
  log("FILL_ROUNDTRIP_PASS count="+count+" maxSurfaces="+maximum);
 }
 @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
  if(!Boolean.getBoolean("road.feedbackFix2Client")||done||e.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();ticks++;
  long now=System.nanoTime();if(stage==8&&lastTick!=0){maxTick=Math.max(maxTick,now-lastTick);previewTicks++;}lastTick=now;
  try{
   if(!started){if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;started=true;Files.createDirectories(OUT);deadline=now+600_000_000_000L;mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(8);mc.options.framerateLimit().set(60);mc.options.enableVsync().set(false);mc.options.hideGui=true;mc.options.cloudStatus().set(CloudStatus.OFF);mc.createWorldOpenFlows().loadLevel(mc.screen,Boolean.getBoolean("road.feedbackFix2Rail")&&!Boolean.getBoolean("road.feedbackFix2RailUpdated")?"world-original":"world");return;}
   if(now>deadline)throw new AssertionError("timeout stage="+stage);
   if(mc.level==null||mc.player==null||mc.getOverlay()!=null)return;
   mc.mouseHandler.releaseMouse();mc.player.setDeltaMovement(0,0,0);
   if(ticks%100==0)log("stage="+stage+" "+RoadTerrainModels.status()+" error="+ClientRoads.error);
   if(ticks<ready)return;
   if(Boolean.getBoolean("road.feedbackFix2Rail")){
    String prefix=Boolean.getBoolean("road.feedbackFix2RailUpdated")?"rail-rebuilt":"rail-original";
    if(stage==1){shot(mc,prefix);camera(mc,5000,80,5000,5000,56,5010);stage=9;ready=ticks+350;return;}
    if(stage==9){var c=CAMERAS[0];camera(mc,c[0],c[1],c[2],c[3],c[4],c[5]);stage=10;ready=ticks+350;return;}
    if(stage==10){shot(mc,prefix+"-return");camera(mc,1207,70,1001,1207,56,990);stage=11;ready=ticks+250;return;}
    if(stage==11){shot(mc,prefix+"-top");camera(mc,1218,59,990,1205,56,990);stage=12;ready=ticks+250;return;}
    if(stage==12){shot(mc,prefix+"-side");log("RAIL_RETURN_PASS");done=true;mc.stop();return;}
   }
   if(stage<=CAMERAS.length){
    if(stage>0)shot(mc,SHOTS[stage-1]);if(stage==3)fills(mc);
    if(stage<CAMERAS.length){mc.setScreen(null);var c=CAMERAS[stage];camera(mc,c[0],c[1],c[2],c[3],c[4],c[5]);stage++;ready=ticks+350;return;}
    stage=7;
   }
   if(stage==7){
    var saved=ClientRoads.INDEX.roads.get(UUID.fromString("50716f67-d317-49c8-9982-2006341a502a"));if(saved==null)throw new AssertionError("preview source missing");
    var record=saved.record.structures(List.of());ClientRoads.draft=new net.minecraft.nbt.CompoundTag();ClientRoads.draft.putUUID("Id",record.id());
    ClientRoads.preview=record.mesh();RoadRenderer.previewCandidates(List.of(record));maxTick=0;previewTicks=0;stage=8;ready=ticks+600;log("PREVIEW_BEGIN samples="+ClientRoads.preview.samples().size());return;
   }
   if(stage==8){shot(mc,"07-long-preview");
    var faces=(List<RoadSurface.Face>)value("furnitureFaces");String failure=(String)value("furnitureFailure");boolean running=(boolean)value("furnitureRunning");
    long walls=faces.stream().filter(f->f.texture()==RoadSurface.Texture.CONCRETE&&f.points().stream().mapToDouble(p->p.x()).average().orElse(0)>1470&&f.points().stream().mapToDouble(p->p.x()).average().orElse(0)<1550&&f.points().stream().mapToDouble(p->p.y()).max().orElse(0)-f.points().stream().mapToDouble(p->p.y()).min().orElse(0)>2).count();
    log("OPEN_CUT_PREVIEW walls="+walls);if(walls<20)throw new AssertionError("open cut preview lost walls");
    log("PREVIEW_RESULT ticks="+previewTicks+" maxTickMs="+maxTick/1e6+" faces="+faces.size()+" running="+running+" message="+failure);
    if(running||faces.isEmpty()||!failure.isBlank())throw new AssertionError("preview incomplete");
    if(previewTicks<500||maxTick>5_000_000_000L)throw new AssertionError("preview froze client");
    ClientRoads.preview=null;RoadRenderer.previewCandidates(List.of());log("PASS");done=true;mc.stop();
   }
  }catch(Throwable failure){done=true;failure.printStackTrace();try{log("FAIL "+failure);}catch(Exception ignored){}mc.stop();}
 }
}
