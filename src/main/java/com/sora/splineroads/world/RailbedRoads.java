package com.sora.splineroads.world;

import com.sora.splineroads.compat.mtr.MtrRailAccess;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.*;

/** MTR is an alignment source. All construction is an ordinary RoadData transaction. */
final class RailbedRoads {
  private static final Deque<Runnable> queue=new ArrayDeque<>();
  private static final Set<CompletableFuture<?>> active=new HashSet<>();
  static final class Preview {
    final ServerLevel level;final RoadData data;final UUID token=UUID.randomUUID();final String dimension;
    final long sourceRevision;final CompletableFuture<RailbedStore.Entry> future=new CompletableFuture<>();
    Preview(ServerLevel level){this.level=level;data=RoadData.get(level);var dim=level.dimension().location();dimension=dim.getNamespace()+"/"+dim.getPath();sourceRevision=MtrRailAccess.revision(dimension);}
    boolean valid(){return !future.isCancelled()&&future.isDone()&&!future.isCompletedExceptionally()&&sourceRevision==MtrRailAccess.revision(dimension)&&data.preparedValid(level,token);}
  }
  static Preview preview(ServerLevel level,ServerPlayer player,UUID id,List<RailbedPath.Path> paths,double width,Structure mode,RailbedOptions options,List<RailbedPath.Path> surrounding){
    return preview(level,player,id,paths,width,mode,options,surrounding,()->false);
  }
  static Preview preview(ServerLevel level,ServerPlayer player,UUID id,List<RailbedPath.Path> paths,double width,Structure mode,RailbedOptions options,List<RailbedPath.Path> surrounding,java.util.function.BooleanSupplier cancelled){
    var result=new Preview(level);enqueue(result.future,()->{
      try(var budget=RoadPlanningBudget.cancellable("铁路路基预览",()->result.future.isCancelled()||cancelled.getAsBoolean())){
      RoadPlanningBudget.check();
      require(level,player,id,paths);var data=result.data;data.adoptRailbeds(level);
      var record=record(data,player,id,paths,width,mode,options);
      List<RoadIndex.Built> planned;try(var external=RoadExternalClearance.open(paths,surrounding)){planned=data.prepareAssembly(level,player,List.of(new RoadIndex.Built(record)),data.index.roads.containsKey(id)?Set.of(id):Set.of(),Set.of(),List.of(),result.token);}
      if(result.sourceRevision!=MtrRailAccess.revision(result.dimension))throw new IllegalArgumentException("MTR 轨道已改变，请重新预览");
      var road=planned.stream().filter(r->r.record.id().equals(id)).findFirst().orElseThrow().record;
      RoadPlanningBudget.check();result.future.complete(entry(road));
      }finally{if(cancelled.getAsBoolean()||result.future.isCancelled()){result.future.cancel(false);if(player!=null)RoadData.forgetPreview(player.getUUID(),result.token);}}
    });return result;
  }
  static CompletableFuture<RailbedStore.Entry> submit(ServerLevel level,ServerPlayer player,UUID id,List<RailbedPath.Path> paths,double width,Structure mode,RailbedOptions options,List<RailbedPath.Path> surrounding){
    return submit(level,player,id,paths,width,mode,options,surrounding,null);
  }
  static CompletableFuture<RailbedStore.Entry> submit(ServerLevel level,ServerPlayer player,UUID id,List<RailbedPath.Path> paths,double width,Structure mode,RailbedOptions options,List<RailbedPath.Path> surrounding,net.minecraft.nbt.CompoundTag planner){
    var future=new CompletableFuture<RailbedStore.Entry>();
    future.whenComplete((value,error)->{
      if(error!=null&&!(error instanceof java.util.concurrent.CancellationException)&&player!=null&&!player.isRemoved())
        player.displayClientMessage(net.minecraft.network.chat.Component.literal(error.getMessage()==null?"铁路路基操作失败":error.getMessage()),true);
    });
    Runnable build=()->{
      require(level,player,id,paths);var data=RoadData.get(level);data.adoptRailbeds(level);
      var dim=level.dimension().location();String dimension=dim.getNamespace()+"/"+dim.getPath();long revision=MtrRailAccess.revision(dimension);
      UUID token=UUID.randomUUID();var proposed=paths==null?List.<RoadIndex.Built>of():List.of(new RoadIndex.Built(record(data,player,id,paths,width,mode,options)));
      try(var external=RoadExternalClearance.open(paths==null?List.of():paths,paths==null?List.of():surrounding)){data.prepareAssembly(level,player,proposed,data.index.roads.containsKey(id)?Set.of(id):Set.of(),Set.of(),List.of(),token);}
      if(paths!=null&&revision!=MtrRailAccess.revision(dimension))throw new IllegalArgumentException("MTR 轨道已改变，请重新预览");
      data.buildPrepared(level,player,token);
      if(player!=null)player.displayClientMessage(net.minecraft.network.chat.Component.literal(paths==null?"铁路路基已删除；MTR 轨道保持原样":"铁路路基建造完成"),true);
    };
    var receipt=planner==null?null:planner.copy();
    enqueue(future,()->{if(receipt==null)build.run();else ManualPlanner.edit(player,receipt,()->{build.run();return "";});future.complete(paths==null?null:RoadData.get(level).railbeds.entries.get(id));});return future;
  }
  private static void require(ServerLevel level,ServerPlayer player,UUID id,List<RailbedPath.Path> paths){
    if(player!=null&&(player.isRemoved()||player.serverLevel()!=level||!player.mayBuild()))throw new IllegalArgumentException("建设权限或维度已改变");
    var data=RoadData.get(level);var old=data.index.roads.get(id);
    if(old!=null){if(!old.record.railway())throw new IllegalArgumentException("目标不是铁路路基");if(player!=null)RoadData.requireOwner(player,old.record.owner());}
    else if(paths==null)throw new IllegalArgumentException("铁路路基已不存在");
    if(paths!=null){String unsupported=RailbedGeometry.unsupported(paths);if(unsupported!=null)throw new IllegalArgumentException(unsupported);
      String identity=RailbedStore.Entry.identity(paths);for(var e:data.railbeds.entries.values())if(!e.road.id().equals(id)&&e.identity().equals(identity))throw new IllegalArgumentException("这段轨道已有 SR 路基，请编辑已有结构");
    }
  }
  private static RoadRecord record(RoadData data,ServerPlayer player,UUID id,List<RailbedPath.Path> paths,double width,Structure mode,RailbedOptions options){
    var plan=RailbedGeometry.plan(paths,width,mode);var mesh=plan.mesh();
    var settings=mesh.settings().structure(mode).options(mesh.settings().options().railbed(new RailbedProfile(paths,width,options)).infrastructure(RoadInfrastructure.Config.DEFAULT.bridge(options.bridge()).tunnel(options.tunnel())).outerRail(RoadProfile.OuterRail.ON));
    var old=data.index.roads.get(id);var owner=old!=null?old.record.owner():player==null?new UUID(141,1):player.getUUID();
    var first=paths.get(0).segments().get(0).from();var last=paths.get(0).segments().get(paths.get(0).segments().size()-1).to();
    return new RoadRecord(id,owner,RailbedCodec.pos(first),RailbedCodec.pos(last),new Node(mesh.first().center(),0,0),new Node(mesh.last().center(),0,0),settings,false,5,List.of(),0,143,null,mesh.samples(),old==null?null:old.record.furniturePhase());
  }
  static RailbedStore.Entry entry(RoadRecord road){var profile=road.settings().options().railbed();return new RailbedStore.Entry(road,profile.paths(),profile.requestedWidth(),road.settings().structure(),profile.fittings());}
  static void sync(RoadData data,List<RoadIndex.Built> built,Set<UUID> removed){
    for(UUID id:removed)if(!data.index.roads.containsKey(id))data.railbeds.entries.remove(id);
    for(var road:built)if(road.record.railway()&&!road.record.legacyRailway())data.railbeds.entries.put(road.record.id(),entry(road.record));
  }
  private static void enqueue(CompletableFuture<?> future,Runnable action){
    if(queue.size()>=16)throw new IllegalArgumentException("道路施工队列已满，请稍后重试");active.add(future);
    queue.add(()->{try{if(!future.isCancelled())action.run();}catch(RuntimeException e){future.completeExceptionally(e);}finally{active.remove(future);}});
  }
  static void tick(){if(!queue.isEmpty())queue.removeFirst().run();}
  static void stop(){for(var future:active)future.cancel(false);active.clear();queue.clear();}
  private RailbedRoads(){}
}
