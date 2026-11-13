package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Optional, bounded endpoint adjustment. World permissions are checked again at commit. */
public final class InterchangeFit {
  public static InterchangePlanner.Plan fit(
      Node[] original, Settings a, Settings b, InterchangePlanner.Options options) {
    return fit(original,a,b,options,null);
  }
  public static InterchangePlanner.Plan fit(Node[] original,Settings a,Settings b,InterchangePlanner.Options options,java.util.function.UnaryOperator<V> conformance){
    return refining(refinement->fit(original,a,b,options,conformance,refinement));
  }
  /** One optional budget starts only when the first complete valid plan exists. */
  static final class Refinement implements AutoCloseable {
    InterchangePlanner.Plan best;private RoadPlanningBudget budget;
    InterchangePlanner.Plan keep(InterchangePlanner.Plan plan){
      best=plan;if(budget==null)budget=RoadPlanningBudget.open("立交有效方案端点收紧",3);return plan;
    }
    @Override public void close(){if(budget!=null)budget.close();}
  }
  static InterchangePlanner.Plan refining(java.util.function.Function<Refinement,InterchangePlanner.Plan> work){
    var refinement=new Refinement();
    try(refinement){return work.apply(refinement);}
    catch(RoadPlanningBudget.Expired expired){
      // The local scope has closed: outer timeout/cancellation must still propagate.
      RoadPlanningBudget.check();if(refinement.best!=null)return refinement.best;throw expired;
    }
  }
  private static InterchangePlanner.Plan fit(Node[] original,Settings a,Settings b,InterchangePlanner.Options options,
      java.util.function.UnaryOperator<V> conformance,Refinement refinement){
    RoadPlanningBudget.check();
    var fixed = options.adjust(false);
    IllegalArgumentException initial = new IllegalArgumentException("未找到符合净空、坡度和半径的布局");
    InterchangePlanner.Plan fallback = null;
    try {
      var originalPlan = refinement.keep(InterchangePlanner.plan(original, a, b, fixed,conformance));
      if (!options.allowShrink()) return originalPlan;
      fallback = originalPlan;
    } catch (IllegalArgumentException e) {
      initial = e;
    }
    Node[] expanded = InterchangePlanner.expand(original, fixed);
    V u = expanded[1].position().sub(expanded[0].position()).horizontalUnit();
    V v = expanded[3].position().sub(expanded[2].position()).horizontalUnit();
    double det = cross(u, v);
    if (Math.abs(det) < .5) {if(fallback!=null)return fallback;throw initial;}
    V center =
        expanded[0]
            .position()
            .add(u.mul(cross(expanded[2].position().sub(expanded[0].position()), v) / det));
    double ya = (expanded[0].position().y() + expanded[1].position().y()) / 2;
    double yb = (expanded[2].position().y() + expanded[3].position().y()) / 2;
    boolean abUpper = Math.abs(ya - yb) < 1e-6 ? options.upper() == 1 : ya > yb;
    double bottom = Math.min(ya, yb), gap = InterchangePlanner.minimumDifference(a, b, fixed);
    double allowedDrop = options.allowShrink() && !options.preset().three ? options.maxLowering() : 0;
    double upper = Math.max(bottom + gap, Math.max(ya, yb) - allowedDrop);
    if(options.flatConnector())upper=Math.max(upper,options.connectorHeight());
    Node[] leveled = original.clone();
    for (int i = 0; i < leveled.length; i++) {
      RoadPlanningBudget.check();
      V p = original[i].position();
      if((i<2)!=abUpper){leveled[i]=original[i];continue;}
      double y = upper;
      // A single horizontal elevation per axis, bounded against every original endpoint.
      for (int j = i < 2 ? 0 : 2; j < Math.min(original.length, i < 2 ? 2 : 4); j++)
        y = Math.max(y, original[j].position().y() - allowedDrop);
      leveled[i] = new Node(new V(p.x(), y, p.z()), original[i].yaw(), 0);
    }
    if(!Arrays.equals(original,leveled))try {
      var leveledPlan = refinement.keep(InterchangePlanner.plan(leveled, a, b, fixed,conformance));
      if (!options.allowShrink()) return leveledPlan;
      fallback = leveledPlan;
    } catch (IllegalArgumentException failure) {
      initial = failure;
      if(Boolean.getBoolean("sr.fitDebug"))System.err.println("Leveled fit: "+failure.getMessage());
    }
    double shortest = Double.POSITIVE_INFINITY;
    for (Node node : leveled)
      shortest = Math.min(shortest, node.position().sub(center).horizontalLength());
    if(fixed.preset()==InterchangePlanner.Preset.DOUBLE_TRUMPET){
      var local=doubleTrumpet(leveled,center,a,b,fixed,conformance,options.allowShrink(),refinement);
      if(local!=null)return restoreThroughEnds(local,leveled,center,a,b,fixed,conformance,refinement);
    }
    double failed = options.allowShrink() ? 45 : Math.max(45, shortest), target = failed;
    double limit = fixed.preset() == InterchangePlanner.Preset.DOUBLE_TRUMPET ? 2048 : 1024;
    InterchangePlanner.Plan best = null;
    for (int tries = 0; tries < 20 && target < limit; tries++) {
      RoadPlanningBudget.check();
      target = Math.min(limit, Math.ceil(Math.max(target + 16, target * 1.18) / 4) * 4);
      try {
        best =
            refinement.keep(InterchangePlanner.plan(
                extend(leveled, center, target, options.allowShrink()), a, b, fixed,conformance));
        break;
      } catch (IllegalArgumentException failure) {
        initial = failure;
        if(Boolean.getBoolean("sr.fitDebug"))System.err.println("Fit distance="+target+": "+failure.getMessage());
        failed = target;
      }
    }
    if (best == null) {
      // Shrinking is an optional optimization. Preserve a fully validated original
      // or leveled candidate when none of the bounded smaller search points works.
      if(fallback!=null)return fallback;
      throw new IllegalArgumentException(
          "自动调整端点后仍不满足要求：" + initial.getMessage() + "；请调整主路夹角、宽度或预设");
    }
    // Refine the successful range with at most eight extra pure geometry checks.
    for (int i = 0; i < 8 && target - failed > 4; i++) {
      RoadPlanningBudget.check();
      double mid = Math.ceil((target + failed) / 8) * 4;
      if (mid >= target) break;
      try {
        var result =
            refinement.keep(InterchangePlanner.plan(
                extend(leveled, center, mid, options.allowShrink()), a, b, fixed,conformance));
        best = result;
        target = mid;
      } catch (IllegalArgumentException ignored) {
        failed = mid;
      }
    }
    best=restoreThroughEnds(best,leveled,center,a,b,fixed,conformance,refinement);
    return best;
  }

