package com.sora.splineroads.net;
import com.sora.splineroads.world.*;
import com.sora.splineroads.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** Server-thread snapshot and publish, pure geometry between them. No blocking join/get. */
public final class RoadPlanningJobs {
  private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"SR-route-planner");t.setDaemon(true);t.setPriority(Thread.NORM_PRIORITY-1);return t;},new ThreadPoolExecutor.AbortPolicy());
  private static final Map<UUID,Job> ACTIVE=new ConcurrentHashMap<>(); // packet thread may cancel only its authenticated owner
  static {WORKER.allowCoreThreadTimeOut(true);}
  private static final class Job {
    final long request;final AtomicBoolean cancelled=new AtomicBoolean();volatile FutureTask<Void> task;
    Job(long request){this.request=request;}
    void stop(){cancelled.set(true);if(task!=null)task.cancel(true);WORKER.purge();}
  }
  public static void cancel(UUID player){var old=ACTIVE.remove(player);if(old!=null)old.stop();}
  public static void cancel(UUID player,long request){var old=ACTIVE.get(player);if(old!=null&&old.request==request&&ACTIVE.remove(player,old))old.stop();}
  public static int activeCount(){return ACTIVE.size();}
  public static void stop(){for(UUID id:List.copyOf(ACTIVE.keySet()))cancel(id);WORKER.getQueue().clear();}
  private static final java.util.concurrent.atomic.LongAdder prepareNanos=new java.util.concurrent.atomic.LongAdder(),workerNanos=new java.util.concurrent.atomic.LongAdder(),finishNanos=new java.util.concurrent.atomic.LongAdder();
  public static String metrics(){return "prepareMs="+(prepareNanos.sum()/1000000)+" workerMs="+(workerNanos.sum()/1000000)+" finishMs="+(finishNanos.sum()/1000000)+" "+LaneRamps.previewMetrics();}
  private static void recordPhase(String phase,long started,java.util.concurrent.atomic.LongAdder total){
    long elapsed=System.nanoTime()-started;total.add(elapsed);
    if(elapsed>=100_000_000L||Boolean.getBoolean("sr.profile"))System.getLogger("SplineRoads/planner").log(System.Logger.Level.INFO,
        String.format(Locale.ROOT,"SR preview %s_ms=%.3f active=%d",phase,elapsed/1e6,ACTIVE.size()));
  }
  public static void begin(ServerPlayer player,ItemStack tool,CompoundTag command){
    UUID owner=player.getUUID();cancel(owner);var job=new Job(command.getLong("Request"));ACTIVE.put(owner,job);
    long prepared=System.nanoTime();LaneRamps.PreviewWork work;
    // Register before preparation: a packet-thread cancellation must also stop
    // a long server-thread snapshot, rather than wait behind it in the mailbox.
    try(var budget=RoadPlanningBudget.cancellable("预览快照准备",job.cancelled::get)){
      work=LaneRamps.preparePreview(player,tool,command);RoadPlanningBudget.check();
    }catch(RuntimeException e){ACTIVE.remove(owner,job);if(job.cancelled.get())return;throw e;}
    finally{recordPhase("prepare",prepared,prepareNanos);}
    var server=player.getServer();
    if(server==null){cancel(owner);throw new IllegalArgumentException("服务器已关闭");}
    if(job.cancelled.get()||ACTIVE.get(owner)!=job)return;
    var dimension=player.level().dimension().location().toString();var t=command.copy();
    submit(player,tool,t,work,job,dimension,0,null);
  }
  private static void submit(ServerPlayer player,ItemStack tool,CompoundTag t,LaneRamps.PreviewWork work,Job job,String dimension,int attempt,LaneRamps.CandidateRejected rejection){
    var server=player.getServer();UUID owner=player.getUUID();
    job.task=new FutureTask<>(()->{
      LaneRamps.PreviewRoute route=null;RuntimeException error=null;long computed=System.nanoTime();
      try(var budget=RoadPlanningBudget.cancellable("匝道路线/净空搜索",job.cancelled::get)){route=work.compute(attempt);}
      catch(RuntimeException e){error=e;}
      finally{recordPhase("worker",computed,workerNanos);RoadQueries.clearThreadCache();}
      var result=route;var failure=error;
      server.execute(()->{
        if(ACTIVE.get(owner)!=job||job.cancelled.get())return;
        if(server.getPlayerList().getPlayer(owner)!=player){cancel(owner);return;}
        try {
          if(!player.level().dimension().location().toString().equals(dimension)||player.getMainHandItem()!=tool&&player.getOffhandItem()!=tool&&!ManualPlanner.ownsTool(player,tool))
            throw new IllegalArgumentException("维度或所持工具已改变，旧计算已丢弃");
          if(failure!=null){
            if(rejection!=null&&failure instanceof IllegalArgumentException){
              if(work.alternative(attempt+1)){submit(player,tool,t,work,job,dimension,attempt+1,rejection);return;}
              throw rejection;
            }
            throw failure;
          }
          long finished=System.nanoTime();
          try(var budget=RoadPlanningBudget.cancellable("预览事务校验",job.cancelled::get)){
            CompoundTag reply;
            try{reply=LaneRamps.finishPreview(player,tool,t,work,result);}
            catch(IllegalArgumentException e){
              var rejected=LaneRamps.candidateRejection(e);
              if(work.alternative(attempt+1)){submit(player,tool,t,work,job,dimension,attempt+1,rejected);return;}
              throw rejected;
            }
            RoadPlanningBudget.check();ACTIVE.remove(owner,job);if(!job.cancelled.get())RoadNetwork.open(player,reply);
          }finally{recordPhase("finish",finished,finishNanos);}
        }catch(RuntimeException e){ACTIVE.remove(owner,job);if(!job.cancelled.get())failed(player,t,e);}
        finally{
          if(job.cancelled.get()&&tool.getOrCreateTag().getCompound("LanePreview").getLong("Request")==job.request)
            tool.getOrCreateTag().remove("LanePreview");
        }
      });return null;
    });
    try{
      if(job.cancelled.get()||ACTIVE.get(owner)!=job){job.task.cancel(true);return;}
      WORKER.execute(job.task);

    }catch(RejectedExecutionException e){ACTIVE.remove(owner);job.stop();throw new IllegalArgumentException("匝道规划队列已满，请稍后重试（未写入道路）");}
  }
  private static void failed(ServerPlayer p,CompoundTag t,RuntimeException e){
    System.getLogger("SplineRoads/planner").log(e instanceof RoadPlanningBudget.Aborted?System.Logger.Level.WARNING:System.Logger.Level.INFO,"Ramp preview: "+e.getMessage());
    if(!(e instanceof IllegalArgumentException)&&!(e instanceof RoadPlanningBudget.Aborted))System.getLogger("SplineRoads/planner").log(System.Logger.Level.ERROR,"Preview error",e);
    var reply=new CompoundTag();reply.putString("Kind","laneRampCheck");reply.putLong("Request",t.getLong("Request"));
    reply.putString("Dimension",p.level().dimension().location().toString());reply.putString("Error",e.getMessage()==null?"匝道预览失败，详见 latest.log":e.getMessage());LaneRamps.conflictRoads(reply,p,reply.getString("Error"));RoadNetwork.open(p,reply);
  }
  private RoadPlanningJobs(){}
}
