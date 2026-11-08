package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadProfile.*;
import java.util.*;
/** A two-way stem separates into an outbound A->B and inbound C->A carriageway. */
public final class YJunctionPlanner {
  public record Plan(Mesh stem,Mesh outbound,Mesh inbound,Node throat){}
  public static Plan plan(Node a,Node b,Node c,Settings main,Settings out,Settings in,double tension){
    var profile=RoadProfile.catalog(main);
    if(!profile.twoWay()||profile.type()==Type.RAMP||profile.type()==Type.LEGACY)throw new IllegalArgumentException("A 必须为普通或高速双向道路");
    for(var s:List.of(out,in))if(RoadProfile.catalog(s).twoWay()||!RoadProfile.modern(s.style()))throw new IllegalArgumentException("B、C 须为单向道路");
    if(!Double.isFinite(tension)||tension<.2||tension>.8)throw new IllegalArgumentException("曲线强度须为 0.2–0.8");
    V forward=a.direction();double distance=Math.min(a.position().sub(b.position()).horizontalLength(),a.position().sub(c.position()).horizontalLength());
    if(distance<24)throw new IllegalArgumentException("A 至 B/C 至少 24 格，以留出分流过渡空间");
    double lead=Math.min(12,distance*.18);Node throat=new Node(a.position().add(forward.mul(lead)).add(new V(0,a.grade()*lead,0)),a.yaw(),a.grade());
    Settings mainFlat=flat(main,tension);Mesh stem=RoadGeometry.build(a,throat,mainFlat);
    var layout=RoadProfile.layout(stem,stem.last());
    int side=RoadProfile.trafficSign(main.options().leftTraffic());
    var outgoing=half(main,layout,side,false);var incoming=half(main,layout,-side,true);
    double outCenter=carriagewayCenter(stem.last().halfWidth(),layout,side);
    double inCenter=carriagewayCenter(stem.last().halfWidth(),layout,-side);
    V right=forward.left();
    Node first=new Node(throat.position().add(right.mul(outCenter)),a.yaw(),a.grade());
    Node last=new Node(throat.position().add(right.mul(inCenter)),a.yaw()+180,-a.grade());
    Settings outSettings=branch(out,main,tension,outgoing,true);
    Settings inSettings=branch(in,main,tension,incoming,false);
    Mesh outbound=RoadGeometry.build(first,b,outSettings),inbound=RoadGeometry.build(c,last,inSettings);
    // The two carriageways must not cross. Sharing their outer boundary at the throat is valid.
    for(var s:outbound.samples()){
      var q=RoadQueries.horizontal(inbound,s.center());
      if(s.center().sub(q.sample().center()).horizontalLength()<s.halfWidth()+q.sample().halfWidth()-.15&&Math.abs(s.center().y()-q.sample().center().y())<3.5)
        throw new IllegalArgumentException("B/C 方位导致两股车流交叉，请交换 B、C 或调整端点朝向");
    }
    // Keep the 3-on / 3-off stripe rhythm in the stem frame, including C -> A.
    outbound=phase(outbound,stem.length());
    inbound=phase(inbound,3-stem.length()-inbound.length());
    return new Plan(stem,outbound,inbound,throat);
  }
  private static Mesh phase(Mesh mesh,double phase){var s=mesh.settings();return RoadRibbon.mesh(mesh.samples(),s.options(s.options().ends(s.options().ends().paintPhase(phase))));}
  private static Settings flat(Settings s,double t){return new Settings(Mode.CURVE,s.style(),s.width(),s.thickness(),t,90,s.width(),s.width(),s.structure(),s.taperVersion(),s.rampTurn(),s.options().ends(RoadTransitions.Ends.NONE.port(s.options().ends().port())).infrastructure(s.options().infrastructure().clearEdits()).laneLines(List.of()));}
  private static double carriagewayCenter(double halfWidth,Layout layout,int side){
    double lo=side<0?-halfWidth:layout.medianEdge(1);
    double hi=side<0?layout.medianEdge(-1):halfWidth;
    return (lo+hi)/2;
  }
  private static RoadTransitions.Section half(Settings main,Layout layout,int side,boolean reverse){
    double halfWidth=main.width()/2,center=carriagewayCenter(halfWidth,layout,side);
    double lo=side<0?-halfWidth:layout.medianEdge(1),hi=side<0?layout.medianEdge(-1):halfWidth;
    double motor0=side<0?layout.motorMin():layout.medianEdge(1),motor1=side<0?layout.medianEdge(-1):layout.motorMax();
    double flip=reverse?-1:1;
    var lines=layout.dividers().stream().filter(d->d>motor0+1e-6&&d<motor1-1e-6).map(d->flip*(d-center)).sorted().toList();
    double m0=flip*(motor0-center),m1=flip*(motor1-center);
    int trafficSide=RoadProfile.trafficSign(main.options().leftTraffic());
    int count=side==trafficSide?RoadLanes.counts(main).forward():RoadLanes.counts(main).reverse();
    var counts=new RoadLanes.Counts(count,0);
    var style=RoadLanes.carrier(layout.catalog().type(),counts,Median.NONE);
    int outside=side*(reverse?-1:1);
    var walk=main.options().sidewalk().side(outside<0?RoadSidewalks.Side.LEFT:RoadSidewalks.Side.RIGHT);
    var port=new RoadTransitions.Port(Math.min(m0,m1),Math.max(m0,m1),0,lines,outside<0?layout.curbWidth():0,outside>0?layout.curbWidth():0);
    var o=main.options();
    return new RoadTransitions.Section(style,hi-lo,o.cycle(),o.cycleRail(),o.curb(),o.outerRail(),walk,o.cycleAsphalt(),port,o.streetscape(),counts);
  }
  private static Settings branch(Settings target,Settings main,double tension,RoadTransitions.Section half,boolean outgoing){
    var s=flat(target,tension).options(flat(target,tension).options().traffic(main.options().leftTraffic()));
    var full=RoadTransitions.Section.of(s);
    var base=outgoing?full:half;
    var options=s.options().lanes(base.lanes()).ends(new RoadTransitions.Ends(outgoing?half:full,outgoing?full:half,true).port(base.port()));
    return new Settings(Mode.CURVE,base.style(),base.width(),s.thickness(),tension,90,outgoing?half.width():s.width(),outgoing?s.width():half.width(),s.structure(),s.taperVersion(),s.rampTurn(),options);
  }
  public static double yaw(V direction){return Math.toDegrees(Math.atan2(-direction.x(),direction.z()));}
  private YJunctionPlanner(){}
}