  private static InterchangePlanner.Plan doubleTrumpet(Node[] nodes,V center,Settings a,Settings b,
      InterchangePlanner.Options fixed,java.util.function.UnaryOperator<V> conformance,boolean shrink,Refinement refinement){
    double failed=shrink?45:Math.max(45,Math.min(nodes[0].position().sub(center).horizontalLength(),nodes[2].position().sub(center).horizontalLength())),target=failed;
    InterchangePlanner.Plan best=null;
    for(int attempt=0;attempt<20&&target<2048;attempt++){
      RoadPlanningBudget.check();target=Math.min(2048,Math.ceil(Math.max(target+16,target*1.18)/4)*4);
      try{best=refinement.keep(InterchangePlanner.plan(trumpetEnds(nodes,center,target,shrink),a,b,fixed,conformance));break;}
      catch(IllegalArgumentException rejected){failed=target;}
    }
    if(best==null)return null;
    for(int i=0;i<8&&target-failed>4;i++){
      RoadPlanningBudget.check();double mid=Math.ceil((target+failed)/8)*4;if(mid>=target)break;
      try{best=refinement.keep(InterchangePlanner.plan(trumpetEnds(nodes,center,mid,shrink),a,b,fixed,conformance));target=mid;}
      catch(IllegalArgumentException rejected){failed=mid;}
    }
    return best;
  }
  private static Node[] trumpetEnds(Node[] nodes,V center,double target,boolean shrink){
    var out=extend(nodes,center,target,shrink);
    for(int i:new int[]{1,3}){
      double original=nodes[i].position().sub(center).horizontalLength();
      if(!shrink||target>=original)out[i]=nodes[i];
    }
    return out;
  }
  private static InterchangePlanner.Plan restoreThroughEnds(InterchangePlanner.Plan best,Node[] original,V center,
      Settings a,Settings b,InterchangePlanner.Options fixed,java.util.function.UnaryOperator<V> conformance,Refinement refinement){
    // If a through end really needed room, retain only the extension which still
    // passes the same complete geometry checks; never impose visual symmetry.
    for(int index=0;index<original.length;index++){
      if(best.anchors().get(index).position().sub(center).horizontalLength()<=original[index].position().sub(center).horizontalLength()+1e-7)continue;
      Node[] candidate=best.anchors().toArray(Node[]::new);candidate[index]=original[index];
      try{best=refinement.keep(InterchangePlanner.plan(candidate,a,b,fixed,conformance));continue;}
      catch(IllegalArgumentException rejected){}
      double low=original[index].position().sub(center).horizontalLength();
      double high=best.anchors().get(index).position().sub(center).horizontalLength();
      for(int i=0;i<8&&high-low>4;i++){
        RoadPlanningBudget.check();double mid=Math.ceil((low+high)/8)*4;if(mid>=high)break;
        candidate=best.anchors().toArray(Node[]::new);
        V p=original[index].position(),delta=p.sub(center).horizontalUnit();
        V at=center.add(delta.mul(mid));candidate[index]=new Node(new V(at.x(),p.y(),at.z()),original[index].yaw(),0);
        try{best=refinement.keep(InterchangePlanner.plan(candidate,a,b,fixed,conformance));high=mid;}
        catch(IllegalArgumentException rejected){low=mid;}
      }
    }
    return best;
  }

  private static Node[] extend(Node[] nodes, V center, double distance, boolean shrink) {
    Node[] out = nodes.clone();
    for (int i = 0; i < out.length; i++) {
      RoadPlanningBudget.check();
      V delta = nodes[i].position().sub(center);
      double length = delta.horizontalLength();
      if (length < 1e-6) throw new IllegalArgumentException("端点不能位于交点");
      V p = center.add(delta.horizontalUnit().mul(shrink ? distance : Math.max(length, distance)));
      out[i] = new Node(new V(p.x(), nodes[i].position().y(), p.z()), nodes[i].yaw(), 0);
    }
    return out;
  }

  private static double cross(V a, V b) {
    return a.x() * b.z() - a.z() * b.x();
  }

  private InterchangeFit() {}
}
