package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.SmartSigns.*;
import com.sora.splineroads.core.SmartSigns.Mode;
import java.util.*;

/** Street signs belong to a saved junction arm, including generated roundabouts. */
final class JunctionSignRoads {
  private record Location(Mesh mesh,Sample sample,int flow,boolean approach){}
  private record Placement(Location location,Support support,boolean medianFlip){}
  /** Exact saved sign mast and board envelopes, using the same placement as
   * derive(). Plant generation reserves these solids before deciding its group. */
  static List<RoadStructures.Part> installationParts(List<RoadIndex.Built> roads){
    var parts=new ArrayList<RoadStructures.Part>();
    for(var owner:roads){var ref=owner.record.junction();if(ref==null||ref.get().arm()<0)continue;
      var arm=ref.spec().arms().get(ref.get().arm());if(arm.incoming()==0)continue;
      for(int slot=0;slot<2;slot++){
        if(!arm.signEnabled(slot))continue;
        var at=placement(owner,slot,p->roads,new HashSet<>());
        var branches=slot==0&&at.medianFlip()?List.of(new Branch("__median__",List.of(),0,true,List.of())):List.<Branch>of();
        var state=State.empty(new Key(owner.record.id(),slot,1))
            .layout(slot==0?Mode.JUNCTION_ADVANCE:Mode.ROAD_NAME,owner.record.id(),branches,"")
            .supported(at.support());
        // Junction panels have fixed dimensions; the median branch records
        // the only placement-dependent reflection used by junctionLayout.
        parts.addAll(SmartSigns.mountParts(owner.mesh,state));
      }
    }
    return List.copyOf(parts);
  }
  /** Reserve a small paved pedestal in a planted median, on its actual road owner. */
  static List<RoadStructures.Part> plantedSeats(RoadIndex.Built owner,List<RoadIndex.Built> roads){
    var out=new ArrayList<RoadStructures.Part>();
    for(var road:roads){var ref=road.record.junction();if(ref==null||ref.get().arm()<0)continue;
      var arm=ref.spec().arms().get(ref.get().arm());if(arm.incoming()==0||!arm.advanceSign())continue;
      var mount=placement(road,0,p->roads,new HashSet<>());if(mount.location().mesh()!=owner.mesh)continue;
      var foot=mount.support().foot();var sample=mount.location().sample();
      if(Math.abs(foot.sub(sample.center()).dot(sample.left()))>=sample.halfWidth()-.3)continue;
      V base=foot.add(mount.support().front().mul(-.03)).add(new V(0,-.4,0));
      out.add(new RoadStructures.Part(base,base,.65,.4,true,RoadStructures.Material.CONCRETE,null,null,"sr:junction_sign_pedestal"));
    }
    return List.copyOf(out);
  }
  static void reservePlanting(List<RoadStructures.Part> parts,RoadIndex.Built owner,List<RoadIndex.Built> roads){
    var bases=plantedSeats(owner,roads);if(bases.isEmpty())return;
    RoadStructures.trimPlanting(parts,bases,true);parts.addAll(bases);
  }
  static List<RoadStructures.Part> supportParts(RoadIndex.Built owner,List<RoadIndex.Built> roads,RoadStructures.Ground ground){
    var out=new ArrayList<RoadStructures.Part>();var ref=owner.record.junction();
    if(ref==null||ref.get().arm()<0)return out;
    var arm=ref.spec().arms().get(ref.get().arm());
    for(int slot=0;slot<2;slot++){
      if(arm.incoming()==0||!(slot==0?arm.advanceSign():arm.nameSign()))continue;
      var mount=placement(owner,slot,p->roads,new HashSet<>());var foot=mount.support().foot();var mesh=mount.location().mesh();
      var at=mount.location().sample();double lateral=foot.sub(at.center()).dot(at.left());
      if(Math.abs(lateral)<=at.halfWidth()-.3||ground.top(foot.x(),foot.z(),foot.y())>=foot.y()-.05)continue;
      if(mesh.settings().options().sidewalk().enabled()&&Math.abs(lateral)<at.halfWidth()+mesh.settings().options().sidewalk().width()-.3)continue;
      V anchor=at.at(Math.copySign(at.halfWidth()-.4,lateral),0);double y=at.center().y()-Math.max(.5,mesh.settings().thickness());
      V outward=at.left().mul(Math.signum(lateral));
      var seat=new RoadStructures.Part(new V(anchor.x(),y,anchor.z()),new V(foot.x(),y,foot.z()).add(outward.mul(.35)),.8,foot.y()-y,false,RoadStructures.Material.CONCRETE,null,null,"sr:junction_sign_seat");
      if(ground.blocked(seat))throw new IllegalArgumentException("路口标牌承托与其他道路冲突");out.add(seat);
    }
    return List.copyOf(out);
  }
  static State derive(RoadData data,RoadIndex.Built owner,State old,int slot,Set<UUID> dependencies){
    var ref=owner.record.junction();var spec=ref.spec();int index=ref.get().arm();var arm=spec.arms().get(index);Mesh mesh=owner.mesh;
    Mode mode=slot==0?Mode.JUNCTION_ADVANCE:Mode.ROAD_NAME;
    var placement=placement(owner,slot,p->{
      int cx=(int)Math.floor(p.x())>>4,cz=(int)Math.floor(p.z())>>4;var found=new LinkedHashSet<RoadIndex.Built>();
      for(int x=cx-1;x<=cx+1;x++)for(int z=cz-1;z<=cz+1;z++)for(UUID id:data.index.inChunk(net.minecraft.world.level.ChunkPos.asLong(x,z))){var road=data.index.roads.get(id);if(road!=null)found.add(road);}return found;
    },dependencies);var support=placement.support();
    var branches=new ArrayList<Branch>();if(slot==0&&placement.medianFlip())branches.add(new Branch("__median__",List.of(),0,true,List.of()));
    if(slot==1)branches.add(new Branch("road",List.of(),0,true,List.of()));
    else {

      V heading=mesh.last().left().left().mul(-1),diagramRight=heading.left();
      for(int i=0;i<spec.arms().size();i++){
        if(i==index||spec.arms().get(i).outgoing()==0)continue;
        final int destination=i;
        var exit=ref.approach(destination);
        V outward=exit.last().left().left();double dx=outward.dot(diagramRight),dz=outward.dot(heading);
        int turn=Math.abs(dx)<.22&&dz>0?0:dx<0?-1:1;
        branches.add(new Branch("arm:"+armIdentity(data,owner,i),List.of(),turn,true,List.of(new V(dx,0,dz))));
      }
    }
    String entry=(spec.kind()==JunctionSpec.Kind.ROUNDABOUT?"roundabout:":"junction:")+armIdentity(data,owner,index);
    return old.layout(mode,owner.record.assembly()==null?owner.record.id():owner.record.assembly(),branches,"",entry).supported(support);
  }
  private static Placement placement(RoadIndex.Built owner,int slot,java.util.function.Function<V,Collection<RoadIndex.Built>> candidates,Set<UUID> dependencies){
    var ref=owner.record.junction();var spec=ref.spec();var arm=spec.arms().get(ref.get().arm());Mesh mesh=owner.mesh;
    double ring=spec.kind()==JunctionSpec.Kind.ROUNDABOUT?spec.islandRadius()+spec.ringLanes()*spec.ringLaneWidth()-mesh.last().center().sub(spec.center()).horizontalLength():0;
    double stop=mesh.length()-ring-arm.crossingSetback()-(arm.crosswalk()?arm.crossingWidth()+arm.stopGap():0);
    double before=slot==0?36:6;
    Location at=locate(candidates,owner,stop-before,dependencies);
    if(slot==0&&arm.rightSlip()&&ref.geometryVersion()>=50){
      var initialSettings=at.approach()?JunctionRoads.editable(arm):at.mesh().settings();
      var initial=RoadProfile.layout(initialSettings,at.sample().halfWidth()*2);
      boolean cycleGreen=initialSettings.options().cycle()&&initialSettings.options().streetscape().separator()==RoadStreetscape.Separator.GREEN;
      boolean central=!cycleGreen&&initial.catalog().median()==RoadProfile.Median.GREEN&&initial.median()>.4;
      if(!central)at=locate(candidates,owner,Math.min(stop-before,JunctionSlips.advanceSignStation(spec,ref.get().arm(),ref.furnitureApproach(ref.get().arm()))),dependencies);
    }
    int side=spec.leftTraffic()?-1:1;
    var road=at.mesh();if(at.approach())road=new Mesh(road.samples(),JunctionRoads.editable(arm),road.min(),road.max(),road.length(),road.closed());var sample=at.sample();var profile=RoadProfile.layout(road,sample);
    double median=at.approach()?JunctionPlanner.approachMedian(arm,at.mesh(),sample,true,spec.leftTraffic()):profile.medianCenter();
    int physicalSide=side*at.flow();double lateral;
    if(slot==0&&road.settings().options().cycle()&&road.settings().options().streetscape().separator()==RoadStreetscape.Separator.GREEN){
      lateral=(at.approach()?JunctionPlanner.approachMotor(arm,at.mesh(),sample,physicalSide):profile.outer(physicalSide))+physicalSide*RoadStreetscape.separatorWidth(road.settings().options())*.5;
    }else if(slot==0&&profile.catalog().median()==RoadProfile.Median.GREEN&&profile.median()>.4){lateral=median;}
    else lateral=physicalSide*(sample.halfWidth()+(slot==0?.8:.45));
    V right=sample.left().mul(at.flow()),forward=right.left().mul(-1);
    V foot=sample.center().add(sample.left().mul(lateral));
    boolean onRoad=Math.abs(lateral)<sample.halfWidth();
    if(onRoad)foot=foot.add(new V(0,.4,0));else if(road.settings().options().sidewalk().enabled())foot=foot.add(new V(0,.2,0));
    // A road-name plate follows the kerb; its front points inward across the road.
    V face=slot==1?right.mul(-side):forward.mul(-1);
    V panelRight=slot==1?face.left().mul(-1):right;
    // A median mast cantilevers over the incoming half of the road.
    boolean cantileverRight=slot==0&&onRoad&&Math.abs(lateral-median)<.1?side>0:spec.leftTraffic();
    var support=new Support(foot,panelRight,face,spec.leftTraffic());
    return new Placement(at,support,cantileverRight!=spec.leftTraffic());
  }
  /** The same mount/road sample drives physical lamp exclusion and the visible sign. */
  static java.util.function.Predicate<V> lampClearance(List<RoadIndex.Built> roads){
    var signs=new ArrayList<java.util.function.Predicate<V>>();
    for(var road:roads){var sign=lampClearance(road,roads);if(sign!=null)signs.add(sign);}
    return point->{for(var sign:signs)if(sign.test(point))return true;return false;};
  }
  /** One source per maintenance step; placement keeps the complete shared road
   * context and the same joined-approach algorithm as synchronous planning. */
  static java.util.function.Predicate<V> lampClearance(RoadIndex.Built road,List<RoadIndex.Built> roads){
    if(road.record.junction()==null||road.record.junction().get().arm()<0
        ||road.record.junction().spec().arms().get(road.record.junction().get().arm()).incoming()<=0||!road.record.junction().spec().arms().get(road.record.junction().get().arm()).advanceSign())return null;
    var sign=placement(road,0,p->roads,new HashSet<>());
    return point->{var at=sign.location();var q=RoadQueries.horizontal(at.mesh(),point);
      // Include the tangent remainder when closest-point queries clamp to an endpoint.
      // Otherwise every point beyond a short approach appears inside the first 24 m.
      double extra=point.sub(q.sample().center()).dot(q.sample().left().left().mul(-1));
      double before=(at.sample().distance()-q.sample().distance()-extra)*at.flow();
      double pole=sign.support().foot().sub(at.sample().center()).dot(at.sample().left());
      // Reserve the actual cantilever plus the lamp's longest arm. A cycle-side
      // board cannot be obscured by a distant central-median lamp; clearing the
      // entire incoming half-road incorrectly removed that independent row.
      int side=(sign.support().paired()?-1:1)*at.flow();
      int cantilever=sign.medianFlip()?side:-side;
      double tip=pole+cantilever*3.32,lo=Math.min(pole,tip)-3.1,hi=Math.max(pole,tip)+3.1;
      return before>=-3&&before<=24&&Math.abs(point.y()-q.sample().center().y())<3&&q.lateral()>=lo&&q.lateral()<=hi;};
  }
  private static String armIdentity(RoadData data,RoadIndex.Built owner,int arm){
    var descriptor=data.junctions.get(owner.record.assembly());
    if(descriptor!=null){var roads=descriptor.getList("ArmRoads",net.minecraft.nbt.Tag.TAG_STRING);if(arm<roads.size()&&!roads.getString(arm).isEmpty())return roads.getString(arm);}
    var p=owner.record.junction().spec().arms().get(arm).endpoint().position();
    return UUID.nameUUIDFromBytes((Math.round(p.x()*1000)+":"+Math.round(p.y()*1000)+":"+Math.round(p.z()*1000)).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
  }
  /** Walk real joined road endpoints when the auto junction's 16 m throat is too short. */
  private static Location locate(java.util.function.Function<V,Collection<RoadIndex.Built>> candidates,RoadIndex.Built owner,double station,Set<UUID> dependencies){
    Mesh mesh=owner.mesh;var ref=owner.record.junction();
    // The bypass removes one edge of the visible approach mesh. Its shifted
    // ribbon center is not the original carriageway/median reference frame.
    Mesh frame=ref.geometryVersion()>=50?ref.furnitureApproach(ref.get().arm()):mesh;
    if(station>=0)return new Location(mesh,RoadStructures.sample(frame,Math.min(station,frame.length()-2)),1,true);
    V point=mesh.first().center(),upstream=mesh.first().left().left();double remaining=-station;
    var seen=new HashSet<UUID>();seen.add(owner.record.id());
    for(int hops=0;hops<8;hops++){
      var matches=new ArrayList<Location>();var ids=new ArrayList<UUID>();
      for(var b:candidates.apply(point)){UUID id=b.record.id();if(seen.contains(id)||b.record.junction()!=null||b.record.settings().style().ramp())continue;
        for(boolean first:new boolean[]{true,false}){var end=first?b.mesh.first():b.mesh.last();V away=end.left().left().mul(first?-1:1);
          if(end.center().distance(point)>.08||away.dot(upstream)<.95)continue;
          matches.add(new Location(b.mesh,RoadStructures.sample(b.mesh,first?Math.min(remaining,b.mesh.length()-2):Math.max(2,b.mesh.length()-remaining)),first?-1:1,false));ids.add(id);
        }
      }
      if(matches.size()!=1)break;
      var match=matches.get(0);UUID id=ids.get(0);dependencies.add(id);seen.add(id);
      if(remaining<=match.mesh().length()-2)return match;
      remaining-=match.mesh().length();var end=match.flow()<0?match.mesh().last():match.mesh().first();point=end.center();upstream=end.left().left().mul(match.flow());
    }
    return new Location(mesh,RoadStructures.sample(frame,Math.min(2,frame.length()/4)),1,true);
  }
  private JunctionSignRoads(){}
}
