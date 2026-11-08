package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadConnectionChecks;
import com.sora.splineroads.core.RoadClearance;
import com.sora.splineroads.core.RoadWaterPolicy;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.LaneAdditions;
import com.sora.splineroads.core.RoadGantry;
import com.sora.splineroads.core.RoadLaneLines;
import com.sora.splineroads.core.RoadAttachments;
import com.sora.splineroads.core.RoadAprons;
import com.sora.splineroads.core.StreetLampClearance;
import com.sora.splineroads.core.TollPlanner;
import com.sora.splineroads.core.RoadRaster;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadStructures;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RailwayCrossings;
import com.sora.splineroads.core.RoadTunnel;
import com.sora.splineroads.core.RoadTunnelFit;
import com.sora.splineroads.core.RoadNoses;
import com.sora.splineroads.core.RoadLimits;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadQueries;
import com.sora.splineroads.core.RoadTransitions;
import com.sora.splineroads.core.RoadEndpointSections;
import com.sora.splineroads.core.RoadTimings;
import com.sora.splineroads.net.RoadNetwork;
import java.util.*;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

public final class RoadData extends SavedData {
  public final RoadIndex index = new RoadIndex();
  public final RailbedStore railbeds = new RailbedStore();
  BlockState railbedOriginal(long key){return terrainOriginal.getOrDefault(key,Blocks.AIR.defaultBlockState());}
  record StreetLedger(BlockState fill,BlockState original,BlockState sidewalk,boolean apron){}
  StreetLedger streetLedger(long key){return new StreetLedger(terrainFill.get(key),terrainOriginal.get(key),sidewalkPlaced.get(key),apronPlaced.contains(key));}
  void streetLedger(long key,StreetLedger value){
    if(value.fill()==null)terrainFill.remove(key);else terrainFill.put(key,value.fill());
    if(value.original()==null)terrainOriginal.remove(key);else terrainOriginal.put(key,value.original());
    if(value.sidewalk()==null)sidewalkPlaced.remove(key);else sidewalkPlaced.put(key,value.sidewalk());
    if(value.apron())apronPlaced.add(key);else apronPlaced.remove(key);
  }
  CompoundTag streetObjects(Set<UUID> ids,Set<UUID> centers){
    var t=new CompoundTag();var roads=new ListTag();var logical=new ListTag();var junctionsTag=new ListTag();
    ids.stream().sorted().forEach(id->{var r=index.roads.get(id);if(r!=null)roads.add(r.record.save());if(streets.containsKey(id))logical.add(streets.get(id).save());});
    centers.stream().sorted().forEach(id->{if(junctions.containsKey(id))junctionsTag.add(junctions.get(id).copy());});
    t.put("Roads",roads);t.put("Streets",logical);t.put("Centers",junctionsTag);return t;
  }
  void restoreStreetObjects(ServerLevel level,Set<UUID> ids,Set<UUID> centers,CompoundTag snapshot){
    for(var id:ids){index.remove(id);streets.remove(id);}centers.forEach(junctions::remove);
    for(Tag value:snapshot.getList("Roads",Tag.TAG_COMPOUND)){var r=RoadRecord.load((CompoundTag)value);index.put(RoadIndex.Built.loading(r));}
    for(Tag value:snapshot.getList("Streets",Tag.TAG_COMPOUND)){var r=RoadRecord.load((CompoundTag)value);streets.put(r.id(),r);}
    for(Tag value:snapshot.getList("Centers",Tag.TAG_COMPOUND)){var t=(CompoundTag)value;junctions.put(t.getUUID("Id"),t.copy());}
    for(var id:ids){var r=index.roads.get(id);if(r==null)RoadNetwork.broadcastDelete(level,id);else RoadNetwork.broadcastRoad(level,r.record);}setDirty();
  }
  static void restoreStreetBlocks(ServerLevel level,Map<Long,BlockState> states,Map<Long,CompoundTag> entities){restoreBlocks(level,states,entities,failure->{throw failure;},true);}
  static void restorePlannerBlocks(ServerLevel level,Map<Long,BlockState> states,Map<Long,CompoundTag> entities){restoreBlocks(level,states,entities,failure->{throw failure;},false);}
  void railbedOwns(long key){terrainOriginal.remove(key);terrainFill.remove(key);}
  public final SmartSignRoads smartSigns = new SmartSignRoads(this);
  public RoadData(){index.changed=smartSigns::changed;}
  private ServerLevel owningLevel;
  RoadStructures.Ground terrainGround(){return owningLevel==null?null:RoadTerrain.read(owningLevel,terrainFill,terrainOriginal,new RoadTerrain.Cache(terrainFill,terrainOriginal));}
  RoadStructures.Ground railbedTerrain(Map<Long,BlockState> prior){
    var originals=new Long2ObjectOpenHashMap<BlockState>(terrainOriginal);for(var entry:railbeds.entries.values())entry.original.forEach(originals::putIfAbsent);originals.putAll(prior);
    return RoadTerrain.read(owningLevel,terrainFill,originals,new RoadTerrain.Cache(terrainFill,originals));
  }
  boolean withinHeight(com.sora.splineroads.core.RoadGeometry.Mesh mesh){return owningLevel==null||mesh.min().y()-mesh.settings().thickness()>=owningLevel.getMinBuildHeight()&&mesh.max().y()+4<owningLevel.getMaxBuildHeight();}
  int minimumBuildHeight(){return owningLevel==null?Integer.MIN_VALUE:owningLevel.getMinBuildHeight();}
  private Set<UUID> confirmedLaneDeletes=Set.of();
  void removeWithDependents(ServerLevel level,ServerPlayer player,UUID id,Set<UUID> dependents){for(var v:dependents)if(player!=null)requireOwner(player,index.roads.get(v).record.owner());confirmedLaneDeletes=Set.copyOf(dependents);try{remove(level,player,id);}finally{confirmedLaneDeletes=Set.of();}}
  final Map<UUID,RoadRecord> streets = new LinkedHashMap<>();
  /** Logical street settings are the editor's authority, ahead of derived pieces. */
  public RoadRecord editableRoad(UUID id){var street=streets.get(id);var built=index.roads.get(id);
    if(street!=null&&built!=null&&!LaneTopology.metadata(built.record).access().isEmpty())return street.withLanePoints(ElevatedAccessRoads.logicalLanes(street,built.record));
    return street!=null?street:built==null?null:built.record;}
  final Map<UUID, CompoundTag> junctions = new HashMap<>();
  final Map<UUID, CompoundTag> interchanges = new HashMap<>();
  private final Map<Long,Set<UUID>> islandRepair=new HashMap<>();
  private final Map<Long,List<Long>> groundJoinRepair=new HashMap<>();
  private final Map<Long,List<RoadStructures.Part>> retiredSignsByChunk=new HashMap<>();
  private Long2ObjectOpenHashMap<BlockState> sidewalkPlaced = new Long2ObjectOpenHashMap<>();
  private final Map<Long,List<Long>> apronWidthRepair=new HashMap<>();
  private final Map<Long,List<RoadStructures.Part>> structureRepair103=new HashMap<>();
  private final Set<UUID> supportRepair104=new LinkedHashSet<>(),attemptedSupportRepair104=new HashSet<>();
  private Set<Long> apronPlaced = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
  private final Long2ObjectOpenHashMap<BlockState> terrainOriginal = new Long2ObjectOpenHashMap<>();
  private final Long2ObjectOpenHashMap<BlockState> terrainFill = new Long2ObjectOpenHashMap<>();
  private final Map<Long, Set<UUID>> repairedChunks = new LinkedHashMap<>(128,.75f,true) {
    @Override protected boolean removeEldestEntry(Map.Entry<Long, Set<UUID>> e) { return size() > 4096; }
  };

  public static RoadData get(ServerLevel level) {
    var data=level.getDataStorage().computeIfAbsent(RoadData::load, RoadData::new, "splineroads");data.owningLevel=level;return data;
  }

