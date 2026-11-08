package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import java.util.*;

/** Main/frontage and stacked parallel roads. Every movement stays on its own side. */
public final class CorridorPlanner {
  public enum Kind { FRONTAGE, LAYERED }
  public enum Sides {
    BOTH("两侧"), RIGHT("A→B 右侧"), LEFT("A→B 左侧");
    public final String label; Sides(String s){label=s;}
    boolean includes(int side){return this==BOTH || side==(this==RIGHT?1:-1);}
  }
  public enum Access {
    BOTH("汇出＋汇入"), EXIT("仅主路汇出"), ENTRY("仅汇入主路"), NONE("仅建道路");
    public final String label; Access(String s){label=s;}
  }
  public enum Adjustment {
    AUTO("自动避障"), BOTH("两端调整"), START("仅 A 侧"), END("仅 B 侧");
    public final String label; Adjustment(String value){label=value;}
  }
  public record Config(Kind kind, Sides sides, Access access, double gap, double frontageY,Adjustment adjustment) {
    public Config(Kind kind,Sides sides,Access access,double gap,double y){this(kind,sides,access,gap,y,Adjustment.BOTH);}
    public Config adjustment(Adjustment value){return new Config(kind,sides,access,gap,frontageY,value);}
    public Config {
      if(kind==null||sides==null||access==null||adjustment==null||!RoadGeometry.finite(gap,frontageY)
          ||gap<(kind==Kind.LAYERED?0:4)||gap>96||frontageY < -64||frontageY>320)
        throw new IllegalArgumentException("双层外绕间隔需为 0–96 格，主辅路间隔需为 4–96 格，辅路高度需在 -64–320 内");
    }
  }
  private record Port(double edge,int outward) {
    double initial(){return edge-outward*.25;}
    double attached(double width){return edge+outward*(width/2-1);}
  }
  private record Lead(double grow,double hold,double peel) {double length(){return grow+hold+peel;}}
  private static Lead lead(Port p,double clear,Options o){
    double grow=Math.max(o.transition()*.4,Math.sqrt(6*Math.abs(p.attached(o.width())-p.initial())*o.radius())+2);
    double peel=Math.max(o.transition()*.4,Math.sqrt(6*Math.abs(clear-p.attached(o.width()))*o.radius())+2);
    return new Lead(Math.max(12,grow),Math.max(8,o.width()*1.5),Math.max(12,peel));
  }
  private static double climb(double ya,double yb){return Math.abs(ya-yb)<.001?8:Math.max(24,2.05*Math.abs(ya-yb)/RoadGrades.MAX_RAMP_GRADE);}
  private static double span(Port a,Port b,double clear,double ya,double yb,Options o){return lead(a,clear,o).length()+climb(ya,yb)+lead(b,clear,o).length();}
  private static boolean direct(Kind kind,double ya,double yb){return kind==Kind.FRONTAGE&&Math.abs(ya-yb)<.001;}
  private static double transfer(Port a,Port b,Options o){return Math.max(o.transition(),Math.sqrt(6*Math.abs(b.attached(o.width())-a.attached(o.width()))*o.radius())+2);}
  private static double span(Kind kind,Port a,Port b,double clear,double ya,double yb,Options o){
    if(!direct(kind,ya,yb))return span(a,b,clear,ya,yb,o);
    Lead la=lead(a,clear,o),lb=lead(b,clear,o);return la.grow+la.hold+transfer(a,b,o)+lb.hold+lb.grow;
  }

  public static Plan plan(Node[] nodes, Settings main, Settings secondary, Options o, Config c) {
    RoadPlanningBudget.check();
    try { return fixed(nodes,main,secondary,o,c); }
    catch(IllegalArgumentException original) {
      if(!o.adjustEndpoints() || nodes.length!=(c.kind==Kind.FRONTAGE?2:4))throw original;
      Node[] fitted=fit(nodes,main,secondary,o,c);
      return fixed(fitted,main,secondary,o,c);
    }
  }

