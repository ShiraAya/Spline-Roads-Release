package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.Settings;
import com.sora.splineroads.net.RoadNetwork;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** One isolated construction job per dimension. Capture and final publication
 * stay on the server; route, facilities, raster and fill calculations do not. */
@Mod.EventBusSubscriber(modid="splineroads")
public final class RoadBuildJobs {
  public static final class Pending extends RuntimeException {public Pending(){super(null,null,false,false);}}
  private static final TicketType<UUID> TICKET=TicketType.create("splineroads_build",UUID::compareTo);
  private static final Map<ServerLevel,Job<?>> ACTIVE=new IdentityHashMap<>();
  static java.util.function.Consumer<String> commitFault=stage->{};
  private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"SR-world-planner");t.setDaemon(true);t.setPriority(Thread.NORM_PRIORITY-1);return t;});
  static{WORKER.allowCoreThreadTimeOut(true);}
  private record Result<T>(RoadData data,T value,Set<UUID> changed,Map<Long,RoadBuildWorld.Cell> cells){}
  private static final class Job<T> {
    final UUID id=UUID.randomUUID();final ServerLevel level;final ServerPlayer player;final RoadData live;final long revision,started=System.nanoTime();
    final RoadBuildWorld world;final CompletableFuture<T> completion=new CompletableFuture<>();
    final CompletableFuture<Result<T>> calculation=new CompletableFuture<>();final Set<Long> tickets=new HashSet<>();
    final Map<Long,CompletableFuture<com.mojang.datafixers.util.Either<ChunkAccess,ChunkHolder.ChunkLoadingFailure>>> loading=new LinkedHashMap<>();
    Iterator<Map.Entry<Long,RoadBuildWorld.Cell>> protection;Result<T> ready;long captureNanos,commitNanos;int ticks;
    Job(ServerLevel level,ServerPlayer player,RoadData live){this.level=level;this.player=player;this.live=live;revision=live.index.contentRevision();world=new RoadBuildWorld(level,player==null?new UUID(0,0):player.getUUID());}
  }
  public static boolean worker(){return RoadBuildWorld.current()!=null;}
  public static boolean busy(ServerLevel level){return ACTIVE.containsKey(level);}
  public static net.minecraft.world.level.block.entity.BlockEntity node(ServerLevel level,BlockPos p){return RoadBuildWorld.entity(level,p);}
  public static CompletableFuture<RoadRecord> connect(ServerLevel level,ServerPlayer player,BlockPos a,BlockPos b,Settings settings,UUID replace,boolean levelEnds,boolean forceA,boolean forceB,List<BlockPos> controls){
    var controlSnapshot=List.copyOf(controls);
    return submit(level,player,data->data.connect(level,null,a,b,settings,replace,levelEnds,forceA,forceB,controlSnapshot));
  }
  /** The operation sees the same isolated RoadData/world as an ordinary save. */
  public static <T> CompletableFuture<T> submit(ServerLevel level,ServerPlayer player,java.util.function.Function<RoadData,T> operation){
    if(!level.getServer().isSameThread())throw new IllegalStateException("Construction must be submitted on the server thread");
    if(busy(level))throw new IllegalArgumentException("已有道路正在计算或保存，请等待完成");
    if(player!=null&&(!player.mayBuild()||player.isSpectator()))throw new IllegalArgumentException("当前不能建造道路");
    var live=RoadData.get(level);
    var job=new Job<T>(level,player,live);ACTIVE.put(level,job);
    try{
      var copy=live.buildCopy(level);var before=live.index.roads.values().stream().collect(java.util.stream.Collectors.toMap(r->r.record.id(),r->r.record));
      WORKER.execute(()->{
        try(var budget=RoadPlanningBudget.cancellable("道路后台建造",()->job.world.cancelled)){
          var data=copy.get();
          try(var scope=job.world.enter(data)){
            var value=operation.apply(data);
            var ids=new HashSet<>(before.keySet());ids.addAll(data.index.roads.keySet());
            ids.removeIf(id->{var after=data.index.roads.get(id);return Objects.equals(before.get(id),after==null?null:after.record);});
            job.calculation.complete(new Result<>(data,value,Set.copyOf(ids),job.world.changes()));
          }
        }catch(Throwable e){job.calculation.completeExceptionally(e);}
        finally{RoadQueries.clearThreadCache();}
      });
    }catch(RuntimeException failure){finish(job,null,failure);throw failure;}
    return job.completion;
  }
  @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event){
    if(event.phase!=TickEvent.Phase.END)return;
    for(var job:List.copyOf(ACTIVE.values())){
      if(job.level.getServer()==event.getServer())tick(job);
    }
  }
  private static <T> void tick(Job<T> job){
      job.ticks++;
      try{
        if(job.player!=null&&(job.player.hasDisconnected()||job.player.serverLevel()!=job.level))throw new IllegalArgumentException("玩家已离开，未写入道路");
        if(job.player!=null&&(!job.player.mayBuild()||job.player.isSpectator()))throw new IllegalArgumentException("玩家建造权限已改变，未写入道路");
        if(job.completion.isCancelled())throw new RoadPlanningBudget.Aborted("道路计算已取消，未写入世界");
        if(job.live.index.contentRevision()!=job.revision)throw new IllegalArgumentException("计算期间道路已改变，请重新保存");
        long begin=System.nanoTime();capture(job,begin+4_000_000);job.captureNanos+=System.nanoTime()-begin;
        if(!job.calculation.isDone())return;
        if(job.ready==null){
          job.ready=job.calculation.join();
          if(job.player!=null)for(var id:job.ready.changed()){var old=job.live.index.roads.get(id);if(old!=null)RoadData.requireOwner(job.player,old.record.owner());}
          job.protection=job.ready.cells().entrySet().iterator();
        }
        long until=System.nanoTime()+4_000_000;
        while(job.protection.hasNext()){
          var entry=job.protection.next();var p=BlockPos.of(entry.getKey());var cell=entry.getValue();
          if(!job.level.getWorldBorder().isWithinBounds(p))throw new IllegalArgumentException("道路超出当前世界边界");
          RoadEditProtection.check(job.level,job.player,p,job.level.getBlockState(p),cell.after());
          if(System.nanoTime()>=until)break;
        }
        if(job.protection.hasNext())return;
        commit(job);finish(job,job.ready.value(),null);
      }catch(Throwable error){if(error instanceof CompletionException&&error.getCause()!=null)error=error.getCause();finish(job,null,error);}
  }
  private static void capture(Job<?> job,long until){
    while(System.nanoTime()<until&&job.loading.size()<8){
      var key=job.world.requests.poll();if(key==null)break;
      if(!job.world.columns.containsKey(key)){job.world.requests.add(key);break;}
      var pos=new ChunkPos(key);
      if(!job.level.getWorldBorder().isWithinBounds(pos))throw new IllegalArgumentException("道路超出世界边界");
      job.level.getChunkSource().addRegionTicket(TICKET,pos,0,job.id);job.tickets.add(key);
      job.loading.put(key,job.level.getChunkSource().getChunkFuture(pos.x,pos.z,ChunkStatus.FULL,true));
    }
    for(var iterator=job.loading.entrySet().iterator();iterator.hasNext()&&System.nanoTime()<until;){
      var entry=iterator.next();if(!entry.getValue().isDone())continue;
      var chunk=entry.getValue().join().left().orElseThrow(()->new IllegalArgumentException("道路区块加载失败"));
      if(!(chunk instanceof LevelChunk full))throw new IllegalStateException("Full chunk missing");
      job.world.columns.get(entry.getKey()).complete(RoadBuildWorld.capture(full));iterator.remove();
    }
  }
  private static void commit(Job<?> job){
    long begin=System.nanoTime();job.ready.data().validateBuildInputs();job.world.validate(job.player);
    var cells=job.ready.cells();
    for(var entry:cells.entrySet()){var p=BlockPos.of(entry.getKey());if(!entry.getValue().before().equals(job.level.getBlockState(p))||!job.level.getWorldBorder().isWithinBounds(p))throw new IllegalArgumentException("计算期间施工范围已改变，请重新保存道路");}
    long validated=System.nanoTime();var rollback=job.live.publishBuild(job.ready.data(),job.ready.changed());
    long published=System.nanoTime();
    var applied=new ArrayList<Long>();
    try{
      commitFault.accept("publish");
      for(var split:job.world.signSplits)job.live.smartSigns.splitKeys(split.source(),split.owners());
      for(var entry:cells.entrySet()){
        long key=entry.getKey();var p=BlockPos.of(key);var cell=entry.getValue();applied.add(key);
        if(!cell.before().equals(cell.after()))job.level.setBlock(p,cell.after(),cell.knownNeighbors()?18:2);
        if(!RoadData.sameWrittenState(job.level.getBlockState(p),cell.after()))throw new IllegalStateException("世界拒绝写入道路方块："+p.toShortString());
        if(cell.fill()!=null){if(!(job.level.getBlockEntity(p) instanceof RoadFillEntity fill))throw new IllegalStateException("道路填土实体创建失败");fill.fill(cell.fill());}
        else if(cell.entity()!=null){var entity=job.level.getBlockEntity(p);if(entity==null)throw new IllegalStateException("道路方块实体创建失败");entity.load(cell.entity().copy());entity.setChanged();job.level.sendBlockUpdated(p,cell.after(),cell.after(),2);}
        commitFault.accept("write");
      }
    }catch(RuntimeException failure){
      rollback.run();var states=new LinkedHashMap<Long,net.minecraft.world.level.block.state.BlockState>();var entities=new HashMap<Long,net.minecraft.nbt.CompoundTag>();
      for(long key:applied){var cell=cells.get(key);states.put(key,cell.before());if(cell.oldEntity()!=null)entities.put(key,cell.oldEntity());}
      states.putAll(job.world.rollbackPlants);
      try{RoadData.restorePlannerBlocks(job.level,states,entities);}catch(RuntimeException restore){failure.addSuppressed(restore);}throw failure;
    }
    long written=System.nanoTime();
    for(var id:job.ready.changed()){
      job.live.smartSigns.changed(id);var road=job.live.index.roads.get(id);
      if(road==null)RoadNetwork.broadcastDelete(job.level,id);else RoadNetwork.broadcastRoad(job.level,road.record);
    }
    job.commitNanos=System.nanoTime()-begin;
    System.out.println("SR_BUILD_COMMIT validateMs="+(validated-begin)/1e6+" publishMs="+(published-validated)/1e6+" writeMs="+(written-published)/1e6+" totalMs="+job.commitNanos/1e6+" blocks="+cells.values().stream().filter(c->!c.before().equals(c.after())).count()+" fills="+cells.values().stream().filter(c->c.fill()!=null).count());
  }
  private static <T> void finish(Job<T> job,T value,Throwable error){
    ACTIVE.remove(job.level,job);job.world.cancelled=true;
    var cancelled=new RoadPlanningBudget.Aborted("道路计算已结束");for(var future:job.world.columns.values())if(!future.isDone())future.completeExceptionally(cancelled);
    for(long key:job.tickets)job.level.getChunkSource().removeRegionTicket(TICKET,new ChunkPos(key),0,job.id);
    System.getLogger("SplineRoads/build").log(error==null?System.Logger.Level.INFO:System.Logger.Level.WARNING,"SR background build ticks="+job.ticks+" totalMs="+(System.nanoTime()-job.started)/1e6+" captureMs="+job.captureNanos/1e6+" commitMs="+job.commitNanos/1e6+" chunks="+job.tickets.size()+" writes="+(job.ready==null?0:job.ready.cells().size())+(error==null?" success":" failure="+error));
    if(error==null)job.completion.complete(value);else job.completion.completeExceptionally(error);
  }
  @SubscribeEvent public static void stopping(ServerStoppingEvent event){for(var job:List.copyOf(ACTIVE.values()))if(job.level.getServer()==event.getServer())finish(job,null,new RoadPlanningBudget.Aborted("服务器关闭，未写入道路"));}
  private RoadBuildJobs(){}
}
