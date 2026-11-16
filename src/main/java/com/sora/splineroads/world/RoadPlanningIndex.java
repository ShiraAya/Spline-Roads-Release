package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadBoundsIndex;
import com.sora.splineroads.core.RoadGeometry.Mesh;
import com.sora.splineroads.core.RoadPlanningBudget;
import com.sora.splineroads.core.RoadStructures;
import java.util.*;

/** One mutable planning snapshot for an edit transaction, never retained by the world.
 * Replacing a planned road updates geometry AND structures seen by subsequent roads. */
final class RoadPlanningIndex {
  private final List<RoadIndex.Built> roads;
  private final RoadBoundsIndex bounds;
  private Map<UUID,Set<Integer>> optionalAutomaticAnchors=Map.of();
  private Map<UUID,Map<Integer,RoadGantryRetention.Snapshot>> preservedGantries=Map.of();
  private record Previous(RoadRecord record,Mesh mesh){}
  private Map<UUID,Previous> dependencies=Map.of();
  private Map<UUID,RoadIndex.Built> previousRoads=Map.of();
  private Set<UUID> newRoads=Set.of(),directlyEditedRoads=Set.of();
  /** Only immutable geometry and fixtures cross the history boundary, never a live Built. */
  record CycleNeighbor(Mesh mesh,List<RoadStructures.Part> structures){
    CycleNeighbor{mesh=immutableMesh(mesh);structures=List.copyOf(structures);}
  }
  private Map<UUID,List<CycleNeighbor>> cycleNeighbors=Map.of();
  RoadPlanningIndex(List<RoadIndex.Built> roads){this(roads,1);}
  RoadPlanningIndex(List<RoadIndex.Built> roads,int plannedRoads){
    this.roads=new ArrayList<>(roads);
    // Building a world-sized index for ONE edited road costs more than two simple scans.
    // Reserve spatial setup for substantial batches; small edits keep the original path.
    bounds=plannedRoads>=256&&roads.size()>64?new RoadBoundsIndex(roads.stream().map(r->box(r.mesh)).toList()):null;
  }
  boolean spatial(){return bounds!=null;}
  private final Map<UUID,RoadCycleChain> cycleChains=new HashMap<>();
  private final Map<UUID,List<com.sora.splineroads.core.RoadsideBypass.Span>> cyclePlans=new HashMap<>();
  void resetCyclePlans(){cycleChains.clear();cyclePlans.clear();}
  RoadCycleChain cycleChain(UUID road){
    if(cycleChains.containsKey(road))return cycleChains.get(road);
    var records=new LinkedHashMap<UUID,RoadRecord>();for(var r:roads)records.put(r.record.id(),r.record);
    var chain=RoadCycleChain.of(records,road);if(chain!=null)for(var leg:chain.legs())cycleChains.put(leg.road().id(),chain);
    return chain;
  }
  List<com.sora.splineroads.core.RoadsideBypass.Span> cyclePlan(RoadCycleChain chain,java.util.function.Supplier<List<com.sora.splineroads.core.RoadsideBypass.Span>> build){
    return cyclePlans.computeIfAbsent(chain.legs().get(0).road().id(),key->List.copyOf(build.get()));
  }
  void editHistory(List<RoadIndex.Built> candidates,Map<UUID,RoadIndex.Built> saved,Set<UUID> explicit){
    previousRoads=Map.copyOf(saved);
    var previous=new HashMap<UUID,Previous>();
    var previousNeighbors=new HashMap<UUID,List<CycleNeighbor>>();
    var frozenNeighbors=new HashMap<UUID,CycleNeighbor>();
    var added=new HashSet<UUID>();var direct=new HashSet<UUID>();
    for(var candidate:candidates){
      RoadPlanningBudget.check();
      var id=candidate.record.id();var before=saved.get(id);
      if(before==null)added.add(id);
      if(explicit.contains(id))direct.add(id);
      if(before==null||explicit.contains(id))continue;
      previous.put(id,new Previous(before.record,before.mesh));
      if(before.record.settings().options().streetscape().crossings().isEmpty())continue;
      double margin=3+(before.record.settings().options().sidewalk().enabled()?before.record.settings().options().sidewalk().width():0);
      var neighbors=new ArrayList<CycleNeighbor>();
      for(var neighbor:saved.values()){
        RoadPlanningBudget.check();
        var mesh=neighbor.mesh;var neighborId=neighbor.record.id();
        if(neighborId.equals(id)||!RoadIndex.overlapXZ(mesh,before.mesh,margin)
            ||!mesh.settings().style().ramp()&&mesh.settings().options().lanePoints().link()==null)continue;
        neighbors.add(frozenNeighbors.computeIfAbsent(neighborId,key->new CycleNeighbor(mesh,neighbor.record.structures())));
      }
      previousNeighbors.put(id,List.copyOf(neighbors));
    }
    dependencies=Map.copyOf(previous);
    cycleNeighbors=Map.copyOf(previousNeighbors);
    newRoads=Set.copyOf(added);directlyEditedRoads=Set.copyOf(direct);
  }
  RoadRecord unchangedDependency(RoadIndex.Built candidate){
    var previous=dependencies.get(candidate.record.id());
    if(previous==null)return null;
    if(previous.record().structures(List.of()).equals(candidate.record.structures(List.of()))&&previous.mesh().equals(candidate.mesh))return previous.record();
    // normalizeTransitions copies derived streetscape data into an endpoint
    // Section. That can change record equality without changing a single metre
    // of the saved bicycle surface. Explicit edits never enter dependencies;
    // this narrower physical receipt is used only by the crossing planner.
    var spans=previous.record().settings().options().streetscape().crossings();
    return com.sora.splineroads.core.RoadsideBypass.sameSavedCrossingSurface(previous.mesh(),candidate.mesh,spans)?previous.record():null;
  }
  /** An incidental rebuild must not turn an unchanged remote, saved overlap into
   * a new edit failure. Any changed road or new fixture near this pier revokes the proof. */
  boolean unchangedFixedSupport(RoadIndex.Built candidate,RoadStructures.Part part){
    var savedOwner=previousRoads.get(candidate.record.id());
    if(savedOwner==null||!savedOwner.record.structures().contains(part))return false;
    double margin=part.halfExtent()+6;
    var area=new RoadBoundsIndex.Bounds(Math.min(part.a().x(),part.b().x())-margin,Math.min(part.a().z(),part.b().z())-margin,
        Math.max(part.a().x(),part.b().x())+margin,Math.max(part.a().z(),part.b().z())+margin);
    // Auto-junction rebuilding can enlist a whole split road as an explicit
    // candidate even when only its remote end changes. Prove the physical local
    // surface instead of treating transaction membership as a changed pier.
    if(!localDeck(savedOwner.mesh,area).equals(localDeck(candidate.mesh,area))
        ||savedOwner.mesh.settings().thickness()!=candidate.mesh.settings().thickness())return false;
    for(var road:roads){
      if(road.record.id().equals(candidate.record.id()))continue;
      var old=previousRoads.get(road.record.id());
      boolean nearby=road.mesh.max().x()>=area.minX()&&road.mesh.min().x()<=area.maxX()&&road.mesh.max().z()>=area.minZ()&&road.mesh.min().z()<=area.maxZ();
      if(nearby&&!localDeck(road.mesh,area).isEmpty()&&(old==null||!localDeck(old.mesh,area).equals(localDeck(road.mesh,area))||old.mesh.settings().thickness()!=road.mesh.settings().thickness()
          ||com.sora.splineroads.core.RoadStructureClearance.headroom(old.mesh)!=com.sora.splineroads.core.RoadStructureClearance.headroom(road.mesh)))return false;
      // A beam can reach this pier from a road whose pavement is farther away.
      for(var fixture:road.record.structures())if(old==null||!old.record.structures().contains(fixture)){
        double pad=fixture.halfExtent();
        if(Math.max(fixture.a().x(),fixture.b().x())+pad>=area.minX()&&Math.min(fixture.a().x(),fixture.b().x())-pad<=area.maxX()
            &&Math.max(fixture.a().z(),fixture.b().z())+pad>=area.minZ()&&Math.min(fixture.a().z(),fixture.b().z())-pad<=area.maxZ())return false;
      }
    }
    return true;
  }
  private static List<com.sora.splineroads.core.LaneDeck.Strip> localDeck(Mesh mesh,RoadBoundsIndex.Bounds area){
    var result=new ArrayList<com.sora.splineroads.core.LaneDeck.Strip>();
    for(int i=1;i<mesh.samples().size();i++){
      var a=mesh.samples().get(i-1);var b=mesh.samples().get(i);double pad=Math.max(a.halfWidth(),b.halfWidth());
      if(Math.max(a.center().x(),b.center().x())+pad<area.minX()||Math.min(a.center().x(),b.center().x())-pad>area.maxX()
          ||Math.max(a.center().z(),b.center().z())+pad<area.minZ()||Math.min(a.center().z(),b.center().z())-pad>area.maxZ())continue;
      result.addAll(com.sora.splineroads.core.LaneDeck.strips(mesh,a,b));
    }
    return result;
  }
  /** Failure-only diagnostics. This describes the saved surface proof, not the
   * separate obstacle comparison performed by the crossing planner. */
  String crossingHistoryReason(RoadIndex.Built candidate){
    UUID id=candidate.record.id();
    if(newRoads.contains(id))return "new-road";
    if(directlyEditedRoads.contains(id))return "directly-edited";
    if(!dependencies.containsKey(id))return "no-history";
    return unchangedDependency(candidate)==null?"surface-changed":"unchanged-surface";
  }
  /** Null means no unchanged-history proof; an empty list is a proven empty old neighborhood. */
  List<CycleNeighbor> previousCycleNeighbors(RoadIndex.Built candidate){
    return unchangedDependency(candidate)==null?null:cycleNeighbors.get(candidate.record.id());
  }
  private static Mesh immutableMesh(Mesh mesh){
    return mesh==null?null:new Mesh(List.copyOf(mesh.samples()),mesh.settings(),mesh.min(),mesh.max(),mesh.length(),
        mesh.closed(),mesh.controlPoint(),List.copyOf(mesh.controls()),immutableMesh(mesh.reference()),mesh.slicing());
  }
  void optionalAutomaticAnchors(Map<UUID,Set<Integer>> values){
    var copy=new HashMap<UUID,Set<Integer>>();values.forEach((id,slots)->copy.put(id,Set.copyOf(slots)));
    optionalAutomaticAnchors=Map.copyOf(copy);
  }
  boolean requiredGantry(UUID road,int slot){return !optionalAutomaticAnchors.getOrDefault(road,Set.of()).contains(slot);}
  void preservedGantries(Map<UUID,Map<Integer,RoadGantryRetention.Snapshot>> values){preservedGantries=values;}
  List<com.sora.splineroads.core.RoadStructures.Part> preservedGantry(UUID road,com.sora.splineroads.core.RoadGantry.Station station){
    var saved=preservedGantries.getOrDefault(road,Map.of()).get(station.slot());return saved==null?null:saved.at(station);
  }
  private static RoadBoundsIndex.Bounds box(Mesh m){return new RoadBoundsIndex.Bounds(m.min().x(),m.min().z(),m.max().x(),m.max().z());}
  void replace(int slot,RoadIndex.Built next){if(bounds!=null)bounds.replace(slot,box(next.mesh));roads.set(slot,next);}
  List<RoadIndex.Built> near(Mesh mesh,double margin,UUID exclude){
    if(bounds==null)return roads.stream().filter(r->!r.record.id().equals(exclude)&&RoadIndex.overlapXZ(r.mesh,mesh,margin)).toList();
    var result=new ArrayList<RoadIndex.Built>();for(int slot:bounds.query(box(mesh),margin)){var r=roads.get(slot);if(!r.record.id().equals(exclude))result.add(r);}return result;
  }
  List<RoadIndex.Built> nearAny(List<Mesh> meshes,double margin,UUID exclude){
    if(bounds==null)return roads.stream().filter(r->!r.record.id().equals(exclude)&&meshes.stream().anyMatch(m->RoadIndex.overlapXZ(r.mesh,m,margin))).toList();
    var slots=new TreeSet<Integer>();for(var mesh:meshes)slots.addAll(bounds.query(box(mesh),margin));
    var result=new ArrayList<RoadIndex.Built>();for(int slot:slots){var r=roads.get(slot);if(!r.record.id().equals(exclude))result.add(r);}return result;
  }
}
