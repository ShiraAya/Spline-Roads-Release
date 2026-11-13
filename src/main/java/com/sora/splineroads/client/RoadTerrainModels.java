package com.sora.splineroads.client;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.config.RoadClientConfig;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadTerrainMesh.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Terrain models consume immutable snapshots. Streaming is per road/chunk, never a road
 * geometry invalidation. Active tiles live with loaded chunks; unloaded tiles use bounded LRU.
 */
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
public final class RoadTerrainModels {
  private static final ModelProperty<List<BakedQuad>> QUADS=new ModelProperty<>();
  private static final ModelProperty<List<BakedQuad>> COVERS=new ModelProperty<>();
  private static final ModelProperty<Integer> FILL_FACES=new ModelProperty<>();
  private static final ModelProperty<Double> BLOCK_BOTTOM=new ModelProperty<>();
  private static final ChunkRenderTypeSet SOLID=ChunkRenderTypeSet.of(RenderType.solid());
  private static final Map<Section,Map<Integer,ModelData>> snapshots=new ConcurrentHashMap<>();
  // Only snapshots/enabled/assets are read outside the client thread.
  private static final Map<Section,Map<UUID,Map<Integer,List<BakedQuad>>>> parts=new HashMap<>();
  private static final Map<UUID,Set<Section>> published=new HashMap<>();
  private static final Set<Section> pending=new LinkedHashSet<>();
  private static final Map<UUID,Source> sources=new HashMap<>();
  private static final Map<Chunk,Set<UUID>> chunkRoads=new HashMap<>();
  private static final Map<Tile,Prepared> active=new HashMap<>();
  private static final LinkedHashMap<Tile,Prepared> cache=new LinkedHashMap<>(16,.75f,true);
  private static final Set<Tile> waiting=new LinkedHashSet<>();
  private static final Set<Tile> incompleteSources=new LinkedHashSet<>();
  private static final Map<Tile,TileJob> jobs=new LinkedHashMap<>();
  private static final Set<Chunk> streamDirty=new LinkedHashSet<>();
  private static final RoadHandoffCohorts<Chunk> cohorts=new RoadHandoffCohorts<>();
  private static final Set<Section> heldSections=new LinkedHashSet<>();
  private static final Map<Chunk,Set<UUID>> cohortCoverage=new HashMap<>();
  private static final Map<UUID,Set<Section>> transitionSections=new HashMap<>();
  private static final int CACHE_TILES=2048,MAX_JOBS=2;
  private static int cachedQuads,lastCacheMiB=-1;
  private static final Map<UUID,Long> tileBytes=new HashMap<>();
  private static long retentionRevision;
  static long retentionRevision(){return retentionRevision;}
  private static void account(Tile tile,long delta){if(delta!=0){tileBytes.merge(tile.road(),delta,Long::sum);if(tileBytes.get(tile.road())==0)tileBytes.remove(tile.road());retentionRevision++;}}
  private static Prepared removeActive(Tile tile){var old=active.remove(tile);if(old!=null)account(tile,-old.quads()*256L);return old;}
  private static Prepared removeCached(Tile tile){var old=cache.remove(tile);if(old!=null){cachedQuads-=old.quads();account(tile,-old.quads()*256L);}return old;}
  private static boolean sortWaiting;
  private static long preparedTiles,cacheHits,discardedTiles;
  private static long chunkEvents,roadChunkEvents;
  private static volatile Assets loadedAssets;
  private static volatile long resourceRevision;
  private static long observedResource=Long.MIN_VALUE;
  private static Assets observedAssets;
  private static volatile boolean enabled,requested;
  private static String shaderStatus="not queried";