  /** Extend only as far as the selected movements need; keep AB's axis and the layer order. */
  private static Node[] fit(Node[] nodes,Settings main,Settings secondary,Options o,Config c){
    RoadPlanningBudget.check();
    V origin=nodes[0].position(),u=nodes[1].position().sub(origin).horizontalUnit();
    if(nodes[1].position().sub(origin).horizontalLength()<1)throw new IllegalArgumentException("A/B 水平距离至少 1 格");
    boolean startOnly=c.adjustment==Adjustment.START,endOnly=c.adjustment==Adjustment.END;
    double y=startOnly?nodes[1].position().y():endOnly?origin.y():(origin.y()+nodes[1].position().y())/2,otherY=c.frontageY,offset=0;
    double[] low=new double[nodes.length/2],high=new double[low.length];
    boolean reverse=false;
    for(int i=0;i<low.length;i++){
      double a=nodes[2*i].position().sub(origin).dot(u),b=nodes[2*i+1].position().sub(origin).dot(u);
      low[i]=Math.min(a,b);high[i]=Math.max(a,b);if(i==1)reverse=a>b;
    }
    if(c.kind==Kind.LAYERED){
      V mid=startOnly?nodes[reverse?2:3].position():endOnly?nodes[reverse?3:2].position():nodes[2].position().add(nodes[3].position()).mul(.5);
      double maxOffset=Math.max(main.width(),secondary.width())/2;
      offset=mid.sub(origin).dot(u.left());
      if(startOnly||endOnly){if(Math.abs(offset)>maxOffset)throw new IllegalArgumentException("固定端横向偏离过大，请允许两端调整");}
      else offset=Math.max(-maxOffset,Math.min(maxOffset,offset));
      otherY=mid.y();double required=o.clearance()+Math.max(main.thickness(),secondary.thickness());
      if(Math.abs(otherY-y)<required){
        if(startOnly||endOnly)throw new IllegalArgumentException("固定端的双层净高不足；仅单端调整不能改变该端高度，请改为两端调整");
        if(otherY>=y)otherY=y+required;else y=otherY+required;
      }
    }
    double required=64;
    if(c.access!=Access.NONE)for(int side:new int[]{1,-1})if(c.sides.includes(side)){
      Port primary=port(main,side,0),other;double clear;
      if(c.kind==Kind.FRONTAGE){
        other=new Port(side*(main.width()/2+c.gap),-side);clear=side*(main.width()/2+c.gap/2);
      }else{other=port(secondary,side,offset);clear=side*(Math.max(main.width()/2,secondary.width()/2+side*offset)+o.width()/2+c.gap+.15);}
      int count=c.access==Access.BOTH?2:1;
      required=Math.max(required,Math.ceil(span(c.kind,primary,other,clear,y,otherY,o)*count+24*(count+1))+2);
    }
    double lo=Arrays.stream(low).max().orElseThrow(),hi=Arrays.stream(high).min().orElseThrow(),center=(lo+hi)/2;
    double targetLo=endOnly?lo:Math.min(lo,(startOnly?hi: center+required/2)-required),targetHi=startOnly?hi:Math.max(hi,(endOnly?lo:center-required/2)+required);
    Node[] result=nodes.clone();
    for(int axis=0;axis<low.length;axis++){
      RoadPlanningBudget.check();
      double a=endOnly?low[axis]:Math.min(low[axis],targetLo),b=startOnly?high[axis]:Math.max(high[axis],targetHi);
      if(b-a>RoadLimits.MAX_ENDPOINT_DISTANCE)throw new IllegalArgumentException("自动调整后道路超过 2048 格；请减少出入口或缩小高差/过渡长度");
      boolean back=axis==1&&reverse;
      for(int end=0;end<2;end++){
        int i=axis*2+end;V p=point(origin,u,(end==0)^back?a:b,axis==0?0:offset,axis==0?y:otherY);
        result[i]=p.distance(nodes[i].position())<1e-8?nodes[i]:new Node(p,nodes[i].yaw(),0);
      }
    }
    return result;
  }

