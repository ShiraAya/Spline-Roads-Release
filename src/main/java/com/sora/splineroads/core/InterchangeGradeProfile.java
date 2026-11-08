package com.sora.splineroads.core;

import java.util.*;

/** Local crossing bounds on a continuous ramp. Unoccupied bends have no deck-height target. */
final class InterchangeGradeProfile {
  record Edge(int from,int to,double rise) {}
  /** Every route is monotone when possible, otherwise has one crest or valley.
   * Direction constraints are solved with the crossing graph, never applied as
   * a cosmetic pass that could put a smoothed road back through another deck. */
  static double[] solve(double[] preferred,double[] lower,double[] upper,List<Edge> source,int[] offsets,double[][] distance) {
    var strongest=new LinkedHashMap<Long,Edge>();
    for(var e:source){long key=((long)e.from()<<32)|(e.to()&0xffffffffL);
      var old=strongest.get(key);if(old==null||e.rise()>old.rise())strongest.put(key,e);}
    var edges=new ArrayList<>(strongest.values());
    // The usual case needs no extrema. Solve all monotone routes together,
    // rather than rebuilding the same constraint graph once per ramp.
    var allMonotone=new ArrayList<Edge>();
    for(int r=0;r<offsets.length;r++){
      int start=offsets[r],end=start+distance[r].length-1;
      allMonotone.addAll(shape(start,end,end,preferred[end]>=preferred[start]?1:-1));
    }
    var monotoneEdges=new ArrayList<>(edges);monotoneEdges.addAll(allMonotone);
    try{return stretch(solve(preferred,lower.clone(),upper.clone(),monotoneEdges,true),lower,upper,monotoneEdges,offsets,distance);}
    catch(IllegalArgumentException requiresExtremum){/* continue with bounded shape search */}
    double[] y=solve(preferred,lower.clone(),upper.clone(),edges,false);
    for(int r=0;r<offsets.length;r++){
      int start=offsets[r],end=start+distance[r].length-1;
      int direction=preferred[end]>=preferred[start]?1:-1;
      var monotone=shape(start,end,end,direction);
      double[] candidate=attempt(preferred,lower,upper,edges,monotone);
      if(candidate!=null){edges.addAll(monotone);y=candidate;continue;}
      // Try the smallest unavoidable extremum first. Endpoints and all crossing
      // inequalities remain fixed, so an infeasible single-turn shape is rejected.
      int high=start,low=start;
      for(int i=start+1;i<=end;i++){if(y[i]>y[high])high=i;if(y[i]<y[low])low=i;}
      boolean crest=y[high]-Math.max(y[start],y[end])>=Math.min(y[start],y[end])-y[low];
      boolean found=false;
      for(int sign:new int[]{crest?1:-1,crest?-1:1}){
        int extreme=sign>0?high:low;
        var choices=new LinkedHashSet<Integer>();choices.add(extreme);
        int last=extreme;for(int i=extreme+1;i<end;i++)if(Math.abs(y[i]-y[extreme])<1e-6)last=i;
        choices.add((extreme+last)/2);choices.add(last);
        for(int peak:choices){if(peak<=start||peak>=end)continue;
          var shape=shape(start,end,peak,sign);candidate=attempt(preferred,lower,upper,edges,shape);
          if(candidate!=null){edges.addAll(shape);y=candidate;found=true;break;}}
        if(found)break;
      }
      if(!found)throw new IllegalArgumentException("实际交叉处无法以单调或一次变坡连接，请扩大范围或调整跨越关系");
    }
    return stretch(solve(preferred,lower.clone(),upper.clone(),edges,true),lower,upper,edges,offsets,distance);
  }
  /** Use the complete free distance between actual constraints. Local relaxation
   * alone diffuses only a few samples and leaves long artificial level landings. */
  private static double[] stretch(double[] feasible,double[] lower,double[] upper,List<Edge> edges,int[] offsets,double[][] distance){
    double[] y=feasible.clone();
    for(int pass=0;pass<2;pass++)for(int r=0;r<offsets.length;r++){
      int start=offsets[r],n=distance[r].length,end=start+n;
      double[] lo=Arrays.copyOfRange(lower,start,end),hi=Arrays.copyOfRange(upper,start,end);
      // Other ramps are held at their already feasible heights during this move.
      // Consecutive local slope/shape edges are checked before accepting it.
      for(var e:edges){boolean a=e.from()>=start&&e.from()<end,b=e.to()>=start&&e.to()<end;
        if(a&&b&&Math.abs(e.to()-e.from())==1)continue;
        if(b)lo[e.to()-start]=Math.max(lo[e.to()-start],y[e.from()]+e.rise());
        if(a)hi[e.from()-start]=Math.min(hi[e.from()-start],y[e.to()]-e.rise());}
      double[] candidate=Arrays.copyOfRange(y,start,end);
      var ranges=new ArrayDeque<int[]>();ranges.add(new int[]{0,n-1});
      while(!ranges.isEmpty()){
        var range=ranges.remove();int a=range[0],b=range[1],pin=-1;double worst=1e-7,target=0;
        for(int i=a+1;i<b;i++){
          double t=(distance[r][i]-distance[r][a])/(distance[r][b]-distance[r][a]);
          double value=candidate[a]*(1-t)+candidate[b]*t;
          double bound=Math.max(lo[i],Math.min(hi[i],value)),error=Math.abs(bound-value);
          if(error>worst){worst=error;pin=i;target=bound;}}
        if(pin<0){for(int i=a+1;i<b;i++){double t=(distance[r][i]-distance[r][a])/(distance[r][b]-distance[r][a]);candidate[i]=candidate[a]*(1-t)+candidate[b]*t;}}
        else {candidate[pin]=target;ranges.add(new int[]{a,pin});ranges.add(new int[]{pin,b});}
      }
      boolean valid=true;
      for(int i=0;i<n;i++)if(candidate[i]<lo[i]-1e-7||candidate[i]>hi[i]+1e-7){valid=false;break;}
      for(var e:edges)if(valid){double a=e.from()>=start&&e.from()<end?candidate[e.from()-start]:y[e.from()];double b=e.to()>=start&&e.to()<end?candidate[e.to()-start]:y[e.to()];if(b<a+e.rise()-1e-7)valid=false;}
      if(valid)System.arraycopy(candidate,0,y,start,n);
      RoadPlanningBudget.check();
    }
    return y;
  }
  private static List<Edge> shape(int start,int end,int peak,int direction){
    var edges=new ArrayList<Edge>(end-start);
    for(int i=start+1;i<=end;i++){int sign=i<=peak?direction:-direction;
      edges.add(sign>0?new Edge(i-1,i,0):new Edge(i,i-1,0));}
    return edges;
  }
  private static double[] attempt(double[] preferred,double[] lower,double[] upper,List<Edge> base,List<Edge> extra){
    var all=new ArrayList<>(base);all.addAll(extra);
    try{return solve(preferred,lower.clone(),upper.clone(),all,false);}catch(IllegalArgumentException rejected){return null;}
  }
  static double[] solve(double[] preferred,double[] lower,double[] upper,List<Edge> edges) {
    return solve(preferred,lower,upper,edges,true);
  }
  private static double[] solve(double[] preferred,double[] lower,double[] upper,List<Edge> edges,boolean fair) {
    int n=preferred.length;
    var incoming=new ArrayList<List<Edge>>(n);var outgoing=new ArrayList<List<Edge>>(n);
    for(int i=0;i<n;i++){incoming.add(new ArrayList<>());outgoing.add(new ArrayList<>());}
    for(var e:edges){outgoing.get(e.from()).add(e);incoming.get(e.to()).add(e);}
    // y[to] >= y[from] + rise. The reverse pass computes the greatest
    // feasible ceiling imposed by every fixed mouth, including future ramps.
    var queue=new ArrayDeque<Integer>();boolean[] queued=new boolean[n];int[] visits=new int[n];
    for(int i=0;i<n;i++)if(Double.isFinite(upper[i])){queue.add(i);queued[i]=true;}
    while(!queue.isEmpty()){
      int i=queue.remove();queued[i]=false;
      if(++visits[i]>n)throw new IllegalArgumentException("交叉高程约束成环，请扩大范围");
      for(var e:incoming.get(i))if(upper[e.from()]>upper[i]-e.rise()+1e-8){
        upper[e.from()]=upper[i]-e.rise();
        if(upper[e.from()]<lower[e.from()]-1e-7){
          throw new IllegalArgumentException("实际交叉处的净高与 15% 纵坡无法同时满足，请扩大范围");}
        if(!queued[e.from()]){queue.add(e.from());queued[e.from()]=true;}
      }
      if((i&255)==0)RoadPlanningBudget.check();
    }
    double[] y=new double[n];
    for(int i=0;i<n;i++){
      if(lower[i]>upper[i]+1e-7)throw new IllegalArgumentException("交叉处净高不足，请扩大范围");
      y[i]=Math.max(lower[i],Math.min(upper[i],preferred[i]));queue.add(i);queued[i]=true;
    }
    Arrays.fill(visits,0);
    while(!queue.isEmpty()){
      int i=queue.remove();queued[i]=false;
      if(++visits[i]>n)throw new IllegalArgumentException("交叉高程约束成环，请扩大范围");
      for(var e:outgoing.get(i))if(y[e.to()]<y[i]+e.rise()-1e-8){
        y[e.to()]=y[i]+e.rise();
        if(y[e.to()]>upper[e.to()]+1e-7)throw new IllegalArgumentException("实际交叉处的净高与 15% 纵坡无法同时满足，请扩大范围");
        if(!queued[e.to()]){queue.add(e.to());queued[e.to()]=true;}
      }
      if((i&255)==0)RoadPlanningBudget.check();
    }
    // Local fairing preserves every pairwise constraint, including pavement edges.
    for(int pass=0;pass<(fair?32:0);pass++)for(int k=1;k<n-1;k++){
      if((k&255)==0)RoadPlanningBudget.check();
      int i=(pass&1)==0?k:n-1-k;
      if(lower[i]==upper[i])continue;
      double lo=lower[i],hi=upper[i];
      for(var e:incoming.get(i))lo=Math.max(lo,y[e.from()]+e.rise());
      for(var e:outgoing.get(i))hi=Math.min(hi,y[e.to()]-e.rise());
      y[i]=Math.max(lo,Math.min(hi,(y[i-1]+y[i+1])*.5));
    }
    return y;
  }
  static int floor(double[] distance,double value){int i=Arrays.binarySearch(distance,value);return i>=0?i:Math.max(0,-i-2);}
  static int ceil(double[] distance,double value){int i=Arrays.binarySearch(distance,value);return i>=0?i:Math.min(distance.length-1,-i-1);}
  private InterchangeGradeProfile(){}
}
