package com.sora.splineroads.client;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.world.RoadData;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Test-only completeness receipts and frame sampling; no production hooks. */
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class Performance211Review {
  static final double[][] VIEWS={{0,0},{1350,0},{2050,0},{2750,0},{1250,800},{250,950},{1100,1200},{850,2100},{0,1750},{2150,1800}};
  static final String[] NAMES={"spui","west-junction","right-slip-junction","attached","tunnel-ramps","tunnel-west-portal","curve-cut","viaduct-ramps","turbine","trumpet"};
  static int view=-1,stable;static long began,firstSurface,loadedAt,previous,diagnosticAt;
  static String waiting="";
  static boolean sampling,finished;
  static final ArrayList<Long> frames=new ArrayList<>();
  static CompletableFuture<Set<UUID>> expected;
  static final Map<String,Field> fields=new HashMap<>();
  static Object field(Object target,String name)throws Exception {
    Class<?> c=target instanceof Class<?> type?type:target.getClass();String key=c.getName()+"#"+name;
    Field f=fields.get(key);if(f==null){f=c.getDeclaredField(name);f.setAccessible(true);fields.put(key,f);}return f.get(target instanceof Class<?>?null:target);
  }
  @SuppressWarnings("unchecked") static int pending(Minecraft mc)throws Exception {
    var preparations=(Map<UUID,?>)field(RoadRenderer.class,"preparations");
    var versions=(Map<UUID,Integer>)field(RoadRenderer.class,"versions");int count=0;
    int missing=0,unprepared=0,windowsWaiting=0,dirty=0,unloaded=0;
    for(var id:expected.join())if(!ClientRoads.active(id)){count++;missing++;}
    for(var r:ClientRoads.INDEX.roads.values()){
      if(!ClientRoads.active(r.record.id())||!RoadVisibility.within(r.mesh.min().x(),r.mesh.min().z(),r.mesh.max().x(),r.mesh.max().z(),mc.player.getX(),mc.player.getZ(),mc.options.getEffectiveRenderDistance()+2))continue;
      var p=preparations.get(r.record.id());
      if(p==null||(int)field(p,"version")!=versions.getOrDefault(r.record.id(),0)){count++;unprepared++;continue;}
      var windows=(List<RoadMeshSurfaces.Window>)field(p,"windows");var complete=(Set<RoadMeshSurfaces.Window>)field(p,"surfaces");
      for(var w:windows)if(RoadVisibility.within(w.min().x(),w.min().z(),w.max().x(),w.max().z(),mc.player.getX(),mc.player.getZ(),mc.options.getEffectiveRenderDistance()+2)&&!complete.contains(w)){count++;windowsWaiting++;}
    }
    for(var section:(List<?>)field(RoadRenderer.class,"visible"))if((boolean)field(section,"dirty")){
      // The renderer deliberately does not upload regions whose terrain chunks
      // have streamed out. They are outside the current world publication.
      if(!RoadTerrainModels.regionLoaded((RoadRenderMesh.Section)field(section,"key"))){unloaded++;continue;}
      count++;dirty++;
    }
    waiting="missing="+missing+" unprepared="+unprepared+" windows="+windowsWaiting+" dirty="+dirty+" outside_loaded_terrain="+unloaded+" stats="+RoadRenderer.streamingStats();
    return count;
  }
  static boolean tick(Minecraft mc,double ground)throws Exception {
    if(finished)return true;
    if(view<0){next(mc,ground);return false;}
    if(!expected.isDone())return false;
    long now=System.nanoTime();var stats=RoadRenderer.streamingStats();
    if(firstSurface==0&&stats.surfaceIndices()>0)firstSurface=now;
    if(!sampling){
      int pending=pending(mc);
      if(now-diagnosticAt>10_000_000_000L){Performance211Visual.log("WAIT "+NAMES[view]+" "+waiting);diagnosticAt=now;}
      if(pending==0&&stats.surfaceIndices()>0)stable++;else stable=0;
      if(stable>=20){loadedAt=now;sampling=true;previous=0;frames.clear();Performance211Visual.log("LOAD "+NAMES[view]+" first_surface_ms="+(firstSurface-began)/1e6+" complete_ms="+(now-began)/1e6+" expected_roads="+expected.join().size()+" client_roads="+ClientRoads.INDEX.roads.size());}
      else if(now-began>180_000_000_000L){
        try(var im=Screenshot.takeScreenshot(mc.getMainRenderTarget())){im.writeToFile(Performance211Visual.OUT.resolve("timeout-"+NAMES[view]+".png"));}
        Performance211Visual.log("LOAD_TIMEOUT "+NAMES[view]+" first_surface_ms="+(firstSurface==0?-1:(firstSurface-began)/1e6)+" complete_ms="+(now-began)/1e6+" expected_roads="+expected.join().size()+" client_roads="+ClientRoads.INDEX.roads.size()+" pending="+pending);
        // A slow baseline is a censored observation, not a fabricated successful
        // load. Continue collecting the other locations; candidate acceptance
        // requires zero timeouts in the external run validator.
        if(view==VIEWS.length-1){finished=true;return true;}next(mc,ground);
      }
      return false;
    }
    if(now-loadedAt<(view==0?30:5)*1_000_000_000L)return false;
    sampling=false;previous=0;
    try(var im=Screenshot.takeScreenshot(mc.getMainRenderTarget())){im.writeToFile(Performance211Visual.OUT.resolve("view-"+NAMES[view]+".png"));}
    var out=new StringBuilder("frame_ns\n");frames.forEach(f->out.append(f).append('\n'));Files.writeString(Performance211Visual.OUT.resolve("idle-"+NAMES[view]+".csv"),out);
    var sorted=frames.stream().mapToLong(Long::longValue).sorted().toArray();long sum=0,low=0;for(long f:sorted)sum+=f;int n=(sorted.length+99)/100;for(int i=sorted.length-n;i<sorted.length;i++)low+=sorted[i];
    Performance211Visual.log("FPS "+NAMES[view]+" frames="+sorted.length+" average="+sorted.length*1e9/sum+" low1="+n*1e9/low);
    if(view==VIEWS.length-1){finished=true;return true;}next(mc,ground);return false;
  }
  static void next(Minecraft mc,double ground){
    view++;stable=0;began=System.nanoTime();firstSurface=0;var p=VIEWS[view];mc.options.hideGui=true;mc.options.renderDistance().set(32);mc.options.fov().set(80);
    Feedback211Visual.camera(mc,p[0]+60,ground+300,p[1]+100,p[0],ground,p[1]);
    expected=mc.getSingleplayerServer().submit(()->{var ids=new HashSet<UUID>();int cx=(int)Math.floor(p[0]+60)>>4,cz=(int)Math.floor(p[1]+100)>>4;var index=RoadData.get(mc.getSingleplayerServer().overworld()).index;for(int x=cx-8;x<=cx+8;x++)for(int z=cz-8;z<=cz+8;z++)ids.addAll(index.inChunk(net.minecraft.world.level.ChunkPos.asLong(x,z)));return Set.copyOf(ids);});
  }
  @SubscribeEvent public static void frame(RenderLevelStageEvent e){if(!sampling||e.getStage()!=RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES)return;long now=System.nanoTime();if(previous!=0)frames.add(now-previous);previous=now;}
  @SubscribeEvent public static void fog(net.minecraftforge.client.event.ViewportEvent.RenderFog e){if("performance".equals(System.getProperty("road.feedback211Resume"))&&view>=0){e.setNearPlaneDistance(10000);e.setFarPlaneDistance(11000);e.setCanceled(true);}}
}
