package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;import java.util.*;
public class HighwayGuides201Validation {
 static boolean marked(List<RoadSurface.Face> faces,V point){var box=List.of(point.add(new V(-.015,0,-.015)),point.add(new V(.015,0,-.015)),point.add(new V(.015,0,.015)),point.add(new V(-.015,0,.015)));return faces.stream().anyMatch(p->RoadSurface.area(RoadSurface.intersect(p.points(),box))>1e-8);}
 public static void main(String[]args){int checks=0;for(boolean left:new boolean[]{false,true})for(int count:new int[]{1,2}){var plan=Interchange201Probe.plan(left,count);var roads=plan.legs().stream().map(InterchangePlanner.Leg::mesh).toList();
 for(int hostIndex=0;hostIndex<2;hostIndex++){var host=roads.get(hostIndex);var all=roads.stream().filter(m->m!=host).toList();var surface=RoadSurface.build(host,List.of(),all);
 for(var leg:plan.legs())if(leg.name().startsWith("共用")){var ramp=leg.mesh();var at=RoadStructures.sample(ramp,ramp.length()*.5);var q=RoadQueries.horizontal(host,at.center());if(q.horizontalDistance()>host.first().halfWidth()+ramp.first().halfWidth())continue;int side=q.lateral()<0?-1:1;
 for(int step=-2;step<=2;step++){double d=1+3*Math.floor((q.sample().distance()-1)/3)+step*3;var mid=RoadStructures.sample(host,d+.75);var gap=RoadStructures.sample(host,d+2.25);var painted=mid.at(RoadProfile.layout(host,mid).outer(side),0);var empty=gap.at(RoadProfile.layout(host,gap).outer(side),0);if(!marked(surface.markings(),painted))throw new AssertionError("missing highway auxiliary dash host="+hostIndex+" left="+left+" lanes="+count+" leg="+leg.name()+" d="+d);if(marked(surface.markings(),empty))throw new AssertionError("auxiliary boundary became solid d="+d);checks+=2;}
 }} }System.out.println("HighwayGuides201Validation PASS checks="+checks);}
}
