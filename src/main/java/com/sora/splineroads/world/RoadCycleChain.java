package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;

/** One continuous bicycle corridor. Branches and changed cross sections are ends. */
final class RoadCycleChain {
  record Leg(RoadRecord road,int sign,double offset){}
  private final List<Leg> legs;
  private final Mesh mesh;
  private RoadCycleChain(List<Leg> legs,Mesh mesh){this.legs=List.copyOf(legs);this.mesh=mesh;}
  List<Leg> legs(){return legs;}
  Mesh mesh(){return mesh;}
  /** Reject an unbuildable bicycle approach while another ramp route can still
   * be tried. World terrain/roof checks remain in the authoritative transaction. */
  static void checkRamp(Map<UUID,RoadRecord> all,UUID id,Mesh ramp){
    var checked=new HashSet<UUID>();
    for(var road:all.values())if(eligible(road)&&!checked.contains(road.id())&&RoadIndex.overlapXZ(road.rawMesh(),ramp,3)){
      var chain=of(all,road.id());var neighbors=new ArrayList<RoadsideBypass.Neighbor>();
      neighbors.add(new RoadsideBypass.Neighbor(ramp,List.of()));
      for(var other:all.values())if(!other.id().equals(id)&&(other.settings().style().ramp()||other.railway()||LaneTopology.metadata(other).link()!=null)
          &&RoadIndex.overlapXZ(chain.mesh(),other.mesh(),3))neighbors.add(new RoadsideBypass.Neighbor(other.mesh(),RoadAutoTunnels.roofRegions(other.structures())));
      List<RoadsideBypass.Span> spans;
      try{spans=RoadsideBypass.plan(chain.mesh(),neighbors);}
      catch(RoadsideBypass.ValidationException e){
        if(Boolean.getBoolean("sr.profile"))System.getLogger("SplineRoads/planner").log(System.Logger.Level.INFO,"SR cycle rejected road="+road.id()+" length="+chain.mesh().length()+" reason="+e.reason()+" from="+e.stationFrom()+" to="+e.stationTo()+" slope="+e.slope());
        throw e;
      }
      for(var leg:chain.legs()){checked.add(leg.road().id());RoadsideBypass.validateSection(leg.road().mesh(),chain.local(leg.road().id(),spans));}
    }
  }
  static boolean eligible(RoadRecord road){return road.assembly()==null&&road.junction()==null&&!road.railway()
      &&!road.rawMesh().closed()&&RoadsideBypass.eligible(road.rawMesh());}
  static RoadCycleChain of(Map<UUID,RoadRecord> all,UUID id){
    var root=all.get(id);if(root==null||!eligible(root))return null;
    var incident=new HashMap<BlockPos,List<RoadRecord>>();
    for(var road:all.values())if(road.assembly()==null&&road.junction()==null){
      incident.computeIfAbsent(road.a(),k->new ArrayList<>()).add(road);
      if(!road.a().equals(road.b()))incident.computeIfAbsent(road.b(),k->new ArrayList<>()).add(road);
    }
    var group=new LinkedHashMap<UUID,RoadRecord>();var queue=new ArrayDeque<RoadRecord>();queue.add(root);
    double length=0;int samples=0;
    while(!queue.isEmpty()){
      RoadPlanningBudget.check();var road=queue.remove();if(group.putIfAbsent(road.id(),road)!=null)continue;
      length+=road.rawMesh().length();samples+=road.rawMesh().samples().size();
      if(group.size()>64||length>RoadLimits.MAX_PATH_LENGTH||samples>RoadLimits.MAX_SAMPLES)return single(root);
      for(var node:List.of(road.a(),road.b())){
        var neighbors=incident.getOrDefault(node,List.of());if(neighbors.size()!=2)continue;
        for(var next:neighbors)if(!group.containsKey(next.id())&&compatible(road,next,node))queue.add(next);
      }
    }
    if(group.size()==1)return single(root);
    // A canonical seed makes all segments derive exactly the same half-metre
    // sampling phase, even when they are planned in a different UUID order.
    var seed=group.values().stream().min(Comparator.comparing(RoadRecord::id)).orElseThrow();
    var ordered=new ArrayList<Leg>();ordered.add(new Leg(seed,1,0));var used=new HashSet<UUID>();used.add(seed.id());
    for(int direction:new int[]{-1,1}){
      var current=new Leg(seed,1,0);
      for(int count=0;count<group.size();count++){
        var node=current.sign()*direction>0?current.road().b():current.road().a();RoadRecord next=null;
        for(var r:incident.getOrDefault(node,List.of()))if(group.containsKey(r.id())&&!used.contains(r.id())&&compatible(current.road(),r,node)){next=r;break;}
        if(next==null)break;int sign=(next.a().equals(node)?1:-1)*direction;
        var leg=new Leg(next,sign,0);if(direction<0)ordered.add(0,leg);else ordered.add(leg);used.add(next.id());current=leg;
      }
    }
    if(used.size()!=group.size())return single(root);
    var points=new ArrayList<Sample>();var result=new ArrayList<Leg>();double offset=0;
    for(var leg:ordered){var raw=clean(leg.road());result.add(new Leg(leg.road(),leg.sign(),offset));
      var list=new ArrayList<>(raw.samples());if(leg.sign()<0)Collections.reverse(list);
      for(var sample:list){double station=offset+(leg.sign()>0?sample.distance():raw.length()-sample.distance());
        if(!points.isEmpty()&&station-points.get(points.size()-1).distance()<1e-7)continue;
        points.add(new Sample(sample.center(),sample.left().mul(leg.sign()),station,sample.halfWidth()));
      }offset+=raw.length();
    }
    var settings=clean(ordered.get(0).road()).settings();
    // Crossing planes can be banked and insert nearly coincident stations.
    // These are already authored surfaces, not a new unbanked ramp ribbon.
    V min=new V(Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE),max=new V(-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE);
    for(var sample:points)for(int side:new int[]{-1,1}){var p=sample.at(side*sample.halfWidth(),0);
      min=new V(Math.min(min.x(),p.x()),Math.min(min.y(),p.y()-settings.thickness()),Math.min(min.z(),p.z()));
      max=new V(Math.max(max.x(),p.x()),Math.max(max.y(),p.y()),Math.max(max.z(),p.z()));}
    return new RoadCycleChain(result,new Mesh(List.copyOf(points),settings,min,max,offset,false,null));
  }
  private static RoadCycleChain single(RoadRecord root){return new RoadCycleChain(List.of(new Leg(root,1,0)),clean(root));}
  private static Mesh clean(RoadRecord road){var mesh=RailwayCrossings.shape(road.rawMesh());var settings=mesh.settings().options(mesh.settings().options()
      // Crossing elevations and bank are baked into these samples. Their local
      // station metadata must not be reused at the first leg's global offset.
      .levelCrossings(List.of())
      .lanePoints(LanePoints.Data.EMPTY).streetscape(mesh.settings().options().streetscape().crossings(List.of())));
    return new Mesh(mesh.samples(),settings,mesh.min(),mesh.max(),mesh.length(),mesh.closed(),mesh.controlPoint());}
  private static boolean compatible(RoadRecord a,RoadRecord b,BlockPos node){
    if(a.id().equals(b.id())||!eligible(b)||a.settings().style()!=b.settings().style()
        ||a.settings().options().leftTraffic()!=b.settings().options().leftTraffic())return false;
    var am=clean(a);var bm=clean(b);var x=a.a().equals(node)?am.first():am.last();var y=b.a().equals(node)?bm.first():bm.last();
    var da=x.left().left().mul(a.a().equals(node)?1:-1);var db=y.left().left().mul(b.a().equals(node)?1:-1);
    if(x.center().distance(y.center())>1e-5||da.dot(db)>-.995)return false;
    var layout=RoadProfile.layout(am,x);if(!layout.catalog().twoWay())return false;
    for(var mesh:List.of(am,bm))for(var s:mesh.samples()){
      var l=RoadProfile.layout(mesh,s);
      if(Math.abs(s.halfWidth()-x.halfWidth())>1e-6||Math.abs(l.motorMin()-layout.motorMin())>1e-6
          ||Math.abs(l.motorMax()-layout.motorMax())>1e-6||Math.abs(l.cycleWidth()-layout.cycleWidth())>1e-6
          ||Math.abs(l.curbWidth()-layout.curbWidth())>1e-6)return false;
    }
    return true;
  }
  List<RoadsideBypass.Span> local(UUID id,List<RoadsideBypass.Span> spans){
    var leg=legs.stream().filter(l->l.road().id().equals(id)).findFirst().orElseThrow();double length=leg.road().rawMesh().length();
    var result=new ArrayList<RoadsideBypass.Span>();
    for(var s:spans){if(s.end()<=leg.offset()||s.start()>=leg.offset()+length)continue;
      result.add(leg.sign()>0?new RoadsideBypass.Span(s.side(),s.start()-leg.offset(),s.from()-leg.offset(),s.to()-leg.offset(),s.end()-leg.offset(),s.delta())
          :new RoadsideBypass.Span(-s.side(),length+leg.offset()-s.end(),length+leg.offset()-s.to(),length+leg.offset()-s.from(),length+leg.offset()-s.start(),s.delta()));
    }return List.copyOf(result);
  }
}
