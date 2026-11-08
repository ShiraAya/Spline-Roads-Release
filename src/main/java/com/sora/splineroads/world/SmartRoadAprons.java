package com.sora.splineroads.world;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.tags.BlockTags;
import java.util.*;

/** Real apron blocks share the sidewalk transaction's original-state/rollback ledger. */
final class SmartRoadAprons {
 static boolean material(BlockState state){return state.is(SplineRoads.ROAD_BLOCK.get())||state.is(SplineRoads.ROAD_SLAB.get());}
 static Set<Long> plan(Map<Long,BlockState> paving,Collection<RoadIndex.Built> roads,ServerLevel level,Map<Long,BlockState> originals,Map<Long,BlockState> previous,Set<Long> previousAprons,Collection<RoadIndex.Built> dirty){
  var aprons=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
  var affected=new HashSet<Long>();for(var r:dirty)affected.addAll(RoadCoverage.chunks(r.mesh,3));
  // Keep untouched roads without re-reading their terrain or re-rasterizing the city.
  for(var e:previous.entrySet())if(previousAprons.contains(e.getKey())&&!affected.contains(new ChunkPos(BlockPos.of(e.getKey())).toLong()))put(paving,aprons,e.getKey(),e.getValue());
  var local=new ArrayList<RoadIndex.Built>();var byChunk=new HashMap<Long,List<RoadIndex.Built>>();
  for(var r:roads){if(!Collections.disjoint(RoadCoverage.chunks(r.mesh,3),affected))local.add(r);for(long c:r.chunks)if(affected.contains(c))byChunk.computeIfAbsent(c,k->new ArrayList<>()).add(r);}
  record Candidate(double top,RoadIndex.Built road){}
  var candidates=new TreeMap<Long,Candidate>();
  var ground=RoadTerrain.read(level,Map.of(),originals,new RoadTerrain.Cache(Map.of(),originals));
  for(var r:local){if(r.record.junction()!=null&&r.record.junction().get().arm()<0){
    var ref=r.record.junction();if(ref.piece()!=ref.spec().arms().size())continue;
    for(var jc:RoadAprons.junctionCells(ref,s->grounded(s,ground,1))){var c=jc.cell();long chunk=ChunkPos.asLong(c.x()>>4,c.z()>>4);if(!affected.contains(chunk))continue;
      var owner=local.stream().filter(v->Objects.equals(v.record.assembly(),r.record.assembly())&&v.record.junction()!=null&&v.record.junction().get().arm()==jc.arm()).findFirst().orElse(r);
      candidates.merge(BlockPos.asLong(c.x(),0,c.z()),new Candidate(c.top(),owner),(a,b)->a.top()<=b.top()?a:b);
    }continue;
   }
   for(var c:RoadAprons.cells(r.mesh,(sample,side)->grounded(sample,ground,side))){long chunk=ChunkPos.asLong(c.x()>>4,c.z()>>4);if(affected.contains(chunk))candidates.merge(BlockPos.asLong(c.x(),0,c.z()),new Candidate(c.top(),r),(a,b)->a.top()<=b.top()?a:b);}
  }
  for(var entry:candidates.entrySet()){
   RoadPlanningBudget.check();var column=BlockPos.of(entry.getKey());double roadTop=entry.getValue().top();var owner=entry.getValue().road();
   boolean terrainMode=owner.record.railway()||owner.record.settings().options().streetscape().apron()==RoadStreetscape.Apron.TERRAIN;
   // Half-block steps follow slopes without raising pavement or changing its width.
   double top=Math.floor(roadTop*2+1e-6)/2;int y=(int)Math.ceil(top)-1;var p=new BlockPos(column.getX(),y,column.getZ());
   if(level.isOutsideBuildHeight(p)||!level.getWorldBorder().isWithinBounds(p)||!RoadWorkChunks.terrainAvailable(level,p))continue;
   BlockState desired=top-y<.75?SplineRoads.ROAD_SLAB.get().defaultBlockState().setValue(SlabBlock.TYPE,SlabType.BOTTOM):SplineRoads.ROAD_BLOCK.get().defaultBlockState();
   var near=byChunk.getOrDefault(new ChunkPos(p).toLong(),List.of());boolean blocked=false;
   for(var r:near){if(r.tunnelAt(p)||r.cycleBodyAt(p)){blocked=true;break;}var c=r.column(p);if(c!=null&&y+1>=c.minTop()-1e-7&&y<c.maxTop()+4.25){
     // Partial edge cells use a capped full texture through RoadFillEntity; never
     // substitute a slab model whose top lies above the cap or into another road.
     if(GroundTerrainJoin.usable(GroundTerrainJoin.cap(p,SplineRoads.ROAD_BLOCK.get().defaultBlockState(),near)))desired=SplineRoads.ROAD_BLOCK.get().defaultBlockState();else {blocked=true;break;}
    }else if(r.clearanceAt(p)){blocked=true;break;}}
   if(blocked)continue;
   BlockState chosen=terrainMode?adjacent(level,owner.mesh,p,originals,previous):SplineRoads.ROAD_BLOCK.get().defaultBlockState();
   if(chosen==null)continue;if(terrainMode)desired=chosen;
   var current=RoadPlanInputs.state(level,p);var base=terrain(current,p,originals,previous);
   if(previous.containsKey(p.asLong())&&!RoadBlocks.isCollider(current)&&!current.equals(previous.get(p.asLong())))continue;
   if(!replaceable(base))continue;
   // Only a ground-supported apron: do not fill across water, voids, bridge spans
   // or erase furniture. A single lower course closes a one-block terrain dip.
   var below=p.below();if(!RoadWorkChunks.terrainAvailable(level,below))continue;var lower=RoadPlanInputs.state(level,below);var support=terrain(lower,below,originals,previous);
   if(!base.canOcclude()&&!support.canOcclude()){
    if(!replaceable(support)||!support.getFluidState().isEmpty()||support.hasBlockEntity())continue;
    var beneath=below.below();if(!RoadWorkChunks.terrainAvailable(level,beneath))continue;var foundation=RoadPlanInputs.state(level,beneath);
    if(!foundation.canOcclude()||foundation.hasBlockEntity()||!foundation.getFluidState().isEmpty())continue;
    if(near.stream().anyMatch(r->r.clearanceAt(below)&&r.boxes(below).isEmpty()||r.tunnelAt(below)||r.cycleBodyAt(below)))continue;
    put(paving,aprons,below.asLong(),chosen);
   }
   put(paving,aprons,p.asLong(),desired);
   // At a noninteger deck level the edge voxel is above the outer slab's course.
   int edgeY=(int)Math.floor(roadTop);var edge=new BlockPos(p.getX(),edgeY,p.getZ());
   if(edgeY!=y&&GroundTerrainJoin.usable(GroundTerrainJoin.cap(edge,SplineRoads.ROAD_BLOCK.get().defaultBlockState(),near))){var at=RoadPlanInputs.state(level,edge);if(replaceable(terrain(at,edge,originals,previous))&&!at.hasBlockEntity())put(paving,aprons,edge.asLong(),chosen);}
  }return aprons;
 }
 private static BlockState adjacent(ServerLevel level,Mesh mesh,BlockPos p,Map<Long,BlockState> originals,Map<Long,BlockState> previous){
  var q=RoadQueries.horizontal(mesh,new V(p.getX()+.5,p.getY(),p.getZ()+.5));int side=q.lateral()<0?-1:1;
  for(double reach:new double[]{1.5,2.5,3.5})for(int down=0;down<=2;down++){
   V edge=q.sample().at(side*(q.sample().halfWidth()+reach),0);var at=BlockPos.containing(edge.x(),p.getY()-down,edge.z());
   if(!RoadWorkChunks.terrainAvailable(level,at))continue;
   var state=terrain(RoadPlanInputs.state(level,at),at,originals,previous);
   if(state.canOcclude()&&!state.hasBlockEntity()&&state.getFluidState().isEmpty()&&!RoadBlocks.isCollider(state)&&!material(state))return state;
  }
  return null;
 }
 private static void put(Map<Long,BlockState> paving,Set<Long> aprons,long key,BlockState state){if(paving.putIfAbsent(key,state)==null)aprons.add(key);}
 private static boolean grounded(Sample sample,RoadStructures.Ground ground,int side){
  int contact=0;for(double f:new double[]{.35,.65,.9}){var p=sample.at(side*sample.halfWidth()*f,0);double y=ground.top(p.x(),p.z(),p.y());if(Double.isFinite(y)&&p.y()-y<=RoadStructures.GROUND_CONTACT)contact++;}return contact>=2;
 }
 private static BlockState terrain(BlockState current,BlockPos p,Map<Long,BlockState> originals,Map<Long,BlockState> previous){return RoadBlocks.isCollider(current)||previous.containsKey(p.asLong())?originals.getOrDefault(p.asLong(),current):current;}
 private static boolean replaceable(BlockState s){return !s.hasBlockEntity()&&s.getFluidState().isEmpty()&&(s.isAir()||s.canBeReplaced()||s.is(BlockTags.DIRT)||s.is(BlockTags.SAND)||s.is(BlockTags.BASE_STONE_OVERWORLD)||s.is(Blocks.GRAVEL)||s.is(Blocks.SNOW_BLOCK)||s.is(Blocks.SNOW)||material(s));}
 private SmartRoadAprons(){}
}
