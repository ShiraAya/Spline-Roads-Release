package com.sora.splineroads.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadRenderMesh.*;
import com.sora.splineroads.world.RoadIndex;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * Region VBOs. No face traversal, triangulation, normals or per-face light reads in steady frames.
 */
@Mod.EventBusSubscriber(modid = SplineRoads.ID, value = Dist.CLIENT)
public final class RoadRenderer {
  private static final ResourceLocation
      ASPHALT = ResourceLocation.fromNamespaceAndPath(SplineRoads.ID, "textures/road/asphalt.png"),
      WHITE = ResourceLocation.fromNamespaceAndPath(SplineRoads.ID, "textures/road/white.png");
  private static final RenderType SURFACE = RoadRenderTypes.surface(ASPHALT, false),
      MARKING = RoadRenderTypes.surface(WHITE, true),
      GHOST = RenderType.entityTranslucent(WHITE),
      CONFLICT = RoadRenderTypes.conflict(WHITE);
  private static final Map<RoadSurface.Texture, RenderType> MATERIALS =
      new java.util.HashMap<>(Map.of(
          RoadSurface.Texture.CONCRETE,
              RoadRenderTypes.solid(
                  ResourceLocation.fromNamespaceAndPath(
                      "minecraft", "textures/block/smooth_stone.png")),
          RoadSurface.Texture.METAL,
              // Physical trusses must use their real depth. Marking bias (-2,-16)
              // pulled the rear beams through sign faces at long camera distances.
              RoadRenderTypes.solid(
                  ResourceLocation.fromNamespaceAndPath(
                      "minecraft", "textures/block/iron_block.png")),
          // Soil and foliage are solid planter faces. Road-paint depth bias
          // pulled the soil through the front kerb at oblique/distant views.
          RoadSurface.Texture.SOIL,
              RoadRenderTypes.solid(ResourceLocation.fromNamespaceAndPath("minecraft","textures/block/dirt.png")),
          RoadSurface.Texture.LEAVES,
              RoadRenderTypes.solid(ResourceLocation.fromNamespaceAndPath("minecraft","textures/block/oak_leaves.png")),
          RoadSurface.Texture.SIGNAL_HOUSING, signalType("housing"),
          RoadSurface.Texture.SIGNAL_GREEN, signalType("green"),
          RoadSurface.Texture.SIGNAL_RED, signalType("red"),
          RoadSurface.Texture.SIGNAL_AMBER, signalType("amber"),
          RoadSurface.Texture.SIGNAL_ATLAS, signalType("atlas")));

