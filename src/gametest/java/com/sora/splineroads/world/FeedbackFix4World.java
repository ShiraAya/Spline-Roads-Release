package com.sora.splineroads.world;
import java.util.*;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
@Mod.EventBusSubscriber(modid="splineroads")
public final class FeedbackFix4World {
 @SubscribeEvent public static void started(ServerStartedEvent event){
  if(!Boolean.getBoolean("road.feedbackFix4")||Boolean.getBoolean("road.feedbackFix4Edit"))return;
  var server=event.getServer();var level=server.overworld();var live=RoadData.get(level);
  System.out.println("FIX4_WORLD roads="+live.index.roads.size());
  RoadBuildJobs.submit(level,null,data->{
   try{
   var targets=new LinkedHashSet<UUID>();
   for(var p:List.of(new V(-8,56,1452),new V(-10,56,1421),new V(1230,56,954),new V(1247,56,959),new V(1297,69,501))){
    System.out.println("FIX4_AT "+p);
    data.index.roads.values().stream().sorted(Comparator.comparingDouble(r->RoadQueries.horizontal(r.mesh,p).horizontalDistance())).limit(3).forEach(b->{
     targets.add(b.record.id());var q=RoadQueries.horizontal(b.mesh,p);
     System.out.println("FIX4_NEAR "+b.record.id()+" assembly="+b.record.assembly()+" station="+q.sample().distance()+" dist="+q.horizontalDistance()+" half="+q.sample().halfWidth()+" y="+q.sample().center().y()+" start="+b.record.start()+" end="+b.record.end());
     for(var part:b.record.structures())if(RoadRailSlabClipping.guardrail(part)&&part.a().sub(p).horizontalLength()<3)System.out.println("FIX4_OLD_RAIL "+part);
    });
   }
   var f=RoadData.class.getDeclaredField("terrainFill");f.setAccessible(true);var g=RoadData.class.getDeclaredField("terrainOriginal");g.setAccessible(true);
   var retained=(Map<Long,net.minecraft.world.level.block.state.BlockState>)f.get(data);var originals=(Map<Long,net.minecraft.world.level.block.state.BlockState>)g.get(data);
   var all=new ArrayList<>(data.index.roads.values());var cache=new RoadTerrain.Cache(retained,originals);var lookup=new RoadPlanningIndex(all);
   for(var id:targets){
    var b=data.index.roads.get(id);long start=System.nanoTime();System.out.println("FIX4_PLAN_BEGIN "+id);
    var next=StructurePlanner.plan(level,b.structures(List.of()),all,retained,originals,cache,lookup);
    var profile=new ArrayList<String>();for(double d=0;d<=b.mesh.length();d+=16){var s=RoadStructures.sample(b.mesh,d);profile.add(String.format(Locale.ROOT,"%.1f:%.3f",d,s.center().y()));}
    System.out.println("FIX4_PROFILE "+id+" "+profile);
    System.out.println("FIX4_PLAN "+id+" old="+b.record.structures().size()+" new="+next.structures().size()+" ms="+(System.nanoTime()-start)/1e6);
    for(var p:List.of(new V(-8,56,1452),new V(-10,56,1421),new V(1230,56,954)))for(var part:next.structures())
      if(part.a().sub(p).horizontalLength()<4&&(part.material()==RoadStructures.Material.TUNNEL||RoadRailSlabClipping.guardrail(part)))System.out.println("FIX4_NEW_PART "+id+" "+part);
   }
   return "diagnostics";
   }catch(Exception failure){throw new RuntimeException(failure);}
  }).whenComplete((result,failure)->{if(failure!=null){System.out.println("FIX4_FAIL");failure.printStackTrace();}else System.out.println("FIX4_DIAGNOSTICS_PASS");server.halt(false);});
 }
}
