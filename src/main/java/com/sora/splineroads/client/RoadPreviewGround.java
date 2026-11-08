package com.sora.splineroads.client;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.*;
import com.sora.splineroads.world.RoadBlocks;
import com.sora.splineroads.world.RoadFillEntity;
import com.sora.splineroads.world.RoadIndex;
import com.sora.splineroads.world.RoadTerrainCover;
import java.lang.ref.WeakReference;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/** One preview's loaded terrain snapshot. Road colliders cannot make an existing
 * bridge look ground-supported, while their retained fill still counts as land.
 * This helper never requests an unloaded chunk or performs a world mutation. */
public final class RoadPreviewGround implements RoadStructures.Ground {
  private final Level level;
  private final Mesh mesh;
  private final List<Mesh> nearby;
  private final List<Mesh> obstacles;
  private final RoadStructureClearance clearance=new RoadStructureClearance();
  private final RoadSolidOverlap.Index cycleExcavation,gantrySolids;
  private final RoadPavingSupport sidewalkSolids;
  private final RoadPierSpacing pierSpacing;
  private final RoadRailJoin railJoin,capMouths;
  private final Map<Mesh,Mesh> motorDecks=new IdentityHashMap<>();
  private final Map<BlockPos,List<AABB>> columns=new HashMap<>();
  private final Set<BlockPos> queries=new LinkedHashSet<>();
  private final Map<Long,LevelChunk> chunks=new HashMap<>();
  private final Sources retained;
  private final List<RoadStructures.Part> fixedSupports;
  private final Mesh terrainReference;
  private RoadTerrainCover cover;
  private final Map<Mesh,List<RoadStructures.Part>> railShells=new IdentityHashMap<>();
  private Mesh stackMesh;
  private List<Mesh> stackNeighbors;

  public RoadPreviewGround(Level level,Mesh mesh,List<Mesh> nearby){
    this(level,mesh,nearby,nearby,List.of(),null,RoadRailJoin.mouths(List.of()),null,null,null);
  }

  /** The saved planner searches beyond the road ribbon: portal feet and smooth
   * sidewalks can lie outside its one-block preview outline. Keep the same local
   * 82-block obstacle extent, while joins retain their narrower road context. */
  public static RoadPreviewGround create(Level level,Mesh mesh,UUID edited,Collection<RoadIndex.Built> roads){
    return create(level,mesh,edited,roads,null);
  }
  static RoadPreviewGround create(Level level,Mesh mesh,UUID edited,Collection<RoadIndex.Built> roads,Sources retained){
    return create(level,mesh,edited,roads,retained,null,null);
  }
  static RoadPreviewGround create(Level level,Mesh mesh,UUID edited,Collection<RoadIndex.Built> roads,Sources retained,
      List<RoadStructures.Part> fixedSupports,Mesh terrainReference){
    var candidates=roads.stream().filter(r->!r.record.id().equals(edited)).toList();
    double margin=3+(mesh.settings().options().sidewalk().enabled()?mesh.settings().options().sidewalk().width():0);
    var nearby=candidates.stream().filter(r->RoadIndex.overlapXZ(r.mesh,mesh,margin)).toList();
    var obstacles=candidates.stream().filter(r->RoadIndex.overlapXZ(r.mesh,mesh,82)).toList();
    boolean laneEdges=mesh.settings().style().ramp()||mesh.settings().options().lanePoints().link()!=null
        ||nearby.stream().anyMatch(r->r.mesh.settings().style().ramp()||r.mesh.settings().options().lanePoints().link()!=null);
    var join=laneEdges?new RoadRailJoin(mesh,nearby.stream().map(r->new RoadRailJoin.Neighbor(r.mesh,
        RoadSurface.higherPriority(r.record.id(),r.mesh,edited==null?new UUID(0,0):edited,mesh))).toList()):null;
    var mouths=RoadRailJoin.mouths(nearby.stream().filter(r->LaneMerge.linkedTo(mesh,r.mesh)||LaneMerge.linkedTo(r.mesh,mesh)
        ||mesh.settings().options().lanePoints().cuts().stream().anyMatch(c->c.connection().equals(r.record.id()))).map(r->r.mesh).toList());
    var result=new RoadPreviewGround(level,mesh,nearby.stream().map(r->r.mesh).toList(),
        obstacles.stream().map(r->r.mesh).toList(),obstacles.stream().flatMap(r->r.record.structures().stream()).toList(),join,mouths,retained,fixedSupports,terrainReference);
    var tunnels=roads.stream().filter(RoadIndex.Built::hasTunnel).toList();
    result.cover=new RoadTerrainCover(chunk->tunnels.stream().filter(r->r.chunks.contains(chunk)).toList());
    nearby.forEach(r->result.railShells.put(r.mesh,r.record.structures()));
    return result;
  }