  public record Assets(TextureAtlasSprite asphalt,TextureAtlasSprite paint,Map<RoadSurface.Texture,TextureAtlasSprite> walks) {
    public Assets(TextureAtlasSprite asphalt,TextureAtlasSprite paint){this(asphalt,paint,Map.of());}
  }
  public record Prepared(Map<Section,Map<Integer,List<BakedQuad>>> sections,int quads) {
    public static Prepared empty(){return new Prepared(Map.of(),0);}
  }
  private record Tile(UUID road,Chunk chunk) {}
  private record Source(RoadTerrainMesh.Source mesh,Assets assets,long bytes) {
    Source(RoadTerrainMesh.Source mesh,Assets assets){this(mesh,assets,RoadTerrainSourceWindows.bytes(mesh));}
  }
  private record TileJob(Source source,CompletableFuture<Prepared> future) {}
  private static double distance(Chunk chunk) {
    var p=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
    double dx=chunk.x()*16.0+8-p.x,dz=chunk.z()*16.0+8-p.z;return dx*dx+dz*dz;
  }
  private static boolean loaded(Chunk chunk) {
    var level=Minecraft.getInstance().level;
    // ClientLevel.hasChunk is always true, including absent cache entries.
    // A non-loading cache lookup distinguishes streamed-out chunks from data
    // that can still receive terrain publication and participate in handoff.
    return level!=null&&level.getChunkSource().getChunk(chunk.x(),chunk.z(),
        net.minecraft.world.level.chunk.ChunkStatus.FULL,false)!=null;
  }
  static boolean regionLoaded(com.sora.splineroads.core.RoadRenderMesh.Section region){
    for(int x=0;x<4;x++)for(int z=0;z<4;z++)if(loaded(new Chunk(region.x()*4+x,region.z()*4+z)))return true;
    return false;
  }
  @SubscribeEvent public static void wrap(ModelEvent.ModifyBakingResult event) {
    event.getModels().replaceAll((id,original)->{
      if(!(id instanceof ModelResourceLocation location)||location.getVariant().equals("inventory"))return original;
      // A saved road can cross retained or player-replaced ordinary blocks.
      // They must emit the same per-cell road publication as SR proxies;
      // otherwise completeCells rejects the entire native section indefinitely.
      if(!id.getNamespace().equals(SplineRoads.ID)){
        var terrain=new TerrainModel(original,true);
        return RoadFabricModels.wrap(original,terrain,data->data.get(QUADS),RoadTerrainModels::exposedFill,
            data->data.get(QUADS)!=null&&!data.get(QUADS).isEmpty()||data.get(COVERS)!=null&&!data.get(COVERS).isEmpty()||data.get(BLOCK_BOTTOM)!=null&&data.get(BLOCK_BOTTOM)<0);
      }
      String name=id.getPath();
      if(name.equals("road_block")||name.equals("road_slab"))return new TerrainModel(new RoadAsphaltModel(original),true);
      if(!Set.of("road_collision","road_infill","road_node").contains(name))return original;
      return new TerrainModel(original,name.equals("road_collision"));
    });
  }
  @SubscribeEvent public static void baked(ModelEvent.BakingCompleted event) {
    SmartSignClient.resourceReloaded();RoadRenderer.asphaltReloaded();RoadShaders.texturesReloaded();
    var atlas=event.getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
    var walks=new EnumMap<RoadSurface.Texture,TextureAtlasSprite>(RoadSurface.Texture.class);
    for(var f:RoadSidewalks.Finish.values())walks.put(RoadSurface.Texture.valueOf("WALK_"+f.name()),atlas.getSprite(ResourceLocation.fromNamespaceAndPath("minecraft","block/"+f.id.substring(10))));
    walks.put(RoadSurface.Texture.WALK_TACTILE,atlas.getSprite(ResourceLocation.fromNamespaceAndPath(SplineRoads.ID,"block/terrain_paint")));
    var next=new Assets(atlas.getSprite(ResourceLocation.fromNamespaceAndPath(SplineRoads.ID,"block/terrain_asphalt")),
        atlas.getSprite(ResourceLocation.fromNamespaceAndPath(SplineRoads.ID,"block/terrain_paint")),Map.copyOf(walks));
    var old=loadedAssets;loadedAssets=next;
    if(old==null||old.asphalt()!=next.asphalt()||old.paint()!=next.paint())resourceRevision++;
  }
  /** Called on the ordinary world pass only. API reports false on disabled/failed shaders. */
  public static void beginFrame() {
    var shader=ShaderPackState.current();
    if(shader.shadowPass())return;
    shaderStatus=shader.active()?"shader active":"shader off";
    var choice=RoadClientConfig.SURFACE_BACKEND.get();
    boolean wanted=loadedAssets!=null&&(choice==RoadClientConfig.SurfaceBackend.TERRAIN||
        choice==RoadClientConfig.SurfaceBackend.AUTO&&shader.active());
    requested=wanted;
    boolean initialized=observedResource!=Long.MIN_VALUE,changed=observedResource!=resourceRevision,toggle=enabled!=wanted;
    observedResource=resourceRevision;RoadRenderer.shaderMode(ShaderPackState.extendedVertices(shader));
    if(lastCacheMiB!=RoadClientConfig.TERRAIN_CACHE_MIB.get())trimCache();
    Set<Section> dirty=initialized&&(changed||toggle)?new HashSet<>(snapshots.keySet()):Set.of();
    RoadTerrainHandoff.frame(wanted);
    if(changed){RoadTerrainHandoff.invalidateSources();reloadAtlas();observedAssets=loadedAssets;}enabled=wanted;
    if(toggle&&!wanted)suspend();
    if(wanted&&(toggle||changed))for(var e:chunkRoads.entrySet())if(loaded(e.getKey()))for(UUID road:e.getValue())request(new Tile(road,e.getKey()));
    // Only road-bearing sections need rebaking. Do not initiate another global
    // world rebuild on top of the shader pack's own resource reload.
    if(Minecraft.getInstance().level!=null)for(var section:dirty)
      Minecraft.getInstance().levelRenderer.setSectionDirty(section.x(),section.y(),section.z());
  }
  public static boolean enabled(){return enabled;}
  static boolean requested(){return requested;}
  public static Assets assets(){return enabled?loadedAssets:null;}
  private static void reloadAtlas(){
    jobs.values().forEach(j->j.future().cancel(false));jobs.clear();waiting.clear();
    // A new atlas object is not new road geometry. Rebind immutable quads and
    // transform their UVs if packing changed; retain the clipped tile and LRU.
    if(observedAssets!=null){
      active.replaceAll((tile,data)->rebind(data,observedAssets,loadedAssets));
      cache.replaceAll((tile,data)->rebind(data,observedAssets,loadedAssets));
    }
    pending.clear();published.clear();parts.clear();snapshots.clear();
    sources.replaceAll((road,source)->new Source(source.mesh(),loadedAssets,source.bytes()));
    active.forEach((tile,data)->data.sections().forEach((section,quads)->putPart(tile.road(),section,quads)));
    cache.forEach((tile,data)->{if(ClientRoads.active(tile.road())&&loaded(tile.chunk()))data.sections().forEach((section,quads)->putPart(tile.road(),section,quads));});
    // Resource reload already rebuilds chunk meshes. Publish the retained data
    // now, so that rebuild sees the current atlas, not empty incremental snapshots.
    pending.addAll(heldSections);
    for(var section:new ArrayList<>(pending))if(publishSection(section))pending.remove(section);
  }
  static Prepared rebind(Prepared data,Assets old,Assets next){
    var sprites=new IdentityHashMap<TextureAtlasSprite,TextureAtlasSprite>();sprites.put(old.asphalt(),next.asphalt());sprites.put(old.paint(),next.paint());
    old.walks().forEach((texture,sprite)->sprites.put(sprite,next.walks().getOrDefault(texture,next.paint())));
    Map<Section,Map<Integer,List<BakedQuad>>> sections=new LinkedHashMap<>();
    data.sections().forEach((section,cells)->{
      Map<Integer,List<BakedQuad>> replaced=new LinkedHashMap<>();
      cells.forEach((cell,quads)->replaced.put(cell,quads.stream().map(q->{
        var before=q.getSprite();var after=sprites.getOrDefault(before,next.asphalt());
        boolean sameUV=before.getU(0)==after.getU(0)&&before.getU(16)==after.getU(16)
            &&before.getV(0)==after.getV(0)&&before.getV(16)==after.getV(16);
        int[] v=sameUV?q.getVertices():q.getVertices().clone();
        if(!sameUV)for(int i=0;i<4;i++){
          double u=(Float.intBitsToFloat(v[i*8+4])-before.getU(0))/(before.getU(16)-before.getU(0));
          double t=(Float.intBitsToFloat(v[i*8+5])-before.getV(0))/(before.getV(16)-before.getV(0));
          v[i*8+4]=Float.floatToRawIntBits(after.getU(u*16));v[i*8+5]=Float.floatToRawIntBits(after.getV(t*16));
        }
        return new BakedQuad(v,q.getTintIndex(),q.getDirection(),after,q.isShade());
      }).toList()));sections.put(section,Collections.unmodifiableMap(replaced));
    });return new Prepared(Collections.unmodifiableMap(sections),data.quads());
  }
  private static void suspend(){
    waiting.clear();sortWaiting=false;var order=new ArrayList<>(active.keySet());order.sort(Comparator.comparingDouble((Tile t)->distance(t.chunk())).reversed());
    for(var t:order)remember(t,removeActive(t));
    // Keep visible snapshots for retained loaded tiles. enabled gates their use;
    // eviction, road edits and chunk unload still invalidate their contributions.
  }
  public static void clear(){
    var previousSections=new ArrayList<>(snapshots.keySet());
    jobs.values().forEach(job->job.future().cancel(false));jobs.clear();waiting.clear();
    sources.clear();chunkRoads.clear();active.clear();cache.clear();cachedQuads=0;tileBytes.clear();retentionRevision++;
    enabled=requested=false;observedResource=Long.MIN_VALUE;observedAssets=null;
    pending.clear();published.clear();parts.clear();snapshots.clear();streamDirty.clear();incompleteSources.clear();cohorts.clear();heldSections.clear();cohortCoverage.clear();transitionSections.clear();sortWaiting=false;
    RoadTerrainHandoff.clearSources();
    preparedTiles=cacheHits=discardedTiles=0;
    // A recovery reset can occur within the same level. Its old chunk meshes
    // must observe the cleared model state as well as the cleared client maps.
    var mc=Minecraft.getInstance();if(mc.level!=null)for(var section:previousSections)
      if(loaded(new Chunk(section.x(),section.z())))mc.levelRenderer.setSectionDirty(section.x(),section.y(),section.z());
  }
  /** Install a newly changed road's immutable face index. Atlas/road changes invalidate old tiles. */
  public static void install(UUID road,RoadTerrainMesh.Source mesh) {
    remove(road);
    if(mesh==null)return;
    sources.put(road,new Source(mesh,loadedAssets));retentionRevision++;
    for(var chunk:mesh.chunks().keySet()){
      chunkRoads.computeIfAbsent(chunk,key->new LinkedHashSet<>()).add(road);
      if(enabled&&loadedAssets!=null&&loaded(chunk))request(new Tile(road,chunk));
    }
  }
  /** Keep the old complete section snapshot during a split/merge; new UUID
   * coverage is assigned only by the eventual coherent uploaded replacement. */
  static void beginBatch(Collection<UUID> members,Collection<Section> oldVboSections){
    Set<Chunk> chunks=new LinkedHashSet<>();
    var previous=new HashSet<>(oldVboSections);
    for(UUID member:members)for(var section:published.getOrDefault(member,Set.of())){
      previous.add(section);
    }
    for(var section:previous)if(loaded(new Chunk(section.x(),section.z()))){chunks.add(new Chunk(section.x(),section.z()));heldSections.add(section);}
    var all=cohorts.begin(members,chunks);
    for(UUID member:all)transitionSections.computeIfAbsent(member,key->new HashSet<>()).addAll(previous);
    rememberCoverage();
  }
  static void finishBatch(Collection<UUID> members){
    // All face windows now belong to this same CPU generation. Preparing their
    // loaded tiles remains asynchronous; ready() below waits for those tiles.
    for(UUID member:cohorts.members()){
      var source=sources.get(member);if(source==null)continue;
      for(var chunk:source.mesh().chunks().keySet())if(loaded(chunk))cohorts.include(member,chunk);
    }
    rememberCoverage();cohorts.installed(members);pending.addAll(heldSections);
    if(!requested)for(var key:cohorts.keys())cohorts.ready(key,(road,chunk)->true);
  }
  private static void rememberCoverage(){
    for(var key:cohorts.keys())cohortCoverage.merge(key,cohorts.members(key),(a,b)->{var ids=new HashSet<>(a);ids.addAll(b);return Set.copyOf(ids);});
    transitionSections.forEach((road,sections)->{for(var section:sections){var key=new Chunk(section.x(),section.z());
      var ids=new HashSet<>(cohortCoverage.getOrDefault(key,Set.of()));ids.add(road);cohortCoverage.put(key,Set.copyOf(ids));}});
  }
  static boolean transitionReady(com.sora.splineroads.core.RoadRenderMesh.Section region,Set<UUID> members){
    var sections=new HashSet<Section>();for(UUID member:members)sections.addAll(transitionSections.getOrDefault(member,Set.of()));
    for(var key:cohorts.keys())if(Math.floorDiv(key.x(),4)==region.x()&&Math.floorDiv(key.z(),4)==region.z()
        &&loaded(key)&&!Collections.disjoint(cohorts.members(key),members))return false;
    for(var section:sections)if(Math.floorDiv(section.x(),4)==region.x()&&Math.floorDiv(section.z(),4)==region.z()
        &&loaded(new Chunk(section.x(),section.z()))){
      if(pending.contains(section)||heldSections.contains(section))return false;
      // Once the complete CPU generation is published, a section with no old
      // terrain coverage can use the new full VBO until its first native upload.
      // Loaded offscreen sections may never receive that first upload. Existing
      // replacement aliases still hide VBO ranges, so compare vboOwners, not
      // only the physical owners of the old terrain allocation.
      var resident=RoadTerrainHandoff.STATE.resident(section);
      if(resident!=null&&!Collections.disjoint(resident.vboOwners(),members)
          &&!RoadTerrainHandoff.currentUpload(section,members,enabled))return false;
    }
    return true;
  }
  static void endBatch(Collection<UUID> members){
    for(UUID member:members)transitionSections.remove(member);
    cohortCoverage.clear();rememberCoverage();
  }
  private static boolean coherent(Section section){
    var chunk=new Chunk(section.x(),section.z());
    // In VBO mode every rebuild is explicitly empty of road quads. Physical
    // terrain ownership still changes only after that rebuild is uploaded.
    if(!requested||!loaded(chunk)){cohorts.ready(chunk,(road,key)->true);return true;}
    return cohorts.ready(chunk,(road,key)->{
      var source=sources.get(road);
      return source==null||!ClientRoads.active(road)||!source.mesh().chunks().containsKey(key)||active.containsKey(new Tile(road,key));
    });
  }
  /** Append one exact work window; invalidate only tiles whose face lists changed. */
  static void installWindow(UUID road,RoadTerrainMesh.Source mesh,boolean first){
    if(first)remove(road);var previous=sources.get(road);var chunks=new LinkedHashMap<Chunk,RoadSurface.Geometry>();if(previous!=null)chunks.putAll(previous.mesh().chunks());
    mesh.chunks().forEach((chunk,geometry)->chunks.merge(chunk,geometry,(a,b)->{var pavement=new ArrayList<>(a.pavement());pavement.addAll(b.pavement());var paint=new ArrayList<>(a.markings());paint.addAll(b.markings());return new RoadSurface.Geometry(List.copyOf(pavement),List.copyOf(paint));}));
    for(var chunk:mesh.chunks().keySet()){var tile=new Tile(road,chunk);unload(tile);removeCached(tile);}
    sources.put(road,new Source(new RoadTerrainMesh.Source(Collections.unmodifiableMap(chunks)),loadedAssets));retentionRevision++;
    for(var chunk:mesh.chunks().keySet()){chunkRoads.computeIfAbsent(chunk,k->new LinkedHashSet<>()).add(road);if(enabled&&loadedAssets!=null&&loaded(chunk))request(new Tile(road,chunk));}
    // A neighbouring window can complete a tile without contributing another face
    // to it, so also retry that road's earlier deferred tiles.
    if(enabled)for(var tile:new ArrayList<>(incompleteSources))if(tile.road().equals(road)&&loaded(tile.chunk()))request(tile);
  }
  /** Accept a complete immutable worker publication without copying all prior
   * road windows or rescanning their faces on the client/render thread. */
  static void installPublication(UUID road,RoadTerrainSourceWindows.Publication publication,boolean first){
    if(first)remove(road);
    var mesh=publication.source();var changed=first?mesh.chunks().keySet():publication.changed();
    for(var chunk:changed){var tile=new Tile(road,chunk);unload(tile);removeCached(tile);
      if(!mesh.chunks().containsKey(chunk)){var ids=chunkRoads.get(chunk);if(ids!=null){ids.remove(road);if(ids.isEmpty())chunkRoads.remove(chunk);}}}
    sources.put(road,new Source(mesh,loadedAssets,publication.bytes()));retentionRevision++;
    for(var chunk:changed)if(mesh.chunks().containsKey(chunk)){
      chunkRoads.computeIfAbsent(chunk,key->new LinkedHashSet<>()).add(road);
      if(enabled&&loadedAssets!=null&&loaded(chunk))request(new Tile(road,chunk));
    }
    if(enabled)for(var tile:new ArrayList<>(incompleteSources))if(tile.road().equals(road)&&loaded(tile.chunk()))request(tile);
  }
  static long retainedBytes(UUID road){
    var source=sources.get(road);return (source==null?0:source.bytes())+tileBytes.getOrDefault(road,0L);
  }
  private static void request(Tile tile){
    if(!ClientRoads.active(tile.road()))return;
    if(!RoadRenderer.terrainTileReady(tile.road(),tile.chunk())){incompleteSources.add(tile);return;}
    incompleteSources.remove(tile);
    if(active.containsKey(tile)||jobs.containsKey(tile)||waiting.contains(tile))return;
    Prepared retained=removeCached(tile);
    if(retained!=null){cacheHits++;activate(tile,retained);return;}
    waiting.add(tile);sortWaiting=true;
  }
  /** Compatibility/testing hook for an already prepared full-road publication. */
  public static void submit(UUID road,Prepared geometry) {
    remove(road);geometry.sections().forEach((section,data)->putPart(road,section,data));
  }
  private static void putPart(UUID road,Section section,Map<Integer,List<BakedQuad>> data){

    if(parts.computeIfAbsent(section,key->new HashMap<>()).put(road,data)==data)return;
    if(loaded(new Chunk(section.x(),section.z()))){var chunk=new Chunk(section.x(),section.z());cohorts.include(road,chunk);
      var ids=cohorts.members(chunk);if(!ids.isEmpty()){cohortCoverage.merge(chunk,ids,(a,b)->{var all=new HashSet<>(a);all.addAll(b);return Set.copyOf(all);});
        for(UUID member:ids){var changed=transitionSections.computeIfAbsent(member,key->new HashSet<>());changed.add(section);changed.add(below(section));}}}
    published.computeIfAbsent(road,key->new HashSet<>()).add(section);pending.add(section);pending.add(below(section));
  }
  private static void dropPart(UUID road,Section section){
    var bucket=parts.get(section);if(bucket!=null){bucket.remove(road);if(bucket.isEmpty())parts.remove(section);}
    var keys=published.get(road);if(keys!=null){keys.remove(section);if(keys.isEmpty())published.remove(road);}
    // Atomically publish the surviving union. Removing one road must not erase
    // every other contributor until a later frame. Deleted geometry is never retained.
    if(publishSection(section))pending.remove(section);else pending.add(section);
    pending.add(below(section));
  }
  public static void suspend(UUID road){
    var source=sources.get(road);if(source==null)return;
    for(var chunk:source.mesh().chunks().keySet())unload(new Tile(road,chunk));
  }



