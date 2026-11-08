package com.sora.splineroads.world;

import com.sora.splineroads.config.StreetServerConfig;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.StreetPlan.*;
import com.sora.splineroads.net.RoadNetwork;
import com.sora.splineroads.world.StreetTaskStore.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;

/** Server-owned bounded capture and connected construction queue. No world access on workers. */
public final class StreetTasks {
  private static final Map<UUID,Run> RUNNING=new ConcurrentHashMap<>();
  private static final Deque<UUID> QUEUE=new ArrayDeque<>();
  private static final class Run {
    final ServerLevel level;final Task task;final AtomicBoolean cancel=new AtomicBoolean();StreetTerrainCapture capture;boolean needsPlan;long nextTick;AutoJunctions.StreetChange prepared;UUID token;
    Run(ServerLevel level,Task task){this.level=level;this.task=task;}
  }
  private static void permission(ServerPlayer player){if(!player.mayBuild()||!player.hasPermissions(StreetServerConfig.PERMISSION.get()))throw new IllegalArgumentException("没有自动路网规划权限");}
  private static Task task(ServerPlayer player,UUID id){var task=StreetTaskStore.get(player.serverLevel()).tasks.get(id);if(task==null)throw new IllegalArgumentException("路网任务不存在或维度不同");RoadData.requireOwner(player,task.owner);return task;}
  public static void cancel(UUID owner,UUID id){var run=RUNNING.get(id);if(run!=null&&run.task.owner.equals(owner))run.cancel.set(true);}
  private static void enqueue(Run run){if(RUNNING.values().stream().anyMatch(r->r.task.owner.equals(run.task.owner)))throw new IllegalArgumentException("已有路网任务运行，请先取消或等待完成");RUNNING.put(run.task.id,run);QUEUE.addLast(run.task.id);}
  public static void action(ServerPlayer player,CompoundTag command){
    try{
      permission(player);String action=command.getString("Action");
      if(action.equals("streetOpen")){open(player);return;}
      if(action.equals("streetCapture")){beginCapture(player,StreetPlanCodec.read(command.getCompound("Input")),command.getLong("Request"));return;}
      var task=task(player,command.getUUID("Task"));
      switch(action){
        case "streetBuild"->beginBuild(player,task,StreetPlanCodec.read(command.getCompound("Input")));
        case "streetResume"->resume(player,task);
        case "streetUndo"->beginUndo(player,task);
        case "streetCancel"->{cancel(player.getUUID(),task.id);if(!RUNNING.containsKey(task.id)){task.state=State.CANCELLED;task.message="任务已取消；已完成部分保留，可撤销";StreetTaskStore.get(player.serverLevel()).changed(task);}}
        case "streetSnapshot"->{send(player,task,true,false);return;}
        case "streetArchive"->{if(RUNNING.containsKey(task.id))throw new IllegalArgumentException("请先停止任务");StreetTaskStore.get(player.serverLevel()).tasks.remove(task.id);StreetTaskStore.get(player.serverLevel()).setDirty();open(player);return;}
        default->throw new IllegalArgumentException("未知路网操作");
      }
      send(player,task,false,false);
    }catch(RuntimeException failure){var t=new CompoundTag();t.putString("Kind","streetUpdate");t.putLong("Request",command.getLong("Request"));t.putString("Error",failure.getMessage()==null?"路网操作失败，未继续施工":failure.getMessage());RoadNetwork.open(player,t);}
  }
  private static Input authoritative(ServerPlayer player,Input input){
    if(input.captureArea().size()>StreetServerConfig.MAX_CHUNKS.get())throw new IllegalArgumentException("选区超过服务器路网区块上限");
    if(!input.corridor().isEmpty()&&!input.corridorConfirmed())throw new IllegalArgumentException("请先单独确认标出的选区外连接走廊");
    var entries=new ArrayList<Entry>();var data=RoadData.get(player.serverLevel());
    for(var e:input.entries()){
      if(!e.existing()){entries.add(e);continue;}
      var authoritative=endpoint(data,player,e.reference());
      if(authoritative.point().distance(e.point())>.01||Math.abs(authoritative.height()-e.height())>.01)throw new IllegalArgumentException("已有入口已移动，请重新选择");entries.add(authoritative);
    }
    return new Input(input.selection(),input.protection(),input.centers(),entries,input.options(),input.corridor(),input.corridorConfirmed());
  }
  static Entry endpoint(RoadData data,ServerPlayer player,String reference){
    String[] parts=reference.split(":");if(parts.length!=2)throw new IllegalArgumentException("已有接口引用无效");var id=UUID.fromString(parts[0]);var road=data.editableRoad(id);boolean first=parts[1].equals("a");if(!first&&!parts[1].equals("b"))throw new IllegalArgumentException("已有接口引用无效");
    if(road==null||road.assembly()!=null||road.junction()!=null||road.settings().structure()==RoadGeometry.Structure.BRIDGE||road.settings().structure()==RoadGeometry.Structure.TUNNEL||RoadProfile.catalog(road.settings()).type()!=RoadProfile.Type.ORDINARY||!RoadProfile.catalog(road.settings()).twoWay()||road.settings().options().lanePoints().link()!=null)throw new IllegalArgumentException("首版仅支持普通双向地面道路的自由端点");
    if(player!=null)RoadData.requireOwner(player,road.owner());var pos=first?road.a():road.b();if(AutoJunctions.degree(data,pos,null)!=1||AutoJunctions.center(data,pos)!=null)throw new IllegalArgumentException("请选择已有普通道路的自由端点；首版不支持中段接入");
    var node=first?road.start():road.end();var direction=endpointDirection(road,first);
    if(Math.abs(node.grade())>.08)throw new IllegalArgumentException("已有接口坡度超过首版缓坡范围");
    return new Entry(new Point(node.position().x(),node.position().z()),node.position().y(),RoadPlanner.yaw(direction),road.settings().options().leftTraffic(),true,reference);
  }
  static RoadGeometry.V endpointDirection(RoadRecord road,boolean first){
    var points=road.mesh().samples();var sample=points.get(first?0:points.size()-1);
    return sample.left().left().mul(first?1:-1).horizontalUnit();
  }
  private static void beginCapture(ServerPlayer player,Input input,long request){
    input=authoritative(player,input);var level=player.serverLevel();var store=StreetTaskStore.get(level);
    if(RUNNING.values().stream().anyMatch(r->r.task.owner.equals(player.getUUID())))throw new IllegalArgumentException("请先取消当前读取或施工");
    var replace=store.tasks.values().stream().filter(t->t.owner.equals(player.getUUID())&&t.progress==0&&t.journals.isEmpty()&&t.state!=State.BUILDING).map(t->t.id).toList();replace.forEach(store.tasks::remove);
    if(store.tasks.size()>=StreetServerConfig.MAX_TASKS.get())throw new IllegalArgumentException("任务记录达到服务器上限，请先明确归档不再需要撤销的旧任务");
    var task=new Task(UUID.randomUUID(),player.getUUID(),input);for(var e:input.entries())if(e.existing()){var id=UUID.fromString(e.reference().split(":")[0]);task.dependencies.put(id,RoadData.get(level).editableRoad(id).save());}
    var run=new Run(level,task);run.capture=new StreetTerrainCapture(level,input.captureArea());store.tasks.put(task.id,task);store.changed(task);enqueue(run);send(player,task,false,false,request);
  }
  private static void beginBuild(ServerPlayer player,Task task,Input proposed){
    if(task.progress!=0||!task.journals.isEmpty())throw new IllegalArgumentException("此任务已有施工记录，请继续任务或新建草案");
    if(task.state!=State.READY)throw new IllegalArgumentException("请先完成地形读取");proposed=authoritative(player,proposed);
    if(!task.terrain.complete(proposed)||!task.input.captureArea().equals(proposed.captureArea()))throw new IllegalArgumentException("选区已改变，请重新读取地形");
    if(!task.input.entries().equals(proposed.entries()))throw new IllegalArgumentException("接口已改变，请重新读取地形");task.input=proposed;checkDependencies(player.serverLevel(),task);
    task.state=State.BUILDING;task.message="正在核对原生道路与路口几何";var run=new Run(player.serverLevel(),task);run.needsPlan=true;enqueue(run);StreetTaskStore.get(player.serverLevel()).changed(task);
  }
  private static void preparePlan(Run run){
    var task=run.task;var graph=StreetLayouts.generate(task.input,planningTerrain(task));if(!graph.buildable())throw new IllegalArgumentException(graph.issues().stream().filter(Issue::fatal).map(Issue::message).findFirst().orElse("没有可建设道路"));
    if(graph.edges().size()>StreetServerConfig.MAX_ROADS.get()||graph.junctions()>StreetServerConfig.MAX_JUNCTIONS.get())throw new IllegalArgumentException("道路或路口数量超过服务器规划上限");
    var draft=StreetNetworkGeometry.plan(graph,task.id,task.owner);if(draft.centers().size()>StreetServerConfig.MAX_JUNCTIONS.get())throw new IllegalArgumentException("完整转角与路口数量超过服务器上限");task.roads=StreetNetworkGeometry.connectedOrder(draft.streets(),task.input.entries().stream().filter(Entry::existing).map(e->BlockPos.containing(e.point().x(),e.height(),e.point().z())).toList());task.centers=draft.centers();run.needsPlan=false;task.message="正在按相连道路和完整路口分批施工";
  }
  static Terrain planningTerrain(Task task){return task.terrain.forPlan(task.input);}
  private static void resume(ServerPlayer player,Task task){
    if(RUNNING.containsKey(task.id))throw new IllegalArgumentException("任务已经运行");
    if(task.undoIntent){task.state=State.UNDOING;task.message="继续尚未完成的条件撤销";enqueue(new Run(player.serverLevel(),task));StreetTaskStore.get(player.serverLevel()).changed(task);return;}
    if(task.roads.isEmpty()){beginCapture(player,task.input,0);return;}
    if(task.state==State.UNDONE||task.state==State.COMPLETE||task.state==State.CONFLICT)throw new IllegalArgumentException("此任务不能继续；请新建草案");
    verifyCompleted(player.serverLevel(),task);int previous=task.roads.size();task.roads=StreetNetworkGeometry.prunePendingSpurs(task.roads,task.progress);
    task.state=State.BUILDING;task.message=previous==task.roads.size()?"已恢复，继续剩余施工单元":"已移除 "+(previous-task.roads.size())+" 条无法接通的边界短支路，继续剩余施工";enqueue(new Run(player.serverLevel(),task));StreetTaskStore.get(player.serverLevel()).changed(task);
  }
  private static void beginUndo(ServerPlayer player,Task task){
    if(RUNNING.containsKey(task.id))throw new IllegalArgumentException("请先取消正在运行的任务");if(task.journals.isEmpty())throw new IllegalArgumentException("此任务没有可撤销的施工");task.state=State.UNDOING;task.undoIntent=true;task.undoCursor=task.journals.size()-1;task.conflicts=0;task.message="正在逐批校验并撤销；保留后续玩家改动";enqueue(new Run(player.serverLevel(),task));StreetTaskStore.get(player.serverLevel()).changed(task);
  }
  private static void checkDependencies(ServerLevel level,Task task){var data=RoadData.get(level);for(var e:task.dependencies.entrySet()){var actual=data.editableRoad(e.getKey());if(actual==null||!actual.save().equals(e.getValue()))throw new IllegalArgumentException("所选已有道路在预览后已改变，请重新规划");}}
  private static void verifyCompleted(ServerLevel level,Task task){
    var data=RoadData.get(level);if(task.verifiedRevision==data.index.contentRevision())return;
    var latest=new HashMap<UUID,CompoundTag>();var absent=new HashSet<UUID>();for(var j:task.journals){for(var id:j.roads){latest.remove(id);absent.add(id);}for(Tag v:j.newObjects.getList("Roads",Tag.TAG_COMPOUND)){var t=(CompoundTag)v;var r=RoadRecord.load(t);latest.put(r.id(),t);absent.remove(r.id());}}
    for(var e:latest.entrySet()){var r=data.index.roads.get(e.getKey());if(r==null||!r.record.save().equals(e.getValue()))throw new IllegalArgumentException("已施工道路被后续编辑，任务已停止；可检查后撤销或另建草案");}for(var id:absent)if(data.index.roads.containsKey(id))throw new IllegalArgumentException("道路记录已被替换，任务已停止");task.verifiedRevision=data.index.contentRevision();
  }
  public static void tick(MinecraftServer server){
    if(QUEUE.isEmpty())return;var id=QUEUE.removeFirst();var run=RUNNING.get(id);if(run==null)return;var task=run.task;var store=StreetTaskStore.get(run.level);var player=server.getPlayerList().getPlayer(task.owner);
    try{
      if(run.cancel.get()){task.state=State.CANCELLED;task.message="任务已取消；已完成部分保留，可撤销";}
      else if(player==null||player.serverLevel()!=run.level){task.state=State.PAUSED;task.message="玩家离线或离开维度，任务已暂停";}
      else if(server.getTickCount()<run.nextTick){QUEUE.addLast(id);return;}
      else {permission(player);
        if(task.state==State.READING){boolean done=run.capture.step();task.readChunks=run.capture.completed();if(done){task.terrain=run.capture.snapshot();task.state=State.READY;task.message="地形已读取，可调整草案并确认施工";send(player,task,true,false);}}
        else if(task.state==State.BUILDING){buildOne(run,player);run.nextTick=server.getTickCount()+StreetServerConfig.UNIT_INTERVAL.get();}
        else if(task.state==State.UNDOING){undoOne(run,player);run.nextTick=server.getTickCount()+StreetServerConfig.UNIT_INTERVAL.get();}
      }
    }catch(RuntimeException failure){task.state=run.cancel.get()?State.CANCELLED:State.FAILED;task.message=failure.getMessage()==null?"本批施工失败，已停止后续道路":failure.getMessage();}
    store.changed(task);
    boolean active=task.state==State.READING||task.state==State.BUILDING||task.state==State.UNDOING;
    if(active)QUEUE.addLast(id);else {RoadData.forgetPreview(task.owner,run.token);RUNNING.remove(id);}
    if(player!=null&&(task.state!=State.READING||server.getTickCount()%10==0))send(player,task,false,false);
  }
  private static void buildOne(Run run,ServerPlayer player){
    var task=run.task;var level=run.level;if(run.needsPlan){try(var budget=RoadPlanningBudget.open("路网原生几何",StreetServerConfig.PREPARE_MILLIS.get()/1000d,run.cancel::get)){preparePlan(run);}return;}if(task.progress>=task.roads.size()){task.state=State.COMPLETE;task.message="路网施工完成；道路与路口可用原工具编辑";return;}
    verifyCompleted(level,task);checkDependencies(level,task);var road=task.roads.get(task.progress);long started=System.nanoTime();
    try(var budget=RoadPlanningBudget.open("路网单元预验证",StreetServerConfig.PREPARE_MILLIS.get()/1000d,run.cancel::get);var chunks=RoadWorkChunks.open(level)){
      chunks.node(road.a());chunks.node(road.b());
      if(run.prepared==null){
        var change=AutoJunctions.streetAddition(level,player,road,task.centers);run.token=UUID.randomUUID();
        AutoJunctions.prepareStreet(level,player,change,run.token);run.prepared=change;
        long elapsed=(System.nanoTime()-started)/1000;task.totalUnitMicros+=elapsed;task.peakUnitMicros=Math.max(task.peakUnitMicros,elapsed);task.message="已准备第 "+(task.progress+1)+" 批，下批重新核对地形后提交";return;
      }
      try(var scope=new StreetEditScope(level,task.input,task.terrain,task.fingerprints,run.prepared.centers())){
        scope.remainingCells=StreetServerConfig.MAX_TASK_CELLS.get()-task.cells();
        AutoJunctions.commitPreparedStreet(level,player,run.prepared,run.token);task.journals.add(scope.finish());run.prepared=null;run.token=null;task.progress++;task.verifiedRevision=RoadData.get(level).index.contentRevision();
      }
      for(var id:task.dependencies.keySet()){var actual=RoadData.get(level).editableRoad(id);if(actual!=null)task.dependencies.put(id,actual.save());}
    }
    long elapsed=(System.nanoTime()-started)/1000;task.totalUnitMicros+=elapsed;task.peakUnitMicros=Math.max(task.peakUnitMicros,elapsed);task.message="已完成 "+task.progress+" / "+task.roads.size()+" 个相连道路单元";
  }
  private static void undoOne(Run run,ServerPlayer player){
    var task=run.task;if(task.undoCursor<0){task.undoIntent=false;task.state=task.journals.isEmpty()?State.UNDONE:State.CONFLICT;task.message=task.journals.isEmpty()?"撤销完成；保留的玩家改动："+task.conflicts:"已保留发生冲突的施工单元："+task.journals.size()+"；冲突数："+task.conflicts;return;}
    var result=task.journals.get(task.undoCursor).undo(run.level,player);task.conflicts+=result.conflicts();if(result.restored())task.journals.remove(task.undoCursor);task.undoCursor--;task.message="正在撤销；剩余检查 "+(task.undoCursor+1)+" 批，冲突 "+task.conflicts;
  }
  public static void stop(){for(var run:RUNNING.values()){RoadData.forgetPreview(run.task.owner,run.token);run.task.state=State.PAUSED;run.task.message="服务器停止，任务暂停；已完成部分与撤销日志已保留";StreetTaskStore.get(run.level).changed(run.task);}RUNNING.clear();QUEUE.clear();}
  private static void open(ServerPlayer player){
    var tasks=StreetTaskStore.get(player.serverLevel()).tasks.values().stream().filter(t->t.owner.equals(player.getUUID())).sorted(Comparator.comparingLong((Task t)->t.updated).reversed()).toList();
    if(tasks.isEmpty()){var t=new CompoundTag();t.putString("Kind","streetOpen");t.putBoolean("DefaultLeft",StreetServerConfig.DEFAULT_LEFT.get());t.putInt("MaxChunks",StreetServerConfig.MAX_CHUNKS.get());RoadNetwork.open(player,t);}else send(player,tasks.get(0),true,true);
  }
  private static void send(ServerPlayer player,Task task,boolean terrain,boolean open){send(player,task,terrain,open,0);}
  private static void send(ServerPlayer player,Task task,boolean terrain,boolean open,long request){
    var t=new CompoundTag();t.putString("Kind",open?"streetOpen":"streetUpdate");t.putUUID("Task",task.id);t.putString("State",task.state.name());t.putString("Message",task.message);t.putInt("Progress",task.progress);t.putInt("Total",task.roads.size());t.putInt("Read",task.readChunks);t.putInt("ReadTotal",task.input.captureArea().size());t.putInt("Conflicts",task.conflicts);t.putInt("JournalCells",task.cells());t.putLong("PeakUnitMicros",task.peakUnitMicros);t.putLong("Request",request);t.putInt("MaxChunks",StreetServerConfig.MAX_CHUNKS.get());
    if(terrain){t.put("Input",StreetPlanCodec.write(task.input));t.put("Terrain",StreetPlanCodec.write(task.terrain));}
    if(terrain||open){var tasks=new ListTag();for(var item:StreetTaskStore.get(player.serverLevel()).tasks.values())if(item.owner.equals(player.getUUID())){var v=new CompoundTag();v.putUUID("Id",item.id);v.putString("State",item.state.name());v.putString("Style",item.input.options().style().label);v.putInt("Progress",item.progress);tasks.add(v);}t.put("Tasks",tasks);}
    if(terrain){var entries=new ListTag();var data=RoadData.get(player.serverLevel());for(var r:AutoJunctions.logical(data).values())for(String end:List.of("a","b"))try{var pos=end.equals("a")?r.a():r.b();if(task.input.captureArea().stream().noneMatch(c->Math.abs(c.x()-Math.floorDiv(pos.getX(),16))<=2&&Math.abs(c.z()-Math.floorDiv(pos.getZ(),16))<=2))continue;var e=endpoint(data,player,r.id()+":"+end);if(task.input.captureArea().stream().anyMatch(c->Math.abs(c.x()-Cell.at(e.point().x(),e.point().z()).x())<=2&&Math.abs(c.z()-Cell.at(e.point().x(),e.point().z()).z())<=2)){var v=new CompoundTag();v.putDouble("X",e.point().x());v.putDouble("Y",e.height());v.putDouble("Z",e.point().z());v.putDouble("Yaw",e.yaw());v.putBoolean("Left",e.left());v.putString("Reference",e.reference());entries.add(v);}}catch(IllegalArgumentException ignored){}t.put("Endpoints",entries);}
    RoadNetwork.open(player,t);
  }
  private StreetTasks(){}
}
