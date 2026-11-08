package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.compat.mtr.MtrRailAccess;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.Structure;
import com.sora.splineroads.net.RoadNetwork;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=SplineRoads.ID)
public final class RailbedTool extends Item {
  static final class Draft {
    final UUID token=UUID.randomUUID();final String dimension;final BlockPos a;BlockPos b;
    final java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
    volatile List<? extends CompletableFuture<?>> reads=List.of();
    void cancel(){cancelled.set(true);reads.forEach(f->f.cancel(false));}
    void reads(List<? extends CompletableFuture<?>> tasks){reads=List.copyOf(tasks);if(cancelled.get())reads.forEach(f->f.cancel(false));}
    RailbedBuildQueue.Preview previewTask;String previewKey="";CompoundTag previewResponse;long previewComputations;
    final List<RailbedPath.Path> paths=new ArrayList<>();List<RailbedPath.Path> candidates=List.of();CompletableFuture<?> pending,preview;String sourceStatus="";List<RailbedPath.Path> surrounding=List.of();double width;Structure mode=Structure.AUTO;RailbedOptions options=RailbedOptions.DEFAULT;UUID saved,plannerSession;
    Draft(ServerPlayer player,BlockPos a){dimension=dimension(player);this.a=a.immutable();}
  }
  private static final Map<UUID,Draft> drafts=new java.util.concurrent.ConcurrentHashMap<>();
  /** Network thread may touch only the authenticated owner's atomic flag. */
  public static void cancelSignal(UUID owner,UUID token){var d=drafts.get(owner);if(d!=null&&d.token.equals(token))d.cancel();}
  public RailbedTool(){super(new Properties().stacksTo(1));}
  private static String dimension(ServerPlayer player){var id=player.level().dimension().location();return id.getNamespace()+"/"+id.getPath();}
  private static boolean held(ServerPlayer player){return player.getMainHandItem().getItem() instanceof RailbedTool||player.getOffhandItem().getItem() instanceof RailbedTool;}
  static Draft draft(ServerPlayer player){var d=drafts.get(player.getUUID());if(d==null||!d.dimension.equals(dimension(player)))throw new IllegalArgumentException("铁路选择已失效，请重新选点");return d;}
  private static void require(ServerPlayer player,CompoundTag tag){if(tag.hasUUID("PlannerSession")){if(!(ManualPlanner.tool(player,tag).getItem() instanceof RailbedTool))throw new IllegalArgumentException("请从规划地图重新打开铁路路基");}else require(player);}
  private static void publish(ServerPlayer player,Draft d,CompoundTag tag){if(d.plannerSession!=null)tag.putUUID("PlannerSession",d.plannerSession);RoadNetwork.open(player,tag);}
  private static void require(ServerPlayer player){if(!held(player)||!player.mayBuild()||player.isSpectator())throw new IllegalArgumentException("请手持铁路路基连接器并确保拥有建设权限");}
  @Override public InteractionResult useOn(UseOnContext context){
    if(context.getLevel().isClientSide)return InteractionResult.SUCCESS;if(!(context.getPlayer() instanceof ServerPlayer player))return InteractionResult.PASS;
    try{require(player);if(player.isShiftKeyDown()){clear(player);return InteractionResult.CONSUME;}
      String problem=MtrRailAccess.unavailable();if(problem!=null)throw new IllegalArgumentException(problem);
      BlockPos pos=context.getClickedPos();if(!player.serverLevel().mayInteract(player,pos))throw new IllegalArgumentException("铁路节点位于受保护区域");
      if(!MtrRailAccess.trainNode(context.getLevel().getBlockState(pos).getBlock())){if(!drafts.containsKey(player.getUUID())&&edit(player))return InteractionResult.CONSUME;var existing=draft(player);if(!toggle(player,existing))resume(player);return InteractionResult.CONSUME;}
      var d=drafts.get(player.getUUID());if(d==null||!d.dimension.equals(dimension(player))){d=new Draft(player,pos);drafts.put(player.getUUID(),d);message(player,"已选铁路节点 A，请右键同一路径的节点 B");}
      else if(d.paths.isEmpty()){if(d.pending!=null&&!d.pending.isDone())throw new IllegalArgumentException("正在读取轨道，请稍候");d.b=pos.immutable();read(player,d);}
      else if(!toggle(player,d))resume(player);
    }catch(RuntimeException failure){message(player,failure.getMessage());}return InteractionResult.CONSUME;
  }
  @Override public InteractionResultHolder<ItemStack> use(Level level,Player actor,InteractionHand hand){if(actor instanceof ServerPlayer player)try{require(player);if(player.isShiftKeyDown())clear(player);else if(!drafts.containsKey(player.getUUID())&&edit(player)){}else{var d=draft(player);if(!toggle(player,d))resume(player);}}catch(RuntimeException failure){message(player,failure.getMessage());}return InteractionResultHolder.sidedSuccess(actor.getItemInHand(hand),level.isClientSide);}
  private static void read(ServerPlayer player,Draft d){message(player,"正在读取 MTR 实际轨迹…");
    var future=MtrRailAccess.read(d.dimension,RailbedCodec.node(d.a),RailbedCodec.node(d.b));d.pending=future;d.reads(List.of(future));
    future.whenComplete((path,error)->player.server.execute(()->{
      if(!valid(player,d))return;d.pending=null;
      if(error!=null){d.b=null;message(player,reason(error));return;}d.paths.clear();d.paths.add(path);neighbors(player,d,true);
    }));
  }
  private static String reason(Throwable e){while(e.getCause()!=null)e=e.getCause();return e.getMessage()==null?"铁路读取失败："+e.getClass().getSimpleName():e.getMessage();}
  private static void resume(ServerPlayer player){open(player,draft(player));}
  private static void neighbors(ServerPlayer player,Draft d,boolean show){var future=MtrRailAccess.nearby(d.dimension,d.paths.get(0));d.pending=future;d.reads(List.of(future));future.whenComplete((near,error)->player.server.execute(()->{if(!valid(player,d))return;d.pending=null;if(error!=null)message(player,reason(error));else{d.surrounding=near;d.candidates=RailbedCandidates.find(d.paths.get(0),near);if(d.saved==null&&d.paths.size()==1&&d.previewKey.isEmpty()){var selected=RailbedCandidates.automatic(d.paths.get(0),d.candidates);d.paths.clear();d.paths.addAll(selected);}}open(player,d,show);}));}
  static void open(ServerPlayer player,Draft d){open(player,d,true);}
  static void open(ServerPlayer player,Draft d,boolean show){if(d.paths.isEmpty()){message(player,"请继续选择铁路节点 B");return;}preview(player,d);var t=new CompoundTag();t.putString("Kind",show?"railbed":"railbedPreview");t.putUUID("Token",d.token);t.putString("Dimension",d.dimension);t.putString("SourceStatus",d.sourceStatus);t.putDouble("Width",d.width);t.putString("Mode",d.mode.name());t.put("Options",RailbedCodec.options(d.options));
    var paths=new ListTag();d.paths.forEach(path->paths.add(RailbedCodec.write(path)));t.put("Paths",paths);var candidates=new ListTag();d.candidates.forEach(path->candidates.add(RailbedCodec.write(path)));t.put("Candidates",candidates);t.putString("Selection",selection(d));if(d.previewResponse!=null)t.put("CachedPreview",d.previewResponse.copy());if(d.saved!=null)t.putUUID("Id",d.saved);publish(player,d,t);}
  private static boolean valid(ServerPlayer player,Draft d){return !d.cancelled.get()&&drafts.get(player.getUUID())==d&&!player.isRemoved()&&d.dimension.equals(dimension(player))&&(d.plannerSession==null||ManualPlanner.active(player,d.plannerSession));}
  private static String selection(Draft d){return d.paths.stream().map(RailbedPath.Path::fingerprint).toList().toString();}
  private static void preview(ServerPlayer player,Draft d){
    if(d.cancelled.get()||d.sourceStatus.startsWith("正在核验"))return;
    String key=selection(d)+":"+d.width+":"+d.mode+":"+d.options+":"+d.saved+":"+d.surrounding.stream().map(RailbedPath.Path::fingerprint).sorted().toList();
    if(d.previewTask!=null&&key.equals(d.previewKey)&&(!d.previewTask.future().isDone()||d.previewTask.valid()))return;
    if(d.preview!=null)d.preview.cancel(false);double width=d.width;var mode=d.mode;var options=d.options;String selection=selection(d);
    d.previewResponse=null;d.previewKey=key;
    try{d.previewTask=RailbedBuildQueue.previewTask(player.serverLevel(),player,d.saved==null?d.token:d.saved,List.copyOf(d.paths),width,mode,d.surrounding,options,d.cancelled::get);var future=d.previewTask.future();d.preview=future;d.previewComputations++;
      future.whenComplete((entry,error)->player.server.execute(()->{if(!valid(player,d)||d.preview!=future||future.isCancelled())return;
        var tag=new CompoundTag();tag.putString("Kind","railbedStructurePreview");tag.putUUID("Token",d.token);tag.putDouble("Width",width);tag.putString("Mode",mode.name());tag.put("Options",RailbedCodec.options(options));tag.putString("Selection",selection);
        if(error==null)tag.put("Road",entry.road.save());else tag.putString("Problem",reason(error));d.previewResponse=tag;publish(player,d,tag);
      }));
    }catch(RuntimeException e){d.previewTask=null;var tag=new CompoundTag();tag.putString("Kind","railbedStructurePreview");tag.putUUID("Token",d.token);tag.putDouble("Width",width);tag.putString("Mode",mode.name());tag.put("Options",RailbedCodec.options(options));tag.putString("Selection",selection);tag.putString("Problem",reason(e));d.previewResponse=tag;publish(player,d,tag);}
  }
  private static void sourceStatus(ServerPlayer player,Draft d){
    String unavailable=MtrRailAccess.unavailable();if(unavailable!=null){d.sourceStatus="源不可用；仍可删除旧路基";open(player,d);return;}
    var reads=d.paths.stream().map(p->MtrRailAccess.read(d.dimension,p.segments().get(0).from(),p.segments().get(p.segments().size()-1).to())).toList();
    var all=CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new));d.pending=all;d.reads(reads);
    all.whenComplete((v,error)->player.server.execute(()->{if(!valid(player,d))return;d.pending=null;
      if(error!=null){d.sourceStatus="源失效："+reason(error)+"；旧路基已保留";open(player,d);return;}
      try{var fresh=new ArrayList<RailbedPath.Path>();fresh.add(reads.get(0).join());for(int i=1;i<reads.size();i++)fresh.add(RailbedCandidates.clip(fresh.get(0),reads.get(i).join().segments()));
        boolean changed=false;for(int i=0;i<fresh.size();i++)changed|=!fresh.get(i).fingerprint().equals(d.paths.get(i).fingerprint());
        d.sourceStatus=changed?"待更新：轨道已改变，保存时需确认新预览":"源轨道有效";neighbors(player,d,true);
      }catch(RuntimeException e){d.sourceStatus="源失效："+reason(e);open(player,d);}
    }));
  }
  private static boolean edit(ServerPlayer player){var picked=RoadTool.pick(player);if(picked==null||!picked.record.railway())return false;openExisting(player,picked.record.id(),null);return true;}
  static void openExisting(ServerPlayer player,UUID id,UUID session){var entry=RoadData.get(player.serverLevel()).railbeds.entries.get(id);if(entry==null)throw new IllegalArgumentException("铁路结构正在恢复，请稍候");RoadData.requireOwner(player,entry.road.owner());var d=new Draft(player,entry.road.a());d.b=entry.road.b();d.paths.addAll(entry.paths);d.saved=entry.road.id();d.width=entry.width;d.mode=entry.mode;d.options=entry.options;d.plannerSession=session;var previous=drafts.put(player.getUUID(),d);if(previous!=null){previous.cancel();if(previous.preview!=null)previous.preview.cancel(false);}d.sourceStatus="正在核验绑定轨道…";open(player,d);sourceStatus(player,d);}
  private static boolean toggle(ServerPlayer player,Draft d){if(d.paths.isEmpty())return false;var eye=player.getEyePosition();var look=player.getLookAngle();var origin=new com.sora.splineroads.core.RoadGeometry.V(eye.x,eye.y,eye.z);var direction=new com.sora.splineroads.core.RoadGeometry.V(look.x,look.y,look.z);RailbedPath.Path selected=null;double nearest=97;
    for(var candidate:d.candidates){var points=candidate.positions();double hit=Double.POSITIVE_INFINITY;for(int i=1;i<points.size();i++){
      var a=points.get(i-1).add(new com.sora.splineroads.core.RoadGeometry.V(0,.2,0));var delta=points.get(i).sub(points.get(i-1));var w=a.sub(origin);double dd=delta.dot(delta),vd=direction.dot(delta),den=dd-vd*vd;double t=den<1e-9?0:Math.max(0,Math.min(1,(vd*w.dot(direction)-w.dot(delta))/den));var p=a.add(delta.mul(t));double distance=p.sub(origin).dot(direction);if(distance>0&&distance<96&&p.distance(origin.add(direction.mul(distance)))<.65)hit=Math.min(hit,distance);
    }if(hit<nearest){if(selected!=null&&Math.abs(hit-nearest)<.75)throw new IllegalArgumentException("候选轨道重叠，请靠近后选择");selected=candidate;nearest=hit;}}
    if(selected==null)return false;String key=RailbedCandidates.key(selected);boolean removed=d.paths.removeIf(p->p!=d.paths.get(0)&&RailbedCandidates.key(p).equals(key));if(!removed){if(d.paths.size()>=8)throw new IllegalArgumentException("最多选择 8 股轨道");var proposed=new ArrayList<>(d.paths);proposed.add(selected);RailbedGeometry.plan(proposed,0,d.mode);d.paths.add(selected);}open(player,d,false);message(player,"已选 "+d.paths.size()+" 股轨道；右键空处返回设置");return true;
  }
  public static void action(ServerPlayer player,CompoundTag tag){try{var d=draft(player);if(tag.getString("Action").equals("railbedCancel")&&tag.hasUUID("Token")&&d.token.equals(tag.getUUID("Token"))){clear(player);return;}require(player,tag);if(d.cancelled.get())return;if(!Objects.equals(d.plannerSession,tag.hasUUID("PlannerSession")?tag.getUUID("PlannerSession"):null))throw new IllegalArgumentException("铁路规划会话已变化，请重新打开");if(!d.token.equals(tag.getUUID("Token")))throw new IllegalArgumentException("铁路草稿已变化，请重新打开");
    String action=tag.getString("Action");if(action.equals("railbedCancel")){clear(player);return;}
    if(d.pending!=null&&!d.pending.isDone())throw new IllegalArgumentException("正在读取或施工，请稍候");
    if(action.equals("railbedDelete")){if(d.saved==null)throw new IllegalArgumentException("当前尚未建造");d.pending=RailbedRoads.submit(player.serverLevel(),player,d.saved,null,0,d.mode,d.options,List.of(),planner(d));d.pending.whenComplete((v,e)->player.server.execute(()->{if(e==null&&drafts.get(player.getUUID())==d)clear(player);}));return;}
    double width=tag.getDouble("Width");if(!Double.isFinite(width)||width<0||width>64)throw new IllegalArgumentException("路基总宽无效");var mode=Structure.valueOf(tag.getString("Mode"));if(mode==Structure.GROUND)throw new IllegalArgumentException("铁路模式无效");
    RailbedGeometry.plan(d.paths,width,mode);d.width=width;d.mode=mode;d.options=RailbedCodec.options(tag.getCompound("Options"));
    if(action.equals("railbedSettings")){preview(player,d);if(d.previewResponse!=null)publish(player,d,d.previewResponse.copy());return;}
    if(!action.equals("railbedBuild"))throw new IllegalArgumentException("未知铁路操作");build(player,d);
  }catch(RuntimeException failure){message(player,failure.getMessage());}}
  private static CompoundTag planner(Draft d){if(d.plannerSession==null)return null;var t=new CompoundTag();t.putUUID("PlannerSession",d.plannerSession);return t;}
  private static void build(ServerPlayer player,Draft d){String unsupported=RailbedGeometry.unsupported(d.paths);if(unsupported!=null)throw new IllegalArgumentException(unsupported);message(player,"正在重新校验 MTR 源轨道…");
    long sourceRevision=MtrRailAccess.revision(d.dimension);
    var reads=d.paths.stream().map(p->{var s=p.segments();return MtrRailAccess.read(d.dimension,s.get(0).from(),s.get(s.size()-1).to());}).toList();var all=CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new));d.pending=all;d.reads(reads);
    all.whenComplete((ignored,error)->player.server.execute(()->{if(!valid(player,d))return;if(error!=null){message(player,reason(error));return;}
      try{var fresh=new ArrayList<RailbedPath.Path>();fresh.add(reads.get(0).join());for(int i=1;i<reads.size();i++)fresh.add(RailbedCandidates.clip(fresh.get(0),reads.get(i).join().segments()));
        for(int i=0;i<fresh.size();i++)if(!fresh.get(i).fingerprint().equals(d.paths.get(i).fingerprint())){d.paths.clear();d.paths.addAll(fresh);message(player,"轨道已变化，请重新确认建造");neighbors(player,d,true);return;}
        var nearby=MtrRailAccess.nearby(d.dimension,fresh.get(0));d.pending=nearby;d.reads(List.of(nearby));nearby.whenComplete((surrounding,failure)->player.server.execute(()->{if(!valid(player,d))return;if(failure!=null){message(player,reason(failure));return;}try{
          if(sourceRevision!=MtrRailAccess.revision(d.dimension))throw new IllegalArgumentException("MTR 轨道在校验期间发生变化，请重新预览");
          UUID id=d.saved==null?UUID.randomUUID():d.saved;var future=RailbedRoads.submit(player.serverLevel(),player,id,fresh,d.width,d.mode,d.options,surrounding,planner(d));d.pending=future;message(player,"已加入铁路施工队列");future.whenComplete((entry,e)->player.server.execute(()->{if(e==null&&drafts.get(player.getUUID())==d)clear(player);}));
        }catch(RuntimeException e){message(player,e.getMessage());}}));
      }catch(RuntimeException e){message(player,reason(e));}
    }));
  }
  private static void clear(ServerPlayer player){var d=drafts.remove(player.getUUID());if(d!=null){d.cancel();if(d.pending!=null)d.pending.cancel(false);if(d.preview!=null)d.preview.cancel(false);}var tag=new CompoundTag();tag.putString("Kind","railbedReset");if(d!=null)tag.putUUID("Token",d.token);if(d!=null)publish(player,d,tag);else RoadNetwork.open(player,tag);message(player,"铁路草稿已清除");}
  private static void message(ServerPlayer player,String text){player.displayClientMessage(Component.literal(text==null?"铁路操作失败":text),true);}
  @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){var d=drafts.remove(event.getEntity().getUUID());if(d!=null){d.cancel();if(d.pending!=null)d.pending.cancel(false);if(d.preview!=null)d.preview.cancel(false);}}
  @SubscribeEvent public static void dimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event){if(event.getEntity() instanceof ServerPlayer player)clear(player);}
  @SubscribeEvent public static void stopped(ServerStoppedEvent event){for(var d:drafts.values()){d.cancel();if(d.pending!=null)d.pending.cancel(false);if(d.preview!=null)d.preview.cancel(false);}drafts.clear();}
}
