package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import java.util.*;

/** Computes reversible host lane reservations from real connector links, never from paint. */
public final class LaneCrossSections {
  public static Map<UUID,RoadRecord> staged(Map<UUID,RoadRecord> records,UUID edited,LanePoints.Link proposal){
    return staged(records,edited,proposal,null);
  }
  public static Map<UUID,RoadRecord> staged(Map<UUID,RoadRecord> records,UUID edited,LanePoints.Link proposal,RoadGeometry.Mesh candidate){
    var result=new LinkedHashMap<>(records);Set<UUID> hosts=new HashSet<>();hosts.add(proposal.from().road());
    if(proposal.to().road()!=null)hosts.add(proposal.to().road());
    var old=records.get(edited);var oldLink=old==null?null:LaneTopology.metadata(old).link();
    if(oldLink!=null){hosts.add(oldLink.from().road());if(oldLink.to().road()!=null)hosts.add(oldLink.to().road());}
    derive(result,edited,proposal,hosts,candidate);return result;
  }
  public static void reconcile(Map<UUID,RoadRecord> records){derive(records,null,null,null,null);}
  /** A local edit must not repair or validate reservations elsewhere in the save. */
  static void reconcile(Map<UUID,RoadRecord> records,Set<UUID> hosts){derive(records,null,null,hosts,null);}
  private static void derive(Map<UUID,RoadRecord> records,UUID edited,LanePoints.Link proposal,Set<UUID> hosts,RoadGeometry.Mesh candidate){
    if(hosts!=null){
      // Include adjacent hosts and old reservations so edits/deletes clear the
      // complete previous footprint, including a closure across a road seam.
      var expanded=new HashSet<>(hosts);var removedConnections=new HashSet<UUID>();
      // A live manual expansion owns stable continuation lanes on its sibling slices.
      // Include those owners when the event is edited/deleted, not only on full reload.
      var splitSources=new HashSet<UUID>();for(UUID host:hosts){var r=records.get(host);if(r!=null&&r.fragment()!=null)splitSources.add(r.fragment().source());}
      for(var r:records.values())if(r.fragment()!=null&&splitSources.contains(r.fragment().source()))expanded.add(r.id());
      for(UUID host:hosts){var r=records.get(host);if(r!=null)for(var cut:LaneTopology.metadata(r).cuts())if(!records.containsKey(cut.connection()))removedConnections.add(cut.connection());}
      for(var r:records.values())if(LaneTopology.metadata(r).cuts().stream().anyMatch(c->removedConnections.contains(c.connection())))expanded.add(r.id());
      for(var road:records.values()){
        var link=LaneTopology.metadata(road).link();
        if(link!=null&&(expanded.contains(link.from().road())||expanded.contains(link.to().road())||road.id().equals(edited))){
          expanded.addAll(LaneRoadChain.of(records,link.from()).ids());
          if(link.to().road()!=null)expanded.addAll(LaneRoadChain.of(records,link.to()).ids());
        }
        for(var cut:LaneTopology.metadata(road).cuts())if(cut.connection().equals(edited))expanded.add(road.id());
      }
      if(proposal!=null){expanded.addAll(LaneRoadChain.of(records,proposal.from()).ids());if(proposal.to().road()!=null)expanded.addAll(LaneRoadChain.of(records,proposal.to()).ids());}
      hosts=expanded;
    }
    deriveAdditions(records,edited,proposal,hosts);
    var events=new HashMap<UUID,List<LaneSections.Event>>();
    for(var road:records.values())if(!road.id().equals(edited)){
      var link=LaneTopology.metadata(road).link();if(link==null)continue;
      if(hosts==null||hosts.contains(link.from().road())||hosts.contains(link.to().road()))
        add(events,records,road.id(),link,hosts,link.options().departure()==LanePoints.Departure.TEMPORARY||link.closesTarget()?road.rawMesh():null,road.structures());
    }
    for(var road:records.values())if(hosts==null||hosts.contains(road.id()))for(var point:LaneTopology.metadata(road).points())if(point.mergeLength()>0){
      var event=LaneMerge.event(road.rawMesh(),point);
      events.computeIfAbsent(road.id(),k->new ArrayList<>()).add(event);
    }
    if(proposal!=null)add(events,records,edited,proposal,hosts,candidate,List.of());
    var derivedCuts=deriveCuts(records,events,hosts);
    for(var road:new ArrayList<>(records.values())){
      if(hosts!=null&&!hosts.contains(road.id()))continue;
      var md=LaneTopology.metadata(road);var cuts=new ArrayList<>(derivedCuts.getOrDefault(road.id(),List.of()));
      var access=md.access().stream().filter(r->records.containsKey(r.connection())).toList();
      for(var reservation:access)for(var cut:reservation.cuts()){
        for(var prior:cuts)if(prior.lane()==cut.lane()&&Math.min(Math.max(prior.begin(),prior.end()),Math.max(cut.begin(),cut.end()))-Math.max(Math.min(prior.begin(),prior.end()),Math.min(cut.begin(),cut.end()))>.01)
          throw new IllegalArgumentException("高架出入口与既有车道封闭区间重叠");
        cuts.add(cut);
      }
      if(!access.equals(md.access())){md=md.access(access);road=road.withLanePoints(md);records.put(road.id(),road);}
      if(!cuts.equals(md.cuts()))records.put(road.id(),road.withLanePoints(md.cuts(cuts)));
    }
    for(var road:records.values())if((hosts==null||hosts.contains(road.id()))&&!LaneTopology.metadata(road).additions().isEmpty())LaneAdditions.validate(road.mesh());
    // Materialize now: all checks happen before the first world cell is written.
    for(UUID id:events.keySet()){
      var host=records.get(id);var effective=host.mesh();
      for(var point:LaneTopology.metadata(host).points())if(point.mergeLength()>0)LaneMerge.event(effective,point);
    }
  }
  private record SplitEvent(RoadRecord road,LaneSections.Event event){
    double station(){return event.station()+road.fragment().origin();}
  }
  /** A permanent departure remains a live reservation on every saved sibling
   * slice. Only its point/link has one owner; the steady missing lane does not
   * become a new authored lane or reopen at the newly inserted endpoint. */
  private static Map<UUID,List<LaneSections.Cut>> deriveCuts(Map<UUID,RoadRecord> records,
      Map<UUID,List<LaneSections.Event>> events,Set<UUID> hosts){
    var routed=new HashMap<UUID,List<LaneSections.Event>>();
    events.forEach((id,list)->routed.put(id,new ArrayList<>(list)));
    var groups=new LinkedHashMap<UUID,List<RoadRecord>>();
    for(var road:records.values())if(road.fragment()!=null&&(hosts==null||hosts.contains(road.id())))
      groups.computeIfAbsent(road.fragment().source(),key->new ArrayList<>()).add(road);
    for(var siblings:groups.values()){
      var departures=new ArrayList<SplitEvent>();var replacements=new ArrayList<SplitEvent>();
      for(var road:siblings){
        var list=routed.computeIfAbsent(road.id(),key->new ArrayList<>());
        for(var event:list){
          if(event.kind()==LaneSections.Kind.DEPART)departures.add(new SplitEvent(road,event));
          if(event.kind()==LaneSections.Kind.REPLACE)replacements.add(new SplitEvent(road,event));
        }
        list.removeIf(event->event.kind()==LaneSections.Kind.REPLACE);
      }
      var used=new HashSet<UUID>();
      for(var departure:departures){
        var e=departure.event();SplitEvent replacement=null;double distance=Double.POSITIVE_INFINITY;
        for(var candidate:replacements){
          var r=candidate.event();double next=e.sign()*(candidate.station()-departure.station());
          if(r.lane()==e.lane()&&r.sign()==e.sign()&&next>1e-4&&next<distance){replacement=candidate;distance=next;}
        }
        if(replacement==null)continue;
        var r=replacement.event();
        if(!used.add(r.connection()))throw new IllegalArgumentException("补入车道同时匹配多个分离接头");
        double station=replacement.station()-departure.road().fragment().origin();
        routed.get(departure.road().id()).add(new LaneSections.Event(r.connection(),r.kind(),r.lane(),r.sign(),
            station,r.transition(),r.returnStation(),r.rectangular(),r.underpass()));
      }
      for(var replacement:replacements)if(!used.contains(replacement.event().connection()))
        throw new IllegalArgumentException("补入模式需要上游已有整车道分离或车道点合流缩减形成空位；普通汇入请选并入现有车道");
    }
    var owned=new LinkedHashMap<UUID,List<LaneSections.Cut>>();
    for(var road:records.values())if(hosts==null||hosts.contains(road.id())){
      var values=routed.getOrDefault(road.id(),List.of());
      var cuts=values.isEmpty()?List.<LaneSections.Cut>of():LaneSections.derive(road.rawMesh(),values);
      if(road.fragment()!=null)cuts=cuts.stream().map(c->{
        if(c.temporary()||c.replacement()!=null)return c;
        double end=(c.sign()>0?road.fragment().total()+2*c.transition():-2*c.transition())-road.fragment().origin();
        return new LaneSections.Cut(c.connection(),c.lane(),c.sign(),c.begin(),end,c.transition(),null,
            false,c.arrival(),c.rectangular(),c.underpass());
      }).toList();
      owned.put(road.id(),cuts);
    }
    var result=new HashMap<UUID,List<LaneSections.Cut>>();
    owned.forEach((id,list)->result.put(id,new ArrayList<>(list)));
    for(var siblings:groups.values()){
      // Validate the complete permanent reservation even when its two mouth
      // points are now owned by different road records.
      var global=new ArrayList<LaneSections.Cut>();
      for(var source:siblings)for(var cut:owned.getOrDefault(source.id(),List.of()))if(!cut.temporary()){
        var translated=shift(cut,source.fragment().origin());
        for(var previous:global)if(previous.lane()==cut.lane()&&previous.sign()==cut.sign()
            &&!previous.connection().equals(cut.connection())
            &&Math.min(Math.max(previous.begin(),previous.end()),Math.max(translated.begin(),translated.end()))
                -Math.max(Math.min(previous.begin(),previous.end()),Math.min(translated.begin(),translated.end()))>.01)
          throw new IllegalArgumentException("同一车道空位内重复整车道分离；Y 分叉请使用普通分流（原车道直行）");
        global.add(translated);
      }
      for(var target:siblings){
        var list=result.get(target.id());list.removeIf(c->!c.temporary());
        for(var cut:global){
          var translated=shift(cut,-target.fragment().origin());
          if(Math.max(translated.begin(),translated.end())>0&&Math.min(translated.begin(),translated.end())<target.rawMesh().length())
            list.add(translated);
        }
      }
    }
    // Projection onto an unchanged saved slice can differ by a few ulps. Keep
    // the old exact equation/order so reconciliation does not discard its saved
    // boundary triangles. Actual movement and all definition changes still win.
    result.replaceAll((id,list)->{
      if(records.get(id).fragment()==null)return List.copyOf(list);
      var old=LaneTopology.metadata(records.get(id)).cuts();
      var stable=list.stream().map(c->old.stream().filter(p->sameCut(p,c)
          &&Math.abs(p.begin()-c.begin())<1e-7&&Math.abs(p.end()-c.end())<1e-7).findFirst().orElse(c)).toList();
      return stable.size()==old.size()&&new HashSet<>(stable).equals(new HashSet<>(old))?old:stable;
    });
    return result;
  }
  private static LaneSections.Cut shift(LaneSections.Cut c,double offset){
    return new LaneSections.Cut(c.connection(),c.lane(),c.sign(),c.begin()+offset,c.end()+offset,c.transition(),
        c.replacement(),c.temporary(),c.arrival(),c.rectangular(),c.underpass());
  }
  private static boolean sameCut(LaneSections.Cut a,LaneSections.Cut b){
    return a.connection().equals(b.connection())&&a.lane()==b.lane()&&a.sign()==b.sign()
        &&a.transition()==b.transition()&&Objects.equals(a.replacement(),b.replacement())&&a.temporary()==b.temporary()
        &&a.arrival()==b.arrival()&&a.rectangular()==b.rectangular()&&a.underpass()==b.underpass();
  }
  private static void deriveAdditions(Map<UUID,RoadRecord> records,UUID edited,LanePoints.Link proposal,Set<UUID> hosts){
    var requests=new LinkedHashMap<UUID,LanePoints.Link>();
    for(var road:records.values())if(!road.id().equals(edited)){
      var l=LaneTopology.metadata(road).link();if(l!=null&&l.options().arrival()==LanePoints.Arrival.ADD)requests.put(road.id(),l);
    }
    if(proposal!=null&&proposal.options().arrival()==LanePoints.Arrival.ADD)requests.put(edited,proposal);
    var additions=new HashMap<UUID,List<LaneAdditions.Addition>>();
    for(var entry:requests.entrySet()){
      var link=entry.getValue();if(link.to().road()==null)throw new IllegalArgumentException("新增外侧车道需要选择道路车道点，不能使用路口中心");
      if(hosts!=null&&!hosts.contains(link.to().road()))continue;
      var resolved=LaneRoadChain.of(records,link.to()).at(link.targetOffset());var road=resolved.road();
      var point=resolved.point();var raw=road.rawMesh();var lane=LanePoints.lane(raw,point);
      double station=resolved.station();
      if(station<-.001||station>raw.length()+.001)throw new IllegalArgumentException("新增车道汇入口超出所选道路");
      var list=additions.computeIfAbsent(road.id(),k->new ArrayList<>());
      var previous=LaneTopology.metadata(road).additions().stream().filter(a->a.connection().equals(entry.getKey())).findFirst().orElse(null);
      int slot=previous==null?LaneAdditions.FIRST_NEW_SLOT:previous.slot();
      if(previous==null){var reserved=new HashSet<Integer>();for(var a:LaneTopology.metadata(road).additions())reserved.add(a.slot());for(var a:list)reserved.add(a.slot());while(reserved.contains(slot))slot++;}
      if(slot>31)throw new IllegalArgumentException("此路段新增车道身份数量达到上限，请分段建设");
      list.add(new LaneAdditions.Addition(entry.getKey(),slot,lane.sign(),station,link.options().transition()));
    }
    for(var road:records.values())if(hosts==null||hosts.contains(road.id()))for(var point:LaneTopology.metadata(road).points())if(point.mergeLength()<0){
      var list=additions.computeIfAbsent(road.id(),k->new ArrayList<>());
      var previous=LaneTopology.metadata(road).additions().stream().filter(a->a.connection().equals(point.id())).findFirst().orElse(null);
      int slot=previous==null?LaneAdditions.FIRST_NEW_SLOT:previous.slot();
      if(previous==null){var used=new HashSet<Integer>();for(var a:LaneTopology.metadata(road).additions())used.add(a.slot());for(var a:list)used.add(a.slot());while(used.contains(slot))slot++;}
      if(slot>31)throw new IllegalArgumentException("新增车道身份数量达到上限");
      list.add(LaneExpansion.addition(road.rawMesh(),point,slot));
    }
    // The merge event remains owned by its original point. Its stable downstream
    // cross-section also belongs to every saved slice of that same authored road.
    // Recompute from live events so deleting the event removes all continuations.
    var derived=new HashMap<UUID,List<LaneAdditions.Addition>>();additions.forEach((id,list)->derived.put(id,List.copyOf(list)));
    for(var entry:derived.entrySet()){
      var source=records.get(entry.getKey());if(source.fragment()==null)continue;
      for(var target:records.values())if(!target.id().equals(source.id())&&target.fragment()!=null&&source.fragment().source().equals(target.fragment().source())&&(hosts==null||hosts.contains(target.id())))
        for(var value:entry.getValue()){
          var next=new LaneAdditions.Addition(value.connection(),value.slot(),value.sign(),value.station()+source.fragment().origin()-target.fragment().origin(),value.transition(),value.end()+source.fragment().origin()-target.fragment().origin());
          if(next.fraction(0)>0||next.fraction(target.rawMesh().length())>0){var list=additions.computeIfAbsent(target.id(),k->new ArrayList<>());if(list.stream().noneMatch(a->a.connection().equals(next.connection())))list.add(next);}
        }
    }
    for(var road:new ArrayList<>(records.values()))if(hosts==null||hosts.contains(road.id())){
      List<LaneAdditions.Addition> list=new ArrayList<>(additions.getOrDefault(road.id(),List.of()));var md=LaneTopology.metadata(road);
      for(var access:md.access())if(records.containsKey(access.connection()))list.addAll(access.additions());
      // Reprojecting a point onto a saved slice can turn 35.876999999999995
      // into 35.877. That is the same derived event, not a lane edit. Keep its
      // saved station so withLanePoints does not discard the exact cut surface.
      // Connection/slot/direction/transition changes remain authoritative.
      if(road.fragment()!=null)list=list.stream().map(value->md.additions().stream()
          .filter(old->old.connection().equals(value.connection())&&old.slot()==value.slot()
              &&old.sign()==value.sign()&&old.transition()==value.transition()
              &&Math.abs(old.station()-value.station())<1e-7)
          .findFirst().orElse(value)).toList();
      if(!list.equals(md.additions()))records.put(road.id(),road.withLanePoints(md.additions(list)));
    }
  }
  private static void add(Map<UUID,List<LaneSections.Event>> events,Map<UUID,RoadRecord> all,UUID connection,LanePoints.Link link,Set<UUID> hosts,RoadGeometry.Mesh candidate,List<RoadStructures.Part> parts){
    if(link==null)return;
    if(link.options().separatesLane()&&(hosts==null||hosts.contains(link.from().road()))) {
      if(link.options().departure()==LanePoints.Departure.DETACH)add(events,all,connection,link.from(),LaneSections.Kind.DEPART,0,link.options().transition(),link.rectangularClosure());
      else if(candidate!=null) {
        // No invented chain-wide provisional cut: real ranges require a candidate.
        // Slot-specific collision guards remain active during route search.
        for(int lane=0;lane<link.options().lanes();lane++)LaneRoadChain.of(all,link.from(),lane).reserve(events,connection,candidate,parts,false,0,link.options().transition(),link.rectangularClosure());
      }
    }
    if(candidate!=null&&link.closesTarget()&&(hosts==null||hosts.contains(link.to().road()))){
      for(int lane=0;lane<link.options().lanes();lane++)LaneRoadChain.of(all,link.to(),lane).reserve(events,connection,candidate,parts,true,link.targetOffset(),link.options().transition(),link.rectangularClosure());
    }

    if(link.options().arrival()==LanePoints.Arrival.ADD&&(hosts==null||hosts.contains(link.to().road()))){
      var location=LaneRoadChain.of(all,link.to()).at(link.targetOffset());var road=location.road();
      var addition=LaneAdditions.owned(road.rawMesh(),connection);
      double start=Math.max(0,Math.min(road.rawMesh().length(),addition.station()-addition.sign()*addition.transition()));
      events.computeIfAbsent(road.id(),k->new ArrayList<>()).add(new LaneSections.Event(connection,LaneSections.Kind.ARRIVE,addition.slot(),addition.sign(),addition.station(),addition.transition(),start,true));
    }
    if(link.options().arrival()==LanePoints.Arrival.REPLACE&&(hosts==null||hosts.contains(link.to().road()))){
      if(link.to().road()==null)throw new IllegalArgumentException("路口中心不能作为车道空位补入目标");
      add(events,all,connection,link.to(),LaneSections.Kind.REPLACE,link.targetOffset(),link.options().transition());
    }
  }
  private static void add(Map<UUID,List<LaneSections.Event>> events,Map<UUID,RoadRecord> all,UUID connection,LanePoints.Ref ref,LaneSections.Kind kind,double offset,double transition){
    add(events,all,connection,ref,kind,offset,transition,false);
  }
  private static void add(Map<UUID,List<LaneSections.Event>> events,Map<UUID,RoadRecord> all,UUID connection,LanePoints.Ref ref,LaneSections.Kind kind,double offset,double transition,boolean rectangular){
    var road=all.get(ref.road());if(road==null)throw new IllegalArgumentException("车道接头引用的道路已不存在");
    var point=LaneTopology.point(road,ref.point());var lane=LanePoints.lane(road.rawMesh(),point);
    double station=lane.station()+offset*lane.sign();
    if(station<-.001||station>road.rawMesh().length()+.001)throw new IllegalArgumentException("车道补入位置超出所属路段");
    events.computeIfAbsent(road.id(),key->new ArrayList<>()).add(new LaneSections.Event(connection,kind,point.lane(),lane.sign(),station,transition,Double.NaN,rectangular));
  }
  /** After structures were planned, rederive only temporary hosts touched by this transaction. */
  static boolean needsRestoreRefresh(List<RoadIndex.Built> planning,Collection<UUID> changed){
    Set<UUID> changes=new HashSet<>(changed),hosts=new HashSet<>();var all=new LinkedHashMap<UUID,RoadRecord>();
    for(var b:planning)all.put(b.record.id(),b.record);
    for(var r:all.values()){
      var l=LaneTopology.metadata(r).link();if(l==null)continue;
      if(l.options().departure()==LanePoints.Departure.TEMPORARY&&(changes.contains(r.id())||changes.contains(l.from().road())))hosts.add(l.from().road());
      if(l.closesTarget()&&(changes.contains(r.id())||changes.contains(l.to().road())))hosts.add(l.to().road());
    }
    if(hosts.isEmpty())return false;var staged=new LinkedHashMap<>(all);derive(staged,null,null,hosts,null);
    for(UUID host:all.keySet())if(!LaneTopology.metadata(all.get(host)).cuts().equals(LaneTopology.metadata(staged.get(host)).cuts()))return true;
    return false;
  }
  private LaneCrossSections(){}
}
