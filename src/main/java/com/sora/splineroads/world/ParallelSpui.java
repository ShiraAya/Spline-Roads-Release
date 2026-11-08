package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

/** A service interchange keeps the existing lower intersection and connects its parallel street. */
public final class ParallelSpui {
  private static final String KEY="ParallelJunction";
  public static boolean present(CompoundTag t){return t.contains(KEY);}
  public static boolean active(CompoundTag t){return present(t)&&Interchanges.read(t.getCompound("Options")).preset()==InterchangePlanner.Preset.SPUI;}
  public static int ordinal(int lanes,int rampLanes){
    if(lanes<(rampLanes==2?3:2))throw new IllegalArgumentException("下层每方向至少需要 "+(rampLanes==2?3:2)+" 条车道才能生成"+(rampLanes==2?"双":"单")+"车道匝道");
    // slot() numbers from the median; the requested rule numbers from outside.
    return rampLanes==2?lanes-2:lanes==2?2:lanes-1;
  }
  static boolean capture(RoadData data,CompoundTag t,ServerPlayer player){
    if(t.getLongArray("Points").length!=4||t.contains("Corridor"))return false;
    if(!present(t)){
      long[] points=t.getLongArray("Points");var logical=AutoJunctions.logical(data);CompoundTag found=null;
      for(int axis=0;axis<2;axis++){
        var a=BlockPos.of(points[axis*2]);var b=BlockPos.of(points[axis*2+1]);
        var upper=data.index.roads.values().stream().map(v->v.record).filter(r->r.assembly()==null&&r.junction()==null&&!r.settings().style().ramp()&&(r.a().equals(a)&&r.b().equals(b)||r.a().equals(b)&&r.b().equals(a))).findFirst().orElse(null);if(upper==null)continue;
        for(var center:data.junctions.values())if(center.getBoolean("Auto")&&center.contains("Spec")){
          var at=JunctionCodec.read(center.getCompound("Spec")).center();var q=RoadQueries.horizontal(upper.mesh(),at);
          if(q.horizontalDistance()>128||q.sample().center().y()-at.y()<5)continue;
          var node=BlockPos.of(center.getLong("CenterPos"));var arms=logical.values().stream().filter(r->r.a().equals(node)||r.b().equals(node)).toList();
          BlockPos c=BlockPos.of(points[(1-axis)*2]),d=BlockPos.of(points[(1-axis)*2+1]);
          boolean selected=arms.stream().anyMatch(r->r.a().equals(c)&&r.b().equals(d)||r.a().equals(d)&&r.b().equals(c));
          if(!selected)selected=arms.stream().anyMatch(r->r.a().equals(c)||r.b().equals(c))&&arms.stream().anyMatch(r->r.a().equals(d)||r.b().equals(d));
          if(!selected)continue;
          V along=q.sample().left().left().mul(-1);var lower=arms.stream().filter(r->Math.abs(away(r,node).dot(along))>.9).toList();
          if(lower.size()!=2||away(lower.get(0),node).dot(away(lower.get(1),node))>-.9)continue;
          var candidate=new CompoundTag();candidate.putUUID("Center",center.getUUID("Id"));candidate.putUUID("Upper",upper.id());var ids=new ListTag();lower.forEach(r->ids.add(StringTag.valueOf(r.id().toString())));candidate.put("Lower",ids);
          if(found!=null&&!found.equals(candidate))throw new IllegalArgumentException("选区对应多个下层路口，请选择更靠近目标路口的道路端点");found=candidate;
        }
      }
      if(found==null)return false;t.put(KEY,found);
      var o=Interchanges.read(t.getCompound("Options"));boolean left=data.index.roads.get(found.getUUID("Upper")).record.settings().options().leftTraffic();t.put("Options",Interchanges.write(new InterchangePlanner.Options(InterchangePlanner.Preset.SPUI,left,o.lanes(),32,o.radius(),o.clearance(),o.upper(),o.rampWidth(),o.adjustEndpoints(),false,0)));
    }
    var tag=t.getCompound(KEY);var center=data.junctions.get(tag.getUUID("Center"));if(center==null)throw new IllegalArgumentException("下层路口已改变，请重新选择道路");tag.put("Junction",center.copy());
    tag.putInt("MinimumBuildHeight",data.minimumBuildHeight());
    var upper=data.index.roads.get(tag.getUUID("Upper"));if(upper==null)throw new IllegalArgumentException("上层道路已不存在");
    var hosts=new LinkedHashSet<UUID>();hosts.add(upper.record.id());for(Tag v:tag.getList("Lower",Tag.TAG_STRING))hosts.add(UUID.fromString(v.getAsString()));
    for(UUID id:hosts){var r=data.index.roads.get(id);if(r==null)throw new IllegalArgumentException("下层道路已分割，请重新选择");if(player!=null)RoadData.requireOwner(player,r.record.owner());}
    if(player!=null)RoadData.requireOwner(player,center.getUUID("Owner"));
    double minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=-minX,maxZ=-minX;
    for(UUID id:hosts){var mesh=data.index.roads.get(id).mesh;minX=Math.min(minX,mesh.min().x());minZ=Math.min(minZ,mesh.min().z());maxX=Math.max(maxX,mesh.max().x());maxZ=Math.max(maxZ,mesh.max().z());}
    var options=Interchanges.read(t.getCompound("Options"));var location=JunctionCodec.read(center.getCompound("Spec")).center();
    double padding=options.adjustEndpoints()?Math.max(512,Math.abs(RoadQueries.horizontal(upper.mesh,location).sample().center().y()-location.y())/.055+4*options.transition()+JunctionPlanner.radius(JunctionCodec.read(center.getCompound("Spec")))+240):256;
    var roads=new ListTag();for(var r:data.index.roads.values())if(r.mesh.max().x()>=minX-padding&&r.mesh.min().x()<=maxX+padding&&r.mesh.max().z()>=minZ-padding&&r.mesh.min().z()<=maxZ+padding)roads.add(r.record.header());tag.put("Roads",roads);
    t.put("Main1",RoadRecord.writeSettings(upper.record.settings()));t.put("Main2",RoadRecord.writeSettings(data.index.roads.get(UUID.fromString(tag.getList("Lower",Tag.TAG_STRING).getString(0))).record.settings()));
    t.putString("Kind","interchange");return true;
  }
  private static V away(RoadRecord r,BlockPos center){return (r.a().equals(center)?r.end().position().sub(r.start().position()):r.start().position().sub(r.end().position())).horizontalUnit();}
  private static UUID identity(UUID group,String name){return UUID.nameUUIDFromBytes((group+":parallel-spui:"+name).getBytes(StandardCharsets.UTF_8));}
  private static UUID group(CompoundTag t){return t.hasUUID("Id")?t.getUUID("Id"):UUID.nameUUIDFromBytes(Arrays.toString(t.getLongArray("Points")).getBytes(StandardCharsets.UTF_8));}
  private record Draft(Map<UUID,RoadRecord> context,List<RoadRecord> hosts,List<RoadRecord> ramps,List<UUID> points,V center){}
  private static Map<UUID,RoadRecord> context(CompoundTag t){
    UUID group=group(t);var removed=new HashSet<UUID>();for(int i=0;i<4;i++)removed.add(identity(group,"ramp:"+i));var pointIds=new HashSet<UUID>();for(int i=0;i<8;i++)pointIds.add(identity(group,"point:"+i));var roads=new LinkedHashMap<UUID,RoadRecord>();
    for(Tag v:t.getCompound(KEY).getList("Roads",Tag.TAG_COMPOUND)){var r=RoadRecord.load((CompoundTag)v);if(removed.contains(r.id()))continue;var md=LaneTopology.metadata(r);var clean=md.points(md.points().stream().filter(p->!pointIds.contains(p.id())).toList()).cuts(md.cuts().stream().filter(c->!removed.contains(c.connection())).toList()).openings(md.openings().stream().filter(o->!removed.contains(o.connection())).toList());roads.put(r.id(),clean.equals(md)?r:r.withLanePoints(clean));}
    return roads;
  }
  private static int slot(RoadRecord road,double station,V direction,int ordinal,int lanes){
    var mesh=road.rawMesh();var at=RoadStructures.sample(mesh,station);var layout=RoadProfile.layout(mesh,at);var same=new ArrayList<LanePoints.Lane>();
    for(int i=0;i<RoadProfile.catalog(road.settings()).lanes();i++){var lane=LanePoints.lane(mesh,station,i);if(lane.direction().dot(direction)>.85)same.add(lane);}
    same.sort(Comparator.comparingDouble(l->Math.abs(l.position().sub(at.center()).dot(at.left())-layout.medianCenter())));
    int index=ordinal<0?same.size()-1:ordinal-1;if(index<0||index+lanes>same.size())throw new IllegalArgumentException("所选方向的连续车道数量不足");
    int chosen=same.get(index).index();LanePoints.group(mesh,station,chosen,lanes);return chosen;
  }
  private static double station(RoadRecord road,V desired){var q=RoadQueries.horizontal(road.rawMesh(),desired);if(q.sample().distance()<2||q.sample().distance()>road.rawMesh().length()-2||q.horizontalDistance()>128)throw new IllegalArgumentException("主路长度不足，请将上、下层道路向路口外延长后重新选择");return q.sample().distance();}
  static void requireOutsideDeck(RoadRecord lower,double station,int slot,int lanes,RoadRecord upper){
    // Check the complete innermost selected lane, not merely its arrow/centre.
    var lane=LanePoints.lane(lower.rawMesh(),station,slot);
    var q=RoadQueries.horizontal(upper.rawMesh(),lane.position());
    double extent=Math.abs(lane.direction().left().dot(q.sample().left()))*lane.width()/2;
    if(q.horizontalDistance()-extent<=q.sample().halfWidth()+1e-6)
      throw new IllegalArgumentException("下方道路从外向内第"+(lanes==2?"三":"二")+"车道须完整位于高架投影外，请加宽下方道路或收窄／移动高架");
  }
  private static void requireLandingOutsideDeck(Map<UUID,RoadRecord> all,RoadRecord ramp,RoadRecord upper){
    var link=LaneTopology.metadata(ramp).link();boolean down=link.options().sourceExtra();
    var at=LaneRoadChain.of(all,down?link.to():link.from()).at(down?link.targetOffset():0);
    var mesh=at.road().rawMesh();var selected=LanePoints.lane(mesh,at.point());
    var layout=RoadProfile.layout(mesh,RoadStructures.sample(mesh,selected.station()));
    int side=selected.position().sub(RoadStructures.sample(mesh,selected.station()).center()).dot(RoadStructures.sample(mesh,selected.station()).left())<layout.medianCenter()?-1:1;
    int boundary=at.point().lane()-(link.options().lanes()==1&&layout.lanesOnSide(side)==2?1:0);
    requireOutsideDeck(at.road(),selected.station(),boundary,link.options().lanes(),upper);
  }
  /** Grow only free, level outer ends; all proposed samples remain in the preview. */
  private static void fit(Map<UUID,RoadRecord> all,UUID id,V desired,double margin,BlockPos fixed){
    var road=all.get(id);var mesh=road.rawMesh();var q=RoadQueries.horizontal(mesh,desired);
    double d=q.sample().distance()+desired.sub(q.sample().center()).dot(q.sample().left().left().mul(-1));
    double before=Math.max(0,margin-d),after=Math.max(0,d+margin-mesh.length());
    if(road.a().equals(fixed)&&d>=2)before=0;
    if(road.b().equals(fixed)&&d<=mesh.length()-2)after=0;
    if(before<1e-5&&after<1e-5)return;
    for(boolean first:new boolean[]{true,false})if((first?before:after)>1e-5){
      var pos=first?road.a():road.b();var point=first?road.start():road.end();
      if(pos.equals(fixed)||Math.abs(point.grade())>1e-5)throw new IllegalArgumentException("只可向路口外延长水平主路端点");
      for(var other:all.values())if(!other.id().equals(id)&&((other.a().equals(pos)&&other.start().position().distance(point.position())<.01)||(other.b().equals(pos)&&other.end().position().distance(point.position())<.01)))
        throw new IllegalArgumentException("主路外端点已连接其他道路，请延长相连道路后重新选择");
    }
    all.put(id,extend(road,before,after));
    if(road.fragment()!=null){var f=road.fragment();double shift=Math.max(0,before-f.origin()),total=Math.max(f.total()+shift,f.origin()+shift+mesh.length()+after);
      for(var other:new ArrayList<>(all.values()))if(!other.id().equals(id)&&other.fragment()!=null&&other.fragment().source().equals(f.source())){
        var g=other.fragment();all.put(other.id(),other.fragment(new RoadSlicing.Fragment(g.source(),g.origin()+shift,total,g.connection(),g.applied(),g.geometry(),g.supports())));}}
  }
  private static RoadRecord extend(RoadRecord road,double before,double after){
    var mesh=road.rawMesh();var samples=new ArrayList<Sample>();var a=mesh.first();var b=mesh.last();
    for(double d=before;d>1e-7;d=Math.max(0,d-1))samples.add(new Sample(a.center().add(a.left().left().mul(d)),a.left(),0,a.halfWidth()));
    samples.addAll(mesh.samples());
    for(double d=Math.min(1,after);d>0;d=Math.min(after,d+1)){samples.add(new Sample(b.center().add(b.left().left().mul(-d)),b.left(),0,b.halfWidth()));if(d>=after)break;}
    var settings=road.settings();var ends=settings.options().ends();var decor=settings.options().streetscape();var sides=decor.sides();
    // Terrain-derived intervals use stations on this ribbon. Moving its origin
    // must move those intervals too; a level free-end extension inherits the
    // endpoint section until the normal world terrain checks confirm it.
    // Otherwise AUTO briefly turns the extended bridge into a ground median,
    // places every lane point on the wrong axis, then reroutes all four ramps
    // after structure planning resolves the actual bridge again.
    var grown=new RoadStreetscape.SideStructures(grow(sides.negativeRaised(),mesh.length(),before,after),grow(sides.positiveRaised(),mesh.length(),before,after),grow(sides.negativeCut(),mesh.length(),before,after),grow(sides.positiveCut(),mesh.length(),before,after));
    var crossings=decor.crossings().stream().map(c->new RoadsideBypass.Span(c.side(),c.start()+before,c.from()+before,c.to()+before,c.end()+before,c.delta())).toList();
    decor=decor.sideStructures(grown).tunnels(grow(decor.tunnels(),mesh.length(),before,after)).crossings(crossings);
    settings=settings.options(settings.options().streetscape(decor).ends(ends.paintPhase(ends.paintPhase()-before)));
    if(before>0){var md=settings.options().lanePoints();
      var cuts=md.cuts().stream().map(c->new LaneSections.Cut(c.connection(),c.lane(),c.sign(),c.begin()+before,c.end()+before,c.transition(),c.replacement(),c.temporary(),c.arrival(),c.rectangular(),c.underpass())).toList();
      var added=md.additions().stream().map(a0->new LaneAdditions.Addition(a0.connection(),a0.slot(),a0.sign(),a0.station()+before,a0.transition())).toList();
      settings=settings.options(settings.options().lanePoints(md.cuts(cuts).additions(added)));
    }
    var shape=RoadRibbon.mesh(samples,settings);var start=before>0?RoadRibbon.start(shape):road.start();var end=after>0?RoadRibbon.end(shape):road.end();
    var fragment=road.fragment();if(fragment!=null){double shift=Math.max(0,before-fragment.origin());var patches=fragment.geometry().patches().stream().map(p->new RoadSlicing.Patch(p.from()+before,p.to()+before,p.strips())).toList();
      fragment=new RoadSlicing.Fragment(fragment.source(),fragment.origin()+shift-before,Math.max(fragment.total()+shift,fragment.origin()+shift+mesh.length()+after),fragment.connection(),fragment.applied(),new RoadSlicing.Geometry(patches),fragment.supports());}
    return new RoadRecord(road.id(),road.owner(),before>0?RampJunctions.at(start.position()):road.a(),after>0?RampJunctions.at(end.position()):road.b(),start,end,settings,road.automatic(),road.clearance(),List.of(),road.endCaps(),road.buildVersion(),road.assembly(),shape.samples(),road.furniturePhase(),road.junction(),road.toll(),fragment);
  }
  private static List<RoadStreetscape.Span> grow(List<RoadStreetscape.Span> spans,double length,double before,double after){
    return spans.stream().map(s->new RoadStreetscape.Span(s.from()<=1e-6?0:s.from()+before,s.to()>=length-1e-6?length+before+after:s.to()+before)).toList();
  }
  private static LanePoints.Point point(Map<UUID,RoadRecord> all,UUID road,UUID id,double station,int slot){var r=all.get(road);var p=LanePoints.point(id,LanePoints.Origin.MANUAL,r.rawMesh(),station,slot);var points=new ArrayList<>(LaneTopology.metadata(r).points());points.add(p);all.put(road,r.withLanePoints(LaneTopology.metadata(r).points(points)));return p;}
  private static double setback(JunctionSpec spec,RoadRecord lower){
    // The existing junction is a fixed-height end of the bicycle corridor.
    // Reserve the complete quintic descent before placing a motor-lane mouth;
    // searching an upper landing cannot move this lower departure away from it.
    double cycle=RoadsideBypass.eligible(lower.rawMesh())
        ?1.875*(RoadsideBypass.HEADROOM+RoadsideBypass.SLAB+RoadsideBypass.ROOF)/RoadsideBypass.MAX_GRADE:0;
    return Math.max(64,JunctionPlanner.radius(spec)+48+cycle);
  }
  private static double continuation(RoadRecord lower,InterchangePlanner.Options options){
    // A motor closure's warning margin is not also the bicycle ascent. Keep
    // enough free road beyond it for the complete smooth return to road level.
    double cycle=RoadsideBypass.eligible(lower.rawMesh())
        ?1.875*(RoadsideBypass.HEADROOM+RoadsideBypass.SLAB+RoadsideBypass.ROOF)/RoadsideBypass.MAX_GRADE+8:0;
    return Math.max(64,options.transition())+cycle;
  }
  private static Draft draft(CompoundTag t){
    synchronized(DRAFTS){var cached=DRAFTS.get(t);if(cached!=null)return cached;}
    var result=draftUncached(t,null);
    synchronized(DRAFTS){DRAFTS.put(t.copy(),result);}
    return result;
  }
  // Preview geometry depends only on the captured descriptor. Cache its four
  // routes for repeated previews; construction also checks the current terrain.
  private static final Map<CompoundTag,Draft> DRAFTS=new LinkedHashMap<>(4,.75f,true){
    protected boolean removeEldestEntry(Map.Entry<CompoundTag,Draft> e){return size()>2;}
  };
  private static Draft draftUncached(CompoundTag t,RoadData data){
    var o=Interchanges.read(t.getCompound("Options"));if(o.preset()!=InterchangePlanner.Preset.SPUI)throw new IllegalArgumentException("已有下层平交路口的三道路布局请选择单点式菱形");
    var all=context(t);var tag=t.getCompound(KEY);UUID group=group(t);var upper=all.get(tag.getUUID("Upper"));var spec=JunctionCodec.read(tag.getCompound("Junction").getCompound("Spec"));V center=spec.center();BlockPos node=BlockPos.of(tag.getCompound("Junction").getLong("CenterPos"));
    if(o.leftTraffic()!=upper.settings().options().leftTraffic())throw new IllegalArgumentException("立交行驶方向须与已有上层道路一致");
    for(Tag v:tag.getList("Lower",Tag.TAG_STRING)){
      var mesh=all.get(UUID.fromString(v.getAsString())).rawMesh();var layout=RoadProfile.layout(mesh,mesh.first());
      ordinal(layout.lanesOnSide(-1),o.lanes());ordinal(layout.lanesOnSide(1),o.lanes());
    }
    if(o.adjustEndpoints()){
      double height=RoadQueries.horizontal(upper.mesh(),center).sample().center().y()-center.y();
      double run=Math.max(160,height/.055+2*o.transition()+96),margin=Math.max(64,2*o.transition());
      for(Tag v:tag.getList("Lower",Tag.TAG_STRING)){UUID id=UUID.fromString(v.getAsString());V outward=away(all.get(id),node);double setback=setback(spec,all.get(id));
        fit(all,id,center.add(outward.mul(setback)),Math.max(margin,continuation(all.get(id),o)),node);
        fit(all,upper.id(),center.add(outward.mul(setback+run)),margin,null);
      }
      upper=all.get(upper.id());
      // Lower free ends retain the local merge/diverge margin above.

    }
    var hosts=new LinkedHashSet<UUID>();hosts.add(upper.id());var links=new ArrayList<LanePoints.Link>();var pointIds=new ArrayList<UUID>();int movement=0;
    for(Tag v:tag.getList("Lower",Tag.TAG_STRING)){
      var lower=all.get(UUID.fromString(v.getAsString()));hosts.add(lower.id());V away=away(lower,node);
      if(lower.settings().options().leftTraffic()!=o.leftTraffic())throw new IllegalArgumentException("上、下层道路的左右行驶设置必须一致");
      double setback=setback(spec,lower);double local=station(lower,center.add(away.mul(setback)));
      for(boolean down:new boolean[]{true,false}){
        V direction=away.mul(down?-1:1);int count=0;for(int i=0;i<RoadProfile.catalog(lower.settings()).lanes();i++)if(LanePoints.lane(lower.rawMesh(),local,i).direction().dot(direction)>.85)count++;
        int chosen=slot(lower,local,direction,ordinal(count,o.lanes()),o.lanes());
        double height=RoadQueries.horizontal(upper.mesh(),center).sample().center().y()-center.y();double run=Math.max(160,height/.055+2*o.transition()+96);
        double high=station(upper,center.add(away.mul(setback+run)));int outer=slot(upper,high,direction,-1,1);
        int boundary=slot(lower,local,direction,count-(o.lanes()==2?2:1),1);
        requireOutsideDeck(lower,local,boundary,o.lanes(),upper);
        UUID loId=identity(group,"point:"+(movement*2)),hiId=identity(group,"point:"+(movement*2+1));pointIds.add(loId);pointIds.add(hiId);
        point(all,lower.id(),loId,local,chosen);point(all,upper.id(),hiId,high,outer);
        var lowRef=LanePoints.Ref.lane(lower.id(),loId);var highRef=LanePoints.Ref.lane(upper.id(),hiId);
        var options=new LanePoints.Options(LanePoints.Path.AUTO,down?LanePoints.Departure.EXTRA:LanePoints.Departure.TEMPORARY,down?LanePoints.Arrival.MERGE:LanePoints.Arrival.EXTRA,o.radius(),o.transition(),LanePoints.Elevation.AUTO,LanePoints.Landing.FLEXIBLE,false,false,o.lanes());
        links.add(new LanePoints.Link(down?highRef:lowRef,down?lowRef:highRef,options,null).withProtectedMerge().withRectangularClosure());movement++;
      }
    }
    var ramps=new ArrayList<RoadRecord>();for(int i=0;i<links.size();i++){var r=LaneRamps.aboveFloor(data,all,identity(group,"ramp:"+i),upper.owner(),links.get(i),center.y());r=r.alignment(group,r.mesh());ramps.add(r);all.put(r.id(),r);links.set(i,LaneTopology.metadata(r).link());}
    boolean hostsExtended=false;
    if(o.adjustEndpoints())for(Tag v:tag.getList("Lower",Tag.TAG_STRING)){
      UUID id=UUID.fromString(v.getAsString());var lower=all.get(id);V outward=away(lower,node);
      double end=0;
      for(var ramp:ramps){var mesh=ramp.mesh();double depth=mesh.settings().thickness()+RoadInfrastructure.girderDepth(mesh);
        for(var sample:mesh.samples()){
          V delta=sample.center().sub(center);double along=delta.dot(outward),across=Math.abs(delta.dot(outward.left()));
          double top=RoadQueries.horizontal(lower.rawMesh(),sample.center()).sample().center().y();
          if(along>0&&across<lower.rawMesh().first().halfWidth()+sample.halfWidth()
              &&sample.center().y()-depth-top<RoadStructureClearance.headroom(lower.rawMesh())+.25)end=Math.max(end,along);
        }
      }
      // Continue past the real merge/closure, not the upper road's remote end.
      fit(all,id,center.add(outward.mul(end)),continuation(lower,o),node);
      hostsExtended|=all.get(id).rawMesh().length()>lower.rawMesh().length()+1e-5;
    }
    LaneCrossSections.reconcile(all);
    if(o.adjustEndpoints()){
      for(int pass=0;pass<8;pass++){
        boolean extended=hostsExtended;hostsExtended=false;
        for(Tag v:tag.getList("Lower",Tag.TAG_STRING)){
          UUID id=UUID.fromString(v.getAsString());var lower=all.get(id);V outward=away(lower,node);double end=0;
          // The closure includes the actual swept deck and its safety advance.
          // Keep a usable continuation beyond that boundary, including the warning
          // approach, rather than growing to an unrelated upper-road endpoint.
          for(var cut:LaneTopology.metadata(lower).cuts())for(double d:new double[]{cut.begin(),cut.end()})
            end=Math.max(end,RoadStructures.sample(lower.rawMesh(),d).center().sub(center).dot(outward));
          fit(all,id,center.add(outward.mul(end)),continuation(lower,o),node);
          extended|=all.get(id).rawMesh().length()>lower.rawMesh().length()+1e-5;
        }
        if(!extended)break;
        LaneCrossSections.reconcile(all);
        // Extension introduces real live lanes where the first route saw a free
        // endpoint. Revalidate/refit against the final hosts before publishing the
        // preview; otherwise only the later world transaction discovers the clash.
        for(int i=0;i<ramps.size();i++){
          var old=ramps.get(i);var r=LaneRamps.aboveFloor(data,all,old.id(),old.owner(),links.get(i),center.y());
          r=r.alignment(group,r.mesh());ramps.set(i,r);all.put(r.id(),r);links.set(i,LaneTopology.metadata(r).link());
        }
        LaneCrossSections.reconcile(all);
        if(pass==7)throw new IllegalArgumentException("下层道路延长与匝道落点未能稳定，请增加过渡长度");
      }
    }
    // The original intersection stays fixed. Report an impossible bicycle floor
    // in the preview as well, before an otherwise valid motor ramp is offered.
    double minimum=data!=null?data.minimumBuildHeight():tag.contains("MinimumBuildHeight")?tag.getInt("MinimumBuildHeight"):Integer.MIN_VALUE;
    var checked=new HashSet<UUID>();
    for(var road:all.values())if(RoadCycleChain.eligible(road)&&!checked.contains(road.id())&&ramps.stream().anyMatch(r->RoadIndex.overlapXZ(road.mesh(),r.mesh(),3))){
      var chain=RoadCycleChain.of(all,road.id());var neighbors=new ArrayList<RoadsideBypass.Neighbor>();
      for(var other:all.values())if((other.settings().style().ramp()||other.railway()||LaneTopology.metadata(other).link()!=null)&&RoadIndex.overlapXZ(chain.mesh(),other.mesh(),3))neighbors.add(new RoadsideBypass.Neighbor(other.mesh(),RoadAutoTunnels.roofRegions(other.structures())));
      var spans=RoadsideBypass.plan(chain.mesh(),neighbors);RoadsideBypass.validateFloor(chain.mesh(),spans,minimum);
      for(var leg:chain.legs())checked.add(leg.road().id());
    }
    for(var ramp:ramps)requireLandingOutsideDeck(all,ramp,all.get(upper.id()));
    var original=context(t);for(var r:all.values())if(original.containsKey(r.id())&&!Objects.equals(r.fragment(),original.get(r.id()).fragment()))hosts.add(r.id());
    return new Draft(all,hosts.stream().map(all::get).toList(),List.copyOf(ramps),List.copyOf(pointIds),center);
  }
  public static InterchangePlanner.Plan plan(CompoundTag t){
    var d=draft(t);var legs=new ArrayList<InterchangePlanner.Leg>();for(var r:d.hosts())legs.add(new InterchangePlanner.Leg("既有主路",0,0,r.mesh()));for(int i=0;i<d.ramps().size();i++)legs.add(new InterchangePlanner.Leg(i%2==0?"上层汇出并入下层":"下层保留车道分离汇入上层",i,i+1,d.ramps().get(i).mesh()));
    var junction=t.getCompound(KEY).getCompound("Junction");var node=BlockPos.of(junction.getLong("CenterPos"));var hosts=d.hosts().stream().map(RoadRecord::id).collect(java.util.stream.Collectors.toSet());
    for(var r:d.context().values()){
      if(r.junction()!=null&&t.getCompound(KEY).getUUID("Center").equals(r.assembly()))legs.add(new InterchangePlanner.Leg("既有下层路口",-1,-1,r.mesh(),r.junction()));
      else if(r.junction()==null&&!hosts.contains(r.id())&&(r.a().equals(node)||r.b().equals(node)))legs.add(new InterchangePlanner.Leg("既有横向道路",-1,-1,r.mesh()));
    }
    var original=context(t);var anchors=new ArrayList<Node>();for(Tag v:t.getList("Nodes",Tag.TAG_COMPOUND)){
      var n=RoadRecord.readNode((CompoundTag)v);for(var host:d.hosts()){var old=original.get(host.id());
        if(n.position().distance(old.start().position())<.01){n=host.start();break;}
        if(n.position().distance(old.end().position())<.01){n=host.end();break;}}
      anchors.add(n);
    }
    return new InterchangePlanner.Plan(legs,4,d.ramps().stream().mapToDouble(r->RoadRibbon.minRadius(r.mesh())).min().orElse(0),d.ramps().stream().mapToDouble(r->r.mesh().max().y()).max().orElse(d.center().y()),d.center(),anchors);
  }
  static String build(ServerLevel level,ServerPlayer player,CompoundTag t){
    // A geometry-only preview cannot choose a legal below-ground route. Use the
    // same earth constraints as individual connectors before any world writes.
    var data=RoadData.get(level);var d=draftUncached(t,data);UUID group=group(t);var built=new ArrayList<RoadIndex.Built>();var removed=new HashSet<UUID>();var selected=new HashSet<BlockPos>();
    for(var r:d.hosts()){built.add(new RoadIndex.Built(r));removed.add(r.id());selected.add(r.a());selected.add(r.b());}
    for(var r:d.ramps()){built.add(new RoadIndex.Built(r));if(data.index.roads.containsKey(r.id()))removed.add(r.id());selected.add(r.a());selected.add(r.b());}
    var moves=new ArrayList<RoadData.NodeMove>();var logical=new LinkedHashMap<UUID,RoadRecord>();var positions=new HashMap<Long,Long>();
    for(var host:d.hosts()){var old=data.index.roads.get(host.id()).record;double before=0,after=0;
      for(boolean first:new boolean[]{true,false}){var from=first?old.a():old.b();var to=first?host.a():host.b();if(from.equals(to))continue;
        var source=first?old.start():old.end();var target=first?host.start():host.end();var marker=RoadData.requireNode(level,from,player);
        moves.add(new RoadData.NodeMove(from,to,target,marker.saveWithoutMetadata()));selected.add(from);positions.put(from.asLong(),to.asLong());
        if(first)before=source.position().distance(target.position());else after=source.position().distance(target.position());
      }
      if(data.streets.containsKey(host.id())){var full=data.streets.get(host.id());if(before>0||after>0)full=extend(full,before,after);
        if(full.fragment()!=null&&host.fragment()!=null){var f=full.fragment();var live=host.fragment();double trim=RoadQueries.horizontal(full.rawMesh(),host.rawMesh().first().center()).sample().distance();full=full.fragment(new RoadSlicing.Fragment(f.source(),Math.max(0,live.origin()-trim),live.total(),f.connection(),f.applied(),f.geometry(),f.supports()));}logical.put(host.id(),full);}
    }
    data.replaceAssembly(level,player,built,removed,selected,moves);
    for(var entry:logical.entrySet())data.streets.put(entry.getKey(),entry.getValue().withLanePoints(LaneTopology.metadata(data.index.roads.get(entry.getKey()).record)));
    t=t.copy();Interchanges.clearRemovedDesign(t);long[] points=t.getLongArray("Points");for(int i=0;i<points.length;i++)points[i]=positions.getOrDefault(points[i],points[i]);t.putLongArray("Points",points);
    var nodes=new ListTag();for(Tag v:t.getList("Nodes",Tag.TAG_COMPOUND)){var n=RoadRecord.readNode((CompoundTag)v);var move=moves.stream().filter(m->m.from().equals(RampJunctions.at(n.position()))).findFirst();nodes.add(RoadRecord.writeNode(move.isPresent()?move.get().target():n));}t.put("Nodes",nodes);
    t.putUUID("Id",group);t.putUUID("Owner",d.hosts().get(0).owner());t.getCompound(KEY).remove("Roads");data.interchanges.put(group,t);data.setDirty();return "单点菱形已连接已有下层路口，四条匝道按指定车道生成";
  }
  static void remove(ServerLevel level,ServerPlayer player,CompoundTag saved){
    var data=RoadData.get(level);capture(data,saved,player);UUID group=saved.getUUID("Id");var cleaned=context(saved);var built=new ArrayList<RoadIndex.Built>();var removed=new HashSet<UUID>();var selected=new HashSet<BlockPos>();
    for(var old:data.index.roads.values()){var r=cleaned.get(old.record.id());if(r==null){if(group.equals(old.record.assembly()))removed.add(old.record.id());}else if(!r.settings().options().lanePoints().equals(LaneTopology.metadata(old.record))){built.add(new RoadIndex.Built(r));removed.add(r.id());selected.add(r.a());selected.add(r.b());}}
    data.replaceAssembly(level,player,built,removed,selected);data.interchanges.remove(group);data.setDirty();
  }
  private ParallelSpui(){}
}
