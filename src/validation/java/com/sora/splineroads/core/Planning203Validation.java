package com.sora.splineroads.core;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
public final class Planning203Validation {
 static int checks;
 static void ok(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
 public static void main(String[] args){
  Random random=new Random(203);int rejected=0,accepted=0;
  for(int c=0;c<6000;c++){
   int n=2+random.nextInt(150);double[] distance=new double[n],lo=new double[n],hi=new double[n],preferred=new double[n];var edges=new ArrayList<InterchangeGradeProfile.Edge>();
   for(int i=0;i<n;i++){
    if(i>0)distance[i]=distance[i-1]+.05+random.nextDouble()*5;
    double reach=i==0?0:(RoadGrades.MAX_RAMP_GRADE-1e-6)*(distance[i]-distance[i-1]);
    preferred[i]=i==0?random.nextDouble()*10:preferred[i-1]+(random.nextDouble()*2-1)*reach;
    lo[i]=preferred[i]-random.nextDouble()*2;hi[i]=preferred[i]+random.nextDouble()*2;
    if(i>0){edges.add(new InterchangeGradeProfile.Edge(i-1,i,-reach));edges.add(new InterchangeGradeProfile.Edge(i,i-1,-reach));}
   }
   if(c%3==0){int at=random.nextInt(n);lo[at]+=20;hi[at]+=20;}
   if(c%3==1)for(int i=1;i<n-1;i++){lo[i]=Double.NEGATIVE_INFINITY;hi[i]=Double.POSITIVE_INFINITY;}
   boolean pruned=false,full=false;
   double[] paddedLo=new double[n+4],paddedHi=new double[n+4];System.arraycopy(lo,0,paddedLo,2,n);System.arraycopy(hi,0,paddedHi,2,n);
   try{InterchangePlanner.checkLocalGradeBounds(paddedLo,paddedHi,2,distance);}catch(IllegalArgumentException expected){pruned=true;}
   try{InterchangeGradeProfile.solve(preferred,lo.clone(),hi.clone(),edges);full=true;}catch(IllegalArgumentException expected){}
   ok(!(pruned&&full),"early prune discarded full-solver accepted graph c="+c);
   if(pruned)rejected++;else accepted++;
  }
  ok(rejected>1000&&accepted>3000,"missing positive/negative controls");
  var first=new Plan(List.of(),0,0,0,new V(0,0,0));var second=new Plan(List.of(),0,0,0,new V(1,0,0));
  Plan returned=InterchangeFit.refining(r->{r.keep(first);r.keep(second);while(true)RoadPlanningBudget.check();});ok(returned==second,"lost newest complete plan");
  boolean propagated=false;try(var outer=RoadPlanningBudget.open("outer",.01)){try{InterchangeFit.refining(r->{r.keep(first);while(true)RoadPlanningBudget.check();});}catch(RoadPlanningBudget.Expired expected){propagated=true;}}ok(propagated,"outer deadline swallowed");
  propagated=false;try(var outer=RoadPlanningBudget.open("no-valid-plan",.01)){try{InterchangeFit.refining(r->{while(true)RoadPlanningBudget.check();});}catch(RoadPlanningBudget.Expired expected){propagated=true;}}ok(propagated,"deadline without valid plan returned fallback");
  AtomicBoolean cancel=new AtomicBoolean();propagated=false;try(var outer=RoadPlanningBudget.cancellable("cancel",cancel::get)){try{InterchangeFit.refining(r->{r.keep(first);cancel.set(true);RoadPlanningBudget.check();return first;});}catch(RoadPlanningBudget.Aborted expected){propagated=!(expected instanceof RoadPlanningBudget.Expired);}}ok(propagated,"user cancellation swallowed");
  propagated=false;try{InterchangeFit.refining(r->{throw new IllegalArgumentException("no-feasible-plan");});}catch(IllegalArgumentException expected){propagated=true;}ok(propagated,"infeasible returned success");
  propagated=false;try{InterchangeFit.refining(r->{r.keep(first);Thread.currentThread().interrupt();RoadPlanningBudget.check();return first;});}catch(RoadPlanningBudget.Aborted expected){propagated=true;}finally{Thread.interrupted();}ok(propagated,"interruption swallowed");
  RoadPlanningBudget.check();System.out.println("Planning203Validation PASS checks="+checks+" pruned="+rejected+" feasible_or_unpruned="+accepted+" budget/outer/cancel/no-solution/interruption=passed");
 }
}