  private static Plan fixed(Node[] nodes, Settings main, Settings secondary, Options o, Config c) {
    RoadPlanningBudget.check();
    if(nodes.length!=(c.kind==Kind.FRONTAGE?2:4))
      throw new IllegalArgumentException(c.kind==Kind.FRONTAGE?"请选择主路 A/B 两端":"请选择第一层 A/B 和第二层 C/D 两端");
    main.validate();secondary.validate();
    if(!RoadProfile.modern(main.style())||!RoadProfile.catalog(main.style()).twoWay())
      throw new IllegalArgumentException("主路需要双向普通道路或高速");
    if(c.kind==Kind.FRONTAGE && (RoadProfile.catalog(secondary.style()).twoWay()
        ||RoadProfile.catalog(secondary.style()).type()!=RoadProfile.Type.ORDINARY))
      throw new IllegalArgumentException("辅路请选择单向 1／2／3／4 车道普通道路");
    if(c.kind==Kind.LAYERED && (!RoadProfile.modern(secondary.style())
        ||!RoadProfile.catalog(secondary.style()).twoWay()))
      throw new IllegalArgumentException("双层道路的第二层也需要双向普通道路或高速");
    V start=nodes[0].position(), delta=nodes[1].position().sub(start), u=delta.horizontalUnit();
    double length=delta.horizontalLength();
    if(length<64||length>RoadLimits.MAX_ENDPOINT_DISTANCE)
      throw new IllegalArgumentException("主路长度需为 64–2048 格");
    if(Math.abs(delta.y())>.001)throw new IllegalArgumentException("A/B 两端需要等高");
    double lo=0,hi=length,secondOffset=0,secondY=c.frontageY;
    if(c.kind==Kind.LAYERED){
      V cd=nodes[3].position().sub(nodes[2].position());
      if(cd.horizontalLength()<64||cd.horizontalLength()>RoadLimits.MAX_ENDPOINT_DISTANCE
          ||Math.abs(cd.y())>.001||Math.abs(cd.horizontalUnit().dot(u))<.9999)
        throw new IllegalArgumentException("双层出入口需要两条平直、各自等高且平行的道路");
      double a=nodes[2].position().sub(start).dot(u),b=nodes[3].position().sub(start).dot(u);
      lo=Math.max(lo,Math.min(a,b));hi=Math.min(hi,Math.max(a,b));
      secondOffset=nodes[2].position().sub(start).dot(u.left());secondY=nodes[2].position().y();
      if(Math.abs(secondOffset)>Math.max(main.width(),secondary.width())/2)
        throw new IllegalArgumentException("两层道路横向偏离过大；请选择上下重叠的平行道路");
      double required=o.clearance()+Math.max(main.thickness(),secondary.thickness());
      if(Math.abs(secondY-start.y())<required)
        throw new IllegalArgumentException(String.format(Locale.ROOT,"双层道路高差至少 %.1f 格（含路板厚度）",required));
    }
    main=main.options(main.options().traffic(o.leftTraffic()));
    secondary=secondary.options(secondary.options().traffic(o.leftTraffic()));
    var legs=new ArrayList<Leg>();
    legs.add(new Leg("主路 AB",0,1,straight(start,nodes[1].position(),main)));
    Mesh lower=null;
    if(c.kind==Kind.LAYERED){
      lower=straight(nodes[2].position(),nodes[3].position(),secondary);
      legs.add(new Leg("第二层 CD",2,3,lower));
    }
    int movements=0;double minRadius=Double.POSITIVE_INFINITY;
    for(int side:new int[]{1,-1})if(c.sides.includes(side)){
      RoadPlanningBudget.check();
      int direction=side*RoadProfile.trafficSign(o.leftTraffic());
      double auxiliary=side*(main.width()/2+c.gap+secondary.width()/2);
      if(c.kind==Kind.FRONTAGE){
        V a=point(start,u,0,auxiliary,secondY),b=point(start,u,length,auxiliary,secondY);
        legs.add(new Leg((side>0?"右":"左")+"侧辅路",-1,-1,
            straight(direction>0?a:b,direction>0?b:a,secondary)));
      }
      if(c.access==Access.NONE)continue;
      Port primary=port(main,side,0), other;
      double clear;
      if(c.kind==Kind.FRONTAGE){
        if(c.gap<o.width()+2)
          throw new IllegalArgumentException("主辅路净间隔至少为匝道宽度＋2 格");
        // A frontage road joins on its inner edge; its outside remains available for local streets.
        other=new Port(auxiliary-side*secondary.width()/2,-side);
        clear=side*(main.width()/2+c.gap/2);
      }else{
        other=port(secondary,side,secondOffset);
        clear=side*(Math.max(main.width()/2,secondary.width()/2+side*secondOffset)+o.width()/2+c.gap+.15);
      }
      double span=span(c.kind,primary,other,clear,start.y(),secondY,o);
      int count=c.access==Access.BOTH?2:1;
      double required=span*count+24*(count+1);
      if(hi-lo<required)
        throw new IllegalArgumentException(String.format(Locale.ROOT,
            "出入口重叠路段至少需 %.0f 格（当前 %.0f）；可选仅汇入/汇出或缩短过渡",Math.ceil(required),hi-lo));
      double margin=(hi-lo-span*count)/ (count+1);
      Mesh firstRamp=null;
      for(int index=0;index<count;index++){
        RoadPlanningBudget.check();
        boolean exit=c.access==Access.EXIT || c.access==Access.BOTH&&index==0;
        double origin=direction>0?lo+margin+index*(span+margin):hi-margin-index*(span+margin);
        Mesh ramp=ramp(c.kind,start,u,origin,direction,exit?primary:other,exit?other:primary,
            clear,exit?start.y():secondY,exit?secondY:start.y(),o);
        if(count==2){
          // Adjacent entry/exit lanes on the secondary road form one continuous
          // auxiliary road. Trim their redundant narrow tails and bridge at full width.
          double grow=lead(other,clear,o).grow;
          ramp=clip(ramp,u,direction,index==0?0:grow,index==0?span-grow:span);
          if(index==0)firstRamp=ramp;
          else legs.add(new Leg((side>0?"右":"左")+"侧共通段",side,side,straight(firstRamp.last().center(),ramp.first().center(),ramp.settings())));
        }
        RoadGrades.validate(ramp);
        double radius=RoadRibbon.minRadius(ramp);
        if(radius+.1<o.radius())throw new IllegalArgumentException("出入口弯道半径不足，请增加过渡长度");
        minRadius=Math.min(minRadius,radius);
        legs.add(new Leg((side>0?"右":"左")+"侧"+(exit?"主路汇出":"汇入主路"),side,side,ramp));
        movements++;
      }
    }
    verify(legs,o.clearance());
    return new Plan(List.copyOf(legs),movements,minRadius,Math.max(start.y(),secondY),
        point(start,u,length/2,0,start.y()),List.of(nodes));
  }