  public static void resume(UUID road){
    var source=sources.get(road);if(source==null||!enabled||loadedAssets==null)return;
    for(var chunk:source.mesh().chunks().keySet())if(loaded(chunk))request(new Tile(road,chunk));
  }
  public static void remove(UUID road){
    var source=sources.remove(road);retentionRevision++;
    if(source!=null)for(var chunk:source.mesh().chunks().keySet()){
      var ids=chunkRoads.get(chunk);if(ids!=null){ids.remove(road);if(ids.isEmpty())chunkRoads.remove(chunk);}
    }
    waiting.removeIf(tile->tile.road().equals(road));
    incompleteSources.removeIf(tile->tile.road().equals(road));
    for(var it=jobs.entrySet().iterator();it.hasNext();){var e=it.next();if(e.getKey().road().equals(road)){e.getValue().future().cancel(false);it.remove();}}
    for(var it=cache.entrySet().iterator();it.hasNext();){var e=it.next();if(e.getKey().road().equals(road)){cachedQuads-=e.getValue().quads();account(e.getKey(),-e.getValue().quads()*256L);it.remove();}}
    for(var tile:new ArrayList<>(active.keySet()))if(tile.road().equals(road))removeActive(tile);
    for(var section:new ArrayList<>(published.getOrDefault(road,Set.of())))dropPart(road,section);
  }
  private static void activate(Tile tile,Prepared data){
    var old=active.put(tile,data);account(tile,(data.quads()-(old==null?0:old.quads()))*256L);data.sections().forEach((section,quads)->putPart(tile.road(),section,quads));
  }
  private static void unload(Tile tile){
    waiting.remove(tile);var job=jobs.remove(tile);if(job!=null)job.future().cancel(false);
    var data=removeActive(tile);if(data==null){
      var retained=cache.get(tile);if(retained!=null)for(var section:retained.sections().keySet()){dropPart(tile.road(),section);}
      return;
    }
    for(var section:data.sections().keySet()){dropPart(tile.road(),section);}
    remember(tile,data);
  }
  private static void remember(Tile tile,Prepared data){var old=cache.put(tile,data);if(old!=null)cachedQuads-=old.quads();cachedQuads+=data.quads();account(tile,(data.quads()-(old==null?0:old.quads()))*256L);trimCache();}
  private static void trimCache(){
    lastCacheMiB=RoadClientConfig.TERRAIN_CACHE_MIB.get();long limit=lastCacheMiB*1048576L/256L;
    var it=cache.entrySet().iterator();while(it.hasNext()&&(cache.size()>CACHE_TILES||cachedQuads>limit)){
      var e=it.next();cachedQuads-=e.getValue().quads();account(e.getKey(),-e.getValue().quads()*256L);it.remove();
      if(!active.containsKey(e.getKey()))for(var section:e.getValue().sections().keySet()){
        dropPart(e.getKey().road(),section);
      }
    }
  }
  /** Chunk lifecycle events are coalesced; do NOT mark road geometry or infrastructure dirty. */
  static void chunkChanged(Chunk chunk){
    chunkEvents++;
    boolean road=chunkRoads.containsKey(chunk);if(road)roadChunkEvents++;
    if(road||RoadRenderer.hasPendingHandoffs())streamDirty.add(chunk);
  }
  private static void stream(){
    if(streamDirty.isEmpty()&&jobs.isEmpty()&&waiting.isEmpty())return;
    long deadline=System.nanoTime()+500_000;
    for(int n=0;n<16&&!streamDirty.isEmpty()&&(n==0||System.nanoTime()<deadline);n++){
      var it=streamDirty.iterator();var chunk=it.next();it.remove();boolean present=loaded(chunk);
      if(!present)RoadRenderer.chunkUnloaded(chunk);
      for(UUID road:chunkRoads.getOrDefault(chunk,Set.of())){
        var tile=new Tile(road,chunk);if(present&&enabled)request(tile);else unload(tile);
      }
    }
    int consumed=0;
    for(var it=jobs.entrySet().iterator();it.hasNext()&&consumed<2;){
      var entry=it.next();var job=entry.getValue();if(!job.future().isDone())continue;it.remove();consumed++;
      var tile=entry.getKey();
      var current=sources.get(tile.road());if(current==null||current.assets()!=job.source().assets()||current.mesh().chunks().get(tile.chunk())!=job.source().mesh().chunks().get(tile.chunk())){discardedTiles++;continue;}
      try{var data=job.future().join();if(enabled&&loaded(tile.chunk()))activate(tile,data);else remember(tile,data);preparedTiles++;}
      catch(CancellationException ignored){discardedTiles++;}
      catch(CompletionException failure){ClientRoads.error="道路 terrain 分块构建失败："+failure.getCause();}
    }
    if(sortWaiting&&!waiting.isEmpty()){
      var order=new ArrayList<>(waiting);order.sort(Comparator.comparingDouble(tile->distance(tile.chunk())));
      waiting.clear();waiting.addAll(order);sortWaiting=false;
    }
    for(var it=waiting.iterator();it.hasNext()&&jobs.size()<MAX_JOBS;){
      Tile tile=it.next();it.remove();Source source=sources.get(tile.road());
      if(source==null||source.assets()==null||!enabled||!loaded(tile.chunk()))continue;
      var geometry=source.mesh().chunks().get(tile.chunk());if(geometry==null)continue;
      jobs.put(tile,new TileJob(source,CompletableFuture.supplyAsync(
          ()->prepare(geometry,source.assets(),Set.of(tile.chunk())),RoadRenderer.terrainExecutor())));
    }
  }
  /** Merge changed contributors once per section, even when many roads touch that section. */
  public static void publish() {
    var mc=Minecraft.getInstance();if(mc.level==null)return;
    stream();
    if(pending.isEmpty())return;
    int allowance=RoadClientConfig.TERRAIN_SECTIONS_PER_FRAME.get();long deadline=System.nanoTime()+900_000;
    int scans=pending.size();
    while(allowance>0&&scans-->0&&!pending.isEmpty()&&System.nanoTime()<deadline) {
      var it=pending.iterator();Section section=it.next();it.remove();
      if(publishSection(section))allowance--;else pending.add(section);
    }
  }
  private static boolean publishSection(Section section){
      if(!coherent(section)){heldSections.add(section);return false;}
      heldSections.remove(section);
      var mc=Minecraft.getInstance();var bucket=parts.get(section);boolean present=loaded(new Chunk(section.x(),section.z()));
      var data=present?publication(combined(section),combined(new Section(section.x(),section.y()+1,section.z()))):Map.<Integer,ModelData>of();
      if(data.isEmpty())snapshots.remove(section);else snapshots.put(section,data);
      RoadTerrainHandoff.publish(section,data,bucket==null?Set.of():bucket.keySet(),cohortCoverage.getOrDefault(new Chunk(section.x(),section.z()),Set.of()));
      if(present)mc.levelRenderer.setSectionDirty(section.x(),section.y(),section.z());
      return true;
  }
  private static Section below(Section section){return new Section(section.x(),section.y()-1,section.z());}
  private static Map<Integer,List<BakedQuad>> combined(Section section){
    var result=new HashMap<Integer,List<BakedQuad>>();var bucket=parts.get(section);
    if(bucket!=null)bucket.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(road->
      road.getValue().forEach((cell,quads)->result.computeIfAbsent(cell,key->new ArrayList<>()).addAll(quads)));
    return result;
  }
  /** A shallow deck can belong to the cell above the retained ground top. Include
   * clipping-only coverage in the same frozen publication as that ground model;
   * never emit the pavement twice or fetch mutable neighbouring worker data. */
  static Map<Integer,ModelData> publication(Map<Integer,List<BakedQuad>> own,Map<Integer,List<BakedQuad>> above){
    var covers=new HashMap<Integer,List<BakedQuad>>();
    own.forEach((cell,quads)->{if((cell>>8)>0)coverBelow(covers,cell-256,quads);});
    above.forEach((cell,quads)->{if((cell>>8)==0)coverBelow(covers,cell+3840,quads);});
    var keys=new HashSet<>(own.keySet());keys.addAll(covers.keySet());var result=new HashMap<Integer,ModelData>();
    for(int cell:keys)result.put(cell,ModelData.builder().with(QUADS,List.copyOf(own.getOrDefault(cell,List.of())))
      .with(COVERS,List.copyOf(covers.getOrDefault(cell,List.of()))).build());
    return Collections.unmodifiableMap(result);
  }
  private static void coverBelow(Map<Integer,List<BakedQuad>> covers,int cell,List<BakedQuad> quads){
    for(var q:quads){var v=q.getVertices().clone();boolean close=true;
      for(int i=0;i<4;i++){float y=Float.intBitsToFloat(v[i*8+1]);if(y<-.025||y>.025){close=false;break;}v[i*8+1]=Float.floatToRawIntBits(y+1);}
      if(close)covers.computeIfAbsent(cell,key->new ArrayList<>()).add(new BakedQuad(v,q.getTintIndex(),q.getDirection(),q.getSprite(),q.isShade(),q.hasAmbientOcclusion()));
    }
  }
  static boolean coverOnly(ModelData data){var cover=data.get(COVERS);var quads=data.get(QUADS);return cover!=null&&!cover.isEmpty()&&(quads==null||quads.isEmpty());}
  static List<BakedQuad> exposedFill(List<BakedQuad> base,ModelData data){return SidewalkBlockQuads.extend(exposedFill(exposedFill(base,data.get(COVERS)),data.get(QUADS)),data.get(BLOCK_BOTTOM));}
  public static String status(){return (enabled?"terrain":"VBO")+" ("+shaderStatus+"), "+snapshots.size()+" sections, "+
      (waiting.size()+jobs.size())+" terrain tiles queued, "+pending.size()+" publish, "+cache.size()+" cached, "+preparedTiles+" baked / "+cacheHits+" reused; "+RoadTerrainHandoff.status();}
  record Stats(int queued,int active,int cached,int cachedQuads,long baked,long hits,long discarded){}
  static Stats stats(){return new Stats(waiting.size()+jobs.size(),active.size(),cache.size(),cachedQuads,preparedTiles,cacheHits,discardedTiles);}