  private RoadPreviewGround(Level level,Mesh mesh,List<Mesh> nearby,List<Mesh> obstacles,
      List<RoadStructures.Part> structures,RoadRailJoin railJoin,RoadRailJoin capMouths,Sources retained,
      List<RoadStructures.Part> fixedSupports,Mesh terrainReference){
    this.level=level;this.mesh=mesh;this.nearby=List.copyOf(nearby);this.obstacles=List.copyOf(obstacles);
    this.retained=retained!=null&&retained.sameLevel(level)?retained:null;
    this.fixedSupports=fixedSupports==null?null:List.copyOf(fixedSupports);this.terrainReference=terrainReference;
    this.railJoin=railJoin;this.capMouths=capMouths;
    cycleExcavation=new RoadSolidOverlap.Index(obstacles.stream().flatMap(r->RoadsideBypass.supportVoids(r).stream()).toList());
    sidewalkSolids=new RoadPavingSupport(structures);
    gantrySolids=new RoadSolidOverlap.Index(structures.stream().filter(RoadGantry::member).toList());
    pierSpacing=new RoadPierSpacing(structures);
  }
  @Override public List<RoadStructures.Part> fixedSupports(){return fixedSupports;}
  @Override public double foundation(V center,double top,double width){
    double median=RoadSupports.medianFoundation(center,top,width,obstacles);
    return sidewalkSolids.foundation(center,top,width,median,()->RoadStructures.Ground.super.foundation(center,top,width));
  }
  @Override public Mesh terrainReference(Mesh candidate){return terrainReference==null?candidate:terrainReference;}

  /** Retain only the queried columns and weak chunk identities. Reloading the
   * same terrain is not a furniture change; real collision/fill changes are.
   * The renderer restricts reuse to the same preview and road-index revision. */
  public Sources sources(){return new Sources(level,new ColumnSources(queries,columns,chunks,
      retained==null?null:retained.columns));}
  boolean incomplete(){return level!=null&&queries.stream().anyMatch(key->!columns.containsKey(key));}
  public static final class Sources {
    private final Level level;
    private final ColumnSources columns;
    private final Terrain terrain;
    private Sources(Level level,ColumnSources columns){
      this.level=level;this.columns=columns;
      terrain=new Terrain(){
        public Object chunk(long position){
          return loadedChunk(level,position);
        }
        public List<AABB> column(BlockPos key){return RoadPreviewGround.column(level,key);}
        public long tick(){return level.getGameTime();}
      };
    }
    boolean sameLevel(Level candidate){return candidate==level;}
    public boolean current(Level candidate){
      if(candidate!=level)return false;
      return level==null||columns.current(terrain);
    }
  }

