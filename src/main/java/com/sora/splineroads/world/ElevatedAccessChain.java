package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;

/** Plan in continuous station coordinates, then put the reservation on the original road IDs. */
final class ElevatedAccessChain {
  record Result(ElevatedAccess.Plan plan,Map<UUID,ElevatedAccess.Reservation> reservations){}
  record Section(RoadRecord road,Mesh raw,boolean forward){}
  static Result plan(RoadData data,RoadRecord host,RoadAttachments.Point marker,UUID id,Node end,Settings settings,boolean first){
    var root=host.rawMesh();
    double station=RoadQueries.horizontal(root,marker.position()).sample().distance();
    try{
      var plan=ElevatedAccess.plan(id,marker.id(),root,station,end,settings,first);
      return new Result(plan,Map.of(host.id(),plan.reservation()));
    }catch(IllegalArgumentException single){
      // A clamped host can also distort the held mouth and fail the grade check.
      // Re-run the complete planner against real contiguous pavement.
      var middle=new Section(host,host.caps(0).rawMesh(),true);
      if(!available(host,id)||!eligible(middle.raw()))throw single;
      var sections=new ArrayDeque<Section>();sections.add(middle);
      var seen=new HashSet<UUID>();seen.add(host.id());
      for(boolean before:new boolean[]{true,false}){
        Section previous=middle;
        double reach=0;
        while(sections.size()<64&&reach<end.position().distance(marker.position())+160){
          boolean atA=before==previous.forward();
          BlockPos seam=atA?previous.road().a():previous.road().b();
          var touching=data.index.atNode(seam);
          if(touching.size()!=2||AutoJunctions.center(data,seam)!=null)break;
          var candidate=touching.stream().filter(v->!seen.contains(v)).findFirst().orElse(null);
          if(candidate==null)break;
          var r=data.index.roads.get(candidate).record;
          if(!r.owner().equals(host.owner())||!supported(r)||!available(r,id)
              ||r.settings().structure()!=host.settings().structure()||r.settings().style()!=host.settings().style())break;
          var raw=r.caps(0).rawMesh();boolean nextA=r.a().equals(seam);
          var port=atA?previous.raw().first():previous.raw().last();
          if((nextA?raw.first():raw.last()).center().distance(port.center())>RoadContinuity.POSITION_EPS
              ||Math.abs((atA?previous.road().start().grade():-previous.road().end().grade())
                  +(nextA?r.start().grade():-r.end().grade()))>1e-4
              ||!joins(previous.raw(),atA,raw,nextA)
              ||!LanePoints.sameSection(previous.raw(),port.distance(),raw,nextA?0:raw.length())
              ||Math.abs(raw.first().halfWidth()-middle.raw().first().halfWidth())>.01)break;
          previous=new Section(r,raw,before?!nextA:nextA);seen.add(candidate);reach+=raw.length();
          if(before)sections.addFirst(previous);else sections.addLast(previous);
        }
      }
      if(sections.size()==1)throw single;
      var samples=new ArrayList<Sample>();
      for(var section:sections){
        var part=section.raw().samples();
        for(int i=samples.isEmpty()?0:1;i<part.size();i++){
          var s=part.get(section.forward()?i:part.size()-1-i);
          samples.add(new Sample(s.center(),section.forward()?s.left():s.left().mul(-1),0,s.halfWidth()));
        }
      }
      var mesh=RoadRibbon.mesh(samples,host.settings());
      var plan=ElevatedAccess.plan(id,marker.id(),mesh,RoadAttachments.station(mesh,marker.position()),end,settings,first);
      var result=new LinkedHashMap<UUID,ElevatedAccess.Reservation>();double start=0;
      for(var part:sections){
        double finish=start+part.raw().length();
        var r=plan.reservation();double lo=start,hi=finish;
        boolean occupied=r.cuts().stream().anyMatch(c->Math.min(c.begin(),c.end())<hi&&Math.max(c.begin(),c.end())>lo)
            ||r.additions().stream().anyMatch(a->Math.min(a.station(),a.end())-a.transition()<hi&&Math.max(a.station(),a.end())+a.transition()>lo);
        if(occupied){
          var localMesh=part.road().rawMesh();var at=part.forward()?part.raw().first():part.raw().last();
          double zero=RoadAttachments.station(localMesh,at.center());
          var local=ElevatedAccess.shifted(r,-start);
          if(!part.forward())local=reverse(local,mesh,finish);
          result.put(part.road().id(),ElevatedAccess.shifted(local,zero));
        }
        start=finish;
      }
      return new Result(plan,result);
    }
  }
  // Follow the sampled road geometry, including gentle curves, without moving any
  // existing pavement. Only the shared terminal frames and physical sections must
  // continue; a chord through a curved road is not the terminal direction.
  private static boolean supported(RoadRecord r){
    return r.assembly()==null&&r.junction()==null&&r.toll()==null&&!r.railway()
        &&r.settings().mode()!=Mode.RING&&LaneTopology.metadata(r).link()==null;
  }
  private static boolean eligible(Mesh mesh){
    var settings=mesh.settings();var kind=RoadProfile.catalog(settings).type();
    if(mesh.closed()||mesh.length()<2||settings.structure()==Structure.TUNNEL
        ||kind!=RoadProfile.Type.ORDINARY&&kind!=RoadProfile.Type.HIGHWAY)return false;
    var bridge=settings.options().infrastructure().bridge();
    return settings.structure()!=Structure.BRIDGE||bridge==RoadInfrastructure.Bridge.STANDARD
        ||bridge==RoadInfrastructure.Bridge.BEAM||bridge==RoadInfrastructure.Bridge.OVERPASS;
  }
  private static boolean joins(Mesh a,boolean aFirst,Mesh b,boolean bFirst){
    if(!eligible(a)||!eligible(b))return false;
    var ap=aFirst?a.first():a.last();var bp=bFirst?b.first():b.last();
    if(ap.center().distance(bp.center())>RoadContinuity.POSITION_EPS)return false;
    var intoA=ap.left().left().mul(aFirst?-1:1);
    var intoB=bp.left().left().mul(bFirst?-1:1);
    if(intoA.dot(intoB)>-1+1e-8)return false;
    boolean reversed=aFirst==bFirst;
    var ac=RoadLanes.counts(a.settings());var bc=RoadLanes.counts(b.settings());
    return ac.equals(reversed?bc.mirrored():bc)
        &&(RoadProfile.catalog(a.settings()).twoWay()||!reversed);
  }
  private static boolean available(RoadRecord r,UUID id){
    var m=LaneTopology.metadata(r);return m.link()==null&&m.cuts().stream().allMatch(c->c.connection().equals(id))
        &&m.additions().stream().allMatch(a->a.connection().equals(id))&&m.openings().stream().allMatch(o->o.connection().equals(id))
        &&m.access().stream().allMatch(a->a.connection().equals(id))&&m.bands().isEmpty();
  }
  private static ElevatedAccess.Reservation reverse(ElevatedAccess.Reservation r,Mesh chain,double station){
    var old=RoadProfile.layout(chain,RoadStructures.sample(chain,station));int low=old.lanesOnSide(-1),high=old.lanesOnSide(1);
    var cuts=r.cuts().stream().map(c->new LaneSections.Cut(c.connection(),c.lane()<low?high+c.lane():c.lane()-low,-c.sign(),-c.begin(),-c.end(),
        c.transition(),c.replacement(),c.temporary(),c.arrival(),c.rectangular(),c.underpass())).toList();
    var adds=r.additions().stream().map(a->new LaneAdditions.Addition(a.connection(),a.slot(),-a.sign(),-a.station(),a.transition(),-a.end())).toList();
    var gaps=r.gaps().stream().map(g->new ElevatedAccess.Gap(-g.to(),-g.from(),-g.high(),-g.low())).toList();
    return new ElevatedAccess.Reservation(r.connection(),r.point(),cuts,adds,gaps);
  }
  private ElevatedAccessChain(){}
}
