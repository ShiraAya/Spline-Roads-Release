package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;
/** Narrow-phase dependencies. Distant curves with overlapping bounds are independent. */
final class RoadInteractions {
  static boolean connected(RoadRecord a,RoadRecord b){return a.a().equals(b.a())||a.a().equals(b.b())||a.b().equals(b.a())||a.b().equals(b.b());}
  /** A host edit must derive its lane openings before checking its own connector.
   * The transaction rebuilds/validates these dependents against the final host. */
  static boolean deferredLaneContact(RoadRecord host,RoadRecord ramp){
    var link=LaneTopology.metadata(ramp).link();if(link==null)return false;
    return host.id().equals(link.from().road())||host.id().equals(link.to().road())
        ||host.assembly()!=null&&host.assembly().equals(link.to().junction())
        ||LaneTopology.metadata(host).cuts().stream().anyMatch(c->c.connection().equals(ramp.id()))
        ||LaneTopology.metadata(host).additions().stream().anyMatch(a->a.connection().equals(ramp.id()));
  }
  static boolean influences(RoadIndex.Built a,RoadIndex.Built b){
    if(a.record.legacyRailway()||b.record.legacyRailway())return false;
    if(connected(a.record,b.record)||RailwayCrossings.any(a.mesh,b.mesh)||!RailwayCrossings.adjustableContacts(a.record.withLevelCrossings(List.of()).mesh(),b.record.withLevelCrossings(List.of()).mesh()).isEmpty())return true;
    if(MultiLevelBridges.compatible(a.mesh,b.mesh)||MultiLevelBridges.compatible(b.mesh,a.mesh))return true;
    double walkway=Math.max(walkExtent(a.mesh),walkExtent(b.mesh));
    if(!RoadIndex.overlapXZ(a.mesh,b.mesh,3+walkway))return false;
    if(LaneTopology.metadata(b.record).link()!=null){
      // A host (or parent-ramp) edit regenerates this dependent in the same
      // transaction. Its old throat cannot veto the new host before that step.
      if(deferredLaneContact(a.record,b.record))return true;
      // A nearby independent ramp is saved authored work. Safe additions keep its
      // furniture; an actual collision must be resolved by the proposed road.
      var link=LaneTopology.metadata(a.record).link();
      var oldLink=LaneTopology.metadata(b.record).link();
      boolean source=link!=null&&(b.record.id().equals(link.from().road())||link.from().equals(oldLink.from())),target=link!=null&&(b.record.id().equals(link.to().road())||link.to().equals(oldLink.to()));
      var host=Map.of(b.record.id(),b.record);var ids=Set.of(b.record.id());
      double begin=source?a.mesh.samples().get(LaneRamps.contactEnd(a.mesh,host,ids,true)).distance():-1;
      double end=target?a.mesh.samples().get(LaneRamps.contactEnd(a.mesh,host,ids,false)).distance():a.mesh.length()+1;
      for(var part:b.record.structures())for(var contact:RoadClearance.structureContacts(part,a.mesh,4.25))if(contact.blocked()
          &&!(source&&contact.to()<=begin+.01||target&&contact.from()>=end-.01))
        throw new IllegalArgumentException(String.format(Locale.ROOT,"新道路与已有匝道设施冲突；冲突道路 %s，构件 %s，坐标 %.2f %.2f %.2f；已保留原匝道",b.record.id(),part.material(),part.a().x(),part.a().y(),part.a().z()));
      // The joined ramp must rebuild its rails against the new paved mouth.
      // Keeping its old furniture after exempting the throat leaves a real fence.
      return source||target;
    }
    // A connector edit must refresh its host's boundary too. Same-height hosts
    // without cycle lanes otherwise retain saved rails through the new merge.
    if(deferredLaneContact(b.record,a.record))return true;
    if((a.mesh.settings().style().ramp()||LaneTopology.metadata(a.record).link()!=null)
        &&RoadProfile.catalog(b.mesh.settings().style()).type()==RoadProfile.Type.ORDINARY
        &&(b.mesh.settings().options().cycle()||b.mesh.settings().options().sidewalk().enabled()))return true;
    // Any existing elevated road depends on a new lower corridor, including
    // saved ramp decks. Rebuild its supports and the lower road's lamps.
    // Same-height independent neighbours keep their saved furniture.
    for(var s:b.mesh.samples()){
      var q=RoadQueries.horizontal(a.mesh,s.center());double dy=s.center().y()-q.sample().center().y();
      if(dy>3&&dy<RoadStructures.MAX_DROP&&s.center().sub(q.sample().center()).horizontalLength()<s.halfWidth()+q.sample().halfWidth()+1+walkway)return true;
      if(dy<-.25&&dy>-(RoadStreetscape.hasPlanting(b.mesh)?RoadStructures.MAX_DROP:10)
          &&s.center().sub(q.sample().center()).horizontalLength()<s.halfWidth()+q.sample().halfWidth()+3+walkway)return true;
    }
    return false;
  }
  private static double walkExtent(Mesh mesh){
    var walk=mesh.settings().options().sidewalk();
    return walk.enabled()&&walk.smooth()&&mesh.settings().structure()!=Structure.TUNNEL&&RoadProfile.catalog(mesh.settings().style()).type()==RoadProfile.Type.ORDINARY?walk.width():0;
  }
  private static boolean near(Mesh a,Mesh b){
    for(var s:a.samples()){
      var p=RoadQueries.horizontal(b,s.center());
      double dx=s.center().sub(p.sample().center()).horizontalLength();
      if(dx<s.halfWidth()+p.sample().halfWidth()+3 && Math.abs(s.center().y()-p.sample().center().y())<RoadStructures.MAX_DROP)return true;
    }return false;
  }
  static boolean selfSupportBlocked(Part part,Mesh mesh){
    return RoadStructureClearance.selfSupportBlocked(part,mesh);
  }
  static boolean invades(Part p,Mesh m){
    return RoadClearance.structureInvades(p,m,RoadStructureClearance.headroom(m));
  }
  static java.util.function.DoubleUnaryOperator tunnelHeadroom(RoadIndex.Built ramp,List<RoadIndex.Built> context){
    return new java.util.function.DoubleUnaryOperator(){private java.util.function.DoubleUnaryOperator resolved;public double applyAsDouble(double d){if(resolved==null)resolved=matchedHeadroom(ramp,context);return resolved.applyAsDouble(d);}};
  }
  private static java.util.function.DoubleUnaryOperator matchedHeadroom(RoadIndex.Built ramp,List<RoadIndex.Built> context){
    var all=new LinkedHashMap<UUID,RoadRecord>();context.forEach(r->all.put(r.record.id(),r.record));all.put(ramp.record.id(),ramp.record);
    var link=LaneTopology.metadata(ramp.record).link();
    var source=link==null?generatedHosts(ramp,context,true):LaneRamps.contactRoads(all,link.from());
    var target=link==null?generatedHosts(ramp,context,false):LaneRamps.contactRoads(all,link.to());
    record Parent(RoadIndex.Built road,double from,double to,java.util.function.DoubleUnaryOperator roof){}
    var parents=new ArrayList<Parent>();
    for(var host:context)if(source.contains(host.record.id())||target.contains(host.record.id())){
      double first=source.contains(host.record.id())?0:ramp.mesh.samples().get(LaneRamps.contactEnd(ramp.mesh,all,Set.of(host.record.id()),false)).distance();
      double last=target.contains(host.record.id())?ramp.mesh.length():ramp.mesh.samples().get(LaneRamps.contactEnd(ramp.mesh,all,Set.of(host.record.id()),true)).distance();
      parents.add(new Parent(host,first,last,noseCeiling(host)));
    }
    return d->{var at=RoadStructures.sample(ramp.mesh,d);double height=ramp.mesh.settings().structure()==Structure.TUNNEL?RoadInfrastructure.ceiling(ramp.mesh.settings().options().infrastructure(),at.halfWidth(),0):Math.max(RoadAutoTunnels.HEADROOM,ramp.mesh.settings().options().infrastructure().headroom());
      for(var p:parents){double outside=Math.max(p.from()-d,d-p.to());if(outside>=16)continue;var q=RoadQueries.horizontal(p.road().mesh,at.center());double roof=p.roof().applyAsDouble(q.sample().distance());if(roof<RoadAutoTunnels.HEADROOM)continue;
        double difference=q.sample().center().y()+roof-at.center().y()-height;
        height+=Math.max(0,difference)*(1-Settings.smooth(Math.max(0,outside)/16));}
      return height;};
  }

