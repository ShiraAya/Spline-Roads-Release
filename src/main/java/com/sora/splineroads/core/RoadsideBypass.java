package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;
/** Persisted vertical bicycle crossings. Sidewalks and motor lanes retain their alignment. */
public final class RoadsideBypass {
  public static final double HEADROOM=4.4,SLAB=.25,ROOF=.25,WALL=.22,MAX_GRADE=.16;
  /** Location of a rejected bicycle approach, before a world transaction adds
   * its road identity. A non-grade failure has no measured slope (NaN). */
  public static final class ValidationException extends IllegalArgumentException {
    public enum Reason { APPROACH_SPACE, ENDPOINT_HEIGHT, GRADE, CLEARANCE }
    private final Reason reason;
    private final int side;
    private final double stationFrom,stationTo,slope;
    private final V worldFrom,worldTo;
    private ValidationException(String message,Reason reason,int side,double from,double to,double slope){
      this(message,reason,side,from,to,slope,null,null);
    }
    private ValidationException(String message,Reason reason,int side,double from,double to,double slope,V worldFrom,V worldTo){
      super(message);this.reason=reason;this.side=side;this.stationFrom=from;this.stationTo=to;this.slope=slope;
      this.worldFrom=worldFrom;this.worldTo=worldTo;
    }
    public Reason reason(){return reason;}
    public int side(){return side;}
    public double stationFrom(){return stationFrom;}
    public double stationTo(){return stationTo;}
    public double slope(){return slope;}
    public double limit(){return MAX_GRADE;}
    public V worldFrom(){return worldFrom;}
    public V worldTo(){return worldTo;}
  }
  public record Span(int side,double start,double from,double to,double end,double delta){
    public Span {
      // A continuation keeps its full polynomial in local coordinates. Its
      // approach may start before this segment or finish on the next segment.
      if(Math.abs(side)!=1||!RoadGeometry.finite(start,from,to,end,delta)||start< -RoadLimits.MAX_PATH_LENGTH||end>2*RoadLimits.MAX_PATH_LENGTH||from<start||to<from||end<to||Math.abs(delta)<1e-7)throw new IllegalArgumentException("无效的非机动车道立体通行范围");
    }
    public double height(double d){
      if(d<start||d>end)return 0;
      double t=d<from?(d-start)/Math.max(1e-8,from-start):d>to?(end-d)/Math.max(1e-8,end-to):1;
      return delta*t*t*t*(10+t*(-15+6*t));
    }
  }
  public record Neighbor(Mesh mesh,List<RoadAutoTunnels.RoofRegion> roofs){}
  private record Obstacle(List<V> polygon,double low,double high,boolean tunnel,
      double depth,double aboveHeight,double legacyLow,int side){
    Obstacle forSide(int value){return new Obstacle(polygon,low,high,tunnel,depth,aboveHeight,legacyLow,value);}
  }
  private final Mesh road;
  private final List<Mesh> neighbors;
  public RoadsideBypass(Mesh road,List<Mesh> neighbors){this.road=road;this.neighbors=neighbors;}
  /** A stored crossing is derived geometry, not permission to create a bicycle
   * lane after its road/profile option was disabled. Parking and tunnels have
   * their own surface policy and never use this open-road bypass. */
  public static boolean eligible(Mesh road){
    var options=road.settings().options();
    return options.cycle()&&!options.streetscape().parking()
        &&road.settings().structure()!=Structure.TUNNEL
        &&RoadProfile.catalog(road.settings().style()).type()==RoadProfile.Type.ORDINARY
        &&options.lanePoints().link()==null;
  }
  public boolean active(){return eligible(road)&&!road.settings().options().streetscape().crossings().isEmpty();}
  /** Legacy lateral shift is deliberately gone; no sidewalk is moved sideways. */
  public double offset(double station,int side){return 0;}
  public double height(double station,int side){return height(road,station,side);}
  public static double height(Mesh road,double station,int side){
    if(!eligible(road))return 0;
    for(var span:road.settings().options().streetscape().crossings())if(span.side()==side&&station>=span.start()&&station<=span.end())return span.height(station);
    return 0;
  }
  public static boolean crossing(Mesh road,double station,int side){return Math.abs(height(road,station,side))>1e-9;}
  public static double inner(Mesh road,Sample s,int side){return side*RoadProfile.layout(road,s).outer(side);}
  public static double outer(Mesh road,Sample s,int side){return s.halfWidth()-RoadProfile.curbExtent(RoadProfile.layout(road,s),s,side);}
  public static Mesh with(Mesh mesh,List<Span> spans){
    var s=mesh.settings().options(mesh.settings().options().streetscape(mesh.settings().options().streetscape().crossings(spans)));
    return sampled(new Mesh(mesh.samples(),s,mesh.min(),mesh.max(),mesh.length(),mesh.closed(),mesh.controlPoint(),mesh.controls(),mesh.reference()));
  }
  public static Mesh sampled(Mesh mesh){
    if(!eligible(mesh)||mesh.settings().options().streetscape().crossings().isEmpty())return mesh;
    var stations=new TreeSet<Double>();var original=new HashMap<Double,Sample>();
    for(var at:mesh.samples()){stations.add(at.distance());original.put(at.distance(),at);}
    for(var span:mesh.settings().options().streetscape().crossings()){
      for(double d=Math.max(0,span.start());d<Math.min(mesh.length(),span.end());d+=.5)addStation(stations,d);
      addStation(stations,span.from());addStation(stations,span.to());addStation(stations,span.end());
    }
    // Re-sampling an existing curve station normalizes its left vector again;
    // even a one-ulp change drifts the persisted floor on repeated rail saves.
    // Interpolate only genuinely new stations and retain the authored samples.
    var samples=stations.stream().filter(d->d>=mesh.first().distance()&&d<=mesh.last().distance())
        .map(d->original.containsKey(d)?original.get(d):RoadStructures.sample(mesh,d)).toList();
    return new Mesh(samples,mesh.settings(),mesh.min(),mesh.max(),mesh.length(),mesh.closed(),mesh.controlPoint(),mesh.controls(),mesh.reference());
  }
  private static void addStation(NavigableSet<Double> stations,double distance){
    // A ribbon's accumulated distances can differ from the half-metre grid by
    // a few ulps. Preserve its authored sample instead of creating a zero-run
    // segment that later travel/clearance meshes reject as an impossible grade.
    var before=stations.floor(distance);if(before!=null&&distance-before<=1e-7)return;
    var after=stations.ceiling(distance);if(after!=null&&after-distance<=1e-7)return;
    stations.add(distance);
  }
  public List<RoadSurface.Face> markings(){
    var out=new ArrayList<RoadSurface.Face>();if(!active())return out;
    for(var span:road.settings().options().streetscape().crossings())for(double d=Math.max(0,span.start());d<Math.min(road.length(),span.end())-1e-8;d+=.5){
      double end=Math.min(road.length(),Math.min(span.end(),d+.5));var a=RoadStructures.sample(road,d);var b=RoadStructures.sample(road,end);int side=span.side();
      double ha=span.height(d),hb=span.height(end);
      for(int edge:new int[]{0,1}){
        double ia=edge==0?inner(road,a,side)+WALL+.14:outer(road,a,side)-WALL-.14;
        double ib=edge==0?inner(road,b,side)+WALL+.14:outer(road,b,side)-WALL-.14;
        out.add(new RoadSurface.Face(List.of(a.at(side*(ia-.06),-ha),a.at(side*(ia+.06),-ha),b.at(side*(ib+.06),-hb),b.at(side*(ib-.06),-hb)),0xEEEEEE));
      }
    }return List.copyOf(out);
  }
  public static List<Span> plan(Mesh road,List<Neighbor> neighbors){
    return plan(road,neighbors,null,null);
  }
  /** A saved crossing can survive an incidental endpoint-section metadata
   * update. Compare its actual sampled travel surface, including strip width
   * and the neighbouring samples used by the slope check, on both sides of
   * every saved span. Geometry outside those spans grants no exemption to new
   * spans or changed obstacles; plan() independently requires both to match. */
  public static boolean sameSavedCrossingSurface(Mesh previous,Mesh candidate,List<Span> spans){
    if(spans==null||spans.isEmpty()||!eligible(previous)||!eligible(candidate)
        ||previous.closed()!=candidate.closed()
        ||!previous.settings().options().streetscape().crossings().equals(spans)
        ||!candidate.settings().options().streetscape().crossings().equals(spans))return false;
    for(int side:new int[]{-1,1}){
      var before=cycleSamples(previous,side);var after=cycleSamples(candidate,side);
      for(var span:spans)if(span.side()==side){
        RoadPlanningBudget.check();
        if(span.end()>previous.length()||span.end()>candidate.length())return false;
        var oldWindow=crossingWindow(before,span);var newWindow=crossingWindow(after,span);
        if(oldWindow.size()<2||!oldWindow.equals(newWindow))return false;
      }
    }
    return true;
  }
  private static List<Sample> crossingWindow(List<Sample> samples,Span span){
    int lo=0,hi=samples.size();
    while(lo<hi){int mid=(lo+hi)>>>1;if(samples.get(mid).distance()<span.start())lo=mid+1;else hi=mid;}
    int first=Math.max(0,lo-1);lo=0;hi=samples.size();
    while(lo<hi){int mid=(lo+hi)>>>1;if(samples.get(mid).distance()<=span.end())lo=mid+1;else hi=mid;}
    return samples.subList(first,Math.min(samples.size(),lo+1));
  }
  /** The transaction may retain an indirectly rebuilt road's previous crossings
   * only after proving its saved crossing surface and ramp neighbourhood
   * unchanged. A lower ramp can miss the original deck-height span test yet cut
   * the lowered bicycle floor during apply(), so equal spans alone are not a
   * sufficient history receipt. Direct user edits pass null histories. */
  public static List<Span> plan(Mesh road,List<Neighbor> neighbors,List<Span> unchangedCrossings,
      List<Neighbor> unchangedNeighbors){
    if(!eligible(road))return List.of();
    var grid=new HashMap<Long,List<Obstacle>>();var rails=new HashMap<Long,List<Obstacle>>();
    for(var n:neighbors){var ramp=n.mesh();boolean railway=ramp.settings().style()==Style.RAILBED;if(!railway&&!ramp.settings().style().ramp()&&ramp.settings().options().lanePoints().link()==null)continue;
      for(int i=1;i<ramp.samples().size();i++){
        if((i&63)==0)RoadPlanningBudget.check();
        var a=ramp.samples().get(i-1);var b=ramp.samples().get(i);double station=(a.distance()+b.distance())/2;
        var region=n.roofs().stream().filter(r->station>=r.from()&&station<=r.to()).findFirst().orElse(null);
        boolean tunnel=ramp.settings().structure()==Structure.TUNNEL||region!=null;
        double clearance=region==null?RoadInfrastructure.clearance(ramp.settings()):Math.max(region.first(),region.last());
        double margin=tunnel?RoadAutoTunnels.LINING:.15;
        var polygon=List.of(a.at(-a.halfWidth()-margin,0),a.at(a.halfWidth()+margin,0),b.at(b.halfWidth()+margin,0),b.at(-b.halfWidth()-margin,0));
        double depth=ramp.settings().thickness()+RoadInfrastructure.girderDepth(ramp);
        double low=Math.min(a.center().y(),b.center().y())-depth,high=Math.max(a.center().y(),b.center().y())+(tunnel?clearance+RoadAutoTunnels.ROOF_THICKNESS:1.35);
        double aboveHeight=tunnel?clearance+RoadAutoTunnels.ROOF_THICKNESS:
            Math.max(4.25,RoadInfrastructure.clearance(ramp.settings()));
        var obstacle=new Obstacle(polygon,low,high,tunnel,depth,aboveHeight,
            Math.min(a.center().y(),b.center().y())-ramp.settings().thickness(),0);
        int x0=tile(polygon.stream().mapToDouble(V::x).min().orElseThrow()),x1=tile(polygon.stream().mapToDouble(V::x).max().orElseThrow()),z0=tile(polygon.stream().mapToDouble(V::z).min().orElseThrow()),z1=tile(polygon.stream().mapToDouble(V::z).max().orElseThrow());
        for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)(railway?rails:grid).computeIfAbsent(key(x,z),k->new ArrayList<>()).add(obstacle);
      }
    }
    // Fitted approaches may be longer than the flat-centerline estimate above.
    // Retain their exact saved shape only with the transaction's unchanged
    // surface receipt, identical obstacle solids and identical crossing cores.
    boolean unchanged=rails.isEmpty()&&unchangedCrossings!=null&&sameObstacles(neighbors,unchangedNeighbors);
    // Releases through 1.0.19 generated bridge crossings from the slab bottom.
    // Recognize that exact old core only for a complete unchanged receipt;
    // a direct edit, changed girder or newly encountered crossing stays strict.
    if(unchanged&&!unchangedCrossings.isEmpty()
        &&grid.values().stream().flatMap(Collection::stream).anyMatch(o->o.low()!=o.legacyLow())
        &&sameCrossingCores(road,crossingSpans(road,grid,true),unchangedCrossings))
      return List.copyOf(unchangedCrossings);
    var result=crossingSpans(road,grid,false);
    if(unchanged&&sameCrossingCores(road,result,unchangedCrossings))return List.copyOf(unchangedCrossings);
    // Direct edits still undergo full validation. A previously fitted envelope
    // with the same newly computed core is a stable starting point, preventing
    // rail-only saves from repeatedly shortening and refitting a legal floor.
    var saved=road.settings().options().streetscape().crossings();
    var fitted=fitApproaches(road,sameFittedCores(road,result,saved)?saved:result);
    // A level railway remains a normal crossing until a new bicycle descent
    // reaches it. Then keep the complete descent below that railway as well.
    var included=new HashSet<Obstacle>();
    while(!rails.isEmpty()){
      RoadPlanningBudget.check();
      var added=new HashSet<Obstacle>();
      for(var span:fitted)for(double d=Math.max(0,span.start());d<Math.min(road.length(),span.end());d+=1){
        double end=Math.min(d+1,Math.min(road.length(),span.end()));if(Math.max(Math.abs(span.height(d)),Math.abs(span.height(end)))<1e-6)continue;
        var a=RoadStructures.sample(road,d);var b=RoadStructures.sample(road,end);int side=span.side();
        var strip=List.of(a.at(side*inner(road,a,side),0),a.at(side*outer(road,a,side),0),b.at(side*outer(road,b,side),0),b.at(side*inner(road,b,side),0));
        var near=new HashSet<Obstacle>();for(var p:strip)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)near.addAll(rails.getOrDefault(key(tile(p.x())+dx,tile(p.z())+dz),List.of()));
        for(var obstacle:near)if(RoadSurface.area(RoadSurface.intersect(obstacle.polygon(),strip))>1e-7){var selected=obstacle.forSide(side);if(!included.contains(selected))added.add(selected);}
      }
      if(added.isEmpty())break;
      for(var obstacle:added){included.add(obstacle);var polygon=obstacle.polygon();
        int x0=tile(polygon.stream().mapToDouble(V::x).min().orElseThrow()),x1=tile(polygon.stream().mapToDouble(V::x).max().orElseThrow()),z0=tile(polygon.stream().mapToDouble(V::z).min().orElseThrow()),z1=tile(polygon.stream().mapToDouble(V::z).max().orElseThrow());
        for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)grid.computeIfAbsent(key(x,z),k->new ArrayList<>()).add(obstacle);
      }
      fitted=fitApproaches(road,crossingSpans(road,grid,false));
    }
    validateClearance(road,fitted,grid);
    return fitted;
  }
  private static List<Span> crossingSpans(Mesh road,Map<Long,List<Obstacle>> grid,boolean legacy){
    var result=new ArrayList<Span>();
    for(int side:new int[]{-1,1}){
      var spans=new ArrayList<Span>();
      for(double d=0;d<road.length()-1e-7;d+=1){
        RoadPlanningBudget.check();
        var a=RoadStructures.sample(road,d);var b=RoadStructures.sample(road,Math.min(road.length(),d+1));var layout=RoadProfile.layout(road,a);
        if(layout.cycleWidth()<.01||!layout.catalog().twoWay()&&layout.outside()!=side)continue;
        var strip=List.of(a.at(side*inner(road,a,side),0),a.at(side*outer(road,a,side),0),b.at(side*outer(road,b,side),0),b.at(side*inner(road,b,side),0));
        var near=new HashSet<Obstacle>();
        int x0=tile(strip.stream().mapToDouble(V::x).min().orElseThrow()),x1=tile(strip.stream().mapToDouble(V::x).max().orElseThrow()),z0=tile(strip.stream().mapToDouble(V::z).min().orElseThrow()),z1=tile(strip.stream().mapToDouble(V::z).max().orElseThrow());
        for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)near.addAll(grid.getOrDefault(key(x,z),List.of()));
        double down=0,up=0;
        for(var obstacle:near){
          if(obstacle.side()!=0&&obstacle.side()!=side)continue;
          double low=legacy?obstacle.legacyLow():obstacle.low();
          if(low>=Math.max(a.center().y(),b.center().y())+HEADROOM+.1||obstacle.high()+.1<=Math.min(a.center().y(),b.center().y())-SLAB)continue;
          var hit=RoadSurface.intersect(obstacle.polygon(),strip);if(Math.abs(JunctionPaint.area(hit))<1e-7)continue;
          if(obstacle.tunnel())up=Math.max(up,obstacle.high()+SLAB+.1-Math.min(a.center().y(),b.center().y()));
          else down=Math.min(down,low-HEADROOM-ROOF-.05-Math.max(a.center().y(),b.center().y()));
        }
        if(down<0&&up>0)throw new RoadCrossingFailure.GeometryRejection("非机动车道上下均被匝道占用，无法保持通行净空");
        double delta=down<0?down:up;if(Math.abs(delta)<1e-7)continue;
        double from=Math.max(0,d-1),to=Math.min(road.length(),d+2),ramp=Math.max(24,Math.abs(delta)*1.875/MAX_GRADE);
        var next=new Span(side,Math.max(0,from-ramp),from,to,Math.min(road.length(),to+ramp),delta);
        if(!spans.isEmpty()){
          var old=spans.get(spans.size()-1);
          if(old.end()>next.start()&&Math.signum(old.delta())==Math.signum(next.delta())){
            delta=delta<0?Math.min(old.delta(),delta):Math.max(old.delta(),delta);ramp=Math.max(24,Math.abs(delta)*1.875/MAX_GRADE);
            next=new Span(side,Math.max(0,old.from()-ramp),old.from(),to,Math.min(road.length(),to+ramp),delta);spans.remove(spans.size()-1);
          }else if(old.to()>=next.from())throw new RoadCrossingFailure.GeometryRejection("非机动车道上下穿区间冲突");
        }
        spans.add(next);
      }
      // Finish merging obstacle cores before dividing any shared approach.
      // Otherwise the next sample of the second core restores its nominal
      // start and silently overlaps the opposite envelope just clipped here.
      for(int i=1;i<spans.size();i++){
        var a=spans.get(i-1);var b=spans.get(i);
        if(a.end()<=b.start())continue;
        double fraction=Math.abs(a.delta())/(Math.abs(a.delta())+Math.abs(b.delta()));
        double join=a.to()+(b.from()-a.to())*fraction;
        spans.set(i-1,new Span(side,a.start(),a.from(),a.to(),join,a.delta()));
        spans.set(i,new Span(side,join,b.from(),b.to(),b.end(),b.delta()));
      }
      result.addAll(spans);
    }
    return List.copyOf(result);
  }
  private static boolean sameCrossingCores(Mesh road,List<Span> planned,List<Span> saved){
    if(saved==null||saved.size()!=planned.size())return false;
    for(int i=0;i<planned.size();i++){
      var a=planned.get(i);var b=saved.get(i);
      if(a.side()!=b.side()||a.from()!=b.from()||a.to()!=b.to()||a.delta()!=b.delta()
          ||b.end()>road.length())return false;
    }
    return true;
  }
  /** Added travel samples can change re-interpolation of the same graded road
   * by a few ulps. This only chooses the direct fit's starting shape: current
   * grade and obstacle clearance are still checked below. History exemptions
   * deliberately keep the exact sameCrossingCores comparison above. */
  private static boolean sameFittedCores(Mesh road,List<Span> planned,List<Span> saved){
    if(saved==null||saved.size()!=planned.size())return false;
    for(int i=0;i<planned.size();i++){
      var a=planned.get(i);var b=saved.get(i);
      double roundoff=32*Math.max(Math.ulp(a.delta()),Math.ulp(b.delta()));
      if(a.side()!=b.side()||a.from()!=b.from()||a.to()!=b.to()
          ||Math.abs(a.delta()-b.delta())>roundoff||b.end()>road.length())return false;
    }
    return true;
  }
  /** The nominal length assumes a flat straight road. An inside cycle lane has
   * less horizontal run, and the base road's grade also contributes to its
   * final slope. Grow only the failing approach against the real travel samples;
   * never reduce a crossing's clearance, move its core or relax validation. */
  private static List<Span> fitApproaches(Mesh road,List<Span> source){
    var spans=new ArrayList<>(source);
    for(int attempt=0;attempt<Math.max(1,spans.size()*64);attempt++){
      RoadPlanningBudget.check();
      try{validate(road,spans);return List.copyOf(spans);}
      catch(ValidationException failure){
        if(failure.reason()!=ValidationException.Reason.GRADE||!Double.isFinite(failure.slope()))throw failure;
        double station=(failure.stationFrom()+failure.stationTo())/2;
        int index=-1;
        for(int i=0;i<spans.size();i++){
          var span=spans.get(i);
          if(span.side()==failure.side()&&station>=span.start()&&station<=span.end()){index=i;break;}
        }
        if(index<0)throw failure;
        var old=spans.get(index);
        // A steep crossing core is base-road geometry; extending its approach
        // cannot repair that slope. Try free run first, then coordinate the
        // two approaches sharing the gap instead of treating their initial
        // division as an immovable obstacle.
        if(station>=old.from()&&station<=old.to())throw failure;
        double lower=0,upper=road.length();int before=-1,after=-1;
        for(int i=0;i<spans.size();i++)if(i!=index&&spans.get(i).side()==old.side()){
          var other=spans.get(i);
          if(other.to()<=old.from()&&other.end()>=lower){lower=other.end();before=i;}
          if(other.from()>=old.to()&&other.start()<=upper){upper=other.start();after=i;}
        }
        double factor=Math.max(1.01,failure.slope()/MAX_GRADE);
        double start=old.start(),end=old.end();
        if(station<old.from())start=Math.max(lower,old.from()-(old.from()-start)*factor);
        else end=Math.min(upper,old.to()+(end-old.to())*factor);
        if(start>=old.start()-1e-7&&end<=old.end()+1e-7){
          int left=station<old.from()?before:index,right=station<old.from()?index:after;
          if(left>=0&&right>=0&&fitSharedApproaches(road,spans,left,right))continue;
          throw failure;
        }
        spans.set(index,new Span(old.side(),start,old.from(),old.to(),end,old.delta()));
      }
    }
    // A bounded search may decline a difficult alignment, but can never publish
    // an unvalidated approach or silently use the centerline estimate instead.
    validate(road,spans);
    return List.copyOf(spans);
  }
  /** Balance the measured slopes of both sides of one gap. The nominal height
   * ratio is only a starting estimate: base grade, lateral curvature and the
   * actual floor sample phase determine whether a split is usable. A bounded
   * search can decline difficult geometry but cannot weaken final validation. */
  private static boolean fitSharedApproaches(Mesh road,List<Span> spans,int left,int right){
    var a=spans.get(left);var b=spans.get(right);
    if(a.side()!=b.side()||a.to()>=b.from()-2e-7)return false;
    double low=a.to()+1e-7,high=b.from()-1e-7;
    double join=Math.max(low,Math.min(high,(a.end()+b.start())/2));
    var trial=new ArrayList<>(spans);
    for(int attempt=0;attempt<48;attempt++){
      RoadPlanningBudget.check();
      var first=new Span(a.side(),a.start(),a.from(),a.to(),join,a.delta());
      var second=new Span(b.side(),join,b.from(),b.to(),b.end(),b.delta());
      trial.set(left,first);trial.set(right,second);
      double[] grades=sharedGrades(road,trial,first,second);
      if(grades[0]<=MAX_GRADE&&grades[1]<=MAX_GRADE){
        if(first.equals(a)&&second.equals(b))return false;
        spans.set(left,first);spans.set(right,second);return true;
      }
      if(grades[0]>grades[1])low=join;else high=join;
      double next=(low+high)/2;if(Math.abs(next-join)<1e-8)break;join=next;
    }
    return false;
  }
  private static double[] sharedGrades(Mesh road,List<Span> spans,Span a,Span b){
    var planned=with(road,spans);var samples=cycleSamples(planned,a.side());var grades=new double[2];
    for(int i=1;i<samples.size();i++){
      if((i&63)==0)RoadPlanningBudget.check();
      var first=samples.get(i-1);var last=samples.get(i);
      sharedGrade(grades,a,b,(first.distance()+last.distance())/2,first.center(),last.center());
    }
    // The physical slabs use the road-wide half-metre phase, whereas travel
    // samples also include each envelope's phase and authored curve samples.
    var stations=floorStations(planned);
    for(int i=1;i<stations.size();i++){
      if((i&63)==0)RoadPlanningBudget.check();
      double from=stations.get(i-1),to=stations.get(i),mid=(from+to)/2;
      if(mid<a.to()||mid>b.from())continue;
      var first=RoadStructures.sample(planned,from);var last=RoadStructures.sample(planned,to);
      sharedGrade(grades,a,b,mid,cyclePoint(planned,first,a.side()),cyclePoint(planned,last,a.side()));
    }
    return grades;
  }
  private static V cyclePoint(Mesh road,Sample at,int side){
    return at.at(side*(inner(road,at,side)+outer(road,at,side))/2,-height(road,at.distance(),side));
  }
  private static void sharedGrade(double[] grades,Span a,Span b,double station,V first,V last){
    if(station<a.to()||station>b.from())return;
    double run=last.sub(first).horizontalLength(),rise=Math.abs(last.y()-first.y());
    double slope=run<1e-9?(rise>1e-9?Double.POSITIVE_INFINITY:0):rise/run;
    int side=station<=a.end()?0:1;grades[side]=Math.max(grades[side],slope);
  }
  private record ClearanceFailure(int side,double from,double to,V point,double available,double required){}
  private record ClearanceStrip(int side,double from,double to,List<V> top){}
  /** An obstacle below the original deck may leave the crossing core unchanged
   * yet occupy a newly lowered approach. Check the final strip before apply()
   * subtracts ramp air from its solids; deleting a floor is never a valid route.
   * The same indexed polygons retain tunnel lining/roofs and bridge underside.
   * Triangles keep interpolated cycle heights exact across each clipped overlap. */
  private static void validateClearance(Mesh road,List<Span> spans,Map<Long,List<Obstacle>> grid){
    if(spans.isEmpty()||grid.isEmpty())return;
    var planned=with(road,spans);ClearanceFailure worst=null;
    var strips=new ArrayList<ClearanceStrip>();
    for(int side:new int[]{-1,1}){
      var samples=cycleSamples(planned,side);
      for(int i=1;i<samples.size();i++){
        RoadPlanningBudget.check();
        var a=samples.get(i-1);var b=samples.get(i);double middle=(a.distance()+b.distance())/2;
        if(spans.stream().noneMatch(s->s.side()==side&&middle>s.start()&&middle<s.end()))continue;
        V al=a.at(a.halfWidth(),0),ar=a.at(-a.halfWidth(),0),bl=b.at(b.halfWidth(),0),br=b.at(-b.halfWidth(),0);
        strips.add(new ClearanceStrip(side,a.distance(),b.distance(),List.of(al,ar,br,bl)));
      }
    }
    // apply() builds its slabs on the road-wide half-metre grid, which can be
    // out of phase with the travel samples. Check those exact Part top vertices
    // as well: even a tiny cut of the slab underside must not validate as clear.
    var ds=floorStations(planned);
    for(int side:new int[]{-1,1})for(int i=1;i<ds.size();i++){
      double da=ds.get(i-1),db=ds.get(i),mid=(da+db)/2;
      var span=spans.stream().filter(s->s.side()==side&&mid>s.start()&&mid<s.end()).findFirst().orElse(null);
      if(span==null)continue;
      var a=RoadStructures.sample(planned,da);var b=RoadStructures.sample(planned,db);var floor=new ArrayList<Part>(1);
      band(floor,a,b,side,inner(planned,a,side),outer(planned,a,side),inner(planned,b,side),outer(planned,b,side),
          span.height(da)-SLAB,span.height(db)-SLAB,SLAB,Material.CONCRETE,"sr:cycle_bypass");
      if(!floor.isEmpty())strips.add(new ClearanceStrip(side,da,db,floor.get(0).top()));
    }
    for(var strip:strips){
        RoadPlanningBudget.check();var quad=strip.top();var near=new HashSet<Obstacle>();
        int x0=tile(quad.stream().mapToDouble(V::x).min().orElseThrow()),x1=tile(quad.stream().mapToDouble(V::x).max().orElseThrow());
        int z0=tile(quad.stream().mapToDouble(V::z).min().orElseThrow()),z1=tile(quad.stream().mapToDouble(V::z).max().orElseThrow());
        for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)near.addAll(grid.getOrDefault(key(x,z),List.of()));
        for(var obstacle:near)if(obstacle.side()==0||obstacle.side()==strip.side())for(var triangle:triangles(quad))
            for(var other:triangles(obstacle.polygon())){
          var overlap=RoadSurface.intersect(triangle,other);
          if(Math.abs(JunctionPaint.area(overlap))<1e-7)continue;
          double below=Double.POSITIVE_INFINITY,above=Double.POSITIVE_INFINITY;V belowPoint=null,abovePoint=null;
          for(var point:overlap){
            double deck=surfaceHeight(other,point),under=deck-obstacle.depth()-point.y(),over=point.y()-SLAB-deck;
            if(under<below){below=under;belowPoint=point;}
            if(over<above){above=over;abovePoint=point;}
          }
          if(below>=HEADROOM-1e-7||above>=obstacle.aboveHeight()-1e-7)continue;
          boolean under=below-HEADROOM>above-obstacle.aboveHeight();
          double available=under?below:above,required=under?HEADROOM:obstacle.aboveHeight();
          if(worst==null||required-available>worst.required()-worst.available())
            worst=new ClearanceFailure(strip.side(),strip.from(),strip.to(),under?belowPoint:abovePoint,available,required);
        }
    }
    if(worst!=null)throw new ValidationException(String.format(Locale.ROOT,
        "非机动车道通行净空不足：可用 %.6f 格 / 要求 %.6f 格；请移动相交匝道或调整高度",
        worst.available(),worst.required()),ValidationException.Reason.CLEARANCE,worst.side(),
        worst.from(),worst.to(),Double.NaN,worst.point(),worst.point());
  }
  private static List<List<V>> triangles(List<V> quad){
    return List.of(List.of(quad.get(0),quad.get(1),quad.get(2)),List.of(quad.get(0),quad.get(2),quad.get(3)));
  }
  private static double surfaceHeight(List<V> triangle,V point){
    var a=triangle.get(0);var b=triangle.get(1).sub(a);var c=triangle.get(2).sub(a);var q=point.sub(a);
    double determinant=b.x()*c.z()-b.z()*c.x();
    return a.y()+b.y()*(q.x()*c.z()-q.z()*c.x())/determinant+c.y()*(b.x()*q.z()-b.z()*q.x())/determinant;
  }
  private static boolean sameObstacles(List<Neighbor> current,List<Neighbor> previous){
    if(previous==null)return false;
    var before=new HashSet<ObstacleGeometry>();var after=new HashSet<ObstacleGeometry>();
    for(var neighbor:previous)if(ramp(neighbor.mesh()))before.add(obstacleGeometry(neighbor));
    for(var neighbor:current)if(ramp(neighbor.mesh()))after.add(obstacleGeometry(neighbor));
    return before.equals(after);
  }
  /** Every physical input read by the span planner and by apply()'s shell cut.
   * Mesh equality also compares rail/paint/attachment metadata, which can
   * change during an incidental rebuild without changing either obstacle.
   * Keep raw sample stations/orientations/widths, roof order and exact doubles:
   * they determine interpolation, first matching roof and actual clearances. */
  private record ObstacleGeometry(List<Sample> samples,double length,double thickness,double girderDepth,
      boolean tunnel,double clearance,List<RoadAutoTunnels.RoofRegion> roofs){}
  private static ObstacleGeometry obstacleGeometry(Neighbor neighbor){
    RoadPlanningBudget.check();
    var mesh=neighbor.mesh();
    return new ObstacleGeometry(List.copyOf(mesh.samples()),mesh.length(),mesh.settings().thickness(),RoadInfrastructure.girderDepth(mesh),
        mesh.settings().structure()==Structure.TUNNEL,RoadInfrastructure.clearance(mesh.settings()),List.copyOf(neighbor.roofs()));
  }
  private static boolean ramp(Mesh mesh){return mesh.settings().style().ramp()||mesh.settings().options().lanePoints().link()!=null;}
  /** Validate the final sampled bicycle surface after every merge and endpoint clamp.
   * Existing saved spans still load; new planning may not silently shorten an approach
   * while keeping its drop. Sample centers are shared with travel(), including base
   * grade and lateral curvature, so the tested slope is the actual travel geometry. */
  public static void validate(Mesh road,List<Span> spans){
    validate(road,spans,false);
  }
  /** Call only after validating the complete connected corridor. */
  public static void validateSection(Mesh road,List<Span> spans){validate(road,spans,true);}
  /** Preview and construction use the same physical underside, including bank. */
  public static void validateFloor(Mesh road,List<Span> spans,double minimumHeight){
    for(var span:spans)if(span.delta()<0)for(double d=Math.max(0,span.start());d<=Math.min(road.length(),span.end());d+=1){
      var at=RoadStructures.sample(road,d);
      for(double fraction:new double[]{.05,.5,.95}){
        double lateral=inner(road,at,span.side())*(1-fraction)+outer(road,at,span.side())*fraction;
        V bottom=at.at(span.side()*lateral,SLAB-span.height(d));
        if(bottom.y()<minimumHeight)throw new RoadCrossingFailure.GeometryRejection("非机动车下穿超出世界最低高度；请先提高相交道路，保留地下通道空间");
      }
    }
  }
  private static void validate(Mesh road,List<Span> spans,boolean continuation){
    if(!eligible(road)||spans.isEmpty())return;
    var planned=with(road,spans);
    for(var span:spans){
      if(!continuation&&(span.start()<0||span.end()>road.length()+1e-7)||span.from()-span.start()<1e-7||span.end()-span.to()<1e-7)
        throw new ValidationException("非机动车道引道空间不足，无法保留端点高程；请移动连接点或延长道路",
            ValidationException.Reason.APPROACH_SPACE,span.side(),span.start(),span.end(),Double.NaN);
      if(Math.abs(span.height(span.start()))>1e-7||Math.abs(span.height(span.end()))>1e-7)
        throw new ValidationException("非机动车道引道端点高程不连续",ValidationException.Reason.ENDPOINT_HEIGHT,
            span.side(),span.start(),span.end(),Double.NaN);
    }
    ValidationException worst=null;
    for(int side:new int[]{-1,1}){
      var samples=cycleSamples(planned,side);
      for(int i=1;i<samples.size();i++){
        if((i&63)==0)RoadPlanningBudget.check();
        var a=samples.get(i-1);var b=samples.get(i);double mid=(a.distance()+b.distance())/2;
        if(spans.stream().noneMatch(s->s.side()==side&&mid>=s.start()&&mid<=s.end()))continue;
        double run=Math.hypot(b.center().x()-a.center().x(),b.center().z()-a.center().z());
        double rise=Math.abs(b.center().y()-a.center().y());
        if(run<1e-9?rise>1e-9:rise/run>MAX_GRADE+1e-7){
          double slope=run<1e-9?Double.POSITIVE_INFINITY:rise/run;
          if(worst==null||slope>worst.slope())worst=new ValidationException(
              "非机动车道实际引道坡度超过 16%，剩余空间不足；请移动连接点或延长道路",
              ValidationException.Reason.GRADE,side,a.distance(),b.distance(),slope,a.center(),b.center());
        }
      }
    }
    if(worst!=null)throw worst;
  }
  public List<Part> apply(List<Part> original){
    if(!active())return original;var out=new ArrayList<Part>();
    var openings=neighbors.stream().map(n->new RoadShellOpening(n,0,n.length(),d->RoadStructureClearance.headroom(n),1,0)).toList();
    // All original pedestrian parts are retained; only the bicycle strip is rebuilt.
    var ds=floorStations(road);
    for(int side:new int[]{-1,1})for(int i=1;i<ds.size();i++){
      double da=ds.get(i-1),db=ds.get(i),mid=(da+db)/2;var span=road.settings().options().streetscape().crossings().stream().filter(s->s.side()==side&&mid>s.start()&&mid<s.end()).findFirst().orElse(null);if(span==null)continue;
      var a=RoadStructures.sample(road,da);var b=RoadStructures.sample(road,db);double ha=span.height(da),hb=span.height(db),ia=inner(road,a,side),ib=inner(road,b,side),oa=outer(road,a,side),ob=outer(road,b,side);
      band(out,a,b,side,ia,oa,ib,ob,ha-SLAB,hb-SLAB,SLAB,Material.CONCRETE,"sr:cycle_bypass");
      boolean roof=span.delta()<0&&mid>=span.from()&&mid<=span.to();
      // Roofed crossing and open-cut approaches share the same floor and wall edges.
      double topA=span.delta()<0?(roof?Math.max(.2,ha+HEADROOM+ROOF):.5):ha+.35,topB=span.delta()<0?(roof?Math.max(.2,hb+HEADROOM+ROOF):.5):hb+.35;
      for(int edge:new int[]{0,1}){
        double ca=edge==0?ia+WALL/2:oa-WALL/2,cb=edge==0?ib+WALL/2:ob-WALL/2;
        double hA=topA-ha+SLAB,hB=topB-hb+SLAB;
        out.add(new Part(a.at(side*ca,SLAB-ha),b.at(side*cb,SLAB-hb),WALL,Math.max(hA,hB),false,span.delta()<0?Material.TUNNEL:Material.CONCRETE,a.left().mul(WALL/2),b.left().mul(WALL/2),"sr:cycle_wall",Math.abs(hA-hB)<1e-8?null:new Heights(hA,hA,hB,hB)));
        if(span.delta()>0||!roof){
          double railA=span.delta()>0?ha+.35:topA,railB=span.delta()>0?hb+.35:topB;
          for(double y:new double[]{.25,.65})out.add(new Part(a.at(side*ca,-railA-y),b.at(side*cb,-railB-y),.1,.12,false,Material.STEEL,a.left().mul(.05),b.left().mul(.05),"sr:cycle_rail"));
          if((int)Math.floor(da*2)%4==0||Math.abs(da-span.start())<1e-7||Math.abs(da-span.to())<1e-7)out.add(new Part(a.at(side*ca,-railA),a.at(side*ca,-railA),.12,.77,true,Material.STEEL,null,null,"sr:cycle_rail"));
          if(Math.abs(db-span.from())<1e-7||Math.abs(db-span.end())<1e-7)out.add(new Part(b.at(side*cb,-railB),b.at(side*cb,-railB),.12,.77,true,Material.STEEL,null,null,"sr:cycle_rail"));
        }
      }
      if(roof){
        band(out,a,b,side,ia,oa,ib,ob,ha+HEADROOM,hb+HEADROOM,ROOF,Material.TUNNEL,"sr:cycle_roof");
        band(out,a,b,side,(ia+oa)/2-.08,(ia+oa)/2+.08,(ib+ob)/2-.08,(ib+ob)/2+.08,ha+HEADROOM-.09,hb+HEADROOM-.09,.07,Material.LAMP,"sr:cycle_light");
        // Close the dirt above the tunnel lip and pave the unused surface beside
        // the ramp. Ramp deck/air is subtracted below, so its live lane stays clear.
        double bottomA=ha+HEADROOM+ROOF,bottomB=hb+HEADROOM+ROOF;
        double coverA=Math.max(0,.0-bottomA),coverB=Math.max(0,.0-bottomB);
        if(Math.min(coverA,coverB)>1e-8)out.add(new Part(a.at(side*(ia+oa)/2,-bottomA),b.at(side*(ib+ob)/2,-bottomB),Math.max(oa-ia,ob-ib),Math.max(coverA,coverB),false,Material.TUNNEL,a.left().mul((oa-ia)/2),b.left().mul((ob-ib)/2),"sr:cycle_portal",new Heights(coverA,coverA,coverB,coverB)));
        var walk=road.settings().options().sidewalk();
        band(out,a,b,side,ia,a.halfWidth(),ib,b.halfWidth(),0,0,.2,RoadSidewalks.Finish.of(walk.material()).material(),"sr:cycle_cover_walk");
      }
    }
    for(var air:openings){
      var kept=new ArrayList<Part>();for(var part:out)kept.addAll(air.cut(part));out=kept;
    }
    // Guard the boundary of the resulting open space, regardless of which kind
    // of road or cover created it. Roof ends and cropped host ends also count.
    for(var crossing:neighbors)cutoffRails(out,crossing,openings);
    for(var span:road.settings().options().streetscape().crossings())if(span.delta()<0){
      for(double d:new TreeSet<>(List.of(span.from(),span.to(),0.0,road.length()))){
        if(d<0||d>road.length()||span.height(d)>-.7)continue;
        var at=RoadStructures.sample(road,d);double inner=inner(road,at,span.side())+WALL/2,outer=outer(road,at,span.side())-WALL/2;
        V a=at.at(span.side()*inner,-.5),b=at.at(span.side()*outer,-.5);
        double probe=d==span.from()?-.08:d==span.to()?.08:d==0?-.08:.08;
        V along=at.left().left().mul(-probe),middle=a.add(b).mul(.5);
        // At an end shared with another lowered cycle path there is no exposed
        // lip. The next segment owns its continuation, not a transverse fence.
        boolean continuing=(d==0||d==road.length())&&neighbors.stream().anyMatch(n->new RoadsideBypass(n,List.of()).openCutLip(middle.add(along).add(new V(0,-.5,0))));
        if(!continuing){lipRail(out,a,b,openings);for(V p:List.of(a,b)){var post=new Part(p,p,.12,.77,true,Material.STEEL,null,null,"sr:cycle_rail");if(openings.stream().allMatch(c->c.cut(post).equals(List.of(post))))out.add(post);}}
      }
    }
    var result=new ArrayList<>(original);result.addAll(out);return List.copyOf(result);
  }
  private void cutoffRails(List<Part> out,Mesh crossing,List<RoadShellOpening> openings){
    for(int side:new int[]{-1,1}){
      V previous=null;double run=0;
      for(double d=0;d<crossing.length();d+=.25){
        var first=RoadStructures.sample(crossing,d);var last=RoadStructures.sample(crossing,Math.min(crossing.length(),d+.25));
        V a=first.at(side*(first.halfWidth()+.08),0),b=last.at(side*(last.halfWidth()+.08),0);
        boolean insideA=openCutLip(a),insideB=openCutLip(b);
        if(!insideA&&!insideB){previous=null;run=0;continue;}
        if(insideA!=insideB){V low=a,high=b;for(int i=0;i<18;i++){V mid=low.add(high).mul(.5);if(openCutLip(mid)==insideA)low=mid;else high=mid;}if(insideA)b=low;else a=high;}
        var qa=RoadQueries.horizontal(road,a);var qb=RoadQueries.horizontal(road,b);
        a=new V(a.x(),qa.sample().center().y()+.5,a.z());b=new V(b.x(),qb.sample().center().y()+.5,b.z());
        if(a.distance(b)<1e-6)continue;
        if(previous==null||previous.distance(a)>.001){lipPost(out,a,openings);run=0;}
        lipRail(out,a,b,openings);
        run+=a.distance(b);
        if(run>=2||!insideB||d+.25>=crossing.length()){lipPost(out,b,openings);run=0;}
        previous=b;
      }
    }
  }
  private void lipPost(List<Part> out,V p,List<RoadShellOpening> openings){
    var post=new Part(p,p,.12,.77,true,Material.STEEL,null,null,"sr:cycle_rail");
    if(openings.stream().allMatch(c->c.cut(post).equals(List.of(post))))out.add(post);
  }
  private void lipRail(List<Part> out,V a,V b,List<RoadShellOpening> openings){
    var parts=new ArrayList<Part>();
    for(double y:new double[]{.25,.65})parts.add(new Part(a.add(new V(0,y,0)),b.add(new V(0,y,0)),.1,.12,false,Material.STEEL,null,null,"sr:cycle_rail"));
    // Only pieces on the exposed side survive. The same rule covers a railway,
    // an ordinary crossing, a joining ramp and a covered pedestrian surface.
    for(var cut:openings){var kept=new ArrayList<Part>();for(var p:parts)kept.addAll(cut.cut(p));parts=kept;if(parts.isEmpty())return;}
    out.addAll(parts);
  }
  private boolean openCutLip(V p){
    if(!active())return false;
    var q=RoadQueries.horizontal(road,p);var at=q.sample();int side=q.lateral()<0?-1:1;
    // Projection clamps to an endpoint. A continued descent on this segment
    // must not claim the next road's edge hundreds of blocks beyond that end.
    if(!road.closed()&&(at.distance()<1e-7&&p.sub(road.first().center()).dot(road.first().left().left().mul(-1))< -1e-7
        ||at.distance()>road.length()-1e-7&&p.sub(road.last().center()).dot(road.last().left().left().mul(-1))>1e-7))return false;
    if(Math.abs(p.y()-at.center().y())>.6||q.lateral()*side<inner(road,at,side)+WALL/2||q.lateral()*side>outer(road,at,side)-WALL/2)return false;
    return road.settings().options().streetscape().crossings().stream().anyMatch(s->s.side()==side&&s.delta()<0&&at.distance()>s.start()&&at.distance()<s.end()
        &&(at.distance()<s.from()||at.distance()>s.to())&&s.height(at.distance())<-.7);
  }
  private static List<Double> floorStations(Mesh road){
    var stations=new TreeSet<Double>();for(double d=0;d<road.length();d+=.5)stations.add(d);stations.add(road.length());
    for(var span:road.settings().options().streetscape().crossings())for(double d:new double[]{span.start(),span.from(),span.to(),span.end()})if(d>=0&&d<=road.length())stations.add(d);
    return new ArrayList<>(stations);
  }
  private static void band(List<Part> out,Sample a,Sample b,int side,double ia,double oa,double ib,double ob,double ya,double yb,double height,Material material,String tag){
    double wa=oa-ia,wb=ob-ib;if(Math.min(wa,wb)<=0)return;
    out.add(new Part(a.at(side*(ia+oa)/2,-ya),b.at(side*(ib+ob)/2,-yb),Math.max(wa,wb),height,false,material,a.left().mul(wa/2),b.left().mul(wb/2),tag));
  }
  /** Exact top for the bicycle-only clearance column; motor clearance is unchanged. */
  public static double ceiling(Mesh mesh,double x,double z,double deckTop){
    if(!eligible(mesh)||mesh.settings().options().streetscape().crossings().isEmpty())return Double.NEGATIVE_INFINITY;
    var at=RoadQueries.horizontal(mesh,new V(x,deckTop,z));int side=at.lateral()<0?-1:1;double h=height(mesh,at.sample().distance(),side);
    if(Math.abs(h)<1e-7||Math.abs(at.lateral())<inner(mesh,at.sample(),side)-.6||Math.abs(at.lateral())>outer(mesh,at.sample(),side)+.6||Math.abs(deckTop-at.sample().center().y()-h)>.6)return Double.NEGATIVE_INFINITY;
    double ceiling=deckTop+HEADROOM;
    for(var span:mesh.settings().options().streetscape().crossings())if(span.side()==side&&span.delta()<0&&at.sample().distance()>span.start()&&at.sample().distance()<span.end()
        &&(at.sample().distance()<span.from()||at.sample().distance()>span.to()))ceiling=Math.max(ceiling,at.sample().center().y()+.2);
    return ceiling;
  }
  public static List<RoadTunnelSpace.Profiled> air(Mesh road){
    if(!eligible(road))return List.of();
    var out=new ArrayList<RoadTunnelSpace.Profiled>();
    for(var span:road.settings().options().streetscape().crossings())if(span.delta()<0){
      var samples=new ArrayList<Sample>();
      for(var at:road.samples())if(at.distance()>=span.start()&&at.distance()<=span.end()){
        double inner=inner(road,at,span.side())+WALL,outer=outer(road,at,span.side())-WALL;
        samples.add(new Sample(at.at(span.side()*(inner+outer)/2,-span.height(at.distance())),at.left(),at.distance(),(outer-inner)/2));
      }
      if(samples.size()<2)continue;
      var mesh=new Mesh(samples,road.settings(),road.min(),road.max(),road.length(),false,null);
      var profiles=new ArrayList<RoadAutoTunnels.RoofRegion>();
      for(int i=1;i<samples.size();i++){
        var a=samples.get(i-1);var b=samples.get(i);double mid=(a.distance()+b.distance())/2;
        double ha=HEADROOM,hb=HEADROOM;
        if(mid<span.from()||mid>span.to()){ha=Math.max(ha,-span.height(a.distance())+.2);hb=Math.max(hb,-span.height(b.distance())+.2);}
        profiles.add(new RoadAutoTunnels.RoofRegion(a.distance(),b.distance(),ha,hb));
      }
      out.add(new RoadTunnelSpace.Profiled(mesh,profiles));
    }return List.copyOf(out);
  }
  /** Cycle bands include lowered floors that are intentionally absent from LaneDeck. */
  public static List<Mesh> travel(Mesh road){
    if(!eligible(road))return List.of();
    var out=new ArrayList<Mesh>();
    for(int side:new int[]{-1,1}){
      var samples=cycleSamples(road,side);
      if(samples.size()>1){
        var settings=new Settings(Mode.CURVE,Style.UNMARKED,Math.max(.5,samples.get(0).halfWidth()*2),SLAB,.35,90);
        out.add(RoadRibbon.mesh(samples,settings));
      }
    }
    return List.copyOf(out);
  }
  private static List<Sample> cycleSamples(Mesh road,int side){
    var samples=new ArrayList<Sample>();
    for(var at:road.samples()){
      var layout=RoadProfile.layout(road,at);
      if(layout.cycleWidth()<=0||!layout.catalog().twoWay()&&layout.outside()!=side)continue;
      double lo=inner(road,at,side),hi=outer(road,at,side);if(hi<=lo)continue;
      samples.add(new Sample(at.at(side*(lo+hi)/2,-height(road,at.distance(),side)),at.left(),at.distance(),(hi-lo)/2));
    }
    return samples;
  }
  /** Excavation above the travel envelope cannot bear a bridge foundation either.
   * Open approaches are cleared all the way to original grade, including wall edges.
   * Covered spans retain their earth/roof above the actual tunnel ceiling. */
  public static List<Part> supportVoids(Mesh road){
    if(!eligible(road))return List.of();
    var out=new ArrayList<Part>();
    for(var span:road.settings().options().streetscape().crossings())if(span.delta()<0){
      var stations=new TreeSet<Double>();stations.add(Math.max(0,span.start()));stations.add(Math.min(road.length(),span.end()));
      for(double d:new double[]{span.from(),span.to()})if(d>0&&d<road.length())stations.add(d);
      for(double d=Math.max(0,span.start());d<Math.min(road.length(),span.end());d+=.5)stations.add(d);
      var ds=new ArrayList<>(stations);
      for(int i=1;i<ds.size();i++){
        double da=ds.get(i-1),db=ds.get(i),mid=(da+db)/2;var a=RoadStructures.sample(road,da);var b=RoadStructures.sample(road,db);
        double ha=span.height(da),hb=span.height(db);boolean open=mid<span.from()||mid>span.to();
        double ta=open?Math.max(.2,ha+HEADROOM):ha+HEADROOM,tb=open?Math.max(.2,hb+HEADROOM):hb+HEADROOM;
        double ia=inner(road,a,span.side()),oa=outer(road,a,span.side()),ib=inner(road,b,span.side()),ob=outer(road,b,span.side());
        double aa=ta-ha+SLAB,bb=tb-hb+SLAB;
        out.add(new Part(a.at(span.side()*(ia+oa)/2,SLAB-ha),b.at(span.side()*(ib+ob)/2,SLAB-hb),Math.max(oa-ia,ob-ib),Math.max(aa,bb),false,Material.CONCRETE,a.left().mul((oa-ia)/2),b.left().mul((ob-ib)/2),"sr:cycle_support_void",new Heights(aa,aa,bb,bb)));
      }
    }return List.copyOf(out);
  }
  public static boolean paved(Part p){return p.material()==Material.CONCRETE&&p.model().equals("sr:cycle_bypass");}
  public static boolean walkway(Part p){return p.material().name().startsWith("WALK_")||paved(p);}
  private static int tile(double v){return (int)Math.floor(v/8);}
  private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
}