  public static RoadData load(CompoundTag root) {
    if (root.getInt("Version") > 71)
      throw new IllegalStateException("Spline Roads save is newer than this mod");
    if (root.getInt("Version") < 71)
      throw new IllegalStateException("Spline Roads 2.0 requires a new save");
    long loadStarted=System.nanoTime();
    RoadData data = new RoadData();
    data.railbeds.load(root.getCompound("Railbeds"));
    Map<CompoundTag,BlockState> palette=new HashMap<>();
    for(Tag t:root.getList("Roads",Tag.TAG_COMPOUND)){
      var r=RoadRecord.load((CompoundTag)t);
      if(r.toll()!=null&&root.getInt("Version")<48){
        var oldParts=r.structures().stream().filter(p->p.model()!=null&&p.model().startsWith("sr:toll_")).toList();
        var parts=new ArrayList<>(r.structures());parts.removeAll(oldParts);parts.addAll(TollPlanner.plan(r.toll()).structures());
        for(var part:oldParts){double pad=part.halfExtent();for(int x=(int)Math.floor(Math.min(part.a().x(),part.b().x())-pad)>>4;x<=(int)Math.floor(Math.max(part.a().x(),part.b().x())+pad)>>4;x++)for(int z=(int)Math.floor(Math.min(part.a().z(),part.b().z())-pad)>>4;z<=(int)Math.floor(Math.max(part.a().z(),part.b().z())+pad)>>4;z++)data.retiredSignsByChunk.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(x,z),k->new ArrayList<>()).add(part);}
        r=r.structures(parts);data.setDirty();
      }
      var ref=r.junction();
      if(ref!=null&&ref.geometryVersion()==42&&ref.spec().kind()==com.sora.splineroads.core.JunctionSpec.Kind.ROUNDABOUT){
        var next=new com.sora.splineroads.core.JunctionPlanner.Ref(ref.spec(),ref.piece(),43);
        var parts=new ArrayList<>(r.structures());
        if(ref.get().arm()<0&&ref.spec().greenIsland()){
          var oldIsland=ref.get().structures().stream().filter(p->(p.material()==RoadStructures.Material.SOIL||p.material()==RoadStructures.Material.GREEN)&&p.a().sub(ref.spec().center()).horizontalLength()<ref.spec().islandRadius()).toList();
          parts.removeAll(oldIsland);parts.addAll(next.get().structures().stream().filter(p->p.model().startsWith("sr:roundabout_")).toList());
        }
        r=r.junction(r.assembly(),next).structures(parts);var rebuilt=RoadIndex.Built.loading(r);
        if(ref.get().arm()<0&&ref.spec().greenIsland())for(long c:rebuilt.chunks)data.islandRepair.computeIfAbsent(c,k->new HashSet<>()).add(r.id());
        data.index.put(rebuilt);data.setDirty();
      }else data.index.put(RoadIndex.Built.loading(r));
    }
    if(root.getInt("Version")<52)for(var built:List.copyOf(data.index.roads.values())){
      var record=built.record;var old=record.structures();var fresh=new ArrayList<>(RoadStructures.repairJoinedPlanting(built.mesh,old));
      if(record.toll()!=null&&!record.toll().config().name().isEmpty()){
        fresh.removeIf(p->p.model().startsWith("sr:toll_model_name_"));
        fresh.addAll(TollPlanner.plan(record.toll()).structures().stream().filter(p->p.model().startsWith("sr:toll_model_name_")).toList());
      }
      if(record.settings().structure()==Structure.TUNNEL&&record.settings().options().cycleRail()){
        var nearby=data.index.surfaceNeighbors(built);
        var ground=new RoadStructures.Ground(){public double top(double x,double z,double y){return Double.NaN;}public boolean blocked(RoadStructures.Part part){return false;}
          public boolean joined(V point){return nearby.stream().anyMatch(n->RoadQueries.contains(n.mesh,point,.05,.2));}};
        var rails=new ArrayList<RoadStructures.Part>();RoadStructures.cycleFurniture(built.mesh,ground,rails);
        var existing=new HashSet<>(fresh);for(var rail:rails)if(existing.add(rail))fresh.add(rail);
      }
      var oldSet=new HashSet<>(old);var nextSet=new HashSet<>(fresh);var delta=new ArrayList<RoadStructures.Part>();
      for(var part:oldSet)if(!nextSet.contains(part))delta.add(part);for(var part:nextSet)if(!oldSet.contains(part))delta.add(part);
      if(delta.isEmpty())continue;
      data.index.put(RoadIndex.Built.loading(record.structures(fresh)));
      for(var part:delta){double pad=part.halfExtent();for(int x=(int)Math.floor(Math.min(part.a().x(),part.b().x())-pad)>>4;x<=(int)Math.floor(Math.max(part.a().x(),part.b().x())+pad)>>4;x++)for(int z=(int)Math.floor(Math.min(part.a().z(),part.b().z())-pad)>>4;z<=(int)Math.floor(Math.max(part.a().z(),part.b().z())+pad)>>4;z++)data.structureRepair103.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(x,z),k->new ArrayList<>()).add(part);}
      data.setDirty();
    }
    for(Tag value:root.getList("StructureRepair103",Tag.TAG_COMPOUND)){var tag=(CompoundTag)value;data.structureRepair103.computeIfAbsent(tag.getLong("Chunk"),k->new ArrayList<>()).addAll(RoadRecord.unpackStructures(tag.getByteArray("Parts")));}
    // Existing generated lamps also respect the new signs after a save is loaded.
    // Cleanup is limited to exact lamp parts; chunk repair preserves other owners.
    var sight=JunctionSignRoads.lampClearance(List.copyOf(data.index.roads.values()));
    for(var built:List.copyOf(data.index.roads.values())){
      var removed=com.sora.splineroads.core.StreetLampClearance.removed(built.record.structures(),sight);if(removed.isEmpty())continue;
      for(var part:removed){double pad=part.halfExtent();for(int x=(int)Math.floor(Math.min(part.a().x(),part.b().x())-pad)>>4;x<=(int)Math.floor(Math.max(part.a().x(),part.b().x())+pad)>>4;x++)for(int z=(int)Math.floor(Math.min(part.a().z(),part.b().z())-pad)>>4;z<=(int)Math.floor(Math.max(part.a().z(),part.b().z())+pad)>>4;z++)data.retiredSignsByChunk.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(x,z),k->new ArrayList<>()).add(part);}
      data.index.put(RoadIndex.Built.loading(built.record.structures(built.record.structures().stream().filter(p->!removed.contains(p)).toList())));
    }
    for(Tag value:root.getList("RetiredSignCleanup",Tag.TAG_COMPOUND)){var tag=(CompoundTag)value;data.retiredSignsByChunk.computeIfAbsent(tag.getLong("Chunk"),k->new ArrayList<>()).addAll(RoadRecord.unpackStructures(tag.getByteArray("Parts")));}
    RoadBlockStorage.read(root.getList(root.contains("TerrainFillPalette")?"TerrainFillPalette":"OriginalPalette", Tag.TAG_COMPOUND), data.terrainFill);
    RoadBlockStorage.read(root.getList("SidewalkPalette", Tag.TAG_COMPOUND), data.sidewalkPlaced);
    for(long key:root.getLongArray("RoadAprons"))if(data.sidewalkPlaced.containsKey(key))data.apronPlaced.add(key);
    var pendingAprons=new HashSet<Long>();for(long c:root.getLongArray("ApronWidthRepair"))pendingAprons.add(c);
    for(long key:data.apronPlaced){long c=new net.minecraft.world.level.ChunkPos(BlockPos.of(key)).toLong();if(root.getInt("Version")<51||pendingAprons.contains(c))data.apronWidthRepair.computeIfAbsent(c,k->new ArrayList<>()).add(key);}
    if(!data.apronWidthRepair.isEmpty())data.setDirty();
    RoadBlockStorage.read(root.getList("TerrainOriginalPalette", Tag.TAG_COMPOUND), data.terrainOriginal);
    for (Tag entry : root.getList("Interchanges", Tag.TAG_COMPOUND)) {
      CompoundTag t = (CompoundTag) entry;
      data.interchanges.put(t.getUUID("Id"), t.copy());
    }
    for (Tag entry : root.getList("Junctions", Tag.TAG_COMPOUND)) {
      CompoundTag t = (CompoundTag)entry; data.junctions.put(t.getUUID("Id"),t.copy());
    }
    for(Tag t:root.getList("LogicalStreets",Tag.TAG_COMPOUND)){var r=RoadRecord.load((CompoundTag)t);data.streets.put(r.id(),r);}
    if(root.getInt("Version")<18)for(Tag entry:root.getList("SidewalkBlocks",Tag.TAG_COMPOUND)) {var t=(CompoundTag)entry;data.sidewalkPlaced.put(t.getLong("Pos"),palette.computeIfAbsent(t.getCompound("State"),v->NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),v)));}
    if(!data.retiredSignsByChunk.isEmpty())data.setDirty();
    for(Tag value:root.getList("IslandRepair",Tag.TAG_COMPOUND)){var t=(CompoundTag)value;if(t.hasUUID("Road"))data.islandRepair.computeIfAbsent(t.getLong("Chunk"),k->new HashSet<>()).add(t.getUUID("Road"));}
    var pendingGround=new HashSet<Long>();for(long c:root.getLongArray("GroundJoinRepair"))pendingGround.add(c);
    for(long key:data.terrainOriginal.keySet()){long c=new net.minecraft.world.level.ChunkPos(BlockPos.of(key)).toLong();if(root.getInt("Version")<49||pendingGround.contains(c))data.groundJoinRepair.computeIfAbsent(c,k->new ArrayList<>()).add(key);}
    if(!data.groundJoinRepair.isEmpty())data.setDirty();
    LaneTopology.initialize(data);
    for(Tag value:root.getList("SupportRepair104",Tag.TAG_COMPOUND)){var t=(CompoundTag)value;if(t.hasUUID("Road")&&data.index.roads.containsKey(t.getUUID("Road")))data.supportRepair104.add(t.getUUID("Road"));}
    // Do not scan every saved pier against every road during world loading.
    // Normal explicit edits still rebuild supports against the complete corridor index.
    data.smartSigns.load(root.getList("SmartSigns",Tag.TAG_COMPOUND));
    if(data.railbeds.pending.isEmpty())data.adoptRailbeds(null);
    RoadTimings.reportLoad(loadStarted,data.index.roads.size());
    return data;
  }

  @Override
  public CompoundTag save(CompoundTag root) {
    root.put("Railbeds",railbeds.save());
    root.putInt("Version", 71);
    var supportRepairs=new ListTag();for(UUID id:supportRepair104){var t=new CompoundTag();t.putUUID("Road",id);supportRepairs.add(t);}root.put("SupportRepair104",supportRepairs);
    var repair103=new ListTag();structureRepair103.forEach((chunk,parts)->{var t=new CompoundTag();t.putLong("Chunk",chunk);t.putByteArray("Parts",RoadRecord.packStructures(parts));repair103.add(t);});root.put("StructureRepair103",repair103);
    root.putLongArray("ApronWidthRepair",apronWidthRepair.keySet().stream().mapToLong(Long::longValue).toArray());
    var islands=new ListTag();islandRepair.forEach((c,ids)->ids.forEach(id->{var t=new CompoundTag();t.putLong("Chunk",c);t.putUUID("Road",id);islands.add(t);}));root.put("IslandRepair",islands);
    root.putLongArray("GroundJoinRepair",groundJoinRepair.keySet().stream().mapToLong(Long::longValue).toArray());
    root.put("SmartSigns",smartSigns.save());
    var cleanup=new ListTag();retiredSignsByChunk.forEach((chunk,parts)->{var t=new CompoundTag();t.putLong("Chunk",chunk);t.putByteArray("Parts",RoadRecord.packStructures(parts));cleanup.add(t);});root.put("RetiredSignCleanup",cleanup);
    ListTag logical=new ListTag();streets.values().forEach(r->logical.add(r.save()));root.put("LogicalStreets",logical);
    ListTag roads = new ListTag();
    index.roads.values().forEach(r -> roads.add(r.record.save()));
    ListTag groups = new ListTag();
    interchanges.values().forEach(t -> groups.add(t.copy()));
    root.put("Interchanges", groups);
    ListTag junctionTags = new ListTag(); junctions.values().forEach(t -> junctionTags.add(t.copy()));
    root.put("Junctions", junctionTags);
    root.put("TerrainOriginalPalette", RoadBlockStorage.write(terrainOriginal));
    root.put("TerrainFillPalette", RoadBlockStorage.write(terrainFill));
    root.remove("OriginalPalette");
    root.put("SidewalkPalette", RoadBlockStorage.write(sidewalkPlaced));
    root.putLongArray("RoadAprons",apronPlaced.stream().mapToLong(Long::longValue).toArray());
    root.remove("OriginalEntities");
    root.put("Roads", roads);
    root.remove("Originals");
    root.remove("SidewalkBlocks");
    return root;
  }

  public boolean linked(BlockPos pos) {
    return !index.atNode(pos).isEmpty();
  }

  public static void requireOwner(ServerPlayer player, UUID owner) {
    if (owner != null && !owner.equals(player.getUUID()) && !player.hasPermissions(2))
      throw new IllegalArgumentException("只能编辑自己创建的道路和端点");
  }

  public static NodeEntity requireNode(ServerLevel level, BlockPos pos, ServerPlayer player) {
    try (var work = RoadWorkChunks.open(level)) {
      work.node(pos);
      if (!(level.getBlockEntity(pos) instanceof NodeEntity node))
        throw new IllegalArgumentException("端点不存在，可能已被删除，请重新选择");
      if (player != null) {
        requireOwner(player, node.owner);
        if (!player.mayBuild() || !level.mayInteract(player, pos))
          throw new IllegalArgumentException("此处没有建设权限");
      }
      return node;
    }
  }

  public RoadRecord connect(
      ServerLevel level,
      ServerPlayer player,
      BlockPos a,
      BlockPos b,
      Settings settings,
      UUID replace) {
    return connect(level, player, a, b, settings, replace, false);
  }

  public RoadRecord connect(
      ServerLevel level,
      ServerPlayer player,
      BlockPos a,
      BlockPos b,
      Settings settings,
      UUID replace,
      boolean levelEnds) {
    return connect(level,player,a,b,settings,replace,levelEnds,false);
  }

  public RoadRecord connect(ServerLevel level,ServerPlayer player,BlockPos a,BlockPos b,Settings settings,UUID replace,boolean levelEnds,boolean forceJunction) {return connect(level,player,a,b,settings,replace,levelEnds,forceJunction,forceJunction);}
  public RoadRecord connect(ServerLevel level,ServerPlayer player,BlockPos a,BlockPos b,Settings settings,UUID replace,boolean levelEnds,boolean forceA,boolean forceB) {return connect(level,player,a,b,settings,replace,levelEnds,forceA,forceB,List.of());}
  public RoadRecord connect(ServerLevel level,ServerPlayer player,BlockPos a,BlockPos b,Settings settings,UUID replace,boolean levelEnds,boolean forceA,boolean forceB,List<BlockPos> controlNodes) {
    if(settings.style()==Style.TOLL)throw new IllegalArgumentException("请使用收费站生成器");
    if(TollRoads.reverseConnection(this,a,b))return connectDirected(level,player,b,a,settings,replace,levelEnds,forceB,forceA,controlNodes);
    return connectDirected(level,player,a,b,settings,replace,levelEnds,forceA,forceB,controlNodes);
  }
  private RoadRecord connectDirected(ServerLevel level,ServerPlayer player,BlockPos a,BlockPos b,Settings settings,UUID replace,boolean levelEnds,boolean forceA,boolean forceB,List<BlockPos> controlNodes) {
    var existingLane=replace==null?null:index.roads.get(replace);
    var current=replace==null?null:editableRoad(replace);
    if(replace!=null&&(current==null||!current.a().equals(a)||!current.b().equals(b)))
      throw new IllegalArgumentException("编辑目标与端点不一致，请重新右键目标道路");
    var savedAttachments=current==null?RoadAttachments.Data.EMPTY:current.settings().options().attachments();
    savedAttachments=CurveControlNodes.attachments(savedAttachments,current==null?List.of():current.settings().options().routing().controls(),settings.options().routing().controls());
    settings=settings.options(settings.options().attachments(savedAttachments).lanePoints(existingLane==null?LanePoints.Data.EMPTY:ElevatedAccessRoads.logicalLanes(current,existingLane.record)));
    settings=settings.options(settings.options().lanePoints(LaneAdditions.reserveAuthored(settings.options().lanePoints(),RoadProfile.catalog(settings).lanes())));
    // Individual gantries belong to the gantry tool. A stale ordinary editor
    // must neither erase a later edit nor restore a gantry that was removed.
    if(current!=null)settings=settings.options(settings.options().infrastructure(
        settings.options().infrastructure().gantryEdits(current.settings().options().infrastructure().gantryEdits())));
    try (var timing=RoadTimings.start("connect",index.roads.size(),1);
         var workChunks = RoadWorkChunks.open(level)) {
      var roadType=RoadProfile.catalog(settings.style()).type();
      if(roadType==RoadProfile.Type.HIGHWAY){forceA=false;forceB=false;}
      boolean throughRoad=roadType==RoadProfile.Type.ORDINARY||roadType==RoadProfile.Type.HIGHWAY;
      if(streets.containsKey(replace)&&!throughRoad)throw new IllegalArgumentException("路口接路不能直接改为匝道，请先删除此支路");
      boolean simpleVertical=com.sora.splineroads.core.RoadTunnelFit.enabled(settings)&&AutoJunctions.center(this,a)==null&&AutoJunctions.center(this,b)==null&&AutoJunctions.degree(this,a,replace)<2&&AutoJunctions.degree(this,b,replace)<2&&!forceA&&!forceB;
      if(throughRoad && !simpleVertical && (AutoJunctions.handles(this,a,b,replace,forceA||forceB)
          ||roadType==RoadProfile.Type.ORDINARY&&AutoJunctions.incompatible(this,a,b,replace,settings)))
        return AutoJunctions.connect(level,player,a,b,settings,replace,forceA,forceB,levelEnds,controlNodes);
      if (a.equals(b)) throw new IllegalArgumentException("请选择两个不同的端点");
      if (Math.hypot((double) a.getX() - b.getX(), (double) a.getZ() - b.getZ())
          > com.sora.splineroads.core.RoadLimits.MAX_ENDPOINT_DISTANCE + 24)
        throw new IllegalArgumentException("端点距离超过 2048 格，请增加中间端点");
      NodeEntity na = requireNode(level, a, player), nb = requireNode(level, b, player);
      ManualPlannerHistory.capture(level,List.of(a.asLong(),b.asLong()));
      RoadRecord previous =
          replace == null
              ? null
              : Optional.ofNullable(index.roads.get(replace))
                  .orElseThrow(() -> new IllegalArgumentException("道路已经被删除"))
                  .record;
      UUID owner =
          previous == null
              ? (player == null ? new UUID(0, 0) : player.getUUID())
              : previous.owner();
      if (previous != null) {
        if (previous.assembly() != null) throw new IllegalArgumentException("自动立交需要使用立交连接器整体编辑");
        if (player != null) requireOwner(player, owner);
        if (!previous.a().equals(a) || !previous.b().equals(b))
          throw new IllegalArgumentException("道路端点已改变");
      }
      for (var road : index.roads.values())
        if (!road.record.id().equals(replace) && !(settings.laneRamp() && road.record.settings().laneRamp())
            && ((road.record.a().equals(a) && road.record.b().equals(b))
                || (road.record.a().equals(b) && road.record.b().equals(a))))
          throw new IllegalArgumentException("这两个端点已经有道路，右键路面即可编辑");

      var ah =
          constructionHint(level, a, true, replace, nb.constructionNode().position(), levelEnds);
      var bh =
          constructionHint(level, b, false, replace, na.constructionNode().position(), levelEnds);
      boolean linkedA = ah.linked(), linkedB = bh.linked();
      var seamA=ah;var seamB=bh;
      if (settings.mode() != Mode.AUTO) {
        Node ma =
            previous == null
                ? connectionNode(level,a,true,replace,nb.constructionNode().position())
                : new Node(
                    na.constructionNode().position(),
                    previous.start().yaw(),
                    previous.start().grade());
        Node mb =
            previous == null
                ? connectionNode(level,b,false,replace,na.constructionNode().position())
                : new Node(
                    nb.constructionNode().position(), previous.end().yaw(), previous.end().grade());
        if(liveEndpoint(a,replace))ma=new Node(seamA.node().position(),ma.yaw(),ma.grade());
        if(liveEndpoint(b,replace))mb=new Node(seamB.node().position(),mb.yaw(),mb.grade());
        if (levelEnds) {
          ma = atGround(ma, a);
          mb = atGround(mb, b);
        }
        ah = RoadPlanner.Hint.free(ma);
        bh = RoadPlanner.Hint.free(mb);
      }
      settings = joinWidths(settings, endpointWidth(a, replace), endpointWidth(b, replace));
      CompoundTag joins=new CompoundTag();jointPayload(joins,a,b,replace);
      settings = joinSections(settings,joins,previous);
      var plan = previous==null?null:previous.retainedPlan(settings,ah,bh,
          new Node(na.constructionNode().position(),previous.start().yaw(),previous.start().grade()),
          new Node(nb.constructionNode().position(),previous.end().yaw(),previous.end().grade()));
      if(plan==null)plan = RoadTunnelFit.plan(ah, bh, settings);
      RoadConnectionChecks.require(plan,seamA,seamB);
      RoadRecord record =
          new RoadRecord(
              replace == null ? UUID.randomUUID() : replace,
              owner,
              a,
              b,
              plan.start(),
              plan.end(),
              plan.settings(),
              settings.mode() == Mode.AUTO,
              4);
      if(previous!=null)record=previous.retainAlignment(record,settings,na.constructionNode(),nb.constructionNode());
      record=RoadCrossingTransitions.fit(record,RoadCrossingTransitions.candidates(this));
      if(!AutoCrossings.hits(record,AutoJunctions.logical(this).values()).isEmpty())
        return AutoJunctions.connectRecord(level,player,record,new CompoundTag(),controlNodes);
      List<RoadIndex.Built> changes = new ArrayList<>();
      Set<UUID> removed = new HashSet<>();
      if (replace != null) removed.add(replace);
      // A retained AUTO slice can reject the provisional plan's live median
      // shift. Commit its actual saved endpoints to the markers as well, so a
      // second furniture edit still sees the same authored nodes.
      Node movedA = new Node(record.start().position(), na.yaw, levelEnds ? 0 : na.grade),
          movedB = new Node(record.end().position(), nb.yaw, levelEnds ? 0 : nb.grade);
      Map<BlockPos, Node> updates = new LinkedHashMap<>();
      if (!liveEndpoint(a,replace) && (movedA.position().distance(na.node().position()) > 1e-8 || levelEnds))
        updates.put(a, movedA);
      if (!liveEndpoint(b,replace) && (movedB.position().distance(nb.node().position()) > 1e-8 || levelEnds))
        updates.put(b, movedB);
      // Migrate the connected legacy-default cluster together; no new half-level seam is
      // introduced.
      ArrayDeque<BlockPos> queue = new ArrayDeque<>(updates.keySet());
      while (!queue.isEmpty()) {
        BlockPos pos = queue.removeFirst();
        for (var built : index.roads.values()) {
          var r = built.record;
          if (!r.a().equals(pos) && !r.b().equals(pos)) continue;
          BlockPos other = r.a().equals(pos) ? r.b() : r.a();
          if (updates.containsKey(other)) continue;
          NodeEntity entity = requireNode(level, other, player);
          Node next = entity.constructionNode();
          if (next.position().distance(entity.node().position()) > 1e-8) {
            updates.put(other, next);
            queue.add(other);
          }
        }
      }
      for (var built : index.roads.values()) {
        RoadRecord r = built.record;
        if (r.id().equals(replace) || (!updates.containsKey(r.a()) && !updates.containsKey(r.b())))
          continue;
        // Ground-ended saves visit shared markers even when their geometry is
        // unchanged. Keep those roads out of the direct-edit list; a changed
        // joint section is still enlisted below by normalizeTransitions.
        boolean changedA=updates.containsKey(r.a())&&!sameNodeIntent(updates.get(r.a()),requireNode(level,r.a(),null).node());
        boolean changedB=updates.containsKey(r.b())&&!sameNodeIntent(updates.get(r.b()),requireNode(level,r.b(),null).node());
        if(!changedA&&!changedB)continue;
        if (RoadTunnelFit.enabled(settings) && r.assembly()!=null)
          throw new IllegalArgumentException("要移动的桥隧端点属于路口或立交；请固定该端，仅调整另一端");
        if (player != null) requireOwner(player, r.owner());
        var ha = hint(level, r.a(), true, r.id());
        var hb = hint(level, r.b(), false, r.id());
        Node aa = r.start(), bb = r.end();
        if (updates.containsKey(r.a())) {
          Node before = requireNode(level, r.a(), null).node();
          ha = editedHint(ha, updates.get(r.a()), before);
          aa =
              editedHint(new RoadPlanner.Hint(aa, true, true, true), updates.get(r.a()), before)
                  .node();
        }
        if (updates.containsKey(r.b())) {
          Node before = requireNode(level, r.b(), null).node();
          hb = editedHint(hb, updates.get(r.b()), before);
          bb =
              editedHint(new RoadPlanner.Hint(bb, true, true, true), updates.get(r.b()), before)
                  .node();
        }
        Settings oldSettings = r.settings();
 if (r.automatic()) {
          var adjusted =
              RoadPlanner.plan(
                  ha, hb, RoadPlanner.mode(oldSettings, Mode.AUTO, oldSettings.arcDegrees()));
          aa = adjusted.start();
          bb = adjusted.end();
          oldSettings = adjusted.settings();
        }
        // A marker move can leave this particular locked road unchanged too.
        // Preserve its saved alignment, phase and derived-history receipt.
        if(aa.equals(r.start())&&bb.equals(r.end())&&oldSettings.equals(r.settings()))continue;
        changes.add(
            new RoadIndex.Built(
                new RoadRecord(
                    r.id(),
                    r.owner(),
                    r.a(),
                    r.b(),
                    aa,
                    bb,
                    oldSettings,
                    r.automatic(),
                    r.clearance())));
        removed.add(r.id());
      }
      changes.add(new RoadIndex.Built(record));
      Map<BlockPos,BlockPos> relocated=new HashMap<>();List<NodeMove> moves=new ArrayList<>();
      for(var pos:controlNodes){requireNode(level,pos,player);if(pos.equals(a)||pos.equals(b)||linked(pos))throw new IllegalArgumentException("控制端点已被使用");moves.add(NodeMove.retire(pos));}
      if(RoadTunnelFit.enabled(settings))for(var entry:updates.entrySet()){
        BlockPos from=entry.getKey();Node target=entry.getValue();var marker=requireNode(level,from,player);
        if(target.position().sub(marker.node().position()).horizontalLength()<1e-7)continue;
        V p=target.position();BlockPos to=BlockPos.containing(p.x(),p.y(),p.z());
        relocated.put(from,to);moves.add(new NodeMove(from,to,target,marker.saveWithoutMetadata()));
      }
      if(!moves.isEmpty()){
        for(int i=0;i<changes.size();i++){
          var r=changes.get(i).record;
          changes.set(i,new RoadIndex.Built(new RoadRecord(r.id(),r.owner(),relocated.getOrDefault(r.a(),r.a()),relocated.getOrDefault(r.b(),r.b()),
              r.start(),r.end(),r.settings(),r.automatic(),r.clearance(),r.structures(),r.endCaps(),r.buildVersion(),r.assembly(),r.alignment(),r.furniturePhase(),r.junction(),r.toll(),r.fragment())));
        }
        var selected=new HashSet<>(updates.keySet());selected.addAll(controlNodes);replaceBatch(level,player,changes,removed,false,selected,moves);
      }else replaceBatch(level, player, changes, removed);
      updates.forEach((pos, n) -> requireNode(level, relocated.getOrDefault(pos,pos), null).apply(n));
      na=requireNode(level,relocated.getOrDefault(a,a),null);nb=requireNode(level,relocated.getOrDefault(b,b),null);
      na.apply(!linkedA && !na.headingLocked ? record.start() : movedA);
      nb.apply(!linkedB && !nb.headingLocked ? record.end() : movedB);
      if (levelEnds) {
        na.heightExplicit = false;
        nb.heightExplicit = false;
        na.gradeLocked = false;
        nb.gradeLocked = false;
        na.setChanged();
        nb.setChanged();
      }
      return index.roads.get(record.id()).record;
    }
  }

  public static Node atGround(Node n, BlockPos p) {
    return new Node(new V(n.position().x(), p.getY(), n.position().z()), n.yaw(), 0);
  }

  public RoadPlanner.Hint constructionHint(
      ServerLevel level, BlockPos pos, boolean start, UUID exclude, V towards, boolean levelEnds) {
    var base = hint(level, pos, start, exclude, towards);
    Node n = requireNode(level, pos, null).constructionNode();
    // A physical merge port may be laterally shifted from its logical marker.
    // Keep the marker and authored host axis unchanged; only the new road starts at the live port.
    Node v = new Node(liveEndpoint(pos,exclude)?base.node().position():n.position(), base.node().yaw(), base.node().grade());
    if (levelEnds) v = atGround(v, pos);
    return new RoadPlanner.Hint(
        v, base.headingLocked(), levelEnds || base.gradeLocked(), base.linked());
  }

  /** Manual modes share the same authored heading in preview and commit. */
  public Node connectionNode(ServerLevel level,BlockPos pos,boolean start,UUID exclude,V towards){
    var entity=requireNode(level,pos,null);var node=entity.constructionNode();
    if(!entity.plannerHeading||entity.headingLocked)return node;
    var seam=constructionHint(level,pos,start,exclude,towards,false);
    V chord=start?towards.sub(node.position()):node.position().sub(towards);
    return new Node(liveEndpoint(pos,exclude)?seam.node().position():node.position(),
        seam.linked()?seam.node().yaw():RoadPlanner.yaw(chord),node.grade());
  }

  public RoadPlanner.Hint hint(ServerLevel level, BlockPos pos, boolean start, UUID exclude) {
    var current = exclude == null ? null : index.roads.get(exclude);
    V towards =
        current == null
            ? null
            : (current.record.a().equals(pos)
                ? current.record.end().position()
                : current.record.start().position());
    return hint(level, pos, start, exclude, towards);
  }

  public RoadPlanner.Hint hint(
      ServerLevel level, BlockPos pos, boolean start, UUID exclude, V towards) {
    NodeEntity entity = requireNode(level, pos, null);
    Node n = entity.node();
    RoadIndex.Built best = null;
    double bestScore = -Double.MAX_VALUE;
    for (var built : index.roads.values()) {
      if (built.record.id().equals(exclude)
          || (!built.record.a().equals(pos) && !built.record.b().equals(pos))
          || !transitionEndpoint(built,pos)&&!built.record.settings().style().ramp()) continue;
      double score =
          towards == null
              ? built.record.settings().width()
              : -away(built, pos).horizontalUnit().dot(towards.sub(n.position()).horizontalUnit());
      if (best == null
          || score > bestScore + 1e-8
          || (Math.abs(score - bestScore) < 1e-8
              && built.record.id().compareTo(best.record.id()) < 0)) {
        best = built;
        bestScore = score;
      }
    }
    if (best != null && best.mesh.closed()) {
      // Diameter markers are points ON a closed carriageway, not its start/end caps.
      // Normal feeders meet the ring at grade.
      var q = RoadQueries.project(best.mesh, n.position());
      return new RoadPlanner.Hint(
          new Node(q.sample().center(), n.yaw(), 0), entity.headingLocked, true, true);
    }
    if (best != null) {
      V d = away(best, pos).mul(start ? -1 : 1);
      return new RoadPlanner.Hint(
          new Node(best.record.fragment()!=null||RoadEndpointSections.changed(best.mesh,best.record.a().equals(pos))?com.sora.splineroads.core.RoadMedianAnchor.position(best.record.caps(0).mesh(),best.record.a().equals(pos)):n.position(), RoadPlanner.yaw(d), d.y()), true, true, true);
    }
    return new RoadPlanner.Hint(n, entity.headingLocked, entity.gradeLocked, false);
  }

  public long connectionsAt(BlockPos p) {
    return index.atNode(p).size();
  }

  public double endpointWidth(BlockPos p, UUID exclude) {
    double width = 0;
    for (var built : index.roads.values())
      if (!built.record.id().equals(exclude)
          && (transitionEndpoint(built,p)||built.record.settings().style().ramp())
          && (built.record.a().equals(p) || built.record.b().equals(p)))
        width =
            Math.max(
                width,
                2
                    * (built.record.a().equals(p) ? built.mesh.first() : built.mesh.last())
                        .halfWidth());
    return width;
  }

  public Settings endpointSettings(BlockPos p, UUID exclude) {
    return index.atNode(p).stream()
        .filter(id -> !id.equals(exclude))
        .map(index.roads::get)
        .filter(r -> r != null && transitionEndpoint(r,p) && !r.record.settings().style().ramp())
        .sorted(Comparator.comparing(r -> r.record.id()))
        .map(r -> RoadEndpointSections.changed(r.mesh,r.record.a().equals(p))?endpointSection(r,p):r.record.fragment()==null && r.record.assembly() == null && !r.record.settings().options().ends().persistent() ? authoredSection(r.record.settings()) : endpointSection(r, p))
        .findFirst()
        .orElse(null);
  }

  private boolean liveEndpoint(BlockPos p,UUID exclude){
    return index.atNode(p).stream().filter(id->!id.equals(exclude)).map(index.roads::get)
        .anyMatch(r->transitionEndpoint(r,p)&&(r.record.toll()!=null||r.record.fragment()!=null||RoadEndpointSections.changed(r.mesh,r.record.a().equals(p))));
  }
  private Settings endpointSettings(BlockPos p,UUID exclude,boolean start){
    var donor=index.atNode(p).stream().filter(id->!id.equals(exclude)).map(index.roads::get)
        .filter(r->r!=null&&transitionEndpoint(r,p)&&!r.record.settings().style().ramp())
        .sorted(Comparator.comparing(r->r.record.id())).findFirst();
    if(donor.isEmpty())return null;var r=donor.get();boolean first=r.record.a().equals(p);
    var section=RoadEndpointSections.changed(r.mesh,first)?endpointSection(r,p):
        r.record.fragment()==null&&r.record.assembly()==null&&!r.record.settings().options().ends().persistent()?authoredSection(r.record.settings()):endpointSection(r,p);
    return RoadEndpointSections.orient(section,first==start);
  }

  /** Never feed an automatically derived seam back into the next seam calculation. */
  static Settings authoredSection(Settings s) {
    return s.taper(s.width(),s.width()).options(s.options().ends(RoadTransitions.Ends.NONE));
  }

  /** Junction pads have no traffic profile. Only an approach's real outside port can be joined. */
  static boolean transitionEndpoint(RoadIndex.Built road,BlockPos pos){
    var r=road.record;
    if(LaneTopology.metadata(r).link()!=null)return false;
    if(r.junction()!=null)return r.junction().get().arm()>=0&&r.a().equals(pos)&&!r.b().equals(pos);
    // A trimmed street retains the logical junction marker, but its physical port is
    // at the cutback. The junction owns that seam, not other streets at the marker.
    var ends=r.settings().options().ends();
    if(r.fragment()!=null){
      boolean first=r.a().equals(pos);var physical=RoadSplitGeometry.node(road.mesh,first?0:road.mesh.length()).position();
      if(BlockPos.containing(physical.x(),physical.y(),physical.z()).equals(pos))return true;
      // Lossless slices include the old free end's half-block cap. Its marker
      // remains the logical endpoint; the next road must meet the actual cap.
      var node=first?r.start():r.end();
      V outward=(first?road.mesh.first():road.mesh.last()).left().left().mul(first?1:-1);
      // A junction-trimmed fragment stores its cutback as start/end too. That
      // is not the logical center marker and must never become a live port.
      // A later slice may also store the actual half-block free cap as its node.
      V uncapped=node.position().sub(outward.mul(.5)).add(new V(0,node.grade()*(first?.5:-.5),0));
      if(!BlockPos.containing(node.position().x(),node.position().y(),node.position().z()).equals(pos)
          &&!BlockPos.containing(uncapped.x(),uncapped.y(),uncapped.z()).equals(pos))return false;
      V delta=physical.sub(node.position());
      double along=delta.dot(outward);
      return along>=-1e-7&&along<=1+1e-7&&Math.abs(delta.dot(outward.left()))<1e-6
          &&Math.abs(delta.y()-along*node.grade()*(first?-1:1))<1e-6;
    }
    if(r.a().equals(pos)&&ends.trimmedStart()>1e-6
        ||r.b().equals(pos)&&ends.trimmedEnd()>1e-6)return false;
    // Standalone legacy unmarked roads still have ordinary endpoints; assembly infill does not.
    return r.assembly()==null||r.settings().style()!=Style.UNMARKED;
  }

  /** Read the section actually built at this end, including assembly grid padding/tapers. */
  static Settings endpointSection(RoadIndex.Built road, BlockPos pos) {
    boolean first = road.record.a().equals(pos);
    if(road.record.toll()!=null)return com.sora.splineroads.core.TollPlaza.port(road.record.toll(),first);
    if(road.record.fragment()!=null||RoadEndpointSections.changed(road.mesh,first)
        ||road.record.assembly()!=null&&road.record.junction()==null&&!road.record.settings().style().ramp())
      return RoadEndpointSections.section(road.record.caps(0).mesh(),first,false);
    var at = first ? road.mesh.first() : road.mesh.last();
    var base = road.record.junction()!=null&&road.record.junction().get().arm()>=0
        ? JunctionRoads.editable(road.record.junction().spec().arms().get(road.record.junction().get().arm()))
        : road.record.settings();
    var end = first ? base.options().ends().start() : base.options().ends().end();
    // Junction approach settings describe its own outer port. External end tapers
    // belong to the original street and may describe a different, remote road.
    if(road.record.junction()!=null) end=null;
    var s = end == null ? base : end.settings(base.options().leftTraffic());
    var options = s.options().ends(RoadTransitions.Ends.NONE)
        .infrastructure(base.options().infrastructure()).cycleFinish(s.options().cycle() ? (base.options().cycleAsphalt() ? RoadProfile.Options.CycleFinish.ASPHALT : RoadProfile.Options.CycleFinish.GREEN) : RoadProfile.Options.CycleFinish.NONE);
    // Old section records predate sidewalks. Missing is inheritance, never "disabled".
    if(end == null || end.sidewalk() == null) options=options.sidewalk(base.options().sidewalk());
    var result=new Settings(Mode.STRAIGHT,s.style(),at.halfWidth()*2,base.thickness(),
        base.tension(),base.arcDegrees()).structure(base.structure()).options(options);
    result.validate();
    return result;
  }

  public void jointPayload(CompoundTag payload, BlockPos a, BlockPos b, UUID exclude) {
    payload.remove("JoinSectionA");payload.remove("JoinSectionB");
    Settings start = endpointSettings(a, exclude,true), end = endpointSettings(b, exclude,false);
    if (start != null) payload.put("JoinSectionA", RoadRecord.writeSettings(start));
    if (end != null) payload.put("JoinSectionB", RoadRecord.writeSettings(end));
    payload.putBoolean("FixedSectionA",fixedEndpoint(a,exclude));
    payload.putBoolean("LiveSectionA",liveEndpoint(a,exclude));
    payload.putBoolean("FixedSectionB",fixedEndpoint(b,exclude));
    payload.putBoolean("LiveSectionB",liveEndpoint(b,exclude));
  }

  private boolean fixedEndpoint(BlockPos p,UUID exclude){return index.atNode(p).stream().filter(id->!id.equals(exclude)).map(index.roads::get).anyMatch(r->(r.record.fragment()!=null||r.record.assembly()!=null||RoadEndpointSections.changed(r.mesh,r.record.a().equals(p)))&&transitionEndpoint(r,p)&&!r.record.settings().style().ramp());}

  public static Settings joinSections(Settings s, CompoundTag payload) {
    return joinSections(s,payload,payload.contains("Road")?RoadRecord.load(payload.getCompound("Road")):null);
  }

  public static Settings joinSections(Settings s, CompoundTag payload,RoadRecord previous) {
    return RoadRecord.joinSections(s,
        payload.contains("JoinSectionA")?RoadRecord.readSettings(payload.getCompound("JoinSectionA")):null,
        payload.contains("JoinSectionB")?RoadRecord.readSettings(payload.getCompound("JoinSectionB")):null,
        payload.getBoolean("FixedSectionA"),payload.getBoolean("FixedSectionB"),previous);
  }

  public static Settings joinWidths(Settings s, double a, double b) {
    return s.style().ramp() && !s.laneRamp()
        ? s.taper(Math.max(s.width(), a), Math.max(s.width(), b))
        : s;
  }

  public static CompoundTag writeHint(RoadPlanner.Hint hint) {
    CompoundTag t = RoadRecord.writeNode(hint.node());
    t.putBoolean("HeadingLocked", hint.headingLocked());
    t.putBoolean("GradeLocked", hint.gradeLocked());
    t.putBoolean("Linked", hint.linked());
    return t;
  }

  public static RoadPlanner.Hint readHint(CompoundTag t) {
    return new RoadPlanner.Hint(
        RoadRecord.readNode(t),
        t.getBoolean("HeadingLocked"),
        t.getBoolean("GradeLocked"),
        t.getBoolean("Linked"));
  }

  public double inheritedWidth(BlockPos a, BlockPos b, double fallback, UUID exclude) {
    for (var r : index.roads.values())
      if (!r.record.id().equals(exclude)
          && (r.record.a().equals(a)
              || r.record.b().equals(a)
              || r.record.a().equals(b)
              || r.record.b().equals(b))) return r.record.settings().width();
    return fallback;
  }

  public void editNode(ServerLevel level, ServerPlayer player, BlockPos pos, Node node) {
    try (var timing=RoadTimings.start("edit_node",index.roads.size(),index.atNode(pos).size());
         var workChunks = RoadWorkChunks.open(level)) {
      if (index.atNode(pos).stream().anyMatch(id -> index.roads.get(id).record.assembly() != null))
        throw new IllegalArgumentException("路口或立交的端点请使用对应连接器整体编辑，或先删除该整体");
      NodeEntity entity = requireNode(level, pos, player);
      V p = node.position();
      if (!RoadGeometry.finite(p.x(), p.y(), p.z(), node.yaw(), node.grade())
          || Math.abs(p.x() - pos.getX() - .5) > 8
          || Math.abs(p.z() - pos.getZ() - .5) > 8
          || p.y() - pos.getY() < -8
          || p.y() - pos.getY() > 16
          || Math.abs(node.grade()) > .5)
        throw new IllegalArgumentException("端点偏移 X/Z：±8，Y：-8–16；坡度：±50%");
      // Saving the unchanged form must not rebuild an exact split fragment.
      if(sameNodeIntent(node,entity.node()))return;
      ManualPlannerHistory.capture(level,List.of(pos.asLong()));
      List<RoadIndex.Built> replacements = new ArrayList<>();
      Set<UUID> ids = new HashSet<>();
      for (var built : index.roads.values()) {
        RoadRecord r = built.record;
        if (r.a().equals(pos) || r.b().equals(pos)) {
          if (player != null) requireOwner(player, r.owner());
          ids.add(r.id());
          replacements.add(
              new RoadIndex.Built(
                  planNodeEdit(
                      r,
                      pos,
                      node,
                      entity.node(),
                      hint(level, r.a(), true, r.id()),
                      hint(level, r.b(), false, r.id()))));
        }
      }
      replaceBatch(level, player, replacements, ids);
      if (Math.abs(node.yaw() - entity.yaw) > 1e-7) entity.headingLocked = true;
      if (Math.abs(node.grade() - entity.grade) > 1e-7) entity.gradeLocked = true;
      if (Math.abs(node.position().y() - entity.node().position().y()) > 1e-8)
        entity.heightExplicit = true;
      entity.apply(node);
    }
  }

  /** Shared with the client: edits use exactly the same endpoint constraints as the preview. */
  public static RoadRecord planNodeEdit(
      RoadRecord r,
      BlockPos pos,
      Node edited,
      Node original,
      RoadPlanner.Hint ha,
      RoadPlanner.Hint hb) {
    if(sameNodeIntent(edited,original))return r;
    Node aa = r.start(), bb = r.end();
    Settings settings = r.settings();
 if (r.automatic()) {
      if (r.a().equals(pos)) ha = editedHint(ha, edited, original);
      if (r.b().equals(pos)) hb = editedHint(hb, edited, original);
      var plan =
          RoadPlanner.plan(ha, hb, RoadPlanner.mode(settings, Mode.AUTO, settings.arcDegrees()));
      aa = plan.start();
      bb = plan.end();
      settings = plan.settings();
    } else {
      if (r.a().equals(pos))
        aa = editedHint(new RoadPlanner.Hint(aa, true, true, true), edited, original).node();
      if (r.b().equals(pos))
        bb = editedHint(new RoadPlanner.Hint(bb, true, true, true), edited, original).node();
    }
    return new RoadRecord(
        r.id(), r.owner(), r.a(), r.b(), aa, bb, settings, r.automatic(), r.clearance());
  }

  private static boolean sameNodeIntent(Node a,Node b){
    return RoadGeometry.finite(a.position().x(),a.position().y(),a.position().z(),a.yaw(),a.grade())
        &&a.position().distance(b.position())<=1e-8&&Math.abs(Math.IEEEremainder(a.yaw()-b.yaw(),360))<=1e-7&&Math.abs(a.grade()-b.grade())<=1e-7;
  }
  private static RoadPlanner.Hint editedHint(RoadPlanner.Hint hint, Node edited, Node original) {
    boolean yawChanged = Math.abs(Math.IEEEremainder(edited.yaw() - original.yaw(),360)) > 1e-7,
        gradeChanged = Math.abs(edited.grade() - original.grade()) > 1e-7;
    double sign = hint.node().direction().dot(original.direction()) < 0 ? -1 : 1;
    V offset = hint.node().position().sub(original.position());
    double angle = Math.toRadians(edited.yaw() - original.yaw());
    V rotated =
        new V(
            offset.x() * Math.cos(angle) - offset.z() * Math.sin(angle),
            offset.y(),
            offset.x() * Math.sin(angle) + offset.z() * Math.cos(angle));
    Node n =
        new Node(
            edited.position().add(rotated),
            hint.node().yaw() + (yawChanged ? edited.yaw() - original.yaw() : 0),
            gradeChanged ? sign * edited.grade() : hint.node().grade());
    return new RoadPlanner.Hint(
        n, hint.headingLocked() || yawChanged, hint.gradeLocked() || gradeChanged, hint.linked());
  }

  public void remove(ServerLevel level, ServerPlayer player, UUID id) {
    var road = index.roads.get(id);
    if (road == null) throw new IllegalArgumentException("道路已经不存在");
    if(road.record.toll()!=null){TollRoads.remove(level,player,id);return;}
    if (player != null) requireOwner(player, road.record.owner());
    if(streets.containsKey(id)&&road.record.assembly()==null){AutoJunctions.removeStreet(level,player,id);return;}
    if (road.record.junction() != null) { Junctions.remove(level,player,road.record.assembly()); return; }
    if(ElevatedAccessRoads.saved(this,road.record.assembly())){ElevatedAccessRoads.remove(level,player,road.record.assembly());return;}
    if (road.record.assembly() != null) {
      if(interchanges.getOrDefault(road.record.assembly(),new CompoundTag()).getBoolean("YJunction")){Interchanges.remove(level,player,road.record.assembly());return;}
      Interchanges.removePart(level, player, id);
      return;
    }
    replaceBatch(level, player, List.of(), Set.of(id));
  }

  RoadRecord planSideHost(ServerLevel level,RoadIndex.Built host,List<RoadIndex.Built> all){
    int mask=0;for(boolean first:new boolean[]{true,false}){BlockPos pos=first?host.record.a():host.record.b();if(all.stream().noneMatch(r->!r.record.id().equals(host.record.id())&&LaneTopology.metadata(r.record).link()==null&&(r.record.a().equals(pos)||r.record.b().equals(pos))))mask|=first?1:2;}
    var capped=host.caps(mask);all.set(all.indexOf(host),capped);
    return StructurePlanner.plan(level,capped,all,terrainFill,terrainOriginal,new RoadTerrain.Cache(terrainFill,terrainOriginal),new RoadPlanningIndex(all));
  }

  public void updatePointMetadata(ServerLevel level,ServerPlayer player,RoadRecord r,RoadAttachments.Data value){
    if(r.toll()!=null)throw new IllegalArgumentException("收费站请使用收费站生成器编辑");
    if(player!=null){requireOwner(player,r.owner());for(var p:value.points())if(!level.mayInteract(player,BlockPos.containing(p.position().x(),p.position().y(),p.position().z())))throw new IllegalArgumentException("附属点位于受保护区域");}
    if(SideHosts.host(r)){SideHosts.updatePoints(level,r,value);return;}
    for(var access:r.settings().options().lanePoints().access()){
      var old=r.settings().options().attachments().points().stream().filter(p->p.id().equals(access.point())).findFirst().orElse(null);
      if(old==null)continue; // The access marker can belong to the preceding host.
      if(value.points().stream().noneMatch(old::equals))throw new IllegalArgumentException("请先删除高架连接，再移动或删除其出入点");
    }
    var next=r.withAttachments(value);
    index.put(RoadIndex.Built.loading(next));if(streets.containsKey(r.id()))streets.put(r.id(),streets.get(r.id()).withAttachments(value));setDirty();RoadNetwork.broadcastRoad(level,next);
  }

  void removeAttachedPoint(ServerLevel level,ServerPlayer player,RoadRecord road,RoadAttachments.Data value,UUID point){
    var next=road.withAttachments(value).structures(List.of());var proposed=new RoadIndex.Built(next);
    for(var other:index.roads.values())if(!other.record.id().equals(road.id())&&RoadIndex.overlapXZ(proposed.mesh,other.mesh,1))Interchanges.checkExternal(proposed.mesh,other.mesh,road.mesh());
    replaceBatch(level,player,List.of(proposed),Set.of(road.id()),true,Set.of(road.a(),road.b()),List.of(),RoadLimits.MAX_EDIT_CELLS,Set.of(point));
  }

  /** Unreferenced lane markers are metadata, not a road construction transaction. */
  void updateLanePointMetadata(ServerLevel level, ServerPlayer player, RoadRecord r, LanePoints.Data value) {
    if(r.toll()!=null)throw new IllegalArgumentException("收费站内部和接口不支持车道点");
    value=value.points(java.util.stream.Stream.concat(value.points().stream().filter(p->!p.automatic()),value.points().stream().filter(LanePoints.Point::automatic)).toList());
    if (player != null) {
      requireOwner(player, r.owner());
      if (!player.mayBuild() || player.isSpectator()) throw new IllegalArgumentException("没有建设权限");
      for (var point : value.points())
        if (!level.mayInteract(player, BlockPos.containing(point.position().x(), point.position().y(), point.position().z())))
          throw new IllegalArgumentException("车道点位于受保护区域");
    }
    var before = LaneTopology.metadata(r);
    for (var point : before.points()) {
      var next = value.points().stream().filter(p -> p.id().equals(point.id())).findFirst().orElse(null);
      if (!point.equals(next) && index.laneReferenced(LanePoints.Ref.lane(r.id(), point.id())))
        throw new IllegalArgumentException("已使用的车道点必须同步重建依赖匝道");
    }
    var next = index.lanePoints(r.id(), value).record;
    if (streets.containsKey(r.id())) streets.put(r.id(), streets.get(r.id()).withLanePoints(value));
    setDirty();
    RoadNetwork.broadcastLanePoints(level, r, next);
  }

  public void editLaneLine(ServerLevel level,ServerPlayer player,CompoundTag t){
    var built=index.roads.get(t.getUUID("Id"));if(built==null||built.record.junction()!=null||built.record.toll()!=null)throw new IllegalArgumentException("路段已改变，请重新选择");
    var r=built.record;if(player!=null)requireOwner(player,r.owner());
    if(t.getInt("Signature")!=GantryTool.signature(r))throw new IllegalArgumentException("道路已更新，请重新选择");
    var options=r.settings().options();
    if(t.contains("Station")){
      double station=t.getDouble("Station");if(!Double.isFinite(station)||station<0||station>built.mesh.length())throw new IllegalArgumentException("标线区间无效");
      var range=RoadAttachments.range(built.mesh,station);var paint=RoadAttachments.paint(built.mesh,station);
      if(t.contains("HideArrows"))paint=new RoadAttachments.Paint(paint.lines(),t.getBoolean("HideArrows"));
      else {String key=t.getString("Key");if(RoadLaneLines.lines(built.mesh).stream().noneMatch(l->l.key().equals(key)))throw new IllegalArgumentException("这根车道线已不存在");paint=new RoadAttachments.Paint(RoadLaneLines.with(paint.lines(),new RoadLaneLines.Edit(key,RoadLaneLines.Pattern.valueOf(t.getString("Pattern")),t.getDouble("Width"))),paint.hideArrows());}
      updatePointMetadata(level,player,r,RoadAttachments.edit(built.mesh,range,paint));return;
    }
    if(!options.attachments().points().isEmpty())throw new IllegalArgumentException("请重新选择标线编辑区间");
    if(t.contains("HideArrows"))options=options.hideArrows(t.getBoolean("HideArrows"));
    else {
      String key=t.getString("Key");if(RoadLaneLines.lines(built.mesh).stream().noneMatch(l->l.key().equals(key)))throw new IllegalArgumentException("这根车道线已不存在");
      var e=new RoadLaneLines.Edit(key,RoadLaneLines.Pattern.valueOf(t.getString("Pattern")),t.getDouble("Width"));
      options=options.laneLines(RoadLaneLines.with(options.laneLines(),e));
    }
    var settings=r.settings().options(options);
    // Paint-only edit: preserve every stored sample, support, neighboring record and world block.
    var next=new RoadRecord(r.id(),r.owner(),r.a(),r.b(),r.start(),r.end(),settings,r.automatic(),r.clearance(),r.structures(),r.endCaps(),r.buildVersion(),r.assembly(),r.alignment(),r.furniturePhase(),r.junction(),r.toll(),r.fragment());
    index.put(RoadIndex.Built.loading(next));if(streets.containsKey(r.id()))streets.put(r.id(),streets.get(r.id()).settings(settings));setDirty();RoadNetwork.broadcastRoad(level,next);
  }

  public void editGantry(ServerLevel level,ServerPlayer player,CompoundTag command) { editGantry(level,player,command,null); }
  public int createGantry(ServerLevel level,ServerPlayer player,UUID road,V point){
    var built=index.roads.get(road);if(built==null||built.record.junction()!=null||built.record.toll()!=null||LaneTopology.metadata(built.record).link()!=null)throw new IllegalArgumentException("请右键要放置龙门架的普通道路或高速道路");
    var q=RoadQueries.horizontal(built.mesh,point);if(Math.abs(q.lateral())>q.sample().halfWidth()+2||Math.abs(point.y()-q.sample().center().y())>3)throw new IllegalArgumentException("请右键道路表面选择放置位置");
    var creation=RoadGantry.create(built.mesh,q.sample().distance());var command=new CompoundTag();command.putUUID("Id",road);command.putInt("Signature",GantryTool.signature(built.record));command.putInt("Slot",creation.slot());
    editGantry(level,player,command,creation);return creation.slot();
  }
  private void editGantry(ServerLevel level,ServerPlayer player,CompoundTag command,RoadGantry.Edit creation) {
    var built=index.roads.get(command.getUUID("Id"));
    if(built==null||built.record.junction()!=null||built.record.toll()!=null)throw new IllegalArgumentException("道路已改变，请重新选择龙门架");
    var r=built.record;if(player!=null){requireOwner(player,r.owner());if(!player.mayBuild()||player.isSpectator())throw new IllegalArgumentException("没有建设权限");}
    if(command.getInt("Signature")!=GantryTool.signature(r))throw new IllegalArgumentException("道路已更新，请重新打开龙门架编辑器");
    int slot=command.getInt("Slot");var oldEdit=creation==null?RoadGantry.station(built.mesh,slot).edit():creation;
    boolean remove=command.getBoolean("Remove");
    int flow=command.getInt("Flow");if(flow==2){if(player==null)throw new IllegalArgumentException("当前方向需要玩家朝向");var look=player.getLookAngle();flow=RoadGantry.playerFlow(RoadGantry.station(built.mesh,slot).sample(),new V(look.x,look.y,look.z));}
    var signKeys=new ArrayList<com.sora.splineroads.core.SmartSigns.Key>();
    com.sora.splineroads.core.SmartSigns.Mode signMode=command.contains("SignMode")?com.sora.splineroads.core.SmartSigns.Mode.valueOf(command.getString("SignMode")):null;
    if(signMode!=null&&!remove){
      if(flow==0&&signMode!=com.sora.splineroads.core.SmartSigns.Mode.SPEED_LIMIT&&signMode!=com.sora.splineroads.core.SmartSigns.Mode.LANE_INDICATOR&&!command.getBoolean("Reset"))throw new IllegalArgumentException("限速或车道指示龙门架支持双向设置");
      for(int direction:flow==0?new int[]{1,-1}:new int[]{flow}){
        var key=new com.sora.splineroads.core.SmartSigns.Key(r.id(),slot,direction);
        smartSigns.validateMode(key,signMode,command.getLong(flow==0&&direction<0?"ReverseSignRevision":"SignRevision"));signKeys.add(key);
      }
    }
    var c=r.settings().options().infrastructure();
    if(creation!=null)c=c.edit(creation);else if(remove)c=oldEdit.independent()?c.reset(slot):c.edit(oldEdit.parameters(oldEdit.offset(),oldEdit.clearance(),RoadInfrastructure.Gantry.OFF,oldEdit.reverse()));
    else if(command.getBoolean("Reset"))c=oldEdit.independent()?c.edit(oldEdit.parameters(0,5.6,RoadInfrastructure.Gantry.SIGNS,false)):c.reset(slot);
    else c=c.edit(oldEdit.parameters(command.getDouble("Offset"),command.getDouble("Clearance"),RoadInfrastructure.Gantry.valueOf(command.getString("Style")),command.getBoolean("Reverse")));
    var settings=r.settings().options(r.settings().options().infrastructure(c));
    var next=r.settings(settings);
    try(var rasters=RoadEditRasters.open();var budget=com.sora.splineroads.core.RoadPlanningBudget.cancellable("龙门架局部编辑");var chunks=RoadWorkChunks.open(level)){
      var candidate=new RoadIndex.Built(next);
      var st=RoadGantry.station(remove?built.mesh:candidate.mesh,slot);
      if(player!=null&&player.position().distanceToSqr(new net.minecraft.world.phys.Vec3(st.sample().center().x(),st.sample().center().y(),st.sample().center().z()))>256*256)throw new IllegalArgumentException("离所选龙门架太远");
      // Terrain probes load only the selected feet; no whole-road clearance scan.
      var members=new ArrayList<>(creation==null?RoadGantry.members(built.mesh,slot,r.structures()):List.<RoadStructures.Part>of());
      var retainedParts=new ArrayList<>(r.structures());retainedParts.removeAll(members);
      var fresh=remove?List.<RoadStructures.Part>of():StructurePlanner.gantry(level,candidate.structures(retainedParts),List.copyOf(index.roads.values()),terrainFill,terrainOriginal,slot);
      var parts=new ArrayList<>(r.structures());parts.removeAll(members);parts.addAll(fresh);
      candidate=candidate.structures(parts);
      var changed=new ArrayList<>(members);changed.addAll(fresh);
      Set<Long> touched=new HashSet<>();
      for(var cell:RoadRaster.structures(changed,null).keySet())touched.add(BlockPos.asLong(cell.x(),cell.y(),cell.z()));
      var finalRoads=new ArrayList<RoadIndex.Built>();for(var road:index.roads.values())finalRoads.add(road==built?candidate:road);
      Map<Long,List<RoadIndex.Built>> byChunk=new HashMap<>(),body=new HashMap<>();Set<Long> air=new HashSet<>(),dry=new HashSet<>(),needed=new HashSet<>();
      for(long key:touched)needed.add(new net.minecraft.world.level.ChunkPos(BlockPos.of(key)).toLong());chunks.load(needed);
      for(var road:finalRoads)for(long chunk:road.chunks)if(needed.contains(chunk))byChunk.computeIfAbsent(chunk,k->new ArrayList<>()).add(road);
      for(long key:touched){var pos=BlockPos.of(key);for(var road:byChunk.getOrDefault(new net.minecraft.world.level.ChunkPos(pos).toLong(),List.of())){
        if(!road.boxes(pos).isEmpty())body.computeIfAbsent(key,k->new ArrayList<>()).add(road);
        if(road.clearanceAt(pos)){air.add(key);if(road.tunnelAt(pos))dry.add(key);}
      }}
      // The normal atomic validator still enforces ownership, protection, containers,
      // world bounds, road collision retention and terrain restoration on the delta.
      validateAndCommit(level,player,new PreparedEdit(List.of(candidate),Set.of(r.id()),false,touched,Set.of(),Set.of(),
          new HashMap<>(sidewalkPlaced),Set.copyOf(apronPlaced),body,air,dry,byChunk,Set.of(r.a(),r.b()),finalRoads,List.of(),RoadLimits.MAX_EDIT_CELLS,RoadWorkChunks.held(level)),false);
      if(!signKeys.isEmpty())smartSigns.applyModes(signKeys,signMode);
    }
  }

  private void checkJoints(List<RoadIndex.Built> replacements, Set<UUID> removed) {
    List<RoadIndex.Built> all = new ArrayList<>();
    for (var r : index.roads.values()) if (!removed.contains(r.record.id())) all.add(r);
    all.addAll(replacements);
    Set<BlockPos> checked = new HashSet<>();
    for (var road : replacements)
      for (BlockPos p : List.of(road.record.a(), road.record.b())) {
        if (!checked.add(p)) continue;
        var links =
            all.stream().filter(r -> r.record.a().equals(p) || r.record.b().equals(p)).toList();
        // Automatic approaches share a logical center marker but have different trimmed
        // physical endpoints. The junction planner owns those seams, not this point check.
        if(links.stream().anyMatch(r->r.record.junction()!=null))continue;
        links=links.stream().filter(r->transitionEndpoint(r,p)).toList();
        UUID sameGroup = links.isEmpty() ? null : links.get(0).record.assembly();
        if (sameGroup != null
            && links.stream().allMatch(r -> sameGroup.equals(r.record.assembly()))) continue;
        long laneRamps = links.stream().filter(r -> r.record.settings().laneRamp()).count();
        if (laneRamps > 16) throw new IllegalArgumentException("同一端点最多连接 16 条匝道，请分组增加端点");
        links = links.stream().filter(r -> !r.record.settings().laneRamp()).toList();
        if (links.size() > 3) throw new IllegalArgumentException("一个端点支持主路和两条支路；更多分叉请增加端点");
        if (links.size() < 2) continue;
        var rings = links.stream().filter(r -> r.mesh.closed()).toList();
        if (!rings.isEmpty()) {
          for (var ring : rings)
            for (var feeder : links) {
              if (feeder == ring) continue;
              V point =
                  (feeder.record.a().equals(p) ? feeder.mesh.first() : feeder.mesh.last()).center();
              var q = RoadQueries.project(ring.mesh, point);
              if (q.horizontalDistance() > .05
                  || Math.abs(point.y() - q.sample().center().y()) > .01)
                throw new IllegalArgumentException("接环道路必须与环道路面等高并接在环道上");
            }
          continue;
        }
        boolean ramp = links.stream().anyMatch(r -> r.record.settings().style().ramp());
        if (links.size() == 3 && !ramp) throw new IllegalArgumentException("Y 字分叉请选择单车道或双车道匝道样式");
        var first = links.get(0);
        V reference = away(first, p);
        var origin = first.record.a().equals(p) ? first.mesh.first() : first.mesh.last();
        boolean opposite = false;
        for (int i = 1; i < links.size(); i++) {
          var r = links.get(i);
          V d = away(r, p);
          double dot = reference.horizontalUnit().dot(d.horizontalUnit());
          double sign = dot < 0 ? -1 : 1;
          var sample = r.record.a().equals(p) ? r.mesh.first() : r.mesh.last();
          if (first.record.junction() == null && r.record.junction() == null)
            RoadTransitions.requireCompatible(endpointSection(first,p), RoadEndpointSections.orient(endpointSection(r,p),origin.left().dot(sample.left())<0));
          if (Math.abs(origin.center().y()-sample.center().y()) > 1e-6)
            throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,"接点高程不一致：两侧实际路面 Y=%.3f / %.3f（差 %.3f 格），请检查接头高程",origin.center().y(),sample.center().y(),Math.abs(origin.center().y()-sample.center().y())));
          if (origin.center().sub(sample.center()).horizontalLength() > 1e-6)
            throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,"接点横向断面未对齐（并非高程不同）：两侧中心偏移 %.3f 格；合并后的实际断面需要续接匹配",origin.center().sub(sample.center()).horizontalLength()));
          if (Math.abs(origin.halfWidth() - sample.halfWidth()) > 1e-6)
            throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,"接缝过渡宽度不一致：%s，两侧 %.3f / %.3f 格",p.toShortString(),origin.halfWidth()*2,sample.halfWidth()*2));
          if (Math.abs(dot) < .99999999
              || (!ramp && dot > 0)
              || Math.abs(d.y() - sign * reference.y()) > 1e-5)
            throw new IllegalArgumentException("接缝方向或坡度不连续，请切回智能模式或调整端点");
          if (dot < 0) opposite = true;
        }
        if (links.size() == 3 && !opposite)
          throw new IllegalArgumentException("Y 字节点需要一条主路和两条支路，不能三条都朝同侧");
      }
  }

  private static V away(RoadIndex.Built b, BlockPos p) {
    boolean first = b.record.a().equals(p);
    var sample = first ? b.mesh.first() : b.mesh.last();
    V forward = sample.left().left().mul(-1);
    double grade = first ? b.record.start().grade() : b.record.end().grade();
    return new V(forward.x(), grade, forward.z()).mul(first ? 1 : -1);
  }

  /** Validates every affected cell before writing any block or index entry. */
  private void replaceBatch(
      ServerLevel level, ServerPlayer player, List<RoadIndex.Built> built, Set<UUID> removed) {
    replaceBatch(level, player, built, removed, false, Set.of(), List.of());
  }

  /** Rebuild both sides in the same transaction; no stale taper after an edit or deletion. */
  private void normalizeTransitions(
      List<RoadIndex.Built> built, Set<UUID> removed, ServerPlayer player, boolean deleting) {
    Map<UUID, RoadIndex.Built> all = new LinkedHashMap<>(index.roads);
    Set<BlockPos> affected = new HashSet<>();
    for (UUID id : removed) {
      var old = all.remove(id);
      if (old != null) {
        affected.add(old.record.a());
        affected.add(old.record.b());
      }
    }
    for (var r : built) {
      all.put(r.record.id(), r);
      affected.add(r.record.a());
      affected.add(r.record.b());
    }
    Map<BlockPos, List<RoadIndex.Built>> joints = new HashMap<>();
    for (var r : all.values()) {
      if (r.record.settings().style().ramp() || r.mesh.closed()) continue;
      for (BlockPos pos : List.of(r.record.a(), r.record.b()))
        if(transitionEndpoint(r,pos))joints.computeIfAbsent(pos, p -> new ArrayList<>()).add(r);
    }
    for (var r : all.values()) {
      var record = r.record;
      if (record.fragment()!=null||record.assembly() != null
          || record.settings().style().ramp()
          || r.mesh.closed()
          || !RoadProfile.modern(record.settings().style())
          || !affected.contains(record.a()) && !affected.contains(record.b())) continue;
      var a = affected.contains(record.a()) && transitionEndpoint(r,record.a()) ? jointSection(r, record.a(), joints.getOrDefault(record.a(), List.of()),deleting) : record.settings().options().ends().start();
      var b = affected.contains(record.b()) && transitionEndpoint(r,record.b()) ? jointSection(r, record.b(), joints.getOrDefault(record.b(), List.of()),deleting) : record.settings().options().ends().end();
      Settings s = RoadTransitions.ends(record.settings(), a, b);
      if (s.equals(record.settings())) continue;
      if (player != null) requireOwner(player, record.owner());
      // User-created mainlines are resampled from their saved curve; assembly alignments stay
      // fixed.
      var next = new RoadIndex.Built(record.settings(s));
      int position = -1;
      for (int i = 0; i < built.size(); i++)
        if (built.get(i).record.id().equals(record.id())) position = i;
      if (position >= 0) built.set(position, next);
      else built.add(next);
      if (index.roads.containsKey(record.id())) removed.add(record.id());
    }
  }

  private static RoadTransitions.Section jointSection(
      RoadIndex.Built road, BlockPos pos, List<RoadIndex.Built> links, boolean deleting) {
    boolean first=road.record.a().equals(pos);
    if(RoadEndpointSections.changed(road.mesh,first))return first?road.record.settings().options().ends().start():road.record.settings().options().ends().end();
    var fixed=links.stream().filter(r->!r.record.id().equals(road.record.id())&&(r.record.fragment()!=null||r.record.assembly()!=null||RoadEndpointSections.changed(r.mesh,r.record.a().equals(pos)))).findFirst();
    if(fixed.isPresent()){
      Settings target=RoadEndpointSections.orient(endpointSection(fixed.get(),pos),(fixed.get().record.a().equals(pos)?fixed.get().mesh.first():fixed.get().mesh.last()).left().dot((first?road.mesh.first():road.mesh.last()).left())<0);if(deleting&&!RoadTransitions.compatible(road.record.settings(),target))return null;RoadTransitions.requireCompatible(road.record.settings(),target);
      return RoadTransitions.Section.of(target);
    }
    // All incident roads choose in one stable physical frame, then reflect the
    // complete result back. Local forward-count tie breaks alone are not enough
    // when the neighboring road was drawn in the opposite direction.
    var reference=links.stream().min(java.util.Comparator.comparing(r->r.record.id())).orElse(road);
    V referenceLeft=(reference.record.a().equals(pos)?reference.mesh.first():reference.mesh.last()).left();
    boolean reverse=(first?road.mesh.first():road.mesh.last()).left().dot(referenceLeft)<0;
    Settings authored=RoadEndpointSections.orient(authoredSection(road.record.settings()),reverse);
    RoadTransitions.Section section = null;
    for (RoadIndex.Built other : links) {
      if (other.record.id().equals(road.record.id())) continue;
      if(deleting&&!RoadTransitions.compatible(road.record.settings(),other.record.settings()))continue;
      var next = RoadTransitions.common(authored, RoadEndpointSections.orient(authoredSection(other.record.settings()),(other.record.a().equals(pos)?other.mesh.first():other.mesh.last()).left().dot(referenceLeft)<0));
      section =
          section == null
              ? next
              : RoadTransitions.common(section.settings(false), next.settings(false));
    }
    return section==null?null:RoadTransitions.Section.of(RoadEndpointSections.orient(section.settings(road.record.settings().options().leftTraffic()),reverse));
  }

  void replaceAssembly(
      ServerLevel level,
      ServerPlayer player,
      List<RoadIndex.Built> built,
      Set<UUID> removed,
      Set<BlockPos> selectedNodes) {
    replaceAssembly(level, player, built, removed, selectedNodes, List.of());
  }

  void replaceCurveControl(ServerLevel level,ServerPlayer player,RoadRecord before,RoadRecord next,UUID deleted){
    replaceBatch(level,player,List.of(new RoadIndex.Built(next)),Set.of(before.id()),true,Set.of(before.a(),before.b()),List.of(),RoadLimits.MAX_EDIT_CELLS,deleted==null?Set.of():Set.of(deleted));
  }
  public record NodeMove(BlockPos from, BlockPos to, Node target, CompoundTag snapshot) {
    static NodeMove retire(BlockPos from){return new NodeMove(from,null,null,null);}
  }

  void replaceAssembly(
      ServerLevel level,
      ServerPlayer player,
      List<RoadIndex.Built> built,
      Set<UUID> removed,
      Set<BlockPos> selectedNodes,
      List<NodeMove> moves) {
    replaceBatch(level, player, built, removed, true, selectedNodes, moves);
  }

  /** A logical street edit includes unchanged neighbours in its draft. Keep the
   * player's actual targets separate from that complete, atomic replacement list.
   * Other assembly callers retain the strict all-inputs-direct default below. */
  void replaceAssembly(ServerLevel level,ServerPlayer player,List<RoadIndex.Built> built,
      Set<UUID> removed,Set<BlockPos> selectedNodes,List<NodeMove> moves,Set<UUID> directlyEditedRoads){
    replaceBatch(level,player,built,removed,true,selectedNodes,moves,RoadLimits.MAX_EDIT_CELLS,Set.of(),null,directlyEditedRoads);
  }

  /** A complete five/six-way layout may exceed a normal road edit; all checks remain atomic. */
  void replaceAssembly(ServerLevel level,ServerPlayer player,List<RoadIndex.Built> built,
      Set<UUID> removed,Set<BlockPos> selectedNodes,List<NodeMove> moves,int limit){
    if(limit<RoadLimits.MAX_EDIT_CELLS||limit>RoadLimits.MAX_MULTI_INTERCHANGE_EDIT_CELLS)throw new IllegalArgumentException("修改范围上限无效");
    replaceBatch(level,player,built,removed,true,selectedNodes,moves,limit);
  }
  /** One-time detection against every saved corridor, including a ramp's own
   * lower taper. EXTRA approaches and generated ramps do not carry additions. */
  private void queueLegacySupportRepairs(){
    for(var corridor:index.roads.values()){
      var ids=new HashSet<UUID>();for(long chunk:corridor.chunks)ids.addAll(index.inChunk(chunk));
      for(UUID id:ids){var old=index.roads.get(id);if(old==null||supportRepair104.contains(id)||!RoadIndex.overlapXZ(old.mesh,corridor.mesh,8))continue;
        for(var part:old.record.structures())if(part.pier()&&part.material()==RoadStructures.Material.CONCRETE&&part.height()>.8&&RoadClearance.structureInvades(part,corridor.mesh,4.25)){supportRepair104.add(id);break;}
      }
    }
  }
  /** Lane topology may widen a host far beyond the connector's own bounds. */
  private void enlistCycleContinuations(List<RoadIndex.Built> changed,Set<UUID> removed){
    var records=new LinkedHashMap<UUID,RoadRecord>();
    for(var r:index.roads.values())if(!removed.contains(r.record.id()))records.put(r.record.id(),r.record);
    for(var r:changed)records.put(r.record.id(),r.record);
    var selected=new HashSet<UUID>();changed.forEach(r->selected.add(r.record.id()));
    var visited=new HashSet<UUID>();
    for(var r:List.copyOf(changed))if(RoadCycleChain.eligible(r.record)&&visited.add(r.record.id())){
      var chain=RoadCycleChain.of(records,r.record.id());
      for(var leg:chain.legs()){
        visited.add(leg.road().id());
        if(selected.add(leg.road().id())){changed.add(new RoadIndex.Built(leg.road().structures(List.of())));removed.add(leg.road().id());}
      }
    }
  }
  private void enlistSupportDependencies(List<RoadIndex.Built> changed,Set<UUID> removed){
    var corridors=List.copyOf(changed);var selected=new HashSet<UUID>();changed.forEach(r->selected.add(r.record.id()));
    var groups=new HashSet<UUID>();
    for(var old:index.roads.values())if(!removed.contains(old.record.id())&&!selected.contains(old.record.id())){
      boolean conflict=false;
      for(var next:corridors){
        var previous=index.roads.get(next.record.id());
        if(previous!=null&&previous.mesh.equals(next.mesh))continue;
        if(!RoadIndex.overlapXZ(old.mesh,next.mesh,8))continue;
        for(var part:old.record.structures())if(part.pier()&&RoadClearance.structureInvades(part,next.mesh,4.25)
            &&(previous==null||!RoadClearance.structureInvades(part,previous.mesh,4.25))){conflict=true;break;}
        if(conflict)break;
      }
      if(conflict){changed.add(old.structures(List.of()));removed.add(old.record.id());selected.add(old.record.id());if(old.record.junction()!=null)groups.add(old.record.assembly());}
    }
    for(var old:index.roads.values())if(old.record.junction()!=null&&groups.contains(old.record.assembly())&&!removed.contains(old.record.id())&&!selected.contains(old.record.id())){changed.add(old.structures(List.of()));removed.add(old.record.id());}
  }

  private void replaceBatch(ServerLevel level,ServerPlayer player,List<RoadIndex.Built> built,
      Set<UUID> removed,boolean assembly,Set<BlockPos> selectedNodes,List<NodeMove> moves){
    replaceBatch(level,player,built,removed,assembly,selectedNodes,moves,RoadLimits.MAX_EDIT_CELLS);
  }
  private void replaceBatch(
      ServerLevel level,
      ServerPlayer player,
      List<RoadIndex.Built> built,
      Set<UUID> removed,
      boolean assembly,
      Set<BlockPos> selectedNodes,
      List<NodeMove> moves,int editCellLimit) {
    replaceBatch(level,player,built,removed,assembly,selectedNodes,moves,editCellLimit,Set.of());
  }
  private void replaceBatch(ServerLevel level,ServerPlayer player,List<RoadIndex.Built> built,Set<UUID> removed,boolean assembly,Set<BlockPos> selectedNodes,List<NodeMove> moves,int editCellLimit,Set<UUID> deletedPoints) {
    replaceBatch(level,player,built,removed,assembly,selectedNodes,moves,editCellLimit,deletedPoints,null);
  }
  List<RoadIndex.Built> previewAssembly(ServerLevel level,ServerPlayer player,List<RoadIndex.Built> built,
      Set<UUID> removed,Set<BlockPos> selectedNodes,List<NodeMove> moves){
    return prepareAssembly(level,player,built,removed,selectedNodes,moves,null);
  }
  private record PreparedEdit(List<RoadIndex.Built> built,Set<UUID> removed,boolean deleting,
      Set<Long> touched,Set<Long> moveSources,Set<Long> moveTargets,Map<Long,BlockState> sidewalks,Set<Long> aprons,
      Map<Long,List<RoadIndex.Built>> body,Set<Long> air,Set<Long> dry,
      Map<Long,List<RoadIndex.Built>> finalByChunk,Set<BlockPos> endpoints,
      List<RoadIndex.Built> finalRoads,List<NodeMove> moves,int editCellLimit,Set<Long> chunks){}
  List<RoadRecord> splitJunctionStructures(ServerLevel level,List<RoadRecord> roads,UUID replaced){
    var all=new ArrayList<RoadIndex.Built>();for(var r:index.roads.values())if(!r.record.id().equals(replaced)&&roads.stream().noneMatch(n->n.id().equals(r.record.id())))all.add(r);
    var built=roads.stream().map(RoadIndex.Built::new).toList();all.addAll(built);var out=new ArrayList<RoadRecord>();var cache=new RoadTerrain.Cache(terrainFill,terrainOriginal);var lookup=new RoadPlanningIndex(all);
    try(var chunks=RoadWorkChunks.open(level)){chunks.roads(built);for(var r:built)out.add(r.record.junction()==null?r.record:StructurePlanner.plan(level,r,all,terrainFill,terrainOriginal,cache,lookup));}return List.copyOf(out);
  }
  record SplitPrepared(PreparedEdit edit,long revision,Map<UUID,RoadRecord> dependencies){int cells(){return edit.touched().size();}}
  private boolean exactSplit;
  static java.util.function.Consumer<String> splitFault=stage->{};
  static java.util.function.Consumer<String> editFault=stage->{};
  SplitPrepared prepareSplit(ServerLevel level,ServerPlayer player,List<RoadIndex.Built> built,Set<UUID> removed,NodeMove node,boolean junction){
    try(var rasters=RoadEditRasters.open();var chunks=RoadWorkChunks.open(level)){
      var old=new ArrayList<RoadIndex.Built>();for(UUID id:removed){var r=index.roads.get(id);if(r!=null)old.add(r);}
      var finalRoads=new ArrayList<RoadIndex.Built>();for(var r:index.roads.values())if(!removed.contains(r.record.id()))finalRoads.add(r);finalRoads.addAll(built);
      chunks.roads(built);chunks.roads(old);
      var touched=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
      for(var r:old){touched.addAll(r.cells.keySet());touched.addAll(r.clearanceCells);}
      for(var r:built){touched.addAll(r.cells.keySet());touched.addAll(r.clearanceCells);}
      touched.add(node.to().asLong());
      Map<Long,BlockState> sidewalks=new HashMap<>(sidewalkPlaced);Set<Long> aprons=new HashSet<>(apronPlaced);
      if(junction){var terrainRoads=new ArrayList<>(old);terrainRoads.addAll(built);sidewalks=SmartSidewalks.plan(finalRoads,level,terrainFill,sidewalkPlaced,apronPlaced,built,terrainRoads);aprons=SmartRoadAprons.plan(sidewalks,finalRoads,level,terrainOriginal,sidewalkPlaced,apronPlaced,terrainRoads);
        for(var e:sidewalkPlaced.entrySet())if(!e.getValue().equals(sidewalks.get(e.getKey())))touched.add(e.getKey());for(var e:sidewalks.entrySet())if(!e.getValue().equals(sidewalkPlaced.get(e.getKey())))touched.add(e.getKey());
      }
      if(touched.size()>RoadLimits.MAX_EDIT_CELLS)throw new IllegalArgumentException("一次分割涉及的世界范围过大");
      Map<Long,List<RoadIndex.Built>> body=new HashMap<>(),finalByChunk=new HashMap<>();Set<Long> air=new HashSet<>(),dry=new HashSet<>(),touchedChunks=new HashSet<>();
      Map<Long,List<BlockPos>> touchedByChunk=new HashMap<>();for(long key:touched){var pos=BlockPos.of(key);long chunk=new net.minecraft.world.level.ChunkPos(pos).toLong();touchedChunks.add(chunk);touchedByChunk.computeIfAbsent(chunk,k->new ArrayList<>()).add(pos);}chunks.load(touchedChunks);
      for(var r:finalRoads)for(long chunk:r.chunks)if(touchedChunks.contains(chunk)){finalByChunk.computeIfAbsent(chunk,k->new ArrayList<>()).add(r);for(var pos:touchedByChunk.get(chunk)){long key=pos.asLong();if(!r.boxes(pos).isEmpty())body.computeIfAbsent(key,k->new ArrayList<>()).add(r);if(r.clearanceAt(pos)){air.add(key);if(r.tunnelAt(pos))dry.add(key);}}}
      Set<BlockPos> endpoints=new HashSet<>();for(var r:built){endpoints.add(r.record.a());endpoints.add(r.record.b());}endpoints.add(node.to());
      // A saved connector changes only its source point's owning slice. Its
      // still-linked target/parent markers remain the same legitimate contacts
      // that its original build checked; unrelated endpoints stay protected.
      var finalRecords=new LinkedHashMap<UUID,RoadRecord>();for(var r:finalRoads)finalRecords.put(r.record.id(),r.record);
      for(var r:built)if(LaneTopology.metadata(r.record).link()!=null)
        endpoints.addAll(LaneRamps.contactEndpoints(finalRecords,r.record));
      var edit=new PreparedEdit(List.copyOf(built),Set.copyOf(removed),false,touched,Set.of(),Set.of(node.to().asLong()),sidewalks,aprons,body,air,dry,finalByChunk,endpoints,List.copyOf(finalRoads),List.of(node),RoadLimits.MAX_EDIT_CELLS,Set.copyOf(touchedChunks));
      var dependencies=new LinkedHashMap<UUID,RoadRecord>();for(UUID id:removed){var previous=index.roads.get(id);if(previous!=null)dependencies.put(id,previous.record);}
      validateAndCommit(level,player,edit,true);return new SplitPrepared(edit,index.revision(),Map.copyOf(dependencies));
    }
  }
  /** Full rollback includes overwritten marker entities and every ownership ledger. */
  void commitSplit(ServerLevel level,ServerPlayer player,SplitPrepared prepared,Runnable metadata){
    if(prepared.revision()!=index.revision())throw new IllegalArgumentException("道路已被修改，请重新选择");
    for(var entry:prepared.dependencies().entrySet()){var live=index.roads.get(entry.getKey());if(live==null||live.record!=entry.getValue())throw new IllegalArgumentException("相关道路元数据已改变，请重新预览");}
    var edit=prepared.edit();try(var rasters=RoadEditRasters.open();var chunks=RoadWorkChunks.open(level)){chunks.load(edit.chunks());validateAndCommit(level,player,edit,true);
      var snapshot=new EditSnapshot(level,edit,true);var callback=index.changed;index.changed=id->{};exactSplit=true;
      RoadNetwork.PreparedBatch packet;
      try{validateAndCommit(level,player,edit,false);splitFault.accept("world");metadata.run();splitFault.accept("metadata");packet=RoadNetwork.prepareBatch(level,edit.built().stream().map(r->r.record).toList(),edit.removed(),edit.moves().get(0).to());setDirty();}
      catch(RuntimeException failure){
        snapshot.restore(level,failure);throw failure;
      }finally{index.changed=callback;exactSplit=false;}
      for(var r:edit.built())smartSigns.changed(r.record.id());
      RoadNetwork.broadcastBatch(level,edit.built().stream().map(r->r.record).toList(),edit.removed(),packet);
    }
  }
  /** One rollback implementation for ordinary edits, cached previews, gantries and splits. */
  private final class EditSnapshot {
    final Map<UUID,RoadIndex.Built> roadsBefore=new LinkedHashMap<>(index.roads);
    final Map<UUID,RailbedStore.Entry> railbedsBefore=new LinkedHashMap<>(railbeds.entries);
    final Map<UUID,RoadRecord> streetsBefore;
    final Map<UUID,CompoundTag> junctionsBefore,interchangesBefore;
    final SmartSignRoads.Snapshot signsBefore;final boolean dirtyBefore=isDirty(),fullMetadata;
    final Map<Long,BlockState> world=new HashMap<>(),original=new HashMap<>(),fill=new HashMap<>();
    // A commit publishes fresh paving maps only after all world writes succeed.
    // Retaining the previous generation is sufficient for rollback; it is never
    // mutated by this transaction and needs no city-wide preparation copy.
    final Long2ObjectOpenHashMap<BlockState> walks=sidewalkPlaced;
    final Map<Long,CompoundTag> entities=new HashMap<>();final Set<Long> aprons=apronPlaced;
    EditSnapshot(ServerLevel level,PreparedEdit edit,boolean fullMetadata){
      this.fullMetadata=fullMetadata;
      streetsBefore=new LinkedHashMap<>();
      if(fullMetadata)streetsBefore.putAll(streets);
      else for(var road:edit.built()){var saved=streets.get(road.record.id());if(saved!=null)streetsBefore.put(saved.id(),saved);}
      junctionsBefore=fullMetadata?copyTags(junctions):copyTags(junctions,junctionGroups(edit));
      // Only the split metadata callback writes interchange/sign authority while
      // index notifications are suppressed. Ordinary commits update existing
      // logical roads and the junction groups selected above, then publish later.
      interchangesBefore=fullMetadata?copyTags(interchanges):Map.of();
      signsBefore=fullMetadata?smartSigns.snapshot():null;
      int captured=0;for(long key:edit.touched()){if((captured++&1023)==0)com.sora.splineroads.core.RoadPlanningBudget.check();var pos=BlockPos.of(key);world.put(key,level.getBlockState(pos));var be=level.getBlockEntity(pos);if(be!=null)entities.put(key,be.saveWithFullMetadata());if(terrainOriginal.containsKey(key))original.put(key,terrainOriginal.get(key));if(terrainFill.containsKey(key))fill.put(key,terrainFill.get(key));}
      com.sora.splineroads.core.RoadPlanningBudget.check();
    }
    void restore(ServerLevel level,RuntimeException failure){
      for(UUID id:new ArrayList<>(index.roads.keySet()))if(index.roads.get(id)!=roadsBefore.get(id))index.remove(id);
      for(var road:roadsBefore.values())if(index.roads.get(road.record.id())!=road)index.put(road);
      index.restoreOrder(roadsBefore);
      railbeds.entries.clear();railbeds.entries.putAll(railbedsBefore);
      if(fullMetadata){streets.clear();junctions.clear();interchanges.clear();interchanges.putAll(interchangesBefore);}
      streets.putAll(streetsBefore);junctions.putAll(junctionsBefore);
      for(long key:world.keySet()){terrainOriginal.remove(key);terrainFill.remove(key);}terrainOriginal.putAll(original);terrainFill.putAll(fill);sidewalkPlaced=walks;apronPlaced=aprons;
      restoreBlocks(level,world,entities,failure::addSuppressed);
      if(signsBefore!=null)smartSigns.restore(signsBefore);setDirty(dirtyBefore);
      if(failure.getSuppressed().length!=0)System.getLogger("SplineRoads/transaction").log(System.Logger.Level.ERROR,"An external world hook prevented complete rollback",failure);
    }
  }
  private static void checkedSetBlock(ServerLevel level,BlockPos pos,BlockState state,int flags){
    // setBlock(false) is normal when the desired state already exists.
    level.setBlock(pos,state,flags);requireBlockState(level,pos,state);
  }
  private static void requireBlockState(ServerLevel level,BlockPos pos,BlockState state){
    var actual=level.getBlockState(pos);
    if(!actual.equals(state))throw new IllegalStateException("世界拒绝写入道路方块："+pos.toShortString()+" target="+state+" actual="+actual);
  }
  /** Original states may depend on each other (e.g. the two halves of seagrass).
   * Restore every state and entity before replaying the normal shape notifications.
   * Commit failures throw; rollback collects failures and keeps restoring other cells. */
  private static void restoreBlocks(ServerLevel level,Map<Long,BlockState> states,
      Map<Long,CompoundTag> entities,java.util.function.Consumer<RuntimeException> failed){
    restoreBlocks(level,states,entities,failed,false);
  }
  private static void restoreBlocks(ServerLevel level,Map<Long,BlockState> states,
      Map<Long,CompoundTag> entities,java.util.function.Consumer<RuntimeException> failed,boolean terrainRestore){
    var changed=new LinkedHashMap<Long,BlockState>();
    for(var entry:states.entrySet())try{
      var pos=BlockPos.of(entry.getKey());var previous=level.getBlockState(pos);
      checkedSetBlock(level,pos,entry.getValue(),net.minecraft.world.level.block.Block.UPDATE_CLIENTS|net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
      if(!previous.equals(entry.getValue()))changed.put(entry.getKey(),previous);
    }catch(RuntimeException failure){failed.accept(failure);}
    // A forward edit can remove the soil beneath an archived plant. Restore all
    // solid ground first, then retire only plants that cannot survive that new
    // terrain. Rollback remains exact and never takes this path.
    if(terrainRestore)for(int pass=0;pass<2;pass++)for(var entry:states.entrySet()){
      var pos=BlockPos.of(entry.getKey());var state=entry.getValue();
      if(state.getBlock() instanceof net.minecraft.world.level.block.BushBlock&&!state.canSurvive(level,pos)){
        var empty=state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState();
        entry.setValue(empty);checkedSetBlock(level,pos,empty,net.minecraft.world.level.block.Block.UPDATE_CLIENTS|net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
      }
    }
    for(var entry:entities.entrySet())try{
      var pos=BlockPos.of(entry.getKey());var state=states.get(entry.getKey());requireBlockState(level,pos,state);
      // load() need not clear absent optional fields (e.g. NodeEntity.Owner or
      // ForgeData). A fresh instance restores absence as well as stored values.
      var be=net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos,state,entry.getValue().copy());
      if(be==null||!state.hasBlockEntity()||!be.getType().isValid(state))throw new IllegalStateException("无法恢复端点/填充实体："+pos.toShortString());
      level.removeBlockEntity(pos);level.setBlockEntity(be);
      if(level.getBlockEntity(pos)!=be||be.isRemoved()||!be.getBlockPos().equals(pos)||!be.getBlockState().equals(state))throw new IllegalStateException("无法挂接恢复的端点/填充实体："+pos.toShortString());
      be.setChanged();level.sendBlockUpdated(pos,state,state,3);
    }catch(RuntimeException failure){failed.accept(failure);}
    for(var entry:changed.entrySet())try{
      var pos=BlockPos.of(entry.getKey());var state=states.get(entry.getKey());requireBlockState(level,pos,state);
      // Same flags/depth as Level's shape updates for a normal UPDATE_CLIENTS write.
      entry.getValue().updateIndirectNeighbourShapes(level,pos,2,511);
      state.updateNeighbourShapes(level,pos,2,511);
      state.updateIndirectNeighbourShapes(level,pos,2,511);
    }catch(RuntimeException failure){failed.accept(failure);}
    for(var entry:states.entrySet())try{
      var pos=BlockPos.of(entry.getKey());var state=entry.getValue();requireBlockState(level,pos,state);
      // Updating neighbours need not revisit the newly restored cell itself.
      // In particular, an upper-only plant must not be accepted as a stable restore.
      var stable=net.minecraft.world.level.block.Block.updateFromNeighbourShapes(state,level,pos);
      if(!stable.equals(state))throw new IllegalStateException("世界拒绝写入道路方块："+pos.toShortString()+" target="+state+" supported="+stable);
      var entity=level.getBlockEntity(pos);var tag=entities.get(entry.getKey());
      if(tag!=null&&(entity==null||entity.isRemoved()||!entity.getBlockPos().equals(pos)||!entity.getBlockState().equals(state)
          ||!entity.getType().isValid(state)||!entity.saveWithFullMetadata().equals(tag)))throw new IllegalStateException("恢复后端点/填充实体不一致："+pos.toShortString());
      if(!state.hasBlockEntity()&&entity!=null)throw new IllegalStateException("恢复后残留方块实体："+pos.toShortString());
    }
    catch(RuntimeException failure){failed.accept(failure);}
  }
  private static Map<UUID,CompoundTag> copyTags(Map<UUID,CompoundTag> source){var out=new LinkedHashMap<UUID,CompoundTag>();source.forEach((key,value)->out.put(key,value.copy()));return out;}
  private static Map<UUID,CompoundTag> copyTags(Map<UUID,CompoundTag> source,Set<UUID> ids){var out=new LinkedHashMap<UUID,CompoundTag>();for(UUID id:ids){var value=source.get(id);if(value!=null)out.put(id,value.copy());}return out;}
  private Set<UUID> junctionGroups(PreparedEdit edit){
    var groups=new HashSet<UUID>();
    for(var road:edit.built())junctionGroups(groups,road.record);
    for(UUID id:edit.removed()){var previous=index.roads.get(id);if(previous!=null)junctionGroups(groups,previous.record);}
    return groups;
  }
  private static void junctionGroups(Set<UUID> groups,RoadRecord record){
    if(record.junction()!=null&&record.assembly()!=null)groups.add(record.assembly());
    var link=LaneTopology.metadata(record).link();if(link!=null&&link.to().junction()!=null)groups.add(link.to().junction());
  }
  private record PreparedEntry(PreparedEdit edit,RoadPlanInputs inputs,UUID owner,long revision,long created){}
  private final LinkedHashMap<UUID,PreparedEntry> preparedEdits=new LinkedHashMap<>();
  private static final Set<RoadData> PREVIEW_DATA=Collections.newSetFromMap(new WeakHashMap<>());
  public static void expirePreviews(){long now=System.nanoTime();RoadSplitter.expire(now);for(var data:new ArrayList<>(PREVIEW_DATA))data.prunePreviews(now);}
  public static void forgetPreviews(UUID owner){for(var data:new ArrayList<>(PREVIEW_DATA))data.discardPreviews(owner);}
  /** Discard only this tool's prepared token; a later editor's preview remains valid. */
  public static void forgetPreview(UUID owner,UUID token){
    if(owner==null||token==null)return;
    for(var data:new ArrayList<>(PREVIEW_DATA)){
      var entry=data.preparedEdits.get(token);
      if(entry!=null&&owner.equals(entry.owner()))data.preparedEdits.remove(token);
      if(data.preparedEdits.isEmpty())PREVIEW_DATA.remove(data);
    }
  }
  public static void clearPreviews(){for(var data:new ArrayList<>(PREVIEW_DATA))data.preparedEdits.clear();PREVIEW_DATA.clear();}
  void prunePreviews(long now){preparedEdits.entrySet().removeIf(e->now-e.getValue().created()>600_000_000_000L||e.getValue().revision()!=index.contentRevision());if(preparedEdits.isEmpty())PREVIEW_DATA.remove(this);}
  public void discardPreviews(UUID owner){preparedEdits.entrySet().removeIf(e->Objects.equals(e.getValue().owner(),owner));if(preparedEdits.isEmpty())PREVIEW_DATA.remove(this);}
  int preparedPreviewCount(){return preparedEdits.size();}

  List<RoadIndex.Built> prepareAssembly(ServerLevel level,ServerPlayer player,List<RoadIndex.Built> built,
      Set<UUID> removed,Set<BlockPos> selectedNodes,List<NodeMove> moves,UUID token){
    return prepareAssembly(level,player,built,removed,selectedNodes,moves,token,Set.of(),true);
  }
  List<RoadIndex.Built> prepareAssembly(ServerLevel level,ServerPlayer player,List<RoadIndex.Built> built,
      Set<UUID> removed,Set<BlockPos> selectedNodes,List<NodeMove> moves,UUID token,Set<UUID> directlyEditedRoads,boolean validatePreview){
    var result=new ArrayList<PreparedEdit>();
    try(var inputs=RoadPlanInputs.open(level,token!=null)){
      replaceBatch(level,player,built,removed,true,selectedNodes,moves,RoadLimits.MAX_EDIT_CELLS,Set.of(),result,directlyEditedRoads,validatePreview);
      var edit=result.get(0);
      if(token!=null){
        // Replace the owner's prior preview only after every validation passes.
        // The in-progress plan and the retained cache each keep their own limits.
        UUID owner=player==null?null:player.getUUID();
        preparedEdits.entrySet().removeIf(e->Objects.equals(e.getValue().owner(),owner)||expired(e.getValue()));
        preparedEdits.put(token,new PreparedEntry(edit,inputs,owner,index.contentRevision(),System.nanoTime()));PREVIEW_DATA.add(this);
        // Retain at most two previews, with one ordinary transaction's total cell budget.
        while(preparedEdits.size()>1&&(preparedEdits.size()>2||preparedEdits.values().stream().mapToLong(e->e.edit().touched().size()).sum()>RoadLimits.MAX_EDIT_CELLS||preparedEdits.values().stream().mapToLong(e->e.inputs().retainedStates()).sum()>4_000_000))
          preparedEdits.remove(preparedEdits.keySet().iterator().next());
      }
      return List.copyOf(edit.built());
    }
  }
  private static boolean expired(PreparedEntry e){return System.nanoTime()-e.created()>600_000_000_000L;}
  /** Publish immutable fixtures and authored gantries for the entire batch before
   * any road searches for automatic piers. A UUID order must not decide whether
   * a harmless rail edit can rebuild an already configured gantry. */
  private void reserveBatchFixtures(ServerLevel level,List<RoadIndex.Built> built,
      List<RoadIndex.Built> planning,Map<Long,BlockState> retained,Map<Long,BlockState> originals,
      Map<BlockPos,List<net.minecraft.world.phys.AABB>> terrainCache,RoadPlanningIndex lookup,Set<UUID> explicit,Set<UUID> removed){
    // A station with physical members or user-authored signs is mandatory even if its
    // original support began as an automatic placement before a road split.
    for(int i=0;i<built.size();i++){
      var old=built.get(i);var infra=old.record.settings().options().infrastructure();
      var edits=infra.gantryEdits().stream().map(e->e.automatic()&&authoredGantry(old.record.id(),e.slot())
          ?e.parameters(e.offset(),e.clearance(),e.kind(),e.reverse()):e).toList();
      if(!edits.equals(infra.gantryEdits())){
        var next=new RoadIndex.Built(old.record.settings(old.record.settings().options(old.record.settings().options().infrastructure(infra.fixedStations(edits)))));
        built.set(i,next);int slot=planning.indexOf(old);planning.set(slot,next);lookup.replace(slot,next);
      }
    }
    lookup.editHistory(built,index.roads,explicit);
    lookup.optionalAutomaticAnchors(optionalAutomaticAnchors(built));
    // A later topology pass must never see an earlier pass's provisional piers.
    for(int i=0;i<built.size();i++){
      var r=built.get(i);var next=r.structures(List.of());built.set(i,next);
      int slot=planning.indexOf(r);planning.set(slot,next);lookup.replace(slot,next);
    }
    // Publish every derived cycle excavation before planning any bridge footing.
    lookup.resetCyclePlans();
    for(int i=0;i<built.size();i++){
      var r=built.get(i);var next=StructurePlanner.prepareCrossings(level,r,retained,originals,terrainCache,lookup);
      if(next!=r){built.set(i,next);int slot=planning.indexOf(r);planning.set(slot,next);lookup.replace(slot,next);}
    }
    lookup.preservedGantries(RoadGantryRetention.capture(built,index.roads,explicit,removed));
    boolean authored=built.stream().anyMatch(r->r.record.junction()==null&&r.record.toll()==null&&r.mesh.settings().structure()!=Structure.TUNNEL
        &&!r.record.settings().options().infrastructure().gantryEdits().isEmpty());
    for(var stage:authored?List.of(StructurePlanner.Stage.FIXED,StructurePlanner.Stage.GANTRIES,StructurePlanner.Stage.PAVING):List.of(StructurePlanner.Stage.PAVING))
      for(int i=0;i<built.size();i++){
        var r=built.get(i);var next=r.planned(StructurePlanner.reserve(level,r,planning,retained,originals,terrainCache,lookup,stage));
        built.set(i,next);int slot=planning.indexOf(r);planning.set(slot,next);lookup.replace(slot,next);
      }
  }
  /** A split saves automatic stations in the edit list even when no frame was
   * generated. Preserve that absence only when the old world proves it, never
   * by inferring authorship from default-looking parameters alone. */
  private boolean authoredGantry(UUID id,int slot){
    var old=index.roads.get(id);
    if(old!=null&&RoadGantry.exists(old.mesh,slot)&&!RoadGantry.members(old.mesh,slot,old.record.structures()).isEmpty())return true;
    return smartSigns.road(id).stream().anyMatch(s->s.key().slot()==slot
            &&(s.mode()!=com.sora.splineroads.core.SmartSigns.Mode.NORMAL&&s.mode()!=com.sora.splineroads.core.SmartSigns.Mode.EXIT_AUTO
                ||!s.text().isEmpty()||!s.drafts().isEmpty()||!s.hiddenHeaders().isEmpty()||!s.hiddenAccessories().isEmpty()||!s.closedLanes().isEmpty()));
  }
  private Map<UUID,Set<Integer>> optionalAutomaticAnchors(List<RoadIndex.Built> candidates){
    var optional=new HashMap<UUID,Set<Integer>>();
    for(var next:candidates){
      var old=index.roads.get(next.record.id());if(old==null)continue;
      var before=old.mesh.settings().options().infrastructure();var after=next.mesh.settings().options().infrastructure();
      if(!after.fixedStations()||before.gantry()!=after.gantry()
          ||old.mesh.settings().structure()==Structure.TUNNEL||next.mesh.settings().structure()==Structure.TUNNEL)continue;
      for(var edit:after.gantryEdits()){
        com.sora.splineroads.core.RoadPlanningBudget.check();
        if(!edit.anchored()||edit.independent()||edit.kind()!=RoadInfrastructure.Gantry.AUTO
            ||edit.offset()!=0||edit.clearance()!=5.6||edit.reverse())continue;
        var prior=before.gantryEdits().stream().filter(e->e.slot()==edit.slot()).findFirst().orElse(null);
        if(prior!=null&&(!prior.anchored()||prior.independent()||prior.kind()!=RoadInfrastructure.Gantry.AUTO
            ||prior.offset()!=0||prior.clearance()!=5.6||prior.reverse()))continue;
        int slot=edit.slot();
        if(smartSigns.road(old.record.id()).stream().anyMatch(s->s.key().slot()==slot
                &&(s.mode()!=com.sora.splineroads.core.SmartSigns.Mode.NORMAL&&s.mode()!=com.sora.splineroads.core.SmartSigns.Mode.EXIT_AUTO
                    ||!s.text().isEmpty()||!s.drafts().isEmpty()||!s.hiddenHeaders().isEmpty()||!s.hiddenAccessories().isEmpty()||!s.closedLanes().isEmpty())))continue;
        try{
          if(!RoadGantry.exists(old.mesh,slot)||!RoadGantry.exists(next.mesh,slot)
              ||RoadGantry.station(old.mesh,slot).kind()==RoadInfrastructure.Gantry.OFF
              ||!RoadGantry.members(old.mesh,slot,old.record.structures()).isEmpty())continue;
          optional.computeIfAbsent(next.record.id(),id->new HashSet<>()).add(slot);
        }catch(IllegalArgumentException invalid){/* An invalid old station keeps the normal hard rejection; cancellation propagates. */}
      }
    }
    return optional;
  }
  boolean preparedValid(ServerLevel level,UUID token){
    var entry=preparedEdits.get(token);if(entry==null||expired(entry)||entry.revision()!=index.contentRevision())return false;
    try{entry.inputs().validate(level);return true;}catch(IllegalArgumentException changed){return false;}
  }
  /** Adopt old restoration receipts without moving or rebuilding any world block. */
  void adoptRailbeds(ServerLevel level){
    if(!railbeds.pending.isEmpty())throw new IllegalArgumentException("旧铁路事务正在恢复，请稍候");
    for(var entry:List.copyOf(railbeds.entries.values()))if(entry.road.legacyRailway()){
      var r=entry.road;var settings=r.settings().structure(entry.mode).options(r.settings().options().railbed(new com.sora.splineroads.core.RailbedProfile(entry.paths,entry.width,entry.options)).outerRail(RoadProfile.OuterRail.ON));
      var next=new RoadRecord(r.id(),r.owner(),r.a(),r.b(),r.start(),r.end(),settings,false,5,r.structures(),r.endCaps(),141,null,r.alignment(),r.furniturePhase());
      entry.original.forEach((key,state)->terrainOriginal.putIfAbsent(key,state));
      index.put(RoadIndex.Built.loading(next));railbeds.entries.put(r.id(),RailbedRoads.entry(next));setDirty();
    }
  }
  void buildPrepared(ServerLevel level,ServerPlayer player,UUID token){
    buildPrepared(level,player,token,null);
  }
  /** A local insertion publishes its road fragments and metadata as one transaction. */
  void buildPrepared(ServerLevel level,ServerPlayer player,UUID token,Runnable metadata){
    var entry=preparedEdits.get(token);
    if(entry==null||expired(entry)){preparedEdits.remove(token);throw new IllegalArgumentException("预览缓存已过期，请重新预览");}
    if(!Objects.equals(entry.owner(),player==null?null:player.getUUID()))throw new IllegalArgumentException("预览不属于当前玩家");
    if(entry.revision()!=index.contentRevision()){preparedEdits.remove(token);throw new IllegalArgumentException("道路已改变，请重新预览");}
    try(var rasters=RoadEditRasters.open();var budget=com.sora.splineroads.core.RoadPlanningBudget.cancellable("提交已验证预览");
        var timing=RoadTimings.start("preview_commit",index.roads.size(),entry.edit().built().size());
        var chunks=RoadWorkChunks.open(level)){
      chunks.load(entry.edit().chunks());entry.inputs().validate(level);
      timing.stage("cached_world_inputs");
      if(metadata==null)validateAndCommit(level,player,entry.edit(),false);
      else {
        var edit=entry.edit();var snapshot=new EditSnapshot(level,edit,true);var callback=index.changed;
        index.changed=id->{};exactSplit=true;RoadNetwork.PreparedBatch packet;
        try{validateAndCommit(level,player,edit,false);metadata.run();packet=RoadNetwork.prepareBatch(level,edit.built().stream().map(r->r.record).toList(),edit.removed(),edit.moves().isEmpty()?edit.built().get(0).record.a():edit.moves().get(0).to());setDirty();}
        catch(RuntimeException failure){snapshot.restore(level,failure);throw failure;}
        finally{index.changed=callback;exactSplit=false;}
        var ids=new LinkedHashSet<>(edit.removed());edit.built().forEach(r->ids.add(r.record.id()));ids.forEach(callback);
        for(var r:edit.built())smartSigns.changed(r.record.id());
        RoadNetwork.broadcastBatch(level,edit.built().stream().map(r->r.record).toList(),edit.removed(),packet);
      }
      timing.stage("validation_commit_sync");
    }finally{preparedEdits.remove(token);}
  }
  /** Resolve the pending topology's cap frame before deriving lane stations.
   * A new edit record starts without caps; deriving first would shift an
   * unchanged analytic road's lane taper by the half-metre start extension. */
  private List<RoadIndex.Built> capPlanning(List<RoadIndex.Built> built,Set<UUID> removed){
    built.removeIf(r->confirmedLaneDeletes.contains(r.record.id()));
    var planning=new ArrayList<RoadIndex.Built>();
    for(var old:index.roads.values())if(!removed.contains(old.record.id())
        &&!confirmedLaneDeletes.contains(old.record.id()))planning.add(old);
    planning.addAll(built);
    for(int i=0;i<built.size();i++){
      var old=built.get(i);int mask=0;
      if(!old.mesh.closed()&&!old.record.settings().style().ramp()&&LaneTopology.metadata(old.record).link()==null){
        for(boolean first:new boolean[]{true,false}){
          BlockPos pos=first?old.record.a():old.record.b();
          boolean shared=planning.stream().anyMatch(r->!r.record.id().equals(old.record.id())
              &&LaneTopology.metadata(r.record).link()==null&&(r.record.a().equals(pos)||r.record.b().equals(pos)));
          if(!shared)mask|=first?1:2;
        }
      }
      var next=old.record.endCaps()==mask&&old.record.buildVersion()==17?old:old.caps(mask);
      built.set(i,next);planning.set(planning.indexOf(old),next);
    }
    return planning;
  }
  private void replaceBatch(ServerLevel level,ServerPlayer player,List<RoadIndex.Built> built,Set<UUID> removed,boolean assembly,Set<BlockPos> selectedNodes,List<NodeMove> moves,int editCellLimit,Set<UUID> deletedPoints,List<PreparedEdit> previewResult) {
    replaceBatch(level,player,built,removed,assembly,selectedNodes,moves,editCellLimit,deletedPoints,previewResult,
        built.stream().map(r->r.record.id()).collect(java.util.stream.Collectors.toUnmodifiableSet()));
  }
  private void replaceBatch(ServerLevel level,ServerPlayer player,List<RoadIndex.Built> built,Set<UUID> removed,boolean assembly,Set<BlockPos> selectedNodes,List<NodeMove> moves,int editCellLimit,Set<UUID> deletedPoints,List<PreparedEdit> previewResult,Set<UUID> directlyEditedRoads) {
    replaceBatch(level,player,built,removed,assembly,selectedNodes,moves,editCellLimit,deletedPoints,previewResult,directlyEditedRoads,true);
  }
  private void replaceBatch(ServerLevel level,ServerPlayer player,List<RoadIndex.Built> built,Set<UUID> removed,boolean assembly,Set<BlockPos> selectedNodes,List<NodeMove> moves,int editCellLimit,Set<UUID> deletedPoints,List<PreparedEdit> previewResult,Set<UUID> directlyEditedRoads,boolean validatePreview) {
    if(built.stream().anyMatch(r->r.record.legacyRailway())||removed.stream().anyMatch(id->index.roads.containsKey(id)&&index.roads.get(id).record.legacyRailway()))throw new IllegalArgumentException("铁路路基请使用铁路路基连接器编辑或删除");
    try (var projections=RoadQueries.scope();var rasters=RoadEditRasters.open();var budget=com.sora.splineroads.core.RoadPlanningBudget.cancellable("道路事务预检查");
         var timing=RoadTimings.start("edit",index.roads.size(),built.size());
         var workChunks = RoadWorkChunks.open(level)) {
      boolean deleting=built.isEmpty();
      built = new ArrayList<>(built);
      var explicit=Set.copyOf(directlyEditedRoads);
      var inputIds=new HashSet<>(removed);built.forEach(r->inputIds.add(r.record.id()));
      if(!inputIds.containsAll(explicit))throw new IllegalArgumentException("本次道路事务缺少直接编辑目标");
      removed = new HashSet<>(removed);
      removed.addAll(confirmedLaneDeletes);
      built.removeIf(b->confirmedLaneDeletes.contains(b.record.id()));
      ElevatedAccessRoads.protect(this,built,removed);
      // Junction pads are filtered by transitionEndpoint; ordinary external
      // approaches still need their taper updated when an assembly is replaced.
      RailbedJoints.normalize(index,built,removed,player);
      normalizeTransitions(built, removed, player,deleting);
      timing.stage("normalize_transitions");com.sora.splineroads.core.RoadPlanningBudget.phase("normalize_transitions");
      List<RoadIndex.Built> requested = new ArrayList<>(built);
      // Prove an incidental endpoint-only rail change once, before scanning
      // dependencies. A long host must not propagate that edit along its length.
      var derived=new HashMap<UUID,List<com.sora.splineroads.core.RoadBoundsIndex.Bounds>>();
      for(var candidate:built)if(!explicit.contains(candidate.record.id())){
        var scope=RoadEditScope.endpointRails(index.roads.get(candidate.record.id()),candidate);
        if(scope!=null)derived.put(candidate.record.id(),scope);
      }
      if(!deleting)for(var old:index.roads.values())if(!removed.contains(old.record.id())&&LaneTopology.metadata(old.record).link()!=null)
        for(var proposed:built)if(!derived.containsKey(proposed.record.id())||RoadEditScope.affects(derived.get(proposed.record.id()),old))RoadInteractions.influences(proposed,old);
      for (UUID id : removed) if (index.roads.containsKey(id)) requested.add(index.roads.get(id));
      // Re-plan affected structures, including a saved ramp joined by a new
      // child mouth. influences keeps independent saved ramp furniture unchanged.
      // Deletion only releases recorded reservations/openings below. It must not
      // enlist a surviving broken connector merely because it shares endpoints.
      for (var old : index.roads.values())
        if (!removed.contains(old.record.id())
            && (!deleting || LaneTopology.metadata(old.record).link()==null)
            && requested.stream().anyMatch(r -> (!derived.containsKey(r.record.id())
                ||RoadEditScope.affects(derived.get(r.record.id()),old))&&RoadInteractions.influences(r, old))) {
          built.add(old.structures(List.of()));
          removed.add(old.record.id());
        }
      // Central junction supports have one shared owner across its fill triangles.
      // If a lower road changes any part of the pad, rebuild the complete junction.
      var supportGroups=new HashSet<UUID>();
      for(var r:built)if(r.record.junction()!=null)supportGroups.add(r.record.assembly());
      for(var old:index.roads.values())if(old.record.junction()!=null&&supportGroups.contains(old.record.assembly())&&!removed.contains(old.record.id())&&built.stream().noneMatch(r->r.record.id().equals(old.record.id()))){built.add(old.structures(List.of()));removed.add(old.record.id());}
      timing.stage("affected_structures");com.sora.splineroads.core.RoadPlanningBudget.phase("affected_structures");
      if(!deleting)AttachedPoints.reconcile(this,built,removed,false,deletedPoints);
      timing.stage("attached_points");com.sora.splineroads.core.RoadPlanningBudget.phase("attached_points");
      capPlanning(built,removed);
      if(deleting){
        LaneTopology.reconcileDeletion(this,built,removed);
        normalizeTransitions(built,removed,player,true);
        LaneTopology.refreshDeletionPoints(this,built,removed);
      }else LaneTopology.reconcile(this,built,removed);
      enlistSupportDependencies(built,removed);
      enlistCycleContinuations(built,removed);
      timing.stage("topology");com.sora.splineroads.core.RoadPlanningBudget.phase("topology");
      built.sort(Comparator.comparing(r -> r.record.id()));
      List<RoadIndex.Built> planning=capPlanning(built,removed);
      List<RoadIndex.Built> terrainRoads = new ArrayList<>(built);
      for (UUID id : removed)
        if (index.roads.containsKey(id)) terrainRoads.add(index.roads.get(id));
      boolean largeAssembly=terrainRoads.stream().anyMatch(r->{
        var descriptor=r.record.assembly()==null?null:interchanges.get(r.record.assembly());
        return descriptor!=null&&descriptor.getLongArray("Points").length>=3;
      });
      if(largeAssembly){workChunks.multiInterchange();editCellLimit=Math.max(editCellLimit,RoadLimits.MAX_MULTI_INTERCHANGE_EDIT_CELLS);}
      timing.stage("caps_and_dependencies");com.sora.splineroads.core.RoadPlanningBudget.phase("caps_and_dependencies");
      workChunks.roads(terrainRoads);
      timing.stage("terrain_chunk_access");com.sora.splineroads.core.RoadPlanningBudget.phase("terrain_chunk_access");
      Map<BlockPos, List<net.minecraft.world.phys.AABB>> terrainCache = new RoadTerrain.Cache(terrainFill,terrainOriginal);
      // Keep saved origins; anchor newly added sections to the already-built connected road.
      List<RoadIndex.Built> spacingReferences = new ArrayList<>(index.roads.values());
      for (int i = 0; i < built.size(); i++) {
        var r = built.get(i);
        var next = r.phase(FurnitureSpacing.resolve(r.record, r.mesh, spacingReferences));
        built.set(i, next);
        planning.set(planning.indexOf(r), next);
        spacingReferences.removeIf(o -> o.record.id().equals(r.record.id()));
        spacingReferences.add(next);
      }
      timing.stage("furniture_phase");com.sora.splineroads.core.RoadPlanningBudget.phase("furniture_phase");
      var structureLookup=new RoadPlanningIndex(planning,built.size());
      reserveBatchFixtures(level,built,planning,terrainFill,terrainOriginal,terrainCache,structureLookup,explicit,removed);
      timing.stage("structure_candidate_index");com.sora.splineroads.core.RoadPlanningBudget.phase("structure_candidate_index");
      for (int i = 0; i < built.size(); i++) {
        var r = built.get(i);
        RoadRecord planned = StructurePlanner.plan(level, r, planning, terrainFill, terrainOriginal, terrainCache,structureLookup);
        var next = r.planned(planned);
        built.set(i, next);
        int slot=planning.indexOf(r);planning.set(slot,next);structureLookup.replace(slot,next);
      }
      timing.stage("structure_plan");com.sora.splineroads.core.RoadPlanningBudget.phase("structure_plan");
      LaneTopology.snapPlannedPoints(built,planning);
      // Terrain-derived raised medians can change a lane center after planning. Resolve
      // dependent ports again before any world write; never save an off-center marker.
      for(int pass=0;!deleting&&(LaneTopology.needsRefresh(this,planning,built.stream().map(b->b.record.id()).toList())||LaneCrossSections.needsRestoreRefresh(planning,built.stream().map(b->b.record.id()).toList()));pass++){
        if(pass>=3){
          for(var pending:planning)if(built.stream().anyMatch(v->v.record.id().equals(pending.record.id())))for(var point:LaneTopology.metadata(pending.record).points()){
            var resolved=LanePoints.lane(pending.mesh,point);double drift=resolved.position().distance(point.position());
            if(drift>1e-5)System.getLogger("SplineRoads/topology").log(System.Logger.Level.WARNING,"Unstable lane {0} road={1} delta={2} station={3} point={4} anchor={5} resolved={6}",point.lane(),pending.record.id(),drift,resolved.station(),point.position(),point.anchor(),resolved.position());
          }
          throw new IllegalArgumentException("断面与车道点未能稳定，请调整道路样式后重试");
        }
        LaneTopology.reconcile(this,built,removed);
      enlistSupportDependencies(built,removed);
        // Late topology can enlist a remote support owner; its terrain must be loaded too.
        workChunks.roads(built);
        planning=capPlanning(built,removed);
        structureLookup=new RoadPlanningIndex(planning,built.size());
        reserveBatchFixtures(level,built,planning,terrainFill,terrainOriginal,terrainCache,structureLookup,explicit,removed);
        for(int i=0;i<built.size();i++){var r=built.get(i);var next=r.planned(StructurePlanner.plan(level,r,planning,terrainFill,terrainOriginal,terrainCache,structureLookup));built.set(i,next);int slot=planning.indexOf(r);planning.set(slot,next);structureLookup.replace(slot,next);}
        LaneTopology.snapPlannedPoints(built,planning);
      }
      timing.stage("dependent_replanning");com.sora.splineroads.core.RoadPlanningBudget.phase("dependent_replanning");
      // Both build orders see the final supports. Earlier provisional planting
      // must not survive inside a bridge column planned later in this batch.
      if(built.stream().anyMatch(r->com.sora.splineroads.core.RoadStreetscape.hasPlanting(r.mesh))){
      var supports=new com.sora.splineroads.core.RoadSolidOverlap.Index(planning.stream()
          .flatMap(r->r.record.structures().stream()).filter(p->p.material()==com.sora.splineroads.core.RoadStructures.Material.CONCRETE&&p.height()>.5)
          .toList());
      for(int i=0;i<built.size();i++){
        var r=built.get(i);var parts=com.sora.splineroads.core.RoadStreetscape.clearBlockedPlanting(
            r.record.structures(),r.mesh,r.record.furniturePhase()==null?com.sora.splineroads.core.RoadFurniture.Phase.DEFAULT:r.record.furniturePhase(),supports::intersects);
        if(parts!=r.record.structures())built.set(i,r.structures(parts));
      }
      }
      var noseCaps =
          RoadNoses.connectors(
              built.stream().map(r -> r.mesh).toList(),
              built.stream().map(r -> r.record.structures()).toList());
      for (var entry : noseCaps.entrySet()) {
        var old = built.get(entry.getKey());
        var parts = new ArrayList<>(old.record.structures());
        parts.addAll(entry.getValue());
        built.set(entry.getKey(), old.structures(parts));
      }
      LampSpacing.reconcile(this,built,removed);
      RailbedRoadEdits.enlist(this,level,built,removed,requested);
      workChunks.roads(built);
      // Check the FINAL list (nose caps may have replaced entries since planning).
      // Replaying an unchanged saved conflict must not prevent removing an unrelated road.
      var shellFinal=new ArrayList<RoadIndex.Built>();
      for(var old:index.roads.values())if(!removed.contains(old.record.id()))shellFinal.add(old);
      shellFinal.addAll(built);
      var shellChanged=new HashSet<UUID>();for(var changed:built)shellChanged.add(changed.record.id());
      // Deletion may rebuild surviving walls or end collars. The validator already
      // exempts unchanged saved parts against unchanged travel geometry; newly
      // introduced conflicts must still fail before any world or ledger write.
      TunnelShellValidation.check(shellFinal,shellChanged,index.roads);
      RoadTravelValidation.check(shellFinal,shellChanged,index.roads);
      if(!deleting)for(var road:built)RoadExternalClearance.validate(road.record);
      if(!deleting){TollRoads.validateConnections(shellFinal);checkJoints(built, removed);}
      timing.stage("caps_shell_and_joint_validation");com.sora.splineroads.core.RoadPlanningBudget.phase("caps_shell_and_joint_validation");
      final List<RoadIndex.Built> committed = built;
      final Set<UUID> removedIds = removed;
      Set<Long> touched = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
      int scanLimit=editCellLimit==RoadLimits.MAX_MULTI_INTERCHANGE_EDIT_CELLS?RoadLimits.MAX_MULTI_INTERCHANGE_SCAN_CELLS:editCellLimit;
      for (UUID id : removed) {
        var old = index.roads.get(id);
        if (old != null) {
          if(old.record.legacyRailway()){touched.addAll(RailbedRoadEdits.cells(old.record));continue;}
          touched.addAll(old.cells.keySet());
          touched.addAll(old.clearanceCells);
        }
      }
      for (var r : built) {
        if(r.record.legacyRailway()){touched.addAll(RailbedRoadEdits.cells(r.record));continue;}
        touched.addAll(r.cells.keySet());
        touched.addAll(r.clearanceCells);
      }
      Set<Long> moveSources = new HashSet<>(), moveTargets = new HashSet<>();
      for (var move : moves) {
        if (move.from() != null) moveSources.add(move.from().asLong());
        if (move.from() != null) touched.add(move.from().asLong());
        if(move.to()!=null){
          if (!moveTargets.add(move.to().asLong())) throw new IllegalArgumentException("端点目标重叠");
          touched.add(move.to().asLong());
        }
      }
      if (touched.size() > scanLimit)
        throw new IllegalArgumentException("本次需检查 "+touched.size()+" 格，超过单次范围上限 "+scanLimit+"，请分段建造");
      List<RoadIndex.Built> finalRoads = new ArrayList<>();
      for (var r : index.roads.values()) if (!removed.contains(r.record.id())) finalRoads.add(r);
      finalRoads.addAll(built);
      timing.stage("edit_raster");com.sora.splineroads.core.RoadPlanningBudget.phase("edit_raster");
      Map<Long,BlockState> sidewalks=SmartSidewalks.plan(finalRoads,level,terrainFill,sidewalkPlaced,apronPlaced,built,terrainRoads);
      var aprons=SmartRoadAprons.plan(sidewalks,finalRoads,level,terrainOriginal,sidewalkPlaced,apronPlaced,terrainRoads);
      for(var e:sidewalkPlaced.entrySet())if(!e.getValue().equals(sidewalks.get(e.getKey())))touched.add(e.getKey());
      for(var e:sidewalks.entrySet())if(!e.getValue().equals(sidewalkPlaced.get(e.getKey())))touched.add(e.getKey());
      if(touched.size()>scanLimit)throw new IllegalArgumentException("含路侧铺装需检查 "+touched.size()+" 格，超过单次范围上限 "+scanLimit+"，请分段建造");
      timing.stage("sidewalks");com.sora.splineroads.core.RoadPlanningBudget.phase("sidewalks");
      Map<Long, List<RoadIndex.Built>> body = new HashMap<>();
      Set<Long> air = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
      Set<Long> dry = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
      Set<Long> touchedChunks = new HashSet<>();
      for (long key : touched)
        touchedChunks.add(new net.minecraft.world.level.ChunkPos(BlockPos.of(key)).toLong());
      workChunks.load(touchedChunks);
      Map<Long,List<BlockPos>> touchedByChunk=new HashMap<>();
      Map<Long,List<RoadIndex.Built>> finalByChunk=new HashMap<>();
      for(long key:touched){var pos=BlockPos.of(key);touchedByChunk.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4),k->new ArrayList<>()).add(pos);}
      for (var r : finalRoads) {
        if (Collections.disjoint(r.chunks, touchedChunks)) continue;
        for(long chunk:r.chunks)if(touchedChunks.contains(chunk))finalByChunk.computeIfAbsent(chunk,k->new ArrayList<>()).add(r);
        if(r.rasterized()) {
          for (long key : r.cells.keySet())if (touched.contains(key))body.computeIfAbsent(key,k->new ArrayList<>()).add(r);
          for (long key : r.clearanceCells)if(touched.contains(key)){air.add(key);if(r.tunnelAt(BlockPos.of(key)))dry.add(key);}
        } else {
          // A short edit next to a kilometres-long unchanged road must not materialize its
          // entire deck, furniture and clearance volume. Query only touched local columns.
          for(long chunk:r.chunks)for(var pos:touchedByChunk.getOrDefault(chunk,List.of())) {
            long key=pos.asLong();
            if(!r.boxes(pos).isEmpty())body.computeIfAbsent(key,k->new ArrayList<>()).add(r);
            if(r.clearanceAt(pos)){air.add(key);if(r.tunnelAt(BlockPos.of(key)))dry.add(key);}
          }
        }
      }
      timing.stage("local_collision");com.sora.splineroads.core.RoadPlanningBudget.phase("local_collision");
      Set<BlockPos> endpoints = new HashSet<>(selectedNodes);
      // Explicit node moves are validated below and committed atomically with the road.
      // Their old markers may lie inside the extended deck until that transaction commits.
      for(long source:moveSources)endpoints.add(BlockPos.of(source));
      for (var r : built) {
        endpoints.add(r.record.a());
        endpoints.add(r.record.b());
      }
      if (assembly)
        for (UUID id : removed) {
          var old = index.roads.get(id);
          if (old != null) {
            endpoints.add(old.record.a());
            endpoints.add(old.record.b());
          }
        }
      // Only markers belonging to a rebuilt, already existing assembly are exempt.
      // Unrelated markers in a new road's footprint remain errors.
      for(var center:junctions.values())if(center.getBoolean("GeneratedRing") && built.stream().anyMatch(r->center.getUUID("Id").equals(r.record.assembly())))
        for(long port:center.getLongArray("Ports"))endpoints.add(BlockPos.of(port));
      var prepared=new PreparedEdit(List.copyOf(built),Set.copyOf(removed),deleting,touched,moveSources,moveTargets,
          sidewalks,aprons,body,air,dry,finalByChunk,Set.copyOf(endpoints),List.copyOf(finalRoads),List.copyOf(moves),editCellLimit,
          RoadWorkChunks.held(level));
      // Queued street preparation retains geometry and its exact read set only.
      // The next tick runs ALL protection/write validation before any mutation.
      // Interactive previews retain their existing immediate validation behavior.
      if(previewResult==null||validatePreview)validateAndCommit(level,player,prepared,previewResult!=null);
      if(previewResult!=null)previewResult.add(prepared);
      timing.stage("validation_commit_sync");
    }
  }

  /** Reuses all geometry/raster planning; world protection and current block contents are
   * always checked again, before the first write. No route search occurs on this path. */
  private void validateAndCommit(ServerLevel level,ServerPlayer player,PreparedEdit edit,boolean preview){
    var built=edit.built();var committed=built;var removed=edit.removed();var removedIds=removed;
    boolean deleting=edit.deleting();var touched=edit.touched();var moveSources=edit.moveSources();var moveTargets=edit.moveTargets();
    var sidewalks=edit.sidewalks();var body=edit.body();var air=edit.air();var dry=edit.dry();
    var finalByChunk=edit.finalByChunk();var endpoints=edit.endpoints();var finalRoads=edit.finalRoads();var moves=edit.moves();int editCellLimit=edit.editCellLimit();
      Map<Long, BlockState> writes = new HashMap<>();
      for (long key : touched) {
        com.sora.splineroads.core.RoadPlanningBudget.check();
        BlockPos p = BlockPos.of(key);
        if (level.isOutsideBuildHeight(p) || !level.getWorldBorder().isWithinBounds(p))
          throw new IllegalArgumentException("道路超出世界高度或边界");
        if (!level.hasChunkAt(p)) throw new IllegalArgumentException("道路区块加载失败，请稍后重试");
        if (player != null && !level.mayInteract(player, p))
          throw new IllegalArgumentException("道路经过受保护区域");
        BlockState state = RoadPlanInputs.state(level,p);
        boolean roadBody = body.containsKey(key), headroom = air.contains(key);
        boolean walkway=sidewalks.containsKey(key);
        if (!roadBody && !headroom && !walkway) continue;
        var localRoads=finalByChunk.getOrDefault(new net.minecraft.world.level.ChunkPos(p).toLong(),List.of());
        boolean ordinaryBody=body.getOrDefault(key,List.of()).stream().anyMatch(r->!r.record.legacyRailway());
        boolean ordinaryAir=localRoads.stream().anyMatch(r->!r.record.legacyRailway()&&r.clearanceAt(p));
        boolean railBody=body.getOrDefault(key,List.of()).stream().anyMatch(r->r.record.legacyRailway());
        if(!deleting){
          if(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("mtr")){
            boolean selectedNode=com.sora.splineroads.compat.mtr.MtrRailAccess.trainNode(state.getBlock())&&localRoads.stream().anyMatch(r->r.record.settings().options().railbed().paths().stream().flatMap(path->path.segments().stream()).anyMatch(segment->RailbedCodec.pos(segment.from()).equals(p)||RailbedCodec.pos(segment.to()).equals(p)));
            boolean motorConflict=localRoads.stream().anyMatch(r->!r.record.railway()&&(!r.boxes(p).isEmpty()||r.clearanceAt(p)));
            boolean fixture=localRoads.stream().filter(r->r.record.railway()).anyMatch(r->r.prepareCollision().structureAt(new RoadRaster.Cell(p.getX(),p.getY(),p.getZ()),part->true));
            boolean levelCrossing=localRoads.stream().filter(r->r.record.railway()).anyMatch(r->localRoads.stream().anyMatch(other->RailwayCrossings.at(r.mesh,other.mesh,new V(p.getX()+.5,p.getY(),p.getZ()+.5),.5)));
            if(selectedNode&&(!motorConflict||levelCrossing)&&!fixture)continue;
            throw new IllegalArgumentException("道路施工碰到 MTR 设施："+p.toShortString());
          }
          for(var candidate:localRoads)if(candidate.record.legacyRailway()&&((ordinaryBody||ordinaryAir||walkway)&&!candidate.boxes(p).isEmpty()||(ordinaryBody||walkway)&&candidate.clearanceAt(p)))throw new IllegalArgumentException("道路施工侵入铁路路基本体或列车通行空间："+p.toShortString());
        }
        // A dependency refresh owns its support cells, never the existing
        // railway's deck, MTR markers or air corridor.
        if(!ordinaryBody&&!ordinaryAir&&!walkway&&localRoads.stream().anyMatch(r->r.record.legacyRailway()&&r.clearanceAt(p))&&!railBody)continue;
        if (state.is(SplineRoads.NODE.get())) {
          if (!endpoints.contains(p)
              && !deleting
              && (walkway || committed.stream()
                  .anyMatch(r -> r.cells.containsKey(key) || r.clearanceCells.contains(key))))
            throw new IllegalArgumentException("道路范围内有其他端点：" + p.toShortString());
          continue;
        }
        // A road always owns its slab collision, including a deck flush with terrain.
        // Existing infrastructure above the new driving corridor cannot be silently erased.
        if (!deleting && headroom
            && !roadBody
            && RoadBlocks.isCollider(state)
            && index.at(key).stream().anyMatch(id -> !removedIds.contains(id)))
          throw new IllegalArgumentException("通行空间与另一条道路相交");
        // Restored host clearance must not erase a surviving road or the user's
        // blocks while deleting. Its existing geometry is allowed to remain.
        if(deleting&&!roadBody&&!walkway)continue;
        if(deleting&&!RoadBlocks.isCollider(state)&&(state.hasBlockEntity()||state.getDestroySpeed(level,p)<0))continue;
        boolean railEdge=!roadBody&&!walkway&&headroom&&!dry.contains(key)&&RailbedTerrainEdge.present(p,retainedSource(key,state,null),localRoads);
        BlockState target = railEdge?SplineRoads.FILLED_COLLIDER.get().defaultBlockState():railBody&&!ordinaryBody&&!walkway
            ? (RoadBlocks.isCollider(state)?state:SplineRoads.COLLIDER.get().defaultBlockState())
            : roadBody ? collisionState(key, state, body.get(key), sidewalks.get(key),dry.contains(key),finalByChunk.getOrDefault(new net.minecraft.world.level.ChunkPos(p).toLong(),List.of())) : walkway ? sidewalks.get(key) : dry.contains(key)?SplineRoads.TUNNEL_AIR.get().defaultBlockState():Blocks.AIR.defaultBlockState();
        if (state.equals(target) && !(target.is(SplineRoads.FILLED_COLLIDER.get())
            && level.getBlockEntity(p) instanceof RoadFillEntity fill
            && (!fill.fill().equals(retainedSource(key,state,sidewalks.get(key)))||!fill.edge().equals(RailbedTerrainEdge.boxes(p,retainedSource(key,state,sidewalks.get(key)),localRoads))||Math.abs(fill.cap()-GroundTerrainJoin.cap(p,retainedSource(key,state,sidewalks.get(key)),localRoads))>1e-8))) continue;
        RoadClearing.validate(level,p,state,state.equals(sidewalkPlaced.get(key)));
        writes.put(key, target);
      }
      for (var move : moves) {
        if (move.from() != null) requireNode(level, move.from(), player);
        if(move.to()==null){
          if(move.from()==null||finalRoads.stream().anyMatch(r->r.record.a().equals(move.from())||r.record.b().equals(move.from())))throw new IllegalArgumentException("仍在使用的端点不能清理");
          long key=move.from().asLong();
          writes.put(key,body.containsKey(key)?collisionState(key,Blocks.AIR.defaultBlockState(),body.get(key),sidewalks.get(key),dry.contains(key),finalByChunk.getOrDefault(new net.minecraft.world.level.ChunkPos(move.from()).toLong(),List.of())):dry.contains(key)?SplineRoads.TUNNEL_AIR.get().defaultBlockState():Blocks.AIR.defaultBlockState());
          continue;
        }
        BlockState state = level.getBlockState(move.to());
        boolean movableMarker = moveSources.contains(move.to().asLong());
        boolean replacedRoad =
            RoadBlocks.isCollider(state)
                && index.at(move.to().asLong()).stream().allMatch(removedIds::contains);
        // The transaction has already approved these terrain writes, but has not
        // applied them yet. A fitted marker inside the new road must use that
        // same clearing decision instead of treating the old ground as occupied.
        // Do not extend this exemption to containers or unrelated road colliders.
        long targetKey = move.to().asLong();
        boolean clearedByConstruction =
            writes.containsKey(targetKey)
                && !RoadBlocks.isCollider(state)
                && !state.hasBlockEntity()
                && level.getBlockEntity(move.to()) == null
                && committed.stream().anyMatch(r ->
                    r.cells.containsKey(targetKey) || r.clearanceCells.contains(targetKey));
        if (!movableMarker
            && !replacedRoad
            && !clearedByConstruction
            && (!state.canBeReplaced() || state.hasBlockEntity() || level.getBlockEntity(move.to()) != null))
          throw new IllegalArgumentException("端点目标被占用：" + move.to().toShortString()
              + "（" + state.getBlock().getName().getString() + " / "
              + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "）");
        long source = move.from() == null ? 0 : move.from().asLong();
        if (move.from() != null && !moveTargets.contains(source))
          writes.put(
              source,
              body.containsKey(source)
                  ? collisionState(source, Blocks.AIR.defaultBlockState(), body.get(source),sidewalks.get(source),dry.contains(source),finalByChunk.getOrDefault(new net.minecraft.world.level.ChunkPos(BlockPos.of(source)).toLong(),List.of()))
                  : dry.contains(source)?SplineRoads.TUNNEL_AIR.get().defaultBlockState():Blocks.AIR.defaultBlockState());
        writes.put(move.to().asLong(), SplineRoads.NODE.get().defaultBlockState());
      }
      // Count only world writes. Cleared headroom has no restoration history.
      long changed=writes.size();var streetRestores=StreetEditScope.active()?new HashSet<Long>():null;
      for(long key:touched) {
        if(body.containsKey(key)||!deleting&&air.contains(key)||sidewalks.containsKey(key)||moveTargets.contains(key))continue;
        BlockState current=level.getBlockState(BlockPos.of(key));
        if(RoadBlocks.isCollider(current)||current.is(SplineRoads.TUNNEL_AIR.get())||current.equals(sidewalkPlaced.get(key)))changed++;
        if(streetRestores!=null&&(RoadBlocks.isCollider(current)||TunnelAir.isAirSpace(current)||current.equals(sidewalkPlaced.get(key))||terrainOriginal.containsKey(key)||terrainFill.containsKey(key)))streetRestores.add(key);
      }
      if(changed>editCellLimit)throw new IllegalArgumentException("实际需修改 "+changed+" 个方块，超过本次上限 "+editCellLimit);
      StreetEditScope.prepare(level,built,removed,touched,writes,sidewalks,edit.aprons(),streetRestores,preview);
      if(preview)return;
      ManualPlannerHistory.capture(level,touched);
      if(player!=null){
        for(var write:writes.entrySet()){var p=BlockPos.of(write.getKey());RoadEditProtection.check(level,player,p,level.getBlockState(p),write.getValue());}
        for(long key:touched){
          if(body.containsKey(key)||!deleting&&air.contains(key)||sidewalks.containsKey(key)||moveTargets.contains(key))continue;
          var p=BlockPos.of(key);var current=level.getBlockState(p);
          if(RoadBlocks.isCollider(current)||TunnelAir.isAirSpace(current)||current.equals(sidewalkPlaced.get(key)))
            RoadEditProtection.check(level,player,p,current,terrainOriginal.getOrDefault(key,terrainFill.getOrDefault(key,Blocks.AIR.defaultBlockState())));
        }
      }
      com.sora.splineroads.core.RoadPlanningBudget.check();
      var groups=exactSplit?Set.<UUID>of():junctionGroups(edit);
      var snapshot=exactSplit?null:new EditSnapshot(level,edit,false);
      var callback=index.changed;if(snapshot!=null)index.changed=id->{};
      try{
      com.sora.splineroads.core.RoadPlanningBudget.committing();
      // A terrain apron can already equal the ground. It still acquires ownership,
      // so save its original even when no block-state write is needed.
      for(long key:sidewalks.keySet())if(!sidewalkPlaced.containsKey(key)&&!terrainOriginal.containsKey(key)){
        var state=level.getBlockState(BlockPos.of(key));if(!RoadBlocks.isCollider(state))terrainOriginal.put(key,state);
      }
      // Old railway piers used a separate restoration ledger. Hand their
      // original terrain to this atomic edit before moving/removing supports.
      for(var road:built)if(road.record.legacyRailway()){
        var prior=railbeds.entries.get(road.record.id());for(long key:RailbedRoadEdits.cells(prior.road))if(prior.original.containsKey(key))terrainOriginal.putIfAbsent(key,prior.original.get(key));
      }
      // All range/permission/geometry checks completed before any block is cleared.
      for (UUID id : removed) index.remove(id);
      if(!exactSplit)editFault.accept("remove");
      for (var r : built) {
        index.put(r);if(exactSplit)splitFault.accept("index");else editFault.accept("index");
        var logical=streets.get(r.record.id());
        if(!exactSplit&&logical!=null&&r.record.junction()==null) {
          // A continuation may be built with the ordinary road tool even when the far
          // end belongs to an auto junction. Persist its section in the full logical path.
          var settings=r.record.settings();var ends=settings.options().ends();
          var full=settings.options(settings.options().ends(new RoadTransitions.Ends(ends.start(),ends.end(),ends.persistent()).paintPhase(ends.paintPhase())));
          // An untrimmed fitted tunnel owns its newly moved endpoints. Retaining the old
          // logical path here resurrected the short, over-steep tunnel on the next edit/delete.
          streets.put(logical.id(),logical.fragment()!=null?logical.settings(settings.options(settings.options().ends(logical.settings().options().ends()))).caps(r.record.endCaps()):ends.trimmedStart()==0&&ends.trimmedEnd()==0
              ?r.record.settings(full):logical.settings(full).caps(r.record.endCaps()));
        }
      }
      for (var entry : writes.entrySet()) {
        long key = entry.getKey();
        BlockPos p = BlockPos.of(key);
        BlockState old = level.getBlockState(p);
        BlockState target=entry.getValue();
        // Excavation belongs to the transaction too. Retain cleared natural solids
        // above the deck so replanning sees the original cover and retired cuts heal.
        if(!terrainOriginal.containsKey(key)&&!RoadBlocks.isCollider(old)&&!old.is(SplineRoads.TUNNEL_AIR.get())&&!old.is(SplineRoads.NODE.get())&&!old.equals(sidewalkPlaced.get(key))) {
          boolean ground=body.containsKey(key)||sidewalks.containsKey(key)
              ||air.contains(key)&&old.canOcclude()&&!old.is(net.minecraft.tags.BlockTags.LEAVES)&&!old.is(net.minecraft.tags.BlockTags.LOGS);
          if(!ground&&air.contains(key)&&old.canOcclude())for(var road:committed){
            var q=RoadQueries.horizontal(road.mesh,new V(p.getX()+.5,p.getY(),p.getZ()+.5));
            if(p.getY()<=q.sample().center().y()+.001&&q.horizontalDistance()<=q.sample().halfWidth()+road.record.settings().options().sidewalk().width()+1){ground=true;break;}
          }
          if(ground)terrainOriginal.put(key,old);
        }
        BlockState fill=retainedSource(key,old,sidewalks.get(key));
        boolean retained=RoadBlocks.isCollider(target)&&(target.is(SplineRoads.FILLED_COLLIDER.get())||target.getValue(RoadBlocks.Road.FILL)!=RoadBlocks.Fill.NONE);
        if(retained&&!TunnelAir.isAirSpace(fill)&&!RoadBlocks.isCollider(fill))terrainFill.put(key,fill);
        else terrainFill.remove(key);
        if(!exactSplit)editFault.accept("ledger");
        checkedSetBlock(level,p,entry.getValue(),2);if(exactSplit)splitFault.accept("write");else editFault.accept("write");
        if(level.getBlockEntity(p) instanceof RoadFillEntity filled){var material=sidewalks.getOrDefault(key,terrainFill.getOrDefault(key,Blocks.AIR.defaultBlockState()));filled.fill(material,GroundTerrainJoin.cap(p,material,finalByChunk.getOrDefault(new net.minecraft.world.level.ChunkPos(p).toLong(),List.of())));}
      }
      for (var move : moves) {
        if(move.to()==null)continue;
        if(!(level.getBlockEntity(move.to()) instanceof NodeEntity marker))throw new IllegalStateException("道路端点实体创建失败："+move.to().toShortString());
        marker.load(move.snapshot().copy());
        marker.heightExplicit = true;
        marker.apply(move.target());
        if(!exactSplit)editFault.accept("node");
      }
      var restoredBlocks=new LinkedHashMap<Long,BlockState>();
      for (long key : touched) {
        com.sora.splineroads.core.RoadPlanningBudget.check();
        // Net clearance belongs to every surviving road, including during deletion.
        if (body.containsKey(key) || air.contains(key) || sidewalks.containsKey(key) || moveTargets.contains(key)) continue;
        BlockPos p = BlockPos.of(key);
        BlockState current = level.getBlockState(p);
        BlockState fill = terrainFill.remove(key);
        BlockState original = terrainOriginal.remove(key);
        if (RoadBlocks.isCollider(current)) {
          // Only infill visibly retained inside a live collider survives removal.
          boolean retained=current.is(SplineRoads.FILLED_COLLIDER.get())||current.getValue(RoadBlocks.Road.FILL)!=RoadBlocks.Fill.NONE;
          restoredBlocks.put(key,original!=null?original:retained&&fill!=null&&!sidewalkPlaced.containsKey(key)?fill:Blocks.AIR.defaultBlockState());
        } else if(TunnelAir.isAirSpace(current)||current.equals(sidewalkPlaced.get(key)))restoredBlocks.put(key,original!=null?original:Blocks.AIR.defaultBlockState());
      }
      restoreBlocks(level,restoredBlocks,Map.of(),failure->{throw failure;},true);
      RailbedRoadEdits.sync(this,level,built);
      RailbedRoads.sync(this,built,removed);
      sidewalkPlaced=new Long2ObjectOpenHashMap<>(sidewalks);apronPlaced=new it.unimi.dsi.fastutil.longs.LongOpenHashSet(edit.aprons());
      if(!exactSplit)RampJunctions.sync(this,groups);
      for(var r:built)if(streets.containsKey(r.record.id())){var logical=streets.get(r.record.id());streets.put(logical.id(),logical.withLanePoints(ElevatedAccessRoads.logicalLanes(logical,r.record)));}
      setDirty();
      if(!exactSplit)editFault.accept("metadata");
      }catch(RuntimeException failure){if(snapshot!=null)snapshot.restore(level,failure);throw failure;}
      finally{if(snapshot!=null)index.changed=callback;}
      if(exactSplit)return;
      ElevatedAccessRoads.prune(this);
      var changedIds=new LinkedHashSet<>(removed);for(var r:built)changedIds.add(r.record.id());changedIds.forEach(callback);
      for (UUID id : removed)
        if (committed.stream().noneMatch(b -> b.record.id().equals(id)))
          RoadNetwork.broadcastDelete(level, id);
      for (var r : built) RoadNetwork.broadcastRoad(level, r.record);
  }

  BlockState railbedCollision(BlockPos p,BlockState original,List<RoadIndex.Built> nearby){
    var owners=nearby.stream().filter(r->!r.boxes(p).isEmpty()).toList();
    return collisionState(p.asLong(),original,owners,null,nearby.stream().anyMatch(r->r.tunnelAt(p)&&r.clearanceAt(p)),nearby);
  }

  private BlockState collisionState(long key, BlockState previous, List<RoadIndex.Built> roads) {
    return collisionState(key,previous,roads,sidewalkPlaced.get(key));
  }
  private BlockState collisionState(long key, BlockState previous, List<RoadIndex.Built> roads,BlockState sidewalk) {
    var pos=BlockPos.of(key);
    var nearby=index.inChunk(new net.minecraft.world.level.ChunkPos(pos).toLong()).stream()
        .map(index.roads::get).filter(Objects::nonNull).toList();
    boolean dryInterior=nearby.stream().anyMatch(r->r.tunnelAt(pos)&&r.clearanceAt(pos));
    return collisionState(key,previous,roads,sidewalk,dryInterior,nearby);
  }
  private BlockState collisionState(long key, BlockState previous, List<RoadIndex.Built> roads,BlockState sidewalk,boolean dryInterior,List<RoadIndex.Built> nearby) {
    BlockPos p = BlockPos.of(key);
    BlockState terrain =
        sidewalk!=null ? sidewalk : sidewalkPlaced.containsKey(key) ? terrainOriginal.getOrDefault(key,Blocks.AIR.defaultBlockState()) : RoadBlocks.isCollider(previous)||previous.equals(sidewalkPlaced.get(key))
            ? terrainFill.getOrDefault(key, Blocks.AIR.defaultBlockState())
            : previous;
    // Legacy edge cells may have cleared their visible fill but retained the original material.
    if(TunnelAir.isAirSpace(terrain)&&RoadBlocks.isCollider(previous))terrain=terrainOriginal.getOrDefault(key,terrain);
    RoadBlocks.Fill fill = RoadBlocks.Fill.of(terrain);
    boolean generic=fill==RoadBlocks.Fill.NONE&&!TunnelAir.isAirSpace(terrain)&&(sidewalk!=null||previous.is(SplineRoads.FILLED_COLLIDER.get()));
    boolean deck = false;
    boolean gantryFill=previous.is(SplineRoads.FILLED_COLLIDER.get())&&roads.stream().anyMatch(r->RoadBlocks.gantryCell(r,new RoadRaster.Cell(p.getX(),p.getY(),p.getZ())));
    for (var r : roads) {
      var c = r.column(p);
      if (c == null) continue;
      deck = true;
      if (p.getY() + fill.top() > c.minTop() + 1e-7) fill = RoadBlocks.Fill.NONE;
      if(!gantryFill&&p.getY()+1>c.minTop()+1e-7)generic=false;
    }
    if (!deck && fill != RoadBlocks.Fill.NONE) {
      double lowest =
          roads.stream()
              .flatMap(r -> r.boxes(p).stream())
              .mapToDouble(com.sora.splineroads.core.RoadRaster.Box::y0)
              .min()
              .orElse(0);
      if (fill.top() > lowest + 1e-7) fill = RoadBlocks.Fill.NONE;
    }
    // Preserve only full original terrain that does not enter ANY final travel volume.
    // A global maximum arch height falsely classified roof/portal exterior as interior.
    // Use the final neighboring roads, not only owners of this exact structural cell.
    boolean exteriorShell=roads.stream().anyMatch(r->r.shellAt(p));
    if(exteriorShell&&TunnelTerrainSpace.safe(p,nearby)){
      BlockState original=terrain;
      if(TunnelAir.isAirSpace(original)&&RoadBlocks.isCollider(previous))original=terrainOriginal.getOrDefault(key,Blocks.AIR.defaultBlockState());
      if(!original.hasBlockEntity()&&original.getFluidState().isEmpty()){
        fill=RoadBlocks.Fill.of(original);
        generic=fill==RoadBlocks.Fill.NONE&&!TunnelAir.isAirSpace(original)&&original.canOcclude();
      }
    }
    // Retain the full original terrain under/along a diagonal sidewalk cell.
    // The continuous SR surface sits above it; only actual elevated voids stay empty.
    double walkTop=roads.stream().mapToDouble(r->r.walkTopAt(p)).max().orElse(Double.NEGATIVE_INFINITY);
    if(!deck&&Double.isFinite(walkTop)&&!terrain.hasBlockEntity()&&terrain.getFluidState().isEmpty()&&!TunnelAir.isAirSpace(terrain)){
      var retained=RoadBlocks.Fill.of(terrain);double top=retained==RoadBlocks.Fill.NONE?1:retained.top();
      if(p.getY()+top<=walkTop+1e-6){fill=retained;generic=fill==RoadBlocks.Fill.NONE&&terrain.canOcclude();}
    }
    // Rails/skirt foundations used to discard an entire grass cube whenever a
    // small shaft entered it. Keep the original solid around buried structures;
    // test all final travel volumes so this never fills a road/cycle/tunnel lane.
    if(!deck&&!TunnelAir.isAirSpace(terrain)&&terrain.canOcclude()&&!terrain.hasBlockEntity()
        &&terrain.getFluidState().isEmpty()&&TunnelTerrainSpace.exteriorInfill(p,nearby)){
      fill=RoadBlocks.Fill.of(terrain);generic=fill==RoadBlocks.Fill.NONE;
    }
    // A lowered cycle floor is not in columnData. The sidewalk maximum and
    // exterior-shell preservation above must never restore terrain into its air.
    double terrainCap=GroundTerrainJoin.cap(p,terrain,nearby);
    if(terrainCap<1){fill=RoadBlocks.Fill.NONE;generic=GroundTerrainJoin.usable(terrainCap);}
    boolean cycleBody=roads.stream().anyMatch(r->r.cycleBodyAt(p));
    if(dryInterior||cycleBody){fill=RoadBlocks.Fill.NONE;generic=false;}
    if(!dryInterior&&!cycleBody&&RailbedTerrainEdge.present(p,terrain,nearby)){fill=RoadBlocks.Fill.NONE;generic=true;}
    // Keep water only in exterior partial-shell cells, never in an interior clearance
    // cell or the actual road slab. The caller supplies the union of ALL tunnel air
    // reservations, including tunnels not owning this particular body cell.
    boolean deckHere=roads.stream().anyMatch(r->{var c=r.column(p);return c!=null
        &&p.getY()+1>c.minTop()-r.record.settings().thickness()+1e-7&&p.getY()<c.maxTop()-1e-7;});
    boolean tunnelOwner=roads.stream().anyMatch(RoadIndex.Built::hasTunnel);
    boolean shell=roads.stream().anyMatch(r->r.shellAt(p));
    boolean permeable=RoadWaterPolicy.permeable(deckHere,generic||fill!=RoadBlocks.Fill.NONE,tunnelOwner,shell,dryInterior);
    boolean originalWater=terrainOriginal.getOrDefault(key,Blocks.AIR.defaultBlockState()).getFluidState().is(net.minecraft.tags.FluidTags.WATER);
    boolean water=RoadWaterPolicy.waterlogged(permeable,terrain.getFluidState().is(net.minecraft.tags.FluidTags.WATER),
        previous.getFluidState().is(net.minecraft.tags.FluidTags.WATER),originalWater);
    return (generic?SplineRoads.FILLED_COLLIDER:SplineRoads.COLLIDER)
        .get()
        .defaultBlockState()
        .setValue(RoadBlocks.Road.FILL, fill)
        .setValue(RoadBlocks.Road.LIT, roads.stream().anyMatch(r -> r.lightAt(p)))
        .setValue(RoadBlocks.Road.PERMEABLE,permeable)
        .setValue(RoadBlocks.Road.WATERLOGGED,water)
        .setValue(RoadBlocks.Road.SEALED, shell);
  }

  /** Preserve the material, including uncommon full terrain, when repairing a shell
   * cell whose old collision state deliberately contained no visible infill. */
  private BlockState retainedSource(long key,BlockState previous,BlockState sidewalk){
    if(sidewalk!=null)return sidewalk;
    if(sidewalkPlaced.containsKey(key))return terrainOriginal.getOrDefault(key,Blocks.AIR.defaultBlockState());
    var fill=terrainFill.getOrDefault(key,previous);
    return RoadBlocks.isCollider(fill)||TunnelAir.isAirSpace(fill)&&RoadBlocks.isCollider(previous)
        ?terrainOriginal.getOrDefault(key,Blocks.AIR.defaultBlockState()):fill;
  }

  /** Fill the unused part of a deck cell without replacing the independent road collision. */
  public boolean infill(ServerLevel level,BlockPos p,BlockState fill,ServerPlayer player) {
    if(!RoadBlocks.canInfill(level,p,fill))return false;
    if(!mayEditFill(level,p,player))return false;
    BlockState old=level.getBlockState(p);
    terrainFill.put(p.asLong(),fill);
    terrainOriginal.put(p.asLong(),fill);
    level.setBlock(p,SplineRoads.FILLED_COLLIDER.get().defaultBlockState().setValue(RoadBlocks.Road.LIT,old.getValue(RoadBlocks.Road.LIT)).setValue(RoadBlocks.Road.SEALED,old.getValue(RoadBlocks.Road.SEALED)),3);
    var nearby=index.inChunk(new net.minecraft.world.level.ChunkPos(p).toLong()).stream().map(index.roads::get).filter(Objects::nonNull).toList();
    ((RoadFillEntity)level.getBlockEntity(p)).fill(fill,GroundTerrainJoin.cap(p,fill,nearby));setDirty();return true;
  }

  /** Filling and excavation share the authority of every road occupying this cell. */
  private boolean mayEditFill(ServerLevel level,BlockPos p,ServerPlayer player) {
    if(player==null)return true;
    if(!player.mayBuild()||!level.mayInteract(player,p))return false;
    if(player.hasPermissions(2))return true;
    for(UUID id:index.at(p.asLong())){
      var road=index.roads.get(id);
      if(road!=null&&road.record.owner()!=null&&!road.record.owner().equals(player.getUUID()))return false;
    }
    return true;
  }

  /** Dig the retained terrain while leaving the independent road slab in the same block cell. */
  public boolean excavateSupport(ServerLevel level, BlockPos p, ServerPlayer player) {
    BlockState current = level.getBlockState(p);
    if (!RoadBlocks.isCollider(current)
        || current.getValue(RoadBlocks.Road.FILL) == RoadBlocks.Fill.NONE && !current.is(SplineRoads.FILLED_COLLIDER.get())) return false;
    if (!mayEditFill(level,p,player)) return false;
    BlockState terrain =
        terrainFill.getOrDefault(p.asLong(), current.getValue(RoadBlocks.Road.FILL).state());
    if (player != null && !player.isCreative() && player.hasCorrectToolForDrops(terrain))
      net.minecraft.world.level.block.Block.dropResources(
          terrain, level, p, null, player, player.getMainHandItem());
    terrainFill.put(p.asLong(), Blocks.AIR.defaultBlockState());
    terrainOriginal.put(p.asLong(), Blocks.AIR.defaultBlockState());

    var owners=index.at(p.asLong()).stream().map(index.roads::get).filter(Objects::nonNull).toList();
    // Re-evaluate the now-empty cell against all remaining geometry. A shell may
    // admit exterior water, whereas a slab or any neighboring tunnel's air stays
    // dry. Passing no paving prevents reintroducing the material just excavated.
    var empty=collisionState(p.asLong(),current,owners,null);
    level.setBlock(p,empty,3);
    if(empty.getValue(RoadBlocks.Road.WATERLOGGED))
      level.scheduleTick(p,net.minecraft.world.level.material.Fluids.WATER,
          net.minecraft.world.level.material.Fluids.WATER.getTickDelay(level));
    setDirty();
    return true;
  }

  /** Remove obstructing lamps on already-built approach roads after sign relocation.
   * Only exact lamp furniture and its local collider cells are touched. */
  void clearSignSightlines(ServerLevel level,Set<UUID> changed){
    var ids=new HashSet<UUID>();
    for(UUID id:changed){var road=index.roads.get(id);if(road==null)continue;
      for(long key:road.chunks){var c=new net.minecraft.world.level.ChunkPos(key);for(int x=c.x-6;x<=c.x+6;x++)for(int z=c.z-6;z<=c.z+6;z++)ids.addAll(index.inChunk(net.minecraft.world.level.ChunkPos.asLong(x,z)));}}
    if(ids.isEmpty())return;var roads=ids.stream().map(index.roads::get).filter(Objects::nonNull).toList();
    var sight=JunctionSignRoads.lampClearance(roads);var chunks=new HashSet<Long>();
    for(var built:roads){var removed=StreetLampClearance.removed(built.record.structures(),sight);if(removed.isEmpty())continue;
      var changedChunks=removeSightlineLamps(level,built,removed);if(changedChunks!=null)chunks.addAll(changedChunks);
    }
    for(long key:chunks){var chunk=new net.minecraft.world.level.ChunkPos(key);if(level.hasChunk(chunk.x,chunk.z))repairChunk(level,chunk);}
  }

  /** Commit one checked lamp result. Null asks a budgeted caller to recheck its
   * stale road snapshot; returned chunks can be repaired in later bounded work. */
  Set<Long> removeSightlineLamps(ServerLevel level,RoadIndex.Built expected,List<RoadStructures.Part> removed){
    if(index.roads.get(expected.record.id())!=expected)return null;
    if(removed.isEmpty())return Set.of();
    var removedSet=new HashSet<>(removed);
    var next=expected.record.structures(expected.record.structures().stream().filter(p->!removedSet.contains(p)).toList());
    index.put(RoadIndex.Built.loading(next));setDirty();RoadNetwork.broadcastRoad(level,next);
    var chunks=new HashSet<Long>();
    for(var part:removed){double pad=part.halfExtent();
      for(int x=(int)Math.floor(Math.min(part.a().x(),part.b().x())-pad)>>4;x<=(int)Math.floor(Math.max(part.a().x(),part.b().x())+pad)>>4;x++)
        for(int z=(int)Math.floor(Math.min(part.a().z(),part.b().z())-pad)>>4;z<=(int)Math.floor(Math.max(part.a().z(),part.b().z())+pad)>>4;z++){
          long key=net.minecraft.world.level.ChunkPos.asLong(x,z);chunks.add(key);retiredSignsByChunk.computeIfAbsent(key,k->new ArrayList<>()).add(part);
        }
    }
    return chunks;
  }

  /** Upgrade legacy flush decks as their chunks are watched; geometry stays unchanged. */
  private void releaseRetiredCell(ServerLevel level,BlockPos pos,BlockState state){
    long key=pos.asLong();var walkway=sidewalkPlaced.get(key);
    // Pavement still owns its original terrain even when its last collider leaves.
    if(walkway!=null){if(RoadBlocks.isCollider(state))checkedSetBlock(level,pos,walkway,3);terrainFill.remove(key);return;}
    if(RoadBlocks.isCollider(state)){
      var restored=terrainOriginal.getOrDefault(key,terrainFill.getOrDefault(key,state.getValue(RoadBlocks.Road.FILL).state()));
      checkedSetBlock(level,pos,restored,3);
    }
    // A player replacement is retained. Only the stale ownership snapshots are consumed.
    terrainFill.remove(key);terrainOriginal.remove(key);
  }

  public void repairChunk(ServerLevel level, net.minecraft.world.level.ChunkPos chunk) {
    // Bridge geometry repairs run through explicit road edits, never while a chunk is watched.
    var revised=structureRepair103.remove(chunk.toLong());
    if(revised!=null){
      var nearby=index.inChunk(chunk.toLong()).stream().map(index.roads::get).filter(Objects::nonNull).toList();
      for(var cell:RoadRaster.structures(revised,null).keySet()){
        if(cell.x()>>4!=chunk.x||cell.z()>>4!=chunk.z)continue;
        var p=new BlockPos(cell.x(),cell.y(),cell.z());long key=p.asLong();var state=level.getBlockState(p);
        var owners=nearby.stream().filter(r->!r.boxes(p).isEmpty()).toList();
        if(owners.isEmpty()){
          releaseRetiredCell(level,p,state);
        }else if(TunnelAir.isAirSpace(state)||RoadBlocks.isCollider(state)){level.setBlock(p,collisionState(key,state,owners),3);if(level.getBlockEntity(p) instanceof RoadFillEntity fill)fill.fill(terrainFill.getOrDefault(key,Blocks.AIR.defaultBlockState()));}
      }
      setDirty();
    }
    var apronCells=apronWidthRepair.remove(chunk.toLong());
    if(apronCells!=null){
      var ids=new HashSet<UUID>();for(int x=chunk.x-1;x<=chunk.x+1;x++)for(int z=chunk.z-1;z<=chunk.z+1;z++)ids.addAll(index.inChunk(net.minecraft.world.level.ChunkPos.asLong(x,z)));
      var nearby=ids.stream().map(index.roads::get).filter(Objects::nonNull).toList();
      for(long key:apronCells){if(!apronPlaced.contains(key))continue;var p=BlockPos.of(key);
        boolean keep=false;for(var road:nearby){if(!RoadAprons.eligible(road.mesh))continue;var q=RoadQueries.horizontal(road.mesh,new V(p.getX()+.5,p.getY(),p.getZ()+.5));if(Math.abs(q.lateral())<=q.sample().halfWidth()+1+1e-7&&Math.abs(q.sample().center().y()-(p.getY()+1))<2.01){keep=true;break;}}
        if(keep)continue;
        var expected=sidewalkPlaced.get(key);var state=level.getBlockState(p);var fillEntity=level.getBlockEntity(p);
        boolean owned=state.equals(expected)||state.is(SplineRoads.FILLED_COLLIDER.get())&&fillEntity instanceof RoadFillEntity f&&f.fill().equals(expected);
        apronPlaced.remove(key);sidewalkPlaced.remove(key);
        var owners=nearby.stream().filter(b->!b.boxes(p).isEmpty()).toList();
        // A replaced apron no longer owns its old restoration history. Keep the
        // player's block, but do not let a later road resurrect pre-migration soil.
        // A surviving collider owner still needs its original terrain snapshot.
        if(!owned){if(owners.isEmpty()){terrainFill.remove(key);terrainOriginal.remove(key);}continue;}
        var original=terrainOriginal.getOrDefault(key,Blocks.AIR.defaultBlockState());
        if(owners.isEmpty()){terrainFill.remove(key);terrainOriginal.remove(key);level.setBlock(p,original,3);}
        else {terrainFill.put(key,original);level.setBlock(p,collisionState(key,state,owners),3);if(level.getBlockEntity(p) instanceof RoadFillEntity f)f.fill(original,GroundTerrainJoin.cap(p,original,nearby));}
      }setDirty();
    }
    var islands=islandRepair.remove(chunk.toLong());
    if(islands!=null){
      var nearby=index.inChunk(chunk.toLong()).stream().map(index.roads::get).filter(Objects::nonNull).toList();
      for(UUID id:islands){var road=index.roads.get(id);if(road==null||road.record.junction()==null)continue;var spec=road.record.junction().spec();
        for(var cell:road.prepareCollision().cellsInChunk(chunk.x,chunk.z)){
          if(new V(cell.x()+.5,spec.center().y(),cell.z()+.5).sub(spec.center()).horizontalLength()>spec.islandRadius()+.75||cell.y()<Math.floor(spec.center().y())||cell.y()>Math.floor(spec.center().y()+.75))continue;
          var p=new BlockPos(cell.x(),cell.y(),cell.z());var previous=level.getBlockState(p);if(!TunnelAir.isAirSpace(previous)&&!RoadBlocks.isCollider(previous))continue;
          var owners=nearby.stream().filter(b->!b.boxes(p).isEmpty()).toList();if(owners.isEmpty())continue;
          level.setBlock(p,collisionState(p.asLong(),previous,owners),3);
        }
      }setDirty();
    }
    var groundCells=groundJoinRepair.remove(chunk.toLong());
    if(groundCells!=null){
      var nearby=index.inChunk(chunk.toLong()).stream().map(index.roads::get).filter(Objects::nonNull).toList();
      for(long key:groundCells){var p=BlockPos.of(key);var state=level.getBlockState(p);if(!RoadBlocks.isCollider(state)||state.is(SplineRoads.FILLED_COLLIDER.get())||state.getValue(RoadBlocks.Road.FILL)!=RoadBlocks.Fill.NONE)continue;
        var original=terrainOriginal.getOrDefault(key,Blocks.AIR.defaultBlockState());double cap=GroundTerrainJoin.cap(p,original,nearby);if(cap>=1)continue;
        var owners=nearby.stream().filter(b->!b.boxes(p).isEmpty()).toList();if(owners.isEmpty())continue;var next=collisionState(key,state,owners);
        if(!next.is(SplineRoads.FILLED_COLLIDER.get()))continue;
        terrainFill.put(key,original);level.setBlock(p,next,3);if(level.getBlockEntity(p) instanceof RoadFillEntity filled)filled.fill(original,cap);
      }setDirty();
    }
    var retired=retiredSignsByChunk.remove(chunk.toLong());
    if(retired!=null){
      for(var cell:RoadRaster.structures(retired,null).keySet()){
        if(cell.x()>>4!=chunk.x||cell.z()>>4!=chunk.z)continue;
        var p=new BlockPos(cell.x(),cell.y(),cell.z());long key=p.asLong();var state=level.getBlockState(p);
        var owners=index.inChunk(chunk.toLong()).stream().map(index.roads::get).filter(r->!r.boxes(p).isEmpty()).toList();
        if(owners.isEmpty())releaseRetiredCell(level,p,state);
        else if(RoadBlocks.isCollider(state)){level.setBlock(p,collisionState(key,state,owners),3);if(level.getBlockEntity(p) instanceof RoadFillEntity fill)fill.fill(terrainFill.getOrDefault(key,Blocks.AIR.defaultBlockState()));}
      }setDirty();
    }
    // Walking is not an edit transaction. Preserve saved geometry, load no remote chunks,
    // and leave geometry upgrades to an explicit editor save.
    for (UUID id : List.copyOf(index.inChunk(chunk.toLong()))) {
      var built = index.roads.get(id);
      // Current transactions persist colliders with their road. Only legacy saves need this scan.
      // Re-scanning modern roads on every chunk watch would eagerly rasterize an entire city.
      if (built == null || built.record.buildVersion() >= 17) continue;
      var done = repairedChunks.computeIfAbsent(chunk.toLong(), ignored -> new HashSet<>());
      if (done.contains(id)) continue;
      for (var cell : built.prepareCollision().cellsInChunk(chunk.x, chunk.z)) {
        BlockPos p = new BlockPos(cell.x(),cell.y(),cell.z());
        long key = p.asLong();
        BlockState state = level.getBlockState(p);
        if (state.is(SplineRoads.NODE.get())
            || RoadBlocks.isCollider(state)
            || state.getDestroySpeed(level, p) < 0 || state.hasBlockEntity()) continue;
        var owners = index.at(key).stream().map(index.roads::get).toList();
        level.setBlock(p, collisionState(key, state, owners), 2);
        setDirty();
      }
      done.add(id);
    }
  }
}
