package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import java.util.*;
import java.util.function.Function;

/** Operation-scoped continuous ranges; original road IDs and real seams are never merged. */
public final class ContinuousRoads {
  static final String MAIN="连续主路:", DONOR="共享端点邻路:";
  record Source(int axis,RoadRecord road){}
  static List<Source> sources(CompoundTag t){var out=new ArrayList<Source>();for(Tag tag:t.getList("ContinuousSources",Tag.TAG_COMPOUND)){var q=(CompoundTag)tag;out.add(new Source(q.getInt("Axis"),RoadRecord.load(q.getCompound("Road"))));}return out;}
  static void capture(RoadData data,CompoundTag t){
    if(t.getLongArray("Points").length>6)return;
    var points=t.getLongArray("Points");var limits=t.getList("Nodes",Tag.TAG_COMPOUND).copy();var list=new ListTag();
    for(int axis=0;axis*2+1<points.length;axis++){
      BlockPos a=BlockPos.of(points[axis*2]),b=BlockPos.of(points[axis*2+1]);var chain=Interchanges.mainChain(data,a,b);if(chain.isEmpty()||chain.stream().anyMatch(r->!RoadContinuity.eligible(r.mesh())))continue;
      if(chain.size()>1)for(int i=1;i<chain.size();i++){var r=chain.get(i-1);var q=chain.get(i);BlockPos shared=r.a().equals(q.a())||r.a().equals(q.b())?r.a():r.b();if(AutoJunctions.center(data,shared)!=null||!RoadContinuity.compatible(r.mesh(),q.mesh(),r.a().equals(shared)?r.start().position():r.end().position()))throw new IllegalArgumentException("连续主路须共线、水平、分方向车道数相同，且中间端点不属于路口");}
      var extended=new ArrayList<>(chain);Set<UUID> seen=new HashSet<>();chain.forEach(r->seen.add(r.id()));
      for(boolean first:new boolean[]{true,false}){
        BlockPos at=first?a:b;RoadRecord previous=first?chain.get(0):chain.get(chain.size()-1);
        while(extended.size()<256){
          if(AutoJunctions.center(data,at)!=null||data.index.atNode(at).size()!=2)break;
          RoadRecord neighbor=null;for(UUID id:data.index.atNode(at))if(!seen.contains(id)){var r=data.index.roads.get(id).record;if(r.assembly()==null&&r.junction()==null&&RoadContinuity.compatible(previous.mesh(),r.mesh(),previous.a().equals(at)?previous.start().position():previous.end().position()))neighbor=r;}
          if(neighbor==null)break;seen.add(neighbor.id());extended.add(neighbor);at=neighbor.a().equals(at)?neighbor.b():neighbor.a();previous=neighbor;
        }
        Node node=previous.a().equals(at)?previous.start():previous.end();limits.set(axis*2+(first?0:1),RoadRecord.writeNode(node));
      }
      for(var road:extended){var q=new CompoundTag();q.putInt("Axis",axis);q.put("Road",road.header());list.add(q);}
    }
    t.put("ContinuousSources",list);t.put("ContinuousLimits",limits);
  }
  public static Plan plan(CompoundTag t,Function<CompoundTag,Plan> base){
    var sources=sources(t);if(sources.isEmpty())return base.apply(t);
    Plan p;boolean adjust=Interchanges.read(t.getCompound("Options")).adjustEndpoints();
    try{p=base.apply(t);}catch(IllegalArgumentException failure){
      if(adjust)throw failure;
      var original=t.getList("Nodes",Tag.TAG_COMPOUND);var limits=t.getList("ContinuousLimits",Tag.TAG_COMPOUND);if(limits.size()!=original.size()||limits.equals(original))throw failure;
      CompoundTag candidate=t.copy();candidate.put("Nodes",limits.copy());candidate.remove("HostAxes");
      p=base.apply(candidate);double lo=0,hi=1;
      for(int iteration=0;iteration<10;iteration++){double f=(lo+hi)/2;var nodes=new ListTag();for(int i=0;i<original.size();i++){Node a=RoadRecord.readNode(original.getCompound(i)),b=RoadRecord.readNode(limits.getCompound(i));nodes.add(RoadRecord.writeNode(new Node(a.position().mul(1-f).add(b.position().mul(f)),a.yaw(),a.grade())));}candidate.put("Nodes",nodes);
        try{var trial=base.apply(candidate);p=trial;hi=f;}catch(IllegalArgumentException e){lo=f;}}
    }
    return segment(t,p,sources);
  }
  private static Plan segment(CompoundTag t,Plan plan,List<Source> sources){
    var legs=new ArrayList<Leg>();var points=t.getLongArray("Points");boolean adjust=Interchanges.read(t.getCompound("Options")).adjustEndpoints();
    Set<UUID> used=new HashSet<>();
    for(var leg:plan.legs()){
      int axis=leg.name().equals("主路 1")||leg.name().equals("主路 AB")?0:leg.name().equals("主路 2")||leg.name().equals("第二层 CD")?1:leg.name().equals("主路 3")?2:-1;
      if(axis<0||sources.stream().noneMatch(s->s.axis()==axis)){legs.add(leg);continue;}
      Mesh generated=leg.mesh();V origin=generated.first().center(),dir=generated.last().center().sub(origin).horizontalUnit();double total=generated.last().center().sub(origin).dot(dir);
      var axisSources=sources.stream().filter(s->s.axis()==axis&&!ContinuousSections.detached(t,s.road().id())).toList();
      // Older saves may still identify a fitted arm by its former source endpoints.
      // Connect only the two outermost source ends to uncovered generated pavement.
      // Internal seams and donors already extending beyond the plan stay pinned.
      var terminals=new HashMap<BlockPos,V>();
      if(!t.contains("Corridor")&&axis*2+1<points.length){
        for(boolean first:new boolean[]{true,false}){
          double bound=first?Double.POSITIVE_INFINITY:Double.NEGATIVE_INFINITY;
          BlockPos outer=null;
          for(var item:axisSources)for(boolean start:new boolean[]{true,false}){
            var r=item.road();var end=start?r.a():r.b();
            V position=start?r.start().position():r.end().position();
            if(adjust)for(int i=2*axis;i<=2*axis+1;i++)if(end.asLong()==points[i])position=plan.anchors().get(i).position();
            double station=position.sub(origin).dot(dir);
            if(first?station<bound:station>bound){bound=station;outer=end;}
          }
          if(outer!=null&&(first?bound>1e-5:bound<total-1e-5))
            terminals.put(outer,first?origin:generated.last().center());
        }
      }
      for(var source:axisSources){var r=source.road();V a=r.start().position(),b=r.end().position();
        if(adjust)for(int i=0;i<points.length;i++){if(r.a().asLong()==points[i])a=plan.anchors().get(i).position();if(r.b().asLong()==points[i])b=plan.anchors().get(i).position();}
        a=terminals.getOrDefault(r.a(),a);b=terminals.getOrDefault(r.b(),b);
        double sa=a.sub(origin).dot(dir),sb=b.sub(origin).dot(dir),low=Math.min(sa,sb),high=Math.max(sa,sb);
        boolean occupies=Math.min(high,total)-Math.max(low,0)>1e-5;
        boolean moved=a.distance(r.start().position())>1e-6||b.distance(r.end().position())>1e-6;
        boolean previouslyOccupied=t.getList("OccupiedRoads",Tag.TAG_COMPOUND).stream().anyMatch(tag->((CompoundTag)tag).getUUID("Road").equals(r.id()));
        if(!occupies&&!moved&&!previouslyOccupied)continue;
        if(a.sub(b).horizontalLength()<2||Math.signum(r.end().position().sub(r.start().position()).dot(dir))!=Math.signum(sb-sa))throw new IllegalArgumentException("共享端点调整后相邻道路不足两格或发生折返");
        if(moved&&(Math.abs(a.y()-b.y())>RoadContinuity.POSITION_EPS||!RoadContinuity.eligible(r.mesh())))throw new IllegalArgumentException("共享端点只能沿符合条件的水平直线调整；此次高度变化会使邻路起坡");
        var samples=new ArrayList<Sample>();TreeSet<Double> distances=new TreeSet<>();double length=b.sub(a).horizontalLength();for(int i=0,n=(int)Math.ceil(length/.5);i<=n;i++)distances.add(length*i/n);
        for(double boundary:new double[]{0,total}){
          double distance=(boundary-sa)/(sb-sa)*length;
          // Projection roundoff can put a boundary a few ulps inside an endpoint or
          // regular sample. Keep the exact endpoints and one sample per boundary.
          if(distance<=1e-6||distance>=length-1e-6)continue;
          Double before=distances.floor(distance),after=distances.ceiling(distance);
          if(before!=null&&distance-before<1e-6)distances.remove(before);
          if(after!=null&&after-distance<1e-6)distances.remove(after);
          distances.add(distance);
        }
        for(double distance:distances){double f=distance/length;V pos=a.mul(1-f).add(b.mul(f));double at=pos.sub(origin).dot(dir);var old=RoadStructures.sample(r.mesh(),Math.min(r.mesh().length(),Math.max(0,RoadAttachments.station(r.mesh(),pos))));double width=old.halfWidth();
          if(at>=-1e-6&&at<=total+1e-6){var q=RoadQueries.horizontal(generated,pos).sample();pos=new V(pos.x(),q.center().y(),pos.z());width=q.halfWidth();}
          samples.add(new Sample(pos,b.sub(a).horizontalUnit().left(),0,Math.max(.25,width)));}
        // The generated cross section is absolute: source meshes already include grid padding.
        // Apply the selected mainline furniture to occupied roads, while donors retain their settings.
        Settings settings=r.settings();
        if(occupies){
          var selected=generated.settings();var original=settings.options();
          var chosen=selected.options().attachments(original.attachments()).lanePoints(original.lanePoints())
              .ends(original.ends()).infrastructure(original.infrastructure().gantry(selected.options().infrastructure().gantry()));
          if(sb<sa){var counts=RoadLanes.counts(selected);chosen=chosen.lanes(new RoadLanes.Counts(counts.reverse(),counts.forward()));
            var rail=chosen.outerRail();if(rail==RoadProfile.OuterRail.SOUND_LEFT)chosen=chosen.outerRail(RoadProfile.OuterRail.SOUND_RIGHT);else if(rail==RoadProfile.OuterRail.SOUND_RIGHT)chosen=chosen.outerRail(RoadProfile.OuterRail.SOUND_LEFT);}
          settings=new Settings(settings.mode(),selected.style(),selected.width(),selected.thickness(),settings.tension(),settings.arcDegrees(),settings.startWidth(),settings.endWidth(),settings.structure(),settings.taperVersion(),settings.rampTurn(),chosen);
        }
        var metadata=settings.options().attachments();var fixed=metadata.points().stream().map(p->new RoadAttachments.Point(p.id(),p.position(),p.position(),false,p.origin(),p.elevated())).toList();settings=settings.options(settings.options().attachments(metadata.points(fixed)));
        Mesh mesh=RoadRibbon.mesh(samples,settings);
        if(occupies){double first=RoadAttachments.station(mesh,origin),last=RoadAttachments.station(mesh,generated.last().center());ContinuousSections.append(legs,leg,r,mesh,Math.min(first,last),Math.max(first,last),moved);}
        else legs.add(new Leg(DONOR+r.id(),leg.from(),leg.to(),mesh));used.add(r.id());
      }
    }
    return new Plan(List.copyOf(legs),plan.movements(),plan.minRadius(),plan.highest(),plan.center(),plan.anchors());
  }
  /** Retain the pre-assembly cross section; rebuilding must not add auxiliary width twice. */
  static RoadRecord updatedSource(RoadRecord original,RoadRecord current){
    Settings settings=original.settings().options(original.settings().options().attachments(current.settings().options().attachments()));
    List<Sample> alignment=original.alignment();
    if(!original.start().position().equals(current.start().position())||!original.end().position().equals(current.end().position())){
      Mesh old=original.mesh();var samples=new ArrayList<Sample>();V start=current.start().position(),end=current.end().position(),left=end.sub(start).horizontalUnit().left();
      for(var sample:old.samples()){double f=sample.distance()/old.length();samples.add(new Sample(start.mul(1-f).add(end.mul(f)),left,0,sample.halfWidth()));}
      alignment=RoadRibbon.mesh(samples,settings).samples();
    }
    return new RoadRecord(original.id(),original.owner(),current.a(),current.b(),current.start(),current.end(),settings,original.automatic(),original.clearance(),List.of(),original.endCaps(),original.buildVersion(),null,alignment,original.furniturePhase(),null,null,original.fragment());
  }
  static UUID sourceId(String name){return ContinuousSections.sourceId(name);}
  static boolean donor(String name){return name.startsWith(DONOR);}
  static RoadRecord source(CompoundTag t,UUID id){return sources(t).stream().map(Source::road).filter(r->r.id().equals(id)).findFirst().orElseThrow();}
  static boolean virtual(CompoundTag t,int i,Node target){
    if(Interchanges.read(t.getCompound("Options")).adjustEndpoints())return false;
    var nodes=HostAxes.nodes(t);V real=nodes[i].position();var points=t.getLongArray("Points");
    for(var source:sources(t)){var r=source.road();if(i<points.length&&r.a().asLong()==points[i]){real=r.start().position();break;}if(i<points.length&&r.b().asLong()==points[i]){real=r.end().position();break;}}
    var limits=t.getList("ContinuousLimits",Tag.TAG_COMPOUND);if(i>=limits.size())return false;V a=real,b=RoadRecord.readNode(limits.getCompound(i)).position(),d=b.sub(a);if(d.horizontalLength()<1e-6)return false;double at=target.position().sub(a).dot(d.horizontalUnit());return at>=-1e-6&&at<=d.horizontalLength()+1e-6&&Math.abs(target.position().y()-a.y())<1e-6;
  }
  static void requireMove(RoadData data,BlockPos from,Node target,Set<UUID> removed,CompoundTag t){
    var outside=data.index.atNode(from).stream().filter(id->!removed.contains(id)).toList();if(outside.isEmpty())return;
    if(AutoJunctions.center(data,from)!=null||data.index.atNode(from).size()!=2)throw new IllegalArgumentException("共享端点属于路口或分岔，不能自动移动");
    var sources=sources(t);for(UUID id:outside)if(sources.stream().noneMatch(s->s.road().id().equals(id)))throw new IllegalArgumentException("共享端点邻路不满足共线水平/车道/结构条件");
  }
  private ContinuousRoads(){}
}
