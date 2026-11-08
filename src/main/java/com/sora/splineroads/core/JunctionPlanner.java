package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import com.sora.splineroads.core.RoadSurface.Face;
import com.sora.splineroads.core.JunctionSpec.*;
import java.util.*;

/** One deterministic plan for preview, collision, markings, signals and persistence. */
public final class JunctionPlanner {
  public record Piece(Mesh mesh, List<Face> paint, List<Part> structures, int arm) {}
  public record Movement(int from, int lane, int to, int targetLane, int turn, List<V> path) {}
  public record Plan(List<Piece> pieces, List<Movement> movements, List<V> boundary, Map<RoadSidewalks.Cell,RoadSidewalks.Config> sidewalks,JunctionSignalCycle signals,Map<Part,JunctionSignals.Head> heads) {}
  /** Retain this piece, not a lookup that rebuilds a whole junction after LRU eviction. */
  public static final class Ref {
    private final JunctionSpec spec;private final int piece,geometryVersion;private final JunctionSlipProfiles slipProfiles;
    private final List<OneSidedJunction.Cut> hostCuts;
    private final List<Mesh> approaches,slips,refuges;private final Piece resolved;private final List<V> boundary;private final JunctionSignalCycle signals;private final Map<Part,JunctionSignals.Head> heads;private final Map<RoadSidewalks.Cell,RoadSidewalks.Config> sidewalks;
    public Ref(JunctionSpec spec,int piece){this(spec,piece,55);}
    public Ref(JunctionSpec spec,int piece,int version){
      this(spec,piece,version,List.of());
    }
    public Ref(JunctionSpec spec,int piece,int version,List<OneSidedJunction.Cut> hostCuts){
      if(version<21||version>55||piece<0)throw new IllegalArgumentException("路口分段无效");
      if(version>=50)spec=spec.supportedCrossings();
      var plan=plan(spec,version);if(piece>=plan.pieces().size())throw new IllegalArgumentException("路口分段无效");
      slipProfiles=version>=49&&JunctionSlips.enabled(spec)?new JunctionSlipProfiles(spec,plan(spec,47).pieces().subList(0,spec.arms().size()).stream().map(Piece::mesh).toList(),version>=52,version>=54):null;
      approaches=plan.pieces().subList(0,spec.arms().size()).stream().map(Piece::mesh).toList();
      slips=JunctionSlips.meshes(plan);refuges=plan.pieces().stream().filter(p->p.arm()==-1).map(Piece::mesh).toList();
      this.hostCuts=List.copyOf(hostCuts);
      if(!hostCuts.isEmpty()&&(version<54||spec.oneSide()==null||plan.pieces().get(piece).arm()!=-3))throw new IllegalArgumentException("共享主路裁剪数据无效");
      this.spec=spec;this.piece=piece;geometryVersion=version;resolved=hostCuts.isEmpty()?plan.pieces().get(piece):OneSidedJunction.retained(spec.oneSide(),hostCuts);boundary=plan.boundary();signals=plan.signals();heads=plan.heads();sidewalks=plan.sidewalks();
    }
    public JunctionSpec spec(){return spec;}public int piece(){return piece;}public int geometryVersion(){return geometryVersion;}
    public List<OneSidedJunction.Cut> hostCuts(){return hostCuts;}
    /** Immutable arm meshes survive plan-cache eviction; sign refresh never replans a junction. */
    public Mesh approach(int arm){return approaches.get(arm);}
    public Mesh furnitureApproach(int arm){return slipProfiles==null?approach(arm):slipProfiles.approaches.get(arm);}
    public List<Part> approachParts(List<Part> parts){return slipProfiles==null||resolved.arm()<0?parts:parts.stream().map(p->slipProfiles.part(resolved.arm(),p)).toList();}
    public List<Mesh> slips(){return slips;}
    public List<Mesh> refuges(){return refuges;}
    public List<Mesh> replacedCycleStrips(){return slipProfiles==null?List.of():slipProfiles.refugeStrips();}
    public Piece get(){return resolved;}public List<V> boundary(){return boundary;}public JunctionSignalCycle signals(){return signals;}
    public Map<RoadSidewalks.Cell,RoadSidewalks.Config> sidewalks(){return sidewalks;}
    public int headState(Part part,long time){
      if(part.material()==Material.SIGNAL_PEDESTRIAN)return signals.pedestrian(resolved.arm(),time);
      var binding=headBinding(part);return binding==null?signals.vehicle(resolved.arm(),time):signals.vehicle(binding.arm(),binding.lane(),binding.left(),time);
    }
    private JunctionSignals.Head headBinding(Part part){
      var exact=heads.get(part);if(exact!=null)return exact;
      for(var e:heads.entrySet()){var p=e.getKey();if(p.material()==part.material()&&p.a().equals(part.a())&&p.b().equals(part.b()))return e.getValue();}
      return null;
    }
    public Part headModel(Part part){return part;}
    @Override public boolean equals(Object other){return other instanceof Ref r&&piece==r.piece&&geometryVersion==r.geometryVersion&&spec.equals(r.spec)&&hostCuts.equals(r.hostCuts);}
    @Override public int hashCode(){return Objects.hash(spec,piece,geometryVersion,hostCuts);}
  }
  private record PlanKey(JunctionSpec spec,int version){}
  // Six entries thrash when several junctions' pieces arrive interleaved. Keep
  // a bounded working set and deduplicate identical in-flight plans without
  // blocking unrelated junctions behind one expensive build.
  private static final BoundedLoadingCache<PlanKey,Plan> CACHE = new BoundedLoadingCache<>(48,32L<<20,JunctionPlanner::planBytes);
  private static long planBytes(Plan p){long bytes=4096+p.boundary().size()*64L+p.sidewalks().size()*128L+p.heads().size()*256L;
    for(var piece:p.pieces()){bytes+=piece.mesh().samples().size()*256L+piece.structures().size()*512L;for(var face:piece.paint())bytes+=128+face.points().size()*64L;}
    for(var movement:p.movements())bytes+=128+movement.path().size()*64L;return bytes;}
  public static Plan plan(JunctionSpec s) {return plan(s,55);}
  private static Plan plan(JunctionSpec s,int version) { return CACHE.get(new PlanKey(s,version),k->build(k.spec(),k.version())); }
  public static Settings settings(double width,double thick) {
    return new Settings(Mode.STRAIGHT,Style.UNMARKED,width,thick,.35,90).structure(Structure.GROUND);
  }
  private static V radial(JunctionSpec s,int i) { return new JunctionSides(s,true).outward.get(i); }
  public static boolean separatedEntrances(JunctionSpec s){return new JunctionSides(s,true).separated();}
  private static double cross(V a,V b) { return a.x()*b.z()-a.z()*b.x(); }
  private static V bezier(V a,V b,V c,V d,double t) {
    double u=1-t; return a.mul(u*u*u).add(b.mul(3*u*u*t)).add(c.mul(3*u*t*t)).add(d.mul(t*t*t));
  }
  public static int turn(JunctionSpec s,int from,int to) {
    if(from==to||new JunctionSides(s,true).paired(from,to))return JunctionSpec.UTURN;
    V f=radial(s,from).mul(-1), d=radial(s,to);
    if(f.dot(d)>.707106)return JunctionSpec.STRAIGHT;
    return f.left().dot(d)>0?JunctionSpec.RIGHT:JunctionSpec.LEFT;
  }
  public static int mask(JunctionSpec s,int from,int lane) {
    int result=requestedMask(s,from,lane);
    if(!s.arms().get(from).rightSlip())return result;
    result&=~JunctionSpec.RIGHT;
    if(result==0){
      int available=0;for(int j=0;j<s.arms().size();j++)if(j!=from&&s.arms().get(j).outgoing()>0)available|=turn(s,from,j);
      result=(available&JunctionSpec.STRAIGHT)!=0?JunctionSpec.STRAIGHT:available&JunctionSpec.LEFT;
    }
    return result;
  }
  private static int requestedMask(JunctionSpec s,int from,int lane) {
    int requested=s.arms().get(from).lanes().get(lane).mask();
    if(requested>=0)return requested;
    int available=0;
    for(int j=0;j<s.arms().size();j++)if(j!=from&&s.arms().get(j).outgoing()>0&&turn(s,from,j)!=JunctionSpec.UTURN)available|=turn(s,from,j);
    if(s.kind()==Kind.ROUNDABOUT&&available==0&&s.arms().get(from).outgoing()>0)available=JunctionSpec.UTURN;
    int count=s.arms().get(from).incoming();
    if(count==1)return available;
    int result=available&JunctionSpec.STRAIGHT;
    if(result==0) {
      if((available&(JunctionSpec.LEFT|JunctionSpec.RIGHT))==(JunctionSpec.LEFT|JunctionSpec.RIGHT))
        return lane<(count+1)/2?JunctionSpec.LEFT:JunctionSpec.RIGHT;
      return available;
    }
    if(lane==0)result|=available&JunctionSpec.LEFT;
    if(lane==count-1)result|=available&JunctionSpec.RIGHT;
    return result;
  }
  /** 0 red, 1 green, 2 yellow. Pedestrian heads follow their local vehicle mast. */
  public static int signal(JunctionSpec s,int arm,long time){return plan(s).signals().vehicle(arm,time);}
  public static int pedestrianSignal(JunctionSpec s,int arm,long time){return plan(s).signals().pedestrian(arm,time);}
  public static double radius(JunctionSpec s) {
    return new JunctionSides(s,true).radius(s);
  }
  private static Plan build(JunctionSpec source,int geometryVersion) {
    return build(source,geometryVersion,false);
  }
  private static Plan build(JunctionSpec source,int geometryVersion,boolean singleArrowRow) {
    if(source.oneSide()!=null){if(geometryVersion<51)throw new IllegalArgumentException("单边路口需要几何版本51");return OneSidedJunction.apply(source,build(OneSidedJunction.prepare(source),geometryVersion,geometryVersion>=54));}
    final JunctionSpec s=geometryVersion>=50?source.supportedCrossings():geometryVersion>=39&&geometryVersion<47?source.arms(source.arms().stream().map(JunctionPlanner::bridgeArm).toList()):source;
    int n=s.arms().size();
    var sides=new JunctionSides(s,geometryVersion>=35);List<Integer> order=sides.order;
    double radius=sides.radius(s,geometryVersion>=46);
    if(s.kind()==Kind.ROUNDABOUT)for(int k=0;k<n;k++){
      int i=order.get(k),j=order.get((k+1)%n);V a=sides.outward.get(i),b=sides.outward.get(j);
      double angle=Math.atan2(cross(a,b),a.dot(b));if(angle<=0)angle+=2*Math.PI;
      if(angle<Math.toRadians(25))throw new IllegalArgumentException("相邻接入口夹角至少 25°；请拉开端点");
      double span=Math.asin(Math.min(.99,s.arms().get(i).width()/radius/2))+Math.asin(Math.min(.99,s.arms().get(j).width()/radius/2));
      if(angle<span+Math.toRadians(2))throw new IllegalArgumentException("相邻环岛接入口过近；请拉开方向或增大中央岛半径");
    }
    List<Mesh> approaches=new ArrayList<>(); List<V> mouths=new ArrayList<>();
    for(int i=0;i<n;i++) {
      Arm a=s.arms().get(i); V r=sides.outward.get(i);
      double mouthRadius=radius;
      if(s.kind()==Kind.ROUNDABOUT) {
        double square=radius*radius-a.width()*a.width()/4;
        if(square<=0 || Math.sqrt(square)-.35<s.islandRadius()+.25)
          throw new IllegalArgumentException("接入口 "+(i+1)+" 过宽；请增加环道车道数或宽度");
        mouthRadius=Math.sqrt(square)-.35;
      }
      V end=sides.mouth(s,i,mouthRadius);
      double length=a.endpoint().position().sub(end).horizontalLength();
      if(a.endpoint().position().sub(s.center()).dot(r)<radius+8)
        throw new IllegalArgumentException("接入口 "+(i+1)+" 太近；离中心至少 "+(int)Math.ceil(radius+8)+" 格");
      if(length>512)throw new IllegalArgumentException("接入口距路口边缘最多 512 格");
      if(a.inward().dot(r.mul(-1))<.5)throw new IllegalArgumentException("接入口 "+(i+1)+" 朝向偏离路口，请调整端点朝向");
      V start=a.endpoint().position(),c1=start.add(a.inward().mul(length*.33)),c2=end.add(r.mul(length*.33));
      int steps=(int)Math.ceil(length*2); var samples=new ArrayList<Sample>(); V prev=null; double dist=0;
      for(int k=0;k<=steps;k++) {
        double t=(double)k/steps; V p=bezier(start,c1,c2,end,t);
        double slope=a.endpoint().grade()*a.endpoint().direction().dot(a.inward());
        double y=(2*t*t*t-3*t*t+1)*start.y()+(t*t*t-2*t*t+t)*length*slope+(-2*t*t*t+3*t*t)*end.y();
        p=new V(p.x(),y,p.z());
        V tangent=k==0?a.inward():k==steps?r.mul(-1):bezier(start,c1,c2,end,Math.min(1,t+.0001)).sub(bezier(start,c1,c2,end,Math.max(0,t-.0001))).horizontalUnit();
        if(prev!=null)dist+=p.distance(prev);
        double blend=Settings.smooth(Math.min(1,t*2));
        samples.add(new Sample(p,tangent.left(),dist,((a.attached()?(geometryVersion>=23?a.external().startWidth():a.external().width()):a.width())*(1-blend)+a.width()*blend)/2)); prev=p;
      }
      var approachSettings=settings(a.width(),s.thickness()).options(RoadProfile.Options.DEFAULT.sidewalk(a.external().options().sidewalk()).streetscape(a.external().options().streetscape()));
      if(geometryVersion>=47)approachSettings=approachSettings.structure(a.external().structure()).options(a.external().options().traffic(s.leftTraffic()).lanePoints(LanePoints.Data.EMPTY));
      approaches.add(RoadRibbon.mesh(samples,approachSettings)); mouths.add(end);
    }
    var movement=new ArrayList<Movement>(); var guides=new ArrayList<Face>();
    for(int i=0;i<n;i++)for(int lane=0;lane<s.arms().get(i).incoming();lane++) {
      Arm a=s.arms().get(i); Lane config=a.lanes().get(lane); int mask=mask(s,i,lane);
      for(int ti=0;ti<4;ti++)if((mask&JunctionSpec.TURNS[ti])!=0) {
        int t=JunctionSpec.TURNS[ti],target=config.targets().get(ti);
        if(target<0) {
          double best=Double.POSITIVE_INFINITY;
          for(int j=0;j<n;j++)if(s.arms().get(j).outgoing()>0&&turn(s,i,j)==t) {
            double angle=Math.acos(Math.max(-1,Math.min(1,radial(s,i).mul(-1).dot(radial(s,j)))));
            double score=Math.abs(angle-(t==JunctionSpec.STRAIGHT?0:t==JunctionSpec.UTURN?Math.PI:Math.PI/2));
            if(score<best){best=score;target=j;}
          }
        }
        if(target<0||target>=n||s.arms().get(target).outgoing()==0||turn(s,i,target)!=t)
          throw new IllegalArgumentException("接入口 "+(i+1)+" 第 "+(lane+1)+" 车道的"+JunctionSpec.maskName(t)+"没有有效出口");
        Arm b=s.arms().get(target); int dest=config.targetLanes().get(ti);
        if(dest<0)dest=Math.min(lane,b.outgoing()-1);
        if(geometryVersion>=49&&JunctionSlips.receives(s,target))dest=Math.min(dest,b.outgoing()-2);
        if(dest>=b.outgoing())throw new IllegalArgumentException("目标出口车道不存在");
        V f=radial(s,i).mul(-1),g=radial(s,target),p=mouths.get(i).add(f.left().mul(a.laneCenter(true,lane,s.leftTraffic()))),q=mouths.get(target).add(g.mul(-1).left().mul(b.laneCenter(false,dest,s.leftTraffic())));
        var path=new ArrayList<V>();
        if(s.kind()==Kind.ROUNDABOUT) {
          double rr=s.islandRadius()+(Math.min(lane,s.ringLanes()-1)+.5)*s.ringLaneWidth();
          double aa=Math.atan2(p.z()-s.center().z(),p.x()-s.center().x()),bb=Math.atan2(q.z()-s.center().z(),q.x()-s.center().x());
          double sign=s.leftTraffic()?1:-1,delta=(bb-aa)*sign; while(delta<=.01)delta+=Math.PI*2;
          path.add(p); for(int k=0;k<=64;k++){double th=aa+sign*delta*k/64;path.add(s.center().add(new V(Math.cos(th)*rr,0,Math.sin(th)*rr)));}path.add(q);
        } else {
          double handle=t==JunctionSpec.UTURN?Math.max(a.width(),6):p.distance(q)*.45;
          for(int k=0;k<=32;k++)path.add(bezier(p,p.add(f.mul(handle)),q.sub(g.mul(handle)),q,k/32.));
        }
        movement.add(new Movement(i,lane,target,dest,t,List.copyOf(path)));
      }
    }
    // Paint lane boundaries, never the whole set of vehicle trajectories. Adjacent
    // lanes reaching the same exit share one divider; converging lanes share none.
    var guidePaths=new ArrayList<JunctionGuides.Guide>();
    if(s.guides()&&s.kind()==Kind.INTERSECTION)for(Movement a:movement)for(Movement b:movement)
      if(a.from()==b.from()&&a.to()==b.to()&&a.turn()==b.turn()&&b.lane()==a.lane()+1
          &&Math.abs(b.targetLane()-a.targetLane())==1&&a.turn()!=JunctionSpec.UTURN) {
        var edge=new ArrayList<V>();for(int k=0;k<a.path().size();k++)edge.add(a.path().get(k).add(b.path().get(k)).mul(.5));
        if(geometryVersion>=41)guidePaths.add(new JunctionGuides.Guide(a.to(),Math.min(a.targetLane(),b.targetLane()),edge));
        else JunctionPaint.path(guides,edge,.14,true,JunctionPaint.WHITE);
      }
    if(geometryVersion>=41)JunctionGuides.paint(guides,guidePaths);
    var pieces=new ArrayList<Piece>();
    for(int i=0;i<n;i++)pieces.add(approach(s,i,approaches.get(i),approaches,geometryVersion,singleArrowRow));
    var cornerApproaches=approaches;
    if(geometryVersion>=49&&JunctionSlips.enabled(s)){
      JunctionSlips.layout(s,approaches,geometryVersion>=52,geometryVersion>=54); // Validate before resolving destination indices and corner sections.
      var profiles=new JunctionSlipProfiles(s,approaches,geometryVersion>=52,geometryVersion>=54);cornerApproaches=new ArrayList<>();
      for(int i=0;i<n;i++)cornerApproaches.add(profiles.approachMesh(i));
    }
    var boundary=new ArrayList<V>();
    if(s.kind()==Kind.INTERSECTION) {
      for(int k=0;k<n;k++) {
        int i=order.get(k),j=order.get((k+1)%n); V r=sides.outward.get(i),next=sides.outward.get(j);
        var mi=cornerApproaches.get(i).last();var mj=cornerApproaches.get(j).last();
        V first=cornerApproaches!=approaches?mi.at(mi.halfWidth(),0):mouths.get(i).sub(r.left().mul(s.arms().get(i).width()/2));
        V a=cornerApproaches!=approaches?mi.at(-mi.halfWidth(),0):mouths.get(i).add(r.left().mul(s.arms().get(i).width()/2));
        V b=cornerApproaches!=approaches?mj.at(mj.halfWidth(),0):mouths.get(j).sub(next.left().mul(s.arms().get(j).width()/2));
        boundary.add(first); boundary.add(a);
        double angle=Math.atan2(cross(r,next),r.dot(next)); if(angle<=0)angle+=2*Math.PI;
        V control=a.add(b).mul(.5);
        // A two-arm corner also needs its convex outside turn. A straight chord
        // across a reflex sector used to cut away half the junction pavement.
        if(geometryVersion<22?angle<Math.PI-.01:Math.abs(cross(r,next))>.01){double d=cross(b.sub(a),next)/cross(r,next);control=a.add(r.mul(d));}
        // Unequal widths at an almost straight two-arm junction send the ray
        // intersection arbitrarily far away. Join the mouths with a bounded taper.
        if(geometryVersion>=29?r.dot(next)<-.995:geometryVersion>=28&&n==2&&r.dot(next)<-.8660254)control=a.add(b).mul(.5);
        // Legacy records refer to triangle numbers, so preserve their exact topology
        // until the player rebuilds the junction. Never reinterpret an old index.
        int steps=geometryVersion<22?5:Math.max(5,(int)Math.ceil((a.distance(control)+control.distance(b))/.8));
        if(geometryVersion>=38){
          // A valid curb leaves the first mouth inward and reaches the next mouth outward.
          // Unequal widths can put the old quadratic control behind a mouth, creating a notch.
          double h0=-control.sub(a).dot(r)*2/3,h1=-control.sub(b).dot(next)*2/3;
          if(h0<.05||h1<.05){h0=h1=Math.min(s.cornerRadius()*1.25,a.distance(b)*.38);}
          double limit=a.distance(b)*.8;h0=Math.min(h0,limit);h1=Math.min(h1,limit);
          V c1=a.sub(r.mul(h0)),c2=b.sub(next.mul(h1));
          for(int z=1;z<steps;z++){double t=(double)z/steps;boundary.add(bezier(a,c1,c2,b,t));}
        }else for(int z=1;z<steps;z++){double t=(double)z/steps;boundary.add(a.mul((1-t)*(1-t)).add(control.mul(2*t*(1-t))).add(b.mul(t*t)));}
      }
      var edges=new ArrayList<Part>();
      for(int k=0;k<boundary.size();k++) {
        V a=boundary.get(k),b=boundary.get((k+1)%boundary.size());
        if(approaches.stream().anyMatch(m->RoadQueries.contains(m,a.add(b).mul(.5),.02,.1)))continue;
        if(geometryVersion>=44&&s.outerRail())
          RoadStructures.barrier(edges,a,b,false,true,k*.5);
        if(s.arms().stream().anyMatch(arm->arm.curbWidth()>0)) {
          if(geometryVersion<27)edges.add(new Part(a,b,.3,.2,false,Material.CONCRETE));
          else {
            V ia=boundaryInset(s,approaches,boundary,k,geometryVersion>=32),ib=boundaryInset(s,approaches,boundary,(k+1)%boundary.size(),geometryVersion>=32);
            double width=Math.max(ia.horizontalLength(),ib.horizontalLength());
            if(width>.001)edges.add(new Part(a.add(ia.mul(.5)),b.add(ib.mul(.5)),width,.2,false,Material.CONCRETE).frames(ia.mul(.5),ib.mul(.5)));
          }
        }
      }
      boolean firstPiece=true;
      for(var triangle:geometryVersion>=29?JunctionPaint.triangulate(boundary):JunctionPaint.triangulateLegacy(boundary)){pieces.add(new Piece(triangle(triangle,s.thickness()),List.copyOf(guides),firstPiece?List.copyOf(edges):List.of(),-1));firstPiece=false;}
    } else {
      double mid=s.islandRadius()+s.ringLanes()*s.ringLaneWidth()/2,half=s.ringLanes()*s.ringLaneWidth()/2;
      int count=(int)Math.ceil(2*Math.PI*mid*2);var samples=new ArrayList<Sample>();var paint=new ArrayList<Face>();
      for(int k=0;k<=count;k++){double t=2*Math.PI*k/count;V r=new V(Math.cos(t),0,Math.sin(t));samples.add(new Sample(s.center().add(r.mul(mid)),r,k*2*Math.PI*mid/count,half));}
      Mesh raw=RoadRibbon.mesh(samples,settings(half*2,s.thickness()));
      Mesh ring=new Mesh(raw.samples(),raw.settings(),raw.min(),raw.max(),raw.length(),true);
      for(int lane=0;lane<s.ringLanes();lane++) {
        var line=new ArrayList<V>();double rr=s.islandRadius()+lane*s.ringLaneWidth();
        for(int k=0;k<=count;k++){double t=2*Math.PI*k/count;line.add(s.center().add(new V(Math.cos(t)*rr,0,Math.sin(t)*rr)));}
        JunctionPaint.path(paint,line,.15,lane>0,JunctionPaint.WHITE);
      }
      for(int k=1;k<=count;k++) {
        double ta=2*Math.PI*(k-1)/count,tb=2*Math.PI*k/count;
        V a=s.center().add(new V(Math.cos(ta)*(mid+half-.2),0,Math.sin(ta)*(mid+half-.2)));
        V b=s.center().add(new V(Math.cos(tb)*(mid+half-.2),0,Math.sin(tb)*(mid+half-.2)));
        if(approaches.stream().noneMatch(m->RoadQueries.contains(m,a,.1,.1)||RoadQueries.contains(m,b,.1,.1)))
          JunctionPaint.line(paint,a,b,.15,JunctionPaint.WHITE);
      }
      var island=new ArrayList<Part>();
      if(s.greenIsland()&&geometryVersion<43)for(double z=-s.islandRadius()+.5;z<s.islandRadius();z+=1) {
        double x=Math.sqrt(Math.max(0,s.islandRadius()*s.islandRadius()-Math.pow(Math.abs(z)+.5,2)))-.2;
        if(x>.1){V a=s.center().add(new V(-x,0,z)),b=s.center().add(new V(x,0,z));island.add(new Part(a,b,1,.35,false,Material.SOIL));island.add(new Part(a.add(new V(0,.35,0)),b.add(new V(0,.35,0)),1,.4,false,Material.GREEN));}
      }
      // Continuous curb hides the stair-stepped fill boundary without widening the
      // circulating lanes. The outside rail opens only at actual connected roads.
      for(int k=1;k<=count;k++) {
        double ta=2*Math.PI*(k-1)/count,tb=2*Math.PI*k/count;
        V ra=new V(Math.cos(ta),0,Math.sin(ta)),rb=new V(Math.cos(tb),0,Math.sin(tb));
        if(s.greenIsland()&&geometryVersion>=43){
          // A triangle fan shares the curb's exact inner vertices, without raster steps.
          V a=s.center().add(ra.mul(s.islandRadius()-.30)),b=s.center().add(rb.mul(s.islandRadius()-.30));
          V edge=a.add(b).mul(.5),frame=a.sub(b).mul(.5),zero=new V(0,0,0);
          island.add(new Part(edge,s.center(),a.distance(b),.35,false,Material.SOIL,frame,zero,"sr:roundabout_soil"));
          island.add(new Part(edge.add(new V(0,.35,0)),s.center().add(new V(0,.35,0)),a.distance(b),.4,false,Material.GREEN,frame,zero,"sr:roundabout_green"));
        }
        island.add(new Part(s.center().add(ra.mul(s.islandRadius()-.15)),s.center().add(rb.mul(s.islandRadius()-.15)),.3,.4,false,Material.CONCRETE).frames(ra.mul(.15),rb.mul(.15)));
        V a=s.center().add(ra.mul(radius-.16)),b=s.center().add(rb.mul(radius-.16));
        if(s.outerRail()&&approaches.stream().noneMatch(m->RoadQueries.contains(m,a,1,.1)||RoadQueries.contains(m,b,1,.1)))RoadStructures.barrier(island,a,b,false,false,k*.5);
      }
      pieces.add(new Piece(ring,List.copyOf(paint),List.copyOf(island),-1));
      for(int k=0;k<64;k++){double t=2*Math.PI*k/64;boundary.add(s.center().add(new V(Math.cos(t)*radius,0,Math.sin(t)*radius)));}
    }
    if(geometryVersion>=38||geometryVersion>=37&&s.kind()==Kind.ROUNDABOUT)boundary=new ArrayList<>(RoadSidewalks.splitBoundary(boundary,approaches));
    List<Integer> pedestrianArms=List.of();Map<Part,JunctionSignals.Head> heads=Map.of();
    boolean lateSignals=geometryVersion>=55&&JunctionSlips.enabled(s)&&s.control()==Control.SIGNALS;
    if(s.control()==Control.SIGNALS&&!lateSignals) {
      var layout=JunctionSignals.structures(s,approaches,boundary,geometryVersion>=25,geometryVersion>=26);var signals=layout.structures();pedestrianArms=layout.pedestrianArms();heads=layout.heads();
      for(int i=0;i<n;i++) {
        Piece old=pieces.get(i);var parts=new ArrayList<>(old.structures());parts.addAll(signals.get(i));
        pieces.set(i,new Piece(old.mesh(),old.paint(),List.copyOf(parts),i));
      }
    }
    for(int i=0;i<approaches.size();i++){var old=pieces.get(i);var parts=new ArrayList<>(old.structures());parts.addAll(geometryVersion>=29?RoadSidewalks.parts(old.mesh(),s.arms().get(i).external().options().sidewalk(),null,old.structures()):RoadSidewalks.parts(old.mesh(),s.arms().get(i).external().options().sidewalk()));pieces.set(i,new Piece(old.mesh(),old.paint(),List.copyOf(parts),old.arm()));}
    if(pieces.size()>approaches.size()){int i=approaches.size();var old=pieces.get(i);var parts=new ArrayList<>(old.structures());parts.addAll(geometryVersion>=36?RoadSidewalks.taperedJunctionParts(s,approaches,boundary):RoadSidewalks.junctionParts(s,approaches,boundary,geometryVersion>=32,geometryVersion>=33,geometryVersion>=34));pieces.set(i,new Piece(old.mesh(),old.paint(),List.copyOf(parts),old.arm()));}
    if(geometryVersion>=34){var trims=TactilePaths.trims(s,approaches,boundary);
      for(int i=0;i<approaches.size();i++){var old=pieces.get(i);pieces.set(i,new Piece(old.mesh(),old.paint(),TactilePaths.trimApproach(old.structures(),old.mesh(),i,trims),old.arm()));}}
    if(geometryVersion>=41){var surface=new TactileSurface(pieces.stream().flatMap(p->p.structures().stream()).toList());
      for(int i=0;i<pieces.size();i++){var p=pieces.get(i);pieces.set(i,new Piece(p.mesh(),p.paint(),surface.gradePaving(p.structures()),p.arm()));}}
    if(geometryVersion>=32){var sidewalks=new SidewalkJoins(pieces.stream().map(Piece::mesh).toList(),List.of());
    for(int i=0;i<pieces.size();i++){var old=pieces.get(i);pieces.set(i,new Piece(old.mesh(),old.paint(),sidewalks.clip(old.structures()),old.arm()));}}
    if(geometryVersion>=40){var surface=new TactileSurface(pieces.stream().flatMap(p->p.structures().stream()).toList());
      for(int i=0;i<pieces.size();i++){var p=pieces.get(i);pieces.set(i,new Piece(p.mesh(),p.paint(),surface.conform(p.structures()),p.arm()));}}
    if(geometryVersion>=24)for(int i=0;i<pieces.size();i++){var p=pieces.get(i);var parts=new ArrayList<>(p.structures());RoadStructures.terminalPosts(parts);pieces.set(i,new Piece(p.mesh(),p.paint(),List.copyOf(parts),p.arm()));}
    var result=new Plan(List.copyOf(pieces),List.copyOf(movement),List.copyOf(boundary),RoadSidewalks.junctionCells(s,approaches,boundary),new JunctionSignalCycle(s,pedestrianArms),heads);
    result=geometryVersion>=49?JunctionSlips.apply(s,result,geometryVersion>=50,geometryVersion>=52,geometryVersion>=53,geometryVersion>=54):geometryVersion==48?JunctionSlips48.apply(s,result):result;
    return lateSignals?JunctionSignals.afterSlips(s,result,approaches):result;
  }
  private static Arm bridgeArm(Arm a){
    if(a.external().structure()!=Structure.BRIDGE||a.incoming()==0||a.outgoing()==0)return a;
    return new Arm(a.endpoint(),a.inward(),a.external(),a.incoming(),a.outgoing(),a.width(),1,RoadProfile.Median.RAIL,a.cycleWidth(),a.curbWidth(),a.crosswalk(),a.crossingWidth(),a.crossingSetback(),a.stopGap(),a.phase(),a.lanes(),a.attached(),a.advanceSign(),a.nameSign());
  }
  private static V boundaryInset(JunctionSpec spec,List<Mesh> approaches,List<V> boundary,int index,boolean smooth){
    V p=boundary.get(index);double best=Double.POSITIVE_INFINITY,width=0,total=0,weighted=0;
    for(int i=0;i<approaches.size();i++) {
      Sample mouth=approaches.get(i).last();double curb=spec.arms().get(i).curbWidth()>0?spec.arms().get(i).curbWidth()+.5:0;
      for(int side:new int[]{-1,1}) {
        double distance=p.distance(mouth.at(side*mouth.halfWidth(),0));
        if(distance<1e-5)return mouth.left().mul(-side*curb);
        double weight=1/(distance*distance);total+=weight;weighted+=curb*weight;
        if(distance<best){best=distance;width=curb;}
      }
    }
    V prev=boundary.get(Math.floorMod(index-1,boundary.size())),next=boundary.get((index+1)%boundary.size());
    V a=p.sub(prev).horizontalUnit(),b=next.sub(p).horizontalUnit();
    V normal=a.left().add(b.left()).mul(1/Math.max(.1,1+a.dot(b)));
    if(normal.dot(spec.center().sub(p))<0)normal=normal.mul(-1);
    return normal.mul(smooth&&total>0?weighted/total:width);
  }
  private static double seamBlend(Mesh mesh,Sample p) {
    return Settings.smooth(Math.min(1,2*p.distance()/Math.max(.001,mesh.length())));
  }
  private static double seamCurb(Arm arm,Mesh mesh,Sample p,int side,int version) {
    var port=arm.external().options().ends().port();
    double full=arm.curbWidth()>0?arm.curbWidth()+.5:0;
    if(version<27||port==null)return p.halfWidth()-(arm.width()/2-arm.curbWidth()-arm.cycleWidth()-.5)-arm.cycleWidth();
    double start=side>0?port.curbLeft():port.curbRight();
    return start+(full-start)*seamBlend(mesh,p);
  }
  private static double seamDivider(Arm arm,Mesh mesh,Sample p,double target,int version) {
    var port=arm.external().options().ends().port();
    if(version<27||port==null||port.dividers().isEmpty())return target;
    double start=port.dividers().stream().min(Comparator.comparingDouble(d->Math.abs(d-target))).orElse(target);
    return start+(target-start)*seamBlend(mesh,p);
  }
  /** Use the exact outside port, then blend to the requested intersection section. */
  public static double approachMotor(Arm arm,Mesh mesh,Sample p,int side){
    double target=side*(arm.width()/2-arm.curbWidth()-arm.cycleWidth()-.5);
    var port=arm.external().options().ends().port();
    if(!arm.attached()||port==null)return target;
    double start=side>0?port.motorMax():port.motorMin();return start+(target-start)*seamBlend(mesh,p);
  }
  public static double approachMedian(Arm arm,Mesh mesh,Sample p,boolean center,boolean left){
    double target=center?arm.divider(left):arm.median();var port=arm.external().options().ends().port();
    if(!arm.attached()||port==null)return target;
    double start=center?port.medianCenter():port.median();return start+(target-start)*seamBlend(mesh,p);
  }
  public static double approachEdge(Arm arm,Mesh mesh,Sample p,int side){
    // Ordinary roads mark the driving edge; bicycle verges mark 0.16 m inside the curb.
    double motor=approachMotor(arm,mesh,p,side);
    return arm.cycleWidth()>.01?side*(p.halfWidth()-Math.max(.3,seamCurb(arm,mesh,p,side,42)+.16)):motor;
  }
  private static double cycleFade(Arm arm,double d,double end){
    double fade=Math.min(6,end/2);double from=arm.attached()?fade:d;
    return Settings.smooth(Math.min(1,Math.min(from,end-d)/Math.max(.001,fade)));
  }
  private static Piece approach(JunctionSpec s,int i,Mesh mesh,List<Mesh> approaches,int version,boolean singleArrowRow) {
    Arm arm=s.arms().get(i);var paint=new ArrayList<Face>();var parts=new ArrayList<Part>();
    boolean raised=version>=38&&arm.external().structure()==Structure.BRIDGE;
    double ringOuter=s.islandRadius()+s.ringLanes()*s.ringLaneWidth();
    double ringOverlap=s.kind()==Kind.ROUNDABOUT?ringOuter-mesh.last().center().sub(s.center()).horizontalLength():0;
    double crossing=mesh.length()-ringOverlap-arm.crossingSetback()-arm.crossingWidth()/2;
    double stop=arm.crosswalk()?crossing-arm.crossingWidth()/2-arm.stopGap():mesh.length()-ringOverlap-arm.crossingSetback();
    if(version>=24&&stop<5)throw new IllegalArgumentException("接入口 "+(i+1)+" 的停止线退距过大；请延长接路或减小斑马线/停止线退距");
    double medianEnd=arm.crosswalk()?crossing-arm.crossingWidth()/2-.5:mesh.length()-ringOverlap-1;
    double sign=s.leftTraffic()?-1:1, divider=arm.divider(s.leftTraffic());
    for(int k=1;k<mesh.samples().size();k++) {
      Sample a=mesh.samples().get(k-1),b=mesh.samples().get(k);
      for(int side:new int[]{-1,1}) {
        V ea=a.at(version>=42?approachEdge(arm,mesh,a,side):side*(a.halfWidth()-.3),-.012),eb=b.at(version>=42?approachEdge(arm,mesh,b,side):side*(b.halfWidth()-.3),-.012);
        if(s.kind()!=Kind.ROUNDABOUT || ea.sub(s.center()).horizontalLength()>ringOuter+.2 && eb.sub(s.center()).horizontalLength()>ringOuter+.2){
          if(version>=54&&arm.crosswalk()){
            double low=crossing-arm.crossingWidth()/2-.12,high=crossing+arm.crossingWidth()/2+.12;
            for(double[] span:List.of(new double[]{a.distance(),Math.min(b.distance(),low)},new double[]{Math.max(a.distance(),high),b.distance()}))
              if(span[1]>span[0]+1e-8){var x=RoadSlicing.at(mesh,span[0]);var y=RoadSlicing.at(mesh,span[1]);JunctionPaint.line(paint,x.at(approachEdge(arm,mesh,x,side),-.012),y.at(approachEdge(arm,mesh,y,side),-.012),.15,JunctionPaint.WHITE);}
          }else JunctionPaint.line(paint,ea,eb,.15,JunctionPaint.WHITE);
        }
        if((arm.curbWidth()>0 || version>=27&&arm.external().options().ends().port()!=null
            &&(arm.external().options().ends().port().curbLeft()>0||arm.external().options().ends().port().curbRight()>0))
            && a.distance()<mesh.length()-ringOverlap-.2) {
          double ca=seamCurb(arm,mesh,a,side,version),cb=seamCurb(arm,mesh,b,side,version);
          if(Math.max(ca,cb)>.001) {
            Part curb=new Part(a.at(side*(a.halfWidth()-ca/2),0),b.at(side*(b.halfWidth()-cb/2),0),Math.max(.001,Math.max(ca,cb)),.2,false,Material.CONCRETE);
            if(version>=27)curb=curb.frames(a.left().mul(ca/2),b.left().mul(cb/2));
            parts.add(curb);
          }
        }
        if(arm.cycleWidth()>0 && a.distance()<stop-.225) {
          double offset=side*(arm.width()/2-arm.curbWidth()-arm.cycleWidth()-.5);
          Sample end=RoadStructures.sample(mesh,Math.min(b.distance(),stop-.225));
          JunctionPaint.line(paint,a.at(version>=42?approachMotor(arm,mesh,a,side):offset,-.014),end.at(version>=42?approachMotor(arm,mesh,end,side):offset,-.014),.13,JunctionPaint.WHITE);
        }
        if(a.distance()<medianEnd) {
          Sample end=RoadStructures.sample(mesh,Math.min(b.distance(),medianEnd));
          if(arm.cycleWidth()>0) {
            double inner=version>=42?approachMotor(arm,mesh,a,side):side*(arm.width()/2-arm.curbWidth()-arm.cycleWidth()-.5);
            double innerEnd=version>=42?approachMotor(arm,mesh,end,side):inner;
            double outer=inner+side*arm.cycleWidth();
            double separation=RoadStreetscape.separatorWidth(arm.external().options());
            if(!arm.external().options().cycleAsphalt())paint.add(new Face(List.of(a.at(inner+side*(separation+.08),-.009),a.at(outer-side*.08,-.009),end.at(innerEnd+side*arm.cycleWidth()-side*.08,-.009),end.at(innerEnd+side*(separation+.08),-.009)),0x536F61));
            if(version<47&&version>=42&&separation>0&&!raised){
              double fa=cycleFade(arm,a.distance(),medianEnd),fb=cycleFade(arm,end.distance(),medianEnd);
              RoadStructures.cyclePlanter(parts,a,end,side,inner,innerEnd,separation,fa,fb);
            }else if(version<47&&separation>0){V first=a.at(inner+side*separation/2,0),last=end.at(inner+side*separation/2,0);
              if(raised)RoadStructures.barrier(parts,first,last,false,true,a.distance());
              else {
                parts.add(new Part(first,last,separation,.3,false,Material.CONCRETE).frames(a.left().mul(separation/2),end.left().mul(separation/2)));
                parts.add(new Part(first.add(new V(0,.3,0)),last.add(new V(0,.3,0)),separation-.3,.45,false,Material.GREEN).frames(a.left().mul((separation-.3)/2),end.left().mul((separation-.3)/2)));}}
            if(arm.external().options().streetscape().parking()&&arm.external().structure()!=Structure.BRIDGE&&a.distance()<stop-3){
              double station=Math.ceil((a.distance()+arm.external().options().ends().paintPhase())/RoadStreetscape.parkingLength())*RoadStreetscape.parkingLength()-arm.external().options().ends().paintPhase();if(station>=a.distance()-1e-7&&station<end.distance()-1e-7){var p=RoadStructures.sample(mesh,station);JunctionPaint.line(paint,p.at(inner,-.012),p.at(side*(p.halfWidth()-.3),-.012),.12,JunctionPaint.WHITE);}}
            if(version<47&&arm.external().options().cycleRail())RoadStructures.barrier(parts,a.at(inner,0),end.at(innerEnd,0),false,false,a.distance());
          }
          if(version<44&&arm.external().options().outerRail()==RoadProfile.OuterRail.ON)
            RoadStructures.barrier(parts,a.at(side*(a.halfWidth()-.16),0),end.at(side*(end.halfWidth()-.16),0),false,arm.external().structure()==Structure.BRIDGE,a.distance());
        }
        if(version>=44&&version<47&&(s.outerRail()||arm.external().options().outerRail()==RoadProfile.OuterRail.ON)) {
          // Outside edges continue beyond the median/stop-line termination.
          // Only the pedestrian crossing itself needs an opening in this rail.
          double lo=arm.crosswalk()?crossing-arm.crossingWidth()/2:Double.POSITIVE_INFINITY;
          double hi=arm.crosswalk()?crossing+arm.crossingWidth()/2:Double.POSITIVE_INFINITY;
          for(double[] span:List.of(new double[]{a.distance(),Math.min(b.distance(),lo)},new double[]{Math.max(a.distance(),hi),b.distance()}))
            if(span[1]>span[0]+1e-7){var x=RoadStructures.sample(mesh,span[0]);var y=RoadStructures.sample(mesh,span[1]);
              RoadStructures.barrier(parts,x.at(side*(x.halfWidth()-.16),0),y.at(side*(y.halfWidth()-.16),0),false,s.outerRail()||raised,span[0]);}
        }
      }
      if(version<47&&(arm.median()>.2||raised&&arm.incoming()>0&&arm.outgoing()>0)&&a.distance()<medianEnd) {
        Sample end=RoadStructures.sample(mesh,Math.min(b.distance(),medianEnd));
        double da=version>=42?approachMedian(arm,mesh,a,true,s.leftTraffic()):divider,db=version>=42?approachMedian(arm,mesh,end,true,s.leftTraffic()):divider;
        V aa=a.at(da,0),bb=end.at(db,0);
        if(aa.distance(bb)>.01) {
          if(version>=42&&arm.medianKind()==RoadProfile.Median.GREEN&&!raised){
            parts.addAll(RoadStructures.planting(aa,bb,a.left(),end.left(),approachMedian(arm,mesh,a,false,s.leftTraffic()),approachMedian(arm,mesh,end,false,s.leftTraffic()),1,0));
          }else if(arm.medianKind()==RoadProfile.Median.GREEN&&!raised) {
            parts.add(new Part(aa,bb,Math.max(.1,arm.median()-.3),.3,false,Material.SOIL));
            parts.add(new Part(aa.add(new V(0,.3,0)),bb.add(new V(0,.3,0)),Math.max(.1,arm.median()-.5),.55,false,Material.GREEN));
            parts.add(new Part(aa.add(new V(0,.82,0)),bb.add(new V(0,.82,0)),Math.max(.1,arm.median()-.85),.25,false,Material.GREEN));
            for(int side:new int[]{-1,1})parts.add(new Part(a.at(divider+side*(arm.median()/2-.05),0),RoadStructures.sample(mesh,Math.min(b.distance(),medianEnd)).at(divider+side*(arm.median()/2-.05),0),.2,.35,false,Material.CONCRETE));
          } else RoadStructures.barrier(parts,aa,bb,false,arm.external().structure()==Structure.BRIDGE,a.distance());
        }
      }
      if(a.distance()>=stop-.225)continue;
      b=RoadStructures.sample(mesh,Math.min(b.distance(),stop-.225));
      for(boolean in:new boolean[]{true,false}) {
        int count=in?arm.incoming():arm.outgoing();
        for(int lane=1;lane<count;lane++) {
          double offset=divider+(in?1:-1)*sign*(arm.median()/2+lane*arm.laneWidth());
          if(a.distance()>stop-20||((int)(a.distance()/3)&1)==0)JunctionPaint.line(paint,a.at(seamDivider(arm,mesh,a,offset,version),-.013),b.at(seamDivider(arm,mesh,b,offset,version),-.013),.15,JunctionPaint.WHITE);
        }
      }
      if(!raised&&arm.incoming()>0&&arm.outgoing()>0&&arm.median()<.2)for(double side:new double[]{-.14,.14})
        JunctionPaint.line(paint,a.at(divider+side,-.013),b.at(divider+side,-.013),.13,JunctionPaint.YELLOW);

    }
    if(version<47&&!raised&&arm.median()>.2&&arm.medianKind()==RoadProfile.Median.GREEN) {
      Sample cap=RoadStructures.sample(mesh,medianEnd);
      parts.add(new Part(cap.at(divider-arm.median()/2+.05,0),cap.at(divider+arm.median()/2-.05,0),.2,.35,false,Material.CONCRETE));
    }
    if(arm.crosswalk()) {
      Sample at=RoadStructures.sample(mesh,crossing);V f=at.left().left().mul(-1);
      double min=Math.min(arm.motorEdge(true,s.leftTraffic()),arm.motorEdge(false,s.leftTraffic())),max=Math.max(arm.motorEdge(true,s.leftTraffic()),arm.motorEdge(false,s.leftTraffic()));
      if(version>=41&&arm.cycleWidth()>0){min=-at.halfWidth()+.25;max=at.halfWidth()-.25;}
      for(double lateral=min+.35;lateral<max-.25;lateral+=1.4)JunctionPaint.line(paint,at.at(lateral,-.016).sub(f.mul(arm.crossingWidth()/2)),at.at(lateral,-.016).add(f.mul(arm.crossingWidth()/2)),.7,JunctionPaint.WHITE);
    }
    Sample at=RoadStructures.sample(mesh,stop);
    if(arm.incoming()>0) {
      if(s.control()==Control.YIELD) {
        double inner=divider+sign*arm.median()/2,outer=arm.motorEdge(true,s.leftTraffic());
        V backwards=at.left().left();
        for(double offset=Math.min(inner,outer)+.65;offset<Math.max(inner,outer)-.5;offset+=1.5)
          paint.add(new Face(List.of(at.at(offset-.42,-.017),at.at(offset+.42,-.017),at.at(offset,-.017).add(backwards.mul(1.2))),JunctionPaint.WHITE));
      } else JunctionPaint.line(paint,at.at(divider+sign*arm.median()/2,-.017),at.at(arm.motorEdge(true,s.leftTraffic()),-.017),.45,JunctionPaint.WHITE);
      if(version<24) {
        for(int lane=0;lane<arm.incoming();lane++)for(double back:new double[]{Math.min(7,Math.max(2,stop-3)),19})if(stop>=back+2.5) {
          Sample arrow=RoadStructures.sample(mesh,stop-back);
          JunctionPaint.arrow(paint,arrow.at(arm.laneCenter(true,lane,s.leftTraffic()),-.02),arrow.left().left().mul(-1),mask(s,i,lane),Math.min(1,arm.laneWidth()/3.6));
        }
      } else for(int lane=0;lane<arm.incoming();lane++) {
        // The complete arrow must fit before the stop bar and inside the actual tapered lane.
        double scale=Math.min(1,(arm.laneWidth()-.5)/3.8);
        double front=2.65*scale+.6,back=Math.max(front,Math.min(7,stop-2.1*scale-.4));
        for(double setback:(singleArrowRow||version>=49&&JunctionSlips.enabled(s)||version>=45&&s.arms().size()>4)?new double[]{back}:new double[]{back,19}) {
          double station=stop-setback;
          if(station<2.1*scale+.35||setback<front)continue;
          Sample sample=RoadStructures.sample(mesh,station);
          var arrow=new ArrayList<Face>();
          JunctionPaint.arrow(arrow,sample.at(arm.laneCenter(true,lane,s.leftTraffic()),-.02),sample.left().left().mul(-1),mask(s,i,lane),scale);
          boolean fits=arrow.stream().flatMap(f->f.points().stream()).allMatch(p->{
            var q=RoadQueries.horizontal(mesh,p);double w=q.sample().halfWidth();
            return q.sample().distance()<=stop-.35&&Math.abs(q.lateral())<w-arm.curbWidth()-arm.cycleWidth()-.2;
          });
          if(fits)paint.addAll(arrow);
        }
      }
    }
    return new Piece(mesh,List.copyOf(paint),List.copyOf(parts),i);
  }
  private static Mesh triangle(List<V> p,double thickness) {
    V a=p.get(0),b=p.get(1),c=p.get(2),mid=b.add(c).mul(.5),side=c.sub(b).horizontalUnit();
    double length=a.distance(mid),half=b.distance(c)/2;
    V min=new V(p.stream().mapToDouble(V::x).min().orElseThrow(),a.y()-thickness,p.stream().mapToDouble(V::z).min().orElseThrow());
    V max=new V(p.stream().mapToDouble(V::x).max().orElseThrow(),a.y(),p.stream().mapToDouble(V::z).max().orElseThrow());
    return new Mesh(List.of(new Sample(a,side,0,0),new Sample(mid,side,length,half)),settings(Math.min(64,Math.max(2,half*2)),thickness),min,max,length,false);
  }
  private JunctionPlanner() {}
}
