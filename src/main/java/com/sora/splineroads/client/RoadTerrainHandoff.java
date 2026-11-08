package com.sora.splineroads.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.RoadHandoffState;
import com.sora.splineroads.core.RoadTerrainMesh.Section;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.CompiledChunk;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher.RenderChunk;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Actual chunk-upload receipts decide which surface ranges the road VBO draws.
 * Vanilla road-bearing chunks upload to isolated allocations; the buffer map,
 * compiled metadata and receipt change together on the render thread. Optional
 * renderers use their own atomic GPU installation through a separate adapter.
 */
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class RoadTerrainHandoff {
  static final RoadHandoffState<Map<Integer,ModelData>> STATE=new RoadHandoffState<>(Map::isEmpty);
  private static final Map<Object,Build> BUILDS=Collections.synchronizedMap(new WeakHashMap<>());
  private static final ThreadLocal<Build> BUILD=new ThreadLocal<>();
  private static final Map<ChunkRenderDispatcher,ArrayDeque<Runnable>> DEFERRED=new WeakHashMap<>();
  private static final RoadTerrainUploadBudget BUDGET=new RoadTerrainUploadBudget();
  // Native renderers can coalesce a dirty notification into an in-flight build.
  // Keep the publication obligation until its actual replacement is uploaded.
  private static final LinkedHashMap<Section,Long> REFRESH=new LinkedHashMap<>();
  private static Object level;
  private static volatile boolean vanillaSeen;
  private static final class Build {
    final RoadHandoffState.Ticket<Map<Integer,ModelData>> ticket;
    final AtomicBoolean cancelled;
    final Queue<Runnable> uploads;
    final Map<RenderType,VertexBuffer> staged;
    final BitSet cells;
    final WeakReference<Object> nativeJob;
    volatile boolean closed,committed;
    int requested,uploaded;
    final int requiredCells;
    Build(RoadHandoffState.Ticket<Map<Integer,ModelData>> ticket,AtomicBoolean cancelled,Queue<Runnable> uploads,Object nativeJob){
      this.ticket=ticket;this.cancelled=cancelled;this.uploads=uploads;
      this.nativeJob=nativeJob==null?null:new WeakReference<>(nativeJob);
      this.staged=ticket.staged&&uploads!=null?new HashMap<>():Collections.emptyMap();
      this.requiredCells=ticket.source==null?0:(int)ticket.source.data().values().stream().filter(data->!RoadTerrainModels.coverOnly(data)).count();
      this.cells=ticket.terrain&&ticket.source!=null&&!ticket.source.data().isEmpty()?new BitSet(4096):null;
    }
  }
  private RoadTerrainHandoff(){}
  @SubscribeEvent public static void renderFrame(TickEvent.RenderTickEvent event){
    if(event.phase==TickEvent.Phase.START){beginUploadFrame();refreshStale();}
  }
  /** A renderer can drain its native queue more than once in one frame. */
  static void beginUploadFrame(){BUDGET.begin();RoadEmbeddiumHandoff.beginUploadFrame();}
  private static void checkLevel(){
    var current=Minecraft.getInstance().level;
    if(level!=current){level=current;STATE.reset();REFRESH.clear();}
  }
  static void frame(boolean terrain){checkLevel();STATE.mode(terrain);}
  static void publish(Section section,Map<Integer,ModelData> cells,Set<UUID> owners){publish(section,cells,owners,Set.of());}
  static void publish(Section section,Map<Integer,ModelData> cells,Set<UUID> owners,Set<UUID> replaces){
    checkLevel();STATE.publish(section,cells,owners,replaces);
    if(STATE.resident(section)!=null)REFRESH.put(section,System.nanoTime()+250_000_000L);
  }
  private static void refreshStale(){
    checkLevel();var mc=Minecraft.getInstance();if(mc.level==null||REFRESH.isEmpty())return;
    long now=System.nanoTime();int count=Math.min(8,REFRESH.size());
    while(count-->0){var it=REFRESH.entrySet().iterator();var entry=it.next();var section=entry.getKey();long due=entry.getValue();it.remove();
      var resident=STATE.resident(section);if(resident==null||resident.source()==STATE.source(section))continue;
      if(!visible(section,resident)){STATE.invalidAllocation(section,resident);continue;}
      if(now>=due){Object owner=resident.owner().get();boolean requested;
        if(owner instanceof RenderChunk chunk){chunk.setDirty(false);requested=true;}else requested=RoadEmbeddiumHandoff.refresh(owner,section);
        if(requested)due=now+500_000_000L;
      }
      REFRESH.put(section,due);
    }
  }
  static void clearSources(){STATE.clearSources();}
  static void invalidateSources(){STATE.clearSources();}
  public static void release(Object owner){STATE.release(owner);}
  public static long ownershipRevision(){return STATE.ownershipRevision();}
  /** One allocation check per section; callers may retain this immutable set
   * until ownershipRevision changes, independently of CPU source publication. */
  public static Set<UUID> visibleOwners(Section section){
    var resident=STATE.resident(section);if(resident==null)return Set.of();
    if(visible(section,resident))return resident.vboOwners();
    STATE.invalidAllocation(section,resident);return Set.of();
  }
  public static boolean owns(Section section,UUID road){
    var resident=STATE.resident(section);return resident!=null&&resident.vboOwners().contains(road)&&visible(section,resident);
  }
  private static boolean visible(Section section,RoadHandoffState.Resident resident){
    Object owner=resident.owner().get();
    if(owner instanceof RenderChunk chunk){
      if(!section.equals(section(chunk.getOrigin())))return false;
      if(!(resident.upload() instanceof Map<?,?> buffers))return false;
      if(chunk.getCompiledChunk().isEmpty(RenderType.solid()))return false;
      var installed=((RoadChunkBuffers)chunk).splineroads$buffers();
      for(var entry:buffers.entrySet())if(!(entry.getValue() instanceof VertexBuffer v)||v.isInvalid()||installed.get(entry.getKey())!=v)return false;
      return true;
    }
    return owner!=null&&RoadEmbeddiumHandoff.alive(owner,section,resident.upload());
  }
  static boolean currentUpload(Section section,Set<UUID> members,boolean terrain){
    var resident=STATE.resident(section);var source=STATE.source(section);
    if(!terrain)return resident==null||Collections.disjoint(resident.vboOwners(),members);
    if(source!=null&&!Collections.disjoint(source.owners(),members)){
      if(resident==null||resident.source()!=source)return false;
      for(UUID owner:source.owners())if(members.contains(owner)&&!owns(section,owner))return false;
      return true;
    }
    // A complete empty/non-member replacement needs no new road allocation,
    // but its old road allocation must genuinely have gone away.
    return resident==null||Collections.disjoint(resident.vboOwners(),members)||resident.source()==source;
  }
  static Section section(BlockPos p){return new Section(p.getX()>>4,p.getY()>>4,p.getZ()>>4);}
  /** Every model lookup in a worker sees one frozen publication and mode. */
  static Map<Integer,ModelData> model(Section section,int cell){
    var build=BUILD.get();if(build==null||!build.ticket.section.equals(section)||!build.ticket.terrain||build.ticket.source==null)return null;
    var map=build.ticket.source.data();if(build.cells!=null&&map.containsKey(cell)&&!RoadTerrainModels.coverOnly(map.get(cell)))build.cells.set(cell);return map;
  }
  static boolean inBuild(){return BUILD.get()!=null;}
  public static void beginVanilla(Object task,RenderChunk chunk,AtomicBoolean cancelled,ChunkRenderDispatcher dispatcher){
    BUILD.remove();if(cancelled.get())return;
    vanillaSeen=true;
    var build=new Build(STATE.begin(chunk,section(chunk.getOrigin())),cancelled,((RoadChunkUploads)dispatcher).splineroads$uploads(),null);
    BUILDS.put(task,build);BUILD.set(build);
  }
  static Object beginOptional(Object task,Object owner,Section section){
    return beginOptional(task,owner,section,null);
  }
  static Object beginOptional(Object task,Object owner,Section section,Object nativeJob){
    BUILD.remove();var build=new Build(STATE.begin(owner,section),new AtomicBoolean(),null,nativeJob);
    BUILDS.put(task,build);BUILD.set(build);return build;
  }
  static Object endOptional(Object task){BUILD.remove();return BUILDS.remove(task);}
  static boolean validOptional(Object token){return token instanceof Build build&&valid(build)&&completeCells(build);}
  static boolean stagedOptional(Object token){return token instanceof Build build&&build.ticket.staged;}
  static boolean ownersOptional(Object token){return token instanceof Build build&&!build.ticket.owners().isEmpty();}
  static Object optionalJob(Object token){return token instanceof Build build&&build.nativeJob!=null?build.nativeJob.get():null;}
  static void finishOptional(Object token){if(token instanceof Build build){build.closed=true;STATE.finish(build.ticket);}}
  static void commitOptional(Object token,Object allocation){
    if(token instanceof Build build&&validOptional(build)){STATE.commit(build.ticket,allocation);}
  }
  static Section optionalSection(Object token){return token instanceof Build b?b.ticket.section:null;}
  /** Clear the worker context even for early cancellation and exceptional exits. */
  public static CompletableFuture<?> endVanilla(CompletableFuture<?> future,Object task){
    BUILD.remove();var build=BUILDS.get(task);
    if(build!=null)future.whenComplete((result,failure)->{BUILDS.remove(task);dispose(build);});
    return future;
  }
  public static void abort(Object task){
    BUILD.remove();var build=BUILDS.remove(task);if(build!=null){build.cancelled.set(true);dispose(build);}
  }
  private static boolean valid(Build build){
    if(build.closed||build.cancelled.get()||!STATE.current(build.ticket))return false;
    Object owner=build.ticket.owner();return !(owner instanceof RenderChunk chunk)||build.ticket.section.equals(section(chunk.getOrigin()));
  }
  private static boolean completeCells(Build build){
    return !build.ticket.terrain||build.ticket.source==null||(build.cells==null?0:build.cells.cardinality())==build.requiredCells;
  }
  private static void retry(Build build){
    build.cancelled.set(true);Object owner=build.ticket.owner();
    if(owner instanceof RenderChunk chunk&&build.ticket.section.equals(section(chunk.getOrigin())))chunk.setDirty(false);
  }
  public static CompletableFuture<Void> uploadVanilla(ChunkRenderDispatcher dispatcher,BufferBuilder.RenderedBuffer data,
      VertexBuffer live,Object task,RenderType layer){
    var build=BUILDS.get(task);if(build==null)return dispatcher.uploadChunkLayer(data,live);
    build.requested++;
    var completion=new CompletableFuture<Void>();
    Runnable upload=new Runnable(){public void run(){
      if(!valid(build)||!completeCells(build)||live.isInvalid()){data.release();retry(build);completion.complete(null);return;}
      if(build.ticket.staged&&!BUDGET.claim(data.drawState().bufferSize(),true)){
        DEFERRED.computeIfAbsent(dispatcher,k->new ArrayDeque<>()).add(this);return;
      }
      VertexBuffer target=build.ticket.staged?new VertexBuffer(VertexBuffer.Usage.STATIC):live;
      boolean uploaded=false;
      try{
        target.bind();target.upload(data);uploaded=true;
        if(build.ticket.staged)build.staged.put(layer,target);
        build.uploaded++;
      }catch(RuntimeException|Error failure){retry(build);completion.completeExceptionally(failure);throw failure;}
      finally{VertexBuffer.unbind();if(!uploaded&&target!=live)target.close();}
      completion.complete(null);
    }};
    build.uploads.add(upload);return completion;
  }
  /** Promoting deferred work once per native drain avoids same-frame queue spins. */
  public static void beginUploads(ChunkRenderDispatcher dispatcher){
    var waiting=DEFERRED.remove(dispatcher);
    if(waiting!=null)((RoadChunkUploads)dispatcher).splineroads$uploads().addAll(waiting);
  }
  public static void discardUploads(ChunkRenderDispatcher dispatcher){
    var waiting=DEFERRED.remove(dispatcher);if(waiting==null)return;
    var queue=((RoadChunkUploads)dispatcher).splineroads$uploads();
    synchronized(BUILDS){for(var build:BUILDS.values())if(build.uploads==queue){build.cancelled.set(true);var owner=build.ticket.owner();if(owner!=null)STATE.release(owner);}}
    while(!waiting.isEmpty())waiting.removeFirst().run();
  }
  /** An empty replacement has no upload future to move completion off a worker. */
  @SuppressWarnings({"rawtypes","unchecked"})
  public static CompletableFuture completeVanilla(CompletableFuture future,BiFunction handler,Object task){
    var build=BUILDS.get(task);if(build==null||!build.ticket.staged)return future.handle(handler);
    return future.handleAsync(handler,(Executor)action->{if(RenderSystem.isOnRenderThread())action.run();else build.uploads.add(action);});
  }
  public static void beforeCommit(Object task,Throwable failure){
    var build=BUILDS.get(task);if(build!=null&&(failure!=null||!valid(build)||!completeCells(build)||build.requested!=build.uploaded))retry(build);
  }
  /** Called in place of the vanilla compiled.set, after every layer succeeded. */
  @SuppressWarnings({"rawtypes","unchecked"})
  public static boolean commitVanilla(AtomicReference reference,Object compiled,Object task){
    var build=BUILDS.get(task);if(build==null){reference.set(compiled);return true;}
    if(build.ticket.staged)RenderSystem.assertOnRenderThread();
    synchronized(STATE){
      if(!valid(build)||!completeCells(build)||build.requested!=build.uploaded
          ||!build.ticket.owners().isEmpty()&&(!build.staged.containsKey(RenderType.solid())
              ||!(compiled instanceof CompiledChunk c)||c.isEmpty(RenderType.solid()))){retry(build);return false;}
      var chunk=(RenderChunk)build.ticket.owner();
      if(!build.ticket.staged){reference.set(compiled);STATE.commit(build.ticket,Map.of());build.committed=true;return true;}
      var buffers=((RoadChunkBuffers)chunk).splineroads$buffers();
      List<VertexBuffer> previous=new ArrayList<>();
      if(build.ticket.staged)build.staged.forEach((layer,buffer)->{var old=buffers.put(layer,buffer);if(old!=null&&old!=buffer)previous.add(old);});
      reference.set(compiled);
      STATE.commit(build.ticket,Map.copyOf(build.staged));

      build.committed=true;build.staged.clear();
      previous.forEach(VertexBuffer::close);
      return true;
    }
  }
  private static void dispose(Build build){
    if(!build.ticket.staged||build.uploads==null){build.closed=true;STATE.finish(build.ticket);return;}
    Runnable close=()->{build.closed=true;build.staged.values().forEach(VertexBuffer::close);build.staged.clear();STATE.finish(build.ticket);};
    if(RenderSystem.isOnRenderThread())close.run();else if(build.uploads!=null)build.uploads.add(close);
  }
  static String status(){return STATE.residentCount()+" uploaded sections, "+STATE.accepted()+" commits / "+STATE.rejected()+" rejected"+
      (vanillaSeen?", vanilla "+BUDGET.bytes()+" bytes/batch":"")+RoadEmbeddiumHandoff.status();}
  record UploadStats(int bytes,int uploads,int deferred,int resident){}
  static UploadStats stats(){boolean optional=RoadEmbeddiumHandoff.active();return new UploadStats(
      optional?RoadEmbeddiumHandoff.uploadedBytes():BUDGET.bytes(),optional?RoadEmbeddiumHandoff.uploadedCount():BUDGET.uploads(),
      optional?RoadEmbeddiumHandoff.pending():DEFERRED.values().stream().mapToInt(ArrayDeque::size).sum(),STATE.residentCount());}
}