  private static Port port(Settings road,int side,double offset){
    // Both ordinary and highway connectors add pavement OUTSIDE the complete host deck.
    return new Port(offset+side*road.width()/2,side);
  }
  private static V point(V origin,V u,double d,double lateral,double y){
    V p=origin.add(u.mul(d)).add(u.left().mul(lateral));return new V(p.x(),y,p.z());
  }
  private static Mesh straight(V a,V b,Settings s){
    V left=b.sub(a).horizontalUnit().left();var points=new ArrayList<Sample>();
    int steps=(int)Math.ceil(a.distance(b));
    for(int i=0;i<=steps;i++){
      if((i&63)==0)RoadPlanningBudget.check();
      points.add(new Sample(a.add(b.sub(a).mul(i/(double)steps)),left,0,s.width()/2));
    }
    return RoadRibbon.mesh(points,s);
  }
  private static double[] leadAt(Port p,double clear,Lead l,double d,Options o){
    if(d<l.grow){double t=Settings.smooth(d/l.grow);return new double[]{p.initial()+(p.attached(o.width())-p.initial())*t,.5+(o.width()-.5)*t};}
    if(d<l.grow+l.hold)return new double[]{p.attached(o.width()),o.width()};
    double t=Settings.smooth(Math.min(1,(d-l.grow-l.hold)/l.peel));return new double[]{p.attached(o.width())+(clear-p.attached(o.width()))*t,o.width()};
  }
  private static Mesh clip(Mesh mesh,V axis,int direction,double from,double to){
    var out=new ArrayList<Sample>();
    V origin=mesh.first().center();
    double begin=mesh.first().center().sub(origin).dot(axis);
    for(double station:new double[]{from,to}){
      double target=begin+direction*station;Sample found=mesh.first();
      for(int i=1;i<mesh.samples().size();i++){
        if((i&63)==0)RoadPlanningBudget.check();
        Sample a=mesh.samples().get(i-1),b=mesh.samples().get(i);double x=a.center().sub(origin).dot(axis),y=b.center().sub(origin).dot(axis);
        if((target-x)*(target-y)<=1e-9){double t=Math.max(0,Math.min(1,(target-x)/(y-x)));double width=a.halfWidth()+(b.halfWidth()-a.halfWidth())*t;found=new Sample(a.center().add(b.center().sub(a.center()).mul(t)),a.left().add(b.left().sub(a.left()).mul(t)).horizontalUnit(),a.distance()+(b.distance()-a.distance())*t,width);break;}
      }
      if(station==from)out.add(found);
      else{double first=out.get(0).distance();for(Sample s:mesh.samples())if(s.distance()>first+1e-7&&s.distance()<found.distance()-1e-7)out.add(s);out.add(found);}
    }
    return RoadRibbon.mesh(out,mesh.settings());
  }
  private static Mesh ramp(Kind kind,V origin,V u,double station,int direction,Port a,Port b,double clear,
      double ya,double yb,Options o){
    Lead la=lead(a,clear,o),lb=lead(b,clear,o);double climb=climb(ya,yb),total=span(kind,a,b,clear,ya,yb,o);int steps=(int)Math.ceil(total/.35);
    var centers=new ArrayList<V>();var widths=new ArrayList<Double>();
    for(int i=0;i<=steps;i++){
      if((i&63)==0)RoadPlanningBudget.check();
      double d=total*i/steps,lateral,y,width;
      if(direct(kind,ya,yb)){
        double start=la.grow+la.hold,end=total-lb.grow-lb.hold;
        if(d<start){var at=leadAt(a,clear,la,d,o);lateral=at[0];width=at[1];}
        else if(d>end){var at=leadAt(b,clear,lb,total-d,o);lateral=at[0];width=at[1];}
        else{lateral=a.attached(o.width())+(b.attached(o.width())-a.attached(o.width()))*Settings.smooth((d-start)/(end-start));width=o.width();}
        y=ya;
      }
      else if(d<la.length()){var at=leadAt(a,clear,la,d,o);lateral=at[0];width=at[1];y=ya;}
      else if(d>la.length()+climb){var at=leadAt(b,clear,lb,total-d,o);lateral=at[0];width=at[1];y=yb;}
      else{lateral=clear;y=ya+(yb-ya)*Settings.smooth((d-la.length())/climb);width=o.width();}
      centers.add(point(origin,u,station+direction*d,lateral,y));widths.add(Math.max(.5,width));
    }
    var samples=new ArrayList<Sample>();
    for(int i=0;i<=steps;i++){
      if((i&63)==0)RoadPlanningBudget.check();
      V tangent=centers.get(Math.min(steps,i+1)).sub(centers.get(Math.max(0,i-1))).horizontalUnit();
      samples.add(new Sample(centers.get(i),tangent.left(),0,widths.get(i)/2));
    }
    var style=o.lanes()==1?Style.R1:Style.R2;
    Settings settings=new Settings(Mode.CURVE,style,o.width(),1,.4,90)
        .options(RoadProfile.Options.DEFAULT.traffic(o.leftTraffic()));
    return RoadRibbon.mesh(samples,settings);
  }
  /** Sample both borders as well as the center: an elevated ramp must clear the lower deck. */
  static void verify(List<Leg> legs,double clearance){
    for(int i=0;i<legs.size();i++)for(int j=0;j<i;j++){
      RoadPlanningBudget.check();
      Mesh a=legs.get(i).mesh(),b=legs.get(j).mesh();
      if(a.max().x()<b.min().x()||b.max().x()<a.min().x()||a.max().z()<b.min().z()||b.max().z()<a.min().z())continue;
      for(int k=0;k<a.samples().size();k+=2){
        if((k&63)==0)RoadPlanningBudget.check();
        Sample s=a.samples().get(k);
        for(double side:new double[]{-.98,0,.98}){
          V p=s.at(side*s.halfWidth(),0);var q=RoadQueries.horizontal(b,p);
          if(q.horizontalDistance()>q.sample().halfWidth()-.1)continue;
          double along=p.sub(q.sample().center()).dot(q.tangent().horizontalUnit());
          if(q.sample().distance()<1e-6&&along<-.05||q.sample().distance()>b.length()-1e-6&&along>.05)continue;
          double gap=Math.abs(p.y()-q.sample().center().y());
          if(gap>.12&&gap<clearance+Math.max(a.settings().thickness(),b.settings().thickness())-.05)
            throw new IllegalArgumentException(legs.get(i).name()+" 与 "+legs.get(j).name()+String.format(Locale.ROOT," 在 %.1f, %.1f, %.1f 附近净空不足（高差 %.2f），请增加侧向间隔或过渡长度",p.x(),p.y(),p.z(),gap));
        }
      }
    }
  }
  private CorridorPlanner(){}
}
