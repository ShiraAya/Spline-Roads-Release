package com.sora.splineroads.world;
import java.util.*;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
@Mod.EventBusSubscriber(modid="splineroads")
public final class FeedbackFix2World {
 static int railDeckConflicts(RoadData data,String label){
  var road=data.index.roads.get(UUID.fromString("f39e9d5a-d3a1-4d56-83cc-c4bc4175c688"));var host=data.index.roads.get(UUID.fromString("680cdaf5-5dfb-4521-ab08-6319d0ba7bd8"));int count=0;
  for(var p:road.record.structures()){
   if(p.material()!=RoadStructures.Material.STEEL&&p.material()!=RoadStructures.Material.DARK_STEEL)continue;
   if(Math.max(p.a().x(),p.b().x())<1180||Math.min(p.a().x(),p.b().x())>1230||Math.max(p.a().z(),p.b().z())<980||Math.min(p.a().z(),p.b().z())>1020||Math.min(p.a().y(),p.b().y())>59)continue;
   if(RoadClearance.structureInDeck(p,host.mesh)){count++;if(label.equals("after"))System.out.println("FIX2_RAIL_DECK_CONFLICT_PART "+p);}
  }System.out.println("FIX2_RAIL_DECK_CONFLICTS "+label+" count="+count);return count;
 }
 static void exposedPosts(RoadData data,String label){
  var road=data.index.roads.get(UUID.fromString("f39e9d5a-d3a1-4d56-83cc-c4bc4175c688"));int left=0,right=0;
  for(var p:road.record.structures()){
   if(p.material()!=RoadStructures.Material.DARK_STEEL||p.width()>.16||p.height()<.4)continue;
   var at=p.a().add(p.b()).mul(.5);
   if(at.x()<1180||at.x()>1230||at.z()<980||at.z()>1020||at.y()>59||at.y()+p.height()<56.5)continue;
   if(RoadQueries.horizontal(road.mesh,at).lateral()<0)right++;else left++;
  }
  System.out.println("FIX2_EXPOSED_POSTS "+label+" left="+left+" right="+right);
  if(label.equals("after")&&(left<3||right<3))throw new AssertionError("exposed guardrail posts removed on one side");
 }
 static int buriedRails(net.minecraft.server.level.ServerLevel level,RoadData data,String label){
  var road=data.index.roads.get(UUID.fromString("f39e9d5a-d3a1-4d56-83cc-c4bc4175c688"));int checked=0,buried=0;
  try(var chunks=RoadWorkChunks.open(level)){chunks.roads(List.of(road));
   for(var part:road.record.structures()){
    var p=part.a().add(part.b()).mul(.5).add(new V(0,part.height()/2,0));
    if(part.material()!=RoadStructures.Material.STEEL||p.sub(new V(1200,56,998)).horizontalLength()>20||p.y()>59)continue;
    checked++;
    var pos=net.minecraft.core.BlockPos.containing(p.x(),p.y(),p.z());var state=level.getBlockState(pos);
    var shape=net.minecraft.world.phys.shapes.Shapes.empty();
    if(level.getBlockEntity(pos) instanceof RoadFillEntity fill)shape=fill.collision(level,pos,net.minecraft.world.phys.shapes.CollisionContext.empty(),shape);
    else if(RoadBlocks.isCollider(state))shape=state.getValue(RoadBlocks.Road.FILL).state().getCollisionShape(level,pos);
    else shape=state.getCollisionShape(level,pos);
    if(shape.toAabbs().stream().anyMatch(b->b.move(pos).inflate(-.0001).contains(p.x(),p.y(),p.z()))){buried++;if(buried<=12)System.out.println("FIX2_BURIED_RAIL "+label+" "+p+" state="+state+" fill="+(level.getBlockEntity(pos) instanceof RoadFillEntity fill?fill.getUpdateTag():""));}
   }
  }System.out.println("FIX2_RAIL_TERRAIN "+label+" samples="+checked+" buried="+buried);return buried;
 }
 @SubscribeEvent public static void started(ServerStartedEvent e){
  if(!Boolean.getBoolean("road.feedbackFix2"))return;
  var server=e.getServer();var level=server.overworld();
  try {
   var data=RoadData.get(level);System.out.println("FIX2_WORLD roads="+data.index.roads.size());
   buriedRails(level,data,"before");
   railDeckConflicts(data,"before");
   exposedPosts(data,"before");
   if(Boolean.getBoolean("road.feedbackFix2RailWorld"))return;
   if(Boolean.getBoolean("road.feedbackFix2Geometry")){
    var all=new ArrayList<>(data.index.roads.values());
    var f=RoadData.class.getDeclaredField("terrainFill");f.setAccessible(true);
    var g=RoadData.class.getDeclaredField("terrainOriginal");g.setAccessible(true);
    var retained=(Map<Long,net.minecraft.world.level.block.state.BlockState>)f.get(data);
    var original=(Map<Long,net.minecraft.world.level.block.state.BlockState>)g.get(data);
    var cache=new RoadTerrain.Cache(retained,original);
    var terrain=RoadTerrain.read(level,retained,original,cache);
    for(var name:List.of("f39e9d5a-d3a1-4d56-83cc-c4bc4175c688","680cdaf5-5dfb-4521-ab08-6319d0ba7bd8")){
     var built=data.index.roads.get(UUID.fromString(name));
     try(var chunks=RoadWorkChunks.open(level)){
      chunks.roads(List.of(built));
      var plan=StructurePlanner.plan(level,built.structures(List.of()),all,retained,original,cache,new RoadPlanningIndex(all));
      for(var parts:List.of(built.record.structures(),plan.structures())){
       System.out.println("FIX2_GEOMETRY "+name+" "+(parts==plan.structures()?"new":"old")+" count="+parts.size());
       for(var part:parts){var mid=part.a().add(part.b()).mul(.5);
        if((part.material()==RoadStructures.Material.STEEL||part.material()==RoadStructures.Material.DARK_STEEL)&&mid.sub(new V(1207,56,1001)).horizontalLength()<18&&part.a().y()<59)
         System.out.println("FIX2_RAIL "+part);
       }
       var nearest=parts.stream().filter(p->p.material()==RoadStructures.Material.TUNNEL&&p.model().startsWith("sr:auto_cut:")).sorted(Comparator.comparingDouble(p->p.a().add(p.b()).mul(.5).sub(new V(1217,56,939)).horizontalLength())).limit(6).toList();
       for(var part:nearest){var m=part.a().add(part.b()).mul(.5);System.out.println("FIX2_WALL "+part+" top="+(m.y()+part.height())+" ground="+terrain.surface(m.x(),m.z(),m.y()+part.height()));}
      }
     }
    }
    return;
   }
   var slow=data.index.roads.values().stream().filter(r->r.record.junction()==null&&!r.mesh.settings().style().ramp()).min(Comparator.comparingDouble(r->RoadQueries.horizontal(r.mesh,new V(1081,56,1471)).horizontalDistance())).orElseThrow();
   System.out.println("FIX2_SLOW_ROAD "+slow.record.id());
   int passed=0;
   var editIds=Boolean.getBoolean("road.feedbackFix2OnlySlow")?List.of(slow.record.id()):List.of(UUID.fromString("2618586e-ceb0-4a05-839d-cd6080c8d27c"),slow.record.id());
   for(var id:editIds){
    var r=data.editableRoad(id);long begin=System.nanoTime();
    var originalPoints=LaneTopology.metadata(data.index.roads.get(id).record).points().stream().filter(p->!p.automatic()).map(LanePoints.Point::id).toList();
    var originalLinks=new HashMap<UUID,LanePoints.Link>();for(var b:data.index.roads.values())if(LaneTopology.metadata(b.record).link()!=null)originalLinks.put(b.record.id(),LaneTopology.metadata(b.record).link());
    System.out.println("FIX2_EDIT_BEGIN "+id+" editable="+r.id()+" points="+LaneTopology.metadata(data.index.roads.get(id).record).points());
    try {
     data.connect(level,null,r.a(),r.b(),r.settings(),id);
     var next=data.index.roads.get(id);
     if(next==null||!LaneTopology.metadata(next.record).points().stream().map(LanePoints.Point::id).toList().containsAll(originalPoints))throw new AssertionError("manual lane points lost");
     for(var entry:originalLinks.entrySet()){
      var kept=data.index.roads.get(entry.getKey());
      if(kept==null||!Objects.equals(LaneTopology.metadata(kept.record).link().from(),entry.getValue().from())||!Objects.equals(LaneTopology.metadata(kept.record).link().to(),entry.getValue().to()))throw new AssertionError("connection lost "+entry.getKey());
     }
     passed++;
     System.out.println("FIX2_EDIT_OK "+id+" seconds="+(System.nanoTime()-begin)/1e9+" points="+LaneTopology.metadata(data.index.roads.get(id).record).points());
    } catch(Throwable f){System.out.println("FIX2_EDIT_FAIL "+id+" seconds="+(System.nanoTime()-begin)/1e9);f.printStackTrace();}
   }
   if(passed!=editIds.size())throw new AssertionError("edits passed "+passed+"/"+editIds.size());
   System.out.println("FIX2_WORLD_PASS edits="+passed+" roads="+data.index.roads.size());
   buriedRails(level,data,"after");
   if(railDeckConflicts(data,"after")!=0)throw new AssertionError("rail remains inside adjoining physical slab");
   exposedPosts(data,"after");
   System.out.println("FIX2_RAIL_DECK_PASS");
   if(Boolean.getBoolean("road.feedbackFix2Scenes")){
    var group=UUID.fromString("086ce4f0-d227-453e-bc4e-648ceae2683c");var before=data.junctions.get(group);
    level.getChunkAt(net.minecraft.core.BlockPos.of(before.getLong("Branch")));
    System.out.println("FIX2_SCENE_BEGIN junction");
    System.out.println("FIX2_SCENE_PASS "+OneSidedJunctions.build(level,null,OneSidedJunctions.payload(level,null,group)));
    var main=data.index.roads.get(UUID.fromString("2c0e4c31-4bcd-4877-9c24-96a38665b5e2"));
    System.out.println("FIX2_SCENE_BEGIN interchange");
    System.out.println("FIX2_SCENE_PASS "+Interchanges.build(level,null,Interchanges.descriptor(data,main.record.assembly())));
    System.out.println("FIX2_SCENES_PASS edits=2");
   }
  }catch(Throwable f){System.out.println("FIX2_WORLD_FAIL");f.printStackTrace();}
  finally{server.halt(false);}
 }
}