  /** The same policy is exercised with deterministic source identities in the
   * model regression; actual terrain reads always use column(Level, BlockPos). */
  interface Terrain {
    Object chunk(long position);
    List<AABB> column(BlockPos key);
    long tick();
  }
  static final class ColumnSources {
    static final int POLL_COLUMNS=32,POLL_INTERVAL=20;
    private static final class ChunkColumns {
      final long position;
      final List<BlockPos> keys=new ArrayList<>();
      WeakReference<Object> identity;
      WeakReference<Object> checking=new WeakReference<>(null);
      int reloadCursor;
      boolean queued,unknown;
      long checkedAt=Long.MIN_VALUE;
      ChunkColumns(long position,Object identity){this.position=position;this.identity=new WeakReference<>(identity);}
    }
    private final Map<BlockPos,List<AABB>> known;
    private final List<BlockPos> keys;
    private final Map<Long,ChunkColumns> chunks=new LinkedHashMap<>();
    private final ArrayDeque<ChunkColumns> reloads=new ArrayDeque<>();
    private int cursor;
    private long budgetTick=Long.MIN_VALUE;
    private int readThisTick;
    private long polledAt=Long.MIN_VALUE,nextPollAt=Long.MIN_VALUE;
    private boolean invalid;
    ColumnSources(Collection<BlockPos> queries,Map<BlockPos,List<AABB>> known,Map<Long,?> observed,ColumnSources retained){
      this.known=Map.copyOf(known);keys=List.copyOf(queries);
      for(var key:keys){
        long position=ChunkPos.asLong(key.getX()>>4,key.getZ()>>4);
        var group=chunks.computeIfAbsent(position,p->{
          Object identity=observed.get(p);
          if(identity==null&&retained!=null){var old=retained.chunks.get(p);if(old!=null)identity=old.identity.get();}
          return new ChunkColumns(p,identity);
        });
        group.keys.add(key);
        group.unknown|=!known.containsKey(key);
      }
    }
    List<AABB> known(BlockPos key){return known.get(key);}
    boolean current(Terrain terrain){
      if(invalid)return false;
      long tick=terrain.tick();
      if(tick!=budgetTick){budgetTick=tick;readThisTick=0;}
      // Availability/identity checks stay cheap and can notice a chunk arriving
      // within the same tick. Equal reloads adopt the new weak identity once.
      for(var group:chunks.values()){
        var present=terrain.chunk(group.position);
        if(present==null||present==group.identity.get())continue;
        // A first real observation cannot validate a schematic/unknown column.
        if(group.unknown)return !(invalid=true);
        if(present!=group.checking.get()){group.checking=new WeakReference<>(present);group.reloadCursor=0;}
        if(!group.queued){group.queued=true;reloads.addLast(group);}
      }
      // Identity replacement shares the same per-tick read allowance as the
      // periodic pass. Rotate chunks so one long/repeated reload cannot prevent
      // another changed chunk from being examined.
      int visits=0;
      while(!reloads.isEmpty()&&readThisTick<POLL_COLUMNS&&visits++<POLL_COLUMNS){
        var group=reloads.removeFirst();group.queued=false;
        var present=terrain.chunk(group.position);
        if(present==null)continue;
        if(present==group.identity.get()){group.checking=new WeakReference<>(null);group.reloadCursor=0;continue;}
        if(present!=group.checking.get()){group.checking=new WeakReference<>(present);group.reloadCursor=0;}
        var key=group.keys.get(group.reloadCursor++);readThisTick++;
        if(!known.get(key).equals(terrain.column(key)))return !(invalid=true);
        if(group.reloadCursor==group.keys.size()){
          group.identity=new WeakReference<>(present);group.checking=new WeakReference<>(null);group.reloadCursor=0;group.checkedAt=tick;
        }else{group.queued=true;reloads.addLast(group);}
      }
      // A long preview must not rescan all columns in a single periodic frame.
      // At most 32 already-read columns are revisited per tick; after one full
      // pass, wait 20 ticks. A second renderer call in that tick does no reread.
      if(tick==polledAt||tick<nextPollAt||readThisTick==POLL_COLUMNS)return true;
      polledAt=tick;
      for(int count=0;count<POLL_COLUMNS&&readThisTick<POLL_COLUMNS&&cursor<keys.size();count++,cursor++){
        var key=keys.get(cursor);var group=chunks.get(ChunkPos.asLong(key.getX()>>4,key.getZ()>>4));
        if(group.checkedAt==tick||group.queued||terrain.chunk(group.position)==null)continue;
        var before=known.get(key);
        readThisTick++;
        if(before==null||!before.equals(terrain.column(key)))return !(invalid=true);
      }
      if(cursor==keys.size()){cursor=0;nextPollAt=tick+POLL_INTERVAL;}
      return true;
    }
  }

  @Override public double top(double x,double z,double deckY){
    return Math.min(solidTop(x,z,deckY,true),deckY);
  }
  @Override public double surface(double x,double z,double roadY){
    return cover==null?RoadTerrainSurface.connected(roadY,probe->solidTop(x,z,probe,false))
        :cover.surface(x,z,roadY,probe->solidTop(x,z,probe,false));
  }
  private double solidTop(double x,double z,double deckY,boolean schematicFoundation){
    if(level==null)return schematicFoundation?deckY:Double.NaN;
    var key=BlockPos.containing(x,Math.min(level.getMaxBuildHeight()-1,deckY),z);
    queries.add(key);
    // Keep known physical columns if they leave view while another column
    // forces a rebuild. Only never-observed terrain uses the schematic default.
    long position=ChunkPos.asLong(key.getX()>>4,key.getZ()>>4);
    var chunk=loadedChunk(level,position);
    chunks.put(position,chunk);
    var values=columns.get(key);
    if(values==null){
      values=chunk==null?(retained==null?null:retained.columns.known(key)):column(level,key);
      if(values!=null)columns.put(key,values);
    }
    if(values==null)return schematicFoundation?deckY:Double.NaN;
    for(var box:values)
      if(x>=box.minX-1e-7&&x<=box.maxX+1e-7&&z>=box.minZ-1e-7&&z<=box.maxZ+1e-7
          &&box.minY<deckY-1e-7)return box.maxY;
    return Double.NaN;
  }

