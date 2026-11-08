package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;

/** A lane-continuous chain of adjoining roads with matching cross sections.
 * Coordinates are signed distance from the clicked point, in its driving direction.
 * Never guesses through a junction or jumps sideways to an unrelated lane. */
public final class LaneRoadChain {
  public record Leg(RoadRecord road,int slot,int sign,double zero){
    public double coordinate(double station){return zero+sign*station;}
    public double low(){return coordinate(sign>0?0:road.rawMesh().length());}
    public double high(){return coordinate(sign>0?road.rawMesh().length():0);}
    public double station(double coordinate){return Math.max(0,Math.min(road.rawMesh().length(),sign*(coordinate-zero)));}
  }
  public record Position(RoadRecord road,LanePoints.Point point,double station){}
  private final List<Leg> legs;
  private final LanePoints.Point origin;
  private LaneRoadChain(List<Leg> legs,LanePoints.Point origin){this.legs=List.copyOf(legs);this.origin=origin;}
  public List<Leg> legs(){return legs;}
  public Set<UUID> ids(){var out=new LinkedHashSet<UUID>();for(var leg:legs)out.add(leg.road().id());return out;}
  public static LaneRoadChain of(Map<UUID,RoadRecord> all,LanePoints.Ref ref){
    return of(all,ref,0);
  }
  static LaneRoadChain of(Map<UUID,RoadRecord> all,LanePoints.Ref ref,int offset){
    var root=all.get(ref.road());if(root==null)throw new IllegalArgumentException("所选连续车道已不存在");
    var point=LaneTopology.point(root,ref.point());var lane=LanePoints.lane(root.rawMesh(),point);
    if(offset!=0){var next=LanePoints.lane(root.rawMesh(),lane.station(),point.lane()+offset);if(next.sign()!=lane.sign())throw new IllegalArgumentException("双车道匝道不能跨越对向车道");point=point.at(next,root.rawMesh());lane=next;}
    var first=new Leg(root,point.lane(),lane.sign(),-lane.sign()*lane.station());
    var out=new ArrayList<Leg>();out.add(first);var seen=new HashSet<UUID>();seen.add(root.id());
    var incident=new HashMap<BlockPos,List<RoadRecord>>();
    for(var road:all.values())if(road.assembly()==null&&road.junction()==null&&LaneTopology.metadata(road).link()==null){
      incident.computeIfAbsent(road.a(),k->new ArrayList<>()).add(road);
      if(!road.b().equals(road.a()))incident.computeIfAbsent(road.b(),k->new ArrayList<>()).add(road);
    }
    for(int direction:new int[]{-1,1}){
      Leg current=first;
      while(true){
        RoadPlanningBudget.check();
        double d=current.sign()*direction>0?current.road().rawMesh().length():0;
        double boundary=current.coordinate(d);
        BlockPos node=d==0?current.road().a():current.road().b();
        var near=incident.getOrDefault(node,List.of());if(near.size()!=2)break;
        var old=LanePoints.lane(current.road().rawMesh(),d,current.slot());Leg next=null;
        for(var road:near)if(!seen.contains(road.id())){
          if(!LanePoints.supported(road.settings())||road.rawMesh().closed())continue;
          double at=road.a().equals(node)?0:road.rawMesh().length();
          if(!LanePoints.sameSection(current.road().rawMesh(),d,road.rawMesh(),at))continue;
          for(int slot:LaneAdditions.slots(road.rawMesh(),at)){
            var lane2=LanePoints.lane(road.rawMesh(),at,slot);
            // Same physical lane center, heading and width across the seam.
            if(old.direction().dot(lane2.direction())<.995||old.position().distance(lane2.position())>.25
                ||Math.abs(old.width()-lane2.width())>.25)continue;
            if((at==0?1:-1)!=lane2.sign()*direction)continue;
            // A lane matched at the wide end of a taper may disappear (or its
            // numeric slot may become opposing traffic) farther along this road.
            // Keep that continuation as a normal obstacle, not a chain-wide
            // selected-lane exemption. Otherwise every route hits a missing slot.
            if(!continuous(road,slot,lane2.sign()))continue;
            if(next!=null)throw new IllegalArgumentException("车道接缝存在多个同向匹配，不能自动换道");
            next=new Leg(road,slot,lane2.sign(),boundary-lane2.sign()*at);
          }
        }
        if(next==null)break;out.add(next);seen.add(next.road().id());current=next;
      }
    }
    out.sort(Comparator.comparingDouble(Leg::low));return new LaneRoadChain(out,point);
  }
  private static boolean continuous(RoadRecord road,int slot,int sign){
    var raw=road.rawMesh();
    for(var sample:raw.samples()){
      if(!LaneAdditions.isAdded(raw,slot)&&slot>=RoadProfile.layout(raw,sample).catalog().lanes())return false;
      if(LanePoints.lane(raw,sample.distance(),slot).sign()!=sign)return false;
    }
    return true;
  }
  public double low(){return legs.get(0).low();}
  public double high(){return legs.get(legs.size()-1).high();}
  public boolean contains(double offset){return Double.isFinite(offset)&&offset>=low()-1e-6&&offset<=high()+1e-6;}
  public Position at(double offset){
    Leg selected=null;
    for(var leg:legs)if(offset>=leg.low()-1e-6&&offset<=leg.high()+1e-6){selected=leg;if(offset<leg.high()-1e-6)break;}
    if(selected==null)throw new IllegalArgumentException("同车道搜索到达实际断头、分岔或不连续接缝，未跨入相邻车道");
    double at=selected.station(offset);var point=LanePoints.point(origin.id(),LanePoints.Origin.MANUAL,selected.road().rawMesh(),at,selected.slot());
    return new Position(selected.road(),point,at);
  }
  public Mesh sweep(){return sweep(true);}
  private Mesh sweep(boolean edges){
    var out=new ArrayList<Sample>();double shift=legs.get(0).low();double thickness=0,girder=0;
    RoadInfrastructure.Config bridge=null;
    for(var leg:legs){var raw=leg.road().rawMesh();thickness=Math.max(thickness,raw.settings().thickness());
      double depth=RoadInfrastructure.girderDepth(raw);if(depth>girder){girder=depth;bridge=raw.settings().options().infrastructure();}
      var samples=new ArrayList<>(raw.samples());if(leg.sign()<0)Collections.reverse(samples);
      for(var sample:samples){double station=leg.coordinate(sample.distance())-shift;
        var lane=LanePoints.lane(raw,sample.distance(),leg.slot());
        if(lane.sign()!=leg.sign())throw new IllegalArgumentException("所选车道在断面变化处已终止，不能跨到反向车道");
        if(!out.isEmpty()&&station-out.get(out.size()-1).distance()<1e-7)continue;
        double low=lane.width()/2+.30,high=low;
        if(edges){
          var layout=RoadProfile.layout(raw,sample);double center=lane.position().sub(sample.center()).dot(sample.left());
          double reach=RoadInfrastructure.edgeReach(raw,sample),lo=0,hi=0;
          if(layout.cycleWidth()<.01){
            if(Math.abs(center-lane.width()/2-layout.motorMin())<.01)lo=Math.max(0,sample.halfWidth()+layout.motorMin())+reach;
            if(Math.abs(center+lane.width()/2-layout.motorMax())<.01)hi=Math.max(0,sample.halfWidth()-layout.motorMax())+reach;
          }
          low+=leg.sign()>0?lo:hi;high+=leg.sign()>0?hi:lo;
        }
        out.add(new Sample(lane.position().add(lane.direction().left().mul((high-low)/2)),lane.direction().left(),station,(high+low)/2));
      }
    }
    var settings=new Settings(Mode.CURVE,Style.C1_RAMP,4,thickness,.35,90);
    // Reservation must open the host's beam as well as its slab. Keep the
    // physical slab setting legal; clearance derives beam depth separately.
    if(bridge!=null)settings=settings.structure(Structure.BRIDGE).options(settings.options().infrastructure(bridge));
    var bounds=RoadRibbon.mesh(out,settings);
    return new Mesh(List.copyOf(out),settings,bounds.min(),bounds.max(),out.get(out.size()-1).distance(),false,null);
  }
  /** Append only the real unsafe interval, in each host's own station system. */
  public void reserve(Map<UUID,List<LaneSections.Event>> events,UUID connection,Mesh ramp,
      List<RoadStructures.Part> parts,boolean arrival,double endOffset,double transition,boolean rectangular){
    double low=legs.get(0).low(),high=legs.get(legs.size()-1).high();
    double begin=arrival?low:0,end=arrival?endOffset:high;
    if(ramp!=null){
      var sweep=sweep();var prepared=RoadClearance.prepare(sweep);var deckContacts=RoadClearance.contacts(prepared,ramp);var contacts=new ArrayList<>(deckContacts);
      for(var part:parts){RoadPlanningBudget.check();contacts.addAll(LaneReopening.structureContacts(prepared,sweep,part,ramp.settings()));}
      if(arrival){
        begin=endOffset;
        for(var c:contacts)if(c.blocked()){
          double from=c.from()+low,to=c.to()+low;
          if(from<=endOffset+.01)begin=Math.min(begin,from);

        }
        for(var c:deckContacts)if(c.blocked()&&c.to()+low>endOffset+.5)
          throw new IllegalArgumentException("汇入点下游仍有实际路面横穿，不能恢复车道");
        // Side rails flush with a valid joining rim are not low overhead obstacles.
        // Validate their actual prisms against the downstream driveable interior.
        if(endOffset+.12<high){
          var driveSweep=sweep(false);
          var after=new ArrayList<Sample>();double from=endOffset+.12-low;
          var at=RoadStructures.sample(driveSweep,from);after.add(new Sample(at.center(),at.left(),0,Math.max(.05,at.halfWidth()-.52)));
          for(var a:driveSweep.samples())if(a.distance()>from)after.add(new Sample(a.center(),a.left(),a.distance()-from,Math.max(.05,a.halfWidth()-.52)));
          var downstream=RoadRibbon.mesh(after,sweep.settings());
          for(var part:parts)if(RoadClearance.structureInvades(part,downstream,RoadClearance.REQUIRED))
            throw new IllegalArgumentException("汇入后实际通行车道被结构占用，不能恢复");
        }
        // Place the physical cutoff eight blocks ahead of the first unsafe
        // contact. The X warnings follow this boundary on intact approach pavement.
        double required=begin-1;
        begin-=rectangular?9:1+transition;
        if(rectangular)for(var leg:legs)for(var cut:LaneTopology.metadata(leg.road()).cuts())if(!cut.connection().equals(connection)&&cut.lane()==leg.slot()){
          double boundary=Math.max(leg.coordinate(cut.begin()),leg.coordinate(cut.end()));
          // Optional warning advance cannot consume an existing legal reservation.
          if(boundary<=required&&boundary>begin)begin=boundary;
        }
      }else{
        end=0;for(var c:contacts)if(c.blocked()&&c.to()+low>=-.01)end=Math.max(end,c.to()+low);
        end+=1+(rectangular?0:transition);
      }
    }
    begin=Math.max(low,begin);end=Math.min(high,end);
    // Authored markers sit half a block inside the real capped ribbon. A closure
    // reaching such a free endpoint must include that cap instead of restoring a
    // half-block road stub with a transverse railing across the connector.
    if(rectangular){if(begin-low<=.50001)begin=low;if(high-end<=.50001)end=high;}
    for(var leg:legs){double a=Math.max(begin,leg.low()),b=Math.min(end,leg.high());if(b-a<.02)continue;
      double start=leg.station(a),finish=leg.station(b);boolean under=false;
      if(ramp!=null){
        // Any below-host stretch must stay open. A later, larger rise must not
        // turn an earlier underground mouth into a planted closed lane.
        for(double d=a;d<=b+.001;d+=.5){var lane=LanePoints.lane(leg.road().rawMesh(),leg.station(d),leg.slot());var q=RoadQueries.horizontal(ramp,lane.position());
          if(q.horizontalDistance()>q.sample().halfWidth()+lane.width()/2+.3)continue;
          if(q.sample().center().y()<lane.position().y()-.1){under=true;break;}
        }
      }
      var event=new LaneSections.Event(connection,arrival?LaneSections.Kind.ARRIVE:LaneSections.Kind.TEMPORARY,
          leg.slot(),leg.sign(),arrival?finish:start,transition,arrival?start:finish,rectangular,under);
      events.computeIfAbsent(leg.road().id(),k->new ArrayList<>()).add(event);
    }
  }
}