  /** All allocations/triangulation/atlas lookup happen per missing tile, off the render thread. */
  public static Prepared prepare(RoadSurface.Geometry geometry,Assets assets,Set<Chunk> loaded) {
    var mesh=RoadTerrainMesh.build(geometry,loaded);Map<Section,Map<Integer,List<BakedQuad>>> sections=new LinkedHashMap<>();int count=0;
    for(var entry:mesh.cells().entrySet()) {
      var cell=entry.getKey();List<BakedQuad> quads=new ArrayList<>();
      for(var polygon:entry.getValue()) {
        var v=cleanPolygon(polygon.vertices(),cell);
        if(v.size()==4)quads.add(quad(cell,polygon,List.of(v.get(0),v.get(1),v.get(2),v.get(3)),assets));
        else for(int i=1;i+1<v.size();i++)if(RoadTerrainMesh.area(List.of(v.get(0),v.get(i),v.get(i+1)))>1e-12)quads.add(quad(cell,polygon,List.of(v.get(0),v.get(i),v.get(i+1),v.get(i+1)),assets));
      }
      count+=quads.size();sections.computeIfAbsent(cell.section(),k->new LinkedHashMap<>()).put(cell.localKey(),List.copyOf(quads));
    }
    sections.replaceAll((key,value)->Collections.unmodifiableMap(value));
    return new Prepared(Collections.unmodifiableMap(sections),count);
  }
  private static List<RoadGeometry.V> cleanPolygon(List<RoadGeometry.V> input,Cell cell){
    var points=new ArrayList<>(input);
    boolean changed=true;while(changed&&points.size()>=3){changed=false;for(int i=0;i<points.size();i++){
      var a=points.get((i+points.size()-1)%points.size());var b=points.get(i);var c=points.get((i+1)%points.size());
      // Match the coordinates that are actually uploaded, after local float conversion.
      double ax=(float)(a.x()-cell.x()),az=(float)(a.z()-cell.z()),bx=(float)(b.x()-cell.x()),bz=(float)(b.z()-cell.z()),cx=(float)(c.x()-cell.x()),cz=(float)(c.z()-cell.z());
      if(Math.abs((bx-ax)*(cz-az)-(bz-az)*(cx-ax))<=1e-12){points.remove(i);changed=true;break;}
    }}return points;
  }
  private static BakedQuad quad(Cell cell,Polygon polygon,List<RoadGeometry.V> points,Assets assets) {
    boolean walk=polygon.texture()!=RoadSurface.Texture.PLAIN;var sprite=polygon.paint()?assets.paint():walk?assets.walks().getOrDefault(polygon.texture(),assets.paint()):assets.asphalt();int[] data=new int[32];
    var face=new RoadSurface.Face(points,polygon.color());var n=RoadLighting.normal(face);
    int normal=((byte)Math.round(n.x()*127)&255)|(((byte)Math.round(n.y()*127)&255)<<8)|(((byte)Math.round(n.z()*127)&255)<<16);
    int rgb=polygon.color(),abgr=0xff000000|((rgb&255)<<16)|(rgb&0xff00)|((rgb>>16)&255);
    double scale=walk?1:4,baseU=Math.floor(cell.x()/scale),baseV=Math.floor(cell.z()/scale);
    for(int i=0;i<4;i++) {
      var p=points.get(i);int j=i*8;
      // Tiny render-only lifts also prevent coplanar overlap with retained terrain infill. The owning
      // collision cell is computed from the original plane, so no air-cell model is required.
      data[j]=Float.floatToRawIntBits((float)(p.x()-cell.x()));
      data[j+1]=Float.floatToRawIntBits((float)(p.y()-cell.y()+(polygon.paint()?.003:.001)));
      data[j+2]=Float.floatToRawIntBits((float)(p.z()-cell.z()));data[j+3]=abgr;
      // White paint and tactile paving use a padded 16px sprite. Keep a consistent
      // planar derivative (8 texels/metre), including clipped triangles. Shader
      // packs that infer texture resolution round sub-texel derivatives to zero.
      boolean solid=polygon.paint()||polygon.texture()==RoadSurface.Texture.WALK_TACTILE;
      double u=solid?.25+.5*(p.x()-cell.x()):p.x()/scale-baseU;
      double v=solid?.25+.5*(p.z()-cell.z()):p.z()/scale-baseV;
      data[j+4]=Float.floatToRawIntBits(sprite.getU(Math.max(0,Math.min(1,u))*16));
      data[j+5]=Float.floatToRawIntBits(sprite.getV(Math.max(0,Math.min(1,v))*16));
      data[j+6]=0; // Vanilla/Forge applies actual per-vertex sky/block lighting, not fullbright.
      data[j+7]=normal;
    }
    return new BakedQuad(data,-1,Direction.UP,sprite,true);
  }
  private static List<RoadGeometry.V> vertices(BakedQuad q){
    var v=q.getVertices();var out=new ArrayList<RoadGeometry.V>(4);
    for(int i=0;i<4;i++)out.add(new RoadGeometry.V(Float.intBitsToFloat(v[i*8]),Float.intBitsToFloat(v[i*8+1]),Float.intBitsToFloat(v[i*8+2])));
    return out;
  }
  /** Retained terrain must remain outside the road, but must not also draw
   * underneath it. Clip only coplanar upward fill faces, preserving sidewalls. */
  static List<BakedQuad> exposedFill(List<BakedQuad> base,List<BakedQuad> roads){
    if(base.isEmpty()||roads==null||roads.isEmpty())return base;
    var out=new ArrayList<BakedQuad>();
    for(var q:base){
      if(q.getDirection()!=Direction.UP){out.add(q);continue;}
      var original=vertices(q);List<List<RoadGeometry.V>> pieces=List.of(original);
      for(var road:roads){
        var boundary=vertices(road);
        if(boundary.stream().anyMatch(p->Math.abs(p.y()-RoadTerrainMesh.height(original,p))>.025))continue;
        var next=new ArrayList<List<RoadGeometry.V>>();
        for(var piece:pieces)next.addAll(RoadSurface.subtract(piece,boundary));pieces=next;
        if(pieces.isEmpty())break;
      }
      if(pieces.size()==1&&pieces.get(0).equals(original)){out.add(q);continue;}
      for(var clipped:pieces){
        // XZ subtraction normalizes to CCW, which is downward in XYZ.
        // Restore the source face winding before Minecraft back-face culling.
        var piece=new ArrayList<>(clipped);
        if(RoadLighting.normal(new RoadSurface.Face(piece,0)).dot(RoadLighting.normal(new RoadSurface.Face(original,0)))<0)
          Collections.reverse(piece);
        for(int i=1;i+1<piece.size();i++){
        int[] packed=q.getVertices().clone();var triangle=List.of(piece.get(0),piece.get(i),piece.get(i+1),piece.get(i+1));
        for(int j=0;j<4;j++){
          var v=triangle.get(j);int k=j*8;
          packed[k]=Float.floatToRawIntBits((float)v.x());packed[k+1]=Float.floatToRawIntBits((float)v.y());packed[k+2]=Float.floatToRawIntBits((float)v.z());
          var a=original.get(0);var b=original.get(1).sub(a);var c=original.get(2).sub(a);var d=v.sub(a);
          double det=b.x()*c.z()-b.z()*c.x();
          if(Math.abs(det)>1e-12){
            double u=(d.x()*c.z()-d.z()*c.x())/det,t=(b.x()*d.z()-b.z()*d.x())/det;
            for(int component=4;component<=5;component++){
              var data=q.getVertices();double first=Float.intBitsToFloat(data[component]);
              packed[k+component]=Float.floatToRawIntBits((float)(first+u*(Float.intBitsToFloat(data[8+component])-first)+t*(Float.intBitsToFloat(data[16+component])-first)));
            }
          }
        }
        out.add(new BakedQuad(packed,q.getTintIndex(),q.getDirection(),q.getSprite(),q.isShade()));
      }
      }
    }return List.copyOf(out);
  }

