package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.net.RoadNetwork;
import com.sora.splineroads.compat.mtr.MtrRailAccess;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;

/** Public connector facade for shared road transactions. The old journal engine below
 * is retained only to recover interrupted pre-1.0.41 saves and verify migration. */
public final class RailbedBuildQueue {
  private static final int MAX_CELLS=1_000_000;
  private static final Deque<Job> jobs=new ArrayDeque<>();
  private static final Set<ServerLevel> recovering=Collections.newSetFromMap(new IdentityHashMap<>());
  private static boolean protectionProbe;
  static boolean protectionProbe(){return protectionProbe;}
  public static CompletableFuture<RailbedStore.Entry> submit(ServerLevel level,ServerPlayer player,UUID id,List<RailbedPath.Path> paths,double width,Structure mode,List<RailbedPath.Path> surrounding){
    return submit(level,player,id,paths,width,mode,surrounding,RailbedOptions.DEFAULT);
  }
  public static CompletableFuture<RailbedStore.Entry> submit(ServerLevel level,ServerPlayer player,UUID id,List<RailbedPath.Path> paths,double width,Structure mode,List<RailbedPath.Path> surrounding,RailbedOptions options){
    return RailbedRoads.submit(level,player,id,paths,width,mode,options,surrounding);
  }
  public static CompletableFuture<RailbedStore.Entry> preview(ServerLevel level,ServerPlayer player,UUID id,List<RailbedPath.Path> paths,double width,Structure mode,List<RailbedPath.Path> surrounding){
    return previewTask(level,player,id,paths,width,mode,surrounding).future();
  }
  static final class Preview {
    private final RailbedRoads.Preview common;
    Preview(RailbedRoads.Preview common){this.common=common;}
    CompletableFuture<RailbedStore.Entry> future(){return common.future;}
    boolean valid(){return common.valid();}
  }
  static Preview previewTask(ServerLevel level,ServerPlayer player,UUID id,List<RailbedPath.Path> paths,double width,Structure mode,List<RailbedPath.Path> surrounding){
    return previewTask(level,player,id,paths,width,mode,surrounding,RailbedOptions.DEFAULT);
  }
  static Preview previewTask(ServerLevel level,ServerPlayer player,UUID id,List<RailbedPath.Path> paths,double width,Structure mode,List<RailbedPath.Path> surrounding,RailbedOptions options){
    return new Preview(RailbedRoads.preview(level,player,id,paths,width,mode,options,surrounding));
  }
  static Preview previewTask(ServerLevel level,ServerPlayer player,UUID id,List<RailbedPath.Path> paths,double width,Structure mode,List<RailbedPath.Path> surrounding,RailbedOptions options,java.util.function.BooleanSupplier cancelled){
    return new Preview(RailbedRoads.preview(level,player,id,paths,width,mode,options,surrounding,cancelled));
  }
  public static void tick(MinecraftServer server){
    for(var level:server.getAllLevels())if(recovering.add(level))for(var pending:RoadData.get(level).railbeds.pending.values())jobs.add(new Job(level,pending));
    long until=System.nanoTime()+4_000_000;int operations=0;
    while(!jobs.isEmpty()&&operations++<256&&System.nanoTime()<until){var job=jobs.removeFirst();
      try(var inputs=job.inputs==null?RoadPlanInputs.open(job.level,false):job.inputs.resume()){job.step();}catch(RuntimeException failure){job.fail(failure);}
      if(!job.done)jobs.addLast(job);
    }
    RailbedRoads.tick();
  }
  public static void stop(){RailbedRoads.stop();for(var j:jobs)j.result.completeExceptionally(new IllegalStateException("服务器停止，未完成施工将在重启时回退"));jobs.clear();recovering.clear();}
  static RailbedStructures.Ground ground(ServerLevel level,RoadData data,Map<Long,BlockState> original,List<RoadIndex.Built> roads,List<RailbedPath.Path> paths){
    var terrain=data.railbedTerrain(original);var railAir=RailbedStructures.clearance(paths);
    var meshes=roads.stream().filter(r->!r.record.railway()).map(r->r.mesh).toList();var fixtures=roads.stream().flatMap(r->r.record.structures().stream()).toList();
    var paving=new RoadPavingSupport(fixtures);var solids=new RoadSolidOverlap.Index(fixtures.stream().filter(p->!RoadSidewalks.smoothPart(p)&&p.material()!=RoadStructures.Material.GREEN&&p.material()!=RoadStructures.Material.SOIL).toList());
    var medianFills=roads.stream().collect(java.util.stream.Collectors.toMap(r->r.mesh,r->new RoadSolidOverlap.Index(r.record.structures().stream().filter(p->p.material()==RoadStructures.Material.GREEN||p.material()==RoadStructures.Material.SOIL).toList()),(a,b)->a,IdentityHashMap::new));
    var existingRails=RailbedStructures.clearance(roads.stream().filter(r->r.record.railway()).flatMap(r->{var entry=data.railbeds.entries.get(r.record.id());return entry==null?java.util.stream.Stream.<RailbedPath.Path>empty():entry.paths.stream();}).toList());
    var clearance=new RoadStructureClearance();var spacing=new RoadPierSpacing(fixtures);
    return new RailbedStructures.Ground(){
      public double floor(V p){try(var lease=RoadWorkChunks.open(level)){lease.node(BlockPos.containing(p.x(),p.y(),p.z()));return terrain.top(p.x(),p.z(),p.y());}}
      public double foundation(V center,double top,double width){double median=RoadSupports.medianFoundation(center,top,width,meshes);return paving.foundation(center,top,width,median,()->RailbedStructures.Ground.super.foundation(center,top,width));}
      public double surface(double x,double z,double y){try(var lease=RoadWorkChunks.open(level)){lease.node(BlockPos.containing(x,y,z));return terrain.surface(x,z,y);}}
      public boolean covered(V p){return surface(p.x(),p.z(),p.y())>p.y()+1;}
      public boolean blocked(RoadStructures.Part part){
        if(railAir.intersects(part)||existingRails.intersects(part)||solids.intersects(part)||paving.blocks(part)||spacing.tooClose(part))return true;
        for(var entry:medianFills.entrySet())if(entry.getValue().intersects(part)&&!(part.material()==RoadStructures.Material.CONCRETE&&RoadStructures.fitsMedian(part,entry.getKey(),meshes)))return true;
        for(var mesh:meshes)if(clearance.cycleBlocked(part,mesh)||RoadStructureClearance.pierBlocked(part,mesh,meshes))return true;
        return false;
      }
    };
  }
  static final class Job {
    final ServerLevel level;final RoadData data;final ServerPlayer player;final UUID id;final RailbedStore.Entry old;
    final CompletableFuture<RailbedStore.Entry> result=new CompletableFuture<>();
    final Long2ObjectOpenHashMap<BlockState> before=new Long2ObjectOpenHashMap<>(),after=new Long2ObjectOpenHashMap<>();
    final List<RailbedPath.Path> paths;final double width;final Structure mode;final RailbedOptions options;final RailbedGeometry.Plan plan;
    RailbedStructures.Planner planner;RoadIndex.Built built;RailbedStore.Entry next;Iterator<Long> chunks;long chunk;int column=256;
    Iterator<Long> write,oldCells,notify;RailbedStore.Pending journal;RuntimeException failure;boolean preview,published,rollback,done;long revision,sourceRevision;
    final String dimension;RoadPlanInputs inputs;
    Set<Long> protectedChunks=Set.of();Map<UUID,RoadRecord> obstacles=Map.of();
    Job(ServerLevel level,ServerPlayer player,UUID id,RailbedStore.Entry old,List<RailbedPath.Path> paths,double width,Structure mode,List<RailbedPath.Path> surrounding){
      this(level,player,id,old,paths,width,mode,surrounding,RailbedOptions.DEFAULT);
    }
    Job(ServerLevel level,ServerPlayer player,UUID id,RailbedStore.Entry old,List<RailbedPath.Path> paths,double width,Structure mode,List<RailbedPath.Path> surrounding,RailbedOptions options){
      this.options=options;this.level=level;data=RoadData.get(level);this.player=player;this.id=id;this.old=old;this.paths=paths==null?null:List.copyOf(paths);this.width=width;this.mode=mode;
      var location=level.dimension().location();dimension=location.getNamespace()+"/"+location.getPath();sourceRevision=MtrRailAccess.revision(dimension);
      revision=data.index.contentRevision();plan=paths==null?null:RailbedGeometry.plan(paths,width,mode);
      protectedChunks=new HashSet<>(RoadCoverage.chunks(plan==null?old.road.mesh():plan.mesh(),16));if(old!=null)protectedChunks.addAll(RoadCoverage.chunks(old.road.mesh(),16));obstacles=localState();
      if(plan!=null){var selected=paths.stream().flatMap(p->p.segments().stream()).map(RailbedPath.Segment::id).collect(java.util.stream.Collectors.toSet());var all=new ArrayList<RailbedPath.Path>();
        for(var p:surrounding)if(p.segments().stream().noneMatch(s->selected.contains(s.id())))all.add(p);all.addAll(paths);
        var roads=obstacles.keySet().stream().map(data.index.roads::get).filter(Objects::nonNull).toList();
        validateDeckClearance(paths,roads);
        validateDeckSolids(plan.mesh(),all,roads,data);
        planner=new RailbedStructures.Planner(plan.mesh(),mode,ground(level,data,old==null?Map.of():old.original,roads,all),options,paths);
      }else initializeWrites();
    }
    Job(ServerLevel level,RailbedStore.Pending pending){this.level=level;dimension="";data=RoadData.get(level);player=null;id=pending.id;old=pending.old;paths=null;width=0;mode=Structure.AUTO;options=RailbedOptions.DEFAULT;plan=null;journal=pending;before.putAll(pending.before);after.putAll(pending.after);rollback=true;published=true;failure=new IllegalStateException("已回退中断的铁路施工");write=before.keySet().iterator();restoreIndex();}
    void step(){
      if(rollback){restoreOne();return;}
      if(!published&&data.railbeds.entries.get(id)!=old)throw new IllegalArgumentException("原路基已改变，请重新预览");
      if(result.isCancelled())throw new IllegalArgumentException("铁路施工已取消并回退");
      if(paths!=null&&sourceRevision!=MtrRailAccess.revision(dimension))throw new IllegalArgumentException("MTR 轨道在施工中发生变化，请重新预览");
      if(player!=null&&(player.isRemoved()||player.serverLevel()!=level||!player.mayBuild()))throw new IllegalArgumentException("玩家离开或建设权限失效，施工已回退");
      if(data.index.contentRevision()!=revision){if(!localState().equals(obstacles))throw new IllegalArgumentException("附近 SR 道路已改变，请重新预览");revision=data.index.contentRevision();}
      if(oldCells!=null){if(oldCells.hasNext()){long key=oldCells.next();var p=BlockPos.of(key);try(var lease=RoadWorkChunks.open(level)){lease.node(p);var current=level.getBlockState(p);if(current.equals(old.written.get(key))){before.put(key,current);after.put(key,remainingState(key,old.original.get(key)));}}return;}oldCells=null;}
      if(planner!=null){if(!planner.done()){planner.step();return;}
        var mesh=plan.mesh();var a=paths.get(0).segments().get(0).from();var last=paths.get(0).segments().get(paths.get(0).segments().size()-1).to();
        var record=new RoadRecord(id,old==null?(player==null?new UUID(138,1):player.getUUID()):old.road.owner(),RailbedCodec.pos(a),RailbedCodec.pos(last),new Node(mesh.first().center(),0,0),new Node(mesh.last().center(),0,0),mesh.settings().structure(mode==Structure.TUNNEL?Structure.TUNNEL:Structure.GROUND),false,5,planner.result(),0,138,id,mesh.samples());
        next=new RailbedStore.Entry(record,paths,width,mode,options);built=RoadIndex.Built.loading(record);built.prepareCollision();chunks=new TreeSet<>(built.chunks).iterator();planner=null;initializeWrites();return;
      }
      if(!published&&built!=null){if(column>=256){if(!chunks.hasNext()){publish();return;}chunk=chunks.next();column=0;}
        var cp=new ChunkPos(chunk);int x=cp.getMinBlockX()+column%16,z=cp.getMinBlockZ()+column/16;column++;prepareColumn(x,z);return;}
      if(!published){publish();return;}
      if(write.hasNext()){long key=write.next();var pos=BlockPos.of(key);try(var lease=RoadWorkChunks.open(level)){lease.node(pos);var current=level.getBlockState(pos);var expected=before.get(key);
        if(!RoadPlanInputs.equivalentTerrain(expected,current))throw new IllegalArgumentException("施工区域已被修改："+pos.toShortString()+"（"+BuiltInRegistries.BLOCK.getKey(expected.getBlock())+" → "+BuiltInRegistries.BLOCK.getKey(current.getBlock())+"）");
        if(!current.equals(expected)){before.put(key,current);if(next!=null)next.original.put(key,current);data.setDirty();}
        protect(pos,current,after.get(key));set(pos,after.get(key));fill(pos,after.get(key),next);}return;}
      if(notify==null)notify=after.keySet().iterator();if(notify.hasNext()){notifyOne(false);return;}
      if(next==null)data.railbeds.entries.remove(id);else data.railbeds.entries.put(id,next);data.railbeds.pending.remove(id);data.setDirty();done=true;result.complete(next);message(next==null?"铁路路基已删除；MTR 轨道保持原样":"铁路路基建造完成");
    }
    void initializeWrites(){if(old!=null)oldCells=old.written.keySet().iterator();}
    Map<UUID,RoadRecord> localState(){var result=new HashMap<UUID,RoadRecord>();for(long chunk:protectedChunks)for(var other:data.index.inChunk(chunk))if(!other.equals(id)){var road=data.index.roads.get(other);if(road!=null)result.put(other,road.record);}return Map.copyOf(result);}
    void prepareColumn(int x,int z){try(var lease=RoadWorkChunks.open(level)){lease.load(Set.of(ChunkPos.asLong(x>>4,z>>4)));var local=built.prepareCollision();var deck=local.deckColumn(x,z);var body=new HashMap<>(deck);local.structureColumn(x,z,p->true).forEach((cell,boxes)->body.merge(cell,boxes,(a,b)->{var both=new ArrayList<>(a);both.addAll(b);return both;}));
      var cells=new HashSet<>(body.keySet());var c=built.column(new BlockPos(x,0,z));if(c!=null)for(int y=(int)Math.floor(c.minTop());y<(int)Math.ceil(c.maxTop()+6);y++)cells.add(new RoadRaster.Cell(x,y,z));
      for(var cell:cells){var p=new BlockPos(x,cell.y(),z);long key=p.asLong();if(level.isOutsideBuildHeight(p)||!level.getWorldBorder().isWithinBounds(p))throw new IllegalArgumentException("铁路结构超出世界边界");
        var current=RoadPlanInputs.state(level,p);var original=source(key,current);
        boolean solid=body.containsKey(cell);if(isMtr(current)){if(!solid||MtrRailAccess.trainNode(current.getBlock())&&!local.structureColumn(x,z,part->true).containsKey(cell))continue;throw new IllegalArgumentException("铁路结构碰到 MTR 设施："+p.toShortString());}
        var nearby=new ArrayList<RoadIndex.Built>();nearby.add(built);
        for(var other:data.index.inChunk(ChunkPos.asLong(x>>4,z>>4)))if(!other.equals(id)){
          var road=data.index.roads.get(other);if(road==null)continue;nearby.add(road);
          // Exact deck/fixture/clearance prisms were checked before rasterization.
          // Stair-stepped voxel boxes overlap at diagonal seams even when the
          // actual structures only touch; retain both owners in this cell.
        }
        boolean otherBody=nearby.stream().skip(1).anyMatch(r->!r.boxes(p).isEmpty());
        if(!solid&&otherBody){
          // Reserved deck air is wider than the train. Existing wall/deck at a
          // continuous endpoint is not an obstacle unless the rail prism hits it.
          next.original.put(key,original);next.written.put(key,current);before.remove(key);after.remove(key);continue;
        }
        if(old!=null&&old.written.containsKey(key)&&!current.equals(old.written.get(key)))throw new IllegalArgumentException("原路基区域已有玩家修改："+p.toShortString());
        RoadClearing.validate(level,p,original,false);
        var target=solid?data.railbedCollision(p,original,nearby):SplineRoads.TUNNEL_AIR.get().defaultBlockState();
        if(preview){if(player!=null&&!level.mayInteract(player,p))throw new IllegalArgumentException("铁路预览范围受保护："+p.toShortString());}else protect(p,current,target);next.original.put(key,original);next.written.put(key,target);if(!current.equals(target)){before.put(key,current);after.put(key,target);}else if(target.is(SplineRoads.FILLED_COLLIDER.get())){before.put(key,current);after.put(key,target);}else{before.remove(key);after.remove(key);}
        if(next.written.size()>MAX_CELLS||before.size()>MAX_CELLS)throw new IllegalArgumentException("铁路施工范围超过预算，请分段建造");
      }
    }}
    BlockState source(long key,BlockState current){
      if(old!=null&&current.equals(old.written.get(key)))return old.original.get(key);
      for(var e:data.railbeds.entries.values())if(!e.road.id().equals(id)&&current.equals(e.written.get(key)))return e.original.get(key);
      return RoadBlocks.isCollider(current)||TunnelAir.isAirSpace(current)?data.railbedOriginal(key):current;
    }
    BlockState remainingState(long key,BlockState original){
      for(var e:data.railbeds.entries.values())if(!e.road.id().equals(id)&&e.written.containsKey(key))return e.written.get(key);
      var p=BlockPos.of(key);var nearby=data.index.inChunk(new ChunkPos(p).toLong()).stream().filter(v->!v.equals(id)).map(data.index.roads::get).filter(Objects::nonNull).toList();
      if(nearby.stream().anyMatch(r->!r.boxes(p).isEmpty()))return data.railbedCollision(p,original,nearby);
      if(nearby.stream().anyMatch(r->r.clearanceAt(p)))return SplineRoads.TUNNEL_AIR.get().defaultBlockState();
      return original;
    }
    void fill(BlockPos p,BlockState state,RailbedStore.Entry entry){
      if(!state.is(SplineRoads.FILLED_COLLIDER.get()))return;
      var original=entry!=null&&entry.original.containsKey(p.asLong())?entry.original.get(p.asLong()):source(p.asLong(),state);
      var nearby=data.index.inChunk(new ChunkPos(p).toLong()).stream().map(data.index.roads::get).filter(Objects::nonNull).toList();
      if(level.getBlockEntity(p) instanceof RoadFillEntity fill)fill.fill(original,GroundTerrainJoin.cap(p,original,nearby));
    }
    void protect(BlockPos pos,BlockState current,BlockState target){if(current.equals(target))return;if(player==null)return;
      if(!level.mayInteract(player,pos)||!player.mayUseItemAt(pos,net.minecraft.core.Direction.UP,player.getMainHandItem()))throw new IllegalArgumentException("铁路施工范围受保护："+pos.toShortString());
      if(!current.isAir()){boolean rejected;protectionProbe=true;try{rejected=MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level,pos,current,player));}finally{protectionProbe=false;}if(rejected)throw new IllegalArgumentException("铁路施工被区域保护拒绝："+pos.toShortString());}
      var snapshot=BlockSnapshot.create(level.dimension(),level,pos);var event=new BlockEvent.EntityPlaceEvent(snapshot,current,player){@Override public BlockState getPlacedBlock(){return target;}@Override public BlockState getState(){return target;}};
      if(MinecraftForge.EVENT_BUS.post(event))throw new IllegalArgumentException("铁路施工被区域保护拒绝："+pos.toShortString());
    }
    void publish(){if(preview){if(inputs!=null)inputs.validate(level);done=true;result.complete(next);return;}journal=new RailbedStore.Pending(id,old,next,before,after);data.railbeds.pending.put(id,journal);data.setDirty();published=true;write=after.keySet().iterator();if(built!=null){data.index.put(built);RoadNetwork.broadcastRoad(level,built.record);}else{data.index.remove(id);RoadNetwork.broadcastDelete(level,id);}revision=data.index.contentRevision();}
    void fail(RuntimeException e){if(rollback){done=true;result.completeExceptionally(e);message("铁路回退中断，已保留恢复日志："+e.getMessage());return;}failure=e;if(published){rollback=true;notify=null;write=before.keySet().iterator();restoreIndex();message("铁路施工正在回退："+e.getMessage());}else{done=true;result.completeExceptionally(e);message(e.getMessage());}}
    void restoreIndex(){if(old==null){data.index.remove(id);RoadNetwork.broadcastDelete(level,id);}else{data.index.put(RoadIndex.Built.loading(old.road));RoadNetwork.broadcastRoad(level,old.road);}data.setDirty();}
    // As with ordinary-road restoration, apply all dependent blocks before shape
    // notifications. Otherwise removing one plant half changes the next write.
    void set(BlockPos p,BlockState state){level.setBlock(p,state,net.minecraft.world.level.block.Block.UPDATE_CLIENTS|net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);if(!level.getBlockState(p).equals(state))throw new IllegalStateException("铁路方块写入失败："+p.toShortString());}
    void notifyOne(boolean restoring){long key=notify.next();var p=BlockPos.of(key);try(var lease=RoadWorkChunks.open(level)){lease.node(p);var state=(restoring?before:after).get(key);if(!level.getBlockState(p).equals(state))return;
      (restoring?after:before).get(key).updateIndirectNeighbourShapes(level,p,2,511);state.updateNeighbourShapes(level,p,2,511);state.updateIndirectNeighbourShapes(level,p,2,511);level.updateNeighborsAt(p,state.getBlock());}}
    void restoreOne(){if(write.hasNext()){long key=write.next();var p=BlockPos.of(key);try(var lease=RoadWorkChunks.open(level)){lease.node(p);if(level.getBlockState(p).equals(after.get(key))){set(p,before.get(key));fill(p,before.get(key),old);}}return;}if(notify==null)notify=before.keySet().iterator();if(notify.hasNext()){notifyOne(true);return;}data.railbeds.pending.remove(id);data.setDirty();done=true;result.completeExceptionally(failure);message(failure.getMessage());}
    void message(String text){if(!preview&&player!=null&&!player.isRemoved())player.displayClientMessage(Component.literal(text),false);}
  }
  static void validateDeckClearance(List<RailbedPath.Path> paths,List<RoadIndex.Built> roads){
    var air=RailbedStructures.clearance(paths);
    for(var road:roads)for(var part:physicalParts(road))if(air.intersects(part))throw new IllegalArgumentException("已有 SR 结构侵入铁路通行空间，请调整高度或位置");
  }
  private static List<RoadStructures.Part> physicalParts(RoadIndex.Built road){
    var result=RoadTravelValidation.deckParts(road.mesh);road.record.structures().stream().filter(p->!RailbedFurniture.visualOnly(p)).forEach(result::add);return result;
  }
  private static void validateDeckSolids(Mesh mesh,List<RailbedPath.Path> rails,List<RoadIndex.Built> roads,RoadData data){
    var deck=RoadTravelValidation.deckParts(mesh);var allRails=new ArrayList<>(rails);
    for(var road:roads){var entry=data.railbeds.entries.get(road.record.id());if(entry!=null)allRails.addAll(entry.paths);}
    var air=RailbedStructures.clearance(allRails);
    for(var part:deck)if(air.intersects(part))throw new IllegalArgumentException("铁路路面侵入既有轨道通行空间");
    for(var road:roads){var solids=new RoadSolidOverlap.Index(physicalParts(road));
      for(var part:deck){
        if(solids.intersects(part))throw new IllegalArgumentException("铁路路面与已有 SR 实体相交");
        if(!road.record.railway()&&RoadClearance.structureInvades(part,road.mesh,RoadStructureClearance.headroom(road.mesh))&&!RoadClearance.belowSurface(part,road.mesh,.025))
          throw new IllegalArgumentException("铁路路面侵入已有道路通行空间");
      }
    }
  }
  private static boolean isMtr(BlockState state){return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("mtr");}
  private RailbedBuildQueue(){}
}