  private static LevelChunk loadedChunk(Level level,long position){
    var at=new ChunkPos(position);
    // Client hasChunkAt/getChunkAt can expose an EmptyLevelChunk placeholder
    // outside its cache. FULL with create=false is the actual no-load boundary.
    var chunk=level.getChunkSource().getChunk(at.x,at.z,ChunkStatus.FULL,false);
    return chunk instanceof LevelChunk loaded&&!(loaded instanceof EmptyLevelChunk)?loaded:null;
  }

  private static List<AABB> column(Level level,BlockPos key){
    var result=new ArrayList<AABB>();
    int first=Math.min(key.getY(),level.getHeight(Heightmap.Types.WORLD_SURFACE,key.getX(),key.getZ())-1);
    int bottom=Math.max(level.getMinBuildHeight(),key.getY()-(int)RoadStructures.MAX_DROP);
    for(int y=first;y>=bottom;y--){
      var pos=new BlockPos(key.getX(),y,key.getZ());
      BlockState state=level.getBlockState(pos);double cap=1;
      if(RoadBlocks.isCollider(state)){
        if(level.getBlockEntity(pos) instanceof RoadFillEntity fill){state=fill.fill();cap=fill.cap();}
        else state=state.getValue(RoadBlocks.Road.FILL).state();
      }
      if(state.isAir()||state.is(BlockTags.LEAVES)||state.is(BlockTags.LOGS)
          ||state.is(SplineRoads.NODE.get())||state.is(SplineRoads.TUNNEL_AIR.get())
          ||!state.getFluidState().isEmpty())continue;
      var shape=state.getCollisionShape(level,pos);
      for(var box:shape.toAabbs())if(box.minY<cap)
        result.add(new AABB(box.minX,box.minY,box.minZ,box.maxX,Math.min(box.maxY,cap),box.maxZ).move(pos));
      if(y<key.getY()&&cap==1&&Block.isShapeFullBlock(shape))break;
    }
    result.sort(Comparator.comparingDouble((AABB box)->box.maxY).reversed());
    return List.copyOf(result);
  }

  @Override public boolean joined(V point){return LanePoints.opening(mesh,point)||nearby.stream().anyMatch(road->RoadQueries.joins(mesh,road,point)||RailwayCrossings.at(mesh,road,point,.3)||RailwayCrossings.walkingAt(mesh,road,point,.3));}
  @Override public boolean furnitureClear(V point){return LanePoints.opening(mesh,point)||nearby.stream().anyMatch(road->RailwayCrossings.at(mesh,road,point,1)||RailwayCrossings.walkingAt(mesh,road,point,1));}
  @Override public boolean wireContinues(boolean first){return nearby.stream().anyMatch(road->RailbedFurniture.continues(mesh,road,railShells.getOrDefault(road,List.of()),first));}
  private void prepareStack(){if(stackNeighbors==null){stackMesh=RoadStreetscape.resolve(mesh,this);stackNeighbors=nearby.stream().map(road->RoadStreetscape.resolve(road,this)).toList();}}
  @Override public boolean sharedDeck(){prepareStack();return stackNeighbors.stream().anyMatch(road->MultiLevelBridges.compatible(stackMesh,road));}
  @Override public List<RoadStructures.Part> sharedSupports(Mesh upper,Sample at,double depth,double width,double offset){
    prepareStack();var lowers=stackNeighbors.stream().filter(road->MultiLevelBridges.compatible(road,stackMesh)).toList();
    return MultiLevelBridges.supports(upper,lowers,at,upper.settings().thickness(),depth,width,offset,this);
  }
  @Override public boolean blocked(RoadStructures.Part part){return blocked(part,null,null,null);}
  @Override public String blockedReason(RoadStructures.Part part){
    return (RoadStructureClearance.selfSupportBlocked(part,mesh)?"构件侵入本桥通行空间，":"")+RoadStructures.Ground.super.blockedReason(part);
  }
  @Override public boolean lampBlocked(RoadStructures.Part part,Sample mount){return blocked(part,null,null,mount);}
  @Override public boolean railBlocked(RoadStructures.Part part,V a,V b){return blocked(part,a,b,null);}

