package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** Conform complete layouts to their authored host paths, then validate the resulting roads. */
public final class CurvedRoadPlans {
  public static List<Preset> presets(int count,boolean curved){
    return InterchangePlanner.presets(count);
  }
  public static Options planningOptions(Options o){
    // Fixed allowance, rather than retry-dependent layers, keeps an unchanged save/reopen stable.
    return new Options(o.preset(),o.leftTraffic(),o.lanes(),o.transition(),o.radius(),Math.min(12,o.clearance()+.5),o.upper(),o.rampWidth(),o.adjustEndpoints(),o.allowShrink(),o.maxLowering(),o.junctionAxis(),o.loopMask(),o.allowAbove(),o.flatConnector(),o.connectorHeight(),o.toll(),o.trumpetStyle());
  }
  public static Plan interchange(Node[] nodes,Settings first,Settings second,Options options,List<RoadAxis> axes){
    if(axes.stream().noneMatch(RoadAxis::curved))return InterchangePlanner.plan(nodes,first,second,options);
    if(!presets(nodes.length,true).contains(options.preset()))throw new IllegalArgumentException("所选样式暂不支持曲线主路；当前样式已保留，请调整主路或手动选择其他样式");
    if(options.preset()==Preset.DOUBLE_TRUMPET&&options.toll().enabled())throw new IllegalArgumentException("曲线双喇叭暂不支持内置收费站；收费设置和立交样式已保留");
    if(axes.size()!=2||axes.stream().anyMatch(a->!a.flat()))throw new IllegalArgumentException("曲线立交的每条主路需各自等高");
    RoadAxis a=axes.get(0),b=axes.get(1);var cross=RoadAxis.crossing(a,b,nodes.length==3);
    V center=cross.point(),u=a.tangent(cross.first()),actualV=b.tangent(cross.second());
    V v=u.left().mul(Math.signum(RoadAxis.cross(u,actualV)));
    double det=RoadAxis.cross(u,v);if(Math.abs(RoadAxis.cross(u,actualV))<Math.sin(Math.toRadians(65)))throw new IllegalArgumentException("曲线主路在交点的夹角需为 65°–115°");
    Node[] flat=nodes.clone();
    for(int i=0;i<nodes.length;i++){
      int axis=i/2;double station=(i%2==0?0:axes.get(axis).length())-(axis==0?cross.first():cross.second());
      V p=center.add((axis==0?u:v).mul(station));flat[i]=new Node(new V(p.x(),nodes[i].position().y(),p.z()),nodes[i].yaw(),0);
    }
    UnaryOperator<V> mapping=p->{
      V d=p.sub(center);double x=RoadAxis.cross(d,v)/det,z=RoadAxis.cross(u,d)/det;
      V pa=a.at(cross.first()+x),pb=b.at(cross.second()+z);
      V ta=a.tangent(cross.first()+x),tb=b.tangent(cross.second()+z);
      // Both host charts must have the same linear transform at their crossing.
      // Mixing an orthogonal offset with the real skew tangent introduced a
      // directional kink precisely where a trumpet stem crosses the mainline.
      V fa=pa.add(rotate(actualV,u,ta).mul(z)),fb=pb.add(rotate(u,actualV,tb).mul(x));
      double xx=x*x,zz=z*z,w=xx+zz<1e-10?.5:xx*xx/(xx*xx+zz*zz);
      V out=fa.mul(w).add(fb.mul(1-w));return new V(out.x(),p.y(),out.z());
    };
    try{
        var fitOptions=planningOptions(options);
        var planned=InterchangePlanner.plan(flat,first,second,fitOptions,mapping);
        var legs=pinHostFrames(planned.legs(),nodes);
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
  /** Preserve an unchanged host's exact cut plane. Numerical derivatives of the
   * conformance map can otherwise rotate the joining edge by a few microradians
   * and create a real, thin overlap with the retained neighbouring road. */
  private static List<Leg> pinHostFrames(List<Leg> source,Node[] nodes){
    var result=new ArrayList<Leg>();
    for(var leg:source){
      if(leg.junction()!=null){result.add(leg);continue;}
      var mesh=leg.mesh();var samples=new ArrayList<>(mesh.samples());boolean changed=false;
      for(int i:new int[]{0,samples.size()-1})for(var node:nodes){
        var sample=samples.get(i);if(sample.center().distance(node.position())>1e-7)continue;
        V left=node.direction().left();double dot=left.dot(sample.left());
        if(Math.abs(dot)<1-1e-8)continue;
        if(dot<0)left=left.mul(-1);
        samples.set(i,new Sample(sample.center(),left,sample.distance(),sample.halfWidth()));changed=true;break;
      }
      result.add(changed?new Leg(leg.name(),leg.from(),leg.to(),RoadRibbon.mesh(samples,mesh.settings()),null,leg.toll()):leg);
    }return List.copyOf(result);
  }
  /** Junction metadata is regenerated at mapped arm endpoints, including paint and signals. */
  static List<Leg> mapLegs(List<Leg> source,UnaryOperator<V> mapping){
    var out=new ArrayList<Leg>();var seen=new HashSet<JunctionSpec>();
    for(var leg:source){
      if(leg.junction()==null){out.add(new Leg(leg.name(),leg.from(),leg.to(),map(leg.mesh(),mapping),null,leg.toll()));continue;}
      var s=leg.junction().spec();if(!seen.add(s))continue;
      var arms=s.arms().stream().map(a->{V p=a.endpoint().position(),q=mapping.apply(p),d=mapping.apply(p.add(a.inward().mul(.05))).sub(mapping.apply(p.sub(a.inward().mul(.05)))).horizontalUnit();return a.node(new Node(q,RoadPlanner.yaw(d),a.endpoint().grade()),d,a.external());}).toList();
      var mapped=new JunctionSpec(mapping.apply(s.center()),s.kind(),s.leftTraffic(),s.cornerRadius(),s.islandRadius(),s.ringLanes(),s.ringLaneWidth(),s.thickness(),s.control(),s.greenSeconds(),s.yellowSeconds(),s.allRedSeconds(),s.timeOffset(),s.guides(),s.greenIsland(),s.outerRail(),arms,s.parallelApproaches());
      var plan=JunctionPlanner.plan(mapped);for(int i=0;i<plan.pieces().size();i++)out.add(new Leg(leg.name(),leg.from(),leg.to(),plan.pieces().get(i).mesh(),new JunctionPlanner.Ref(mapped,i)));
    }return out;
  }
  public static Plan multi(Node[] nodes,Settings[] settings,Options options,double base,List<RoadAxis> axes){
    if(axes.size()!=3||axes.stream().anyMatch(a->!a.flat()))throw new IllegalArgumentException("五、六向立交的三条主路需各自等高");
    if(axes.stream().noneMatch(RoadAxis::curved))return MultiInterchange.plan(nodes,settings,options,base);
    var crossing=RoadAxis.crossing(axes.get(0),axes.get(1),false);V center=crossing.point();
    double[] stations={crossing.first(),crossing.second(),axes.get(2).project(center)};V[] directions=new V[3];
    for(int i=0;i<3;i++){if(axes.get(i).at(stations[i]).sub(center).horizontalLength()>.25)throw new IllegalArgumentException("五、六向曲线主路须经过同一个中心");directions[i]=axes.get(i).tangent(stations[i]);}
    Node[] flat=nodes.clone();for(int i=0;i<nodes.length;i++){int axis=i/2;V p=center.add(directions[axis].mul((i%2==0?0:axes.get(axis).length())-stations[axis]));flat[i]=new Node(new V(p.x(),nodes[i].position().y(),p.z()),nodes[i].yaw(),0);}
    UnaryOperator<V> mapping=p->{V d=p.sub(center);double[] lateral=new double[3];for(int i=0;i<3;i++)lateral[i]=d.dot(directions[i].left());V sum=new V(0,0,0);double total=0;
      for(int i=0;i<3;i++){double weight=1;for(int j=0;j<3;j++)if(j!=i)weight*=Math.pow(lateral[j],4);double at=stations[i]+d.dot(directions[i]);V q=axes.get(i).at(at).add(axes.get(i).tangent(at).left().mul(lateral[i]));sum=sum.add(q.mul(weight));total+=weight;}
      V q=total<1e-20?center:sum.mul(1/total);return new V(q.x(),p.y(),q.z());};
    var result=MultiInterchange.plan(flat,settings,planningOptions(options),base,mapping);validate(result.legs(),options);
    var anchors=new ArrayList<Node>();for(int i=0;i<nodes.length;i++){V p=mapping.apply(result.anchors().get(i).position());anchors.add(p.distance(nodes[i].position())<1e-5?nodes[i]:new Node(p,RoadPlanner.yaw(axes.get(i/2).tangent(axes.get(i/2).project(p))),0));}
    return finish(result,result.legs(),anchors,mapping.apply(result.center()));
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
    }var result=RoadRibbon.mesh(samples,source.settings());return source.closed()?new Mesh(result.samples(),result.settings(),result.min(),result.max(),result.length(),true,null):result;
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