  /** Do not bake the off-screen kilometres of a long road merely because one chunk is loaded. */
  public static Set<Chunk> loadedChunks(com.sora.splineroads.world.RoadIndex.Built road) {
    var level=Minecraft.getInstance().level;Set<Chunk> result=new HashSet<>();
    if(level!=null)for(long packed:road.chunks) {
      int x=(int)packed,z=(int)(packed>>32);
      var chunk=new Chunk(x,z);if(loaded(chunk))result.add(chunk);
    }
    return Set.copyOf(result);
  }
  @Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
  public static final class Streaming {
    @SubscribeEvent public static void loaded(net.minecraftforge.event.level.ChunkEvent.Load event){changed(event);}
    @SubscribeEvent public static void unloaded(net.minecraftforge.event.level.ChunkEvent.Unload event){changed(event);}
    private static void changed(net.minecraftforge.event.level.ChunkEvent event) {
      if(!(event.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel level))return;
      long packed=event.getChunk().getPos().toLong();var mc=Minecraft.getInstance();
      mc.execute(()->{if(mc.level==level){ClientRoads.chunkChanged(packed);chunkChanged(new Chunk((int)packed,(int)(packed>>32)));}});
    }
  }

  public static BakedModel markerModel(BakedModel model){model=RoadFabricModels.unwrap(model);return model instanceof TerrainModel terrain?terrain.baseModel:model;}

