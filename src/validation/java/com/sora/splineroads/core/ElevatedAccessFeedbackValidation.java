package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
/** New feedback fixtures include a physical median, both traffic conventions and mild grades. */
public final class ElevatedAccessFeedbackValidation {
  private static int checks,cases,legacyFailures;
  private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
  public static void main(String[] args){
    boolean legacy=Boolean.getBoolean("sr201.expectLegacy");
    for(boolean first:new boolean[]{true,false})for(boolean traffic:new boolean[]{false,true})
      for(double grade:new double[]{0,.0001,.005,-.005})
        for(int[] counts:new int[][]{{3,3,2,3},{2,3,2,3},{3,2,2,2},{3,2,3,2}}){
          cases++;
          var s=RoadLanes.configure(new Settings(Mode.STRAIGHT,Style.O2_RAIL,9,1,.35,90),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(counts[0],counts[1]),4);
          s=s.options(s.options().traffic(traffic));
          var host=RoadMedianAnchor.apply(RoadGeometry.build(new Node(new V(210,3,170),0,grade),new Node(new V(210,3+400*grade,570),0,grade),s));
          var upper=RoadLanes.configure(s,RoadProfile.Type.ORDINARY,new RoadLanes.Counts(counts[2],counts[3]),4);
          if(!first)upper=RoadEndpointSections.orient(upper,true);
          boolean single=counts[0]!=counts[2]||counts[1]!=counts[3];
          try{
            var plan=ElevatedAccess.plan(UUID.randomUUID(),UUID.randomUUID(),host,120,new Node(new V(210,15+240*grade,410),0,grade),upper,first);
            check(!(legacy&&single),"legacy one-sided case unexpectedly passed");
            var r=plan.reservation();check(r.additions().size()==(single?1:2),"wrong number of auxiliary directions");
            var md=host.settings().options().lanePoints().cuts(r.cuts()).additions(r.additions()).access(List.of(r));
            var actual=LaneSections.apply(RoadMedianAnchor.apply(RoadGeometry.build(new Node(new V(210,3,170),0,grade),new Node(new V(210,3+400*grade,570),0,grade),s.options(s.options().lanePoints(md)))));
            RoadClearance.check(plan.road(),actual,null);checks++;
            var station=140d;var sample=RoadStructures.sample(actual,station);var raw=RoadStructures.sample(host,station);var layout=RoadProfile.layout(host,raw);
            var median=raw.at(layout.medianCenter(),0);double lateral=median.sub(sample.center()).dot(sample.left());
            check(!LaneDeck.present(actual,sample,lateral,0),"physical median was not removed at its world position");
            for(var direction:plan.directions())for(int slot=0;slot<layout.catalog().lanes();slot++){
              var lane=LanePoints.lane(host,station,slot);if(lane.sign()!=direction.sign()||direction.slots().contains(slot))continue;
              check(LaneDeck.present(actual,sample,lane.position().sub(sample.center()).dot(sample.left()),0),"unselected through lane was removed");
            }
          }catch(IllegalArgumentException e){
            if(!legacy||!single||!e.getMessage().contains("道路投影交叠"))throw e;
            legacyFailures++;checks++;
          }
        }
    check(cases==64,"missing cases");if(legacy)check(legacyFailures==32,"expected 32 baseline one-sided failures");
    System.out.println("PASS cases="+cases+" checks="+checks+" legacyFailures="+legacyFailures);
  }
}
