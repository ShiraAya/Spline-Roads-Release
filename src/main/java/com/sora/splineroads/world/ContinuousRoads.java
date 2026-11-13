package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import java.util.*;
import java.util.function.Function;

/** A managed occupied range with ordinary continuation roads outside its exact cuts. */
public final class ContinuousRoads {
  static final double MAX_EXTENSION=4096;
  static final String MAIN="连续主路:", DONOR="共享端点邻路:";
  record Source(int axis,RoadRecord road){}
  static List<Source> sources(CompoundTag t){var out=new ArrayList<Source>();for(Tag tag:t.getList("ContinuousSources",Tag.TAG_COMPOUND)){var q=(CompoundTag)tag;out.add(new Source(q.getInt("Axis"),RoadRecord.load(q.getCompound("Road"))));}return out;}
  static void capture(RoadData data,CompoundTag t){
    if(t.getLongArray("Points").length>6)return;
    var points=t.getLongArray("Points");var limits=t.getList("Nodes",Tag.TAG_COMPOUND).copy();var list=new ListTag();
    for(int axis=0;axis*2+1<points.length;axis++){
      BlockPos a=BlockPos.of(points[axis*2]),b=BlockPos.of(points[axis*2+1]);var chain=Interchanges.mainChain(data,a,b);if(chain.isEmpty()||chain.stream().anyMatch(r->!RoadContinuity.eligible(r.mesh())))continue;
      if(chain.size()>1)for(int i=1;i<chain.size();i++){var r=chain.get(i-1);var q=chain.get(i);BlockPos shared=r.a().equals(q.a())||r.a().equals(q.b())?r.a():r.b();if(AutoJunctions.center(data,shared)!=null||!sameSettings(r,q)||!r.owner().equals(q.owner())||!RoadContinuity.compatible(r.mesh(),q.mesh(),r.a().equals(shared)?r.start().position():r.end().position()))throw new IllegalArgumentException("连续主路须共线、等高、各项设置一致，且中间端点不属于路口");}
      var extended=new ArrayList<>(chain);Set<UUID> seen=new HashSet<>();chain.forEach(r->seen.add(r.id()));
      for(boolean first:new boolean[]{true,false}){
        BlockPos at=first?a:b;RoadRecord previous=first?chain.get(0):chain.get(chain.size()-1);double distance=0;Node limit=previous.a().equals(at)?previous.start():previous.end();
        while(extended.size()<256&&distance<MAX_EXTENSION-1e-6){
          if(AutoJunctions.center(data,at)!=null||data.index.atNode(at).size()!=2)break;
          RoadRecord neighbor=null;for(UUID id:data.index.atNode(at))if(!seen.contains(id)){var r=data.index.roads.get(id).record;if(r.assembly()==null&&r.junction()==null&&r.owner().equals(previous.owner())&&sameSettings(previous,r)&&RoadContinuity.compatible(previous.mesh(),r.mesh(),previous.a().equals(at)?previous.start().position():previous.end().position()))neighbor=r;}
          if(neighbor==null)break;seen.add(neighbor.id());extended.add(neighbor);
          boolean forward=neighbor.a().equals(at);double remaining=MAX_EXTENSION-distance,length=neighbor.caps(0).mesh().length(),used=Math.min(length,remaining);
          var next=RoadSplitGeometry.node(neighbor.caps(0).mesh(),forward?used:length-used);limit=new Node(next.position(),limit.yaw(),limit.grade());distance+=used;
          at=forward?neighbor.b():neighbor.a();previous=neighbor;if(used<length-1e-6)break;
        }
        limits.set(axis*2+(first?0:1),RoadRecord.writeNode(limit));
      }
      for(var road:extended){var q=new CompoundTag();q.putInt("Axis",axis);q.put("Road",road.header());list.add(q);}
    }
    t.put("ContinuousSources",list);t.put("ContinuousLimits",limits);
  }
  /** Compare authored profile/furniture settings, excluding per-piece point IDs and generated joins. */
  static boolean sameSettings(RoadRecord a,RoadRecord b){
    boolean reverse=a.end().position().sub(a.start().position()).dot(b.end().position().sub(b.start().position()))<0;
    return settingsKey(a.settings()).equals(settingsKey(RoadEndpointSections.orient(b.settings(),reverse)));
  }
  private static CompoundTag settingsKey(Settings s){
    var o=s.options();var key=RoadRecord.writeSettings(s.taper(s.width(),s.width()).options(o.ends(RoadTransitions.Ends.NONE).attachments(RoadAttachments.Data.EMPTY).lanePoints(o.lanePoints().points(List.of()))));
    // Decorations are authored settings; interval caches, point identities and automatic
    // gantry station assignments belong to each road's generated geometry.
    var infra=key.getCompound("Infrastructure");var manual=new ListTag();for(Tag tag:infra.getList("GantryEdits",Tag.TAG_COMPOUND))if(!((CompoundTag)tag).getBoolean("Automatic"))manual.add(tag.copy());infra.put("GantryEdits",manual);
    var decor=key.getCompound("Streetscape");for(String name:List.of("Tunnels","Raised","CycleCrossings","SideStructures"))decor.remove(name);
    return key;
  }
  public static Plan plan(CompoundTag t,Function<CompoundTag,Plan> base){
    var sources=sources(t);if(HostAxes.retiredInterchange(t)||HostAxes.read(t).stream().anyMatch(a->!a.flat()))return base.apply(t);if(sources.isEmpty()||t.contains("Corridor"))return sources.isEmpty()?base.apply(t):segment(t,base.apply(t),sources);
    var plan=ContinuousFit.plan(t,candidate->{var fitted=base.apply(candidate);segment(t,fitted,sources);return fitted;});if(plan.anchors().size()!=t.getLongArray("Points").length)throw new IllegalArgumentException("立交端点方案数量不一致");for(int i=0;i<plan.anchors().size();i++)if(!virtual(t,i,plan.anchors().get(i)))physicalEnd(t,i,plan.anchors().get(i));return segment(t,plan,sources);
  }
  static Plan confirmed(CompoundTag t,Plan plan){
    var sources=sources(t);
    if(plan.anchors().size()!=t.getLongArray("Points").length)throw new IllegalArgumentException("立交端点方案数量不一致");
    for(int i=0;i<plan.anchors().size();i++)if(!virtual(t,i,plan.anchors().get(i)))physicalEnd(t,i,plan.anchors().get(i));
    return sources.isEmpty()?plan:segment(t,plan,sources);
  }
  static Plan segment(CompoundTag t,Plan plan,List<Source> sources){
    var legs=new ArrayList<Leg>();var points=t.getLongArray("Points");boolean adjust=Interchanges.read(t.getCompound("Options")).adjustEndpoints();
    Set<UUID> used=new HashSet<>();
    for(var leg:plan.legs()){
      int axis=leg.name().equals("主路 1")||leg.name().equals("主路 AB")?0:leg.name().equals("主路 2")||leg.name().equals("第二层 CD")?1:leg.name().equals("主路 3")?2:-1;
      if(axis<0||sources.stream().noneMatch(s->s.axis()==axis)){legs.add(leg);continue;}
      Mesh generated=leg.mesh();V origin=generated.first().center(),dir=generated.last().center().sub(origin).horizontalUnit();double total=generated.last().center().sub(origin).dot(dir);
      ContinuousReclaim.requireClear(t,axis,generated);
      var axisSources=sources.stream().filter(s->s.axis()==axis&&ContinuousReclaim.eligible(t,s.road().id())).toList();
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
            double station=position.sub(origin).dot(dir);
            if(first?station<bound:station>bound){bound=station;outer=end;}
          }
          if(outer!=null&&(first?bound>=-1e-5:bound<=total+1e-5))
            terminals.put(outer,first?origin:generated.last().center());
        }
      }
      for(var source:axisSources){var r=source.road();V a=r.start().position(),b=r.end().position();
        a=terminals.getOrDefault(r.a(),a);b=terminals.getOrDefault(r.b(),b);
        double sa=a.sub(origin).dot(dir),sb=b.sub(origin).dot(dir),low=Math.min(sa,sb),high=Math.max(sa,sb);
        boolean occupies=Math.min(high,total)-Math.max(low,0)>1e-5;
        boolean moved=a.distance(r.start().position())>1e-6||b.distance(r.end().position())>1e-6;
        boolean previouslyOccupied=ContinuousService.logical(t,r.id())||t.getList("OccupiedRoads",Tag.TAG_COMPOUND).stream().anyMatch(tag->((CompoundTag)tag).getUUID("Road").equals(r.id()));
        if(!occupies&&!moved&&!previouslyOccupied)continue;
        if(a.sub(b).horizontalLength()<2||Math.signum(r.end().position().sub(r.start().position()).dot(dir))!=Math.signum(sb-sa))throw new IllegalArgumentException("共享端点调整后相邻道路不足两格或发生折返");
        if(moved&&!RoadContinuity.eligible(r.mesh()))throw new IllegalArgumentException("共享端点只能沿同规格直路调整");
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
        Mesh mesh=RoadRibbon.mesh(samples,settings);if(RoadGrades.maximum(mesh)>.15+1e-6)throw new IllegalArgumentException("主路连接余段超过15%纵坡，请增加可用直路长度");
        if(occupies){double first=RoadAttachments.station(mesh,origin),last=RoadAttachments.station(mesh,generated.last().center());ContinuousSections.append(legs,leg,r,mesh,Math.min(first,last),Math.max(first,last),moved);}
        else legs.add(new Leg(DONOR+r.id(),leg.from(),leg.to(),mesh));used.add(r.id());
      }
    }
    ContinuousService.outside(t,plan,legs);
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
  /** A saved neighbour limit may predate a successful endpoint extension.
   * It must never shorten the currently authored road or restore its old height.
   * Keep genuinely farther limits, including caps inside an outside donor. */
  static Node[] effectiveLimits(CompoundTag t){
    var nodes=HostAxes.nodes(t);var stored=t.getList("ContinuousLimits",Tag.TAG_COMPOUND);
    Node[] result=nodes.clone();if(stored.size()!=nodes.length)return result;
    V center=ContinuousFit.center(nodes,Interchanges.read(t.getCompound("Options")));
    for(int i=0;i<nodes.length;i++){
      Node limit=RoadRecord.readNode(stored.getCompound(i));V ray=nodes[i].position().sub(center);double length=ray.horizontalLength();
      if(length<1e-6)continue;
      V delta=limit.position().sub(center),direction=ray.horizontalUnit();
      if(RoadGeometry.finite(limit.position().x(),limit.position().y(),limit.position().z(),limit.yaw(),limit.grade())
          &&delta.dot(direction)>length+1e-6&&Math.abs(delta.dot(direction.left()))<=1e-5)result[i]=limit;
    }return result;
  }
  /** Remember accepted outer endpoints before a later compact layout replaces Nodes.
   * This only combines existing authorized limits with already validated plan anchors;
   * it never promotes the far end of a captured road past an interior 4096 limit. */
  static ListTag committedLimits(CompoundTag before,List<Node> confirmed){
    var result=new ListTag();if(sources(before).isEmpty())return result;
    var prior=HostAxes.nodes(before);var next=confirmed.toArray(Node[]::new);
    if(prior.length!=next.length)throw new IllegalArgumentException("立交端点方案数量不一致");
    var options=Interchanges.read(before.getCompound("Options"));
    V oldCenter=ContinuousFit.center(prior,options),newCenter=ContinuousFit.center(next,options);
    boolean sameCenter=oldCenter.sub(newCenter).horizontalLength()<=1e-5;
    var available=effectiveLimits(before);
    for(int i=0;i<next.length;i++){
      Node keep=next[i];V ray=next[i].position().sub(newCenter),oldRay=prior[i].position().sub(oldCenter);
      if(sameCenter&&ray.horizontalLength()>1e-6&&oldRay.horizontalLength()>1e-6){
        V direction=ray.horizontalUnit(),delta=available[i].position().sub(newCenter);
        if(direction.dot(oldRay.horizontalUnit())>1-1e-8&&Math.abs(delta.dot(direction.left()))<=1e-5
            &&delta.dot(direction)>ray.horizontalLength()+1e-6)keep=available[i];
      }
      result.add(RoadRecord.writeNode(keep));
    }return result;
  }
  public static boolean rangeBoundary(CompoundTag t,int i,Node target){return virtual(t,i,target);}
  static boolean virtual(CompoundTag t,int i,Node target){
    var nodes=HostAxes.nodes(t);if(i>=nodes.length||sources(t).stream().noneMatch(s->s.axis()==i/2))return false;
    V center=ContinuousFit.center(nodes,Interchanges.read(t.getCompound("Options"))),direction=nodes[i].position().sub(center).horizontalUnit();var limits=effectiveLimits(t);
    V end=limits[i].position(),delta=target.position().sub(center);double at=delta.dot(direction),maximum=end.sub(center).dot(direction);
    return at>0&&at<=maximum+1e-6&&Math.abs(delta.dot(direction.left()))<1e-5&&Math.abs(target.position().y()-nodes[i].position().y())<1e-6;
  }
  static BlockPos physicalEnd(CompoundTag t,int i,Node target){
    long[] points=t.getLongArray("Points");var nodes=HostAxes.nodes(t);BlockPos result=BlockPos.of(points[i]);if(sources(t).stream().noneMatch(s->s.axis()==i/2))return result;
    V center=ContinuousFit.center(nodes,Interchanges.read(t.getCompound("Options"))),direction=nodes[i].position().sub(center).horizontalUnit();double far=nodes[i].position().sub(center).dot(direction);
    var limits=effectiveLimits(t);double bound=limits[i].position().sub(center).dot(direction);
    for(var source:sources(t))if(source.axis()==i/2&&ContinuousReclaim.eligible(t,source.road().id()))for(boolean first:new boolean[]{true,false}){
      var r=source.road();var node=first?r.start():r.end();double distance=node.position().sub(center).dot(direction);if(distance>far+1e-6&&distance<=bound+1e-6){far=distance;result=first?r.a():r.b();}
    }if(far<bound-1e-5)throw new IllegalArgumentException("4096格延伸范围止于道路内部，范围外仍有道路，不能移动原选接缝或越界占用；请缩小立交或先独立调整道路");return result;
  }
  static void requireMove(RoadData data,BlockPos from,Node target,Set<UUID> removed,CompoundTag t){
    var outside=data.index.atNode(from).stream().filter(id->!removed.contains(id)).toList();if(outside.isEmpty())return;
    if(AutoJunctions.center(data,from)!=null||data.index.atNode(from).size()!=2)throw new IllegalArgumentException("共享端点属于路口或分岔，不能自动移动");
    var sources=sources(t);for(UUID id:outside)if(sources.stream().noneMatch(s->s.road().id().equals(id)))throw new IllegalArgumentException("共享端点邻路不满足共线水平/车道/结构条件");
  }
  private ContinuousRoads(){}
}