  private static final class TerrainModel extends BakedModelWrapper<BakedModel> {
    private final boolean originalVisible;
    private final BakedModel baseModel;
    TerrainModel(BakedModel original,boolean originalVisible){super(original);this.baseModel=original;this.originalVisible=originalVisible;}
    @Override public ModelData getModelData(BlockAndTintGetter level,BlockPos pos,BlockState state,ModelData input) {
      input=super.getModelData(level,pos,state,input);
      double bottom=com.sora.splineroads.world.SidewalkBlockContact.bottom(level,ClientRoads.INDEX,pos,state);
      if(bottom<0||input.get(BLOCK_BOTTOM)!=null)input=input.derive().with(BLOCK_BOTTOM,bottom).build();
      var fill=input.get(com.sora.splineroads.world.RoadFillEntity.MATERIAL);
      if(fill!=null){int visible=0;for(var direction:Direction.values()){var adjacent=pos.relative(direction);if(!level.getBlockState(adjacent).isSolidRender(level,adjacent))visible|=1<<direction.ordinal();}input=input.derive().with(RoadAsphaltModel.POSITION,pos.immutable()).with(FILL_FACES,visible).build();}
      var section=new Section(pos.getX()>>4,pos.getY()>>4,pos.getZ()>>4);
      int cell=(pos.getX()&15)|((pos.getZ()&15)<<4)|((pos.getY()&15)<<8);
      var map=RoadTerrainHandoff.model(section,cell);if(map==null)return withoutRoads(input);
      ModelData data=map.get(cell);
      if(data==null)return withoutRoads(input);
      // A sloping deck lies inside a sealed collision cell. Vanilla's inset
      // face lighting samples that cell's darkness, although the actual road
      // surface is exposed above it. Supply the exterior light, retaining real
      // bridge/tunnel shade and emissive blocks; never force full brightness.
      // A neighbouring half-height wall/roof can seal only part of that cell.
      // Sampling pos.above() for the entire quad makes its exposed pavement
      // black in shader packs which use the sky-light channel for ambient light.
      // Use the same visible-face samples as the independent road renderer.
      var samples=new HashMap<BlockPos,Integer>();
      var lighting=new RoadLighting.Access(){
        public boolean opaque(int x,int y,int z){var p=new BlockPos(x,y,z);return level.getBlockState(p).getLightBlock(level,p)>=15;}
        public int packed(int x,int y,int z){return samples.computeIfAbsent(new BlockPos(x,y,z),p->net.minecraft.client.renderer.LevelRenderer.getLightColor(level,p));}
      };
      var lit=new ArrayList<BakedQuad>();
      for(var quad:data.get(QUADS)){
        var vertices=quad.getVertices().clone();
        var points=vertices(quad).stream().map(p->p.add(new RoadGeometry.V(pos.getX(),pos.getY(),pos.getZ()))).toList();
        var face=new RoadSurface.Face(points,0);
        for(int vertex=0;vertex<4;vertex++)vertices[vertex*8+6]=RoadLighting.sample(face,points.get(vertex),lighting,true);
        // These are continuous road triangles, not the occluding proxy cube.
        // Proxy-cube AO makes the flat deck speckled and inset slopes black.
        lit.add(new BakedQuad(vertices,quad.getTintIndex(),quad.getDirection(),quad.getSprite(),quad.isShade(),false));
      }
      return input.derive().with(QUADS,List.copyOf(lit)).with(COVERS,data.get(COVERS)).build();
    }
    private static ModelData withoutRoads(ModelData input) {
      return input.get(QUADS)==null&&input.get(COVERS)==null?input:input.derive().with(QUADS,List.of()).with(COVERS,List.of()).build();
    }
    @Override public List<BakedQuad> getQuads(BlockState state,Direction side,RandomSource random) {
      return originalVisible?super.getQuads(state,side,random):List.of();
    }
    @Override public List<BakedQuad> getQuads(BlockState state,Direction side,RandomSource random,ModelData data,RenderType layer) {
      List<BakedQuad> base=originalVisible?super.getQuads(state,side,random,data,layer):List.of();
      var material=data.get(com.sora.splineroads.world.RoadFillEntity.MATERIAL);var cap=data.get(com.sora.splineroads.world.RoadFillEntity.CAP);
      if(material!=null&&cap!=null){
        // The retained material is a delegate, not another terrain carrier.
        // Passing this infill's ModelData into its wrapper would recurse.
        var model=markerModel(Minecraft.getInstance().getBlockRenderer().getBlockModel(material));
        // Weighted models consume the RNG while choosing their render layer.
        // Probing with the quad RNG selects a different texture rotation on each face.
        var pos=data.get(RoadAsphaltModel.POSITION);var layerRandom=RandomSource.create(material.getSeed(pos==null?BlockPos.ZERO:pos));
        base=layer==null||model.getRenderTypes(material,layerRandom,data).contains(layer)?capQuads(model.getQuads(material,side,random,data,layer),cap):List.of();
        var edge=data.get(com.sora.splineroads.world.RoadFillEntity.EDGE);
        var outline=data.get(com.sora.splineroads.world.RoadFillEntity.OUTLINE);
        if(edge!=null&&!edge.isEmpty()||outline!=null&&!outline.isEmpty()){
          base=List.of();
          if(side==null&&(layer==null||model.getRenderTypes(material,RandomSource.create(material.getSeed(pos==null?BlockPos.ZERO:pos)),data).contains(layer))){
            var faces=new ArrayList<BakedQuad>(model.getQuads(material,null,random,data,layer));
            for(var direction:Direction.values())faces.addAll(model.getQuads(material,direction,random,data,layer));
            base=outline!=null&&!outline.isEmpty()?outlineQuads(faces,outline,cap,data.get(FILL_FACES)==null?63:data.get(FILL_FACES)):edgeQuads(faces,edge,cap);
          }
        }
      }
      var roads=data.get(QUADS);
      base=exposedFill(base,data);
      if(side!=null||(layer!=null&&layer!=RenderType.solid())||roads==null||roads.isEmpty())return base;
      if(base.isEmpty())return roads;
      var fill=base;
      return new AbstractList<>() {public int size(){return fill.size()+roads.size();}
        public BakedQuad get(int i){return i<fill.size()?fill.get(i):roads.get(i-fill.size());}};
    }
    @Override public ChunkRenderTypeSet getRenderTypes(BlockState state,RandomSource random,ModelData data) {
      var fill=data.get(com.sora.splineroads.world.RoadFillEntity.MATERIAL);
      if(fill!=null)return ChunkRenderTypeSet.union(markerModel(Minecraft.getInstance().getBlockRenderer().getBlockModel(fill)).getRenderTypes(fill,random,ModelData.EMPTY),SOLID);
      if(!originalVisible)return SOLID;
      var base=super.getRenderTypes(state,random,data);var roads=data.get(QUADS);
      return roads==null||roads.isEmpty()||base.contains(RenderType.solid())?base:ChunkRenderTypeSet.union(base,SOLID);
    }
    @Override public boolean isCustomRenderer(){return originalVisible&&super.isCustomRenderer();}
    @Override public boolean useAmbientOcclusion(){return !originalVisible||super.useAmbientOcclusion();}
    @Override public boolean useAmbientOcclusion(BlockState state){return !originalVisible||super.useAmbientOcclusion(state);}
    @Override public boolean useAmbientOcclusion(BlockState state,RenderType layer){return !originalVisible||super.useAmbientOcclusion(state,layer);}
  }
  static List<BakedQuad> capQuads(List<BakedQuad> quads,double cap){
    return quads.stream().map(q->{int[] packed=q.getVertices().clone();for(int i=0;i<4;i++){int at=i*8+1;packed[at]=Float.floatToRawIntBits((float)(Float.intBitsToFloat(packed[at])*cap-(cap>=1?.002:0)));}return new BakedQuad(packed,q.getTintIndex(),q.getDirection(),q.getSprite(),q.isShade(),q.hasAmbientOcclusion());}).toList();
  }
  static List<BakedQuad> outlineQuads(List<BakedQuad> faces,List<List<RoadGeometry.V>> polygons,double cap,int visible){
    // Cube-corner AO is invalid for clipped triangle fans and creates dark sawteeth.
    // These partial terrain faces retain ordinary block light and directional shading.
    var out=new ArrayList<BakedQuad>();
    for(var tile:com.sora.splineroads.core.TerrainEdgeGeometry.faces(polygons))for(var face:faces){
      var direction=face.getDirection();int axis=direction.getAxis()==Direction.Axis.X?0:direction.getAxis()==Direction.Axis.Y?1:2;
      if(axis!=tile.axis()||(direction.getAxisDirection()==Direction.AxisDirection.POSITIVE)!=tile.positive())continue;
      if((visible&(1<<direction.ordinal()))==0&&tile.points().stream().allMatch(p->Math.abs((axis==0?p.x():axis==1?p.y():p.z())-(tile.positive()?1:0))<1e-8))continue;
      var original=vertices(face);var a=original.get(0);var b=original.get(1).sub(a);var c=original.get(2).sub(a);
      double bb=b.dot(b),cc=c.dot(c),bc=b.dot(c),det=bb*cc-bc*bc;if(Math.abs(det)<1e-12)continue;
      var points=tile.points();var triangles=new ArrayList<List<RoadGeometry.V>>();
      if(points.size()==4)triangles.add(points);else for(int i=1;i+1<points.size();i++)triangles.add(List.of(points.get(0),points.get(i),points.get(i+1),points.get(i+1)));
      for(var quad:triangles){var source=face.getVertices();var packed=source.clone();
        var normal=RoadLighting.normal(new RoadSurface.Face(quad,0));int packedNormal=((byte)Math.round(normal.x()*127)&255)|(((byte)Math.round(normal.y()*127)&255)<<8)|(((byte)Math.round(normal.z()*127)&255)<<16);
        for(int i=0;i<4;i++){var p=quad.get(i);var next=new RoadGeometry.V(p.x(),p.y()*cap,p.z());int at=i*8;
          packed[at]=Float.floatToRawIntBits((float)next.x());packed[at+1]=Float.floatToRawIntBits((float)next.y());packed[at+2]=Float.floatToRawIntBits((float)next.z());packed[at+7]=packedNormal;
          var d=next.sub(a);double u=(d.dot(b)*cc-d.dot(c)*bc)/det,t=(d.dot(c)*bb-d.dot(b)*bc)/det;
          for(int component=4;component<=5;component++){double first=Float.intBitsToFloat(source[component]);packed[at+component]=Float.floatToRawIntBits((float)(first+u*(Float.intBitsToFloat(source[8+component])-first)+t*(Float.intBitsToFloat(source[16+component])-first)));}
        }out.add(new BakedQuad(packed,face.getTintIndex(),face.getDirection(),face.getSprite(),face.isShade(),false));
      }
    }return List.copyOf(out);
  }
  static List<BakedQuad> edgeQuads(List<BakedQuad> faces,List<com.sora.splineroads.core.RoadRaster.Box> boxes,double cap){
    var out=new ArrayList<BakedQuad>();
    for(var tile:TerrainEdgeFaces.surface(boxes))for(var face:faces){
      var direction=face.getDirection();int axis=direction.getAxis()==Direction.Axis.X?0:direction.getAxis()==Direction.Axis.Y?1:2;
      if(axis!=tile.axis()||(direction.getAxisDirection()==Direction.AxisDirection.POSITIVE)!=tile.positive())continue;
      var box=tile.box();var vertices=vertices(face);var a=vertices.get(0);var b=vertices.get(1).sub(a);var c=vertices.get(2).sub(a);
      double bb=b.dot(b),cc=c.dot(c),bc=b.dot(c),det=bb*cc-bc*bc;if(Math.abs(det)<1e-12)continue;
      var source=face.getVertices();var packed=source.clone();
      for(int i=0;i<4;i++){var v=vertices.get(i);var next=new RoadGeometry.V(box.x0()+v.x()*(box.x1()-box.x0()),(box.y0()+v.y()*(box.y1()-box.y0()))*cap,box.z0()+v.z()*(box.z1()-box.z0()));
        int at=i*8;packed[at]=Float.floatToRawIntBits((float)next.x());packed[at+1]=Float.floatToRawIntBits((float)next.y());packed[at+2]=Float.floatToRawIntBits((float)next.z());
        var d=next.sub(a);double u=(d.dot(b)*cc-d.dot(c)*bc)/det,t=(d.dot(c)*bb-d.dot(b)*bc)/det;
        for(int component=4;component<=5;component++){double first=Float.intBitsToFloat(source[component]);packed[at+component]=Float.floatToRawIntBits((float)(first+u*(Float.intBitsToFloat(source[8+component])-first)+t*(Float.intBitsToFloat(source[16+component])-first)));}
      }
      out.add(new BakedQuad(packed,face.getTintIndex(),face.getDirection(),face.getSprite(),face.isShade(),face.hasAmbientOcclusion()));
    }return List.copyOf(out);
  }
  private RoadTerrainModels(){}
}
