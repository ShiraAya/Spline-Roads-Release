package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Direction-constrained lane connector. Coordinates are sampled into the real road ribbon. */
public final class LaneRampPaths {
  public record Port(V position,V direction,V outside,double extraWidth,double grade,List<Sample> approach){
    public Port(V position,V direction,V outside,double extraWidth,double grade){this(position,direction,outside,extraWidth,grade,List.of());}
    public Port {approach=List.copyOf(approach);}
  }
  public record Candidate(LanePoints.Path path,Mesh mesh){}
  private record Frame(V p,V d){}
  /** One smooth free-span curve with exact endpoint tangents. Fixed extra-lane
   * approaches are kept outside the curve, and the requested radius is a minimum. */
  public static List<Candidate> smoothTurns(Port a,Port b,Settings settings,LanePoints.Options options,double maxGrade){
    return smoothTurns(a,b,settings,options,maxGrade,Math.abs(a.position().y()-b.position().y())>.5);
  }
  public static List<Candidate> smoothTurns(Port a,Port b,Settings settings,LanePoints.Options options,double maxGrade,boolean layerChange){
    LaneRampGrade.checked(maxGrade);
    if(LaneRampGrade.exceeds(a.grade(),1,maxGrade)||LaneRampGrade.exceeds(b.grade(),1,maxGrade))return List.of();
    if(options.sourceExtra()||options.targetExtra()){
      var prefix=options.sourceExtra()?approach(a,true,settings.width(),options.transition()):List.<Sample>of();
      var suffix=options.targetExtra()?approach(b,false,settings.width(),options.transition()):List.<Sample>of();
      var start=prefix.isEmpty()?a:approachPort(prefix,false,a);var end=suffix.isEmpty()?b:approachPort(suffix,true,b);
      var out=new ArrayList<Candidate>();
      for(var c:smoothTurns(start,end,settings,options.withoutApproaches(),maxGrade,layerChange))try{
        var samples=new ArrayList<Sample>();append(samples,prefix);append(samples,c.mesh().samples());append(samples,suffix);
        var mesh=RoadRibbon.mesh(samples,settings);LaneRampGrade.validate(mesh,maxGrade);RoadRibbon.checkSelfIntersections(mesh,4);checkVolume(mesh);
        out.add(new Candidate(c.path(),mesh));
      }catch(IllegalArgumentException ignored){}return out;
    }
    V delta=b.position().sub(a.position());double distance=delta.horizontalLength();
    if(distance<4||distance>2048)return List.of();
    var out=new ArrayList<Candidate>();var continuousReturns=new ArrayList<Candidate>();var simpleReturns=new ArrayList<Candidate>();
    boolean returning=delta.dot(a.direction())<0||delta.dot(b.direction())<0;
    // Parallel decks need a free corridor beside BOTH hosts before changing
    // height. A single Bezier with collinear endpoint tangents stays on the host.
    if(!returning&&layerChange&&a.direction().dot(b.direction())>.5
          &&a.outside().dot(b.outside())>.5){
      // A single smooth lateral bow avoids the middle reverse curve introduced
      // by three short waypoint curves alongside a curving upper deck.
      for(double side:new double[]{1,-1})for(double offset:new double[]{3,2,1,5})for(double fraction:new double[]{.55,.4,.7})try{
        RoadPlanningBudget.check();var frames=new ArrayList<Frame>();double handle=distance*fraction;
        V[] controls={a.position(),a.position().add(a.direction().mul(handle/2)),
            a.position().add(a.direction().mul(handle)).add(a.outside().mul(side*offset*settings.width())),
            b.position().sub(b.direction().mul(handle)).add(b.outside().mul(side*offset*settings.width())),
            b.position().sub(b.direction().mul(handle/2)),b.position()};
        quintic(frames,controls);var mesh=finish(a,b,settings,frames,0,maxGrade);
        if(RoadRibbon.minRadius(mesh)+1e-3>=options.radius())out.add(new Candidate(LanePoints.Path.AUTO,mesh));
      }catch(IllegalArgumentException ignored){}
      for(double offset:new double[]{settings.width(),settings.width()*2,settings.width()*3,settings.width()*5})
          for(double fraction:new double[]{.22,.32,.42,.48,.12})try{
          RoadPlanningBudget.check();var frames=new ArrayList<Frame>();
            V first=a.position().add(a.direction().mul(distance*fraction)).add(a.outside().mul(offset));
            V last=b.position().sub(b.direction().mul(distance*fraction)).add(b.outside().mul(offset));
            quintic(frames,a.position(),a.direction(),first,a.direction(),distance*fraction*.55);
            quintic(frames,first,a.direction(),last,b.direction(),first.sub(last).horizontalLength()*.55);
            quintic(frames,last,b.direction(),b.position(),b.direction(),distance*fraction*.55);
          var mesh=finish(a,b,settings,frames,0,maxGrade);
          if(RoadRibbon.minRadius(mesh)+1e-3>=options.radius())out.add(new Candidate(LanePoints.Path.AUTO,mesh));
        }catch(IllegalArgumentException ignored){}
    }
    if(!returning)for(double fraction:new double[]{.6,.85,1.1})try{
      var frames=new ArrayList<Frame>();quintic(frames,a.position(),a.direction(),b.position(),b.direction(),distance*fraction);
      var mesh=finish(a,b,settings,frames,0,maxGrade);
      if(RoadRibbon.minRadius(mesh)+1e-3>=options.radius())out.add(new Candidate(LanePoints.Path.AUTO,mesh));
    }catch(IllegalArgumentException ignored){}
    if(returning){
      // A return need not contain a reverse bend. Try two same-turn arcs with
      // their common external tangent before the three-circle fallback below.
      // Fair the joins (including the straight endpoint tangents), keeping the
      // normal radius, self-intersection and subsequent world-clearance checks.
      for(double scale:new double[]{.16,.22,.3,.4})for(int side:new int[]{1,-1})try{
        RoadPlanningBudget.check();double radius=Math.max(options.radius()*1.25,distance*scale);
        var rounded=fairTurns(tangentReturn(a,b,radius,side),Math.min(16,radius*.5),true);
        var mesh=finish(a,b,settings,rounded,0,maxGrade);
        if(RoadRibbon.minRadius(mesh)+1e-3>=options.radius())simpleReturns.add(new Candidate(LanePoints.Path.AUTO,mesh));
      }catch(IllegalArgumentException ignored){}
      // Three tangent circles cover return layouts whose two-circle solution
      // degenerates. Every curved segment has the same generous radius.
      for(double scale:new double[]{.16,.22,.3,.4})for(int side:new int[]{1,-1})for(int bend:new int[]{1,-1})try{
        double radius=Math.max(options.radius(),distance*scale);
        for(double generous:new double[]{1.25,1.4,1.6})try{
          var rounded=fairTurns(roundReturn(a,b,radius*generous,side,bend),Math.min(16,radius*.5));
          var continuous=finish(a,b,settings,rounded,0,maxGrade);
          if(RoadRibbon.minRadius(continuous)+1e-3>=options.radius())continuousReturns.add(new Candidate(LanePoints.Path.AUTO,continuous));
        }catch(IllegalArgumentException ignored){}
        var mesh=finish(a,b,settings,roundReturn(a,b,radius,side,bend),0,maxGrade);
        if(RoadRibbon.minRadius(mesh)+1e-3>=options.radius())out.add(new Candidate(LanePoints.Path.AUTO,mesh));
      }catch(IllegalArgumentException ignored){}
      out.sort(Comparator.comparingDouble(c->c.mesh().length()));
    }
    for(double fraction:returning?new double[]{.7,1.1,1.6}:new double[]{.55,.4,.7})for(double ratio:new double[]{1,.55,1.8})try{
      RoadPlanningBudget.check();var frames=new ArrayList<Frame>();
      bezier(frames,a.position(),a.direction(),b.position(),b.direction(),Math.max(options.radius(),distance*fraction),Math.max(options.radius(),distance*fraction*ratio));
      var mesh=finish(a,b,settings,frames,0,maxGrade);
      if(RoadRibbon.minRadius(mesh)+1e-3<options.radius())continue;
      out.add(new Candidate(LanePoints.Path.AUTO,mesh));
    }catch(IllegalArgumentException ignored){}
    // Keep the explicit side corridor ahead of host-hugging curves on stacked
    // same-side decks. Sorting solely by length picked a legal two-stage climb
    // under the upper deck before trying the available lateral escape.
    if(returning){
      out.sort(Comparator.comparingDouble(c->c.mesh().length()));
      double shortest=out.isEmpty()?Double.POSITIVE_INFINITY:out.get(0).mesh().length();
      continuousReturns.sort(Comparator.comparingDouble(c->c.mesh().length()));
      out.addAll(0,continuousReturns.stream().filter(c->c.mesh().length()<=shortest*1.6).toList());
      simpleReturns.sort(Comparator.comparingDouble(c->c.mesh().length()));
      out.addAll(0,simpleReturns.stream().filter(c->c.mesh().length()<=shortest*1.6).toList());
    }
    return out;
  }
  private static void quintic(List<Frame> out,V a,V da,V b,V db,double handle){
    V[] p={a,a.add(da.mul(handle/2)),a.add(da.mul(handle)),b.sub(db.mul(handle)),b.sub(db.mul(handle/2)),b};
    quintic(out,p);
  }
  private static void quintic(List<Frame> out,V[] p){
    polynomial(out,p);
  }
  private static void polynomial(List<Frame> out,V[] p){
    double length=0;for(int i=1;i<p.length;i++)length+=p[i].sub(p[i-1]).horizontalLength();
    int count=Math.max(16,(int)Math.ceil(length/.5));
    for(int i=0;i<=count;i++){
      double t=(double)i/count,u=1-t;V[] q=p.clone();
      for(int n=p.length-1;n>1;n--)for(int j=0;j<n;j++)q[j]=q[j].mul(u).add(q[j+1].mul(t));
      V direction=q[1].sub(q[0]);if(direction.horizontalLength()<1e-8)throw new IllegalArgumentException("平滑曲线折返");
      add(out,q[0].mul(u).add(q[1].mul(t)),direction.horizontalUnit());
    }
  }
  private static List<Frame> roundReturn(Port a,Port b,double radius,int side,int bend){
    V ca=a.position().add(a.direction().left().mul(side*radius));
    V cb=b.position().add(b.direction().left().mul(side*radius));
    V delta=cb.sub(ca);delta=new V(delta.x(),0,delta.z());double distance=delta.horizontalLength();
    if(distance<1e-7||distance>=4*radius-1e-7)throw new IllegalArgumentException("回环圆弧空间不足");
    V middle=ca.add(delta.mul(.5)).add(delta.horizontalUnit().left().mul(bend*Math.sqrt(4*radius*radius-distance*distance/4)));
    V j1=ca.add(middle).mul(.5),j2=cb.add(middle).mul(.5);
    V d1=j1.sub(ca).left().mul(side).horizontalUnit(),d2=j2.sub(middle).left().mul(-side).horizontalUnit();
    var frames=new ArrayList<Frame>();
    circular(frames,ca,radius,a.direction(),d1,side);
    circular(frames,middle,radius,d1,d2,-side);
    circular(frames,cb,radius,d2,b.direction(),side);
    return frames;
  }
  private static List<Frame> tangentReturn(Port a,Port b,double radius,int side){
    V ca=a.position().add(a.direction().left().mul(side*radius));
    V cb=b.position().add(b.direction().left().mul(side*radius));
    V delta=cb.sub(ca);var frames=new ArrayList<Frame>();
    if(delta.horizontalLength()<1e-7){circular(frames,ca,radius,a.direction(),b.direction(),side);return frames;}
    V tangent=delta.horizontalUnit();
    circular(frames,ca,radius,a.direction(),tangent,side);
    line(frames,ca.sub(tangent.left().mul(side*radius)),cb.sub(tangent.left().mul(side*radius)),tangent);
    circular(frames,cb,radius,tangent,b.direction(),side);
    return frames;
  }
  /** Replace curvature steps by endpoint-position/tangent/curvature matched
   * quintics. Overlapping transitions share one span, so a tiny reverse arc
   * cannot survive as two independently rounded kinks. */
  private static List<Frame> fairTurns(List<Frame> frames,double reach){
    return fairTurns(frames,reach,false);
  }
  private static List<Frame> fairTurns(List<Frame> frames,double reach,boolean sameTurn){
    int n=frames.size();double[] distance=new double[n],curvature=new double[n];
    for(int i=1;i<n;i++){
      var a=frames.get(i-1);var b=frames.get(i);double length=b.p().sub(a.p()).horizontalLength();distance[i]=distance[i-1]+length;
      curvature[i]=Math.atan2(a.d().x()*b.d().z()-a.d().z()*b.d().x(),a.d().dot(b.d()))/length;
    }
    var windows=new ArrayList<int[]>();
    for(int i=0;i<n;i++)if(i==0||i==n-1||Math.abs(curvature[i]-curvature[i+1])>.005){
      int a=i,b=i;while(a>0&&distance[i]-distance[a]<reach)a--;while(b<n-1&&distance[b]-distance[i]<reach)b++;
      if(!windows.isEmpty()&&a<=windows.get(windows.size()-1)[1])windows.get(windows.size()-1)[1]=b;
      else windows.add(new int[]{a,b});
    }
    var out=new ArrayList<Frame>();int cursor=0;
    for(var window:windows){int a=window[0],b=window[1];
      while(cursor<a){var f=frames.get(cursor++);add(out,f.p(),f.d());}
      var first=frames.get(a);var last=frames.get(b);double length=distance[b]-distance[a];
      V p=first.p(),q=last.p(),u=first.d(),v=last.d();double ka=a==0?0:curvature[a],kb=b==n-1?0:curvature[b+1];
      if(sameTurn){convexBlend(out,p,u,q,v,ka,kb);cursor=b+1;continue;}
      V p1=p.add(u.mul(length/5)),p2=p.add(u.mul(2*length/5)).add(u.left().mul(ka*length*length/20));
      V p4=q.sub(v.mul(length/5)),p3=q.sub(v.mul(2*length/5)).add(v.left().mul(kb*length*length/20));
      quintic(out,new V[]{p,p1,p2,p3,p4,q});cursor=b+1;
    }
    while(cursor<n){var f=frames.get(cursor++);add(out,f.p(),f.d());}return out;
  }
  /** A convex control polygon cannot introduce a reverse bend. At arc/line
   * joins, one control is the tangent intersection; solve the other handle
   * for the arc curvature so the straight end retains exactly zero curvature. */
  private static void convexBlend(List<Frame> out,V p,V u,V q,V v,double ka,double kb){
    double cross=u.x()*v.z()-u.z()*v.x();V delta=q.sub(p);
    if(Math.abs(cross)<1e-8){line(out,p,q,u);return;}
    double alpha=(delta.x()*v.z()-delta.z()*v.x())/cross;
    double beta=(u.x()*delta.z()-u.z()*delta.x())/cross;
    if(alpha<=0||beta<=0)throw new IllegalArgumentException("同向回环平滑空间不足");
    double ha,hb;
    if(Math.abs(ka)<1e-8){hb=beta;ha=alpha-1.5*kb*beta*beta/cross;}
    else if(Math.abs(kb)<1e-8){ha=alpha;hb=beta-1.5*ka*alpha*alpha/cross;}
    else {ha=alpha*.75;hb=beta*.75;}
    if(ha<=0||hb<=0||ha>alpha+1e-6||hb>beta+1e-6)throw new IllegalArgumentException("同向回环曲率接头不足");
    bezier(out,p,u,q,v,ha,hb);
  }
  private static void circular(List<Frame> frames,V center,double radius,V from,V to,int side){
    double turn=positive(side*(angle(to)-angle(from)));
    if(side>0)arc(frames,center,radius,angle(from),turn);else arcLeft(frames,center,radius,angle(from),turn);
  }
  /** Complete circle-straight-circle direction families for AUTO fallback.
   * Short right/left turns cannot reach a target behind its incoming tangent.
   * Keep the real lane directions and requested radius instead of reversing B.
   * Explicit RIGHT/LEFT modes never use these unrestricted turns. */
  public static List<Candidate> automaticTurns(Port a,Port b,Settings settings,LanePoints.Options options,double maxGrade){
    LaneRampGrade.checked(maxGrade);
    if(a.position().sub(b.position()).horizontalLength()>2048)
      throw new IllegalArgumentException("匝道端点距离不能超过 2048 格");
    if(LaneRampGrade.exceeds(a.grade(),1,maxGrade)||LaneRampGrade.exceeds(b.grade(),1,maxGrade))
      throw new IllegalArgumentException("端口坡度超过 "+LaneRampGrade.label(maxGrade)+"，不能破坏接缝来强行连接");
    if(options.sourceExtra()||options.targetExtra()){
      var prefix=options.sourceExtra()?approach(a,true,settings.width(),options.transition()):List.<Sample>of();
      var suffix=options.targetExtra()?approach(b,false,settings.width(),options.transition()):List.<Sample>of();
      var start=prefix.isEmpty()?a:approachPort(prefix,false,a);var end=suffix.isEmpty()?b:approachPort(suffix,true,b);
      var out=new ArrayList<Candidate>();
      for(var candidate:automaticTurns(start,end,settings,options.withoutApproaches(),maxGrade))try{
        var samples=new ArrayList<Sample>();append(samples,prefix);append(samples,candidate.mesh().samples());append(samples,suffix);
        var mesh=RoadRibbon.mesh(samples,settings);RoadRibbon.checkSelfIntersections(mesh,4);checkVolume(mesh);
        out.add(new Candidate(candidate.path(),mesh));
      }catch(IllegalArgumentException ignored){}
      return out;
    }
    var out=new ArrayList<Candidate>();double r=options.radius();
    V pa=a.position().add(a.direction().mul(options.transition()));
    V pb=b.position().sub(b.direction().mul(options.transition()));
    for(int first:new int[]{1,-1})for(int last:new int[]{1,-1})try{
      RoadPlanningBudget.check();var frames=new ArrayList<Frame>();
      V ca=pa.add(a.direction().left().mul(first*r)),cb=pb.add(b.direction().left().mul(last*r));
      V delta=cb.sub(ca);double length=delta.horizontalLength(),offset=(first-last)*r;
      if(length<1e-7||length<=Math.abs(offset)+1e-7)continue;
      double heading=angle(delta)+Math.asin(offset/length);V tangent=new V(Math.cos(heading),0,Math.sin(heading));
      double turnA=positive(first*(heading-angle(a.direction()))),turnB=positive(last*(angle(b.direction())-heading));
      line(frames,a.position(),pa,a.direction());
      if(first>0)arc(frames,ca,r,angle(a.direction()),turnA);else arcLeft(frames,ca,r,angle(a.direction()),turnA);
      V ta=ca.sub(tangent.left().mul(first*r)),tb=cb.sub(tangent.left().mul(last*r));line(frames,ta,tb,tangent);
      if(last>0)arc(frames,cb,r,heading,turnB);else arcLeft(frames,cb,r,heading,turnB);
      line(frames,pb,b.position(),b.direction());
      out.add(new Candidate(LanePoints.Path.AUTO,finish(a,b,settings,frames,0,maxGrade)));
    }catch(IllegalArgumentException ignored){}
    out.sort(Comparator.comparingDouble(c->c.mesh().length()));return List.copyOf(out);
  }
  public static List<Candidate> candidates(Port a,Port b,Settings settings,LanePoints.Options options){
    return candidates(a,b,settings,options,LaneRampGrade.LEGACY);
  }
  public static List<Candidate> candidates(Port a,Port b,Settings settings,LanePoints.Options options,double maxGrade){
    LaneRampGrade.checked(maxGrade);
    if(options.sourceExtra()||options.targetExtra())return expanded(a,b,settings,options,maxGrade);
    List<Candidate> out=new ArrayList<>();var errors=new EnumMap<LanePoints.Path,String>(LanePoints.Path.class);
    for(var kind:options.path()==LanePoints.Path.AUTO?List.of(LanePoints.Path.DIRECT,LanePoints.Path.RIGHT,LanePoints.Path.LEFT,LanePoints.Path.LEFT_LOOP):List.of(options.path()))for(double lift:loopLifts(kind,options.elevation()))try{
      out.add(new Candidate(kind,generate(a,b,settings,options,kind,lift,maxGrade)));
    }catch(IllegalArgumentException e){errors.put(kind,e.getMessage());}
    if(out.isEmpty())throw new IllegalArgumentException(failure(a,b,options,errors));
    out.sort(Comparator.comparingDouble(c->c.mesh().length()));return List.copyOf(out);
  }
  private static double[] loopLifts(LanePoints.Path path,LanePoints.Elevation elevation){
    if(path!=LanePoints.Path.LEFT_LOOP||elevation==LanePoints.Elevation.KEEP)return new double[]{0};
    if(elevation==LanePoints.Elevation.OVER)return new double[]{0,6,8,10,12,16,24};
    if(elevation==LanePoints.Elevation.UNDER)return new double[]{0,-6,-8,-10,-12,-16,-24};
    return new double[]{0,6,-6,8,-8,10,-10,12,-12,16,-16,24,-24};
  }
  private static List<Candidate> expanded(Port a,Port b,Settings settings,LanePoints.Options o,double maxGrade){
    List<Sample> prefix=o.sourceExtra()?approach(a,true,settings.width(),o.transition()):List.of();
    List<Sample> suffix=o.targetExtra()?approach(b,false,settings.width(),o.transition()):List.of();
    Port start=prefix.isEmpty()?a:approachPort(prefix,false,a),end=suffix.isEmpty()?b:approachPort(suffix,true,b);
    var plain=o.withoutApproaches();
    var result=new ArrayList<Candidate>();
    String error="扩出后的路径净空不足";
    // A fixed auxiliary taper must remain host-aligned, but the free route may
    // align earlier. Otherwise a same-height approach from the opposite side can
    // cross the last live lane too close to the locked tail to descend safely,
    // despite hundreds of metres of unused route. These are additional candidates,
    // not relocated B points or exemptions: the normal full-road corridor still
    // checks every sample, and only the original auxiliary approach is locked.
    for(double lead:o.targetExtra()?new double[]{0,64,96,128}:new double[]{0})try{
      V extension=end.direction().mul(lead).add(new V(0,end.grade()*lead,0));
      Port early=lead==0?end:new Port(end.position().sub(extension),end.direction(),end.outside(),end.extraWidth(),end.grade());
      for(var c:candidates(start,early,settings,plain,maxGrade))try{
        var samples=new ArrayList<Sample>();append(samples,prefix);append(samples,c.mesh().samples());
        if(lead>0){int count=(int)Math.ceil(lead/.5);var run=new ArrayList<Sample>();
          for(int i=1;i<=count;i++)run.add(new Sample(early.position().add(extension.mul((double)i/count)),end.direction().left(),lead*i/count,settings.width()/2));
          append(samples,run);
        }
        append(samples,suffix);
        Mesh mesh=RoadRibbon.mesh(samples,settings);LaneRampGrade.validate(mesh,maxGrade);RoadRibbon.checkSelfIntersections(mesh,4);checkVolume(mesh);result.add(new Candidate(c.path(),mesh));
      }catch(IllegalArgumentException e){error=e.getMessage();}
    }catch(IllegalArgumentException e){error=e.getMessage();}
    // A motor-edge departure can need to rise beside its host BEFORE turning
    // across it. These straight free leads are not part of the locked at-grade
    // auxiliary lane; profile the whole free span together so its length remains
    // available for climbing. All candidates still pass the ordinary corridor.
    if(o.sourceExtra()&&(o.path()==LanePoints.Path.DIRECT||o.path()==LanePoints.Path.AUTO))
      for(double lead:new double[]{16,32,48})try{
        if(end.position().sub(start.position()).dot(start.direction())<lead+8)continue;
        V later=start.position().add(start.direction().mul(lead));
        var late=new Port(later,start.direction(),start.outside(),start.extraWidth(),0);
        var flatEnd=new Port(new V(end.position().x(),later.y(),end.position().z()),end.direction(),end.outside(),end.extraWidth(),0);
        var direct=new LanePoints.Options(LanePoints.Path.DIRECT,LanePoints.Departure.BRANCH,LanePoints.Arrival.MERGE,o.radius(),o.transition(),o.elevation(),o.landing(),o.gradeOverride(),o.allowTunnel());
        for(var c:candidates(late,flatEnd,settings,direct,maxGrade)){
          var frames=new ArrayList<Frame>();line(frames,start.position(),later,start.direction());
          for(var s:c.mesh().samples())add(frames,s.center(),s.left().left().mul(-1));
          var free=finish(start,end,settings,frames,0,maxGrade);var samples=new ArrayList<Sample>();
          append(samples,prefix);append(samples,free.samples());append(samples,suffix);
          var mesh=RoadRibbon.mesh(samples,settings);LaneRampGrade.validate(mesh,maxGrade);RoadRibbon.checkSelfIntersections(mesh,4);checkVolume(mesh);
          result.add(new Candidate(LanePoints.Path.DIRECT,mesh));
        }
      }catch(IllegalArgumentException ignored){}
    if(result.isEmpty())throw new IllegalArgumentException(error);
    return List.copyOf(result);
  }
  private static List<Sample> approach(Port p,boolean source,double width,double transition){
    if(!p.approach().isEmpty())return p.approach();
    double total=LaneRampApproach.length(transition);int n=(int)Math.ceil(total/.5);var out=new ArrayList<Sample>();
    for(int i=0;i<=n;i++){double d=total*i/n,q=source?d:total-d,grow=Settings.smooth(Math.min(1,q/LaneRampApproach.taper(transition)));
      V position=p.position().add(p.direction().mul(source?d:d-total)).add(p.outside().mul(p.extraWidth()*grow));
      out.add(new Sample(position,p.direction().left(),d,width/2));}
    return out;
  }
  private static Port approachPort(List<Sample> samples,boolean first,Port p){
    var s=samples.get(first?0:samples.size()-1);var a=samples.get(first?0:samples.size()-2);var b=samples.get(first?1:samples.size()-1);V d=b.center().sub(a.center());
    return new Port(s.center(),s.left().left().mul(-1),p.outside(),p.extraWidth(),d.y()/Math.max(.001,d.horizontalLength()));
  }
  private static void append(List<Sample> out,List<Sample> added){for(var s:added){double d=out.isEmpty()?0:s.center().sub(out.get(out.size()-1).center()).horizontalLength();if(!out.isEmpty()&&d<1e-7)continue;out.add(new Sample(s.center(),s.left(),out.isEmpty()?0:out.get(out.size()-1).distance()+d,s.halfWidth()));}}
  public static String failure(Port a,Port b,LanePoints.Options options,Map<LanePoints.Path,String> errors){
    var preferred=options.path();
    if(preferred==LanePoints.Path.AUTO&&errors.containsKey(LanePoints.Path.AUTO))
      return "自动回转方案："+errors.get(LanePoints.Path.AUTO);
    if(preferred==LanePoints.Path.AUTO){double turn=positive(angle(b.direction())-angle(a.direction()));preferred=a.direction().dot(b.direction())>.75?LanePoints.Path.DIRECT:turn<=Math.PI?LanePoints.Path.RIGHT:LanePoints.Path.LEFT;}
    String error=errors.get(preferred);if(error==null)return "自动未找到可用路径："+String.join("；",errors.values());
    String prefix=preferred.label+"：";while(error.startsWith(prefix))error=error.substring(prefix.length());
    return (options.path()==LanePoints.Path.AUTO?"自动 / ":"")+prefix+error;
  }
  private static Mesh generate(Port a,Port b,Settings settings,LanePoints.Options o,LanePoints.Path kind,double lift,double maxGrade){
    if(kind==LanePoints.Path.LEFT){
      Mesh mirrored=generateOriented(reflect(a),reflect(b),settings,o,LanePoints.Path.RIGHT,lift,maxGrade,LanePoints.Path.LEFT);
      var samples=mirrored.samples().stream().map(s->new Sample(reflect(s.center()),reflect(s.left()).mul(-1),s.distance(),s.halfWidth())).toList();
      return RoadRibbon.mesh(samples,settings);
    }
    return generateOriented(a,b,settings,o,kind,lift,maxGrade,kind);
  }
  /** The geometry is evaluated in a canonical right-turn frame, but diagnostic
   * ownership always remains with the requested path. No LEFT -> RIGHT fallback. */
  private static Mesh generateOriented(Port a,Port b,Settings settings,LanePoints.Options o,LanePoints.Path kind,double lift,double maxGrade,LanePoints.Path requested){
    RoadPlanningBudget.check();
    if(LaneRampGrade.exceeds(a.grade(),1,maxGrade)||LaneRampGrade.exceeds(b.grade(),1,maxGrade))
      throw new IllegalArgumentException("端口坡度超过 "+LaneRampGrade.label(maxGrade)+"，不能破坏接缝来强行连接");
    if(a.position().sub(b.position()).horizontalLength()>2048)throw new IllegalArgumentException("匝道端点距离不能超过 2048 格");
    double transition=o.transition();V pa=a.position(),pb=b.position();List<Frame> frames=new ArrayList<>();
    double leadA=transition,leadB=transition;
    if(kind==LanePoints.Path.RIGHT){
      double turn=positive(angle(b.direction())-angle(a.direction()));
      if(turn>Math.PI+1e-5)throw new IllegalArgumentException("所选车道方向不符合"+requested.label+"；不能反转车道方向");
      double cross=a.direction().x()*b.direction().z()-a.direction().z()*b.direction().x();
      if(cross>1e-6){
        V delta=pb.sub(pa);double tangent=o.radius()*Math.tan(turn/2);
        double availableA=(delta.x()*b.direction().z()-delta.z()*b.direction().x())/cross-tangent;
        double availableB=(a.direction().x()*delta.z()-a.direction().z()*delta.x())/cross-tangent;
        // Retain the requested radius and explicit extra-lane tapers; shorten only optional straight leads.
        leadA=Math.min(transition,Math.max(0,availableA));
        leadB=Math.min(transition,Math.max(0,availableB));
      }
    }
    if(kind!=LanePoints.Path.DIRECT){V lead=pa.add(a.direction().mul(leadA));if(leadA>1e-6)line(frames,pa,lead,a.direction());pa=lead;pb=pb.sub(b.direction().mul(leadB));}
    if(kind==LanePoints.Path.DIRECT){
      V delta=pb.sub(pa);if(delta.dot(a.direction())<2||delta.dot(b.direction())<2||a.direction().dot(b.direction())<.75)throw new IllegalArgumentException("直接连接需要两端大致同向且前方有过渡空间；请尝试自动或回环");
      bezier(frames,pa,a.direction(),pb,b.direction(),Math.max(4,Math.min(delta.horizontalLength()*.42,Math.max(o.radius(),transition))));
    }else{
      // Right uses an external tangent (RSR); the left loop uses an internal
      // tangent (RSL) with a long right arc and a small left alignment arc.
      double r=o.radius();V ca=pa.add(a.direction().left().mul(r)),cb=pb.add(b.direction().left().mul(kind==LanePoints.Path.LEFT_LOOP?-r:r));
      V delta=cb.sub(ca);
      if(kind==LanePoints.Path.RIGHT&&delta.horizontalLength()<1e-6){
        arc(frames,ca,r,angle(a.direction()),positive(angle(b.direction())-angle(a.direction())));
      }else{
      if(delta.horizontalLength()<1e-6)throw new IllegalArgumentException("回环圆心重合，请调整半径或点位");V tangent=delta.horizontalUnit();if(kind==LanePoints.Path.LEFT_LOOP){double length=delta.horizontalLength();if(length<=2*r)throw new IllegalArgumentException("回环内公切线空间不足，请扩大间距");double theta=angle(tangent)+Math.asin(2*r/length);tangent=new V(Math.cos(theta),0,Math.sin(theta));}
      double aa=angle(a.direction()),bb=angle(b.direction()),tt=angle(tangent),turnA=positive(tt-aa),turnB=positive(kind==LanePoints.Path.LEFT_LOOP?tt-bb:bb-tt),turn=kind==LanePoints.Path.LEFT_LOOP?turnA-turnB:turnA+turnB;
      if(kind==LanePoints.Path.RIGHT&&turn>Math.PI+1e-5)throw new IllegalArgumentException("当前半径或扩出过渡放不进"+requested.label+"空间，请减小半径、关闭扩出或调整选点");
      if(kind==LanePoints.Path.LEFT_LOOP&&turn<Math.PI+1e-5)throw new IllegalArgumentException("当前布局没有左转回环空间，请移动点位或改用其他样式");
      arc(frames,ca,r,aa,turnA);V ta=ca.sub(tangent.left().mul(r)),tb=cb.sub(tangent.left().mul(kind==LanePoints.Path.LEFT_LOOP?-r:r));line(frames,ta,tb,tangent);if(kind==LanePoints.Path.LEFT_LOOP)arcLeft(frames,cb,r,tt,turnB);else arc(frames,cb,r,tt,turnB);
      }
    }
    if(kind!=LanePoints.Path.DIRECT)line(frames,pb,b.position(),b.direction());
    return finish(a,b,settings,frames,lift,maxGrade);
  }
  private static Mesh finish(Port a,Port b,Settings settings,List<Frame> frames,double lift,double maxGrade){
    if(frames.size()<2)throw new IllegalArgumentException("连接距离过短");
    // Explicit endpoint frames prevent sampled-chord headings from reversing either lane.
    frames.set(0,new Frame(a.position(),a.direction()));frames.set(frames.size()-1,new Frame(b.position(),b.direction()));
    double[] d=new double[frames.size()];for(int i=1;i<d.length;i++)d[i]=d[i-1]+frames.get(i).p().sub(frames.get(i-1).p()).horizontalLength();
    double length=d[d.length-1];if(length<4)throw new IllegalArgumentException("连接距离须至少 4 格");
    List<Sample> samples=new ArrayList<>();double lastY=0;
    for(int i=0;i<frames.size();i++){double t=d[i]/length,u=1-t;double y=(2*t*t*t-3*t*t+1)*a.position().y()+(t*t*t-2*t*t+t)*length*a.grade()+(-2*t*t*t+3*t*t)*b.position().y()+(t*t*t-t*t)*length*b.grade()+lift*Math.pow(Math.sin(Math.PI*t),2);if(settings.style().connectorRamp())y=LaneRampProfile.height(a.position().y(),b.position().y(),a.grade(),b.grade(),length,d[i],maxGrade)+lift*Math.pow(Math.sin(Math.PI*t),2);
      if(i>0&&LaneRampGrade.exceeds(y-lastY,d[i]-d[i-1],maxGrade))throw new IllegalArgumentException("匝道坡度超过 "+LaneRampGrade.label(maxGrade)+"，请扩大间距或降低高差");lastY=y;
      var f=frames.get(i);samples.add(new Sample(new V(f.p().x(),y,f.p().z()),f.d().left(),d[i],settings.width()/2));
    }
    Mesh mesh=RoadRibbon.mesh(samples,settings);if(RoadRibbon.minRadius(mesh)<Math.max(settings.width()/2+.5,3))throw new IllegalArgumentException("接头内侧半径不足，请增大过渡长度");RoadRibbon.checkSelfIntersections(mesh,4);checkVolume(mesh);return mesh;
  }
  public static void checkVolume(Mesh mesh){
    if(mesh.samples().size()<96){shortVolume(mesh);return;}
    var p=mesh.samples();double half=p.stream().mapToDouble(Sample::halfWidth).max().orElse(mesh.settings().width()/2);
    double width=half*2,clearance=4+mesh.settings().thickness();var index=RoadPathIndex.points(mesh);
    for(int i=0;i<p.size();i+=3){
      RoadPlanningBudget.check();var a=p.get(i);
      for(int point:index.query(RoadPathIndex.segment(a.center(),a.center()),a.halfWidth()+half+.35)){
        int j=point*3;if(j<i+3)continue;var b=p.get(j);
        if(b.distance()-a.distance()<width*3)continue;
        double horizontal=a.center().sub(b.center()).horizontalLength();
        if(horizontal<a.halfWidth()+b.halfWidth()+.35&&Math.abs(a.center().y()-b.center().y())<clearance-.05)
          throw new IllegalArgumentException("回环路面体积净空不足，请增大半径、间距或过渡长度");
      }
    }
  }