  /** Generated ramps use explicit level endpoints rather than lane-point IDs.
   * Only the contiguous mouth is opened by the same lining/nose logic as links. */
  private static Set<UUID> generatedHosts(RoadIndex.Built ramp,List<RoadIndex.Built> context,boolean first){
    if(ramp.record.assembly()==null||!ramp.mesh.settings().style().ramp())return Set.of();
    var result=new HashSet<UUID>();var end=first?ramp.mesh.first():ramp.mesh.last();
    for(var host:context){
      if(host.record.id().equals(ramp.record.id())||host.mesh.settings().style().ramp()&&!ramp.record.assembly().equals(host.record.assembly()))continue;
      var q=RoadQueries.horizontal(host.mesh,end.center());
      if(Math.abs(end.center().y()-q.sample().center().y())<.025
          &&Math.abs(end.left().dot(q.sample().left()))>.94
          &&q.horizontalDistance()<end.halfWidth()+q.sample().halfWidth()+RoadAutoTunnels.LINING*2)result.add(host.record.id());
    }
    return Set.copyOf(result);
  }

  private static java.util.function.DoubleUnaryOperator noseCeiling(RoadIndex.Built road){return noseCeiling(road,road.record.structures());}
  private static java.util.function.DoubleUnaryOperator noseCeiling(RoadIndex.Built road,List<Part> parts){
    if(road.mesh.settings().structure()==Structure.TUNNEL)return d->RoadInfrastructure.ceiling(road.mesh.settings().options().infrastructure(),RoadStructures.sample(road.mesh,d).halfWidth(),0);
    var roofs=RoadAutoTunnels.roofRegions(parts);var walls=RoadAutoTunnels.wallCeiling(road.mesh,parts);
    return d->{for(var r:roofs)if(d>=r.from()&&d<=r.to())return r.first()+(r.last()-r.first())*(d-r.from())/(r.to()-r.from());return walls.applyAsDouble(d);};
  }
  static List<Part> openPortal(RoadIndex.Built tube,List<Part> parts,List<RoadIndex.Built> roads){
    return openPortal(tube,parts,roads,roads);
  }
  static List<Part> openPortal(RoadIndex.Built tube,List<Part> parts,List<RoadIndex.Built> roads,List<RoadIndex.Built> context){
    boolean explicit=tube.record.settings().structure()==Structure.TUNNEL;
    if(!explicit&&parts.stream().noneMatch(p->p.material()==Material.TUNNEL&&!p.model().startsWith("sr:cycle_")))return parts;
    // Lane mouths join a point inside a host, not its physical endpoint block.
    // Open only the contiguous approach to those authored hosts. Later crossings
    // and unrelated roads still reach the normal shell collision validator.
    var link=LaneTopology.metadata(tube.record).link();
    var all=new LinkedHashMap<UUID,RoadRecord>();context.forEach(r->all.put(r.record.id(),r.record));all.put(tube.record.id(),tube.record);
    var source=link==null?generatedHosts(tube,roads,true):LaneRamps.contactRoads(all,link.from());
    var target=link==null?generatedHosts(tube,roads,false):LaneRamps.contactRoads(all,link.to());
    boolean linked=link!=null||!source.isEmpty()||!target.isEmpty();
    // RoadInfrastructure's lining extends 1.55 beyond the road edge; its last
    // wall segment can still overlap after the deck itself has separated.
    double liningWidth=RoadAutoTunnels.LINING;
    double parentApron=context.stream().filter(r->source.contains(r.record.id())||target.contains(r.record.id())).mapToDouble(r->r.mesh.settings().structure()==Structure.TUNNEL?1.55:RoadAutoTunnels.regions(r.record.structures()).isEmpty()?0:RoadAutoTunnels.LINING).max().orElse(0);
    var lining=!linked?tube.mesh:RoadRibbon.mesh(tube.mesh.samples().stream().map(s->new Sample(s.center(),s.left(),s.distance(),s.halfWidth()+liningWidth+parentApron)).toList(),tube.mesh.settings());
    double begin=!linked?0:lining.samples().get(LaneRamps.contactEnd(lining,all,source,true)).distance();
    double finish=!linked?tube.mesh.length():lining.samples().get(LaneRamps.contactEnd(lining,all,target,false)).distance();
    // The host also needs an opening where a lane connector starts inside it.
    // Physical marker equality only covers end-to-end roads, not lane points.
    record Mouth(RoadIndex.Built ramp,boolean source,boolean target,double from,double to){}
    var mouths=new ArrayList<Mouth>();
    for(var r:roads){
      var branch=LaneTopology.metadata(r.record).link();
      var sources=branch==null?generatedHosts(r,context,true):LaneRamps.contactRoads(all,branch.from());
      var targets=branch==null?generatedHosts(r,context,false):LaneRamps.contactRoads(all,branch.to());
      boolean atStart=sources.contains(tube.record.id()),atEnd=targets.contains(tube.record.id());
      if(!atStart&&!atEnd)continue;
      // Account for the host lining outside the deck as well as the ramp width.
      var rampLining=RoadRibbon.mesh(r.mesh.samples().stream().map(s->new Sample(s.center(),s.left(),s.distance(),s.halfWidth()+liningWidth)).toList(),r.mesh.settings());
      double from=atStart?rampLining.samples().get(LaneRamps.contactEnd(rampLining,all,sources,true)).distance():-1;
      double to=atEnd?rampLining.samples().get(LaneRamps.contactEnd(rampLining,all,targets,false)).distance():r.mesh.length()+1;
      mouths.add(new Mouth(r,atStart,atEnd,from,to));
    }
    for(var mouth:mouths){
      if(mouth.source())parts=RoadTunnelNose.apply(parts,RoadTunnelNose.build(tube.mesh,mouth.ramp().mesh,0,mouth.from(),noseCeiling(tube,parts),tunnelHeadroom(mouth.ramp(),context)));
      if(mouth.target())parts=RoadTunnelNose.apply(parts,RoadTunnelNose.build(tube.mesh,mouth.ramp().mesh,mouth.to(),mouth.ramp().mesh.length(),noseCeiling(tube,parts),tunnelHeadroom(mouth.ramp(),context)));
    }
    var openings=new ArrayList<RoadShellOpening>();
    for(var mouth:mouths){var rampHeight=tunnelHeadroom(mouth.ramp(),context);var hostHeight=noseCeiling(tube,parts);
      java.util.function.DoubleUnaryOperator height=d->{var at=RoadStructures.sample(mouth.ramp().mesh,d);var q=RoadQueries.horizontal(tube.mesh,at.center());return Math.max(rampHeight.applyAsDouble(d),q.sample().center().y()+hostHeight.applyAsDouble(q.sample().distance())-at.center().y());};
      if(mouth.source())openings.add(new RoadShellOpening(mouth.ramp().mesh,0,mouth.from(),height));
      if(mouth.target())openings.add(new RoadShellOpening(mouth.ramp().mesh,mouth.to(),mouth.ramp().mesh.length(),height));
    }
    // Separate live parent air from physical lining. The old apron-wide prism
    // deleted the ramp's retaining wall and a strip beyond real roof corners.
    var parents=new HashMap<UUID,List<RoadShellOpening>>();
    if(linked)for(var r:roads)if(source.contains(r.record.id())||target.contains(r.record.id())){
      boolean tunnel=r.mesh.settings().structure()==Structure.TUNNEL;
      var profiles=RoadAutoTunnels.roofRegions(r.record.structures());
      var childHeight=tunnelHeadroom(tube,context);
      var air=new RoadShellOpening(r.mesh,0,r.mesh.length(),d->{var at=RoadStructures.sample(r.mesh,d);
        double height=tunnel?RoadInfrastructure.ceiling(r.mesh.settings().options().infrastructure(),at.halfWidth(),0):4.25;
        if(!tunnel)for(var profile:profiles)if(d>=profile.from()&&d<=profile.to()){height=profile.first()+(profile.last()-profile.first())*(d-profile.from())/(profile.to()-profile.from());break;}
        // Remove the whole inner branch wall above the shared pavement. A
        // shorter parent ceiling must not leave the upper half hanging in air.
        var child=RoadQueries.horizontal(tube.mesh,at.center()).sample();
        return Math.max(height,child.center().y()+childHeight.applyAsDouble(child.distance())-at.center().y());});
      List<Part> shell=(tunnel?RoadInfrastructure.tunnelShell(r.mesh):r.record.structures()).stream().filter(p->p.material()==Material.TUNNEL).toList();
      // The parent also opens onto this ramp. Subtract its retained lining, not
      // an uncut wall that no longer exists after the reciprocal opening.
      var portals=new ArrayList<RoadShellOpening>();var noseCuts=new ArrayList<RoadShellOpening>();
      var mouthLining=RoadRibbon.mesh(tube.mesh.samples().stream().map(s->new Sample(s.center(),s.left(),s.distance(),s.halfWidth()+liningWidth)).toList(),tube.mesh.settings());
      if(source.contains(r.record.id())){double end=mouthLining.samples().get(LaneRamps.contactEnd(mouthLining,all,source,true)).distance();portals.add(new RoadShellOpening(tube.mesh,0,end,tunnelHeadroom(tube,context)));var join=RoadTunnelNose.build(r.mesh,tube.mesh,0,end,noseCeiling(r),tunnelHeadroom(tube,context));shell=RoadTunnelNose.apply(shell,join);noseCuts.add(RoadShellOpening.solids(join.volume()));}
      if(target.contains(r.record.id())){double start=mouthLining.samples().get(LaneRamps.contactEnd(mouthLining,all,target,false)).distance();portals.add(new RoadShellOpening(tube.mesh,start,tube.mesh.length(),tunnelHeadroom(tube,context)));var join=RoadTunnelNose.build(r.mesh,tube.mesh,start,tube.mesh.length(),noseCeiling(r),tunnelHeadroom(tube,context));shell=RoadTunnelNose.apply(shell,join);noseCuts.add(RoadShellOpening.solids(join.volume()));}
      for(var aperture:portals){var clipped=new ArrayList<Part>();for(var p:shell)clipped.addAll(aperture.cut(p));shell=clipped;}
      var cuts=new ArrayList<RoadShellOpening>();cuts.add(air);cuts.add(RoadShellOpening.solids(shell.stream().filter(p->p.material()==Material.TUNNEL).toList()));cuts.addAll(noseCuts);parents.put(r.record.id(),List.copyOf(cuts));
    }
    var out=new ArrayList<Part>();
    for(var p:parts){
      if(p.material()!=Material.TUNNEL&&p.material()!=Material.SIGN_WHITE&&p.material()!=Material.LAMP){out.add(p);continue;}
      List<Part> pieces=List.of(p);
      boolean ceilingLight=p.model().equals("sr:tunnel_ceiling_light")||p.model().equals("sr:auto_light");
      // This roof belongs to the parent across the shared mouth, so its lights
      // continue too. Only wall-mounted decoration is removed with the wall.
      if(!ceilingLight)for(var opening:openings){var next=new ArrayList<Part>();for(var piece:pieces)next.addAll(opening.cut(piece));pieces=next;}
      if(linked){
        double low=p.base().stream().mapToDouble(v->RoadQueries.horizontal(tube.mesh,v).sample().distance()).min().orElseThrow();
        double high=p.base().stream().mapToDouble(v->RoadQueries.horizontal(tube.mesh,v).sample().distance()).max().orElseThrow();
        for(var r:roads)if(source.contains(r.record.id())&&low<=begin+.01||target.contains(r.record.id())&&high>=finish-.01){
          for(var opening:parents.get(r.record.id())){
            var next=new ArrayList<Part>();for(var piece:pieces)next.addAll(opening.cut(piece));pieces=next;
          }
        }
      }
      // Existing physical end-to-end ports retain their scoped endpoint rule.
      boolean endPort=false;
      for(var r:roads)if(!r.record.id().equals(tube.record.id())&&connected(tube.record,r.record)){
        V mid=p.a().add(p.b()).mul(.5);
        for(var pos:List.of(tube.record.a(),tube.record.b()))if(pos.equals(r.record.a())||pos.equals(r.record.b())){
          V end=pos.equals(tube.record.a())?tube.mesh.first().center():tube.mesh.last().center();
          if(mid.sub(end).horizontalLength()<Math.max(8,(tube.record.settings().width()+r.record.settings().width())*.6+3)&&invades(p,r.mesh))endPort=true;
        }
      }
      if(!endPort)out.addAll(pieces);
    }
    // Check attachment in the actual retained lining. Expanding a clipped
    // triangle's frame expanded an arbitrary diagonal, not the wall normal,
    // and punched irregular gaps into otherwise continuous wall strips.
    var attached=RoadShellOpening.solids(out.stream().filter(p->p.material()==Material.TUNNEL).toList());
    var result=new ArrayList<Part>();
    for(var p:out){
      if(p.material()!=Material.LAMP&&p.material()!=Material.SIGN_WHITE){result.add(p);continue;}
      boolean ceiling=p.model().equals("sr:tunnel_ceiling_light")||p.model().equals("sr:auto_light");
      var q=RoadQueries.horizontal(tube.mesh,p.a().add(p.b()).mul(.5));
      V shift=ceiling?new V(0,.24,0):q.sample().left().mul((q.lateral()<0?-1:1)*.36);
      for(var kept:attached.retain(translated(p,shift)))result.add(translated(kept,shift.mul(-1)));
    }
    return List.copyOf(result);
  }
  private static Part translated(Part p,V shift){return new Part(p.a().add(shift),p.b().add(shift),p.width(),p.height(),p.pier(),p.material(),p.frameA(),p.frameB(),p.model(),p.heights());}
}
