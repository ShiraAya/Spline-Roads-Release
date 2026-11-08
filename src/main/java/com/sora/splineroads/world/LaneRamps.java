package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;

/** Server authoritative build, preview and dependency regeneration for lane-point ramps. */
public final class LaneRamps {
  /** Immutable records make identity caching safe within one preview/build computation.
   * Never retain a whole world or query grid after the request returns. */
  private static final ThreadLocal<Planning> CURRENT=new ThreadLocal<>();
  private static final class Planning implements AutoCloseable {
    final IdentityHashMap<RoadRecord,Mesh> meshes=new IdentityHashMap<>();
    Map<RoadRecord,Mesh> seedMeshes=Map.of();
    final IdentityHashMap<Mesh,RoadClearance.Prepared> decks=new IdentityHashMap<>();
    final IdentityHashMap<Mesh,Map<List<Integer>,Mesh>> protectedDecks=new IdentityHashMap<>();
    final IdentityHashMap<Map<UUID,RoadRecord>,Map<LanePoints.Ref,Set<UUID>>> connected=new IdentityHashMap<>();
    final IdentityHashMap<Map<UUID,RoadRecord>,Map<LanePoints.Ref,LaneRoadChain>> chains=new IdentityHashMap<>();
    final IdentityHashMap<LaneRoadChain,Map<ApproachKey,LaneRampPaths.Port>> approaches=new IdentityHashMap<>();
    RoadStructures.Ground terrain;
    V junctionDirection;double minimumHeight=Double.NEGATIVE_INFINITY,maximumHeight=Double.POSITIVE_INFINITY;
    boolean retainProfile;
    Planning(Map<RoadRecord,Mesh> seeds,V direction,double minHeight,double maxHeight){root=CURRENT.get()==null;if(root){CURRENT.set(this);seedMeshes=seeds;junctionDirection=direction;minimumHeight=minHeight;maximumHeight=maxHeight;}}
    final boolean root;final long started=System.nanoTime();long clearanceNanos;int routeCount,contactQueries;
    Planning(RoadData data){root=CURRENT.get()==null;if(root){CURRENT.set(this);if(data!=null){terrain=data.terrainGround();seedMeshes=data.index.planningSnapshot().seeds();}}}
    public void close(){if(root){
      long elapsed=System.nanoTime()-started;
      if(elapsed>1_000_000_000L||Boolean.getBoolean("sr.profile"))System.getLogger("SplineRoads/planner").log(System.Logger.Level.INFO,
          String.format(Locale.ROOT,"SR route plan total_ms=%.3f clearance_ms=%.3f candidates=%d contact_queries=%d cached_meshes=%d",elapsed/1e6,clearanceNanos/1e6,routeCount,contactQueries,meshes.size()));
      CURRENT.remove();}}
  }
  private static Mesh mesh(RoadRecord road){var plan=CURRENT.get();if(plan==null)return road.mesh();var seeded=plan.seedMeshes.get(road);if(seeded!=null)return seeded;if(plan.meshes.size()>512)plan.meshes.clear();return plan.meshes.computeIfAbsent(road,RoadRecord::mesh);}
  /** A service interchange must stay above its fixed lower intersection, even in a geometry preview. */
  static RoadRecord aboveFloor(RoadData data,Map<UUID,RoadRecord> all,UUID id,UUID owner,LanePoints.Link link,double floor){
    try(var scope=new Planning(data)){
      var active=CURRENT.get();var previous=active.terrain;boolean retain=active.retainProfile;
      // ParallelSpui only supplies ramps generated in this same draft. Host
      // extension needs full revalidation, not a new aesthetic profile search.
      active.retainProfile=all.containsKey(id);
      active.terrain=new RoadStructures.Ground(){
        public double top(double x,double z,double y){return Math.min(y,floor);}
        public double surface(double x,double z,double y){double actual=previous==null?floor:previous.surface(x,z,y);return Double.isFinite(actual)?Math.max(floor,actual):floor;}
        public boolean blocked(RoadStructures.Part p){return previous!=null&&previous.blocked(p);}
        public boolean joined(V p){return previous!=null&&previous.joined(p);}
      };
      try{return generate(data,all,id,owner,link);}finally{active.terrain=previous;active.retainProfile=retain;}
    }
  }
  private static List<RoadClearance.Contact> contacts(Mesh proposed,Mesh existing){
    var plan=CURRENT.get();if(plan==null)return RoadClearance.contacts(proposed,existing);
    if(plan.decks.size()>32)plan.decks.clear();
    long start=System.nanoTime();plan.contactQueries++;
    try{return RoadClearance.contacts(proposed,plan.decks.computeIfAbsent(existing,RoadClearance::prepare));}
    finally{plan.clearanceNanos+=System.nanoTime()-start;}
  }
  public static RoadRecord host(Map<UUID,RoadRecord> all,LanePoints.Ref ref){var r=all.get(ref.road());if(r==null)throw new IllegalArgumentException("所选道路已不存在");if(r.toll()!=null)throw new IllegalArgumentException("收费站内部和 A/B 接口不支持匝道，请连接站外道路");if(r.assembly()!=null||r.junction()!=null||!LanePoints.supported(r.settings()))throw new IllegalArgumentException("连接器仅支持独立普通道路／高速／自由匝道的地面、隧道、自动高架、标准小河桥、梁式高架与跨线桥；不支持立交内部道路");var selected=LaneTopology.point(r,ref.point());var tolls=all instanceof RoadPlanningSnapshot.Records snapshot?snapshot.tolls():all.values();for(var other:tolls)if(other.toll()!=null&&TollPlanner.contains(other.mesh(),selected.position(),.03,other.toll().config().clearHeight()))throw new IllegalArgumentException("该车道点位于收费站内部或 A/B 接口，请把匝道接点移到站外");return r;}
  public static LaneRampPaths.Port port(RoadRecord road,LanePoints.Point p){return port(road,p,1);}
  public static LaneRampPaths.Port port(RoadRecord road,LanePoints.Point p,int lanes){var mesh=mesh(road);var l=LanePoints.group(mesh,p,lanes);var sample=RoadStructures.sample(mesh,l.station());var layout=RoadProfile.layout(mesh,sample);double lateral=l.position().sub(sample.center()).dot(sample.left());int side=layout.catalog().twoWay()?(lateral<layout.medianCenter()?-1:1):layout.outside();double edge=side<0?-layout.motorMin():layout.motorMax();double distance=Math.max(l.width(),edge-side*lateral+l.width()/2);double step=Math.min(.5,mesh.length()/10);var before=RoadStructures.sample(mesh,Math.max(0,l.station()-step));var after=RoadStructures.sample(mesh,Math.min(mesh.length(),l.station()+step));V delta=after.center().sub(before.center());double grade=delta.y()/Math.max(.001,delta.horizontalLength())*l.sign();return new LaneRampPaths.Port(l.position(),l.direction(),sample.left().mul(side),distance,grade);}
  public static LaneRampPaths.Port targetPort(RoadRecord road,LanePoints.Point point,double offset){return targetPort(road,point,offset,1);}
  private static LaneRampPaths.Port targetPort(RoadRecord road,LanePoints.Point point,double offset,int lanes){
    var lane=LanePoints.lane(mesh(road),point);double station=lane.station()+lane.sign()*offset;
    if(station<-.00001||station>mesh(road).length()+.00001)throw new IllegalArgumentException("汇入口超出所选道路，不能换路或换车道");
    return port(road,LanePoints.point(point.id(),point.origin(),mesh(road),station,point.lane()),lanes);
  }
  public static LaneRampPaths.Port arrivalPort(RoadRecord road,LanePoints.Point point,double offset,LanePoints.Link link,UUID connection){
    if(link.options().targetExtra())return extraPort(road,point,offset);
    if(link.options().arrival()!=LanePoints.Arrival.ADD)return targetPort(road,point,offset,link.options().lanes());
    var at=LanePoints.lane(mesh(road),point);var added=LaneAdditions.owned(mesh(road),connection);
    return port(road,LanePoints.point(point.id(),point.origin(),mesh(road),at.station()+at.sign()*offset,added.slot()));
  }
  private static LaneRampPaths.Port extraPort(RoadRecord road,LanePoints.Point point,double offset){
    var host=mesh(road);var lane=LanePoints.lane(host,point);double station=lane.station()+lane.sign()*offset;
    if(station<0||station>host.length())throw new IllegalArgumentException("额外车道接头超出主路范围");
    var outside=LaneRampApproach.outerLane(host,station,point.lane());
    return port(road,LanePoints.point(point.id(),point.origin(),host,station,outside.index()));
  }
  /** Restoration policy does not move a previously validated motor path. */
  private static boolean sameRoute(LanePoints.Link a,LanePoints.Link b){
    if(Objects.equals(a,b))return true;
    if(a==null||b==null||!a.options().separatesLane()||!b.options().separatesLane())return false;
    var o=a.options();var n=b.options();
    return a.equals(new LanePoints.Link(b.from(),b.to(),new LanePoints.Options(n.path(),o.departure(),n.arrival(),n.radius(),n.transition(),n.elevation(),n.landing(),n.gradeOverride(),n.allowTunnel(),n.lanes()),b.junctionMouth(),b.targetOffset(),b.protectedMerge(),b.rectangularClosure()));
  }
  private static LaneRampAlignment.Mouth[] contactMouths(Map<UUID,RoadRecord> all,LanePoints.Link link,UUID id){
    var source=host(all,link.from());var point=LaneTopology.point(source,link.from().point());var lane=LanePoints.group(source.mesh(),point,link.options().sourceExtra()?1:link.options().lanes());
    var from=link.options().sourceExtra()?LaneRampAlignment.Mouth.extra(lane.width(),RoadProfile.trafficSign(source.settings().options().leftTraffic())):LaneRampAlignment.mouth(source.mesh(),lane);
    var to=LaneRampAlignment.Mouth.extra(lane.width());
    if(link.to().road()!=null){
      var position=chain(all,link.to()).at(link.targetOffset());var target=position.road().mesh();var targetLane=LanePoints.group(target,position.point(),link.options().targetExtra()?1:link.options().lanes());
      if(link.options().arrival()==LanePoints.Arrival.ADD){var added=LaneAdditions.owned(target,id);targetLane=LanePoints.lane(target,added.station(),added.slot());}
      to=link.options().targetExtra()?LaneRampAlignment.Mouth.extra(targetLane.width(),RoadProfile.trafficSign(target.settings().options().leftTraffic())):LaneRampAlignment.mouth(target,targetLane);
    }
    return new LaneRampAlignment.Mouth[]{from,to};
  }
  private record Generated(RoadRecord road,LanePoints.Path path) {}
  static RoadRecord generate(RoadData data,Map<UUID,RoadRecord> all,UUID id,UUID owner,LanePoints.Link link){
    return generateChoice(data,all,id,owner,link,null).road();
  }
  public static RoadRecord reconfigure(RoadRecord old,Settings requested,Map<UUID,RoadRecord> all){return reconfigure(null,old,requested,all);}
  private static RoadRecord reconfigure(RoadData data,RoadRecord old,Settings requested,Map<UUID,RoadRecord> all){
    if(RoadProfile.catalog(requested).lanes()!=LaneTopology.metadata(old).link().options().lanes()||(!LanePoints.supported(requested)&&requested.structure()!=Structure.TUNNEL))
      throw new IllegalArgumentException("连接器匝道须保持独立匝道类型、单车道及支持的道路结构");
    return generateChoice(data,all,old.id(),old.owner(),LaneTopology.metadata(old).link(),requested).road();
  }
  private static Generated generateChoice(RoadData data,Map<UUID,RoadRecord> all,UUID id,UUID owner,LanePoints.Link link,Settings edited){
    try(var budget=RoadPlanningBudget.cancellable("匝道路线/净空搜索");var ignored=new Planning(data)){return generatePlanned(data,all,id,owner,link,edited);}
  }
  private static Generated generatePlanned(RoadData data,Map<UUID,RoadRecord> all,UUID id,UUID owner,LanePoints.Link link,Settings edited){
    link=link.withProtectedMerge().withRectangularClosure();
    var existing=all.get(id);var existingLink=existing==null?null:LaneTopology.metadata(existing).link();
    // Existing authored geometry remains readable/rebuildable. New selections and
    // changed connector options must follow the tunnel-end-only EXTRA policy.
    if(existingLink==null||!existingLink.options().equals(link.options()))validateTunnelEnds(all,link.from(),link.to(),link.options());
    var source=host(all,link.from());var p=LaneTopology.point(source,link.from().point());var lane=LanePoints.group(mesh(source),p,link.options().sourceExtra()?1:link.options().lanes());
    double maxGrade=gradeLimit(all,link);
    var previous=all.get(id);var old=previous!=null&&LaneTopology.metadata(previous).link()!=null?previous:null;
    Style style=RoadProfile.highway(source.settings().style())?Style.C1_HIGHWAY_RAMP:Style.C1_RAMP;
    Settings base=edited!=null?edited:old!=null?old.settings():new Settings(Mode.CURVE,style,Math.max(4,lane.width()),source.settings().thickness(),.35,90).structure(Structure.AUTO).options(RoadProfile.Options.DEFAULT.traffic(source.settings().options().leftTraffic()).lanePoints(LanePoints.Data.EMPTY.link(link)));
    boolean migrating=old!=null&&!old.settings().style().connectorRamp();
    Style kind=(edited!=null?RoadProfile.highway(edited.style()):old!=null?RoadProfile.highway(old.settings().style()):RoadProfile.highway(style))?Style.C1_HIGHWAY_RAMP:Style.C1_RAMP;
    var options=base.options().lanePoints(base.options().lanePoints().link(link)).lanes(link.options().lanes()>1?new RoadLanes.Counts(link.options().lanes(),0):RoadLanes.Counts.AUTO).hideArrows(true);
    // New connectors and old AUTO defaults start without gantries. Explicit saved/edited
    // equipment choices survive; ordinary roads and automatic interchanges are untouched.
    if(old==null&&edited==null||migrating&&options.infrastructure().gantry()==RoadInfrastructure.Gantry.AUTO)
      options=options.infrastructure(options.infrastructure().gantry(RoadInfrastructure.Gantry.OFF));
    base=new Settings(base.mode(),kind,base.width(),base.thickness(),base.tension(),base.arcDegrees(),base.startWidth(),base.endWidth(),base.structure(),base.taperVersion(),base.rampTurn(),options);
    if(base.structure()==Structure.TUNNEL)base=base.structure(Structure.AUTO);
    base=LaneRampAlignment.usableWidth(base,link.options().sourceExtra()?lane.width()*link.options().lanes():lane.width());
    base.validate();
    // Rechecking an unchanged connector must first validate its saved alignment.
    // Searching from scratch can choose a different family or reject an old valid
    // layout after a candidate ordering update. This still runs current clearance.
    if(old!=null&&(edited==null||old.settings().options().ends().start()!=null&&old.settings().options().ends().end()!=null)&&sameRoute(LaneTopology.metadata(old).link(),link)&&base.width()==old.settings().width()
        &&base.thickness()==old.settings().thickness()&&base.style()==old.settings().style()
        &&(CURRENT.get()!=null&&CURRENT.get().retainProfile||link.options().elevation()==LanePoints.Elevation.KEEP||!LaneTopology.metadata(old).link().options().equals(link.options())||!verticalKink(old.mesh()))
        &&(CURRENT.get()!=null&&CURRENT.get().retainProfile||link.options().elevation()!=LanePoints.Elevation.AUTO||monotone(old.mesh())))try{
      var kept=old.settings(base);var candidate=kept.mesh();
      if(!currentApproaches(candidate,all,link))throw new IllegalArgumentException("额外汇入/汇出渐变长度已更新");
      var context=LaneCrossSections.staged(all,id,link,candidate);
      var mouths=contactMouths(context,link,id);
      if(!LaneRampAlignment.matches(candidate,mouths[0],mouths[1])){
        candidate=LaneRampAlignment.refit(candidate,mouths[0],mouths[1]);
        var start=RoadRibbon.start(candidate);var end=RoadRibbon.end(candidate);
        kept=new RoadRecord(id,owner,RampJunctions.at(start.position()),RampJunctions.at(end.position()),start,end,candidate.settings(),false,4).alignment(null,candidate).furniturePhase(old.furniturePhase());
        context=LaneCrossSections.staged(all,id,link,candidate);
      }
      var fitted=fitHostContacts(candidate,context,link);
      if(!fitted.samples().equals(candidate.samples())){
        candidate=fitted;var start=RoadRibbon.start(candidate);var end=RoadRibbon.end(candidate);
        kept=new RoadRecord(id,owner,RampJunctions.at(start.position()),RampJunctions.at(end.position()),start,end,candidate.settings(),false,4).alignment(null,candidate).furniturePhase(old.furniturePhase());
        context=LaneCrossSections.staged(all,id,link,candidate);
      }
      var sourceHost=host(context,link.from());var sourcePoint=LaneTopology.point(sourceHost,link.from().point());
      var from=link.options().sourceExtra()?extraPort(sourceHost,sourcePoint,0):port(sourceHost,sourcePoint,link.options().lanes());
      var to=link.to().road()==null?null:resolvedArrival(context,link,id);
      if(LaneRampAlignment.axis(candidate,true).distance(from.position())<1e-5
          &&LaneRampAlignment.axis(candidate,false).distance(to==null?link.junctionMouth():to.position())<1e-5){
        if(CURRENT.get()!=null&&CURRENT.get().retainProfile){
          // Growing a host can expose a new clearance constraint under an
          // otherwise valid ramp. Refit this horizontal route before searching
          // hundreds of different landing stations and turn families again.
          var errors=new EnumMap<LanePoints.Path,String>(LanePoints.Path.class);
          for(var fittedHeight:heightSearch(candidate,context,id,link,errors,link.options().path()))try{
            if(data!=null&&!data.withinHeight(fittedHeight))throw new IllegalArgumentException("上跨／下穿超出世界高度范围");
            var finalContext=LaneCrossSections.staged(all,id,link,fittedHeight);
            validate(fittedHeight,finalContext,id,link);RoadCycleChain.checkRamp(finalContext,id,fittedHeight);
            for(var road:finalContext.values())for(var cut:LaneTopology.metadata(road).cuts())if(cut.connection().equals(id)&&!cut.arrival()&&cut.temporary())LaneReopening.validateRestored(road.mesh(),cut.lane(),fittedHeight,id);
            return new Generated(new RoadRecord(id,owner,RampJunctions.at(RoadRibbon.start(fittedHeight).position()),RampJunctions.at(RoadRibbon.end(fittedHeight).position()),RoadRibbon.start(fittedHeight),RoadRibbon.end(fittedHeight),fittedHeight.settings(),false,4).alignment(null,fittedHeight).furniturePhase(old.furniturePhase()),link.options().path());
          }catch(IllegalArgumentException rejected){errors.put(link.options().path(),rejected.getMessage());}
          throw new IllegalArgumentException(errors.toString());
        }
        validate(candidate,context,id,link);
        RoadCycleChain.checkRamp(context,id,candidate);
        for(var road:context.values())for(var cut:LaneTopology.metadata(road).cuts())if(cut.connection().equals(id)&&!cut.arrival()&&cut.temporary())LaneReopening.validateRestored(road.mesh(),cut.lane(),candidate,id);
        return new Generated(kept,link.options().path());
      }
    }catch(IllegalArgumentException changed){
      if(Boolean.getBoolean("sr.profile"))System.getLogger("SplineRoads/planner").log(System.Logger.Level.INFO,"SR retained route rejected: "+changed.getMessage());
      /* changed world: run the normal constrained search */
    }
    var a=port(source,p,link.options().sourceExtra()?1:link.options().lanes());if(link.options().sourceExtra())a=approach(all,link.from(),0,true,base,link.options().transition());
    var offsets=new LinkedHashSet<Double>();
    var landingChain=link.to().road()==null?null:chain(all,link.to());
    if(link.options().landing()==LanePoints.Landing.EXACT||landingChain==null)offsets.add(0d);
    else {
      if(landingChain.contains(link.targetOffset()))offsets.add(link.targetOffset());
      offsets.add(0d);
      // Search the whole real lane, continuing through unambiguous connected
      // records. Distance from B is only the candidate order, never a cutoff.
      double reach=Math.max(-landingChain.low(),landingChain.high());
      for(double step=8;step<reach;step+=8){
        RoadPlanningBudget.check();
        if(landingChain.contains(step))offsets.add(step);
        if(landingChain.contains(-step))offsets.add(-step);
      }
      for(var leg:landingChain.legs()){offsets.add(leg.low());offsets.add(leg.high());}
    }
    var stagedOffsets=new HashMap<Double,Map<UUID,RoadRecord>>();
    var candidateMemo=new CandidateMemo();
    Generated bestProfile=null;double bestBend=Double.POSITIVE_INFINITY;int compared=0,compareAttempts=0;
    var errors=new EnumMap<LanePoints.Path,String>(LanePoints.Path.class);String error="所选范围内没有可用汇入口";LaneRampPaths.Port target=null;
    // A locally shifted short turn must be tried before committing to a long
    // loop. Previously a valid loop in pass one hid a much shorter lead/tail
    // solution in pass two (e.g. a 540m route instead of a 403m route).
    // Compare layer choices within each horizontal candidate. A saved explicit
    // OVER/UNDER first tries the requested layer. Only failed preferred profiles
    // enter the mixed fallback, ordered by the fewest opposite-layer contacts.
    // Each route solves monotone first internally, then real obstacle-required dips.
    // A global monotone pass discarded feasible underpasses and repeated the whole search.
    for(int stage=0;stage<3;stage++)for(double offset:offsets)try{
      RoadPlanningBudget.check();
      var actual=link.targetOffset(offset);
      var context=stagedOffsets.get(offset);
      if(context==null){context=LaneCrossSections.staged(all,id,actual);stagedOffsets.put(offset,context);}
      var currentSource=host(context,actual.from());
      if(!LaneSections.active(mesh(currentSource),lane.station(),p.lane()))throw new IllegalArgumentException("汇出车道已在此位置分离，不能从车道空位再次汇出");
      LaneRampPaths.Port b;double targetLaneWidth=lane.width();
      LaneRoadChain.Position targetPosition=null;
      if(link.to().road()!=null){
        targetPosition=chain(context,link.to()).at(offset);
        var road=targetPosition.road();var point=targetPosition.point();var selectedLane=LanePoints.group(mesh(road),point,link.options().targetExtra()?1:link.options().lanes());
        if(!rampHost(road)&&!link.options().targetExtra()&&tunnelAt(road,selectedLane.station())&&(existingLink==null||!existingLink.options().equals(link.options())))throw new IllegalArgumentException("实际汇入点位于隧道，只允许额外汇入");
        if((link.options().arrival()==LanePoints.Arrival.MERGE||link.options().arrival()==LanePoints.Arrival.FLOW)&&!LaneSections.active(mesh(road),selectedLane.station(),point.lane()))throw new IllegalArgumentException("B 位于已分离的车道空位，不能当作开放车道汇入");
        targetLaneWidth=selectedLane.width();
        b=link.options().targetExtra()?approach(context,link.to(),offset,false,base,link.options().transition()):arrivalPort(road,point,0,actual,id);
      }else{
        if(link.options().targetExtra())throw new IllegalArgumentException("路口中心接入口不设置沿主路的额外汇入车道");
        V direction=data!=null?RampJunctions.spec(data,link.to().junction()).center().sub(link.junctionMouth()).horizontalUnit():CURRENT.get()!=null&&CURRENT.get().junctionDirection!=null?CURRENT.get().junctionDirection:old!=null?old.end().direction():null;
        if(direction==null)throw new IllegalArgumentException("缺少路口接入方向");
        b=new LaneRampPaths.Port(link.junctionMouth(),direction,direction.left(),lane.width(),0);
      }
      target=b;
      var md=(old==null?LanePoints.Data.EMPTY:LaneTopology.metadata(old)).link(actual).openings(List.of()).bands(List.of());
      var settings=base.options(base.options().lanePoints(md));
      for(var candidate:routeCandidates(a,b,settings,link.options(),source,p,targetPosition==null?null:targetPosition.road(),targetPosition==null?null:targetPosition.point(),0,maxGrade,errors,stage,candidateMemo,offset,chain(context,actual.from()),actual.to().road()==null?null:chain(context,actual.to())))try{
        if(bestProfile!=null&&++compareAttempts>32)return bestProfile;
        RoadPlanningBudget.check();
        if(CURRENT.get()!=null)CURRENT.get().routeCount++;
        var sourceMouth=actual.options().sourceExtra()?LaneRampAlignment.Mouth.extra(lane.width(),RoadProfile.trafficSign(settings.options().leftTraffic())):LaneRampAlignment.mouth(currentSource.mesh(),LanePoints.group(currentSource.mesh(),p,actual.options().lanes()));
        var targetMouth=targetPosition==null?LaneRampAlignment.Mouth.extra(targetLaneWidth):actual.options().targetExtra()?LaneRampAlignment.Mouth.extra(targetLaneWidth,RoadProfile.trafficSign(settings.options().leftTraffic())):LaneRampAlignment.mouth(targetPosition.road().mesh(),LanePoints.group(targetPosition.road().mesh(),targetPosition.point(),actual.options().lanes()));
        // ADD has a new outer slot, not the old selected slot.
        if(actual.options().arrival()==LanePoints.Arrival.ADD){var host=targetPosition.road().mesh();var added=LaneAdditions.owned(host,id);targetMouth=LaneRampAlignment.mouth(host,LanePoints.lane(host,added.station(),added.slot()));}
        var baseMesh=fitHostContacts(LaneRampAlignment.fit(candidate.mesh(),sourceMouth,targetMouth,normalHosts(context,actual,true),normalHosts(context,actual,false)),context,actual);
        for(Mesh mesh:heightSearch(baseMesh,context,id,actual,errors,candidate.path()))try{
          if(data!=null&&!data.withinHeight(mesh)||data==null&&CURRENT.get()!=null&&(mesh.min().y()-mesh.settings().thickness()<CURRENT.get().minimumHeight||mesh.max().y()+4>=CURRENT.get().maximumHeight))throw new IllegalArgumentException("上跨／下穿超出世界高度范围");
          var finalContext=actual.options().departure()==LanePoints.Departure.TEMPORARY||actual.closesTarget()?
              LaneCrossSections.staged(all,id,actual,mesh):context;
          validate(mesh,finalContext,id,actual);
          RoadCycleChain.checkRamp(finalContext,id,mesh);
          if(actual.options().departure()==LanePoints.Departure.TEMPORARY)
            for(var road:finalContext.values())for(var cut:LaneTopology.metadata(road).cuts())if(cut.connection().equals(id)&&!cut.arrival())
              LaneReopening.validateRestored(road.mesh(),cut.lane(),mesh,id);
          var start=RoadRibbon.start(mesh);var end=RoadRibbon.end(mesh);
          var record=new RoadRecord(id,owner,RampJunctions.at(start.position()),RampJunctions.at(end.position()),start,end,mesh.settings(),false,4).alignment(null,mesh);
          if(old!=null)record=record.furniturePhase(old.furniturePhase());
          var generated=new Generated(record,candidate.path());
          // Keep a valid fallback, but do not stop at an unrequested crest on the
          // first AUTO auxiliary turn. A later validated turn may descend directly.
          if(link.options().elevation()==LanePoints.Elevation.AUTO
              &&(link.options().sourceExtra()||link.options().targetExtra())){
            if(monotone(mesh))return generated;
            double reversal=verticalEffort(mesh)-Math.abs(end.position().y()-start.position().y());
            if(bestProfile==null||reversal<bestBend){bestProfile=generated;bestBend=reversal;}
            if(++compared>=24)return bestProfile;
            continue;
          }
          boolean compare=link.options().path()==LanePoints.Path.AUTO&&link.options().elevation()==LanePoints.Elevation.AUTO
              &&Math.abs(a.position().y()-b.position().y())>.5&&a.direction().dot(b.direction())>.5
              &&(link.options().separatesLane()&&!LaneSections.edge(source.rawMesh(),lane.station(),p.lane())
                 ||actual.closesTarget());
          if(!compare)return generated;
          double bend=gradeBends(mesh);
          if(bend<bestBend){bestProfile=generated;bestBend=bend;}
          if(bestBend<.02||++compared>=24)return bestProfile;
        }catch(IllegalArgumentException e){errors.put(candidate.path(),e.getMessage());error=e.getMessage();}
      }catch(IllegalArgumentException e){errors.put(candidate.path(),e.getMessage());error=e.getMessage();}
    }catch(IllegalArgumentException e){if(offset==link.targetOffset())error=e.getMessage();}
    if(bestProfile!=null)return bestProfile;
    if(!errors.isEmpty()&&target!=null)error=LaneRampPaths.failure(a,target,link.options(),errors);
    throw new IllegalArgumentException(error+(link.to().road()!=null?link.options().landing()==LanePoints.Landing.EXACT?"；汇入点锁定 B":"；整条连续同车道内未找到满足方向、坡度和净空的汇入口":""));
  }
  /** Extra longitudinal bends beyond one smooth rise/fall. Only compare fully
   * validated AUTO routes; a constrained fallback is retained if no better one fits. */
  private static double gradeBends(Mesh mesh){
    double previous=0,total=0,peak=0;
    for(int i=1;i<mesh.samples().size();i++){
      V delta=mesh.samples().get(i).center().sub(mesh.samples().get(i-1).center());
      double grade=delta.y()/Math.max(1e-8,delta.horizontalLength());
      total+=Math.abs(grade-previous);peak=Math.max(peak,Math.abs(grade));previous=grade;
    }
    return Math.max(0,total+Math.abs(previous)-2*peak);
  }
  /** Try a same-slot lead before a lateral turn, giving an inner lane enough room
   * to rise/drop BEFORE crossing its neighbours. These are automatic candidates,
   * not manually editable control points and not a clearance exemption. */
  /** Generate expensive fallback ribbons only when the preceding candidate actually failed.
   * RC1 eagerly built the entire source-lead x target-tail cross product even when
   * the first direct candidate was valid. No route or safety check is removed. */
  private record CandidateKey(double offset,double lead,double tail,LanePoints.Path kind,boolean automatic,boolean smooth,boolean constrained){}
  /** Horizontal search is identical across vertical passes. Replay the same lazy
   * groups, including rejections, within this one immutable planning request.
   * Bounded by samples and entries; never retain roads after the request. */
  private static final class CandidateMemo {
    private record Entry(List<LaneRampPaths.Candidate> candidates,String error,int weight){}
    private final LinkedHashMap<CandidateKey,Entry> entries=new LinkedHashMap<>(64,.75f,true);
    private int weight;
    List<LaneRampPaths.Candidate> get(CandidateKey key,java.util.function.Supplier<List<LaneRampPaths.Candidate>> build){
      var value=entries.get(key);
      if(value==null){
        try{var made=List.copyOf(build.get());value=new Entry(made,null,Math.max(1,made.stream().mapToInt(c->c.mesh().samples().size()).sum()));}
        catch(IllegalArgumentException e){value=new Entry(List.of(),e.getMessage(),1);}
        if(value.weight()<=300_000){entries.put(key,value);weight+=value.weight();var it=entries.values().iterator();
          while(weight>300_000||entries.size()>256){weight-=it.next().weight();it.remove();}}
      }
      if(value.error()!=null)throw new IllegalArgumentException(value.error());return value.candidates();
    }
  }
  private static Iterable<LaneRampPaths.Candidate> routeCandidates(LaneRampPaths.Port a,LaneRampPaths.Port b,Settings settings,LanePoints.Options options,RoadRecord source,LanePoints.Point point,RoadRecord target,LanePoints.Point targetPoint,double targetOffset,double maxGrade,Map<LanePoints.Path,String> errors,int stage,CandidateMemo memo,double landingOffset,LaneRoadChain sourceChain,LaneRoadChain targetChain){
    if(stage==2&&options.path()!=LanePoints.Path.AUTO)return List.of();
    boolean innerSource=options.separatesLane()&&!LaneSections.edge(source.rawMesh(),LanePoints.lane(source.rawMesh(),point).station(),point.lane());
    double[] leads=options.separatesLane()?innerSource?new double[]{64,48,32,96,128,0}:new double[]{0,32,64,96,128}:new double[]{0};
    boolean tail=target!=null&&(options.arrival()==LanePoints.Arrival.MERGE||options.arrival()==LanePoints.Arrival.FLOW||options.arrival()==LanePoints.Arrival.ADD);
    boolean innerTarget=tail&&!LaneSections.edge(target.rawMesh(),LanePoints.lane(target.rawMesh(),targetPoint).station(),targetPoint.lane());
    boolean lateralFirst=(innerSource||innerTarget)&&Math.abs(a.position().y()-b.position().y())>.5
        &&a.direction().dot(b.direction())>.5&&a.outside().dot(b.outside())>.5;
    double[] tails=tail?lateralFirst?new double[]{64,96,128,48,32,0,16}:innerTarget?new double[]{64,32,96,128,0,16}:new double[]{0,16,32,48,64,96,128}:new double[]{0};
    return ()->new Iterator<>(){
      int li,ti,ki;final List<LanePoints.Path> kinds=options.path()==LanePoints.Path.AUTO?
          stage==2?List.of(LanePoints.Path.LEFT_LOOP):lateralFirst?List.of(LanePoints.Path.RIGHT,LanePoints.Path.DIRECT,LanePoints.Path.LEFT):List.of(LanePoints.Path.DIRECT,LanePoints.Path.RIGHT,LanePoints.Path.LEFT):List.of(options.path());
      Iterator<LaneRampPaths.Candidate> ready=Collections.emptyIterator();
      public boolean hasNext(){
        while(!ready.hasNext()&&ti<tails.length){
          RoadPlanningBudget.check();
          double lead=leads[li],endLength=tails[ti];var kind=kinds.get(ki++);if(ki==kinds.size()){ki=0;if(++li==leads.length){li=0;ti++;}}
          if(stage==0&&(lead!=leads[0]||endLength!=tails[0])||stage==1&&lead==leads[0]&&endLength==tails[0])continue;
          var specific=new LanePoints.Options(kind,options.departure(),options.arrival(),options.radius(),options.transition(),options.elevation(),options.landing(),options.gradeOverride(),options.allowTunnel(),options.lanes());
          try{var key=new CandidateKey(landingOffset,lead,endLength,kind,options.path()==LanePoints.Path.AUTO&&kind==LanePoints.Path.LEFT_LOOP,kind==LanePoints.Path.RIGHT||options.path()==LanePoints.Path.LEFT,options.path()!=LanePoints.Path.AUTO);ready=memo.get(key,()->routeGroup(a,b,settings,specific,source,point,target,targetPoint,targetOffset,maxGrade,lead,endLength,key.automatic(),key.smooth(),key.constrained(),sourceChain,targetChain,landingOffset)).iterator();}
          catch(IllegalArgumentException failure){errors.putIfAbsent(kind,failure.getMessage());ready=Collections.emptyIterator();}
        }
        return ready.hasNext();
      }
      public LaneRampPaths.Candidate next(){if(!hasNext())throw new NoSuchElementException();return ready.next();}
    };
  }
  private static List<LaneRampPaths.Candidate> routeGroup(LaneRampPaths.Port a,LaneRampPaths.Port b,Settings settings,LanePoints.Options options,RoadRecord source,LanePoints.Point point,RoadRecord target,LanePoints.Point targetPoint,double targetOffset,double maxGrade,double leadLength,double tailLength,boolean automaticTurns,boolean smooth,boolean constrainSmooth,LaneRoadChain sourceChain,LaneRoadChain targetChain,double landingOffset){
    var before=new ArrayList<Sample>();var after=new ArrayList<Sample>();
    if(leadLength>0){
      for(double d=0;d<=leadLength;d+=.5){var pos=sourceChain.at(d);var q=LanePoints.group(pos.road().rawMesh(),pos.point(),options.lanes());before.add(new Sample(q.position(),q.direction().left(),d,settings.width()/2));}
      var last=before.get(before.size()-1);V delta=last.center().sub(before.get(before.size()-2).center());V direction=last.left().left().mul(-1);
      a=new LaneRampPaths.Port(last.center(),direction,direction.left().mul(Math.signum(a.outside().dot(a.direction().left()))),a.extraWidth(),delta.y()/Math.max(.001,delta.horizontalLength()));
    }
    if(tailLength>0&&options.arrival()!=LanePoints.Arrival.ADD){
      var pos=targetChain.at(landingOffset-tailLength);var q=LanePoints.group(pos.road().rawMesh(),pos.point(),options.lanes());
      var next=targetChain.at(landingOffset-tailLength+.5);var following=LanePoints.group(next.road().rawMesh(),next.point(),options.lanes());V delta=following.position().sub(q.position());
      b=new LaneRampPaths.Port(q.position(),q.direction(),q.direction().left().mul(Math.signum(b.outside().dot(b.direction().left()))),b.extraWidth(),delta.y()/Math.max(.001,delta.horizontalLength()));
      for(double d=.5;d<=tailLength+.001;d+=.5){var at=targetChain.at(landingOffset-tailLength+d);var lane=LanePoints.group(at.road().rawMesh(),at.point(),options.lanes());after.add(new Sample(lane.position(),lane.direction().left(),d,settings.width()/2));}
    }else if(tailLength>0){
      var raw=target.rawMesh();var lane=LanePoints.lane(raw,targetPoint);double end=lane.station()+lane.sign()*targetOffset,start=end-lane.sign()*tailLength;
      if(start<=.5||start>=raw.length()-.5)return List.of();
      int slot=targetPoint.lane();
      if(options.arrival()==LanePoints.Arrival.ADD){
        // Match the newly added arrival axis, not the old clicked lane.
        var best=raw.settings().options().lanePoints().additions().stream().filter(v->v.sign()==lane.sign()&&Math.abs(v.station()-end)<1e-5).reduce((x,y)->y);
        if(best.isEmpty())return List.of();slot=best.get().slot();
        // A lead inside a widening taper is not a stable full-width incoming lane.
        if(best.get().fraction(start)<1-1e-6)return List.of();
      }
      var q=LanePoints.lane(raw,start,slot);var prev=LanePoints.lane(raw,start-lane.sign()*.5,slot);V delta=q.position().sub(prev.position());
      b=new LaneRampPaths.Port(q.position(),q.direction(),b.outside(),b.extraWidth(),delta.y()/Math.max(.001,delta.horizontalLength()));
      for(double d=.5;d<=tailLength+.001;d+=.5){var at=LanePoints.lane(raw,start+lane.sign()*d,slot);after.add(new Sample(at.position(),at.direction().left(),d,settings.width()/2));}
    }
    // A lead/tail follows a lane horizontally, but its vertical profile is free
    // for a closed-slot connector. Do not demand the entire rise inside the
    // short turn between them before the full corridor solver gets a chance.
    boolean wholeProfile=options.separatesLane()&&options.arrival()==LanePoints.Arrival.MERGE
        &&options.elevation()!=LanePoints.Elevation.KEEP&&(!before.isEmpty()||!after.isEmpty());
    double endHeight=b.position().y(),endGrade=b.grade(),startGrade=a.grade();
    if(wholeProfile)b=new LaneRampPaths.Port(new V(b.position().x(),a.position().y(),b.position().z()),b.direction(),b.outside(),b.extraWidth(),a.grade());
    // Flattening a free closed-slot profile above postpones only its HEIGHT
    // solve. Keep the real layer change when selecting a lateral escape;
    // otherwise the horizontal solver hugs both stacked hosts and forces a shelf.
    var smoothCandidates=smooth?LaneRampPaths.smoothTurns(a,b,settings,options,maxGrade,Math.abs(endHeight-a.position().y())>.5).stream()
        .filter(c->!constrainSmooth||matchesTurn(c.mesh(),options.path()))
        .map(c->constrainSmooth?new LaneRampPaths.Candidate(options.path(),c.mesh()):c).toList():List.<LaneRampPaths.Candidate>of();
    var candidates=new ArrayList<LaneRampPaths.Candidate>();
    if(automaticTurns)candidates.addAll(LaneRampPaths.automaticTurns(a,b,settings,options,maxGrade));
    try{candidates.addAll(LaneRampPaths.candidates(a,b,settings,options,maxGrade));}
    catch(IllegalArgumentException failure){if(candidates.isEmpty()&&smoothCandidates.isEmpty())throw failure;}
    candidates.sort(Comparator.comparingDouble(c->c.mesh().length()));
    // Smoothness may modestly increase length, but must not double the footprint.
    double shortest=candidates.stream().mapToDouble(c->c.mesh().length()).min().orElse(Double.POSITIVE_INFINITY);
    candidates.addAll(0,smoothCandidates.stream().filter(c->c.mesh().length()<=shortest*1.35).toList());
    if(before.isEmpty()&&after.isEmpty())return candidates;
    var out=new ArrayList<LaneRampPaths.Candidate>();
    for(var c:candidates)try{
      var samples=new ArrayList<>(before);samples.addAll(before.isEmpty()?c.mesh().samples():c.mesh().samples().subList(1,c.mesh().samples().size()));samples.addAll(after);
      if(wholeProfile){
        double length=0;for(int i=1;i<samples.size();i++)length+=samples.get(i).center().sub(samples.get(i-1).center()).horizontalLength();
        double fromY=samples.get(0).center().y(),toY=after.isEmpty()?endHeight:after.get(after.size()-1).center().y(),station=0;
        for(int i=0;i<samples.size();i++){var s=samples.get(i);if(i>0)station+=s.center().sub(samples.get(i-1).center()).horizontalLength();
          double y=LaneRampProfile.height(fromY,toY,startGrade,endGrade,length,station,maxGrade);
          samples.set(i,new Sample(new V(s.center().x(),y,s.center().z()),s.left(),station,s.halfWidth()));}
      }
      var mesh=RoadRibbon.mesh(samples,settings);RoadRibbon.checkSelfIntersections(mesh,4);out.add(new LaneRampPaths.Candidate(c.path(),mesh));
    }catch(IllegalArgumentException ignored){}
    return out;
  }
  static boolean matchesTurn(Mesh mesh,LanePoints.Path path){
    double turn=0;var samples=mesh.samples();
    for(int i=1;i<samples.size();i++){V a=samples.get(i-1).left(),b=samples.get(i).left();turn+=Math.atan2(a.x()*b.z()-a.z()*b.x(),a.dot(b));}
    return path==LanePoints.Path.RIGHT?turn>=-.001&&turn<=Math.PI+.001:
        path==LanePoints.Path.LEFT?turn<=.001&&turn>=-Math.PI-.001:true;
  }
  private record ApproachKey(double offset,boolean source,double width,double motorCenter,double transition){}
  private static LaneRampPaths.Port approach(Map<UUID,RoadRecord> all,LanePoints.Ref ref,double offset,boolean source,Settings settings,double transition){
    var route=chain(all,ref);var plan=CURRENT.get();
    if(plan==null)return buildApproach(route,offset,source,settings,transition);
    if(plan.approaches.size()>128)plan.approaches.clear();
    var cache=plan.approaches.computeIfAbsent(route,k->new HashMap<>());
    var key=new ApproachKey(offset,source,settings.width(),RoadProfile.layout(settings,settings.width()).motorCenter(),transition);
    return cache.computeIfAbsent(key,k->buildApproach(route,offset,source,settings,transition));
  }
  private static LaneRampPaths.Port buildApproach(LaneRoadChain route,double offset,boolean source,Settings settings,double transition){
    var location=route.at(offset);var road=location.road();var point=location.point();
    boolean tunnel=tunnelAt(road,location.station());
    double length=(tunnel?Math.max(64,transition*2):LaneRampApproach.taper(transition))+LaneRampApproach.parallel(transition);
    var port=extraPort(road,point,0);
    var samples=LaneRampApproach.build(d->{var next=route.at(offset+(source?d:d-length));
      return new LaneRampApproach.Station(mesh(next.road()),next.point().lane(),next.station());},source,settings,transition,tunnel);
    return new LaneRampPaths.Port(port.position(),port.direction(),port.outside(),port.extraWidth(),port.grade(),samples);
  }
  static Set<UUID> contactRoads(Map<UUID,RoadRecord> all,LanePoints.Ref ref){
    var plan=CURRENT.get();if(plan==null)return findContactRoads(all,ref);
    if(plan.connected.size()>64)plan.connected.clear();
    return plan.connected.computeIfAbsent(all,k->new HashMap<>()).computeIfAbsent(ref,k->findContactRoads(all,ref));
  }
  private static LaneRoadChain chain(Map<UUID,RoadRecord> all,LanePoints.Ref ref){
    var p=CURRENT.get();if(p==null)return LaneRoadChain.of(all,ref);
    if(p.chains.size()>64)p.chains.clear();
    return p.chains.computeIfAbsent(all,k->new HashMap<>()).computeIfAbsent(ref,k->LaneRoadChain.of(all,k));
  }
  private static Set<UUID> findContactRoads(Map<UUID,RoadRecord> all,LanePoints.Ref ref){
    if(ref.road()==null)return Set.of();
    return chain(all,ref).ids();
  }