  private static V reflect(V p){return new V(p.x(),p.y(),-p.z());}
  private static Port reflect(Port p){return new Port(reflect(p.position()),reflect(p.direction()),reflect(p.outside()),p.extraWidth(),p.grade());}
  private static double angle(V d){return Math.atan2(d.z(),d.x());}
  private static double positive(double a){double v=a%(2*Math.PI);if(v<0)v+=2*Math.PI;return v<1e-10||2*Math.PI-v<1e-10?0:v;}
  private static void add(List<Frame> f,V p,V d){if(!f.isEmpty()&&f.get(f.size()-1).p().sub(p).horizontalLength()<1e-6)return;if(f.size()>=RoadLimits.MAX_SAMPLES)throw new IllegalArgumentException("匝道过长");f.add(new Frame(p,d));}
  private static void arc(List<Frame> f,V c,double r,double start,double turn){int n=Math.max(1,(int)Math.ceil(r*turn/.65));for(int i=0;i<=n;i++){double t=start+turn*i/n;V d=new V(Math.cos(t),0,Math.sin(t));add(f,c.sub(d.left().mul(r)),d);}}
  private static void arcLeft(List<Frame> f,V c,double r,double start,double turn){int n=Math.max(1,(int)Math.ceil(r*turn/.65));for(int i=0;i<=n;i++){double t=start-turn*i/n;V d=new V(Math.cos(t),0,Math.sin(t));add(f,c.add(d.left().mul(r)),d);}}
  private static void line(List<Frame> f,V a,V b,V dir){int n=Math.max(1,(int)Math.ceil(a.sub(b).horizontalLength()/.65));for(int i=0;i<=n;i++)add(f,a.add(b.sub(a).mul((double)i/n)),dir);}
  private static void bezier(List<Frame> f,V a,V da,V b,V db,double k){bezier(f,a,da,b,db,k,k);}
  private static void bezier(List<Frame> f,V a,V da,V b,V db,double ka,double kb){V p=a.add(da.mul(ka)),q=b.sub(db.mul(kb));int n=Math.max(8,(int)Math.ceil((a.sub(p).horizontalLength()+p.sub(q).horizontalLength()+q.sub(b).horizontalLength())/.55));for(int i=0;i<=n;i++){double t=(double)i/n,u=1-t;V v=a.mul(u*u*u).add(p.mul(3*u*u*t)).add(q.mul(3*u*t*t)).add(b.mul(t*t*t));V d=p.sub(a).mul(u*u).add(q.sub(p).mul(2*u*t)).add(b.sub(q).mul(t*t));if(d.horizontalLength()<1e-7)throw new IllegalArgumentException("匝道过渡折返");add(f,v,d.horizontalUnit());}}
  private static void shortVolume(Mesh mesh){var p=mesh.samples();double width=mesh.samples().stream().mapToDouble(s->s.halfWidth()*2).max().orElse(mesh.settings().width()),clearance=4+mesh.settings().thickness();for(int i=0;i<p.size();i+=3){RoadPlanningBudget.check();for(int j=i+3;j<p.size();j+=3){var a=p.get(i);var b=p.get(j);if(b.distance()-a.distance()<width*3)continue;double horizontal=a.center().sub(b.center()).horizontalLength();if(horizontal<a.halfWidth()+b.halfWidth()+.35&&Math.abs(a.center().y()-b.center().y())<clearance-.05)throw new IllegalArgumentException("回环路面体积净空不足，请增大半径、间距或过渡长度");}}}
  private LaneRampPaths(){}
}
