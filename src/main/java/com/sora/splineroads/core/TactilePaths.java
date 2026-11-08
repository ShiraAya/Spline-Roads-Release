package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Offset a complete corner, remove offset loops, then round the remaining joins. */
public final class TactilePaths {
  private record End(int arm,Sample mouth,int side,RoadSidewalks.Config config) {
    V edge(){return mouth.at(side*mouth.halfWidth(),0);}
    V normal(){return mouth.left().mul(side);}
    double offset(){return RoadStreetscape.tactileOffset(config.width());}
    boolean enabled(){return config.enabled()&&config.smooth()&&config.tactile()&&config.width()>=2
        &&(config.side()==RoadSidewalks.Side.BOTH||config.side()==RoadSidewalks.Side.LEFT&&side<0||config.side()==RoadSidewalks.Side.RIGHT&&side>0);}
  }
  private static End nearest(JunctionSpec s,List<Mesh> approaches,V p){
    End best=null;double d=Double.POSITIVE_INFINITY;
    for(int i=0;i<approaches.size();i++)for(int side:new int[]{-1,1}){
      var end=new End(i,approaches.get(i).last(),side,s.arms().get(i).external().options().sidewalk());
      double distance=end.edge().distance(p);if(distance<d){d=distance;best=end;}
    }return best;
  }
  public static List<Part> junction(JunctionSpec s,List<Mesh> approaches,List<V> boundary){return junction(s,approaches,boundary,false);}
  public record Trim(int arm,int side,V point,V forward){}
  public static List<Part> junction(JunctionSpec s,List<Mesh> approaches,List<V> boundary,boolean insetBend){return junction(s,approaches,boundary,insetBend,new ArrayList<>());}
  public static List<Trim> trims(JunctionSpec s,List<Mesh> approaches,List<V> boundary){
    var trims=new ArrayList<Trim>();junction(s,approaches,boundary,true,trims);return List.copyOf(trims);
  }
  private static List<Part> junction(JunctionSpec s,List<Mesh> approaches,List<V> boundary,boolean insetBend,List<Trim> trims){
    var out=new ArrayList<Part>();int n=boundary.size();if(n<3)return out;
    boolean[] open=new boolean[n];double area=JunctionPaint.area(boundary);double sign=area>=0?-1:1;
    int start=-1;
    for(int i=0;i<n;i++){
      V a=boundary.get(i),b=boundary.get((i+1)%n),mid=a.add(b).mul(.5);
      open[i]=a.distance(b)>1e-8&&approaches.stream().noneMatch(m->RoadQueries.contains(m,mid,.02,.1));
      if(!open[i])start=i;
    }
    if(start<0)return out;
    var run=new ArrayList<V>();
    for(int k=1;k<=n;k++){
      int i=(start+k)%n;
      if(open[i]){if(run.isEmpty())run.add(boundary.get(i));run.add(boundary.get((i+1)%n));}
      else if(!run.isEmpty()){corner(out,s,approaches,run,sign,insetBend,trims,boundary);run.clear();}
    }
    if(!run.isEmpty())corner(out,s,approaches,run,sign,insetBend,trims,boundary);
    crossingBranches(out,s,approaches,trims);
    return List.copyOf(out);
  }
  /** Corner continuity and access to each crossing are independent connections. */
  private static void crossingBranches(List<Part> out,JunctionSpec spec,List<Mesh> approaches,List<Trim> trims){
    var trunk=List.copyOf(out);
    for(int arm=0;arm<approaches.size();arm++)for(int side:new int[]{-1,1}){
      var mesh=approaches.get(arm);var end=new End(arm,mesh.last(),side,spec.arms().get(arm).external().options().sidewalk());
      var road=spec.arms().get(arm);if(!end.enabled()||!road.crosswalk())continue;
      double ring=spec.kind()==JunctionSpec.Kind.ROUNDABOUT?spec.islandRadius()+spec.ringLanes()*spec.ringLaneWidth()-mesh.last().center().sub(spec.center()).horizontalLength():0;
      var at=RoadStructures.sample(mesh,Math.max(1,mesh.length()-ring-road.crossingSetback()-road.crossingWidth()/2));
      V inner=at.at(side*(at.halfWidth()+.45),0),outer=at.at(side*(at.halfWidth()+end.offset()),0);
      // A disconnected sidewalk already received its bent terminal and warning pad.
      if(trunk.stream().anyMatch(p->p.material()==Material.TACTILE&&p.pier()&&p.a().sub(inner).horizontalLength()<.3))continue;
      V join=outer;boolean cut=false;
      for(var trim:trims)if(trim.arm()==arm&&trim.side()==side&&outer.sub(trim.point()).dot(trim.forward())>0){cut=true;break;}
      if(cut){
        double nearest=Double.POSITIVE_INFINITY;
        for(var p:trunk)if(p.material()==Material.TACTILE&&p.height()<.018){
          V delta=p.b().sub(p.a());double t=Math.max(0,Math.min(1,inner.sub(p.a()).dot(delta)/Math.max(1e-12,delta.dot(delta))));
          V candidate=p.a().add(delta.mul(t)).sub(new V(0,.2,0));double distance=candidate.sub(inner).horizontalLength();
          if(distance<nearest){nearest=distance;join=candidate;}
        }
      }
      // An acute corner may put the nearest trunk beside the warning square,
      // rather than outside it. Enter the nearest whole edge; a fixed outer
      // cap can reverse its frame and twist the strip across the paving.
      V toward=join.sub(inner),axis=Math.abs(toward.dot(at.left()))>=Math.abs(toward.dot(at.left().left()))?at.left():at.left().left();
      axis=axis.mul(toward.dot(axis)<0?-1:1);
      V padEdge=inner.add(axis.mul(.3));
      V normal=toward.horizontalUnit().left().mul(-1),radialEdge=inner.add(toward.horizontalUnit().mul(.3));
      // Keep the established trunk connection exactly; only close the small
      // terminal wedge between the radial cap and the warning square.
      out.addAll(parts(List.of(join,radialEdge)));
      out.addAll(parts(List.of(radialEdge,padEdge),normal,axis.left().mul(-1)));warning(out,inner,at.left());
    }
  }
  private static void corner(List<Part> out,JunctionSpec spec,List<Mesh> approaches,List<V> edge,double sign,boolean insetBend,List<Trim> trims,List<V> roadBoundary){
    edge=RoadSidewalks.closeCorner(spec,approaches,edge);
    if(edge.size()<2)return;
    End first=nearest(spec,approaches,edge.get(0)),last=nearest(spec,approaches,edge.get(edge.size()-1));
    if(!first.enabled()&&!last.enabled())return;
    if(first.enabled()!=last.enabled()){
      crossingTerminal(out,spec,approaches,first.enabled()?first:last,trims);return;
    }
    if(spec.kind()==JunctionSpec.Kind.ROUNDABOUT){roundCorner(out,spec,approaches,first,last,trims);return;}
    if(tangentCorner(out,spec,approaches,edge,sign,first,last,trims,roadBoundary))return;
    var offset=new ArrayList<V>();double length=0,station=0;for(int i=1;i<edge.size();i++)length+=edge.get(i).distance(edge.get(i-1));
    for(int i=0;i<edge.size();i++){
      V normal;
      if(i==0)normal=first.normal();
      else if(i==edge.size()-1)normal=last.normal();
      else {
        V a=edge.get(i).sub(edge.get(i-1)).horizontalUnit().left().mul(sign),b=edge.get(i+1).sub(edge.get(i)).horizontalUnit().left().mul(sign);
        double den=1+a.dot(b);normal=den>.15?a.add(b).mul(1/den):b;
      }
      if(i>0)station+=edge.get(i).distance(edge.get(i-1));
      double t=Settings.smooth(station/Math.max(1e-9,length)),distance=first.offset()*(1-t)+last.offset()*t;
      offset.add(edge.get(i).add(normal.mul(distance)));
    }
    // Follow the paved outer corner. The previous independent inset arc could cut
    // across the roadway and trim away the matching approach, leaving a yellow gap.
    // Include the two approach rays when removing loops. At an acute corner the
    // outer offset may meet an approach before its mouth; trimming only the corner
    // left the approach's yellow tip sticking out as a triangular spur.
    V incoming=first.mouth().left().left().mul(-1),outgoing=last.mouth().left().left().mul(-1);
    double lead=Math.max(first.config().width(),last.config().width())*4+8;
    var complete=new ArrayList<V>();complete.add(offset.get(0).sub(incoming.mul(lead)));complete.addAll(offset);complete.add(offset.get(offset.size()-1).sub(outgoing.mul(lead)));
    List<V> joined=rounded(removeLoops(complete),.7);if(joined.size()<4)return;
    V begin=joined.get(1),finish=joined.get(joined.size()-2);
    trims.add(new Trim(first.arm(),first.side(),begin,incoming));trims.add(new Trim(last.arm(),last.side(),finish,outgoing));
    List<V> route=joined.subList(1,joined.size()-1);
    if(route.size()<2)return;
    // An unpaved/disabled end receives a clear warning pad; never continue a yellow line into it.
    if(first.enabled()!=last.enabled()){
      int mid=Math.max(1,route.size()/2);
      route=first.enabled()?route.subList(0,mid+1):route.subList(mid,route.size());
      V end=first.enabled()?route.get(route.size()-1):route.get(0);warning(out,end);
    }
    out.addAll(parts(route));
  }
  /** A sidewalk ending at an unpaved arm returns to its own crossing, not into grass. */
  private static void crossingTerminal(List<Part> out,JunctionSpec spec,List<Mesh> approaches,End end,List<Trim> trims){
    var arm=spec.arms().get(end.arm());var mesh=approaches.get(end.arm());
    double ring=spec.kind()==JunctionSpec.Kind.ROUNDABOUT?spec.islandRadius()+spec.ringLanes()*spec.ringLaneWidth()-mesh.last().center().sub(spec.center()).horizontalLength():0;
    double d=mesh.length()-ring-arm.crossingSetback()-(arm.crosswalk()?arm.crossingWidth()/2:1);
    Sample at=RoadStructures.sample(mesh,Math.max(1,d));V outer=at.at(end.side()*(at.halfWidth()+end.offset()),0),inner=at.at(end.side()*(at.halfWidth()+.45),0);
    V forward=at.left().left().mul(-1);
    // Trim at the start of the bend, not at the intersection of two square-ended strips.
    V padEdge=inner.add(outer.sub(inner).horizontalUnit().mul(.3));
    var turn=rounded(List.of(outer.sub(forward.mul(2)),outer,padEdge),.75);
    V begin=turn.get(1);trims.add(new Trim(end.arm(),end.side(),begin,forward));
    // The last curve chord is still oblique when a short bend consumes the
    // entire branch. Share the warning square's full edge instead of ending
    // with a rotated strip cap that leaves a triangular notch.
    out.addAll(parts(turn.subList(1,turn.size()),at.left(),at.left().left().mul(-end.side())));warning(out,inner,at.left());
  }