  private boolean blocked(RoadStructures.Part part,V railA,V railB,Sample lampMount){
    if(!RailbedFurniture.visualOnly(part)&&nearby.stream().anyMatch(road->java.util.stream.Stream.concat(part.base().stream(),java.util.stream.Stream.of(part.a().add(part.b()).mul(.5))).anyMatch(p->RailwayCrossings.walkingAt(mesh,road,p,.3))))return true;
    if(pierSpacing.tooClose(part)||part.pier()&&!RoadGantry.member(part)&&gantrySolids.intersects(part))return true;
    if(part.pier()&&part.material()==RoadStructures.Material.CONCRETE&&cycleExcavation.intersects(part))return true;
    if(!RoadSidewalks.smoothPart(part)&&sidewalkSolids.blocks(part))return true;
    if(lampMount==null?(RoadGantryMounts.foot(part)?RoadGantryMounts.blockedByRoad(part,mesh,4.25)
        :RoadStructureClearance.selfSupportBlocked(part,mesh)):RoadStreetscape.lampOwnRoadBlocked(part,mesh,lampMount))return true;
    if(railJoin==null&&!part.pier()&&part.material()==RoadStructures.Material.DEFAULT)return false;
    double w=part.halfExtent()+(part.pier()?.5:.05);
    double minX=Math.min(part.a().x(),part.b().x())-w,maxX=Math.max(part.a().x(),part.b().x())+w;
    double minZ=Math.min(part.a().z(),part.b().z())-w,maxZ=Math.max(part.a().z(),part.b().z())+w;
    double minY=Math.min(part.a().y(),part.b().y())-part.verticalFrame(),maxY=Math.max(part.a().y(),part.b().y())+part.height()+part.verticalFrame();
    for(var other:obstacles){
      if(railA!=null&&RoadRailJoin.sharedRail(other,railA,railB))continue;
      if(maxX<other.min().x()-2||minX>other.max().x()+2||maxZ<other.min().z()-2||minZ>other.max().z()+2)continue;
      if(clearance.cycleBlocked(part,other))return true;
      if(maxY<other.min().y()-.05||minY>other.max().y()+RoadStructureClearance.headroom(other))continue;
      if(RoadSidewalks.smoothPart(part))continue;
      if(part.pier()){
        if(RoadStructureClearance.pierBlocked(part,other,nearby))return true;
      }else{
        if(LaneClosureLandscape.paved(part)&&RoadClearance.belowSurface(part,other,.025))continue;
        boolean joining=LaneMerge.linkedTo(mesh,other)||LaneMerge.linkedTo(other,mesh)
            ||RoadQueries.joins(mesh,other,part.a())||RoadQueries.joins(mesh,other,part.b());
        if(joining&&RoadClearance.joiningFloor(part,mesh,other))continue;
        var protectedMesh=railA==null?other:motorDecks.computeIfAbsent(other,LaneDeck::motorOnly);
        if(RoadClearance.structureInvades(part,protectedMesh,RoadStructureClearance.headroom(other)))return true;
      }
    }
    return false;
  }

  @Override public boolean unionRails(){return railJoin!=null;}
  @Override public V railJoint(V p,V direction){return railJoin==null?null:railJoin.joint(p,direction);}
  @Override public V railJoint(V p,V direction,boolean highway,boolean raised){return railJoin==null?null:railJoin.joint(p,direction,highway,raised);}
  @Override public boolean railPost(V p){return railJoin==null||railJoin.ownsPost(p);}
  @Override public boolean railPost(V p,boolean highway,boolean raised){return railJoin==null||railJoin.ownsPost(p,highway,raised);}
  @Override public List<RoadRailJoin.Span> railSpans(V a,V b,V outside){return railJoin==null?RoadStructures.Ground.super.railSpans(a,b,outside):railJoin.exposed(a,b,outside);}
  @Override public List<RoadRailJoin.Span> railSpans(V a,V b,V outside,double inset){return railJoin==null?RoadStructures.Ground.super.railSpans(a,b,outside,inset):railJoin.exposed(a,b,outside,inset);}
  @Override public List<RoadRailJoin.Span> capRailSpans(V a,V b,V outside){
    var result=new ArrayList<RoadRailJoin.Span>();
    for(var span:railSpans(a,b,outside))result.addAll(capMouths.exposedMouth(span.a(),span.b()));
    return result;
  }
}
