package com.sora.splineroads.world;
import java.util.*;
import com.sora.splineroads.core.*;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
@Mod.EventBusSubscriber(modid="splineroads")
public final class Feedback212World {
 @SubscribeEvent public static void started(ServerStartedEvent e){
  if(!Boolean.getBoolean("road.feedbackFix1"))return;
  var server=e.getServer();var level=server.overworld();
  try{
   var data=RoadData.get(level);System.out.println("FIX1_WORLD roads="+data.index.roads.size());
   for(String id:List.of("680cdaf5-5dfb-4521-ab08-6319d0ba7bd8","2618586e-ceb0-4a05-839d-cd6080c8d27c")){
    var road=data.index.roads.get(UUID.fromString(id));
    for(long chunk:road.chunks)level.getChunk((int)chunk,(int)(chunk>>32));
    var all=new ArrayList<>(data.index.roads.values());
    var retained=terrain(data,"terrainFill");var originals=terrain(data,"terrainOriginal");
    var result=StructurePlanner.plan(level,road,all,retained,originals,new RoadTerrain.Cache(retained,originals),new RoadPlanningIndex(all));
    System.out.println("FIX1_PLAN "+id+" old="+road.record.structures().size()+" new="+result.structures().size()+" piers="+result.structures().stream().filter(RoadStructures.Part::pier).count()+" walks="+result.structures().stream().filter(RoadSidewalks::smoothPart).count());
   }
   System.out.println("FIX1_WORLD_PASS");
  }catch(Throwable failure){System.out.println("FIX1_WORLD_FAIL");failure.printStackTrace();}
  finally{server.halt(false);}
 }
 @SuppressWarnings("unchecked") private static Map<Long,net.minecraft.world.level.block.state.BlockState> terrain(RoadData data,String name)throws Exception{
  var field=RoadData.class.getDeclaredField(name);field.setAccessible(true);return (Map<Long,net.minecraft.world.level.block.state.BlockState>)field.get(data);
 }
}