  static {
    for(String name:List.of("oak_log","birch_log","spruce_log","cherry_log","oak_leaves","birch_leaves","spruce_leaves","cherry_leaves"))MATERIALS.put(RoadSurface.Texture.valueOf(name.toUpperCase(java.util.Locale.ROOT)),RoadRenderTypes.surface(ResourceLocation.fromNamespaceAndPath("minecraft","textures/block/"+name+".png"),true));
    MATERIALS.put(RoadSurface.Texture.FLOWERS,RoadRenderTypes.surface(ResourceLocation.fromNamespaceAndPath("minecraft","textures/block/flowering_azalea_top.png"),true));
    MATERIALS.put(RoadSurface.Texture.NOISE_BARRIER_GLASS,RoadRenderTypes.glass(ResourceLocation.fromNamespaceAndPath("splineroads","textures/road/sr_glass.png")));
    MATERIALS.put(RoadSurface.Texture.NOISE_BARRIER,RoadRenderTypes.surface(ResourceLocation.fromNamespaceAndPath("splineroads","textures/road/sr_white.png"),true));
    for(var finish:com.sora.splineroads.core.RoadSidewalks.Finish.values())MATERIALS.put(RoadSurface.Texture.valueOf("WALK_"+finish.name()),RoadRenderTypes.solid(ResourceLocation.fromNamespaceAndPath("minecraft","textures/block/"+finish.id.substring(10)+".png")));
    MATERIALS.put(RoadSurface.Texture.WALK_TACTILE,MARKING);
  }
  private static RenderType signalType(String name) {
    return RoadRenderTypes.surface(ResourceLocation.fromNamespaceAndPath(
        SplineRoads.ID, "textures/road/sr_white.png"), true);
  }
  private static final ExecutorService WORKER =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "SplineRoads mesh builder");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY-1);
            return t;
          });
  private static final Map<UUID, Integer> versions = new ConcurrentHashMap<>();
  private static final Set<UUID> dirty = new LinkedHashSet<>();
  private static final Set<UUID> priorityDirty = new LinkedHashSet<>();
  private static final RoadBuildScope buildScope=new RoadBuildScope();
  private static final Map<UUID,Integer> meshVersions=new HashMap<>(),terrainVersions=new HashMap<>();
  private static final Map<UUID, Job> jobs = new HashMap<>();
  private static final Map<UUID, Set<Section>> roadSections = new HashMap<>();
  // Storage belongs to spatial regions; each uploaded range retains its source identity.
  private static Map<Section,GpuSection> regions=new LinkedHashMap<>(),dormant=new LinkedHashMap<>();
  private static final List<GpuSection> nearbyRegions=new ArrayList<>();
  private static long regionBoundsRevision,nearbyRevision=-1;
  private static int nearbyX,nearbyZ,nearbyDistance;
  private static long uploadCancels,uploadFrames,uploadZeroFrames,packedFaces;
  private static long pavementReuse,detailReuse;
  private static boolean extendedEncoding;
  private static long useClock,retainedBytes,lastBudget=-1;
  private static final RoadGpuRetention<GpuSection> unloadedGpu=new RoadGpuRetention<>();
  private static final Set<Section> residencyDirty=new LinkedHashSet<>();
  private static long residencyChecks,residencyEvictions,residencyUploadCancels;
  record ResidencyStats(long bytes,int regions,int queued,long checks,long evictions,long cancelledUploads){}
  static ResidencyStats residencyStats(){return new ResidencyStats(unloadedGpu.bytes(),unloadedGpu.size(),residencyDirty.size(),residencyChecks,residencyEvictions,residencyUploadCancels);}
  private static long retentionRevision;
  private static final java.util.concurrent.atomic.AtomicLong workerRetentionRevision=new java.util.concurrent.atomic.AtomicLong();
  static long retentionRevision(){return retentionRevision+workerRetentionRevision.get()+RoadTerrainModels.retentionRevision();}
  private static V cameraPosition = new V(0, 0, 0);
  private static volatile long epoch;
  private static int lastDraws, lastUploads, lastDistant, lastMatrixUploads;
  private static long lastVertices;
  private static double averageCpuMs,lastCpuMs;
  private static long lastSurfaceIndices;
  record StreamingStats(double cpuMillis,int uploads,int meshJobs,long surfaceIndices){}
  static StreamingStats streamingStats(){return new StreamingStats(lastCpuMs,lastUploads,dirty.size()+jobs.size(),lastSurfaceIndices);}
  private static final List<GpuSection> visible = new ArrayList<>();
  private static final List<GpuSection> glassVisible = new ArrayList<>();
  private static final List<GpuSection> layerVisible = new ArrayList<>();
  // BufferBuilder owns native memory; discarding the Java object does not free it in 1.20.1.
  // Road uploads share one arena across frames; preview has a separate reusable arena.
  private static final RoadUploadArena uploadArena = new RoadUploadArena();
  private static final RoadUploadArena previewArena = new RoadUploadArena();
  private static Upload pendingUpload;
  private static final java.util.concurrent.atomic.AtomicLong pieceSerial=new java.util.concurrent.atomic.AtomicLong();
  private record Prepared(long identity,long cpuBytes,Piece piece,List<RoadSurface.Face> paint,AABB bounds,Map<RoadSurface.Texture,List<RoadSurface.Face>> near,
      Map<RoadSurface.Texture,List<RoadSurface.Face>> far,long sharedDetailMaterials) {
    private static List<RoadSurface.Face> splitWalks(List<RoadSurface.Face> faces){
      var result=new ArrayList<RoadSurface.Face>();for(var f:faces)result.addAll(RoadTerrainMesh.walkSurface(f)?RoadSurfaceSections.split(List.of(f)):List.of(f));return result;
    }
    static Prepared of(Layers layers) {
      var piece=new Piece(RoadUploadBudget.faces(RoadSurfaceSections.split(layers.surface().pavement())),RoadUploadBudget.faces(splitWalks(layers.shared().detail())),RoadUploadBudget.faces(splitWalks(layers.shared().distant())));
      var paint=RoadUploadBudget.faces(RoadSurfaceSections.split(layers.surface().detail()));
      var near=new EnumMap<RoadSurface.Texture,List<RoadSurface.Face>>(RoadSurface.Texture.class);
      var far=new EnumMap<RoadSurface.Texture,List<RoadSurface.Face>>(RoadSurface.Texture.class);
      for(var f:piece.detail())near.computeIfAbsent(f.texture(),k->new ArrayList<>()).add(f);
      for(var f:piece.distant())far.computeIfAbsent(f.texture(),k->new ArrayList<>()).add(f);
      long cpuBytes=0;for(var faces:List.of(piece.pavement(),piece.detail(),piece.distant(),paint))for(var face:faces)cpuBytes+=96L+face.points().size()*64L;
      long shared=0;for(var texture:RoadSurface.Texture.values())if(Objects.equals(near.get(texture),far.get(texture)))shared|=materialBit(texture);
      var bound=new ArrayList<>(piece.detail());bound.addAll(paint);return new Prepared(pieceSerial.incrementAndGet(),cpuBytes,piece,paint,RoadRenderer.bounds(new Piece(piece.pavement(),bound,piece.distant())),near,far,shared);
    }
  }
  private record SignalCache(List<RoadSurface.Face> faces, RoadSignalBatch batch) {}
  private static final Map<UUID, SignalCache> signalBatches = new HashMap<>();
  private static final Set<RenderType> signalTypesUsed = new LinkedHashSet<>();

  static RenderType signalMaterial(RoadSurface.Texture texture) {
    return texture == RoadSurface.Texture.PLAIN ? MARKING : MATERIALS.get(texture);
  }

  static Executor terrainExecutor(){return WORKER;}

  private record MeshResult(Map<Section,Prepared> regions, RoadTerrainSourceWindows.Publication terrain) {}

  private static final Map<UUID,Preparation> preparations=new HashMap<>();
  private static final Map<UUID,AtomicUpdate> atomicUpdates=new HashMap<>();
  private static final Map<UUID,AtomicUpdate> displayUpdates=new HashMap<>();
  private static long drawSelectionBuilds,drawSelectionRanges,drawOwnershipQueries;
  record SelectionStats(long builds,long ranges,long ownershipQueries){}
  static SelectionStats selectionStats(){return new SelectionStats(drawSelectionBuilds,drawSelectionRanges,drawOwnershipQueries);}
  private static long movementRescans,movementCandidates,windowSelections,windowVisits,completedWindowSkips,lightRefreshes,buildCandidateChecks;
  private static long lightReadChecks,lightReadPositions,lightReadUnchanged,lightHashes;
  private static final int LIGHT_POSITIONS_PER_FRAME=2048,LIGHT_READ_SLICE=128;
  private static final boolean LIGHT_DIAGNOSTICS=Boolean.getBoolean("sr.lightDiagnostics");
  private static int lightPollCursor;
  record MovementStats(long rescans,long candidates,long selections,long visits,long completedSkips,long lightRefreshes){}
  static MovementStats movementStats(){return new MovementStats(movementRescans,movementCandidates,windowSelections,windowVisits,completedWindowSkips,lightRefreshes);}
  static boolean hasPendingHandoffs(){return !displayUpdates.isEmpty();}
  private static final class AtomicUpdate {
    final Set<UUID> members,all;final Map<UUID,RoadTerrainSourceWindows.Publication> terrain=new HashMap<>();
    final Set<GpuSection> pending=Collections.newSetFromMap(new IdentityHashMap<>());
    boolean installed;
    AtomicUpdate(Set<UUID> ids,Set<UUID> all){members=Set.copyOf(ids);this.all=Set.copyOf(all);}
  }
  private static void attach(GpuSection section,AtomicUpdate group){
    if(!section.handoffs.add(group))return;
    if(pendingUpload!=null&&pendingUpload.section==section){pendingUpload.cancel();pendingUpload=null;}
    group.pending.add(section);
  }
  private static void superseded(GpuSection section,Set<AtomicUpdate> obsolete){
    for(var old:obsolete)if(section.handoffs.remove(old)){old.pending.remove(section);retire(old);}
  }
  private static void retire(AtomicUpdate group){
    if(!group.installed||!group.pending.isEmpty())return;
    var ended=new HashSet<UUID>();for(UUID id:group.all)if(displayUpdates.remove(id,group))ended.add(id);
    if(!ended.isEmpty())RoadTerrainModels.endBatch(ended);
  }
  private static void completedHandoff(GpuSection section){
    var groups=new ArrayList<>(section.handoffs);section.handoffs.clear();section.retained.clear();
    section.clearDrawSelection();
    for(var group:groups){group.pending.remove(section);retire(group);}
  }
  private static boolean handoffReady(GpuSection section){
    for(var group:section.handoffs)if(!group.installed||!RoadTerrainModels.transitionReady(section.key,group.all))return false;
    return true;
  }
  /** Keep the old complete surfaces until every member of an explicit edit batch is ready. */
  public static void changedBatch(Collection<UUID> changed,Set<UUID> members){
    var all=new LinkedHashSet<>(changed);all.addAll(members);invalidate(all,true);
  }
  private static void finishAtomic(AtomicUpdate group){
    for(UUID id:group.members){var p=preparations.get(id);if(p==null||p.version!=versions.getOrDefault(id,0)||p.surfaces.size()!=p.windows.size()||RoadTerrainModels.requested()&&p.terrain.size()!=p.windows.size())return;}
    // All CPU pieces are installed before any region's VBO can be uploaded.
    // Each region therefore swaps from a complete old union to a complete new union.
    for(UUID id:group.members){var p=preparations.get(id);var old=new HashSet<>(roadSections.getOrDefault(id,Set.of()));old.removeAll(p.pieces.keySet());
      installPieces(regions,id,old,p.pieces,false);installPieces(dormant,id,old,p.pieces,true);roadSections.put(id,Set.copyOf(p.pieces.keySet()));meshVersions.put(id,p.version);signalBatches.remove(id);
      var source=group.terrain.get(id);if(source!=null){RoadTerrainModels.installPublication(id,source,true);if(p.terrain.size()==p.windows.size())terrainVersions.put(id,p.version);}atomicUpdates.remove(id);
    }group.terrain.clear();group.installed=true;RoadTerrainModels.finishBatch(group.all);retire(group);trimDormant();
  }

  private static final class Preparation {
    final int version;final List<RoadMeshSurfaces.Window> windows;final Set<RoadMeshSurfaces.Window> surfaces=new HashSet<>(),terrain=new HashSet<>();
    final Map<Section,Prepared> pieces=new HashMap<>();final Set<Section> previous;final RoadIndex.Built source;RoadMeshSurfaces.Structures structures;RoadMeshSurfaces.Context context;
    final boolean finishPrevious;
    final Map<RoadMeshSurfaces.Window,Map<Section,Prepared>> windowPieces=new LinkedHashMap<>();
    final RoadTerrainSourceWindows<RoadMeshSurfaces.Window> terrainIndex=new RoadTerrainSourceWindows<>();
    Preparation(UUID id,int version,RoadIndex.Built road){this.version=version;source=road;windows=RoadMeshSurfaces.windows(road);previous=Set.copyOf(roadSections.getOrDefault(id,Set.of()));
      var old=preparations.get(id);if(old!=null&&old.source==road){windowPieces.putAll(old.windowPieces);pieces.putAll(old.pieces);structures=old.structures;}
      // Old regions without an inherited contribution can only be retired after
      // this replacement's complete window union is known. Carry that obligation
      // across another invalidation until the existing full-union cleanup runs.
      finishPrevious=!pieces.keySet().containsAll(previous)||old!=null&&old.finishingPrevious();
    }
    boolean finishingPrevious(){return finishPrevious&&surfaces.size()<windows.size();}
    Map<Section,Prepared> replace(RoadMeshSurfaces.Window window,Map<Section,Prepared> result){
      var old=windowPieces.put(window,result);Set<Section> affected=new HashSet<>(result.keySet());if(old!=null)affected.addAll(old.keySet());
      var changed=new LinkedHashMap<Section,Prepared>();for(var key:affected){Prepared combined=null;for(var part:windowPieces.values()){var contribution=part.get(key);if(contribution!=null)combined=combined==null?contribution:append(combined,contribution);}if(combined==null)pieces.remove(key);else{pieces.put(key,combined);changed.put(key,combined);}}return changed;
    }
    RoadMeshSurfaces.Window next(boolean exact){
      windowSelections++;
      // A camera move only changes priority. Completed windows stay complete,
      // including retained preparations whose GPU regions are temporarily cold.
      if(surfaces.size()==windows.size()&&(!exact||terrain.size()==windows.size())){completedWindowSkips++;return null;}
      windowVisits+=windows.size();
      RoadMeshSurfaces.Window best=null;double nearest=Double.POSITIVE_INFINITY;
      for(var window:windows)if((!surfaces.contains(window)||exact&&!terrain.contains(window))&&(atomicUpdates.containsKey(source.record.id())||finishingPrevious()||RoadVisibility.within(window.min().x(),window.min().z(),window.max().x(),window.max().z(),cameraPosition.x(),cameraPosition.z(),Minecraft.getInstance().options.getEffectiveRenderDistance()+2))){
        double distance=window.distance(cameraPosition);if(distance<nearest){nearest=distance;best=window;}
      }return best;
    }
  }
  private record Job(int version,long epoch,CompletableFuture<MeshResult> future,Preparation preparation,RoadMeshSurfaces.Window window,java.util.concurrent.atomic.AtomicBoolean cancelled) {
    void cancel(){cancelled.set(true);future.cancel(false);}
  }
  private static <T> List<T> append(List<T> a,List<T> b){var list=new ArrayList<T>(a.size()+b.size());list.addAll(a);list.addAll(b);return List.copyOf(list);}
  private static Prepared append(Prepared a,Prepared b){
    var near=new EnumMap<RoadSurface.Texture,List<RoadSurface.Face>>(RoadSurface.Texture.class);near.putAll(a.near);b.near.forEach((key,value)->near.merge(key,value,RoadRenderer::append));
    var far=new EnumMap<RoadSurface.Texture,List<RoadSurface.Face>>(RoadSurface.Texture.class);far.putAll(a.far);b.far.forEach((key,value)->far.merge(key,value,RoadRenderer::append));
    return new Prepared(pieceSerial.incrementAndGet(),a.cpuBytes+b.cpuBytes,new Piece(append(a.piece.pavement(),b.piece.pavement()),append(a.piece.detail(),b.piece.detail()),append(a.piece.distant(),b.piece.distant())),append(a.paint,b.paint),a.bounds.minmax(b.bounds),near,far,a.sharedDetailMaterials&b.sharedDetailMaterials);
  }

  private static final Map<net.minecraft.core.SectionPos,RoadLightSnapshot> lightSnapshots=new HashMap<>();
  private static long lightSnapshotTick=Long.MIN_VALUE;
  private static RoadLightSnapshot lightPair(net.minecraft.core.SectionPos pos) {
    var level=Minecraft.getInstance().level;
    long tick=level.getGameTime();if(tick!=lightSnapshotTick){lightSnapshotTick=tick;lightSnapshots.clear();}
    var cached=lightSnapshots.get(pos);if(cached!=null)return cached;
    lightHashes++;
    var engine = level.getLightEngine();
    var snapshot=RoadLightSnapshot.capture(
        engine.getLayerListener(net.minecraft.world.level.LightLayer.SKY).getDataLayerData(pos),
        engine.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK).getDataLayerData(pos));
    lightSnapshots.put(pos,snapshot);return snapshot;
  }

  /** Availability is stable within one render-thread step. Read states from the
   * same loaded chunk used for that check, retaining normal chunk, block-state
   * and light accessors without repeating the world's chunk lookup per voxel. */
  private static final class FrameLightAccess implements RoadLightReadSet.Access {
    private net.minecraft.client.multiplayer.ClientLevel level;
    private final BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
    private final RoadFrameSamples lightSections=new RoadFrameSamples(256);
    private final RoadFrameSamples samples=new RoadFrameSamples(8192);
    private int chunkX,chunkZ;private boolean haveChunk,loaded;
    private long lastLightSection;
    private boolean haveLightSection,lastLightAvailable;
    private net.minecraft.world.level.chunk.LevelChunk chunk;
    void beginFrame(net.minecraft.client.multiplayer.ClientLevel next){level=next;haveChunk=false;haveLightSection=false;chunk=null;lightSections.clear();samples.clear();}
    void clear(){level=null;haveChunk=false;haveLightSection=false;chunk=null;lightSections.clear();samples.clear();}
    boolean loaded(BlockPos p){
      int x=p.getX()>>4,z=p.getZ()>>4;
      if(!haveChunk||x!=chunkX||z!=chunkZ){
        chunkX=x;chunkZ=z;haveChunk=true;
        // hasChunk is always true on ClientLevel; use the non-loading cache.
        chunk=level.getChunkSource().getChunk(x,z,net.minecraft.world.level.chunk.ChunkStatus.FULL,false);loaded=chunk!=null;
      }
      return loaded;
    }
    net.minecraft.world.level.block.state.BlockState state(BlockPos p){
      return loaded(p)?chunk.getBlockState(p):level.getBlockState(p);
    }
    boolean lit(BlockPos p){
      int x=p.getX()>>4,y=p.getY()>>4,z=p.getZ()>>4;
      long key=net.minecraft.core.SectionPos.asLong(x,y,z);
      if(haveLightSection&&lastLightSection==key)return lastLightAvailable;
      int known=lightSections.get(key);
      if(known==0){known=level.getLightEngine().lightOnInSection(net.minecraft.core.SectionPos.of(x,y,z))?2:1;lightSections.put(key,known);}
      haveLightSection=true;lastLightSection=key;lastLightAvailable=known==2;
      return lastLightAvailable;
    }
    @Override public int sample(long position,int requested){
      // Adjacent regions often depend on the same border voxels. Share only
      // reads from this uninterrupted render-thread step; normal world/mod
      // accessors remain authoritative and the next frame starts empty.
      int value=samples.get(position),missing=requested&~value;
      if(missing==0)return requestedValue(value,requested);
      lightReadPositions++;pos.set(BlockPos.getX(position),BlockPos.getY(position),BlockPos.getZ(position));
      if(!loaded(pos)){value|=requested|RoadLightReadSet.UNAVAILABLE_OPAQUE|RoadLightReadSet.UNAVAILABLE_PACKED;samples.put(position,value);return requestedValue(value,requested);}
      var state=state(pos);
      if((missing&RoadLightReadSet.READ_OPAQUE)!=0&&state.getLightBlock(level,pos)>=15)value|=RoadLightReadSet.OPAQUE;
      if((missing&RoadLightReadSet.READ_PACKED)!=0){
        if(lit(pos))value|=LevelRenderer.getLightColor(level,state,pos);
        else value|=RoadLightReadSet.UNAVAILABLE_PACKED;
      }
      value|=requested;samples.put(position,value);
      return requestedValue(value,requested);
    }
    private static int requestedValue(int value,int requested){
      if((value&RoadLightReadSet.UNAVAILABLE_OPAQUE)!=0)return RoadLightReadSet.UNAVAILABLE_OPAQUE|RoadLightReadSet.UNAVAILABLE_PACKED;
      return value&(((requested&RoadLightReadSet.READ_OPAQUE)!=0?RoadLightReadSet.OPAQUE:0)
          |((requested&RoadLightReadSet.READ_PACKED)!=0?RoadLightReadSet.PACKED_MASK|RoadLightReadSet.UNAVAILABLE_PACKED:0)
          |RoadLightReadSet.UNAVAILABLE_OPAQUE);
    }
  }
  private static final FrameLightAccess lightPollAccess=new FrameLightAccess();

  private static int pollLight(GpuSection s,int allowance) {
    long tick = Minecraft.getInstance().level.getGameTime();
    if(s.lightCheck==null){
      if(tick-s.checkedLightAt<20)return 0;
      s.lightCheck=s.lightReads.cursor();lightReadChecks++;
    }
    int read=s.lightCheck.advance(lightPollAccess,allowance);
    if(!s.lightCheck.complete())return read;
    boolean changed=s.lightCheck.changed();
    if(changed){
      if(s.lightCheck.located()){
        long position=s.lightCheck.changedPosition();
        s.surfaceLightDirty=s.surfaceReads.contains(position);
        s.detailLightDirty=s.detailReads.contains(position);
      }
      if(!s.surfaceLightDirty&&!s.detailLightDirty)s.surfaceLightDirty=s.detailLightDirty=true;
    }
    s.lightCheck=null;s.checkedLightAt=tick;
    if(changed){lightRefreshes++;s.dirty=true;return read;}
    lightReadUnchanged++;
    // Preserve the existing diagnostic interface. These coarse storage stamps
    // no longer decide validity: queued data and implicit sky light need not
    // match what getLightColor returns, so every poll checks the actual reads.
    if(LIGHT_DIAGNOSTICS)s.lighting.replaceAll((position,previous)->lightPair(position));
    return read;
  }

  private static void pollVisibleLight(){
    if(visible.isEmpty())return;
    lightPollAccess.beginFrame(Minecraft.getInstance().level);
    int remaining=LIGHT_POSITIONS_PER_FRAME;
    var budget=new RoadScanBudget(Math.min(64,visible.size()),System.nanoTime()+500_000);
    while(remaining>0&&budget.take()){
      var section=visible.get(Math.floorMod(lightPollCursor++,visible.size()));
      if(!section.dirty&&(pendingUpload==null||pendingUpload.section!=section))
        remaining-=pollLight(section,Math.min(LIGHT_READ_SLICE,remaining));
    }
  }

  private record OwnerRange(UUID owner,long source,int firstIndex,int indexCount,RoadTerrainMesh.Section terrain) {}

  private static final class GpuSection {
    final Section key;
    final Map<UUID, Prepared> pieces = new HashMap<>();
    final List<VertexBuffer> pavement=new ArrayList<>(),paint=new ArrayList<>();
    final Map<RoadSurface.Texture, List<VertexBuffer>> near = new EnumMap<>(RoadSurface.Texture.class),
        far = new EnumMap<>(RoadSurface.Texture.class);
    long nearMaterials,farMaterials;
    final Map<VertexBuffer,List<OwnerRange>> owners=new IdentityHashMap<>();
    final Map<UUID,Long> uploadedSources=new HashMap<>();
    final Map<VertexBuffer,RoadDrawSelection> drawSelections=new IdentityHashMap<>();
    final Map<RoadTerrainMesh.Section,Set<UUID>> terrainOwners=new HashMap<>();
    final Map<UUID,Boolean> drawActiveOwners=new HashMap<>();
    long drawRevision=Long.MIN_VALUE,drawActivityRevision=Long.MIN_VALUE,drawTerrainRevision=Long.MIN_VALUE;
    final Set<UUID> retained=new HashSet<>();
    final Set<AtomicUpdate> handoffs=Collections.newSetFromMap(new IdentityHashMap<>());
    boolean distant;
    boolean pavementOnly;
    private long liveActivity=Long.MIN_VALUE,liveRevision=Long.MIN_VALUE;
    private boolean liveOwners;
    private UUID liveOwner;
    long nearVertices, farVertices,surfaceVertices,lastUsed;
    final Matrix4f transform = new Matrix4f();
    AABB bounds,uploadedBounds;
    boolean dirty = true;
    long revision;
    long checkedLightAt;
    final Map<net.minecraft.core.SectionPos, RoadLightSnapshot> lighting = new HashMap<>();
    RoadLightReadSet lightReads=RoadLightReadSet.EMPTY;
    RoadLightReadSet surfaceReads=RoadLightReadSet.EMPTY,detailReads=RoadLightReadSet.EMPTY;
    boolean surfaceLightDirty,detailLightDirty;
    RoadLightReadSet.Cursor lightCheck;

    GpuSection(Section key) {
      this.key = key;
    }
    long sharedDetailVertices;

    boolean hasLiveOwners(){
      if(!retained.isEmpty())return true;
      long activity=ClientRoads.visibilityRevision();
      if(liveActivity==activity&&liveRevision==revision)return liveOwners;
      liveActivity=activity;liveRevision=revision;
      if(liveOwner!=null&&pieces.containsKey(liveOwner)&&ClientRoads.active(liveOwner))return liveOwners=true;
      liveOwners=false;
      liveOwner=null;
      for(var id:pieces.keySet())if(ClientRoads.active(id)){liveOwner=id;liveOwners=true;break;}
      return liveOwners;
    }

    void clearDrawSelection(){
      drawSelections.clear();terrainOwners.clear();drawActiveOwners.clear();drawRevision=Long.MIN_VALUE;
    }

    void close() {
      regionBoundsRevision++;
      unloadedGpu.remove(this);
      pavement.forEach(VertexBuffer::close);pavement.clear();paint.forEach(VertexBuffer::close);paint.clear();
      var details=Collections.newSetFromMap(new IdentityHashMap<VertexBuffer,Boolean>());
      near.values().forEach(details::addAll);far.values().forEach(details::addAll);details.forEach(VertexBuffer::close);
      sharedDetailVertices=0;
      near.clear();
      far.clear();nearMaterials=farMaterials=0;owners.clear();uploadedSources.clear();
      pavementOnly=false;
      lighting.clear();lightReads=surfaceReads=detailReads=RoadLightReadSet.EMPTY;lightCheck=null;
      surfaceLightDirty=detailLightDirty=false;
      clearDrawSelection();
      uploadedBounds=null;
    }
  }

  private static boolean asphaltMipmaps;
  static void asphaltReloaded(){asphaltMipmaps=false;}
  /** Standalone VBO asphalt needs the same minification levels as the block atlas. */
  static void prepareAsphaltTexture(){
    if(asphaltMipmaps)return;
    RenderSystem.setShaderTexture(0,ASPHALT);
    int previous=org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
    RenderSystem.bindTexture(RenderSystem.getShaderTexture(0));
    try{
      int width=org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,0,org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
      int height=org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,0,org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);
      int maximum=31-Integer.numberOfLeadingZeros(Math.max(1,Math.min(width,height)));
      // The atlas setting (usually 4) reaches 1px for vanilla 16px textures,
      // but would strand a 512px standalone road at 32px. Complete its mip chain.
      int levels=Minecraft.getInstance().options.mipmapLevels().get()>0?maximum:0;
      org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL,levels);
      // SimpleTexture allocates a base-only image and also clamps sampler LOD to zero.
      org.lwjgl.opengl.GL11.glTexParameterf(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LOD,levels);
      if(levels>0)org.lwjgl.opengl.GL30.glGenerateMipmap(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
      asphaltMipmaps=true;
    }finally{RenderSystem.bindTexture(previous);}
  }

  private static final Set<UUID> streamedNeighbors=new LinkedHashSet<>();
  private static long firstStreamedNeighbor,lastStreamedNeighbor;
  static void sourceArrived(UUID id,Collection<UUID> neighbors){
    invalidate(List.of(id),false);
    streamedNeighbors.addAll(neighbors);streamedNeighbors.remove(id);
    lastStreamedNeighbor=System.nanoTime();
    if(firstStreamedNeighbor==0)firstStreamedNeighbor=lastStreamedNeighbor;
  }
  private static void refreshStreamedNeighbors(){
    if(streamedNeighbors.isEmpty())return;
    long now=System.nanoTime();
    if(now-lastStreamedNeighbor<200_000_000L&&now-firstStreamedNeighbor<1_000_000_000L)return;
    var ready=new ArrayList<UUID>();
    for(var it=streamedNeighbors.iterator();it.hasNext()&&ready.size()<24;){
      UUID id=it.next();
      if(jobs.containsKey(id))continue;
      ready.add(id);it.remove();
    }
    if(streamedNeighbors.isEmpty())firstStreamedNeighbor=0;
    if(!ready.isEmpty())invalidate(ready,false);
  }
  public static void changed(Collection<UUID> ids) {
    streamedNeighbors.removeAll(ids);
    if(streamedNeighbors.isEmpty())firstStreamedNeighbor=0;
    invalidate(ids,false);
  }
  /** Eviction removes the same local render ownership as an explicit change. */
  static void sourceEvicted(Collection<UUID> ids){invalidate(ids,false);}
  private static void invalidate(Collection<UUID> ids,boolean atomic) {
    var affected=new LinkedHashSet<UUID>(ids);var obsolete=new HashSet<AtomicUpdate>();for(UUID id:ids){var group=displayUpdates.get(id);if(group!=null)obsolete.add(group);}
    for(var group:obsolete)for(UUID id:group.all){atomicUpdates.remove(id);affected.add(id);}
    AtomicUpdate update=null;
    if(atomic||!obsolete.isEmpty()){
      var live=new HashSet<>(affected);live.removeIf(id->!ClientRoads.INDEX.roads.containsKey(id));update=new AtomicUpdate(live,affected);
      for(UUID id:affected)displayUpdates.put(id,update);for(UUID id:live)atomicUpdates.put(id,update);
      var previous=new HashSet<RoadTerrainMesh.Section>();
      for(var map:List.of(regions,dormant))for(var section:map.values()){
        boolean touches=!Collections.disjoint(section.pieces.keySet(),affected)||!Collections.disjoint(section.handoffs,obsolete);
        for(var ranges:section.owners.values())for(var range:ranges)if(affected.contains(range.owner)){
          touches=true;if(map==regions&&range.terrain!=null)previous.add(range.terrain);
        }
        if(touches){
          if(map==regions&&RoadTerrainModels.regionLoaded(section.key)){attach(section,update);superseded(section,obsolete);}
          else discardAllocation(section);
        }
      }
      RoadTerrainModels.beginBatch(affected,previous);
    }

    // Discard every staged preparation when a member changes before publication.
    for (UUID id : affected) {
      versions.merge(id, 1, Integer::sum);
      if(!ClientRoads.INDEX.roads.containsKey(id)) {
        var pending=jobs.remove(id);if(pending!=null)pending.cancel();
        dirty.remove(id);priorityDirty.remove(id);remove(id);versions.remove(id);
      } else {RoadTerrainModels.remove(id);enqueue(id);}
    }
    if(update!=null&&update.members.isEmpty())finishAtomic(update);
  }

  public static void reset() {
    RoadClientLightBudget.reset();
    if(pendingUpload!=null){pendingUpload.cancel();pendingUpload=null;}
    clearPreviewBuffer();RoadGpuTimer.reset();
    clearPreviewFurniture();
    previewRecords.clear();previewContextRevision++;
    epoch++;
    RoadTerrainModels.clear();
    lightSnapshots.clear();
    lightPollAccess.clear();
    lightPollCursor=0;
    dirty.clear();priorityDirty.clear();streamedNeighbors.clear();firstStreamedNeighbor=0;
    buildScope.clear();
    versions.clear();
    jobs.values().forEach(j -> j.cancel());
    jobs.clear();
    roadSections.clear();meshVersions.clear();terrainVersions.clear();preparations.clear();atomicUpdates.clear();displayUpdates.clear();
    for (var s : regions.values()) s.close();
    regions.clear();for(var s:dormant.values())s.close();dormant.clear();retainedBytes=0;
    unloadedGpu.clear();residencyDirty.clear();
    signalBatches.clear();
    signalTypesUsed.clear();
    visible.clear();glassVisible.clear();
    nearbyRegions.clear();nearbyRevision=-1;
    lastDraws = lastUploads = lastDistant = 0;
    lastVertices = 0;lastSurfaceIndices=0;
    averageCpuMs = 0;
  }

  /** Encoding is distinct from the selected surface backend; never bind the other layout. */
  static void shaderMode(boolean extended){
    if(extendedEncoding==extended){if(lastBudget!=com.sora.splineroads.config.RoadClientConfig.INACTIVE_VBO_CACHE_MIB.get())trimDormant();return;}
    extendedEncoding=extended;if(pendingUpload!=null){pendingUpload.cancel();pendingUpload=null;}clearPreviewBuffer();
    for(var e:regions.entrySet())if(!dormant.containsKey(e.getKey())){var next=new GpuSection(e.getValue().key);next.pieces.putAll(e.getValue().pieces);next.bounds=e.getValue().bounds;next.revision=e.getValue().revision;dormant.put(e.getKey(),next);}
    var old=regions;regions=dormant;dormant=old;visible.clear();glassVisible.clear();
    regionBoundsRevision++;
    // A format cache with an unfinished edit cannot retire the visible batch.
    // Transfer every live gate before discarding the now-inactive allocation.
    for(var section:regions.values()){
      section.checkedLightAt=Long.MIN_VALUE/2;
      section.lightCheck=null;
      for(UUID id:section.pieces.keySet()){var group=displayUpdates.get(id);if(group!=null&&RoadTerrainModels.regionLoaded(section.key))attach(section,group);}
    }
    for(var section:dormant.values())if(!section.handoffs.isEmpty())discardAllocation(section);
    trimDormant();
  }
  private static void discardAllocation(GpuSection section){
    if(dormant.get(section.key)==section)retainedBytes=Math.max(0,retainedBytes-gpuBytes(section));
    if(pendingUpload!=null&&pendingUpload.section==section){pendingUpload.cancel();pendingUpload=null;}
    section.close();section.nearVertices=section.farVertices=section.surfaceVertices=0;section.dirty=true;
    updateBounds(section);completedHandoff(section);
  }
  static void chunkUnloaded(RoadTerrainMesh.Chunk chunk){
    chunkChanged(net.minecraft.world.level.ChunkPos.asLong(chunk.x(),chunk.z()));
    if(displayUpdates.isEmpty())return;
    var key=new Section(Math.floorDiv(chunk.x(),4),0,Math.floorDiv(chunk.z(),4));
    if(RoadTerrainModels.regionLoaded(key))return;
    for(var map:List.of(regions,dormant)){var section=map.get(key);if(section!=null&&!section.handoffs.isEmpty())discardAllocation(section);}
  }
  private static long gpuBytes(GpuSection s){return (s.nearVertices+s.farVertices-s.surfaceVertices-s.sharedDetailVertices)*64L+s.lightReads.bytes()+s.surfaceReads.bytes()+s.detailReads.bytes();}
  /** Chunk cache notifications include VBO-only regions. Whole-road activity
   * cannot protect an unloaded distant portion of a long road indefinitely. */
  static void chunkChanged(long chunk){
    var key=new Section(Math.floorDiv((int)chunk,4),0,Math.floorDiv((int)(chunk>>32),4));
    if(regions.containsKey(key)||dormant.containsKey(key))residencyDirty.add(key);
  }
  private static void reconcileResidency(Section key){
    residencyChecks++;
    boolean loaded=RoadTerrainModels.regionLoaded(key);
    if(!loaded&&pendingUpload!=null&&pendingUpload.section.key.equals(key)){
      pendingUpload.cancel();pendingUpload=null;residencyUploadCancels++;
    }
    for(var map:List.of(regions,dormant)){
      var section=map.get(key);if(section==null)continue;
      // A completely absent region has no old display to protect. Retire its
      // edit gate just as the terrain unload path does, without losing pieces.
      if(!loaded&&!section.handoffs.isEmpty())discardAllocation(section);
      unloadedGpu.update(section,gpuBytes(section),loaded);
    }
  }
  private static void trimUnloadedGpu(){
    long deadline=System.nanoTime()+500_000;
    var scans=new RoadScanBudget(16,deadline);
    while(!residencyDirty.isEmpty()&&scans.take()){
      var iterator=residencyDirty.iterator();var key=iterator.next();iterator.remove();reconcileResidency(key);
    }
    long limit=com.sora.splineroads.config.RoadClientConfig.UNLOADED_VBO_CACHE_MIB.get()*1048576L;
    var evictions=new RoadScanBudget(8,deadline);
    while(evictions.take()){
      var section=unloadedGpu.takeOldest(limit);if(section==null)break;
      // A return can race a queued notification. Check actual residency before
      // releasing the allocation, never the whole road's active flag.
      if(RoadTerrainModels.regionLoaded(section.key))continue;
      discardAllocation(section);residencyEvictions++;
    }
  }
  private static void trimDormant(){
    lastBudget=com.sora.splineroads.config.RoadClientConfig.INACTIVE_VBO_CACHE_MIB.get();long limit=lastBudget*1048576L;
    retainedBytes=0;for(var s:dormant.values())retainedBytes+=gpuBytes(s);
    if(retainedBytes<=limit)return;var ordered=new ArrayList<>(dormant.values());ordered.sort(Comparator.comparingLong(s->s.lastUsed));
    for(var s:ordered){if(retainedBytes<=limit)break;discardAllocation(s);}
  }
  private static void removePiece(Map<Section,GpuSection> map,UUID id,Section key){
    var section=map.get(key);if(section==null||section.pieces.remove(id)==null)return;
    if(map==regions&&displayUpdates.containsKey(id)&&section.owners.values().stream().anyMatch(ranges->ranges.stream().anyMatch(range->range.owner.equals(id))))section.retained.add(id);
    if(pendingUpload!=null&&pendingUpload.section==section){pendingUpload.cancel();pendingUpload=null;}
    section.revision++;section.dirty=true;
    if(section.pieces.isEmpty()&&section.retained.isEmpty()){map.remove(key);section.close();completedHandoff(section);return;}
    updateBounds(section);
    // Entirely cold batches can release GPU storage before their road sources.
    if(section.retained.isEmpty()&&section.pieces.keySet().stream().noneMatch(ClientRoads::active)){
      discardAllocation(section);
    }
    // Keep active survivors in the old buffers until replacement upload is complete.
  }
  private static void updateBounds(GpuSection section){
    regionBoundsRevision++;
    section.bounds=section.uploadedBounds;
    for(var p:section.pieces.values())section.bounds=section.bounds==null?p.bounds:section.bounds.minmax(p.bounds);
  }
  private static void remove(UUID id){
    RoadTerrainModels.remove(id);signalBatches.remove(id);meshVersions.remove(id);terrainVersions.remove(id);preparations.remove(id);
    for(var map:List.of(regions,dormant))for(var key:roadSections.getOrDefault(id,Set.of()))removePiece(map,id,key);
    roadSections.remove(id);trimDormant();
  }
  private static void installPieces(Map<Section,GpuSection> map,UUID id,Set<Section> old,Map<Section,Prepared> pieces,boolean inactive){
    retentionRevision++;
    for(var key:old)if(!pieces.containsKey(key))removePiece(map,id,key);
    pieces.forEach((key,p)->{
      var section=map.computeIfAbsent(key,GpuSection::new);
      var group=displayUpdates.get(id);if(group!=null){if(map==regions&&RoadTerrainModels.regionLoaded(key))attach(section,group);else discardAllocation(section);}
      section.pieces.put(id,p);updateBounds(section);
      section.dirty=true;section.revision++;
    });
  }
  /** VBOs have separate per-region budgets; account only CPU data here. */
  static Map<UUID,Long> retainedRoadBytes(Collection<UUID> ids){
    Map<UUID,Long> totals=new HashMap<>();
    for(UUID id:ids)totals.put(id,retainedRoadBytes(id));
    return totals;
  }
  static long retainedRoadBytes(UUID id){
    long total=RoadTerrainModels.retainedBytes(id);var preparation=preparations.get(id);if(preparation!=null){if(preparation.structures!=null)total+=preparation.structures.bytes();if(preparation.context!=null)total+=preparation.context.bytes();}
    for(var key:roadSections.getOrDefault(id,Set.of())){
      var section=regions.get(key);if(section==null)continue;
      var p=section.pieces.get(id);if(p!=null)total+=p.cpuBytes;
    }return total;
  }
  static boolean releaseCompletedPreparation(UUID id){
    var p=preparations.get(id);
    if(p==null||jobs.containsKey(id)||p.surfaces.size()!=p.windows.size()
        ||p.context==null&&p.structures==null)return false;
    p.context=null;p.structures=null;retentionRevision++;
    return true;
  }
  static void discardDerived(UUID id){
    versions.merge(id,1,Integer::sum);var job=jobs.remove(id);if(job!=null)job.cancel();
    remove(id);enqueue(id);
  }
  static void suspend(UUID id){RoadTerrainModels.suspend(id);}
  static void resume(UUID id){
    RoadTerrainModels.resume(id);
    int version=versions.getOrDefault(id,0);
    if(!Objects.equals(meshVersions.get(id),version)
        ||RoadTerrainModels.requested()&&!Objects.equals(terrainVersions.get(id),version))enqueue(id);
  }
  private static void enqueue(UUID id){dirty.add(id);if(buildVisible(id))priorityDirty.add(id);}
  private static boolean buildVisible(UUID id){
    var preparation=preparations.get(id);
    if(atomicUpdates.containsKey(id)||preparation!=null&&preparation.finishingPrevious())return ClientRoads.INDEX.roads.containsKey(id);
    if(!ClientRoads.active(id))return false;var road=ClientRoads.INDEX.roads.get(id);if(road==null)return false;
    return RoadVisibility.within(road.mesh.min().x(),road.mesh.min().z(),road.mesh.max().x(),road.mesh.max().z(),cameraPosition.x(),cameraPosition.z(),Minecraft.getInstance().options.getEffectiveRenderDistance()+2);
  }

  static boolean terrainSourcesReady(){
    for(UUID id:ClientRoads.INDEX.roads.keySet())if(buildVisible(id)){
      var p=preparations.get(id);if(p==null){if(!Objects.equals(terrainVersions.get(id),versions.getOrDefault(id,0)))return false;}
      else for(var w:p.windows)if(w.distance(cameraPosition)<Math.pow((Minecraft.getInstance().options.getEffectiveRenderDistance()+2)*16,2)&&!p.terrain.contains(w))return false;
    }return true;
  }
  /** A terrain tile may contain faces from several work windows. Publishing a
   * partial owner would hide that owner's complete VBO range in this section. */
  static boolean terrainTileReady(UUID road,RoadTerrainMesh.Chunk chunk){
    var p=preparations.get(road);if(p==null)return true;
    double x=chunk.x()*16.0,z=chunk.z()*16.0;
    for(var w:p.windows)if(w.min().x()<x+16&&w.max().x()>x&&w.min().z()<z+16&&w.max().z()>z&&!p.terrain.contains(w))return false;
    return true;
  }


  private static void refreshBuildScope(){
    boolean exact=RoadTerrainModels.requested();
    if(!buildScope.changed(cameraPosition.x(),cameraPosition.z(),Minecraft.getInstance().options.getEffectiveRenderDistance(),exact))return;
    movementRescans++;
    // Wake deferred roads only when the view or backend changes. Source edits
    // and cold returns enqueue their own owners in invalidate()/resume().
    // Visible source edits/returns have a separate ready set so a bounded visit
    // budget cannot delay them behind a large retained offscreen backlog.
    // Window enumeration belongs to bounded scheduling.
    var ready=new LinkedHashSet<UUID>();
    for(UUID id:ClientRoads.INDEX.roads.keySet()){
      movementCandidates++;int version=versions.getOrDefault(id,0);
      if((!Objects.equals(meshVersions.get(id),version)||exact&&!Objects.equals(terrainVersions.get(id),version))&&buildVisible(id))ready.add(id);
    }
    dirty.addAll(ready);priorityDirty.addAll(ready);
  }
  private static void update() {
    refreshBuildScope();
    int completed = 0;long deadline=System.nanoTime()+750_000;
    for (var it = jobs.entrySet().iterator(); it.hasNext() && completed < 4 && System.nanoTime()<deadline; ) {
      var entry = it.next();
      var job = entry.getValue();
      if(!job.future.isDone()&&!buildVisible(entry.getKey())){dirty.add(entry.getKey());job.cancel();it.remove();continue;}
      if (!job.future.isDone()) continue;
      it.remove();
      completed++;
      if (job.epoch != epoch || job.version != versions.getOrDefault(entry.getKey(), 0)) continue;
      try {
        var result=job.future.join();var p=job.preparation;UUID id=entry.getKey();
        var atomic=atomicUpdates.get(id);
        if(atomic!=null){
          if(result.terrain()!=null){atomic.terrain.put(id,result.terrain());p.terrain.add(job.window);}
          if(result.regions()!=null){p.replace(job.window,result.regions());p.surfaces.add(job.window);}
          finishAtomic(atomic);if(atomicUpdates.containsKey(id)&&(p.surfaces.size()<p.windows.size()||RoadTerrainModels.requested()&&p.terrain.size()<p.windows.size()))enqueue(id);continue;
        }
        if(result.terrain()!=null){
          boolean first=p.terrain.isEmpty();p.terrain.add(job.window);
          RoadTerrainModels.installPublication(id,result.terrain(),first);
          if(p.terrain.size()==p.windows.size())terrainVersions.put(id,job.version);
        }
        if(result.regions()!=null){
          var replacedKeys=new HashSet<>(p.windowPieces.getOrDefault(job.window,Map.of()).keySet());
          var changed=p.replace(job.window,result.regions());
          replacedKeys.removeAll(p.pieces.keySet());
          installPieces(regions,id,replacedKeys,changed,false);installPieces(dormant,id,replacedKeys,changed,true);
          p.surfaces.add(job.window);var keys=new HashSet<>(roadSections.getOrDefault(id,Set.of()));keys.removeAll(replacedKeys);keys.addAll(changed.keySet());roadSections.put(id,Set.copyOf(keys));
          if(p.surfaces.size()==p.windows.size()){
            for(var key:p.previous)if(!p.pieces.containsKey(key)){removePiece(regions,id,key);removePiece(dormant,id,key);}
            roadSections.put(id,Set.copyOf(p.pieces.keySet()));meshVersions.put(id,job.version);
          }signalBatches.remove(id);trimDormant();
        }
        if(p.surfaces.size()<p.windows.size()||RoadTerrainModels.requested()&&p.terrain.size()<p.windows.size())enqueue(id);
      } catch (CompletionException e) {
        ClientRoads.error = "道路网格构建失败：" + e.getCause().getMessage();
      }
    }
    // Bound visits independently of successful submissions. Offscreen owners
    // are woken by refreshBuildScope(), invalidate() or resume(), rather than
    // circulating through the ready queue on every display frame.
    refreshStreamedNeighbors();
    var candidates=new RoadScanBudget(Math.min(64,dirty.size()),deadline);
    while (!dirty.isEmpty()&&jobs.size()<4&&candidates.take()) {
      buildCandidateChecks++;
      var it=(priorityDirty.isEmpty()?dirty:priorityDirty).iterator();UUID id=it.next();it.remove();dirty.remove(id);
      var built = ClientRoads.INDEX.roads.get(id);
      if (built == null) {
        remove(id);
        continue;
      }
      if(!buildVisible(id))continue;
      if(jobs.containsKey(id)){enqueue(id);continue;}
      int version=versions.getOrDefault(id,0);
      var preparation=preparations.get(id);if(preparation==null||preparation.version!=version){preparation=new Preparation(id,version,built);preparations.put(id,preparation);}
      boolean needTerrain=RoadTerrainModels.requested();var window=preparation.next(needTerrain);if(window==null)continue;
      boolean surfaceWanted=!preparation.surfaces.contains(window),terrainWanted=needTerrain&&!preparation.terrain.contains(window);
      var neighbors = ClientRoads.INDEX.surfaceNeighbors(built);
      List<com.sora.splineroads.world.RoadIndex.Built> joined = new ArrayList<>(),
          higher = new ArrayList<>();
      for (var other : neighbors) {
        if (other.mesh.min().y() > built.mesh.max().y() + .2
            || other.mesh.max().y() < built.mesh.min().y() - .2) continue;
        if(built.sameJunctionTriangles(other))continue;
        joined.add(other);
        if(RoadSurface.higherPriority(other.record.id(),other.mesh,id,built.mesh))higher.add(other);
      }
      long generation = epoch;final var windowPreparation=preparation;var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
      jobs.put(
          id,
          new Job(
              version,
              generation,
              CompletableFuture.supplyAsync(
                  () -> {
                    if(generation!=epoch || version!=versions.getOrDefault(id,0))return new MeshResult(Map.of(),null);
                    try(var scope=RoadPlanningBudget.cancellable("客户端道路网格",()->cancelled.get()||generation!=epoch||version!=versions.getOrDefault(id,0))){
                    if(windowPreparation.context==null)windowPreparation.context=new RoadMeshSurfaces.Context(built,joined,higher,windowPreparation.windows);
                    var surfaces=windowPreparation.context.prepare(window,surfaceWanted,terrainWanted);RoadPlanningBudget.check();
                    var terrain=terrainWanted?windowPreparation.terrainIndex.replace(window,RoadTerrainMesh.source(surfaces.exact())):null;
                    Map<Section,Prepared> result=surfaceWanted?new LinkedHashMap<>():null;
                    if(surfaceWanted){if(windowPreparation.structures==null)windowPreparation.structures=new RoadMeshSurfaces.Structures(built,windowPreparation.windows);windowPreparation.structures.layers(surfaces.surface(),window).forEach((key,piece)->result.put(key,Prepared.of(piece)));}
                    return new MeshResult(result,terrain);
                    }finally{
                      // Cancellation may retain a newly constructed Context in
                      // Preparation without ever installing a mesh. Publish its
                      // accounting generation even on that completion path.
                      workerRetentionRevision.incrementAndGet();
                    }
                  },
                  WORKER),preparation,window,cancelled));
    }
  }

  @SubscribeEvent
  public static void render(RenderLevelStageEvent event) {
    if(event.getStage()==RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS){
      if(Minecraft.getInstance().level==null)return;
      var camera=event.getCamera().getPosition();cameraPosition=new V(camera.x,camera.y,camera.z);
      glassVisible.clear();for(var s:visible){var list=(s.distant?s.far:s.near).get(RoadSurface.Texture.NOISE_BARRIER_GLASS);if(list!=null&&!list.isEmpty())glassVisible.add(s);}
      if(glassVisible.isEmpty())return;
      glassVisible.sort(java.util.Comparator.comparingDouble((GpuSection s)->s.bounds.distanceToSqr(camera)).reversed());
      for(var s:glassVisible){var origin=s.key.origin();s.transform.set(event.getPoseStack().last().pose()).translate((float)(origin.x()-camera.x),(float)(origin.y()-camera.y),(float)(origin.z()-camera.z));}
      draw(glassVisible,MATERIALS.get(RoadSurface.Texture.NOISE_BARRIER_GLASS),RoadSurface.Texture.NOISE_BARRIER_GLASS,event);return;
    }
    if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
    var mc = Minecraft.getInstance();
    if (mc.level == null) return;
    RoadSignVisibility.beginFrame();
    long started = System.nanoTime();
    lastDraws = lastDistant = lastMatrixUploads = 0;
    lastVertices = 0;lastSurfaceIndices=0;
    boolean shadowPass=ShaderPackState.current().shadowPass();
    if(!shadowPass)RoadTerrainModels.beginFrame();
    var currentCamera=event.getCamera().getPosition();
    cameraPosition=new V(currentCamera.x,currentCamera.y,currentCamera.z);
    if(!shadowPass){update();RoadTerrainModels.publish();trimUnloadedGpu();}
    var camera = currentCamera;

    int uploads = 0;
    visible.clear();glassVisible.clear();
    int renderChunks=mc.options.getEffectiveRenderDistance();
    // Distance and frustum checks also precede lighting and uploads, including newly loaded roads.
    for (var s : nearbyRegions(camera.x,camera.z,renderChunks)) {
      if (s.bounds == null) continue;
      if (!RoadVisibility.within(s.bounds.minX,s.bounds.minZ,s.bounds.maxX,s.bounds.maxZ,
          camera.x,camera.z,renderChunks)||!event.getFrustum().isVisible(s.bounds)||!s.hasLiveOwners()) continue;
      double d = s.bounds.distanceToSqr(camera);
      if (d > 112 * 112) s.distant = true;
      else if (d < 88 * 88) s.distant = false; // hysteresis, no flicker near a LOD boundary
      V origin = s.key.origin();
      s.transform
          .set(event.getPoseStack().last().pose())
          .translate(
              (float) (origin.x() - camera.x),
              (float) (origin.y() - camera.y),
              (float) (origin.z() - camera.z));
      visible.add(s);s.lastUsed=++useClock;
      if (s.distant) lastDistant++;
    }
    if(!shadowPass){RoadUploadBudget.beginFrame(com.sora.splineroads.config.RoadClientConfig.VBO_UPLOAD_MICROS.get(),SmartSignClient.pendingUploadBytes());uploads=uploadVisible();}else RoadUploadBudget.drawOnly();
    var pose = event.getPoseStack();
    var buffers = mc.renderBuffers().bufferSource();
    RoadGpuTimer.begin();
    if(!visible.isEmpty())drawPrepared(visible,event.getPoseStack(),event.getProjectionMatrix(),true);
    RoadGpuTimer.end();
    pose.pushPose();
    if (mc.level != null) {
      signalTypesUsed.clear();
      for (var road : ClientRoads.INDEX.signalsNear((int)Math.floor(camera.x) >> 4, (int)Math.floor(camera.z) >> 4)) {
        if(!ClientRoads.active(road.record.id()))continue;
        if (road.signalBounds == null || road.signalBounds.distanceToSqr(camera) >= 128 * 128
            || !event.getFrustum().isVisible(road.signalBounds)) continue;
        var faces = road.signalFaces(mc.level.getGameTime());
        var cached = signalBatches.get(road.record.id());
        if (cached == null || cached.faces() != faces) {
          cached = new SignalCache(faces, new RoadSignalBatch(faces));
          signalBatches.put(road.record.id(), cached);
        }
        cached.batch().emit(pose, buffers, cameraPosition, RoadRenderer::signalMaterial, signalTypesUsed);
      }
      for (var type : signalTypesUsed) buffers.endBatch(type);
    }
    SmartSignClient.render(event);TollNameRenderer.render(event);TollPreview.render(event);
    drawPreview(event);
    pose.popPose();
    RoadUploadBudget.endFrame();
    lastUploads = uploads;
    double elapsed = (System.nanoTime() - started) / 1e6;
    lastCpuMs=elapsed;
    averageCpuMs = averageCpuMs == 0 ? elapsed : averageCpuMs * .9 + elapsed * .1;
  }

  /** Cache only a conservative horizontal broad phase. The real camera,
   * frustum, ownership and LOD are still checked on every pass, including
   * shader shadow passes. A bounds change or format swap invalidates this list.
   * The extra two chunks cover every camera position in the 64-block cell. */
  private static Collection<GpuSection> nearbyRegions(double x,double z,int distance){
    if(!Double.isFinite(x)||!Double.isFinite(z)||distance>Integer.MAX_VALUE-2)return regions.values();
    int cellX=(int)Math.floor(x/64),cellZ=(int)Math.floor(z/64);
    if(nearbyRevision!=regionBoundsRevision||nearbyX!=cellX||nearbyZ!=cellZ||nearbyDistance!=distance){
      nearbyRegions.clear();
      double centerX=cellX*64.0+32,centerZ=cellZ*64.0+32;
      for(var section:regions.values()){
        var b=section.bounds;
        if(b!=null&&RoadVisibility.within(b.minX,b.minZ,b.maxX,b.maxZ,centerX,centerZ,Math.max(0,distance)+2))nearbyRegions.add(section);
      }
      nearbyRevision=regionBoundsRevision;nearbyX=cellX;nearbyZ=cellZ;nearbyDistance=distance;
    }
    return nearbyRegions;
  }

  private static Mesh ghostMesh;
  private static List<Mesh> ghostRoads;
  private static List<RoadSurface.Face> ghostFaces;
  private static VertexBuffer ghostBuffer;
  private static V ghostOrigin;
  private static boolean ghostDelete, ghostJunction, ghostConflict;
  private static void clearPreviewBuffer() {
    if (ghostBuffer != null) ghostBuffer.close();
    ghostBuffer = null; ghostMesh = null; ghostRoads = null; ghostFaces = null;
  }
  private static void drawPreview(RenderLevelStageEvent event) {
    if (ClientRoads.preview == null) { clearPreviewBuffer(); clearPreviewFurniture(); if(!previewRecords.isEmpty())previewCandidates(List.of()); return; }
    if(furniturePreview!=ClientRoads.preview||furnitureRevision!=ClientRoads.INDEX.revision()
        ||furnitureContextRevision!=previewContextRevision
        ||furnitureGround!=null&&!furnitureGround.sameLevel(Minecraft.getInstance().level))clearPreviewFurniture();
    boolean deleting = Minecraft.getInstance().screen instanceof RoadDeleteScreen || ClientRoads.conflictPreview;
    boolean junction = ClientRoads.preview == JunctionScreen.previewMesh;
    // The unchanged editor shows the existing furniture in the world. Building
    // a second translucent copy of every fixture delays its first frame.
    boolean unchanged=savedPreview(ClientRoads.preview);
    boolean furniture=!unchanged&&!deleting&&ClientRoads.nodePreviews.size()<3&&ClientRoads.preview.settings().style()!=Style.TOLL
        &&RoadProfile.modern(ClientRoads.preview.settings().style());
    boolean furnitureChanged=furniture&&(furniturePreview!=ClientRoads.preview
        ||furnitureRevision!=ClientRoads.INDEX.revision()||furnitureContextRevision!=previewContextRevision
        ||(furnitureGround==null||!furnitureGround.current(Minecraft.getInstance().level)));
    if (ghostMesh != ClientRoads.preview || ghostRoads != ClientRoads.nodePreviews
        || ghostFaces != JunctionScreen.previewFaces || ghostDelete != deleting || ghostJunction != junction
        || ghostConflict != ClientRoads.conflictPreview || furnitureChanged) {
      clearPreviewBuffer();
      ghostOrigin = ClientRoads.preview.first().center();
      V camera = cameraPosition;
      var builder = previewArena.begin();
      cameraPosition = ghostOrigin;
      try { emitPreview(new PoseStack(), builder); }
      finally { cameraPosition = camera; }
      var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
      try {
        buffer.bind(); buffer.upload(builder.end()); ghostBuffer = buffer;
      } catch (RuntimeException e) { buffer.close(); throw e; }
      finally { VertexBuffer.unbind(); }
      ghostMesh = ClientRoads.preview; ghostRoads = ClientRoads.nodePreviews;
      ghostFaces = JunctionScreen.previewFaces; ghostDelete = deleting; ghostJunction = junction;
      ghostConflict=ClientRoads.conflictPreview;
    }
    var type=ClientRoads.conflictPreview?CONFLICT:GHOST;
    type.setupRenderState();
    var shader = RenderSystem.getShader();
    var transform = new Matrix4f(event.getPoseStack().last().pose()).translate(
        (float)(ghostOrigin.x()-cameraPosition.x()), (float)(ghostOrigin.y()-cameraPosition.y()),
        (float)(ghostOrigin.z()-cameraPosition.z()));
    ghostBuffer.bind(); setupShader(shader, event);
    // A hidden lower deck can be beyond the normal fog end even though the
    // bridge above it is in view. Keep the diagnostic red identifiable instead
    // of blending it completely into the sky colour through the occluder.
    if(ClientRoads.conflictPreview){
      if(shader.FOG_START!=null){shader.FOG_START.set(1_000_000f);shader.FOG_START.upload();}
      if(shader.FOG_END!=null){shader.FOG_END.set(1_000_001f);shader.FOG_END.upload();}
    }
    if (shader.MODEL_VIEW_MATRIX != null) { shader.MODEL_VIEW_MATRIX.set(transform); shader.MODEL_VIEW_MATRIX.upload(); }
    ghostBuffer.draw(); lastDraws++;
    shader.clear(); VertexBuffer.unbind(); type.clearRenderState();
  }
  private static void emitPreview(PoseStack pose, VertexConsumer c) {
    var mc = Minecraft.getInstance();
      boolean conflict=ClientRoads.conflictPreview;
      double lift=conflict?.06:-.025;
      int walkColor=conflict?0xFF5D45:0x9DE69A;
      var previews =
          ClientRoads.nodePreviews.isEmpty()
              ? List.of(ClientRoads.preview)
              : ClientRoads.nodePreviews;
      for (var mesh : previews)
        for (int i = 1; i < mesh.samples().size(); i++) {
          Sample a = mesh.samples().get(i - 1), b = mesh.samples().get(i);
          V l = a.at(a.halfWidth(), lift),
              r = a.at(-a.halfWidth(), lift),
              ll = b.at(b.halfWidth(), lift),
              rr = b.at(-b.halfWidth(), lift);
          triangle(
              pose,
              c,
              l,
              r,
              rr,
              (mc.screen instanceof RoadDeleteScreen || ClientRoads.conflictPreview ? 0xFF5D45 : 0x3FDCDD),
              105,
              LightTexture.FULL_BRIGHT,
              false,
              true);
          triangle(
              pose,
              c,
              l,
              rr,
              ll,
              (mc.screen instanceof RoadDeleteScreen || ClientRoads.conflictPreview ? 0xFF5D45 : 0x3FDCDD),
              105,
              LightTexture.FULL_BRIGHT,
              false,
              true);
        }
      if(ClientRoads.preview.settings().style()==Style.RAILBED)
        for(var part:RailbedScreen.structures)for(var face:part.faces()){
          var v=face.points();for(int i=1;i+1<v.size();i++)triangle(pose,c,v.get(0),v.get(i),v.get(i+1),0x3FDCDD,105,LightTexture.FULL_BRIGHT,false,true);
        }
      for(var mesh:previews)if(!savedPreview(mesh)&&mesh.settings().options().sidewalk().enabled()) {
        var walk=mesh.settings().options().sidewalk();
        if(walk.smooth()){
          for(var part:com.sora.splineroads.core.RoadSidewalks.parts(mesh,walk))if(part.material()!=com.sora.splineroads.core.RoadStructures.Material.TACTILE){var v=part.base().stream().map(p->p.add(new V(0,part.height()+.015,0))).toList();triangle(pose,c,v.get(0),v.get(1),v.get(2),walkColor,105,LightTexture.FULL_BRIGHT,false,true);triangle(pose,c,v.get(0),v.get(2),v.get(3),walkColor,105,LightTexture.FULL_BRIGHT,false,true);}
        }else for(var block:com.sora.splineroads.core.RoadSidewalks.cells(mesh,walk)) {
          V a=new V(block.x(),block.y()+1.03,block.z()),b=a.add(new V(1,0,0)),d=a.add(new V(0,0,1)),e=a.add(new V(1,0,1));
          triangle(pose,c,a,d,e,walkColor,105,LightTexture.FULL_BRIGHT,false,true);
          triangle(pose,c,a,e,b,walkColor,105,LightTexture.FULL_BRIGHT,false,true);
        }
      }
      if (ClientRoads.preview == JunctionScreen.previewMesh)
        for (var face : JunctionScreen.previewFaces) {
          var vertices=face.points();
          for(int i=1;i<vertices.size()-1;i++)
            triangle(pose,c,vertices.get(0).add(new V(0,.035,0)),vertices.get(i).add(new V(0,.035,0)),
                vertices.get(i+1).add(new V(0,.035,0)),face.color(),205,LightTexture.FULL_BRIGHT,false,true);
        }
      if (!savedPreview(ClientRoads.preview) && !ClientRoads.conflictPreview && !(mc.screen instanceof RoadDeleteScreen) && ClientRoads.nodePreviews.size() < 3 && ClientRoads.preview.settings().style()!=Style.TOLL && ClientRoads.preview.settings().style()!=Style.RAILBED)
        for (var face : previewFurniture(ClientRoads.preview)) {
          var vertices = face.points();
          for (int i = 1; i < vertices.size() - 1; i++)
            triangle(
                pose,
                c,
                vertices.get(0),
                vertices.get(i),
                vertices.get(i + 1),
                face.color(),
                155,
                LightTexture.FULL_BRIGHT,
                false,
                true);
        }
      if (!conflict && !(mc.screen instanceof RoadDeleteScreen) && !ClientRoads.preview.settings().laneRamp()) for(V control:ClientRoads.preview.controls()) {
        V point = control.add(new V(0, .12, 0));
        int color=0xCD7BFF;
        var marker =
            new com.sora.splineroads.core.RoadStructures.Part(point, point, .45, .45, true);
        for (var face : marker.faces()) {
          var vertices = face.points();
          triangle(
              pose,
              c,
              vertices.get(0),
              vertices.get(1),
              vertices.get(2),
              color,
              230,
              LightTexture.FULL_BRIGHT,
              false,
              true);
          triangle(
              pose,
              c,
              vertices.get(0),
              vertices.get(2),
              vertices.get(3),
              color,
              230,
              LightTexture.FULL_BRIGHT,
              false,
              true);
        }
      }
  }

  @SubscribeEvent
  public static void debug(
      net.minecraftforge.client.event.CustomizeGuiOverlayEvent.DebugText event) {
    if (Minecraft.getInstance().level == null || !com.sora.splineroads.config.RoadClientConfig.PERF_OVERLAY.get()) return;
    event.getLeft().add(SmartSignClient.metrics());
    event.getLeft().add(uploadMetrics());
    event.getLeft().add(ClientRoads.cacheMetrics());
    event
        .getLeft()
        .add(
            String.format(
                Locale.ROOT,
                "SR: %d regions (%d far), %d draws, %,d vertices",
                visible.size(),
                lastDistant,
                lastDraws,
                lastVertices));
    event
        .getLeft()
        .add(
            String.format(
                Locale.ROOT,
                "SR CPU: %.2f ms, %d uploads, %d road meshes queued",
                averageCpuMs,
                lastUploads,
                dirty.size() + jobs.size()));
    event.getLeft().add(RoadGpuTimer.metrics());
    event.getLeft().add("SR surface: "+RoadTerrainModels.status());
    event.getLeft().add("SR inactive VBO cache: "+(retainedBytes/1048576)+" MiB estimated");
  }

  private static void draw(
      List<GpuSection> visible,
      RenderType type,
      RoadSurface.Texture texture,
      RenderLevelStageEvent event) {drawLayer(visible,type,texture,event,false);}
  private static void drawLayer(List<GpuSection> visible,RenderType type,RoadSurface.Texture texture,RenderLevelStageEvent event,boolean paint){
    drawLayer(visible,type,texture,event.getPoseStack(),event.getProjectionMatrix(),paint);
  }
  private static void drawLayer(List<GpuSection> visible,RenderType type,RoadSurface.Texture texture,PoseStack pose,Matrix4f projection,boolean paint){
    if (visible.isEmpty()) return;
    layerVisible.clear();
    for(var s:visible){var list=paint?s.paint:texture==null?s.pavement:(s.distant?s.far:s.near).get(texture);if(drawable(s,list))layerVisible.add(s);}
    if(layerVisible.isEmpty())return;
    type.setupRenderState();
    ShaderInstance shader = RenderSystem.getShader();
    setupShader(shader,pose,projection);
    for (var s : layerVisible) {
      var buffers = paint?s.paint:texture == null ? s.pavement : (s.distant ? s.far : s.near).get(texture);
      if (shader.MODEL_VIEW_MATRIX != null) {
        shader.MODEL_VIEW_MATRIX.set(s.transform);
        shader.MODEL_VIEW_MATRIX.upload();lastMatrixUploads++;
      }
      for(var buffer:buffers)drawOwned(s,buffer);
    }
    shader.clear();
    VertexBuffer.unbind();
    type.clearRenderState();
  }

  private static boolean drawable(GpuSection section,List<VertexBuffer> buffers){
    if(buffers==null)return false;
    for(var buffer:buffers)if(drawSelection(section,buffer).indices!=0)return true;
    return false;
  }

  /** One physical ownership query per changed terrain section, shared by all materials. */
  private static RoadDrawSelection drawSelection(GpuSection section,VertexBuffer buffer){
    long activity=ClientRoads.visibilityRevision(),terrain=RoadTerrainHandoff.ownershipRevision();
    boolean changed=section.drawRevision!=section.revision;
    // A global revision is only a hint to recheck this region's dependencies.
    // Metadata edits and uploads elsewhere do not change these index ranges.
    if(!changed&&section.drawActivityRevision!=activity)for(var entry:section.drawActiveOwners.entrySet())
      if(entry.getValue()!=ClientRoads.active(entry.getKey())){changed=true;break;}
    if(!changed&&section.drawTerrainRevision!=terrain)for(var entry:section.terrainOwners.entrySet()){
      drawOwnershipQueries++;
      // Keep the physical allocation validation, including native disposal and
      // replacement identity; a ledger receipt alone cannot hide the VBO.
      if(!entry.getValue().equals(RoadTerrainHandoff.visibleOwners(entry.getKey()))){changed=true;break;}
    }
    if(changed){
      section.clearDrawSelection();section.drawRevision=section.revision;
    }
    section.drawActivityRevision=activity;section.drawTerrainRevision=terrain;
    var saved=section.drawSelections.get(buffer);if(saved!=null)return saved;
    var ranges=section.owners.get(buffer);if(ranges==null||ranges.isEmpty())return RoadDrawSelection.EMPTY;
    var selection=new RoadDrawSelection.Builder();drawSelectionBuilds++;
    for(var range:ranges){
      drawSelectionRanges++;
      boolean live=section.retained.contains(range.owner)||section.pieces.containsKey(range.owner)
          &&section.drawActiveOwners.computeIfAbsent(range.owner,ClientRoads::active);
      if(live&&range.terrain!=null){
        var owners=section.terrainOwners.get(range.terrain);
        if(owners==null){drawOwnershipQueries++;owners=RoadTerrainHandoff.visibleOwners(range.terrain);section.terrainOwners.put(range.terrain,owners);}
        live=!owners.contains(range.owner);
      }
      selection.add(range.firstIndex,range.indexCount,live,range.terrain!=null);
    }
    saved=selection.build();section.drawSelections.put(buffer,saved);return saved;
  }

  /** Both the ordinary world pass and renderer validation use these material submissions. */
  static void drawPrepared(List<?> sections,PoseStack pose,Matrix4f projection,boolean surface){
    @SuppressWarnings("unchecked") var list=(List<GpuSection>)sections;
    if(surface){prepareAsphaltTexture();drawLayer(list,SURFACE,null,pose,projection,false);drawLayer(list,MARKING,null,pose,projection,true);}
    long materials=0;
    for(var s:list)materials|=s.distant?s.farMaterials:s.nearMaterials;
    if((materials&materialBit(RoadSurface.Texture.PLAIN))!=0)drawLayer(list,MARKING,RoadSurface.Texture.PLAIN,pose,projection,false);
    for(var e:MATERIALS.entrySet())if(e.getKey()!=RoadSurface.Texture.NOISE_BARRIER_GLASS&&(materials&materialBit(e.getKey()))!=0)drawLayer(list,e.getValue(),e.getKey(),pose,projection,false);
  }
  private static long materialBit(RoadSurface.Texture texture){return 1L<<texture.ordinal();}
  /** Deleted, replaced and cold owners are excluded without discarding neighbouring geometry. */
  private static void drawOwned(GpuSection section,VertexBuffer buffer){
    var selection=drawSelection(section,buffer);if(selection.indices==0)return;
    lastVertices+=selection.indices*2L/3;lastSurfaceIndices+=selection.surfaceIndices;buffer.bind();
    if(selection.all){buffer.draw();lastDraws++;return;}
    var indexType=RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS).type();
    int indexBytes=indexType==VertexFormat.IndexType.SHORT?2:4;
    for(int i=0;i<selection.spans.length;i+=2){
      org.lwjgl.opengl.GL11.glDrawElements(org.lwjgl.opengl.GL11.GL_TRIANGLES,selection.spans[i+1],indexType.asGLType,(long)selection.spans[i]*indexBytes);lastDraws++;
    }
  }
  private static boolean drawOwner(GpuSection section,OwnerRange range){
    return (section.retained.contains(range.owner)||section.pieces.containsKey(range.owner)&&ClientRoads.active(range.owner))
        &&(range.terrain==null||!RoadTerrainHandoff.owns(range.terrain,range.owner));
  }

  /** Shared 1.20.1 shader state is uploaded once per material, not once per city region. */
  private static void setupShader(ShaderInstance shader, RenderLevelStageEvent event) {setupShader(shader,event.getPoseStack(),event.getProjectionMatrix());}
  private static final String[] SAMPLER_NAMES={"Sampler0","Sampler1","Sampler2","Sampler3","Sampler4","Sampler5","Sampler6","Sampler7","Sampler8","Sampler9","Sampler10","Sampler11"};
  static void setupShader(ShaderInstance shader,PoseStack pose,Matrix4f projection) {
    for (int i = 0; i < SAMPLER_NAMES.length; i++) shader.setSampler(SAMPLER_NAMES[i], RenderSystem.getShaderTexture(i));
    if (shader.MODEL_VIEW_MATRIX != null)
      shader.MODEL_VIEW_MATRIX.set(pose.last().pose());
    if (shader.PROJECTION_MATRIX != null) shader.PROJECTION_MATRIX.set(projection);
    if (shader.INVERSE_VIEW_ROTATION_MATRIX != null)
      shader.INVERSE_VIEW_ROTATION_MATRIX.set(RenderSystem.getInverseViewRotationMatrix());
    if (shader.COLOR_MODULATOR != null) shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
    if (shader.GLINT_ALPHA != null) shader.GLINT_ALPHA.set(RenderSystem.getShaderGlintAlpha());
    if (shader.FOG_START != null) shader.FOG_START.set(RenderSystem.getShaderFogStart());
    if (shader.FOG_END != null) shader.FOG_END.set(RenderSystem.getShaderFogEnd());
    if (shader.FOG_COLOR != null) shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
    if (shader.FOG_SHAPE != null) shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
    if (shader.TEXTURE_MATRIX != null) shader.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
    if (shader.GAME_TIME != null) shader.GAME_TIME.set(RenderSystem.getShaderGameTime());
    if (shader.SCREEN_SIZE != null) {
      var window = Minecraft.getInstance().getWindow();
      shader.SCREEN_SIZE.set((float) window.getWidth(), (float) window.getHeight());
    }
    RenderSystem.setupShaderLights(shader);
    shader.apply();
  }

  private static AABB bounds(Piece piece) {
    double x0 = Double.POSITIVE_INFINITY, y0 = x0, z0 = x0, x1 = -x0, y1 = -x0, z1 = -x0;
    for (var list : List.of(piece.pavement(), piece.detail(), piece.distant()))
      for (var face : list)
        for (V p : face.points()) {
          x0 = Math.min(x0, p.x());
          y0 = Math.min(y0, p.y());
          z0 = Math.min(z0, p.z());
          x1 = Math.max(x1, p.x());
          y1 = Math.max(y1, p.y());
          z1 = Math.max(z1, p.z());
        }
    return new AABB(x0, y0, z0, x1, y1, z1).inflate(.1);
  }

  /** Complete a snapshot even if more windows arrive; publish newer revisions in the next upload. */
  private static int uploadVisible(){
    int uploads=0;
    // Stable regions must make bounded progress even while another region's
    // packing/upload spans many frames. The current upload is checked on its
    // next stable pass, so polling cannot invalidate its own staged snapshot.
    pollVisibleLight();
    if(pendingUpload==null) {
      GpuSection candidate=null;double closest=Double.POSITIVE_INFINITY;boolean initial=false;
      var eye=new net.minecraft.world.phys.Vec3(cameraPosition.x(),cameraPosition.y(),cameraPosition.z());
      for(var s:visible){
        if(!s.dirty||!handoffReady(s)||!RoadTerrainModels.regionLoaded(s.key))continue;
        boolean first=s.uploadedBounds==null&&s.handoffs.isEmpty();
        double distance=s.bounds.distanceToSqr(eye);
        if((first&&!initial)||first==initial&&distance<closest){closest=distance;candidate=s;initial=first;}
      }
      if(candidate!=null)pendingUpload=new Upload(candidate,initial);
    }
    if(pendingUpload!=null){
      try {long before=packedFaces;uploadFrames++;
        // The upload owns its budget; scanning/sorting cannot consume its first step.
        long now=System.nanoTime(),budget=com.sora.splineroads.config.RoadClientConfig.VBO_UPLOAD_MICROS.get()*1000L;
        // Existing geometry remains drawn during a light-only refresh. Give
        // first surfaces and source edits the full budget; cap background
        // relighting briefly, then restore full progress for an older upload.
        if(pendingUpload.lightingOnly&&pendingUpload.section.revision==pendingUpload.revision
            &&now-pendingUpload.started<150_000_000L)budget=Math.min(budget,300_000L);
        uploads=pendingUpload.step(now+budget);
        if(before==packedFaces&&uploads==0&&!pendingUpload.complete)uploadZeroFrames++;
        if(pendingUpload.complete)pendingUpload=null;}
      catch(RuntimeException failure){pendingUpload.cancel();pendingUpload=null;ClientRoads.error="道路网格上传失败："+failure.getMessage();}
    }
    return uploads;
  }

  /** CPU packing yields between faces; the previous VBO stays live until every material is ready. */
  private static final class Upload {
    final GpuSection section;final long revision;final long started=System.nanoTime();final boolean surfaceOnly;
    final boolean reusePavement,reuseDetails,lightingOnly;
    final long sharedDetailMaterials;
    final List<Prepared> pieces;final List<UUID> owners;
    final List<OwnerRange> ranges=new ArrayList<>();
    final GpuSection staged;
    final UploadLighting lighting;
    final PoseStack pose=new PoseStack();
    final List<Batch> batches=new ArrayList<>();
    BufferBuilder builder;
    BufferBuilder.RenderedBuffer ready;
    int batchIndex,pieceIndex,faceIndex;
    long vertices;
    boolean complete;
    record Batch(int kind,RoadSurface.Texture texture){}
    Upload(GpuSection s,boolean initial){
      // Publish a new region's road surface before packing roadside furniture.
      surfaceOnly=initial&&s.pieces.values().stream().anyMatch(p->!p.piece.pavement().isEmpty())
          &&s.pieces.values().stream().anyMatch(p->!p.near.isEmpty()||!p.far.isEmpty());
      section=s;revision=s.revision;owners=s.pieces.keySet().stream().sorted().toList();pieces=owners.stream().map(s.pieces::get).toList();staged=new GpuSection(s.key);
      long shared=-1L;for(var piece:pieces)shared&=piece.sharedDetailMaterials;sharedDetailMaterials=shared;
      boolean sameSources=s.uploadedBounds!=null&&s.handoffs.isEmpty()&&s.uploadedSources.size()==owners.size()
          && owners.stream().allMatch(id->Objects.equals(s.uploadedSources.get(id),s.pieces.get(id).identity));
      lightingOnly=sameSources&&!s.pavementOnly&&(s.surfaceLightDirty||s.detailLightDirty);
      reusePavement=sameSources&&(s.pavementOnly||!s.surfaceLightDirty&&s.detailLightDirty);
      reuseDetails=sameSources&&!s.pavementOnly&&s.surfaceLightDirty&&!s.detailLightDirty;
      if(reusePavement){
        staged.surfaceReads=s.surfaceReads;
        staged.lighting.putAll(s.lighting);
      }else{
        batches.add(new Batch(0,null));batches.add(new Batch(3,RoadSurface.Texture.PLAIN));
      }
      if(reuseDetails){staged.detailReads=s.detailReads;staged.lighting.putAll(s.lighting);}
      lighting=new UploadLighting(staged,s);
      for(var piece:pieces)staged.bounds=staged.bounds==null?piece.bounds:staged.bounds.minmax(piece.bounds);
      if(!surfaceOnly&&!reuseDetails)for(int kind:new int[]{1,2})for(var texture:RoadSurface.Texture.values()){
        if(kind==2&&(sharedDetailMaterials&materialBit(texture))!=0)continue;
        boolean present=false;for(var p:pieces)if((kind==1?p.near:p.far).containsKey(texture)){present=true;break;}
        if(present)batches.add(new Batch(kind,texture));
      }
    }
    List<RoadSurface.Face> faces(Prepared p,Batch b){return b.kind==0?p.piece.pavement():b.kind==3?p.paint:(b.kind==1?p.near:p.far).getOrDefault(b.texture,List.of());}
    int step(long deadline){
      cameraPosition=section.key.origin();int uploads=0,packed=0;
      lighting.beginFrame();
      while(batchIndex<batches.size()){
        if(packed>0&&System.nanoTime()>=deadline)return uploads;
        Batch batch=batches.get(batchIndex);
        lighting.group(batch.kind==0||batch.kind==3);
        if(builder==null&&ready==null)builder=uploadArena.begin();
        if(ready==null){
        packing: while(pieceIndex<pieces.size()){
          var faces=faces(pieces.get(pieceIndex),batch);
          while(faceIndex<faces.size()){
            var f=faces.get(faceIndex);
            if(vertices>0&&vertices+RoadRenderMesh.vertexCount(f)>RoadUploadBudget.MAX_VERTICES)break packing;
            faceIndex++;packedFaces++;lighting.emit(f,batch.kind!=0,builder,pose);
            int first=(int)(vertices/4*6),count=(int)(RoadRenderMesh.vertexCount(f)/4*6);
            UUID owner=owners.get(pieceIndex);long source=pieces.get(pieceIndex).identity;
            var terrain=batch.kind==0||batch.kind==3||RoadTerrainMesh.walkSurface(f)?RoadSurfaceSections.owner(f):null;
            if(!ranges.isEmpty()&&ranges.get(ranges.size()-1).owner.equals(owner)&&Objects.equals(ranges.get(ranges.size()-1).terrain,terrain)){
              var previous=ranges.remove(ranges.size()-1);ranges.add(new OwnerRange(owner,source,previous.firstIndex,previous.indexCount+count,terrain));
            }else ranges.add(new OwnerRange(owner,source,first,count,terrain));
            vertices+=RoadRenderMesh.vertexCount(f);
            if((++packed&15)==0&&System.nanoTime()>=deadline)return uploads;
          }
          faceIndex=0;pieceIndex++;
        }
        ready=builder.end();builder=null;
        }
        VertexBuffer buffer=null;
        if(vertices>0){
          // Read the built draw state so optional extended vertex formats are
          // charged by their actual bytes, not the vanilla 36-byte estimate.
          if(!RoadUploadBudget.claimRoad(ready.drawState().bufferSize()))return uploads;
          buffer=new VertexBuffer(VertexBuffer.Usage.STATIC);
          try {buffer.bind();var uploading=ready;ready=null;buffer.upload(uploading);}
          catch(RuntimeException|Error failure){buffer.close();throw failure;}
          finally {VertexBuffer.unbind();}
          uploads++;
        } else {ready.release();ready=null;}
        if(buffer!=null){
          staged.owners.put(buffer,List.copyOf(ranges));
          if(batch.kind==0){staged.pavement.add(buffer);staged.nearVertices+=vertices;staged.farVertices+=vertices;staged.surfaceVertices+=vertices;}
          else if(batch.kind==3){staged.paint.add(buffer);staged.nearVertices+=vertices;staged.farVertices+=vertices;staged.surfaceVertices+=vertices;}
          else if(batch.kind==1){staged.near.computeIfAbsent(batch.texture,k->new ArrayList<>()).add(buffer);staged.nearVertices+=vertices;
            if((sharedDetailMaterials&materialBit(batch.texture))!=0){staged.far.computeIfAbsent(batch.texture,k->new ArrayList<>()).add(buffer);staged.farVertices+=vertices;staged.sharedDetailVertices+=vertices;}}
          else {staged.far.computeIfAbsent(batch.texture,k->new ArrayList<>()).add(buffer);staged.farVertices+=vertices;}
        }
        vertices=0;ranges.clear();
        if(pieceIndex==pieces.size()){pieceIndex=faceIndex=0;batchIndex++;}
        // Multiple small buffers may progress within the same bounded frame budget.
        if(uploads>=4&&batchIndex<batches.size())return uploads;
      }
      if(!handoffReady(section))return uploads;
      if(reusePavement){
        pavementReuse++;
        staged.pavement.addAll(section.pavement);
        staged.paint.addAll(section.paint);
        for(var buffer:staged.pavement)staged.owners.put(buffer,section.owners.get(buffer));
        for(var buffer:staged.paint)staged.owners.put(buffer,section.owners.get(buffer));
        section.pavement.clear();
        section.paint.clear();
        staged.surfaceVertices=section.surfaceVertices;
        staged.nearVertices+=staged.surfaceVertices;
        staged.farVertices+=staged.surfaceVertices;
      }
      if(reuseDetails){
        detailReuse++;
        staged.near.putAll(section.near);staged.far.putAll(section.far);
        for(var buffers:section.near.values())for(var buffer:buffers)staged.owners.put(buffer,section.owners.get(buffer));
        for(var buffers:section.far.values())for(var buffer:buffers)staged.owners.put(buffer,section.owners.get(buffer));
        staged.nearVertices+=section.nearVertices-section.surfaceVertices;
        staged.farVertices+=section.farVertices-section.surfaceVertices;
        staged.sharedDetailVertices=section.sharedDetailVertices;
        section.near.clear();section.far.clear();
      }
      if(!reusePavement)staged.surfaceReads=lighting.surfaceReads.build();
      if(!reuseDetails)staged.detailReads=lighting.detailReads.build();
      section.close();section.pavement.addAll(staged.pavement);section.paint.addAll(staged.paint);section.near.putAll(staged.near);section.far.putAll(staged.far);
      section.pavementOnly=surfaceOnly;
      for(var texture:section.near.keySet())section.nearMaterials|=materialBit(texture);
      for(var texture:section.far.keySet())section.farMaterials|=materialBit(texture);
      section.owners.putAll(staged.owners);
      for(int i=0;i<owners.size();i++)section.uploadedSources.put(owners.get(i),pieces.get(i).identity);
      section.nearVertices=staged.nearVertices;section.farVertices=staged.farVertices;section.surfaceVertices=staged.surfaceVertices;
      section.sharedDetailVertices=staged.sharedDetailVertices;
      section.lighting.clear();section.lighting.putAll(staged.lighting);
      // Merge each distinct dependency once, rather than recording every
      // lighting read into both its layer and the aggregate during packing.
      section.lightReads=RoadLightReadSet.union(staged.surfaceReads,staged.detailReads);
      section.surfaceReads=staged.surfaceReads;section.detailReads=staged.detailReads;
      section.uploadedBounds=staged.bounds;updateBounds(section);
      section.dirty=surfaceOnly||section.revision!=revision;section.checkedLightAt=Minecraft.getInstance().level.getGameTime()-Math.floorMod(section.key.hashCode(),20);
      completedHandoff(section);
      if(section.pieces.isEmpty()){regions.remove(section.key,section);dormant.remove(section.key,section);}
      else residencyDirty.add(section.key);
      complete=true;return uploads;
    }
    void cancel(){
      uploadCancels++;
      if(builder!=null&&builder.building())builder.end().release();
      if(ready!=null){ready.release();ready=null;}
      builder=null;staged.close();
    }
  }

  static String uploadMetrics(){var p=pendingUpload;long lightBytes=0;for(var map:List.of(regions,dormant))for(var section:map.values())lightBytes+=section.lightReads.bytes()+section.surfaceReads.bytes()+section.detailReads.bytes();return "upload frames="+uploadFrames+" zero="+uploadZeroFrames+" cancel="+uploadCancels+" packed="+packedFaces+" pavementReuse="+pavementReuse+" detailReuse="+detailReuse+" lightChecks="+lightReadChecks+" lightPositions="+lightReadPositions+" lightUnchanged="+lightReadUnchanged+" lightHashes="+lightHashes+" lightBytes="+lightBytes+" candidateChecks="+buildCandidateChecks+" "+RoadUploadBudget.metrics()+(p==null?" idle":" pendingMs="+((System.nanoTime()-p.started)/1000000)+" batch="+p.batchIndex+" face="+p.faceIndex);}
  private static Mesh furniturePreview;
  private static final Map<Mesh,com.sora.splineroads.world.RoadRecord> previewRecords=new IdentityHashMap<>();
  private static long previewContextRevision,furnitureContextRevision=Long.MIN_VALUE;
  /** Publish the editor's resolved candidates in one step. A split fragment's
   * empty support list is authoritative, and cannot be reconstructed from Mesh. */
  static void previewCandidates(Collection<com.sora.splineroads.world.RoadRecord> candidates){
    var next=new IdentityHashMap<Mesh,com.sora.splineroads.world.RoadRecord>();
    for(var record:candidates)next.put(record.mesh(),record);
    boolean same=next.size()==previewRecords.size();
    if(same)for(var entry:next.entrySet())if(!Objects.equals(previewRecords.get(entry.getKey()),entry.getValue())){same=false;break;}
    if(same)return;
    previewRecords.clear();previewRecords.putAll(next);previewContextRevision++;
  }
  private static boolean savedPreview(Mesh mesh){
    var candidate=previewRecords.get(mesh);
    var built=candidate==null?null:ClientRoads.INDEX.roads.get(candidate.id());
    return built!=null&&built.record==candidate&&built.mesh==mesh;
  }
  private static RoadPreviewGround.Sources furnitureGround;
  private static long furnitureRevision = Long.MIN_VALUE;
  private static List<RoadSurface.Face> furnitureFaces = List.of();
  private static String furnitureFailure="";

  static void previewFurnitureStatus(Mesh mesh){
    if(mesh!=furniturePreview||furnitureRevision!=ClientRoads.INDEX.revision()
        ||furnitureContextRevision!=previewContextRevision)return;
    if(Minecraft.getInstance().screen instanceof RoadScreen screen)screen.furnitureStatus(furnitureFailure);
  }

  private static void clearPreviewFurniture(){
    furniturePreview=null;furnitureGround=null;furnitureRevision=furnitureContextRevision=Long.MIN_VALUE;furnitureFaces=List.of();furnitureFailure="";
  }
  private static List<RoadSurface.Face> previewFurniture(Mesh mesh) {
    var level = Minecraft.getInstance().level;
    long revision = ClientRoads.INDEX.revision();
    boolean same=furniturePreview==mesh&&furnitureRevision==revision&&furnitureContextRevision==previewContextRevision
        &&furnitureGround!=null&&furnitureGround.sameLevel(level);
    if(same&&furnitureGround.current(level)){previewFurnitureStatus(mesh);return furnitureFaces;}
    var retained=same?furnitureGround:null;
    furniturePreview = mesh;
    furnitureRevision = revision;
    furnitureContextRevision=previewContextRevision;
    furnitureGround = null;
    furnitureFailure="";
    if (!com.sora.splineroads.core.RoadProfile.modern(mesh.settings().style()))
      return furnitureFaces = List.of();
    UUID edited=ClientRoads.draft!=null&&ClientRoads.draft.hasUUID("Id")?ClientRoads.draft.getUUID("Id"):null;
    if(edited==null&&ClientRoads.draft!=null&&ClientRoads.draft.getString("Kind").equals("node")){
      var attached=ClientRoads.draft.getList("Attached",net.minecraft.nbt.Tag.TAG_COMPOUND);
      // RoadScreen appends node meshes in Attached order. Resolve this exact
      // candidate's identity, rather than treating every attached preview as the
      // first road and then colliding with its own saved shafts/spacing entries.
      int selected=-1;
      for(int i=0;i<ClientRoads.nodePreviews.size();i++)if(ClientRoads.nodePreviews.get(i)==mesh){selected=i;break;}
      if(selected>=0&&selected<attached.size()){
        var road=attached.getCompound(selected).getCompound("Road");
        if(road.hasUUID("Id"))edited=road.getUUID("Id");
      }
    }
    var candidate=previewRecords.get(mesh);
    if(candidate!=null)edited=candidate.id();
    var fixed=candidate==null||candidate.fragment()==null?null:candidate.fragment().supports();
    var reference=candidate==null?null:candidate.terrainClassificationMesh();
    var ground = RoadPreviewGround.create(level,mesh,edited,ClientRoads.INDEX.roads.values(),retained,fixed,reference,candidate);
    try {
      var phase = com.sora.splineroads.core.RoadFurniture.Phase.DEFAULT;
      var draft = ClientRoads.draft;
      if(candidate!=null){
        var resolved=candidate.furniturePhase(com.sora.splineroads.world.FurnitureSpacing.resolve(candidate,mesh,ClientRoads.INDEX.roads.values()));
        phase=com.sora.splineroads.world.FurnitureSpacing.onMesh(resolved,mesh);
      }else if (draft != null && draft.contains("A") && draft.contains("B")) {
        var start = new Node(mesh.first().center(),
            com.sora.splineroads.core.RoadPlanner.yaw(mesh.first().left().left().mul(-1)), 0);
        var end = new Node(mesh.last().center(),
            com.sora.splineroads.core.RoadPlanner.yaw(mesh.last().left().left().mul(-1)), 0);
        if (draft.contains("StartNode")) start = com.sora.splineroads.world.RoadRecord.readNode(draft.getCompound("StartNode"));
        if (draft.contains("EndNode")) end = com.sora.splineroads.world.RoadRecord.readNode(draft.getCompound("EndNode"));
        var record = new com.sora.splineroads.world.RoadRecord(
            draft.hasUUID("Id") ? draft.getUUID("Id") : new UUID(0, 0), new UUID(0, 0),
            BlockPos.of(draft.getLong("A")), BlockPos.of(draft.getLong("B")), start, end, mesh.settings());
        record = record.furniturePhase(com.sora.splineroads.world.FurnitureSpacing.resolve(
            record, mesh, ClientRoads.INDEX.roads.values()));
        phase = com.sora.splineroads.world.FurnitureSpacing.onMesh(record, mesh);
      }
      var parts =
          com.sora.splineroads.core.RoadStructures.plan(
              mesh,
              ground, phase);
      List<RoadSurface.Face> faces = new ArrayList<>();
      for (var part : RoadSignVisibility.parts(mesh,parts)) faces.addAll(part.faces());
      return furnitureFaces = List.copyOf(faces);
    } catch (IllegalArgumentException e) {
      furnitureFaces = List.of();
      furnitureFailure = ground.incomplete()?"正在等待桥墩基础附近地形加载…":"设施预览无效：" + e.getMessage();
      return furnitureFaces;
    } finally {
      // Keep actual column results across equal terrain reloads and same-key
      // replans, without retaining nearby meshes or strong chunk references.
      furnitureGround = ground.sources();
      previewFurnitureStatus(mesh);
    }
  }

  /** Shared final GPU submission. Solid undersides retain their authored outward winding. */
  static void emitLitFace(RoadSurface.Face face,boolean detail,int[] lights,BufferBuilder builder,PoseStack pose){
    var p=face.points();if(p.size()<3)return;
    if(!face.uv().isEmpty()){texturedFace(pose,builder,face,lights);return;}
    if(p.size()==4&&face.texture()!=RoadSurface.Texture.PLAIN){furnitureFace(pose,builder,face,lights);return;}
    V n=RoadLighting.normal(face);
    if(p.size()==4){for(int i=0;i<4;i++)vertex(pose,builder,p.get(i),face.color(),255,lights[i],detail,(float)n.x(),(float)n.y(),(float)n.z(),face.texture());}
    else for(int i=1;i<p.size()-1;i++)for(int k:new int[]{0,i,i+1,i+1})
      vertex(pose,builder,p.get(k),face.color(),255,lights[k],detail,(float)n.x(),(float)n.y(),(float)n.z(),face.texture());
  }

  private static void triangle(
      PoseStack pose,
      VertexConsumer consumer,
      V a,
      V b,
      V c,
      int color,
      int alpha,
      int light,
      boolean marking,
      boolean up) {
    quad(pose, consumer, a, b, c, c, color, alpha, light, marking, up, RoadSurface.Texture.PLAIN);
  }

  private static void triangle(
      PoseStack pose,
      VertexConsumer consumer,
      V a,
      V b,
      V c,
      int color,
      int alpha,
      int light,
      boolean marking,
      boolean up,
      RoadSurface.Texture texture) {
    quad(pose, consumer, a, b, c, c, color, alpha, light, marking, up, texture);
  }

  private static void quad(
      PoseStack pose,
      VertexConsumer consumer,
      V a,
      V b,
      V c,
      V d,
      int color,
      int alpha,
      int light,
      boolean marking,
      boolean up,
      RoadSurface.Texture texture) {
    V ab = b.sub(a), ac = c.sub(a);
    double nx = ab.y() * ac.z() - ab.z() * ac.y(),
        ny = ab.z() * ac.x() - ab.x() * ac.z(),
        nz = ab.x() * ac.y() - ab.y() * ac.x(),
        length = Math.sqrt(nx * nx + ny * ny + nz * nz);
    if (length < 1e-10) {
      // A clipped quad may start with three collinear vertices. Preserve its remaining triangle.
      if (!c.equals(d))
        triangle(pose, consumer, a, c, d, color, alpha, light, marking, up, texture);
      return;
    }
    double sign = up && ny < 0 ? -1 : 1;
    if (sign < 0) {
      V swap = b;
      b = d;
      d = swap;
    } // outward winding permits real back-face culling
    float x = (float) (nx * sign / length),
        y = (float) (ny * sign / length),
        z = (float) (nz * sign / length);
    vertex(pose, consumer, a, color, alpha, light, marking, x, y, z, texture);
    vertex(pose, consumer, b, color, alpha, light, marking, x, y, z, texture);
    vertex(pose, consumer, c, color, alpha, light, marking, x, y, z, texture);
    vertex(pose, consumer, d, color, alpha, light, marking, x, y, z, texture);
  }

  /** Uploads are short-lived and usually touch few light sections. Keep their
   * original growing byte map; the bounded stamped arrays belong to the
   * persistent frame poller. Block/chunk lookup remains shared and unchanged. */
  private static final class UploadLightAccess {
    private final FrameLightAccess blocks=new FrameLightAccess();
    private final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap sections=new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
    private long lastSection;
    private boolean haveSection,lastAvailable;
    void beginFrame(net.minecraft.client.multiplayer.ClientLevel level){blocks.beginFrame(level);sections.clear();haveSection=false;}
    boolean loaded(BlockPos pos){return blocks.loaded(pos);}
    net.minecraft.world.level.block.state.BlockState state(BlockPos pos){return blocks.state(pos);}
    boolean lit(BlockPos pos){
      int x=pos.getX()>>4,y=pos.getY()>>4,z=pos.getZ()>>4;
      long key=net.minecraft.core.SectionPos.asLong(x,y,z);
      if(haveSection&&lastSection==key)return lastAvailable;
      byte known=sections.get(key);
      if(known==0){known=(byte)(blocks.level.getLightEngine().lightOnInSection(net.minecraft.core.SectionPos.of(x,y,z))?2:1);sections.put(key,known);}
      haveSection=true;lastSection=key;lastAvailable=known==2;
      return lastAvailable;
    }
  }

  private static final class UploadLighting implements RoadLighting.Access {
    private final net.minecraft.client.multiplayer.ClientLevel level=Minecraft.getInstance().level;
    private final GpuSection section;
    private final BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
    private static final int OPACITY_CACHE_SIZE=1024;
    private final long[] opacityKeys=new long[OPACITY_CACHE_SIZE];
    private final byte[] opacityValues=new byte[OPACITY_CACHE_SIZE];
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap light=new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private int[] vertexLights=new int[4];
    private final RoadLighting.SurfaceCache surfaceLight=new RoadLighting.SurfaceCache();
    private final UploadLightAccess availability=new UploadLightAccess();
    final RoadLightReadSet.Builder surfaceReads,detailReads;
    private RoadLightReadSet.Builder groupReads;
    private int groupMask;
    private boolean haveLastOpaque,haveLastPacked,lastOpaque;
    private long lastOpaqueKey,lastPackedKey;
    private int lastPacked;
    UploadLighting(GpuSection section,GpuSection previous){
      this.section=section;light.defaultReturnValue(-1);
      surfaceReads=new RoadLightReadSet.Builder(previous.surfaceReads);detailReads=new RoadLightReadSet.Builder(previous.detailReads);
    }
    void group(boolean surface){
      var next=surface?surfaceReads:detailReads;
      groupMask=surface?1<<24:1<<25;
      if(groupReads!=next){groupReads=next;haveLastOpaque=haveLastPacked=false;Arrays.fill(opacityValues,(byte)0);surfaceLight.clear();}
    }
    void beginFrame(){availability.beginFrame(level);haveLastOpaque=false;Arrays.fill(opacityValues,(byte)0);surfaceLight.clear();}
    @Override public int surface(V vertex,V normal,double reach){return surfaceLight.sample(vertex,normal,reach,this);}
    void emit(RoadSurface.Face face,boolean detail,BufferBuilder builder,PoseStack pose){
      int vertices=face.points().size();
      if(vertices<3)return;
      if(vertices>vertexLights.length)vertexLights=new int[vertices];
      if(face.emissive())Arrays.fill(vertexLights,0,vertices,LightTexture.FULL_BRIGHT);
      else{
        V normal=RoadLighting.normal(face),center=RoadLighting.center(face);
        for(int i=0;i<vertices;i++)vertexLights[i]=RoadLighting.sample(face,face.points().get(i),this,normal,center);
      }
      emitLitFace(face,detail,vertexLights,builder,pose);
    }
    @Override public boolean opaque(int x,int y,int z){
      long key=BlockPos.asLong(x,y,z);
      if(haveLastOpaque&&lastOpaqueKey==key)return lastOpaque;
      haveLastOpaque=true;lastOpaqueKey=key;
      int slot=(int)it.unimi.dsi.fastutil.HashCommon.mix(key)&(OPACITY_CACHE_SIZE-1);
      int known=opacityValues[slot];
      if(known!=0&&opacityKeys[slot]==key)return lastOpaque=known==2;
      pos.set(x,y,z);recordSectionLight();
      boolean available=availability.loaded(pos);
      boolean opaque=groupReads.opaque(key,available&&availability.state(pos).getLightBlock(level,pos)>=15,available);
      opacityKeys[slot]=key;opacityValues[slot]=(byte)(opaque?2:1);return lastOpaque=opaque;
    }
    @Override public int packed(int x,int y,int z){
      long key=BlockPos.asLong(x,y,z);
      if(haveLastPacked&&lastPackedKey==key)return lastPacked;
      haveLastPacked=true;lastPackedKey=key;int known=light.get(key);
      if(known>=0){
        int packed=known&RoadLightReadSet.PACKED_MASK;
        // The upload's packed cache is immutable per position. Each layer
        // only needs to record its first use, even across several frames.
        if((known&groupMask)==0){groupReads.packed(key,packed);light.put(key,known|groupMask);}
        return lastPacked=packed;
      }
      pos.set(x,y,z);recordSectionLight();
      boolean available=availability.loaded(pos)&&availability.lit(pos);
      int packed=groupReads.packed(key,LevelRenderer.getLightColor(level,pos),available);
      light.put(key,packed|groupMask);return lastPacked=packed;
    }
    private void recordSectionLight(){
      if(LIGHT_DIAGNOSTICS)section.lighting.computeIfAbsent(net.minecraft.core.SectionPos.of(pos),RoadRenderer::lightPair);
    }
  }

  private static V normal(RoadSurface.Face face) {
    var p = face.points(); V a = p.get(1).sub(p.get(0)), b = p.get(2).sub(p.get(0));
    V n = new V(a.y()*b.z()-a.z()*b.y(), a.z()*b.x()-a.x()*b.z(), a.x()*b.y()-a.y()*b.x());
    double length = Math.sqrt(n.dot(n));
    return length < 1e-10 ? new V(0, 1, 0) : n.mul(1 / length);
  }

  private static void furnitureFace(PoseStack pose, VertexConsumer builder,
      RoadSurface.Face face, int[] lights) {
    V n = normal(face);
    for (int i = 0; i < 4; i++)
      vertex(pose, builder, face.points().get(i), face.color(), 255, lights[i], true,
          (float)n.x(), (float)n.y(), (float)n.z(), face.texture());
  }

  private static void texturedFace(PoseStack pose, VertexConsumer builder,
      RoadSurface.Face face, int[] lights) {
    V n=normal(face);int count=face.points().size();
    if(count==4)for(int i=0;i<4;i++)texturedVertex(pose,builder,face,lights,n,i);
    else for(int i=1;i<count-1;i++)for(int j:new int[]{0,i,i+1,i+1})texturedVertex(pose,builder,face,lights,n,j);
  }
  private static void texturedVertex(PoseStack pose,VertexConsumer builder,RoadSurface.Face face,int[] lights,V n,int i){
    V p=face.points().get(i);var uv=face.uv().get(i);
    RoadVertex.emit(pose,builder,(float)(p.x()-cameraPosition.x()),(float)(p.y()-cameraPosition.y()),(float)(p.z()-cameraPosition.z()),
        face.color(),255,(float)uv.u(),(float)uv.v(),lights[i],(float)n.x(),(float)n.y(),(float)n.z());
  }

  private static void vertex(
      PoseStack pose,
      VertexConsumer c,
      V p,
      int color,
      int alpha,
      int light,
      boolean marking,
      float nx,
      float ny,
      float nz,
      RoadSurface.Texture texture) {
    float u, v;
    if (texture != RoadSurface.Texture.PLAIN) {
      double px = p.x() - Math.floor(cameraPosition.x()),
          py = p.y() - Math.floor(cameraPosition.y()),
          pz = p.z() - Math.floor(cameraPosition.z());
      if (Math.abs(ny) >= Math.max(Math.abs(nx), Math.abs(nz))) {
        u = (float) px;
        v = (float) pz;
      } else if (Math.abs(nx) > Math.abs(nz)) {
        u = (float) pz;
        v = (float) py;
      } else {
        u = (float) px;
        v = (float) py;
      }
    } else {
      // The white marking texture also needs a non-degenerate UV tangent for
      // extended shader vertices; its colour is independent of these UVs.
      u = (float) (p.x() / 4 - Math.floor(cameraPosition.x() / 4));
      v = (float) (p.z() / 4 - Math.floor(cameraPosition.z() / 4));
    }
    RoadVertex.emit(pose,c,(float)(p.x()-cameraPosition.x()),(float)(p.y()-cameraPosition.y()),
        (float)(p.z()-cameraPosition.z()),color,alpha,u,v,light,nx,ny,nz);
  }

  private RoadRenderer() {}
}