  static int contactEnd(Mesh mesh,Map<UUID,RoadRecord> all,Set<UUID> hosts,boolean first){
    int n=mesh.samples().size(),i=first?0:n-1;
    var roads=hosts.stream().map(all::get).filter(Objects::nonNull).map(LaneRamps::mesh).toList();
    while(i>=0&&i<n){var sample=mesh.samples().get(i);boolean touching=false;
      for(var host:roads){var q=RoadQueries.horizontal(host,sample.center());
        if(q.horizontalDistance()<q.sample().halfWidth()+sample.halfWidth()+.15){touching=true;break;}}
      if(!touching)break;i+=first?1:-1;
    }
    return Math.max(0,Math.min(n-1,i+(first?2:-2)));
  }
  /** A declared joining throat follows its existing host elevation before it becomes free.
   * Only the contiguous first/last contact is eligible; later crossings are still obstacles. */
  private static Mesh fitHostContacts(Mesh mesh,Map<UUID,RoadRecord> all,LanePoints.Link link){
    // Closed-slot connectors retain their independent profile. Open BRANCH/FLOW
    // and highway auxiliary throats follow the selected lane and its outside
    // emergency shoulder until their paved ribbons separate. Other carriageways
    // must not flatten an unrelated overpass.
    if(link.protectedMerge()){
      if(link.options().departure()==LanePoints.Departure.BRANCH||link.options().sourceExtra()&&highwayHost(all,link.from()))mesh=LaneRampThroat.fit(mesh,normalHosts(all,link,true),true);
      if(link.options().arrival()==LanePoints.Arrival.FLOW||link.options().targetExtra()&&highwayHost(all,link.to()))mesh=LaneRampThroat.fit(mesh,normalHosts(all,link,false),false);
      return mesh;
    }
    var sourceIds=contactRoads(all,link.from());var targetIds=contactRoads(all,link.to());
    var source=sourceIds.stream().map(all::get).filter(Objects::nonNull).map(LaneRamps::mesh).toList();
    var target=targetIds.stream().map(all::get).filter(Objects::nonNull).map(LaneRamps::mesh).toList();
    double from=link.options().separatesLane()?0:mesh.samples().get(contactEnd(mesh,all,sourceIds,true)).distance();
    double to=link.closesTarget()?mesh.length():mesh.samples().get(contactEnd(mesh,all,targetIds,false)).distance();
    if(from>=to)return mesh;
    double ease=Math.min(200,Math.max(2,(to-from)/3));var samples=new ArrayList<Sample>();
    for(var sample:mesh.samples()){
      double ws=source.isEmpty()||link.options().separatesLane()?0:1-Settings.smooth(Math.max(0,Math.min(1,(sample.distance()-from)/ease)));
      double wt=target.isEmpty()||link.closesTarget()?0:1-Settings.smooth(Math.max(0,Math.min(1,(to-sample.distance())/ease)));
      double dy=((ws>0?ws*hostHeightDelta(sample,source):0)+(wt>0?wt*hostHeightDelta(sample,target):0))/Math.max(1,ws+wt);
      samples.add(new Sample(sample.center().add(new V(0,dy,0)),sample.left(),sample.distance(),sample.halfWidth()));
    }
    return RoadRibbon.mesh(samples,mesh.settings());
  }
  private static boolean highwayHost(Map<UUID,RoadRecord> all,LanePoints.Ref ref){if(ref.road()==null)return false;var r=all.get(ref.road());return r!=null&&RoadProfile.highway(r.settings().style());}
  private static List<Mesh> normalHosts(Map<UUID,RoadRecord> all,LanePoints.Link link,boolean first){
    var ref=first?link.from():link.to();if(ref.road()==null)return List.of();
    var out=new ArrayList<Mesh>();
    // Single-lane parents (including free ramps) share their full paved ribbon.
    // For a multi-lane host use only the selected lane: opposite lanes remain obstacles.
    for(var leg:chain(all,ref).legs()){
      var host=mesh(leg.road());
      if(RoadProfile.catalog(host.settings()).lanes()==1)out.add(host);
      else {var points=new ArrayList<Sample>();for(var at:host.samples()){
        var lane=(first?link.options().sourceExtra():link.options().targetExtra())?LaneRampApproach.outerLane(host,at.distance(),leg.slot()):LanePoints.group(host,at.distance(),leg.slot(),link.options().lanes());
        double center=lane.position().sub(at.center()).dot(at.left()),low=center-lane.width()/2,high=center+lane.width()/2;
        // A highway's outside emergency shoulder is part of the joining surface.
        // Do not start climbing/descending while the ramp still crosses it.
        if(RoadProfile.highway(host.settings().style())){var layout=RoadProfile.layout(host,at);
          if(Math.abs(low-layout.motorMin())<.01)low=-at.halfWidth();
          if(Math.abs(high-layout.motorMax())<.01)high=at.halfWidth();
        }
        points.add(new Sample(at.at((low+high)/2,0),at.left(),at.distance(),(high-low)/2));
      }out.add(RoadRibbon.mesh(points,new Settings(Mode.CURVE,Style.C1_RAMP,4,host.settings().thickness(),.35,90)));}
    }
    for(var road:all.values()){
      var other=LaneTopology.metadata(road).link();if(other==null)continue;
      boolean sibling=first?other.from().equals(link.from())&&!other.to().equals(link.to())&&other.options().departure()==LanePoints.Departure.BRANCH
          :other.to().equals(link.to())&&!other.from().equals(link.from())&&other.options().arrival()==LanePoints.Arrival.FLOW;
      if(sibling)out.add(mesh(road));
    }
    return List.copyOf(out);
  }
  private static double hostHeightDelta(Sample sample,List<Mesh> hosts){
    double distance=Double.POSITIVE_INFINITY,delta=0;
    for(var host:hosts){var q=RoadQueries.horizontal(host,sample.center());if(q.horizontalDistance()<distance){distance=q.horizontalDistance();delta=q.sample().center().y()-sample.center().y();}}
    return delta;
  }
  private record Obstacle(UUID road,RoadClearance.Contact contact,boolean structure){Obstacle(UUID road,RoadClearance.Contact contact){this(road,contact,false);}}
  private static List<Obstacle> crossings(Mesh mesh,Map<UUID,RoadRecord> all,UUID id,LanePoints.Link link){return crossings(mesh,all,id,link,null);}
  private static List<Obstacle> crossings(Mesh mesh,Map<UUID,RoadRecord> all,UUID id,LanePoints.Link link,Set<UUID> changed){return crossings(mesh,all,id,link,changed,false);}
  private static List<Obstacle> crossings(Mesh mesh,Map<UUID,RoadRecord> all,UUID id,LanePoints.Link link,Set<UUID> changed,boolean firstConflict){
    var out=new ArrayList<Obstacle>();var sourceHosts=contactRoads(all,link.from());var targetHosts=contactRoads(all,link.to());
    double sourceLimit=mesh.samples().get(contactEnd(mesh,all,sourceHosts,true)).distance();
    double targetLimit=mesh.samples().get(contactEnd(mesh,all,targetHosts,false)).distance();
    for(var road:all.values()){
      RoadPlanningBudget.check();
      if(changed!=null&&!changed.contains(road.id()))continue;
      if(road.id().equals(id)||road.junction()!=null&&Objects.equals(road.assembly(),link.to().junction()))continue;
      var other=LaneTopology.metadata(road).link();
      // A dependent branch is regenerated after this parent. Its own validator then checks the
      // new parent outside its local throat; unrelated siblings are NOT globally exempted.
      if(other!=null&&(id.equals(other.from().road())||id.equals(other.to().road())))continue;
      Mesh old=mesh(road);
      double sharedStart=-1,sharedEnd=mesh.length()+1;
      if(other!=null&&link.from().equals(other.from()))sharedStart=mesh.samples().get(contactEnd(mesh,Map.of(road.id(),road),Set.of(road.id()),true)).distance();
      if(other!=null&&link.to().equals(other.to()))sharedEnd=mesh.samples().get(contactEnd(mesh,Map.of(road.id(),road),Set.of(road.id()),false)).distance();
      if(sharedStart>mesh.length()*.9||sharedEnd<mesh.length()*.1)throw new IllegalArgumentException("同一车道点的两条匝道几乎全程重合，请使用不同汇入方向");
      // Saved bridge supports and furniture are constraints even on ordinary roads.
      // Parent ramps and siblings remain obstacles outside their actual local
      // throat. Exempting an entire related ramp hid later furniture collisions
      // from the solver and left only a construction-time veto.
      // Ordinary tunnel walls/roofs are obstacles too. Only the actual joining
      // throat below can be opened; an unrelated tunnel or a later crossing of
      // the same host must constrain the height search before final validation.
      if(RoadIndex.overlapXZ(mesh,old,8))
        for(var part:routingStructures(mesh,road,all,sourceHosts,targetHosts))for(var c:RoadClearance.structureContacts(part,mesh,4.25)){
          if(sourceHosts.contains(road.id())&&c.to()<=sourceLimit+.01||targetHosts.contains(road.id())&&c.from()>=targetLimit-.01
              ||c.to()<=sharedStart+.01||c.from()>=sharedEnd-.01)continue;
          if(addObstacle(out,new Obstacle(road.id(),c,true),all,firstConflict))return out;
        }
      if(!RoadIndex.overlapXZ(mesh,old,0))continue;
      var exactSlots=new LinkedHashSet<Integer>();
      if(link.protectedMerge()){
        for(var leg:chain(all,link.from()).legs())if(leg.road().id().equals(road.id())){if(link.options().sourceExtra())exactSlots.add(LaneRampApproach.outerLane(old,leg.station(0),leg.slot()).index());else for(int n=0;n<link.options().lanes();n++)exactSlots.add(leg.slot()+n);}
        if(link.to().road()!=null)for(var leg:chain(all,link.to()).legs())if(leg.road().id().equals(road.id())){
          if(link.options().arrival()==LanePoints.Arrival.ADD){for(var added:LaneTopology.metadata(road).additions())if(added.connection().equals(id))exactSlots.add(added.slot());}
          else if(link.options().targetExtra())exactSlots.add(LaneRampApproach.outerLane(old,leg.station(link.targetOffset()),leg.slot()).index());else for(int n=0;n<link.options().lanes();n++)exactSlots.add(leg.slot()+n);
        }
      }
      if(!exactSlots.isEmpty()){
        Mesh protectedDeck=protectedDeck(old,exactSlots);
        for(var c:contacts(mesh,protectedDeck))if(addObstacle(out,new Obstacle(road.id(),c),all,firstConflict))return out;
      }
      // EXTRA authorizes only its actual taper + parallel lane. The broad paved
      // contact can remain connected through a tiny shoulder overlap; it must not
      // exempt a separate crossing of the selected through lane before/after that
      // auxiliary approach. This was letting a raised ramp occupy the host lane.
      boolean extraSource=link.options().sourceExtra()&&sourceHosts.contains(road.id());
      boolean extraTarget=link.options().targetExtra()&&targetHosts.contains(road.id());
      if(extraSource||extraTarget){
        double begin=extraSource?fixedApproach(mesh,all,link,true):-1;
        double end=extraTarget?mesh.length()-fixedApproach(mesh,all,link,false):mesh.length()+1;
        for(var c:contacts(mesh,protectedDeck(old,Set.of())))
          if(!(extraSource&&c.to()<=begin+.01||extraTarget&&c.from()>=end-.01))if(addObstacle(out,new Obstacle(road.id(),c),all,firstConflict))return out;
      }
      for(var c:contacts(mesh,old)){
        boolean sourceJoin=sourceHosts.contains(road.id())&&c.to()<=sourceLimit+.01;
        if(sourceJoin&&link.options().separatesLane())sourceJoin=separationThroat(c,road,link,all);
        if(sourceJoin||targetHosts.contains(road.id())&&c.from()>=targetLimit-.01)continue;
        if(c.to()<=sharedStart+.01||c.from()>=sharedEnd-.01)continue;
        if(addObstacle(out,new Obstacle(road.id(),c),all,firstConflict))return out;
      }
    }return out;
  }
  private static boolean addObstacle(List<Obstacle> out,Obstacle obstacle,Map<UUID,RoadRecord> all,boolean firstConflict){
    out.add(obstacle);return firstConflict&&obstacle.contact().blocked();
  }
  /** A slot exclusion depends only on the immutable host mesh and the selected
   * slot set, not on a candidate's height. Reuse exactly the same protected deck. */
  private static Mesh protectedDeck(Mesh host,Set<Integer> slots){
    var key=List.copyOf(slots);var plan=CURRENT.get();
    if(plan==null)return makeProtectedDeck(host,key);
    if(plan.protectedDecks.size()>128)plan.protectedDecks.clear();
    return plan.protectedDecks.computeIfAbsent(host,k->new HashMap<>()).computeIfAbsent(key,k->makeProtectedDeck(host,k));
  }
  private static Mesh makeProtectedDeck(Mesh host,List<Integer> slots){
    var result=LaneDeck.motorOnly(host);for(int slot:slots)result=LaneDeck.excludingSlot(result,slot);return result;
  }
  /** Only the selected slot joins; opposite traffic is not exempt merely because it has the same host ID. */
  private static boolean separationThroat(RoadClearance.Contact contact,RoadRecord road,LanePoints.Link link,Map<UUID,RoadRecord> all){
    for(var leg:chain(all,link.from()).legs())if(leg.road().id().equals(road.id())){
      var raw=road.rawMesh();var q=RoadQueries.horizontal(raw,contact.ours());var lane=LanePoints.group(raw,q.sample().distance(),leg.slot(),link.options().lanes());
      // The fitted mouth includes the actual outside shoulder (one block in the
      // saved ordinary-road case), not a fixed .55 m allowance. Neighboring motor
      // slots are still checked independently by protectedDeck above.
      var at=q.sample();var layout=RoadProfile.layout(raw,at);
      double center=lane.position().sub(at.center()).dot(at.left());
      double low=center-lane.width()/2,high=center+lane.width()/2;
      if(Math.abs(low-layout.motorMin())<1e-5)low=-at.halfWidth();
      if(Math.abs(high-layout.motorMax())<1e-5)high=at.halfWidth();
      double offset=contact.ours().sub(at.center()).dot(at.left());
      if(link.protectedMerge()){
        // The exact protected deck above already tests every other motor lane
        // and the median. Remaining motor-band contacts are this connector's
        // own closure ledges. Reprojecting their curved polygon witness onto a
        // normalized lane frame is not a second valid boundary test: resampling
        // the reservation moves that projection by micrometres.
        low=Math.min(low,layout.motorMin());high=Math.max(high,layout.motorMax());
      }
      return leg.coordinate(lane.station())>=-.25&&offset>=low-1e-5&&offset<=high+1e-5;
    }return false;
  }
  static LaneRampPaths.Port resolvedArrival(Map<UUID,RoadRecord> all,LanePoints.Link link,UUID id){
    var position=chain(all,link.to()).at(link.targetOffset());
    return arrivalPort(position.road(),position.point(),0,link,id);
  }
  private static double[] terrainFloors(Mesh mesh,LanePoints.Link link){return LaneRampTerrain.floors(mesh,link.options(),CURRENT.get()==null?null:CURRENT.get().terrain);}
  /** Defer the mixed beam until every cheap profile fails final world validation.
   * A profile solving its height bounds does not prove its deck/structure is clear. */
  private static Iterable<Mesh> heightSearch(Mesh base,Map<UUID,RoadRecord> all,UUID id,LanePoints.Link link,Map<LanePoints.Path,String> errors,LanePoints.Path path){
    return ()->new Iterator<>(){
      Iterator<Mesh> current=heightCandidates(base,all,id,link,errors,path,false,false).iterator();boolean fallback;
      public boolean hasNext(){if(current.hasNext())return true;if(fallback)return false;fallback=true;current=heightCandidates(base,all,id,link,errors,path,false,true).iterator();return current.hasNext();}
      public Mesh next(){if(!hasNext())throw new NoSuchElementException();return current.next();}
    };
  }
  private static List<Mesh> heightCandidates(Mesh base,Map<UUID,RoadRecord> all,UUID id,LanePoints.Link link,Map<LanePoints.Path,String> errors,LanePoints.Path path,boolean monotoneOnly){
    var result=new ArrayList<>(heightCandidates(base,all,id,link,errors,path,monotoneOnly,false));
    result.addAll(heightCandidates(base,all,id,link,errors,path,monotoneOnly,true));
    if(link.options().elevation()==LanePoints.Elevation.AUTO)result.sort(heightOrder(base));
    return result;
  }
  private static List<Mesh> heightCandidates(Mesh base,Map<UUID,RoadRecord> all,UUID id,LanePoints.Link link,Map<LanePoints.Path,String> errors,LanePoints.Path path,boolean monotoneOnly,boolean fallbackOnly){
    var mode=link.options().elevation();var out=new ArrayList<Mesh>();
    
    double flatY=base.first().center().y();
    boolean fixedFlat=monotoneOnly&&base.last().center().y()==flatY&&base.samples().stream().allMatch(s->s.center().y()==flatY);
    List<Obstacle> contacts=crossings(base,all,id,link,null,fixedFlat);
    double[] floors=terrainFloors(base,link);boolean earthClear=LaneRampTerrain.clear(base,floors);
    boolean clear=contacts.stream().noneMatch(c->c.contact().blocked());
    if(fixedFlat&&(!clear||!earthClear)){
      // Equal-height monotone ports have exactly one possible height profile.
      // Once that flat profile conflicts, OVER/UNDER and every mixed beam choice
      // are infeasible too. Later unrestricted passes still try every route.
      errors.put(path,"等高端口的单调候选与实际障碍冲突"+obstacleSummary(base,contacts));return List.of();
    }
    if(mode==LanePoints.Elevation.KEEP)return List.of(base);
    if(mode==LanePoints.Elevation.AUTO&&clear&&earthClear&&(!monotoneOnly||monotone(base))){
      // A horizontal candidate may already carry a loop lift or a mouth-induced crest.
      // Try removing it before accepting it, with the same ports, terrain and final checks.
      if(!monotone(base))try{
        double start=link.protectedMerge()?fixedApproach(base,all,link,true):link.options().separatesLane()?0:base.samples().get(contactEnd(base,all,contactRoads(all,link.from()),true)).distance();
        double end=link.protectedMerge()?base.length()-fixedApproach(base,all,link,false):link.closesTarget()?base.length():base.samples().get(contactEnd(base,all,contactRoads(all,link.to()),false)).distance();
        var relaxed=LaneRampCorridor.solveMixed(base,start,end,List.of(),gradeLimit(all,link),true,floors);
        return List.of(relaxed,base);
      }catch(IllegalArgumentException noMonotoneProfile){/* Real port/terrain constraints retain the original candidate. */}
      return List.of(base);
    }
    if(contacts.isEmpty()&&earthClear&&(!monotoneOnly||monotone(base)))return List.of(base);
    double from=link.protectedMerge()?fixedApproach(base,all,link,true):link.options().separatesLane()?0:base.samples().get(contactEnd(base,all,contactRoads(all,link.from()),true)).distance();
    double to=link.protectedMerge()?base.length()-fixedApproach(base,all,link,false):link.closesTarget()?base.length():base.samples().get(contactEnd(base,all,contactRoads(all,link.to()),false)).distance();
    if(fallbackOnly)return contacts.isEmpty()?List.of():mixedCandidates(base,from,to,contacts,all,gradeLimit(all,link),monotoneOnly,floors,mode);
    for(boolean over:mode==LanePoints.Elevation.UNDER?new boolean[]{false}:mode==LanePoints.Elevation.OVER?new boolean[]{true}:new boolean[]{true,false}){
      var constraints=new ArrayList<LaneRampCorridor.Bound>();
      for(var obstacle:contacts){var c=obstacle.contact();constraints.add(new LaneRampCorridor.Bound(c.from(),c.to(),over?c.raiseBound():c.lowerBound(),over));}
      try{out.add(LaneRampCorridor.solveMixed(base,from,to,constraints,gradeLimit(all,link),monotoneOnly,floors));}
      catch(IllegalArgumentException e){errors.put(path,e.getMessage());}
    }
    if(mode==LanePoints.Elevation.AUTO){
      // Uniform OVER/UNDER is not sufficient when roads on different levels bound
      // the same connector. Choose the locally reachable side of each real contact.
      var mixed=new ArrayList<LaneRampCorridor.Bound>();double grade=gradeLimit(all,link);
      for(var obstacle:contacts){
        var c=obstacle.contact();var other=all.get(obstacle.road());var saved=other==null||obstacle.structure()?null:LaneTopology.metadata(other).link();
        double d=Math.max(0,Math.min(base.length(),(c.from()+c.to())/2));var at=RoadStructures.sample(base,d);
        double reachLow=Math.max(base.first().center().y()-grade*d,base.last().center().y()-grade*(base.length()-d));
        double reachHigh=Math.min(base.first().center().y()+grade*d,base.last().center().y()+grade*(base.length()-d));
        boolean up=at.center().y()+c.raiseBound()<=reachHigh+1e-6,down=at.center().y()-c.lowerBound()>=reachLow-1e-6;
        boolean over=saved!=null&&saved.options().elevation()==LanePoints.Elevation.UNDER||
            !(saved!=null&&saved.options().elevation()==LanePoints.Elevation.OVER)&&
            (up&&!down||up==down&&(c.blocked()?c.raiseBound()<=c.lowerBound():c.ours().y()>=c.other().y()));
        mixed.add(new LaneRampCorridor.Bound(c.from(),c.to(),over?c.raiseBound():c.lowerBound(),over));
      }
      try{out.add(LaneRampCorridor.solveMixed(base,from,to,mixed,grade,monotoneOnly,floors));}catch(IllegalArgumentException e){
        if(out.isEmpty())errors.put(path,e.getMessage()+obstacleSummary(base,contacts));
      }
    }
    if(out.isEmpty()&&!contacts.isEmpty())errors.put(path,errors.getOrDefault(path,"没有满足净空的纵坡方案")+obstacleSummary(base,contacts));
    if(out.isEmpty()&&!errors.containsKey(path))errors.put(path,"没有满足端点、坡度及净空的自动跨越方案");
    if(mode==LanePoints.Elevation.AUTO)out.sort(heightOrder(base));
    return out;
  }
  /** Match the host's actual portal aperture instead of exempting its whole shell.
   * Lining reaches beyond the deck, so its opening uses the same widened contact
   * interval and six-metre air prism as RoadInteractions.openPortal. Retained
   * roof/side fragments and later crossings still constrain the route. */
  private static List<RoadStructures.Part> routingStructures(Mesh mesh,RoadRecord road,Map<UUID,RoadRecord> all,Set<UUID> source,Set<UUID> target){
    boolean start=source.contains(road.id()),end=target.contains(road.id());
    // Match the final structure planner: the fascia below a selected host's
    // joining floor is shared slab volume, not a second overhead obstacle.
    // Rails, piers and any concrete above the ramp surface remain constraints.
    var structures=start||end?road.structures().stream().filter(p->!RoadClearance.joiningFloor(p,road.mesh(),mesh)).toList():road.structures();
    if(road.settings().structure()!=Structure.TUNNEL||!start&&!end)return structures;
    var lining=RoadRibbon.mesh(mesh.samples().stream().map(s->new Sample(s.center(),s.left(),s.distance(),s.halfWidth()+RoadAutoTunnels.LINING)).toList(),mesh.settings());
    var openings=new ArrayList<RoadShellOpening>();
    if(start)openings.add(new RoadShellOpening(mesh,0,lining.samples().get(contactEnd(lining,all,source,true)).distance(),d->RoadAutoTunnels.HEADROOM));
    if(end)openings.add(new RoadShellOpening(mesh,lining.samples().get(contactEnd(lining,all,target,false)).distance(),mesh.length(),d->RoadAutoTunnels.HEADROOM));
    var kept=new ArrayList<RoadStructures.Part>();
    for(var part:structures){
      if(part.material()!=RoadStructures.Material.TUNNEL&&part.material()!=RoadStructures.Material.SIGN_WHITE&&part.material()!=RoadStructures.Material.LAMP
          ||part.model().equals("sr:tunnel_ceiling_light")||part.model().equals("sr:auto_light")){kept.add(part);continue;}
      var pieces=List.of(part);
      for(var opening:openings){var next=new ArrayList<RoadStructures.Part>();for(var piece:pieces)next.addAll(opening.cut(piece));pieces=next;}
      kept.addAll(pieces);
    }
    return kept;
  }
  private static String obstacleSummary(Mesh base,List<Obstacle> contacts){
    if(contacts.isEmpty())return "";
    // Name real obstacle identities and both vertical alternatives, not just a
    // propagated solver sample that can be several blocks away from the obstacle.
    var c=contacts.stream().filter(v->v.contact().blocked()).min(Comparator.comparingDouble(v->Math.min(v.contact().from(),base.length()-v.contact().to()))).orElse(contacts.get(0));
    var v=c.contact();return String.format(Locale.ROOT,"；实际冲突道路 %s，交叠区在候选路线距 A 沿线 %.1f–%.1f 格（路线全长 %.1f 格，AB 水平直距 %.1f 格），%s；要求净空 %.2f 格，上跨需抬升 %.2f 格，下穿需降低 %.2f 格",c.road(),v.from(),v.to(),base.length(),base.last().center().sub(base.first().center()).horizontalLength(),RoadClearance.clearanceLabel(v.usableClearance()),v.required(),v.raise(),v.lower());
  }
  static boolean monotone(Mesh m){return verticalEffort(m)<=Math.abs(m.last().center().y()-m.first().center().y())+1e-5;}
  /** An unchanged legacy preview must not keep a sharp grade corner forever.
   * Smooth saved routes retain the fast path; restoration-only edits keep their
   * exact validated alignment, and KEEP remains an explicit height-preservation mode. */
  static boolean verticalKink(Mesh mesh){
    var points=mesh.samples();double previous=0,previousRun=0;
    for(int i=1;i<points.size();i++){
      V delta=points.get(i).center().sub(points.get(i-1).center());double run=delta.horizontalLength();if(run<1e-6)continue;
      double grade=delta.y()/run,change=Math.abs(grade-previous);
      if(previousRun>0&&change>.02&&change/((run+previousRun)/2)>.03)return true;
      previous=grade;previousRun=run;
    }
    return false;
  }
  /** Keep several feasible layer assignments. A locally cheaper underpass must not
   * rule out an overpass required by the NEXT ramp. Triangle contacts share a group. */
  /** The running maximum is exactly the old group's max(to). Rescanning all
   * previous triangle contacts for every insertion made this step quadratic. */
  private static List<List<Obstacle>> groupedContacts(List<Obstacle> contacts){
    var groups=new ArrayList<List<Obstacle>>();double groupEnd=0;
    for(var obstacle:contacts.stream().sorted(Comparator.comparing((Obstacle o)->o.road().toString()).thenComparingDouble(o->o.contact().from())).toList()){
      List<Obstacle> group=groups.isEmpty()?null:groups.get(groups.size()-1);
      if(group==null||!group.get(0).road().equals(obstacle.road())||group.get(0).structure()!=obstacle.structure()
          ||obstacle.contact().from()>groupEnd+2){group=new ArrayList<>();groups.add(group);groupEnd=Double.NEGATIVE_INFINITY;}
      group.add(obstacle);groupEnd=Math.max(groupEnd,obstacle.contact().to());
    }
    groups.sort(Comparator.comparingDouble(g->g.get(0).contact().from()));
    return groups;
  }
  private static List<Mesh> mixedCandidates(Mesh base,double from,double to,List<Obstacle> contacts,Map<UUID,RoadRecord> all,double grade,boolean strict,double[] floors,LanePoints.Elevation preference){
    var groups=groupedContacts(contacts);
    record Choice(List<LaneRampCorridor.Bound> bounds,Mesh mesh,int secondary){}
    var beam=new ArrayList<Choice>();beam.add(new Choice(List.of(),base,0));
    for(var group:groups){var next=new ArrayList<Choice>();
      for(var choice:beam)for(boolean over:preference==LanePoints.Elevation.UNDER?new boolean[]{false,true}:new boolean[]{true,false}){
        RoadPlanningBudget.check();
        var bounds=new ArrayList<>(choice.bounds());
        for(var o:group){var c=o.contact();bounds.add(new LaneRampCorridor.Bound(c.from(),c.to(),over?c.raiseBound():c.lowerBound(),over));}
        try{next.add(new Choice(List.copyOf(bounds),LaneRampCorridor.solveMixed(base,from,to,bounds,grade,strict,floors),choice.secondary()+(preference==LanePoints.Elevation.OVER&&!over||preference==LanePoints.Elevation.UNDER&&over?1:0)));}catch(IllegalArgumentException ignored){}
      }
      next.sort(Comparator.comparingInt(Choice::secondary).thenComparing(Choice::mesh,heightOrder(base)));
      beam=new ArrayList<>(next.subList(0,Math.min(16,next.size())));if(beam.isEmpty())break;
    }
    return beam.stream().map(Choice::mesh).toList();
  }
  private static double verticalDisplacement(Mesh base,Mesh candidate){double sum=0;for(int i=0;i<candidate.samples().size();i++)sum+=Math.abs(candidate.samples().get(i).center().y()-base.samples().get(i).center().y());return sum;}
  /** Prefer the least departure from the endpoint profile. Total vertical travel
   * alone always favors a monotone climb, even over a much smaller underpass dip. */
  private static Comparator<Mesh> heightOrder(Mesh base){return Comparator.<Mesh>comparingDouble(m->verticalDisplacement(base,m)).thenComparingDouble(LaneRamps::verticalEffort);}
  private static double verticalEffort(Mesh m){double total=0;for(int i=1;i<m.samples().size();i++)total+=Math.abs(m.samples().get(i).center().y()-m.samples().get(i-1).center().y());return total;}
  private static double fixedApproach(Mesh ramp,Map<UUID,RoadRecord> all,LanePoints.Link link,boolean source){
    if(source&&link.options().departure()==LanePoints.Departure.BRANCH||!source&&link.options().arrival()==LanePoints.Arrival.FLOW){
      int end=LaneRampThroat.end(ramp,normalHosts(all,link,source),source);
      return source?ramp.samples().get(end).distance():ramp.length()-ramp.samples().get(end).distance();
    }
    if(source?!link.options().sourceExtra():!link.options().targetExtra())return 0;
    var ref=source?link.from():link.to();if(ref.road()==null)return 0;
    var samples=approach(all,ref,source?0:link.targetOffset(),source,ramp.settings(),link.options().transition()).approach();
    double span=0;for(int i=1;i<samples.size();i++)span+=samples.get(i).center().distance(samples.get(i-1).center());
    return Math.min(ramp.length(),span);
  }
  /** Validate only new/changed obstacle decks against an unchanged saved connector. */
  static void validateChanges(Mesh mesh,Map<UUID,RoadRecord> all,UUID id,LanePoints.Link link,Set<UUID> changed){
    // This final validation runs outside route generation. The map is immutable
    // throughout this call: reuse its lane chains and prepared obstacle meshes
    // instead of rebuilding a complete chain for every contact witness.
    try(var ignored=new Planning((RoadData)null)){
      for(var obstacle:crossings(mesh,all,id,link,changed)){
        if(obstacle.contact().blocked())
          throw new IllegalArgumentException("与本次修改道路 "+obstacle.road()+" 的净空或上下关系冲突");
      }
    }
  }
  static void validate(Mesh mesh,Map<UUID,RoadRecord> all,UUID id,LanePoints.Link link){
    LaneRampTerrain.validate(mesh,link.options(),CURRENT.get()==null?null:CURRENT.get().terrain);
    RoadRibbon.checkSelfIntersections(mesh,4);LaneRampPaths.checkVolume(mesh);
    LaneRampGrade.validate(mesh,gradeLimit(all,link));
    for(var other:all.values()){
      var saved=LaneTopology.metadata(other).link();
      if(saved==null||other.id().equals(id)||other.id().equals(link.from().road())||other.id().equals(link.to().road())
          ||id.equals(saved.from().road())||id.equals(saved.to().road())
          ||saved.from().equals(link.from())||saved.to().equals(link.to())||!RoadIndex.overlapXZ(mesh,mesh(other),2))continue;
      for(var part:other.structures())if(RoadClearance.structureInvades(part,mesh,4.25))
        throw new IllegalArgumentException("候选路线侵入已建匝道 "+other.id()+" 的设施，不能通过重建旧匝道腾出空间");
    }
    for(var obstacle:crossings(mesh,all,id,link)){
      var c=obstacle.contact();var mode=link.options().elevation();
      if(c.blocked())throw new IllegalArgumentException("与道路 "+obstacle.road()+" 冲突："+new RoadClearance.Conflict(c).getMessage());
    }
  }
  /** Classify from real source/target hosts, never from the edited ramp skin. An
   * ordinary-looking connector chained from a highway cannot raise its limit to 25%.
   * Junction arms come from their actual saved spec, not an invented client flag. */
  static double gradeLimit(Map<UUID,RoadRecord> all,LanePoints.Link link){
    var roads=new ArrayDeque<UUID>();var junctions=new LinkedHashSet<UUID>();
    for(var ref:List.of(link.from(),link.to())){if(ref.road()!=null)roads.add(ref.road());else junctions.add(ref.junction());}
    var seen=new HashSet<UUID>();boolean highway=false;
    while(!roads.isEmpty()){
      UUID id=roads.removeFirst();if(!seen.add(id))continue;
      var road=all.get(id);if(road==null)throw new IllegalArgumentException("坡比判定的关联道路已不存在，请重新选择");
      highway|=RoadProfile.highway(road.settings().style());
      var parent=LaneTopology.metadata(road).link();if(parent!=null)for(var ref:List.of(parent.from(),parent.to())){
        if(ref.road()!=null)roads.add(ref.road());else junctions.add(ref.junction());
      }
    }
    for(UUID junction:junctions){boolean found=false;
      for(var road:all.values())if(junction.equals(road.assembly())&&road.junction()!=null){
        found=true;for(var arm:road.junction().spec().arms())highway|=RoadProfile.highway(arm.external().style());
      }
      // Missing classification must not silently grant the more permissive ordinary cap.
      if(!found)highway=true;
    }
    return LaneRampGrade.limit(highway,link.options().gradeOverride());
  }
  private static boolean contact(Sample s,Mesh host,double limit,V end){var q=RoadQueries.horizontal(host,s.center());return q.horizontalDistance()<q.sample().halfWidth()+s.halfWidth()+1&&Math.abs(q.sample().center().y()-s.center().y())<.18;}
  private static int signature(RoadData data,LanePoints.Ref ref){if(ref.junction()!=null)return data.junctions.getOrDefault(ref.junction(),new CompoundTag()).hashCode();var b=data.index.roads.get(ref.road());if(b==null)throw new IllegalArgumentException("道路已不存在");return b.record.header().hashCode();}
  static void selection(ServerPlayer p,ItemStack tool,CompoundTag t){
    if(t.hasUUID("Id")){
      var built=RoadData.get(p.serverLevel()).index.roads.get(t.getUUID("Id"));
      if(built==null||LaneTopology.metadata(built.record).link()==null||built.record.assembly()!=null)throw new IllegalArgumentException("所选独立匝道已不存在");
      RoadData.requireOwner(p,built.record.owner());
      if(t.getInt("Signature")!=built.record.header().hashCode())throw new IllegalArgumentException("匝道已改变，请重新打开");
      var link=LaneTopology.metadata(built.record).link();
      if(!LanePointCodec.ref(link.from()).equals(t.getCompound("From"))||!LanePointCodec.ref(link.to()).equals(t.getCompound("To")))throw new IllegalArgumentException("不能在编辑时替换车道点引用");
      return;
    }
    var state=tool.getOrCreateTag();
    if(!state.contains("LaneFrom")||!state.contains("LaneTo")||!state.getCompound("LaneFrom").equals(t.getCompound("From"))||!state.getCompound("LaneTo").equals(t.getCompound("To"))||!p.level().dimension().location().toString().equals(state.getString("LaneDimension")))throw new IllegalArgumentException("匝道选择已失效，请先选汇出点，再选汇入点");
  }
  public static boolean tunnelEnd(Map<UUID,RoadRecord> all,LanePoints.Ref ref){
    if(ref.road()==null)return false;
    var road=host(all,ref);
    double station=LanePoints.lane(mesh(road),LaneTopology.point(road,ref.point())).station();
    return tunnelAt(road,station);
  }
  private static boolean tunnelAt(RoadRecord road,double station){
    if(road.settings().structure()==Structure.TUNNEL)return true;
    return RoadAutoTunnels.regions(road.structures()).stream().anyMatch(r->station>=r.from()-1e-6&&station<=r.to()+1e-6);
  }
  private static boolean currentApproaches(Mesh candidate,Map<UUID,RoadRecord> all,LanePoints.Link link){
    for(boolean source:new boolean[]{true,false}){
      if(source?!link.options().sourceExtra():!link.options().targetExtra())continue;
      var position=chain(all,source?link.from():link.to()).at(source?0:link.targetOffset());
      var lane=LanePoints.lane(mesh(position.road()),position.point());
      var expected=approach(all,source?link.from():link.to(),source?0:link.targetOffset(),source,candidate.settings(),link.options().transition()).approach();
      for(int i=0;i<expected.size();i+=8){var authored=expected.get(i);
        var mouth=LaneRampAlignment.Mouth.extra(lane.width(),RoadProfile.trafficSign(candidate.settings().options().leftTraffic()));
        V center=authored.center().add(authored.left().mul(LaneRampAlignment.auxiliaryShift(authored.halfWidth()*2,mouth,candidate.settings().width())));
        var actual=RoadQueries.horizontal(candidate,center);
        if(actual.horizontalDistance()>.05||Math.abs(actual.sample().halfWidth()-authored.halfWidth())>.01)return false;
      }
    }
    return true;
  }
  public static boolean rampHost(RoadRecord road){return road!=null&&(road.settings().style().ramp()||LaneTopology.metadata(road).link()!=null);}
  private static boolean rampEnd(Map<UUID,RoadRecord> all,LanePoints.Ref ref){return ref.road()!=null&&rampHost(all.get(ref.road()));}
  private static boolean highwayEnd(Map<UUID,RoadRecord> all,LanePoints.Ref ref){
    if(ref.road()==null)return false;
    var road=host(all,ref);
    return !rampHost(road)&&RoadProfile.catalog(road.settings()).type()==RoadProfile.Type.HIGHWAY;
  }
  static void validateTunnelEnds(Map<UUID,RoadRecord> all,LanePoints.Ref from,LanePoints.Ref to,LanePoints.Options options){
    boolean sourceRamp=rampEnd(all,from),targetRamp=rampEnd(all,to);
    if(highwayEnd(all,from)&&!options.sourceExtra())throw new IllegalArgumentException("高速车道点只允许额外扩出");
    if(highwayEnd(all,to)&&!options.targetExtra())throw new IllegalArgumentException("高速车道点只允许额外扩入");
    if(sourceRamp&&options.departure()!=LanePoints.Departure.BRANCH)throw new IllegalArgumentException("匝道车道点仅支持普通分流");
    if(targetRamp&&options.arrival()!=LanePoints.Arrival.FLOW)throw new IllegalArgumentException("匝道车道点仅支持普通合流");
    if(!sourceRamp&&tunnelEnd(all,from)&&!options.sourceExtra())throw new IllegalArgumentException("隧道汇出端只允许额外汇出，请重新选择或编辑匝道");
    if(!targetRamp&&tunnelEnd(all,to)&&!options.targetExtra())throw new IllegalArgumentException("隧道汇入端只允许额外汇入，请重新选择或编辑匝道");
  }
  public static CompoundTag tunnelChoices(CompoundTag t,Map<UUID,RoadRecord> all){
    if(!t.contains("From")||!t.contains("To"))return t;
    var from=LanePointCodec.ref(t.getCompound("From"));var to=LanePointCodec.ref(t.getCompound("To"));
    boolean sourceRamp=rampEnd(all,from),targetRamp=rampEnd(all,to);
    boolean source=!sourceRamp&&tunnelEnd(all,from),target=!targetRamp&&tunnelEnd(all,to);
    t.putBoolean("SourceRamp",sourceRamp);t.putBoolean("TargetRamp",targetRamp);
    t.putBoolean("SourceTunnel",source);t.putBoolean("TargetTunnel",target);
    boolean sourceHighway=highwayEnd(all,from),targetHighway=highwayEnd(all,to);
    t.putBoolean("SourceHighway",sourceHighway);t.putBoolean("TargetHighway",targetHighway);
    var context=new ListTag();var ids=new LinkedHashSet<UUID>();
    if(from.road()!=null)ids.add(from.road());if(to.road()!=null)ids.add(to.road());
    for(var id:ids){var road=all.get(id);if(road!=null)context.add(road.header());}
    t.put("ContextRoads",context);
    var o=LanePointCodec.options(t.getCompound("Options"));
    t.put("Options",LanePointCodec.options(new LanePoints.Options(o.path(),sourceRamp?LanePoints.Departure.BRANCH:(source||sourceHighway)?LanePoints.Departure.EXTRA:o.departure(),targetRamp?LanePoints.Arrival.FLOW:(target||targetHighway)?LanePoints.Arrival.EXTRA:o.arrival(),o.radius(),o.transition(),o.elevation(),o.landing(),o.gradeOverride(),o.allowTunnel(),o.lanes())));
    return t;
  }
  public static CompoundTag payload(RoadRecord road,boolean roadEditor){
    var link=LaneTopology.metadata(road).link();if(link==null)throw new IllegalArgumentException("请选择连接器生成的独立匝道");
    var t=new CompoundTag();t.putString("Kind",roadEditor?"laneRampRoad":"laneRamp");t.putUUID("Id",road.id());t.putInt("Signature",road.header().hashCode());
    t.put("From",LanePointCodec.ref(link.from()));t.put("To",LanePointCodec.ref(link.to()));t.put("Options",LanePointCodec.options(link.options()));t.put("Road",road.header());
    t.put("Settings",RoadRecord.writeSettings(road.settings()));t.putLong("A",road.a().asLong());t.putLong("B",road.b().asLong());t.put("StartNode",RoadRecord.writeNode(road.start()));t.put("EndNode",RoadRecord.writeNode(road.end()));
    return t;
  }
  /** Revision includes metadata edits; values never retain their weak index key. */
  private record PreviewSnapshot(long revision,RoadPlanningSnapshot state) {
    Map<UUID,RoadRecord> records(){return state.records();}
    Map<RoadRecord,Mesh> seeds(){return state.seeds();}
  }
  private static final Map<RoadIndex,PreviewSnapshot> PREVIEW_SNAPSHOTS=new WeakHashMap<>();
  private static long snapshotBuilds,snapshotHits,snapshotNanos,terrainFallbackNanos,commitNanos;
  private static synchronized PreviewSnapshot previewSnapshot(RoadData data){
    var found=PREVIEW_SNAPSHOTS.get(data.index);long revision=data.index.contentRevision();
    if(found!=null&&found.revision()==revision){snapshotHits++;return found;}
    long started=System.nanoTime();
    var snapshot=new PreviewSnapshot(revision,data.index.planningSnapshot());
    if(PREVIEW_SNAPSHOTS.size()>=4)PREVIEW_SNAPSHOTS.clear();PREVIEW_SNAPSHOTS.put(data.index,snapshot);snapshotBuilds++;snapshotNanos+=System.nanoTime()-started;return snapshot;
  }
  public static synchronized void clearPreviewSnapshots(){PREVIEW_SNAPSHOTS.clear();}
  public static String previewMetrics(){return "snapshotBuilds="+snapshotBuilds+" snapshotHits="+snapshotHits+" snapshotMs="+(snapshotNanos/1000000)+" terrainFallbackMs="+(terrainFallbackNanos/1000000)+" commitMs="+(commitNanos/1000000);}
  /** Immutable route snapshot. The worker never touches a ServerLevel, RoadData or ItemStack. */
  public static final class PreviewWork {
    private final RoadPlanningSnapshot snapshot;
    private final Map<UUID,RoadRecord> all;
    private final Map<RoadRecord,Mesh> seeds;
    private final UUID id,owner;private final LanePoints.Link link;private final V junctionDirection;
    public final long revision;private final double minHeight,maxHeight;
    private PreviewWork(RoadPlanningSnapshot snapshot,UUID id,UUID owner,LanePoints.Link link,V direction,long revision,double minHeight,double maxHeight){
      this.snapshot=snapshot;this.all=snapshot.records();this.seeds=snapshot.seeds();
      this.id=id;this.owner=owner;this.link=link;this.junctionDirection=direction;this.revision=revision;this.minHeight=minHeight;this.maxHeight=maxHeight;
    }
    public boolean alternative(int attempt){
      int count=switch(link.options().elevation()){case AUTO->3;case KEEP->1;case OVER,UNDER->2;};
      return attempt>=0&&attempt<count;
    }
    public PreviewRoute compute(){return compute(0);}
    public PreviewRoute compute(int attempt){try(var scope=new Planning(seeds,junctionDirection,minHeight,maxHeight)){
      if(!alternative(attempt))throw new IllegalArgumentException("没有更多高程候选");
      var trial=link;
      if(attempt>0){var o=link.options();
        // OVER/UNDER are preferences, including when the first route only fails
        // after terrain-dependent lining is added. KEEP never reaches this path.
        var elevation=o.elevation()==LanePoints.Elevation.OVER?LanePoints.Elevation.UNDER:
            o.elevation()==LanePoints.Elevation.UNDER?LanePoints.Elevation.OVER:
            attempt==1?LanePoints.Elevation.OVER:LanePoints.Elevation.UNDER;
        var options=new LanePoints.Options(o.path(),o.departure(),o.arrival(),o.radius(),o.transition(),elevation,o.landing(),o.gradeOverride(),o.allowTunnel(),o.lanes());
        trial=new LanePoints.Link(link.from(),link.to(),options,link.junctionMouth(),link.targetOffset(),link.protectedMerge(),link.rectangularClosure());}
      var generated=generateChoice(null,all,id,owner,trial,null);var road=generated.road();
      if(attempt>0){var data=LaneTopology.metadata(road);var selected=data.link();
        road=road.withLanePoints(data.link(new LanePoints.Link(selected.from(),selected.to(),link.options(),selected.junctionMouth(),selected.targetOffset(),selected.protectedMerge(),selected.rectangularClosure())));}
      return new PreviewRoute(road,generated.path(),trial.options().elevation());
    }}
    public <T> T resolve(java.util.function.Function<PreviewRoute,T> validate){
      CandidateRejected rejection=null;
      for(int attempt=0;alternative(attempt);attempt++){
        PreviewRoute route;
        try{route=compute(attempt);}catch(IllegalArgumentException e){if(rejection==null)throw e;continue;}
        try{return validate.apply(route);}catch(IllegalArgumentException e){rejection=candidateRejection(e);}
      }
      throw rejection;
    }
  }
  /** A geometrically valid candidate can still conflict after terrain-dependent lining is added. */
  public static final class CandidateRejected extends IllegalArgumentException {
    public CandidateRejected(String message){super(message);}
    public CandidateRejected(String message,Throwable cause){super(message,cause);}
  }
  /** Shared by synchronous and queued previews; only explicit geometric failures
   * may advance the bounded candidate sequence. Keep the original diagnostic. */
  public static CandidateRejected candidateRejection(IllegalArgumentException failure){
    if(failure instanceof CandidateRejected rejected)return rejected;
    if(failure instanceof RoadCrossingFailure crossing&&crossing.candidateDependent())
      return new CandidateRejected(failure.getMessage(),failure);
    throw failure;
  }
  /** Keep the trial direction separate from the user's persisted preference.
   * A later terrain-only replan must not silently repeat the rejected direction. */
  public record PreviewRoute(RoadRecord road,LanePoints.Path path,LanePoints.Elevation trialElevation){
    public PreviewRoute(RoadRecord road,LanePoints.Path path){this(road,path,LaneTopology.metadata(road).link().options().elevation());}
  }
  /** Include actual roads even when outside the client's normal subscription radius. */
  public static void conflictRoads(CompoundTag reply,ServerPlayer player,String message){
    var records=new ListTag();var data=RoadData.get(player.serverLevel());
    for(UUID id:RoadConflictIds.read(message)){var road=data.index.roads.get(id);if(road!=null)records.add(road.record.header());}
    reply.put("ConflictRoads",records);
  }
  public static PreviewWork preparePreview(ServerPlayer player,ItemStack tool,CompoundTag t){
    selection(player,tool,t);var data=RoadData.get(player.serverLevel());var from=LanePointCodec.ref(t.getCompound("From"));var to=LanePointCodec.ref(t.getCompound("To"));var options=LanePointCodec.options(t.getCompound("Options"));var snapshot=previewSnapshot(data);var all=snapshot.records();var source=host(all,from);RoadData.requireOwner(player,source.owner());
    if(to.road()!=null)RoadData.requireOwner(player,host(all,to).owner());else RoadData.requireOwner(player,data.junctions.getOrDefault(to.junction(),new CompoundTag()).getUUID("Owner"));
    validateTunnelEnds(all,from,to,options);
    V mouth=to.junction()==null?null:RampJunctions.mouth(data,to.junction(),LaneTopology.point(source,from.point()).position(),new Settings(Mode.STRAIGHT,Style.O1_ONE,Math.max(4,LanePoints.lane(mesh(source),LaneTopology.point(source,from.point())).width()+1),1,.35,90));
    V direction=to.junction()==null?null:RampJunctions.spec(data,to.junction()).center().sub(mouth).horizontalUnit();
    UUID id=t.hasUUID("Id")?t.getUUID("Id"):UUID.randomUUID();double previousOffset=t.hasUUID("Id")?LaneTopology.metadata(all.get(id)).link().targetOffset():0;
    return new PreviewWork(snapshot.state(),id,t.hasUUID("Id")?all.get(id).owner():player.getUUID(),new LanePoints.Link(from,to,options,mouth,previousOffset),direction,snapshot.revision(),player.serverLevel().getMinBuildHeight(),player.serverLevel().getMaxBuildHeight());
  }
  public static CompoundTag preview(ServerPlayer player,ItemStack tool,CompoundTag t){
    var work=preparePreview(player,tool,t);return work.resolve(route->finishPreview(player,tool,t,work,route));
  }
  /** Main-thread validation/publish. Reject stale work before touching any live state. */
  public static CompoundTag finishPreview(ServerPlayer player,ItemStack tool,CompoundTag t,PreviewWork work,PreviewRoute generated){
    try(var chunks=RoadWorkChunks.open(player.serverLevel())){return finishPreviewLoaded(player,tool,t,work,generated);}
  }
  private static CompoundTag finishPreviewLoaded(ServerPlayer player,ItemStack tool,CompoundTag t,PreviewWork work,PreviewRoute generated){
    selection(player,tool,t);var data=RoadData.get(player.serverLevel());
    if(data.index.contentRevision()!=work.revision)throw new IllegalArgumentException("计算期间道路已改变，旧预览已丢弃，请重新预览");
    var all=work.all;var id=work.id;var from=work.link.from();var to=work.link.to();var options=work.link.options();
    // Publish the actual normalized link selected by the worker, including the
    // rectangular closure and flexible B offset. The raw request has neither.
    RoadRecord r=generated.road();
    // The asynchronous geometric draft contains no live-world reads. Only if it
    // violates the earth policy, rerun its constrained search with original terrain
    // on this main-thread validation stage, before publishing or changing any block.
    var terrain=data.terrainGround();
    if(!LaneRampTerrain.clear(r.mesh(),LaneRampTerrain.floors(r.mesh(),LaneTopology.metadata(r).link().options(),terrain))){
      long started=System.nanoTime();
      try{
        var selected=LaneTopology.metadata(r).link();var o=selected.options();
        var trialOptions=new LanePoints.Options(o.path(),o.departure(),o.arrival(),o.radius(),o.transition(),generated.trialElevation(),o.landing(),o.gradeOverride(),o.allowTunnel(),o.lanes());
        var trial=new LanePoints.Link(selected.from(),selected.to(),trialOptions,selected.junctionMouth(),selected.targetOffset(),selected.protectedMerge(),selected.rectangularClosure());
        r=generateChoice(data,all,r.id(),r.owner(),trial,r.settings()).road();
        var fitted=LaneTopology.metadata(r);var finalLink=fitted.link();
        r=r.withLanePoints(fitted.link(new LanePoints.Link(finalLink.from(),finalLink.to(),o,finalLink.junctionMouth(),finalLink.targetOffset(),finalLink.protectedMerge(),finalLink.rectangularClosure())));
      }
      catch(IllegalArgumentException e){throw new CandidateRejected(e.getMessage(),e);}
      finally{terrainFallbackNanos+=System.nanoTime()-started;}
    }
    if(!data.withinHeight(r.mesh()))throw new CandidateRejected("上跨／下穿超出世界高度范围");
    var planned=new ArrayList<RoadIndex.Built>();planned.add(new RoadIndex.Built(r));
    var removed=new HashSet<UUID>();if(all.containsKey(id))removed.add(id);
    var request=assemblyRequest(data,r,all);
    var token=UUID.randomUUID();
    planned=new ArrayList<>(data.prepareAssembly(player.serverLevel(),player,planned,removed,request.endpoints(),request.moves(),token));
    var staging=work.snapshot.changed(removed,planned).records();r=staging.get(id);
    var checked=new CompoundTag();checked.put("Road",r.header());checked.putLong("WorldRevision",data.index.revision());checked.putInt("FromSignature",signature(data,from));checked.putInt("ToSignature",signature(data,to));checked.putUUID("Token",token);checked.putLong("Request",t.getLong("Request"));
    if(t.hasUUID("Id")){checked.putUUID("Id",id);checked.putInt("Signature",t.getInt("Signature"));}
    RoadPlanningBudget.check();tool.getOrCreateTag().put("LanePreview",checked);
    var reply=new CompoundTag();reply.putString("Kind","laneRampCheck");reply.putString("ResolvedPath",generated.path().name());reply.putLong("Request",t.getLong("Request"));reply.put("Road",r.header());reply.putUUID("Token",checked.getUUID("Token"));reply.putDouble("TargetOffset",LaneTopology.metadata(r).link().targetOffset());reply.putDouble("GradeLimit",gradeLimit(staging,LaneTopology.metadata(r).link()));
    var finalLink=LaneTopology.metadata(r).link();var finalMesh=r.mesh();
    var metrics=LaneRampGrade.report(finalMesh,fixedApproach(finalMesh,staging,finalLink,true),
        finalMesh.length()-fixedApproach(finalMesh,staging,finalLink,false),gradeLimit(staging,finalLink));
    reply.putDouble("GradeHorizontal",metrics.horizontal());reply.putDouble("GradeAvailable",metrics.available());
    reply.putDouble("GradeMinimum",metrics.minimum());reply.putDouble("ActualGrade",metrics.maximum());
    var changed=new ListTag();for(var entry:planned){var next=entry.record;if(next.id().equals(id)||all.containsKey(next.id())&&!next.equals(all.get(next.id())))changed.add(next.header());}reply.put("ChangedRoads",changed);
    if(options.departure()==LanePoints.Departure.TEMPORARY)for(var cut:LaneTopology.metadata(staging.get(from.road())).cuts())if(cut.connection().equals(id)){
      double length=cut.sign()*(cut.end()-cut.begin());
      reply.putBoolean("TemporaryClosure",true);reply.putBoolean("RectangularClosure",cut.rectangular());
      reply.putDouble("ReopenAfter",cut.rectangular()?length:length-cut.transition());reply.putDouble("RestoredAfter",length);break;
    }
    if(LaneTopology.metadata(r).link().closesTarget())for(var cut:LaneTopology.metadata(staging.get(to.road())).cuts())if(cut.connection().equals(id)&&cut.arrival()){
      reply.putBoolean("TargetClosure",true);reply.putDouble("TargetClosedBefore",cut.sign()*(cut.end()-Math.max(0,Math.min(staging.get(to.road()).rawMesh().length(),cut.begin()))));break;
    }
    return reply;
  }
  public static String build(ServerPlayer player,ItemStack tool,CompoundTag t){
    selection(player,tool,t);var checked=tool.getOrCreateTag().getCompound("LanePreview");
    if(!checked.hasUUID("Token")||!t.hasUUID("Token")||!checked.getUUID("Token").equals(t.getUUID("Token"))||checked.hasUUID("Id")!=t.hasUUID("Id")||t.hasUUID("Id")&&!checked.getUUID("Id").equals(t.getUUID("Id")))throw new IllegalArgumentException("请先检查当前设置的预览");
    var r=RoadRecord.load(checked.getCompound("Road"));var link=LaneTopology.metadata(r).link();var data=RoadData.get(player.serverLevel());
    if(checked.getLong("WorldRevision")!=data.index.revision())throw new IllegalArgumentException("道路几何已在预览后改变，请重新预览完整接头");
    if(t.hasUUID("Id")&&checked.getInt("Signature")!=t.getInt("Signature")||!LanePointCodec.options(link.options()).equals(t.getCompound("Options"))||checked.getInt("FromSignature")!=signature(data,link.from())||checked.getInt("ToSignature")!=signature(data,link.to()))throw new IllegalArgumentException("道路或选项已变化，请重新预览");
    long started=System.nanoTime();try{data.buildPrepared(player.serverLevel(),player,checked.getUUID("Token"));}finally{commitNanos+=System.nanoTime()-started;}LaneRampTool.clearSelection(tool);return t.hasUUID("Id")?"匝道已更新，身份与依赖引用保留":"匝道已建成，车道点引用生效";
  }
  public static String editRoad(ServerLevel level,ServerPlayer player,CompoundTag t){
    var data=RoadData.get(level);var b=data.index.roads.get(t.getUUID("Id"));
    if(b==null||LaneTopology.metadata(b.record).link()==null)throw new IllegalArgumentException("所选匝道已不存在");
    if(player!=null)RoadData.requireOwner(player,b.record.owner());
    if(t.getInt("Signature")!=b.record.header().hashCode())throw new IllegalArgumentException("道路已改变，请重新打开编辑器");
    var next=reconfigure(data,b.record,RoadRecord.readSettings(t.getCompound("Settings")),LaneTopology.records(data));
    build(data,level,player,next);return "匝道路面与附属设置已保存，车道点引用保留";
  }
  private record AssemblyRequest(Set<BlockPos> endpoints,List<RoadData.NodeMove> moves){}
  private static AssemblyRequest assemblyRequest(RoadData data,RoadRecord r){
    return assemblyRequest(data,r,data.index.planningSnapshot().records());
  }
  private static AssemblyRequest assemblyRequest(RoadData data,RoadRecord r,Map<UUID,RoadRecord> all){
    var link=LaneTopology.metadata(r).link();if(link==null)throw new IllegalArgumentException("缺少真实车道点引用");
    var moves=new ArrayList<RoadData.NodeMove>();if(link.to().junction()!=null){var n=new Node(link.junctionMouth(),RoadPlanner.yaw(RampJunctions.spec(data,link.to().junction()).center().sub(link.junctionMouth()).horizontalUnit()),0);var snapshot=new CompoundTag();snapshot.putUUID("Owner",r.owner());snapshot.put("Node",RoadRecord.writeNode(n));snapshot.putBoolean("HeightExplicit",true);moves.add(new RoadData.NodeMove(null,RampJunctions.at(n.position()),n,snapshot));}
    // Contact may contain an existing host marker. On first construction that host is
    // rebuilt for its new opening; subsequent edits may leave it unchanged. Its own
    // markers are still legitimate contacts, never unrelated obstacles or deletion targets.
    return new AssemblyRequest(contactEndpoints(all,r),List.copyOf(moves));
  }
  static Set<BlockPos> contactEndpoints(Map<UUID,RoadRecord> all,RoadRecord r){
    var endpoints=new HashSet<BlockPos>();endpoints.add(r.a());endpoints.add(r.b());
    var pending=new ArrayDeque<LanePoints.Ref>();var seen=new HashSet<UUID>();seen.add(r.id());
    var link=LaneTopology.metadata(r).link();if(link!=null){pending.add(link.from());pending.add(link.to());}
    while(!pending.isEmpty())for(UUID id:contactRoads(all,pending.removeFirst()))if(seen.add(id)){
      var host=all.get(id);endpoints.add(host.a());endpoints.add(host.b());
      // Opening a child branch also replans its saved parent's furniture.
      // That parent's original host markers remain legitimate contacts.
      var parent=LaneTopology.metadata(host).link();if(parent!=null){pending.add(parent.from());pending.add(parent.to());}
    }
    return Set.copyOf(endpoints);
  }
  public static void build(RoadData data,ServerLevel level,ServerPlayer player,RoadRecord r){
    var request=assemblyRequest(data,r);
    data.replaceAssembly(level,player,List.of(new RoadIndex.Built(r)),data.index.roads.containsKey(r.id())?Set.of(r.id()):Set.of(),request.endpoints(),request.moves());RampJunctions.sync(data);data.setDirty();
  }
  private LaneRamps(){}
}
