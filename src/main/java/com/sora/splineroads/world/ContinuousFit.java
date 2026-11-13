package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import java.util.*;
import java.util.function.Function;
import net.minecraft.nbt.*;

/** Same geometry validator for selected ranges, neighbouring ranges and independent automatic ends. */
final class ContinuousFit {
  static V center(Node[] nodes,Options options){
    Node[] expanded=nodes.length==3?InterchangePlanner.expand(nodes,options):nodes;
    V a=expanded[0].position(),u=expanded[1].position().sub(a).horizontalUnit(),b=expanded[2].position(),v=expanded[3].position().sub(b).horizontalUnit();double det=u.x()*v.z()-u.z()*v.x();
    if(Math.abs(det)<.3)throw new IllegalArgumentException("主路夹角不足，无法计算连续范围");double distance=((b.x()-a.x())*v.z()-(b.z()-a.z())*v.x())/det;return a.add(u.mul(distance));
  }
  private static CompoundTag candidate(CompoundTag t,Node[] nodes,Options options){var out=t.copy();var list=new ListTag();for(var n:nodes)list.add(RoadRecord.writeNode(n));out.put("Nodes",list);out.put("Options",Interchanges.write(options));out.remove("HostAxes");out.remove("ResolvedFit");return out;}
  private static Node at(Node reference,V center,double radius){V p=center.add(reference.position().sub(center).horizontalUnit().mul(radius));return new Node(new V(p.x(),reference.position().y(),p.z()),reference.yaw(),reference.grade());}
  static Plan plan(CompoundTag t,Function<CompoundTag,Plan> base){
    var options=Interchanges.read(t.getCompound("Options"));var original=HostAxes.nodes(t);var fixed=options.adjust(false);V center=center(original,options);
    double minimum=original.length>=5?70:32;double maximum=options.preset()==Preset.DOUBLE_TRUMPET?2048:1024;
    IllegalArgumentException initial;Plan p;
    try{p=base.apply(candidate(t,original,fixed));return tighten(t,base,p,original,center,minimum,fixed);}
    catch(IllegalArgumentException failure){initial=failure;}
    // Long authored roads can contain the required occupied interval even when their outer
    // endpoints exceed the geometry planner's radius bound. Search inside selected roads first.
    p=within(t,base,original,center,minimum,maximum,fixed);
    if(p!=null)return tighten(t,base,p,original,center,minimum,fixed);
    Node[] available=ContinuousRoads.effectiveLimits(t);
    if(!Arrays.equals(original,available)){
      p=within(t,base,available,center,minimum,maximum,fixed);
      if(p!=null)return tighten(t,base,p,available,center,minimum,fixed);
    }
    if(!options.adjustEndpoints())throw initial;
    // Start outside adjustment from the farthest usable neighbour, never from an inner
    // selected seam. Core fitting restores each sufficient end independently.
    p=base.apply(candidate(t,available,options.shrink(false)));
    // After moving only the necessary outer ends, cut unused existing pavement from
    // the managed range as well. A valid interior cut preserves its old outer marker.
    return tighten(t,base,p,available,center,minimum,fixed);
  }
  private static boolean captured(CompoundTag t,int i){return ContinuousRoads.sources(t).stream().anyMatch(s->s.axis()==i/2);}
  private static Plan within(CompoundTag t,Function<CompoundTag,Plan> base,Node[] bounds,V center,double minimum,double maximum,Options options){
    double far=Arrays.stream(bounds).mapToDouble(n->n.position().sub(center).horizontalLength()).max().orElse(minimum);far=Math.min(far,maximum);
    for(double radius=minimum;radius<=far+1e-6;radius=Math.min(far,Math.max(radius+16,radius*1.2))){
      RoadPlanningBudget.check();Node[] candidate=bounds.clone();for(int i=0;i<candidate.length;i++)if(captured(t,i))candidate[i]=at(bounds[i],center,Math.min(radius,bounds[i].position().sub(center).horizontalLength()));
      try{return base.apply(candidate(t,candidate,options));}catch(IllegalArgumentException ignored){}
      if(radius>=far-1e-6)break;
    }return null;
  }
  private static Node fittedAt(Node reference,Node fitted,V center,double radius){
    V p=reference.position();return at(new Node(new V(p.x(),fitted.position().y(),p.z()),fitted.yaw(),fitted.grade()),center,radius);
  }
  private static Plan tighten(CompoundTag t,Function<CompoundTag,Plan> base,Plan best,Node[] reference,V center,double minimum,Options options){
    // Coordinate search retains a fully validated incumbent. Each end has its own lower
    // failure bound; no common interpolation fraction forces the opposite end to move.
    // Refinement is optional only after a complete, validated feasible plan exists.
    // A pathological failed candidate must not hide that usable preview indefinitely.
    try(var refinement=RoadPlanningBudget.open("立交有效方案范围收紧",3)){
    for(int pass=0;pass<2;pass++)for(int i=0;i<reference.length;i++){
      if(!captured(t,i))continue;
      double high=best.anchors().get(i).position().sub(center).horizontalLength(),low=minimum;if(high<=low+.5)continue;
      Node[] trial=best.anchors().toArray(Node[]::new);trial[i]=fittedAt(reference[i],best.anchors().get(i),center,low);
      try{best=base.apply(candidate(t,trial,options));continue;}catch(IllegalArgumentException ignored){}
      for(int iteration=0;iteration<10&&high-low>.5;iteration++){
        RoadPlanningBudget.check();double mid=(low+high)/2;trial=best.anchors().toArray(Node[]::new);trial[i]=fittedAt(reference[i],best.anchors().get(i),center,mid);
        try{best=base.apply(candidate(t,trial,options));high=mid;}catch(IllegalArgumentException ignored){low=mid;}
      }
    }}catch(RoadPlanningBudget.Expired expired){
      // Re-check the enclosing scope: a user cancellation or an outer deadline must
      // propagate instead of being mistaken for an optional refinement timeout.
      RoadPlanningBudget.check();
    }return best;
  }
  private ContinuousFit(){}
}
