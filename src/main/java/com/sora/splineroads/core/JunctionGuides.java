package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.RoadSurface.Face;
import java.util.*;

/** Boundaries sharing an exit merge into one tangent stem with one dash phase. */
final class JunctionGuides {
  record Guide(int exit,int divider,List<V> path) {}
  static void paint(List<Face> out,List<Guide> input){
    var groups=new LinkedHashMap<String,List<Guide>>();
    for(var g:input)groups.computeIfAbsent(g.exit()+":"+g.divider(),k->new ArrayList<>()).add(g);
    for(var group:groups.values()){
      var primary=group.stream().min(Comparator.comparingDouble(g->length(g.path()))).orElseThrow();
      List<V> trunk=primary.path();JunctionPaint.path(out,trunk,.14,true,JunctionPaint.WHITE);
      for(var g:group){if(g==primary)continue;
        List<V> path=g.path();int join=trunk.size()-1;double tail=0;
        while(join>1&&tail<Math.min(16,length(trunk)*.35)){tail+=trunk.get(join).distance(trunk.get(join-1));join--;}
        V end=trunk.get(join),tangent=trunk.get(join+1).sub(end).horizontalUnit();
        int cut=1;double nearest=Double.POSITIVE_INFINITY;
        for(int i=1;i<path.size()-2;i++){double d=path.get(i).distance(end);if(d<nearest){nearest=d;cut=i;}}
        cut=Math.max(1,cut-3);var branch=new ArrayList<>(path.subList(0,cut+1));
        V a=path.get(cut),forward=a.sub(path.get(cut-1)).horizontalUnit();double handle=Math.min(8,a.distance(end)*.4);
        V b=a.add(forward.mul(handle)),c=end.sub(tangent.mul(handle));
        int steps=Math.max(8,(int)Math.ceil(a.distance(end)/.3));
        for(int i=1;i<=steps;i++){double t=i/(double)steps,u=1-t;branch.add(a.mul(u*u*u).add(b.mul(3*u*u*t)).add(c.mul(3*u*t*t)).add(end.mul(t*t*t)));}
        double at=length(trunk.subList(0,join+1));
        JunctionPaint.path(out,branch,.14,true,JunctionPaint.WHITE,at-length(branch));
      }
    }
  }
  private static double length(List<V> path){double d=0;for(int i=1;i<path.size();i++)d+=path.get(i).distance(path.get(i-1));return d;}
  private JunctionGuides(){}
}
