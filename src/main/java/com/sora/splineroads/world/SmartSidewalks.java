package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/** Plans real blocks and partial-cell infill; RoadData owns their original terrain. */
public final class SmartSidewalks {
  public static Map<Long,BlockState> plan(Collection<RoadIndex.Built> roads) {
    var result=new LinkedHashMap<Long,BlockState>();var materials=new HashMap<String,BlockState>();
    var junctions=new HashSet<JunctionSpec>();
    for(var built:roads.stream().sorted(Comparator.comparing(b->b.record.id())).toList()) {
      var r=built.record;var config=r.settings().options().sidewalk();
      if(r.junction()!=null) {
        var spec=r.junction().spec();
        if(junctions.add(spec))for(var e:r.junction().sidewalks().entrySet())put(result,materials,e.getKey(),e.getValue());
        int arm=r.junction().get().arm();if(arm<0)continue;
        config=spec.arms().get(arm).external().options().sidewalk();
      } else if(r.assembly()!=null||RoadProfile.catalog(r.settings().style()).type()!=RoadProfile.Type.ORDINARY)continue;
      if(!config.enabled()||config.smooth())continue;
      for(var cell:RoadSidewalks.cells(built.mesh,config))if(!RoadStreetscape.underground(built.mesh,RoadQueries.horizontal(built.mesh,new com.sora.splineroads.core.RoadGeometry.V(cell.x()+.5,cell.y(),cell.z()+.5)).sample().distance()))put(result,materials,cell,config);
    }
    var byChunk=new HashMap<Long,List<RoadIndex.Built>>();
    for(var r:roads)for(long chunk:r.chunks)byChunk.computeIfAbsent(chunk,k->new ArrayList<>()).add(r);
    result.keySet().removeIf(key->{
      BlockPos p=BlockPos.of(key);long column=new BlockPos(p.getX(),0,p.getZ()).asLong();
      for(var r:byChunk.getOrDefault(net.minecraft.world.level.ChunkPos.asLong(p.getX()>>4,p.getZ()>>4),List.of())) {
        if(r.clearanceAt(p))return true;
        if(r.boxes(p).isEmpty())continue;
        var c=r.column(p);
        // A sidewalk may fill the unused portion of an edge cell below its deck.
        // It may never replace headroom, a mast, or pavement at a lower elevation.
        if(c==null||p.getY()+1>c.minTop()+1e-7)return true;
      }
      return false;
    });
    return result;
  }
  public static Map<Long,BlockState> plan(Collection<RoadIndex.Built> roads,net.minecraft.server.level.ServerLevel level,Map<Long,BlockState> originals,Map<Long,BlockState> previous,Set<Long> aprons,Collection<RoadIndex.Built> edited,Collection<RoadIndex.Built> dirty){
    var result=plan(roads);var materials=new HashMap<String,BlockState>();
    var editedIds=new HashSet<java.util.UUID>();dirty.forEach(r->editedIds.add(r.record.id()));
    var unchanged=new HashMap<Long,List<RoadIndex.Built>>();
    for(var r:roads)if(!editedIds.contains(r.record.id()))for(long chunk:r.chunks)unchanged.computeIfAbsent(chunk,k->new ArrayList<>()).add(r);
    var affectedNear=new Nearby(dirty);var bordersNear=new Nearby(roads);
    for(var e:previous.entrySet()){
      if(aprons.contains(e.getKey()))continue;
      var p=BlockPos.of(e.getKey());var point=new com.sora.splineroads.core.RoadGeometry.V(p.getX()+.5,p.getY()+1,p.getZ()+.5);
      boolean affected=affectedNear.at(p).stream().anyMatch(r->{var q=RoadQueries.horizontal(r.mesh,point);return Math.abs(point.y()-q.sample().center().y())<2&&point.sub(q.sample().center()).horizontalLength()<=q.sample().halfWidth()+r.record.settings().options().sidewalk().width()+2;});
      boolean retained=unchanged.getOrDefault(net.minecraft.world.level.ChunkPos.asLong(p.getX()>>4,p.getZ()>>4),List.of()).stream().anyMatch(r->{
        var c=r.record.settings().options().sidewalk();if(!c.enabled()||r.record.settings().options().streetscape().apron()!=RoadStreetscape.Apron.ROAD)return false;
        var q=RoadQueries.horizontal(r.mesh,point);int side=q.lateral()<0?-1:1;
        if(c.side()==RoadSidewalks.Side.LEFT&&side>0||c.side()==RoadSidewalks.Side.RIGHT&&side<0)return false;
        double edge=q.sample().halfWidth()+RoadSidewalks.walkWidth(r.mesh,c,q.sample(),side);
        return Math.abs(point.y()-q.sample().center().y())<1.1&&Math.abs(Math.abs(q.lateral())-edge)<1.6&&q.horizontalDistance()<edge+1.6;
      });
      if(retained||!affected&&bordersNear.at(p).stream().anyMatch(r->ownsBorder(r,p)))result.putIfAbsent(e.getKey(),e.getValue());
    }
    var byChunk=new HashMap<Long,List<RoadIndex.Built>>();for(var r:roads)for(long chunk:r.chunks)byChunk.computeIfAbsent(chunk,k->new ArrayList<>()).add(r);
    for(var r:edited){var c=r.record.settings().options().sidewalk();if(!c.enabled()||!c.smooth()||r.record.settings().options().streetscape().apron()!=RoadStreetscape.Apron.ROAD||r.record.settings().structure()==com.sora.splineroads.core.RoadGeometry.Structure.TUNNEL)continue;
      var paving=new RoadSidewalks.PavingAnchors(r.record.structures());
      // Extend the exact ribbon by one block, then keep only the outer ring.
      var expanded=new com.sora.splineroads.core.RoadGeometry.Mesh(r.mesh.samples().stream().map(s->new com.sora.splineroads.core.RoadGeometry.Sample(s.center(),s.left(),s.distance(),s.halfWidth()+c.width())).toList(),r.mesh.settings(),r.mesh.min(),r.mesh.max(),r.mesh.length(),r.mesh.closed());
      var ring=new RoadSidewalks.Config(true,c.side(),1,c.material(),false);
      for(var cell:RoadSidewalks.cells(expanded,ring)){
        var p=new BlockPos(cell.x(),cell.y(),cell.z());long key=p.asLong();if(!RoadBuildWorld.loaded(level,p))continue;
        var current=RoadPlanInputs.state(level,p);var terrain=RoadBlocks.isCollider(current)||previous.containsKey(key)?originals.getOrDefault(key,current):current;
        if(terrain.hasBlockEntity()||!terrain.getFluidState().isEmpty()||!net.minecraft.world.level.block.Block.isShapeFullBlock(terrain.getCollisionShape(RoadBuildWorld.blocks(level),p)))continue;
        if(!paving.near(new com.sora.splineroads.core.RoadGeometry.V(p.getX()+.5,p.getY()+1,p.getZ()+.5),c.width()+4))continue;
        boolean blocked=false;
        // A one-cell border query must not materialize every collision cell of
        // an otherwise untouched neighboring road. column() uses the same exact
        // deck raster locally, and reuses a full raster when one already exists.
        for(var other:byChunk.getOrDefault(net.minecraft.world.level.ChunkPos.asLong(p.getX()>>4,p.getZ()>>4),List.of())){var col=other.column(p);if(col!=null&&p.getY()+1>col.minTop()+1e-6&&p.getY()<col.maxTop()+4.25){blocked=true;break;}}
        if(!blocked)put(result,materials,cell,ring);
      }
    }return result;
  }
  private static boolean ownsBorder(RoadIndex.Built road,BlockPos p){
    var c=road.record.settings().options().sidewalk();if(!c.enabled()||road.record.settings().options().streetscape().apron()!=RoadStreetscape.Apron.ROAD)return false;
    var point=new com.sora.splineroads.core.RoadGeometry.V(p.getX()+.5,p.getY()+1,p.getZ()+.5);
    var q=RoadQueries.horizontal(road.mesh,point);int side=q.lateral()<0?-1:1;
    if(c.side()==RoadSidewalks.Side.LEFT&&side>0||c.side()==RoadSidewalks.Side.RIGHT&&side<0)return false;
    double edge=q.sample().halfWidth()+RoadSidewalks.walkWidth(road.mesh,c,q.sample(),side);
    return Math.abs(point.y()-q.sample().center().y())<1.1&&Math.abs(Math.abs(q.lateral())-edge)<1.6&&q.horizontalDistance()<edge+1.6;
  }
  /** Conservative horizontal segment capsules. The exact projection tests above
   * still decide membership, including endpoints and graded/variable-width walks. */
  private static final class Nearby {
    private final Map<Long,List<RoadIndex.Built>> bins=new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    Nearby(Collection<RoadIndex.Built> roads){
      for(var road:roads){
        var config=road.record.settings().options().sidewalk();var ends=road.mesh.settings().options().ends();
        double width=config.width();
        // walkBlend is base + (start-base)*a + (end-base)*b, with a,b in [0,1].
        // Sum absolute changes so even overlapping transition zones are covered.
        if(ends.start()!=null&&ends.start().sidewalk()!=null)width+=Math.abs(ends.start().sidewalk().width()-config.width());
        if(ends.end()!=null&&ends.end().sidewalk()!=null)width+=Math.abs(ends.end().sidewalk().width()-config.width());
        var keys=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();var previous=road.mesh.first();
        for(var next:road.mesh.samples()){
          double radius=Math.max(previous.halfWidth(),next.halfWidth())+width+3;
          int x0=(int)Math.floor(Math.min(previous.center().x(),next.center().x())-radius)>>8;
          int x1=(int)Math.floor(Math.max(previous.center().x(),next.center().x())+radius)>>8;
          int z0=(int)Math.floor(Math.min(previous.center().z(),next.center().z())-radius)>>8;
          int z1=(int)Math.floor(Math.max(previous.center().z(),next.center().z())+radius)>>8;
          for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)keys.add(net.minecraft.world.level.ChunkPos.asLong(x,z));
          previous=next;
        }
        for(long key:keys)bins.computeIfAbsent(key,ignored->new ArrayList<>()).add(road);
      }
    }
    List<RoadIndex.Built> at(BlockPos p){return bins.getOrDefault(net.minecraft.world.level.ChunkPos.asLong(p.getX()>>8,p.getZ()>>8),List.of());}
  }
  private static void put(Map<Long,BlockState> result,Map<String,BlockState> materials,RoadSidewalks.Cell cell,RoadSidewalks.Config config) {
    if(config.smooth())return;
    var state=materials.computeIfAbsent(config.material(),name->{
      ResourceLocation id=ResourceLocation.tryParse(name);
      if(id==null||!BuiltInRegistries.BLOCK.containsKey(id))throw new IllegalArgumentException("人行道材质不存在："+name);
      var block=BuiltInRegistries.BLOCK.get(id);var value=block.defaultBlockState();
      if(TunnelAir.isAirSpace(value)||block instanceof RoadBlocks.Road||block==com.sora.splineroads.SplineRoads.NODE.get()||value.hasBlockEntity())throw new IllegalArgumentException("请选择不含方块实体的普通原版或模组方块作为人行道材质");
      return value;
    });
    result.putIfAbsent(new BlockPos(cell.x(),cell.y(),cell.z()).asLong(),state);
  }
  private SmartSidewalks(){}
}
