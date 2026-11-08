package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

final class StructurePlanner {
  enum Stage { FULL, FIXED, GANTRIES, PAVING }
  static RoadRecord plan(
      ServerLevel level,
      RoadIndex.Built built,
      List<RoadIndex.Built> all,
      Map<Long, BlockState> originals,
      Map<BlockPos, List<AABB>> terrainCache) {
    return plan(level,built,all,originals,terrainCache,new RoadPlanningIndex(all));
  }

  static RoadRecord plan(ServerLevel level,RoadIndex.Built built,List<RoadIndex.Built> all,
      Map<Long,BlockState> originals,Map<BlockPos,List<AABB>> terrainCache,RoadPlanningIndex lookup) {
    return plan(level,built,all,originals,Map.of(),terrainCache,lookup);
  }

  static RoadRecord plan(ServerLevel level,RoadIndex.Built built,List<RoadIndex.Built> all,
      Map<Long,BlockState> retained,Map<Long,BlockState> originals,
      Map<BlockPos,List<AABB>> terrainCache,RoadPlanningIndex lookup) {
    if(passiveFill(built))return built.record.structures(List.of());
    built=prepareCrossings(level,built,retained,originals,terrainCache,lookup);
    var nearby=lookup.near(built.mesh,3+(built.record.settings().options().sidewalk().enabled()?built.record.settings().options().sidewalk().width():0),built.record.id());
    return planGeometry(level,built,all,retained,originals,terrainCache,lookup,nearby,null,Stage.FULL);
  }
  static RoadRecord reserve(ServerLevel level,RoadIndex.Built built,List<RoadIndex.Built> all,
      Map<Long,BlockState> retained,Map<Long,BlockState> originals,
      Map<BlockPos,List<AABB>> terrainCache,RoadPlanningIndex lookup,Stage stage){
    if(passiveFill(built))return built.record.structures(List.of());
    if(stage==Stage.FIXED&&(built.mesh.settings().structure()==Structure.TUNNEL
        ||built.record.fragment()==null&&built.record.junction()==null&&built.record.toll()==null&&!RoadInfrastructure.customBridge(built.mesh)))
      return built.record.structures(List.of());
    if(stage==Stage.GANTRIES&&(built.mesh.settings().structure()==Structure.TUNNEL||built.record.junction()!=null||built.record.toll()!=null
        ||built.mesh.settings().options().infrastructure().gantryEdits().isEmpty()))return built.record;
    var nearby=lookup.near(built.mesh,3+(built.record.settings().options().sidewalk().enabled()?built.record.settings().options().sidewalk().width():0),built.record.id());
    return planGeometry(level,built,all,retained,originals,terrainCache,lookup,nearby,null,stage);
  }
  static RoadIndex.Built prepareCrossings(ServerLevel level,RoadIndex.Built built,
      Map<Long,BlockState> retained,Map<Long,BlockState> originals,
      Map<BlockPos,List<AABB>> terrainCache,RoadPlanningIndex lookup){
    try{return prepareCrossingGeometry(level,built,retained,originals,terrainCache,lookup);}
    catch(IllegalArgumentException failure){
      if(failure instanceof RoadCrossingFailure)throw failure;
      throw new RoadCrossingFailure(built.record.id(),built.mesh,lookup.crossingHistoryReason(built),failure);
    }
  }
  /** Interior fill triangles own pavement only. The first pad owns its walls,
   * walks and supports; approach pieces own signals and signs. */
  private static boolean passiveFill(RoadIndex.Built built){
    var ref=built.record.junction();if(ref==null||ref.spec().kind()!=JunctionSpec.Kind.INTERSECTION||ref.piece()<=ref.spec().arms().size()||ref.get().arm()>=0||!ref.get().structures().isEmpty()||built.mesh.settings().structure()!=Structure.GROUND)return false;
    var decor=built.mesh.settings().options().streetscape();var sides=decor.sides();
    return decor.tunnels().isEmpty()&&decor.crossings().isEmpty()&&sides.negativeRaised().isEmpty()&&sides.positiveRaised().isEmpty()&&sides.negativeCut().isEmpty()&&sides.positiveCut().isEmpty();
  }
  private static RoadIndex.Built prepareCrossingGeometry(ServerLevel level,RoadIndex.Built built,
      Map<Long,BlockState> retained,Map<Long,BlockState> originals,
      Map<BlockPos,List<AABB>> terrainCache,RoadPlanningIndex lookup){
    var nearby=lookup.near(built.mesh,3+(built.record.settings().options().sidewalk().enabled()?built.record.settings().options().sidewalk().width():0),built.record.id());
    var terrain=RoadTerrain.read(level,retained,originals,terrainCache);
    if(RoadProfile.catalog(built.mesh.settings().style()).type()==RoadProfile.Type.ORDINARY&&built.record.junction()==null){
      var raw=built.record.withLevelCrossings(List.of());var authored=RoadStreetscape.resolve(raw.mesh(),terrain);
      var railMeshes=nearby.stream().filter(r->r.record.railway()).map(r->RoadStreetscape.resolve(r.mesh,terrain)).toList();
      built=built.planned(raw.withLevelCrossings(RailwayCrossings.surfaces(authored,railMeshes)));
    }

    var roofCache=terrainCache instanceof RoadTerrain.Cache cache?cache.cycleRoofs:new IdentityHashMap<Mesh,List<RoadAutoTunnels.RoofRegion>>();
    boolean cycle=RoadsideBypass.eligible(built.mesh);
    var chain=cycle?lookup.cycleChain(built.record.id()):null;
    boolean continuous=chain!=null&&chain.legs().size()>1;
    var neighbors=new ArrayList<RoadsideBypass.Neighbor>();
    var cycleNearby=continuous?lookup.near(chain.mesh(),3,built.record.id()):nearby;
    if(cycle)for(var r:cycleNearby)neighbors.add(cycleNeighbor(r.mesh,r.record.structures(),terrain,roofCache));
    var unchanged=lookup.unchangedDependency(built);
    List<RoadsideBypass.Neighbor> previousNeighbors=null;
    if(unchanged!=null){
      var previous=lookup.previousCycleNeighbors(built);
      if(previous!=null){
        previousNeighbors=new ArrayList<>();
        for(var r:previous)previousNeighbors.add(cycleNeighbor(r.mesh(),r.structures(),terrain,roofCache));
      }
    }
    var crossings=continuous?chain.local(built.record.id(),lookup.cyclePlan(chain,()->RoadsideBypass.plan(chain.mesh(),neighbors))):
        RoadsideBypass.plan(built.mesh,neighbors,unchanged==null?null:
          unchanged.settings().options().streetscape().crossings(),previousNeighbors);
    if(continuous)RoadsideBypass.validateSection(built.mesh,crossings);
    var tunnelSpans=new ArrayList<RoadStreetscape.Span>();
    for(var section:RoadAutoTunnels.structuralSections(built.mesh,terrain))if(section.roof()){
      if(!tunnelSpans.isEmpty()&&Math.abs(tunnelSpans.get(tunnelSpans.size()-1).to()-section.from())<1e-7){var previous=tunnelSpans.remove(tunnelSpans.size()-1);tunnelSpans.add(new RoadStreetscape.Span(previous.from(),section.to()));}
      else tunnelSpans.add(new RoadStreetscape.Span(section.from(),section.to()));
    }
    var derived=built.record.derivedStreetscape(built.record.settings().options().streetscape().crossings(crossings).tunnels(tunnelSpans));
    if(derived!=built.record)built=built.planned(derived);
    RoadsideBypass.validateFloor(built.mesh,crossings,level.getMinBuildHeight());
    double missingDepth=0;BlockPos obstruction=null;
    for(var span:crossings)if(span.delta()<0)for(double d=Math.max(0,span.start());d<=Math.min(built.mesh.length(),span.end());d+=1){
      var at=RoadStructures.sample(built.mesh,d);
      for(double fraction:new double[]{.05,.5,.95}){
        double lateral=RoadsideBypass.inner(built.mesh,at,span.side())*(1-fraction)+RoadsideBypass.outer(built.mesh,at,span.side())*fraction;
        V bottom=at.at(span.side()*lateral,RoadsideBypass.SLAB-span.height(d));
        var pos=BlockPos.containing(bottom.x(),bottom.y()+1e-7,bottom.z());
        if(level.hasChunkAt(pos)){
          var state=RoadPlanInputs.state(level,pos);
          if(!RoadBlocks.isCollider(state)&&state.getDestroySpeed(level,pos)<0&&pos.getY()+1-bottom.y()>missingDepth){missingDepth=pos.getY()+1-bottom.y();obstruction=pos;}
        }
      }
    }
    if(obstruction!=null)throw new RoadCrossingFailure.GeometryRejection("非机动车下穿遇到基岩等不可破坏方块，位置 "+obstruction.toShortString()+"；本段相交道路需提高至少 "+String.format(java.util.Locale.ROOT,"%.2f",missingDepth)+" 格以保留地下空间");
    return built;
  }
  private static RoadsideBypass.Neighbor cycleNeighbor(Mesh mesh,List<RoadStructures.Part> structures,
      RoadStructures.Ground terrain,Map<Mesh,List<RoadAutoTunnels.RoofRegion>> roofCache){
    var roofs=RoadAutoTunnels.roofRegions(structures);
    if(roofs.isEmpty()&&mesh.settings().structure()==Structure.AUTO)
      roofs=roofCache.computeIfAbsent(mesh,m->RoadAutoTunnels.roofRegions(RoadAutoTunnels.enclose(m,terrain,List.of())));
    return new RoadsideBypass.Neighbor(mesh,roofs);
  }
  static List<RoadStructures.Part> gantry(ServerLevel level,RoadIndex.Built built,List<RoadIndex.Built> all,
      Map<Long,BlockState> retained,Map<Long,BlockState> originals,int slot){
    var lookup=new RoadPlanningIndex(all);
    var nearby=lookup.near(built.mesh,3+(built.record.settings().options().sidewalk().enabled()?built.record.settings().options().sidewalk().width():0),built.record.id());
    return planGeometry(level,built,all,retained,originals,new HashMap<>(),lookup,nearby,slot,Stage.FULL).structures();
  }
  private static RoadRecord planGeometry(ServerLevel level,RoadIndex.Built built,List<RoadIndex.Built> all,
      Map<Long,BlockState> retained,Map<Long,BlockState> originals,Map<BlockPos,List<AABB>> terrainCache,
      RoadPlanningIndex lookup,List<RoadIndex.Built> nearby,Integer gantrySlot,Stage stage){
    var planting=new HashMap<UUID,Boolean>();
    // Lane connectors and automatic interchange ramps share one paved boundary.
    // UUID/depth gives one owner when
    // the rail centrelines coincide; no capsule or distance-only opening can erase them.
    boolean laneEdges=built.mesh.settings().style().ramp()||nearby.stream().anyMatch(r->r.mesh.settings().style().ramp())||LaneTopology.metadata(built.record).link()!=null||nearby.stream().anyMatch(r->LaneTopology.metadata(r.record).link()!=null);
    var railJoin=laneEdges?new RoadRailJoin(built.mesh,nearby.stream().map(r->new RoadRailJoin.Neighbor(r.mesh,
        RoadSurface.higherPriority(r.record.id(),r.mesh,built.record.id(),built.mesh))).toList()):null;
    var capMouths=RoadRailJoin.mouths(nearby.stream().filter(r->LaneMerge.linkedTo(built.mesh,r.mesh)||LaneMerge.linkedTo(r.mesh,built.mesh)||LaneTopology.metadata(built.record).cuts().stream().anyMatch(c->c.connection().equals(r.record.id()))).map(r->r.mesh).toList());
    var approaches = built.record.assembly() == null ? List.<RoadSignals.Approach>of()
        : RoadSignals.approaches(built.mesh, nearby.stream()
            .filter(r -> built.record.assembly().equals(r.record.assembly()))
            .map(r -> r.mesh).toList());
    var supportMeshes=built.record.junction()==null?List.of(built.mesh):all.stream().filter(r->Objects.equals(r.record.assembly(),built.record.assembly())).map(r->r.mesh).toList();
    var obstacles=lookup.nearAny(supportMeshes,82,built.record.id());
    var motorDecks=new IdentityHashMap<Mesh,Mesh>();
    var roadClearance=new RoadStructureClearance();
    var cycleExcavation=new RoadSolidOverlap.Index(obstacles.stream().flatMap(r->RoadsideBypass.supportVoids(r.mesh).stream()).toList());
    // Road deck columns exclude smooth sidewalks outside the deck. Index their actual
    // slabs independently so lamp arms/posts cannot tunnel through an upper walkway.
    var sidewalkSolids=new RoadPavingSupport(obstacles.stream().flatMap(r->r.record.structures().stream())
        .filter(p->p.material().name().startsWith("WALK_")).toList());
    // Piers may stand outside their owning road's deck bounds and record endpoints
    // are not reliable geometric identity. Check their actual local shaft positions.
    var pierSpacing=new RoadPierSpacing(obstacles.stream().flatMap(r->r.record.structures().stream()).toList());
    var fixtures=new ArrayList<RoadStructures.Part>();
    obstacles.forEach(r->fixtures.addAll(r.record.structures()));
    // A local gantry edit sees the surviving own-road fixtures too. Its selected
    // old members were removed by the caller, including untagged legacy members.
    if(gantrySlot!=null)fixtures.addAll(built.record.structures());
    boolean hasGantry=RoadGantry.slots(built.mesh).stream().anyMatch(slot->RoadGantry.station(built.mesh,slot).kind()!=RoadInfrastructure.Gantry.OFF);
    var gantryFixtures=new ArrayList<RoadStructures.Part>();
    if(hasGantry){
      gantryFixtures.addAll(fixtures.stream().filter(p->p.pier()||RoadGantry.member(p)).toList());
      // Fixed bridge beams/arches/cables are obstacles too, even when not shafts.
      for(var other:obstacles)if(RoadInfrastructure.customBridge(other.mesh))gantryFixtures.addAll(other.record.structures());
    }
    var stripPlantings=built.mesh.settings().options().streetscape().plantings();
    var treeObstacles=new RoadSolidOverlap.Index(built.mesh.settings().options().streetscape().planting()!=RoadStreetscape.Planting.NONE||stripPlantings.medianTrees()!=RoadStreetscape.Planting.NONE||stripPlantings.separatorTrees()!=RoadStreetscape.Planting.NONE?fixtures:List.of());
    var gantryObstacles=new RoadSolidOverlap.Index(gantryFixtures);
    var gantrySolids=new RoadSolidOverlap.Index(fixtures.stream().filter(RoadGantry::member).toList());
    var boundaryFixtures=new ArrayList<RoadStructures.Part>();
    if(hasGantry){
      for(var other:obstacles)for(var part:other.record.structures())
        if(RoadGantryMounts.boundaryPart(other.mesh,part))boundaryFixtures.add(part);
      if(gantrySlot!=null)for(var part:built.record.structures())
        if(RoadGantryMounts.boundaryPart(built.mesh,part))boundaryFixtures.add(part);
    }
    var gantryBoundaries=new RoadSolidOverlap.Index(boundaryFixtures);
    var terrainReference=built.record.terrainClassificationMesh();
    var terrain=RoadTerrain.read(level,retained,originals,terrainCache);
    var link=LaneTopology.metadata(built.record).link();
    var stackMesh=RoadStreetscape.resolve(built.mesh,terrain);
    var stackNeighbors=nearby.stream().filter(r->r.record.junction()==null&&r.record.toll()==null&&!r.record.legacyRailway())
        .map(r->RoadStreetscape.resolve(r.mesh,terrain)).toList();
    boolean sharedDeck=built.record.junction()==null&&built.record.toll()==null&&stackNeighbors.stream().anyMatch(m->MultiLevelBridges.compatible(stackMesh,m));
    var lowerDecks=sharedDeck?List.<Mesh>of():stackNeighbors.stream().filter(m->MultiLevelBridges.compatible(m,stackMesh)).toList();
    if(gantrySlot==null&&link!=null)try{LaneRampTerrain.validate(built.mesh,link.options(),terrain);}
      catch(IllegalArgumentException e){throw new LaneRamps.CandidateRejected(e.getMessage());}
    var signNeighbors=new ArrayList<>(lookup.near(built.mesh,100,built.record.id()));signNeighbors.add(built);
    var signSight=gantrySlot==null?JunctionSignRoads.lampClearance(signNeighbors):(java.util.function.Predicate<V>)(p->false);
    final RoadRecord builtRecord=built.record;
    var missingSupports=new ArrayList<String>();
    // The lamp's under-deck bracket intentionally joins the slab. Only the
    // above-deck superstructure (arches, hangers and towers) excludes a lamp.
    var ownBridge=new RoadSolidOverlap.Index(RoadInfrastructure.fixedBridgeParts(built.mesh,terrain).stream()
        .filter(p->p.top().stream().anyMatch(v->v.y()>RoadQueries.horizontal(built.mesh,v).sample().center().y()+.05)).toList());
    var railwayAir=RailbedStructures.catenaryClearance(built.record.settings().options().railbed().paths());
    var ground = new RoadStructures.Ground() {
              public boolean sharedDeck(){return sharedDeck;}
              public List<RoadStructures.Part> sharedSupports(Mesh mesh,Sample at,double depth,double width,double offset){
                return MultiLevelBridges.supports(mesh,lowerDecks,at,mesh.settings().thickness(),depth,width,offset,this);
              }
              public List<RoadStructures.Part> fixedSupports(){return builtRecord.fragment()==null?null:builtRecord.fragment().supports();}
              public boolean requiredGantry(RoadGantry.Station station){return lookup.requiredGantry(builtRecord.id(),station.slot());}
              public List<RoadStructures.Part> preservedGantry(RoadGantry.Station station){return lookup.preservedGantry(builtRecord.id(),station);}
              public void missingSupport(double station,String reason){missingSupports.add(String.format(Locale.ROOT,"%.2f: %s",station,reason));}
              public boolean lampClear(V p){return signSight.test(p);}
              public Mesh terrainReference(Mesh mesh){return terrainReference;}
              public boolean closedLanePlanting(UUID connection){
                return planting.computeIfAbsent(connection,id->nearby.stream().filter(r->r.record.id().equals(id)).noneMatch(r->buried(r.mesh,this)));
              }
              public boolean furnitureClear(V point) {
                return LanePoints.opening(built.mesh,point)||RoadSignals.furnitureClear(point, approaches)||nearby.stream().anyMatch(r->RailwayCrossings.at(built.mesh,r.mesh,point,1)||RailwayCrossings.walkingAt(built.mesh,r.mesh,point,1));
              }
              public boolean wireContinues(boolean first){
                return nearby.stream().anyMatch(r->RailbedFurniture.continues(built.mesh,r.mesh,r.record.structures(),first));
              }
              public boolean marker(V point) {
                for (BlockPos p : List.of(built.record.a(), built.record.b()))
                  if (RoadPlanInputs.node(level,p) instanceof NodeEntity node
                      && node.node().position().distance(point) < 1.6) return true;
                return false;
              }

              public double top(double x,double z,double deckY){return terrain.top(x,z,deckY);}
              public double foundation(V center,double top,double width){
                double median=RoadSupports.medianFoundation(center,top,width,obstacles.stream().map(r->r.mesh).toList());
                return sidewalkSolids.foundation(center,top,width,median,()->terrain.foundation(center,top,width));
              }
              public double surface(double x,double z,double roadY){return terrain.surface(x,z,roadY);}

              public V railJoint(V p,V direction){return railJoin==null?null:railJoin.joint(p,direction);}
              public boolean railPost(V p){return railJoin==null||railJoin.ownsPost(p);}
              public V railJoint(V p,V direction,boolean highway,boolean raised){return railJoin==null?null:railJoin.joint(p,direction,highway,raised);}
              public boolean railPost(V p,boolean highway,boolean raised){return railJoin==null||railJoin.ownsPost(p,highway,raised);}
              public List<RoadRailJoin.Span> railSpans(V a,V b,V outside) {
                return railJoin==null?RoadStructures.Ground.super.railSpans(a,b,outside):railJoin.exposed(a,b,outside);
              }

              public List<RoadRailJoin.Span> railSpans(V a,V b,V outside,double inset){
                return railJoin==null?RoadStructures.Ground.super.railSpans(a,b,outside,inset):railJoin.exposed(a,b,outside,inset);
              }
              public List<RoadRailJoin.Span> capRailSpans(V a,V b,V outside){
                var result=new ArrayList<RoadRailJoin.Span>();
                for(var span:railSpans(a,b,outside))result.addAll(capMouths.exposedMouth(span.a(),span.b()));
                return result;
              }
              public boolean joined(V point) {
                if(LanePoints.opening(built.mesh,point))return true;
                for (var other : nearby)
                  if (RoadQueries.joins(built.mesh, other.mesh, point)||RailwayCrossings.at(built.mesh,other.mesh,point,.3)||RailwayCrossings.walkingAt(built.mesh,other.mesh,point,.3)) return true;
                return false;
              }

              public String blockedReason(RoadStructures.Part part){
                if(RoadStructureClearance.selfSupportBlocked(part,built.mesh))return "构件侵入本桥通行空间，"+RoadStructures.Ground.super.blockedReason(part);
                for(var other:obstacles)if(RoadClearance.structureInvades(part,other.mesh,RoadStructureClearance.headroom(other.mesh)))
                  return RoadStructures.Ground.super.blockedReason(part)+"，冲突道路 "+other.record.id();
                return RoadStructures.Ground.super.blockedReason(part);
              }
              public boolean blocked(RoadStructures.Part part) {
                return blocked(part,null,null,null);
              }
              public boolean plantBlocked(RoadStructures.Part part,Sample mount){return treeObstacles.intersects(part)||ownBridge.intersects(part)||blocked(part,null,null,mount);}
              public boolean lampBlocked(RoadStructures.Part part,Sample mount){return ownBridge.intersects(part)||blocked(part,null,null,mount);}
              public boolean gantryMountConflict(RoadStructures.Part part,RoadGantry.Station station){
                if(!RoadGantryMounts.foot(part)||gantryObstacles.intersects(part)||pierSpacing.tooClose(part)
                    ||part.material()==RoadStructures.Material.CONCRETE&&cycleExcavation.intersects(part)
                    ||sidewalkSolids.blocks(part))return false;
                boolean contact=gantryBoundaries.intersects(part);
                if(RoadGantryMounts.blockedByRoad(part,built.mesh,4.25)){
                  if(!RoadGantryMounts.vergeContact(part,built.mesh,station,4.25))return false;
                  contact=true;
                }
                for(var other:obstacles){
                  if(roadClearance.cycleBlocked(part,other.mesh))return false;
                  double clearance=RoadStructureClearance.headroom(other.mesh);
                  if(RoadGantryMounts.blockedByRoad(part,other.mesh,clearance)){
                    if(!RoadGantryMounts.vergeContact(part,other.mesh,station,clearance))return false;
                    contact=true;
                  }
                }
                return contact;
              }
              public boolean railBlocked(RoadStructures.Part part,V a,V b){return blocked(part,a,b,null);}
              public boolean unionRails(){return laneEdges;}
              private boolean blocked(RoadStructures.Part part,V railA,V railB,Sample lampMount){
                RoadPlanningBudget.check();
                if(RoadExternalClearance.blocked(part))return true;
                if(!RailbedFurniture.visualOnly(part)&&nearby.stream().anyMatch(r->java.util.stream.Stream.concat(part.base().stream(),java.util.stream.Stream.of(part.a().add(part.b()).mul(.5))).anyMatch(p->RailwayCrossings.walkingAt(built.mesh,r.mesh,p,.3))))return true;
                if(!RailbedFurniture.visualOnly(part)&&nearby.stream().anyMatch(r->RailwayCrossings.at(built.mesh,r.mesh,part.a().add(part.b()).mul(.5),.3)&&RoadInteractions.invades(part,r.mesh)))return true;
                if(RoadGantry.member(part)&&(gantryObstacles.intersects(part)||gantryBoundaries.intersects(part))
                    ||part.pier()&&!RoadGantry.member(part)&&gantrySolids.intersects(part))return true;
                if(pierSpacing.tooClose(part))return true;
                if(part.pier()&&part.material()==RoadStructures.Material.CONCRETE&&cycleExcavation.intersects(part))return true;
                if(!RoadSidewalks.smoothPart(part)&&sidewalkSolids.blocks(part))return true;
                if(builtRecord.settings().options().railbed().active()&&part.model().startsWith("sr:catenary")?railwayAir.intersects(part):lampMount==null?(RoadGantryMounts.foot(part)?RoadGantryMounts.blockedByRoad(part,built.mesh,4.25):RoadInteractions.selfSupportBlocked(part,built.mesh)):RoadStreetscape.lampOwnRoadBlocked(part,built.mesh,lampMount))return true;
                // Rails use the exact deck opening test above. The pier's vehicle-clearance
                // envelope would otherwise erase rails for several blocks around every seam.
                if (!laneEdges && !part.pier() && part.material() == RoadStructures.Material.DEFAULT)
                  return false;
                double w = part.halfExtent() + (part.pier() ? .5 : .05);
                AABB box =
                    new AABB(
                        Math.min(part.a().x(), part.b().x()) - w,
                        Math.min(part.a().y(), part.b().y())-part.verticalFrame(),
                        Math.min(part.a().z(), part.b().z()) - w,
                        Math.max(part.a().x(), part.b().x()) + w,
                        Math.max(part.a().y(), part.b().y()) + part.height()+part.verticalFrame(),
                        Math.max(part.a().z(), part.b().z()) + w);
                boolean precise=true;
                if(precise&&!part.pier()){
                  var base=part.base();
                  box=new AABB(base.stream().mapToDouble(V::x).min().orElseThrow(),base.stream().mapToDouble(V::y).min().orElseThrow(),base.stream().mapToDouble(V::z).min().orElseThrow(),
                      base.stream().mapToDouble(V::x).max().orElseThrow(),base.stream().mapToDouble(V::y).max().orElseThrow()+part.height(),base.stream().mapToDouble(V::z).max().orElseThrow());
                }
                for (var other : obstacles) {
                  if(railA!=null&&RoadRailJoin.sharedRail(other.mesh,railA,railB))continue;
                  if(box.maxX<other.mesh.min().x()-2||box.minX>other.mesh.max().x()+2||box.maxZ<other.mesh.min().z()-2||box.minZ>other.mesh.max().z()+2)continue;
                  if(roadClearance.cycleBlocked(part,other.mesh))return true;
                  if (box.maxY < other.mesh.min().y() - .05 || box.minY > other.mesh.max().y() + RoadStructureClearance.headroom(other.mesh))
                    continue;
                  if(RoadSidewalks.smoothPart(part)){
                    // Exact clipping after planning keeps the usable part of boundary tiles.
                    continue;
                  }
                  if(part.pier()&&part.material()==RoadStructures.Material.CONCRETE&&RoadSidewalks.blocksTactile(part,other.mesh))return true;
                  if(!part.pier()){
                    if(LaneClosureLandscape.paved(part)&&RoadClearance.belowSurface(part,other.mesh,.025))continue;
                    // The apron/edge slab of a real joining bridge may share the
                    // floor volume. This permits no material above the driving surface.
                    boolean joining=RoadInteractions.connected(built.record,other.record)||LaneMerge.linkedTo(built.mesh,other.mesh)||LaneMerge.linkedTo(other.mesh,built.mesh);
                    if(joining&&RoadClearance.joiningFloor(part,built.mesh,other.mesh))continue;
                    // Rails may occupy the outside shoulder beside a different
                    // deck height; motor traffic and physical median stay protected.
                    // Testing the full paved verge erased both sides of a raised
                    // junction although neither assembly entered a driving lane.
                    var protectedMesh=railA==null?other.mesh:motorDecks.computeIfAbsent(other.mesh,LaneDeck::motorOnly);
                    if(RoadClearance.structureInvades(part,protectedMesh,RoadStructureClearance.headroom(other.mesh)))return true;
                    continue;
                  }
                  // Use the actual shaft prism and the live deck bands, including
                  // framed/tilted supports, instead of the voxel-column envelope.
                  if(RoadStructureClearance.pierBlocked(part,other.mesh,nearby.stream().map(r->r.mesh).toList()))return true;
                }
                return false;
              }
            };
    if(stage==Stage.PAVING){
      var slabs=RoadSidewalks.parts(built.mesh,built.mesh.settings().options().sidewalk(),ground,built.record.structures());
      slabs=new RoadsideBypass(built.mesh,nearby.stream().map(r->r.mesh).toList()).apply(slabs);
      var grading=new ArrayList<>(slabs);nearby.forEach(r->grading.addAll(r.record.structures()));
      slabs=new TactileSurface(grading).gradePaving(slabs);
      var joins=new SidewalkJoins(nearby.stream().filter(r->Math.abs(r.mesh.first().center().y()-built.mesh.first().center().y())<4).map(r->r.mesh).toList(),nearby.stream().flatMap(r->r.record.structures().stream()).filter(RoadSidewalks::smoothPart).toList());
      var reserved=new ArrayList<>(built.record.structures());
      reserved.addAll(joins.clip(RoadInteractions.openPortal(built,slabs,nearby,all)).stream().filter(RoadSidewalks::smoothPart).toList());
      return built.record.structures(reserved);
    }
    if(stage==Stage.FIXED){
      var fixed=new ArrayList<RoadStructures.Part>();
      if(built.record.fragment()!=null)fixed.addAll(built.record.fragment().supports());
      if(built.record.junction()!=null)fixed.addAll(built.record.junction().get().structures());
      else if(built.record.toll()!=null)fixed.addAll(built.record.tollPlan().structures());
      else fixed.addAll(RoadInfrastructure.fixedBridgeParts(built.mesh,ground));
      return built.record.structures(fixed);
    }
    if(stage==Stage.GANTRIES){
      var reserved=new ArrayList<>(built.record.structures());
      if(built.record.junction()==null&&built.record.toll()==null){
        var mesh=RoadStreetscape.resolve(built.mesh,ground);
        reserved.addAll(RoadGantry.required(mesh,ground,reserved));
      }
      return built.record.structures(reserved);
    }
    // Editing one gantry must never search for or replace bridge foundations.
    if(gantrySlot!=null)return built.record.structures(RoadGantry.planStation(built.mesh,ground,gantrySlot));
    if(built.record.toll()!=null){
      for(double d=0;d<=built.mesh.length();d+=4){var sample=RoadStructures.sample(built.mesh,d);
        for(double lateral:new double[]{-.7,0,.7}){V p=sample.at(lateral*sample.halfWidth(),built.mesh.settings().thickness());double top=ground.top(p.x(),p.z(),p.y());
          if(!Double.isFinite(top)||p.y()-top>RoadStructures.MAX_DROP)throw new IllegalArgumentException("收费站下方缺少可达基础，请降低站区或先建设地基");
          if(built.record.toll().config().structure()==Structure.GROUND&&p.y()-top>1.25)throw new IllegalArgumentException("地面收费站下方有悬空区，请铺设地基或改用自动/高架结构");
        }
      }
      var parts=new ArrayList<>(built.record.tollPlan().structures());
      parts.addAll(RoadStructures.supports(built.mesh,ground,RoadFurniture.Phase.DEFAULT));
      return built.record.structures(parts);
    }
    if(built.record.junction()!=null){
      var ref=built.record.junction();if(OneSidedJunction.retainedPiece(ref))return built.record.structures(ref.get().structures());var parts=new ArrayList<>(ref.get().structures());
      if(ref.get().arm()>=0){
        var arm=ref.spec().arms().get(ref.get().arm());var setting=JunctionRoads.editable(arm);
        var source=ref.furnitureApproach(ref.get().arm());var proxy=RoadStreetscape.resolve(new Mesh(source.samples(),setting,source.min(),source.max(),source.length(),source.closed()),ground);
        if(ref.geometryVersion()>=47){
          parts.addAll(ref.approachParts(JunctionApproachFurniture.plan(ref.spec(),ref.get().arm(),proxy,ground)));
          if(proxy.settings().structure()==Structure.TUNNEL)parts.addAll(RoadInfrastructure.plan(proxy,ground));
          else {parts.addAll(ref.approachParts(RoadStructures.edgeSlabs(proxy,ground)));parts.addAll(ref.approachParts(RoadStructures.groundSkirts(proxy,ground)));}
          var enclosed=new ArrayList<>(RoadAutoTunnels.enclose(proxy,ground,parts,RoadInteractions.tunnelHeadroom(built,all)));parts.clear();parts.addAll(enclosed);
        }
        var decoratedGround=new RoadStructures.Ground(){
          public double top(double x,double z,double y){return ground.top(x,z,y);}
          public boolean joined(V p){return ground.joined(p);}
          public boolean lampClear(V p){return ground.lampClear(p);}
          public boolean blocked(RoadStructures.Part p){
            return ground.blocked(p)||signalBlocked(p);
          }
          public boolean lampBlocked(RoadStructures.Part p,Sample mount){
            return ground.lampBlocked(p,mount)||signalBlocked(p);
          }
          public boolean plantBlocked(RoadStructures.Part p,Sample mount){return ground.plantBlocked(p,mount)||signalBlocked(p);}
          private boolean signalBlocked(RoadStructures.Part p){
            for(var old:parts)if(old.material()==RoadStructures.Material.CB_BASE||old.material()==RoadStructures.Material.CB_POST||old.material()==RoadStructures.Material.CB_ARM){
              if(p.a().y()+p.height()<=old.a().y()||old.a().y()+old.height()<=p.a().y())continue;
              if(RoadSurface.area(p.base())-RoadSurface.subtract(p.base(),old.base()).stream().mapToDouble(RoadSurface::area).sum()>1e-6)return true;
            }return false;
          }
          public boolean furnitureClear(V p){double distance=RoadQueries.horizontal(proxy,p).sample().distance();double setback=arm.crossingSetback()+(arm.crosswalk()?arm.crossingWidth():0)+Math.max(arm.stopGap()+3,6);return distance>proxy.length()-setback||ground.furnitureClear(p);}
        };
        var amenities=RoadStreetscape.plan(proxy,decoratedGround,FurnitureSpacing.onMesh(built.record,proxy));
        parts.addAll(ref.approachParts(amenities));
      }
      if(ref.get().arm()>=0||ref.spec().kind()==JunctionSpec.Kind.ROUNDABOUT)
        parts.addAll(RoadStructures.supports(built.mesh,ground,FurnitureSpacing.onMesh(built.record,built.mesh)));
      else if(ref.piece()==ref.spec().arms().size()){
        if(ref.geometryVersion()>=47)parts.addAll(JunctionRetainingWalls.plan(ref.spec(),java.util.stream.IntStream.range(0,ref.spec().arms().size()).mapToObj(ref::approach).toList(),ref.boundary(),ground));
        // One owner for the entire central pad, not one column per fill triangle.
        var edge=ref.boundary();V center=ref.spec().center();
        double minX=edge.stream().mapToDouble(V::x).min().orElse(center.x()),maxX=edge.stream().mapToDouble(V::x).max().orElse(center.x());
        double minZ=edge.stream().mapToDouble(V::z).min().orElse(center.z()),maxZ=edge.stream().mapToDouble(V::z).max().orElse(center.z());
        for(double x=center.x()+Math.ceil((minX-center.x())/24)*24;x<=maxX;x+=24)for(double z=center.z()+Math.ceil((minZ-center.z())/24)*24;z<=maxZ;z+=24){
          V at=new V(x,center.y(),z);boolean inside=true;
          for(double dx:new double[]{-2,2})for(double dz:new double[]{-2,2})inside&=JunctionPaint.inside(edge,at.add(new V(dx,0,dz)));
          if(inside)parts.addAll(RoadSupports.clearStandard(new Sample(at,new V(1,0,0),0,2),ref.spec().thickness(),ground));
        }
      }
      var walks=all.stream().filter(r->Objects.equals(r.record.assembly(),built.record.assembly())&&r.record.junction()!=null)
          .flatMap(r->r.record.junction().get().structures().stream()).toList();
      JunctionSignalSupports.add(parts,supportMeshes,walks,ground,ref.spec().thickness());
      parts.addAll(JunctionSignRoads.supportParts(built,all,ground));
      JunctionSignRoads.reservePlanting(parts,built,all);
      // Final slip signals already avoid every live deck. Refuge-cycle masks
      // describe removed old lanes and must not cut the replacement lamp group.
      var finalSignals=ref.geometryVersion()>=55?ref.get().structures().stream()
          .filter(p->RoadPoleModel.support(p)||RoadJunctionSignalModel.supports(p)).toList():List.<RoadStructures.Part>of();
      if(ref.geometryVersion()>=48&&JunctionSlips.enabled(ref.spec())){
        if(ref.get().arm()<=-2)parts.addAll(RoadStructures.supports(built.mesh,ground,FurnitureSpacing.onMesh(built.record,built.mesh)));
        if(ref.get().arm()>=-1){var cleared=ref.geometryVersion()==48?JunctionSlips48.clear(parts,ref.slips()):JunctionSlips.clear(parts,ref.slips());parts.clear();parts.addAll(cleared);}
        if(ref.geometryVersion()>=49&&ref.get().arm()>=0){var cleared=JunctionSlips.clear(parts,ref.replacedCycleStrips());parts.clear();parts.addAll(cleared);}
        if(ref.geometryVersion()>=55){var cleared=JunctionSlips.clearRefugePlanting(parts,ref.refuges());parts.clear();parts.addAll(cleared);}
      }
      for(var signal:finalSignals)if(!parts.contains(signal))parts.add(signal);
      // Approach furniture and open-cut clipping happen after the static
      // junction plan. Close their surviving bar ends, including crosswalks.
      RoadStructures.terminalPosts(parts,ground);
      if(ref.spec().oneSide()!=null){var clipped=OneSidedJunction.generatedParts(ref.spec().oneSide(),parts);parts.clear();parts.addAll(clipped);}
      var config=RoadStreetscape.resolve(built.mesh,ground).settings().options().streetscape();
      return built.record.derivedStreetscape(config).structures(parts);
    }
    var parts=RoadStructures.plan(built.mesh,ground,FurnitureSpacing.onMesh(built.record,built.mesh));
    if(built.record.assembly()!=null){
      var siblings=all.stream().filter(r->built.record.assembly().equals(r.record.assembly())).toList();
      if(siblings.size()==3){
        var stem=siblings.stream().filter(r->RoadProfile.catalog(r.record.settings().style()).twoWay()&&!r.record.settings().options().ends().persistent()).findFirst().orElse(null);
        if(stem!=null){
          var out=siblings.stream().filter(r->r!=stem&&r.record.a().equals(stem.record.b())&&r.record.settings().options().ends().persistent()).findFirst().orElse(null);
          var in=siblings.stream().filter(r->r!=stem&&r.record.b().equals(stem.record.b())&&r.record.settings().options().ends().persistent()).findFirst().orElse(null);
          if(out!=null&&in!=null)parts=YJunctionWalks.apply(built.mesh,stem.mesh,out.mesh,in.mesh,parts);
        }
      }
    }
    var grading=new ArrayList<>(parts);nearby.forEach(r->grading.addAll(r.record.structures()));
    parts=RoadAutoTunnels.enclose(built.mesh,ground,parts,RoadInteractions.tunnelHeadroom(built,all));
    parts=new RoadsideBypass(built.mesh,nearby.stream().map(r->r.mesh).toList()).apply(parts);
    parts=new TactileSurface(grading).gradePaving(parts);
    var joins=new SidewalkJoins(nearby.stream().filter(r->Math.abs(r.mesh.first().center().y()-built.mesh.first().center().y())<4).map(r->r.mesh).toList(),nearby.stream().flatMap(r->r.record.structures().stream()).filter(RoadSidewalks::smoothPart).toList());
    var result = new ArrayList<>(joins.clip(RoadInteractions.openPortal(built,parts,nearby,all)));
    RoadStructures.terminalPosts(result,ground);
    TollRoads.alignApproachRails(built,nearby,result);
    result.addAll(RoadCrashCushion.plan(built.mesh,nearby.stream().map(r->r.mesh).toList(),result));
    var paving=new ArrayList<>(result);nearby.forEach(r->paving.addAll(r.record.structures()));
    result=new ArrayList<>(new TactileSurface(paving).conform(result));
    JunctionSignRoads.reservePlanting(result,built,all);
    ElevatedSigns.reserve(result,built.mesh);
    // A far-side signal can be across a wide pad, beyond the incoming ramp's
    // 3m neighbor query. Its bearing belongs to the same complete assembly.
    if(!approaches.isEmpty()){
      var signalRoads=new ArrayList<Mesh>();signalRoads.add(built.mesh);
      for(var other:all)if(!other.record.id().equals(built.record.id())
          &&Objects.equals(built.record.assembly(),other.record.assembly()))signalRoads.add(other.mesh);
      result.addAll(RoadSignals.structures(approaches,signalRoads,ground));
    }
    if(built.record.settings().options().railbed().active()){
      var spec=built.record.settings().options().railbed();result.removeIf(p->p.material()==RoadStructures.Material.LAMP);
      result.addAll(RailbedFurniture.plan(built.mesh,built.record.settings().structure(),result,spec.paths(),spec.fittings(),ground,FurnitureSpacing.onMesh(built.record,built.mesh)));
    }
    if(!missingSupports.isEmpty())System.getLogger("SplineRoads/supports").log(System.Logger.Level.INFO,"道路 "+built.record.id()+" 未布置单柱的站位与原因："+String.join("；",missingSupports));
    return built.record.derivedStreetscape(built.record.settings().options().streetscape().sideStructures(RoadStreetscape.classifySides(built.mesh,ground))).structures(result);
  }

  /** Read the same original terrain as tunnel planning. A road may be buried;
   * this only suppresses its reserved lane's planter and concrete lid. */
  static boolean buried(Mesh mesh,RoadStructures.Ground ground){
    if(mesh.settings().structure()==Structure.TUNNEL)return true;
    for(double d=0;d<mesh.length();d+=1){
      var sample=RoadStructures.sample(mesh,Math.min(mesh.length(),d+.5));int covered=0;
      for(double side:new double[]{-.7,0,.7}){
        V p=sample.at(side*sample.halfWidth(),0);double top=ground.top(p.x(),p.z(),p.y()+6);
        if(Double.isFinite(top)&&top>p.y()+.12)covered++;
      }
      if(covered>=2)return true;
    }return false;
  }
  private StructurePlanner() {}
}
