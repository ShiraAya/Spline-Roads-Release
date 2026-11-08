package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** Conform complete layouts to their authored host paths, then validate the resulting roads. */
public final class CurvedRoadPlans {
  public static List<Preset> presets(int count,boolean curved){
    return count==4&&curved?List.of(Preset.CLOVERLEAF,Preset.HYBRID,Preset.STACK):InterchangePlanner.presets(count);
  }
  public static Options planningOptions(Options o){
    // Fixed allowance, rather than retry-dependent layers, keeps an unchanged save/reopen stable.
    return new Options(o.preset(),o.leftTraffic(),o.lanes(),o.transition(),o.radius(),Math.min(12,o.clearance()+.5),o.upper(),o.rampWidth(),o.adjustEndpoints(),o.allowShrink(),o.maxLowering(),o.junctionAxis(),o.loopMask(),o.allowAbove(),o.flatConnector(),o.connectorHeight(),o.toll());
  }
  public static Plan interchange(Node[] nodes,Settings first,Settings second,Options options,List<RoadAxis> axes){
    if(axes.stream().noneMatch(RoadAxis::curved))return InterchangePlanner.plan(nodes,first,second,options);
    if(!presets(nodes.length,true).contains(options.preset()))throw new IllegalArgumentException("曲线四向立交仅支持苜蓿、苜蓿＋定向、堆栈");
    if(axes.size()!=2||axes.stream().anyMatch(a->!a.flat()))throw new IllegalArgumentException("曲线立交的每条主路需各自等高");
    RoadAxis a=axes.get(0),b=axes.get(1);var cross=RoadAxis.crossing(a,b,nodes.length==3);
    V center=cross.point(),u=a.tangent(cross.first()),actualV=b.tangent(cross.second());
    V v=u.left().mul(Math.signum(RoadAxis.cross(u,actualV)));
    double det=RoadAxis.cross(u,v);if(Math.abs(RoadAxis.cross(u,actualV))<.9)throw new IllegalArgumentException("曲线主路在交点的夹角需为 65°–115°");
    Node[] flat=nodes.clone();
    for(int i=0;i<nodes.length;i++){
      int axis=i/2;double station=(i%2==0?0:axes.get(axis).length())-(axis==0?cross.first():cross.second());
      V p=center.add((axis==0?u:v).mul(station));flat[i]=new Node(new V(p.x(),nodes[i].position().y(),p.z()),nodes[i].yaw(),0);
    }
    UnaryOperator<V> mapping=p->{
      V d=p.sub(center);double x=RoadAxis.cross(d,v)/det,z=RoadAxis.cross(u,d)/det;
      V pa=a.at(cross.first()+x),pb=b.at(cross.second()+z);
      V ta=a.tangent(cross.first()+x),tb=b.tangent(cross.second()+z);
      V fa=pa.add(rotate(v,u,ta).mul(z)),fb=pb.add(rotate(u,v,tb).mul(x));
      double xx=x*x,zz=z*z,w=xx+zz<1e-10?.5:xx*xx/(xx*xx+zz*zz);
      V out=fa.mul(w).add(fb.mul(1-w));return new V(out.x(),p.y(),out.z());
    };
    try{
        var fitOptions=planningOptions(options);
        var planned=InterchangePlanner.plan(flat,first,second,fitOptions,mapping);
        var legs=planned.legs();
        validate(legs,options);
        var anchors=new ArrayList<Node>();
        for(int i=0;i<nodes.length;i++){
          Node fitted=planned.anchors().get(i);V p=mapping.apply(fitted.position());
          anchors.add(p.distance(nodes[i].position())<1e-5?nodes[i]:new Node(p,YJunctionPlanner.yaw(axes.get(i/2).tangent(axes.get(i/2).project(p))),0));
        }
        return finish(planned,legs,anchors,mapping.apply(planned.center()));
    }catch(IllegalArgumentException error){
      // InterchangeFit has already exhausted the bounded search using mapped geometry.
      throw new IllegalArgumentException("曲线立交无法满足半径、纵坡或净空："+error.getMessage());
    }
  }
  private static V rotate(V vector,V old,V next){double cos=old.dot(next),sin=RoadAxis.cross(old,next);return new V(vector.x()*cos-vector.z()*sin,0,vector.x()*sin+vector.z()*cos);}
  public static boolean fixedFrontageHeight(Node[] nodes,CorridorPlanner.Config config,RoadAxis axis){
    return config.kind()==CorridorPlanner.Kind.FRONTAGE&&(Math.abs(nodes[0].position().y()-nodes[1].position().y())>.001||!axis.flat());
  }
  public static Plan corridor(Node[] nodes,Settings main,Settings secondary,Options options,CorridorPlanner.Config config,List<RoadAxis> axes){
    RoadAxis axis=axes.get(0);boolean fixedY=fixedFrontageHeight(nodes,config,axis);
    if(config.kind()!=CorridorPlanner.Kind.FRONTAGE){
      if(axes.stream().anyMatch(a->a.curved()||!a.flat()))throw new IllegalArgumentException("双层同侧出入口仍需要平直且各自等高的主路");
      return CorridorPlanner.plan(nodes,main,secondary,options,config);
    }
    if(!axis.curved()&&!fixedY)return CorridorPlanner.plan(nodes,main,secondary,options,config);
    double y=nodes[0].position().y();
    if(fixedY){config=new CorridorPlanner.Config(config.kind(),config.sides(),CorridorPlanner.Access.NONE,config.gap(),y,config.adjustment());options=options.adjust(false);}
    Node[] flat={new Node(new V(0,y,0),-90,0),new Node(new V(axis.length(),y,0),-90,0)};
    Plan result=CorridorPlanner.plan(flat,main,secondary,options,config);
    UnaryOperator<V> mapping=p->{V c=axis.at(p.x()),out=c.add(axis.tangent(p.x()).left().mul(p.z()));return new V(out.x(),p.y(),out.z());};
    var legs=new ArrayList<Leg>();
    for(int i=0;i<result.legs().size();i++){
      var leg=result.legs().get(i);final boolean preserveGrade=fixedY&&i==0;
      Mesh mesh=map(leg.mesh(),p->{V q=mapping.apply(p);return preserveGrade?new V(q.x(),axis.at(p.x()).y(),q.z()):q;});
      legs.add(new Leg(leg.name(),leg.from(),leg.to(),mesh));
    }
    validate(legs,options);
    var anchors=new ArrayList<Node>();
    for(int i=0;i<2;i++){
      V p=legs.get(0).mesh().samples().get(i==0?0:legs.get(0).mesh().samples().size()-1).center();
      anchors.add(p.distance(nodes[i].position())<1e-5?nodes[i]:new Node(p,YJunctionPlanner.yaw(axis.tangent(result.anchors().get(i).position().x())),0));
    }
    return finish(result,legs,anchors,mapping.apply(result.center()));
  }
  private static Plan finish(Plan base,List<Leg> legs,List<Node> nodes,V center){
    double radius=legs.stream().filter(l->l.mesh().settings().style().ramp()).mapToDouble(l->RoadRibbon.minRadius(l.mesh())).min().orElse(Double.POSITIVE_INFINITY);
    return new Plan(List.copyOf(legs),base.movements(),radius,legs.stream().mapToDouble(l->l.mesh().max().y()).max().orElse(center.y()),center,List.copyOf(nodes));
  }
  static Mesh map(Mesh source,UnaryOperator<V> mapping){
    var points=source.samples().stream().map(s->mapping.apply(s.center())).toList();var samples=new ArrayList<Sample>();
    for(int i=0;i<points.size();i++){
      Sample before=source.samples().get(i);V tangent=before.left().left().mul(-1);
      V d=mapping.apply(before.center().add(tangent.mul(.05))).sub(mapping.apply(before.center().sub(tangent.mul(.05)))).horizontalUnit();
      samples.add(new Sample(points.get(i),d.left(),0,before.halfWidth()));
    }return RoadRibbon.mesh(samples,source.settings());
  }
  static void validate(List<Leg> legs,Options options){
    for(Leg leg:legs){
      RoadGrades.validate(leg.mesh());RoadRibbon.checkSelfIntersections(leg.mesh(),options.clearance());
      if(leg.mesh().settings().style().ramp()&&RoadRibbon.minRadius(leg.mesh())+.1<options.radius())throw new IllegalArgumentException(String.format(Locale.ROOT,"%s 实际转弯半径 %.1f 格，要求 %.1f 格",leg.name(),RoadRibbon.minRadius(leg.mesh()),options.radius()));
    }
    CorridorPlanner.verify(legs,options.clearance());
  }
  private CurvedRoadPlans(){}
}
