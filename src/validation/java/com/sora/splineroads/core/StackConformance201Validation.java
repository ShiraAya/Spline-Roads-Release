package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import java.util.*;

/** Existing curved hosts must retain their paths after the inner STACK turn refit. */
public final class StackConformance201Validation {
  static RoadAxis axis(boolean vertical,double y,double amplitude){var p=new ArrayList<V>();for(int i=0;i<=800;i++){double t=i/800.0,x=-600+1200*t,z=amplitude*Math.sin(Math.PI*t);p.add(vertical?new V(z,y,x):new V(x,y,z));}return new RoadAxis(p);}
  public static void main(String[] args){int movements=0,hosts=0;double worst=0;
    for(boolean left:new boolean[]{false,true})for(int curved:new int[]{0,1})for(double amplitude:new double[]{12,40}){
      var a=axis(false,4,curved==0?amplitude:0);var b=axis(true,25,curved==1?-amplitude:0);var axes=List.of(a,b);
      Node[] nodes={new Node(a.at(0),-90,0),new Node(a.at(a.length()),-90,0),new Node(b.at(0),0,0),new Node(b.at(b.length()),0,0)};
      var s=new Settings(Mode.STRAIGHT,Style.H4_RAIL,28,1,.4,90);var o=new Options(Preset.STACK,left,1,96,20,5,2);
      var plan=CurvedRoadPlans.interchange(nodes,s,s,o,axes);if(plan.movements()!=8)throw new AssertionError("missing interchange movements");
      for(int i=0;i<2;i++){var source=axes.get(i);for(var p:plan.legs().get(i).mesh().samples())if(p.center().sub(source.at(source.project(p.center()))).horizontalLength()>.03)throw new AssertionError("host curve drifted");hosts++;}
      for(var leg:plan.legs())if(leg.name().contains(left?" 右转":" 左转")){
        double positive=0,negative=0;var samples=leg.mesh().samples();for(int i=1;i<samples.size();i++){var x=samples.get(i-1).left();var y=samples.get(i).left();double turn=Math.atan2(x.x()*y.z()-x.z()*y.x(),x.dot(y));if(turn>0)positive+=turn;else negative-=turn;}double reverse=Math.min(positive,negative);worst=Math.max(worst,reverse);if(reverse>.035)throw new AssertionError("mapped inner ramp reverses by "+Math.toDegrees(reverse)+" degrees");movements++;
      }
      var again=CurvedRoadPlans.interchange(nodes,s,s,o,axes);for(int i=0;i<4;i++)if(plan.anchors().get(i).position().distance(again.anchors().get(i).position())>1e-7)throw new AssertionError("unchanged fit moved anchors");
    }
    if(movements!=32||hosts!=16)throw new AssertionError("missing conformance cases");System.out.println("StackConformance201Validation PASS hosts="+hosts+" innerRamps="+movements+" maximumReverseDegrees="+Math.toDegrees(worst));
  }
}