  private record Anchor(V point,Sample sample){}
  private static Anchor anchor(Mesh mesh,End end,V desired){
    V forward=end.mouth().left().left().mul(-1),outer=end.mouth().at(end.side()*(end.mouth().halfWidth()+end.offset()),0);
    double station=Math.max(.5,Math.min(mesh.length(),mesh.length()+desired.sub(outer).dot(forward)));
    var at=RoadStructures.sample(mesh,station);return new Anchor(at.at(end.side()*(at.halfWidth()+end.offset()),0),at);
  }
  /** Offset circular corners can invert when the sidewalk is wider than their radius.
   * Construct one tangent fillet between the two approach rows instead of following that hook. */
  private static boolean tangentCorner(List<Part> out,JunctionSpec spec,List<Mesh> approaches,List<V> edge,double sign,End first,End last,List<Trim> trims,List<V> roadBoundary){
    V u=first.mouth().left().left().mul(-1),v=last.mouth().left().left();
    double den=cross(u,v),dot=Math.max(-1,Math.min(1,u.dot(v))),angle=Math.atan2(den,dot),turn=Math.abs(angle);
    if(turn<Math.toRadians(3)||turn>Math.toRadians(175)||Math.abs(den)<1e-8)return false;
    // A reflex boundary already has an outward-safe parallel curve. A separate
    // fillet between mouth rays cuts inside the actual Bezier sidewalk and forces
    // the row inward to find support. Use the paved boundary offset below instead.
    if(angle*first.normal().dot(u.left())<0)return false;
    var paving=new ArrayList<>(RoadSidewalks.cornerPaving(spec,approaches,edge,sign));
    for(var e:List.of(first,last))paving.addAll(RoadSidewalks.parts(approaches.get(e.arm()),e.config()).stream().filter(part->part.material().name().startsWith("WALK_")).toList());
    // Use the true two offset rays. Reducing both to the narrower width and
    // blending the wider approach made a visible inward S-bend before the corner.
    double common=Math.min(first.offset(),last.offset());
    // If the tangent at the authored row is not supported, use the actual corner
    // boundary below. Do not invent a narrower inset row and weave inward then out.
    for(double inset:new double[]{0}){
      double offset=common-inset;if(offset<.65)continue;
      V p=first.mouth().at(first.side()*(first.mouth().halfWidth()+first.offset()),0),q=last.mouth().at(last.side()*(last.mouth().halfWidth()+last.offset()),0);
      V hit=p.add(u.mul(cross(q.sub(p),v)/den));
      for(double radius=Math.max(.85,spec.cornerRadius()-offset);radius>=.29;radius/=1.5){
        double distance=radius*Math.tan(turn/2);V begin=hit.sub(u.mul(distance)),end=hit.add(v.mul(distance));
        double leadA=0,leadB=0; // Tangency is solved between ACTUAL offset rows, not a narrower common row.
        var a=anchor(approaches.get(first.arm()),first,begin.sub(u.mul(leadA)));var b=anchor(approaches.get(last.arm()),last,end.add(v.mul(leadB)));
        if(begin.sub(a.point()).dot(u)<-.05||b.point().sub(end).dot(v)<-.05)continue;
        V center=begin.add(u.left().mul(Math.signum(angle)*radius));double start=Math.atan2(begin.z()-center.z(),begin.x()-center.x());
        var route=new ArrayList<V>();route.add(a.point());blend(route,a.point(),begin,u);
        int count=Math.max(12,(int)Math.ceil(radius*turn/.12));
        for(int k=1;k<count;k++){double fraction=k/(double)count,theta=start+angle*fraction;route.add(new V(center.x()+radius*Math.cos(theta),begin.y()+(end.y()-begin.y())*fraction,center.z()+radius*Math.sin(theta)));}
        route.add(end);blend(route,end,b.point(),v);
        var pieces=parts(route,a.sample().left(),b.sample().left().mul(-1));
        if(!supported(pieces,paving)){
          // Unequal-width tangent rows may just miss the old quadratic paving edge.
          // Supply the missing narrow backing only OUTSIDE the carriageways instead
          // of moving the yellow row inward and then back out.
          boolean safe=true;for(var part:pieces)if(part.height()<.018)for(V vertex:part.base()){
            if(JunctionPaint.inside(roadBoundary,vertex)||approaches.stream().anyMatch(m->RoadQueries.contains(m,vertex,.02,.5))){safe=false;break;}
          }
          if(!safe)continue;
          for(var part:pieces)if(part.height()<.018){
            var config=first.config();V down=new V(0,-.5,0);
            out.add(new Part(part.a().add(down),part.b().add(down),part.width()+.6,.5,false,RoadSidewalks.Finish.of(config.material()).material(),part.frameA().mul(2),part.frameB().mul(2)));
          }
        }
        trims.add(new Trim(first.arm(),first.side(),a.point(),a.sample().left().left().mul(-1)));
        trims.add(new Trim(last.arm(),last.side(),b.point(),b.sample().left().left().mul(-1)));
        out.addAll(pieces);return true;
      }
    }return false;
  }
  private static void blend(List<V> route,V a,V b,V direction){
    if(a.distance(b)<.005)return;
    double handle=Math.max(0,b.sub(a).dot(direction))/3;V c=a.add(direction.mul(handle)),d=b.sub(direction.mul(handle));
    int steps=Math.max(1,(int)Math.ceil(a.distance(b)/.3));
    for(int k=1;k<=steps;k++){double t=k/(double)steps,w=1-t;route.add(a.mul(w*w*w).add(c.mul(3*w*w*t)).add(d.mul(3*w*t*t)).add(b.mul(t*t*t)));}
  }
  private static boolean supported(List<Part> pieces,List<Part> paving){
    for(var p:pieces)if(p.height()<.018){var base=p.base();for(int i=0;i<base.size();i++){
      V a=base.get(i),b=base.get((i+1)%base.size());for(double t:new double[]{0,.5}){V at=a.mul(1-t).add(b.mul(t));if(paving.stream().noneMatch(w->JunctionPaint.inside(w.base(),at)))return false;}
    }}return true;
  }
  private static V circleJoin(JunctionSpec spec,Mesh mesh,End end){
    double radius=spec.islandRadius()+spec.ringLanes()*spec.ringLaneWidth()+end.offset();
    for(int i=1;i<mesh.samples().size();i++){
      var a=mesh.samples().get(i-1);var b=mesh.samples().get(i);
      V p=a.at(end.side()*(a.halfWidth()+end.offset()),0),q=b.at(end.side()*(b.halfWidth()+end.offset()),0);
      if(p.sub(spec.center()).horizontalLength()>=radius&&q.sub(spec.center()).horizontalLength()<=radius){
        double lo=0,hi=1;for(int k=0;k<40;k++){double t=(lo+hi)/2;V at=p.add(q.sub(p).mul(t));if(at.sub(spec.center()).horizontalLength()>radius)lo=t;else hi=t;}return p.add(q.sub(p).mul((lo+hi)/2));
      }
    }
    return end.mouth().at(end.side()*(end.mouth().halfWidth()+end.offset()),0);
  }
  private static void roundCorner(List<Part> out,JunctionSpec spec,List<Mesh> approaches,End first,End last,List<Trim> trims){
    V p=circleJoin(spec,approaches.get(first.arm()),first),q=circleJoin(spec,approaches.get(last.arm()),last);
    double a=Math.atan2(p.z()-spec.center().z(),p.x()-spec.center().x()),b=Math.atan2(q.z()-spec.center().z(),q.x()-spec.center().x());
    double turn=(b-a+Math.PI*2)%(Math.PI*2);if(turn>Math.PI*1.8)return;
    double r0=p.sub(spec.center()).horizontalLength(),r1=q.sub(spec.center()).horizontalLength();
    int count=Math.max(2,(int)Math.ceil(Math.max(r0,r1)*turn/.3));var route=new ArrayList<V>();route.add(p);
    for(int k=1;k<count;k++){double t=k/(double)count,r=r0+(r1-r0)*Settings.smooth(t),angle=a+turn*t;route.add(new V(spec.center().x()+r*Math.cos(angle),p.y()+(q.y()-p.y())*t,spec.center().z()+r*Math.sin(angle)));}route.add(q);
    if(first.enabled())trims.add(new Trim(first.arm(),first.side(),p,first.mouth().left().left().mul(-1)));
    if(last.enabled())trims.add(new Trim(last.arm(),last.side(),q,last.mouth().left().left().mul(-1)));
    if(first.enabled()!=last.enabled()){
      int middle=Math.max(1,route.size()/2);route=new ArrayList<>(first.enabled()?route.subList(0,middle+1):route.subList(middle,route.size()));warning(out,first.enabled()?route.get(route.size()-1):route.get(0));
    }
    out.addAll(parts(route));
  }
  /** A single tangent arc set back from an acute island tip, not a loop around its point. */
  public static List<V> insetBend(List<V> fallback,V incoming,V outgoing,double radius){
    if(fallback.size()<2)return fallback;
    V p=fallback.get(0),q=fallback.get(fallback.size()-1),u=incoming.horizontalUnit(),v=outgoing.horizontalUnit();
    double dot=Math.max(-1,Math.min(1,u.dot(v))),turn=Math.acos(dot),den=cross(u,v);
    if(turn<Math.toRadians(65)||turn>Math.toRadians(165)||Math.abs(den)<1e-8)return fallback;
    double before=cross(q.sub(p),v)/den,after=-cross(q.sub(p),u)/den;
    if(before<1||after<1)return fallback;
    double tangent=Math.min(radius*Math.tan(turn/2),Math.min(before,after)*.8);
    double actual=tangent/Math.tan(turn/2),sign=Math.signum(den);
    V tip=p.add(u.mul(before)),begin=tip.sub(u.mul(tangent)),end=tip.add(v.mul(tangent));
    V center=begin.add(u.left().mul(sign*actual));
    double angle=Math.atan2(begin.z()-center.z(),begin.x()-center.x());
    var out=new ArrayList<V>();out.add(p);out.add(begin);
    int count=Math.max(12,(int)Math.ceil(actual*turn/.15));
    for(int k=1;k<=count;k++){
      double t=(double)k/count,a=angle+sign*turn*t;
      out.add(new V(center.x()+actual*Math.cos(a),begin.y()+(end.y()-begin.y())*t,center.z()+actual*Math.sin(a)));
    }
    out.add(q);return List.copyOf(out);
  }
  public static List<Part> trimApproach(List<Part> parts,Mesh mesh,int arm,List<Trim> trims){
    var out=new ArrayList<Part>();
    for(var original:parts){
      Part p=original;
      if(p.material()==Material.TACTILE)for(var trim:trims)if(trim.arm()==arm){
        var projection=RoadQueries.horizontal(mesh,p.a().add(p.b()).mul(.5));
        if(projection.lateral()*trim.side()<=0)continue;
        double a=p.a().sub(trim.point()).dot(trim.forward()),b=p.b().sub(trim.point()).dot(trim.forward());
        if(a>=-1e-7&&b>=-1e-7){p=null;break;}
        if(a>0||b>0){
          double t=a/(a-b);V cut=p.a().add(p.b().sub(p.a()).mul(t));
          V fa=p.frameA(),fb=p.frameB(),frame=fa==null||fb==null?null:fa.add(fb.sub(fa).mul(t));
          p=a>0?new Part(cut,p.b(),p.width(),p.height(),p.pier(),p.material(),frame,fb,p.model())
               :new Part(p.a(),cut,p.width(),p.height(),p.pier(),p.material(),fa,frame,p.model());
        }
      }
      if(p!=null)out.add(p);
    }return List.copyOf(out);
  }
  public static List<V> removeLoops(List<V> source){
    var p=new ArrayList<>(source);int budget=p.size()*2;
    boolean changed=true;
    while(changed&&budget-->0){changed=false;
      outer:for(int i=0;i<p.size()-3;i++)for(int j=i+2;j<p.size()-1;j++){
        V a=p.get(i),u=p.get(i+1).sub(a),b=p.get(j),v=p.get(j+1).sub(b);
        double den=cross(u,v);if(Math.abs(den)<1e-9)continue;
        double t=cross(b.sub(a),v)/den,q=cross(b.sub(a),u)/den;
        if(t>=0&&t<=1&&q>=0&&q<=1){V hit=a.add(u.mul(t));for(int k=j;k>i;k--)p.remove(k);p.add(i+1,hit);changed=true;break outer;}
      }
    }
    var clean=new ArrayList<V>();for(V v:p)if(clean.isEmpty()||v.distance(clean.get(clean.size()-1))>.005)clean.add(v);
    return List.copyOf(clean);
  }
  private static double cross(V a,V b){return a.x()*b.z()-a.z()*b.x();}
  public static List<V> rounded(List<V> p,double radius){
    if(p.size()<3)return p;var out=new ArrayList<V>();out.add(p.get(0));
    for(int i=1;i<p.size()-1;i++){
      V a=p.get(i-1),b=p.get(i),c=p.get(i+1);
      V incoming=b.sub(a).horizontalUnit(),outgoing=c.sub(b).horizontalUnit();
      if(incoming.dot(outgoing)>.8){out.add(b);continue;}
      // Sampling density must not shrink the turning radius at an acute island tip.
      int before=i-1,after=i+1;double back=0,forward=0;
      while(before>0&&back+p.get(before).distance(p.get(before+1))<radius){back+=p.get(before).distance(p.get(before+1));before--;}
      while(after<p.size()-1&&forward+p.get(after).distance(p.get(after-1))<radius){forward+=p.get(after).distance(p.get(after-1));after++;}
      V ba=p.get(before),bb=p.get(before+1),fa=p.get(after-1),fb=p.get(after);
      double db=Math.min(radius-back,ba.distance(bb)),df=Math.min(radius-forward,fa.distance(fb));
      V begin=bb.add(ba.sub(bb).horizontalUnit().mul(db)),end=fa.add(fb.sub(fa).horizontalUnit().mul(df));
      while(out.size()>1&&out.get(out.size()-1).distance(b)<radius+.01)out.remove(out.size()-1);
      out.add(begin);int steps=Math.max(12,(int)Math.ceil((begin.distance(b)+b.distance(end))/.15));
      for(int k=1;k<=steps;k++){double t=(double)k/steps;out.add(begin.mul((1-t)*(1-t)).add(b.mul(2*t*(1-t))).add(end.mul(t*t)));}
      i=after-1;
    }out.add(p.get(p.size()-1));return List.copyOf(out);
  }
  public static List<Part> parts(List<V> route){
    return parts(route,null,null);
  }
  private static List<Part> parts(List<V> route,V firstNormal,V lastNormal){
    var out=new ArrayList<Part>();route=removeLoops(route);if(route.size()<2)return out;
    var normals=new ArrayList<V>();
    for(int i=0;i<route.size();i++){
      V a=route.get(i==0?0:i-1),b=route.get(i==route.size()-1?i:i+1);
      V tangent=b.sub(a);
      if(tangent.horizontalLength()<1e-8)tangent=i>0?route.get(i).sub(route.get(i-1)):route.get(1).sub(route.get(0));
      V normal=tangent.horizontalUnit().left();
      if(i>0&&i<route.size()-1){V incoming=route.get(i).sub(a).horizontalUnit().left(),outgoing=b.sub(route.get(i)).horizontalUnit().left();double den=1+incoming.dot(outgoing);if(den>.2)normal=incoming.add(outgoing).mul(1/den);}
      if(i==0&&firstNormal!=null)normal=firstNormal;if(i==route.size()-1&&lastNormal!=null)normal=lastNormal;
      normals.add(normal);
    }
    for(int i=1;i<route.size();i++){
      V a=route.get(i-1),b=route.get(i),na=normals.get(i-1),nb=normals.get(i);if(a.distance(b)<.005)continue;
      out.add(new Part(a.add(new V(0,.2,0)),b.add(new V(0,.2,0)),.6,.015,false,Material.TACTILE).frames(na.mul(.3),nb.mul(.3)));
      for(double r:new double[]{-.18,0,.18})out.add(new Part(a.add(na.mul(r)).add(new V(0,.215,0)),b.add(nb.mul(r)).add(new V(0,.215,0)),.06,.02,false,Material.TACTILE).frames(na.mul(.03),nb.mul(.03)));
    }return List.copyOf(out);
  }
  private static void warning(List<Part> out,V p){
    warning(out,p,new V(1,0,0));
  }
  private static void warning(List<Part> out,V p,V axis){
    V at=p.add(new V(0,.2,0)),normal=axis.left();out.add(new Part(at.sub(axis.mul(.3)),at.add(axis.mul(.3)),.6,.015,false,Material.TACTILE).frames(normal.mul(.3),normal.mul(.3)));
    for(double x:new double[]{-.18,0,.18})for(double z:new double[]{-.18,0,.18}){V q=p.add(axis.mul(x)).add(normal.mul(z)).add(new V(0,.215,0));out.add(new Part(q,q,.08,.02,true,Material.TACTILE));}
  }
  private TactilePaths(){}
}
