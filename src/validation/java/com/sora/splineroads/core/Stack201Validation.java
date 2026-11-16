package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;import java.util.*;
public class Stack201Validation{
 public static void main(String[]args){int tested=0;double maximum=0;for(boolean left:new boolean[]{false,true})for(int lanes:new int[]{1,2})for(double angle:new double[]{0,.37})for(int size:new int[]{600,800}){
 var s=new Settings(Mode.STRAIGHT,Style.H4_RAIL,28,1,.4,90);var u=new V(Math.cos(angle),0,Math.sin(angle));var v=u.left();var center=new V(1024.5,4,-1024.5);var ns=new Node[]{new Node(center.sub(u.mul(size)),RoadPlanner.yaw(u),0),new Node(center.add(u.mul(size)),RoadPlanner.yaw(u),0),new Node(center.sub(v.mul(size)).add(new V(0,21,0)),RoadPlanner.yaw(v),0),new Node(center.add(v.mul(size)).add(new V(0,21,0)),RoadPlanner.yaw(v),0)};
 var plan=InterchangePlanner.plan(ns,s,s,new InterchangePlanner.Options(InterchangePlanner.Preset.STACK,left,lanes,96,20,5,2));int ramps=0;
 for(var leg:plan.legs())if(leg.name().contains(left?" 右转":" 左转")){var samples=leg.mesh().samples();double positive=0,negative=0;for(int i=1;i<samples.size();i++){var a=samples.get(i-1).left();var b=samples.get(i).left();double turn=Math.atan2(a.x()*b.z()-a.z()*b.x(),a.dot(b));if(turn>0)positive+=turn;else negative-=turn;}double reverse=Math.min(positive,negative);maximum=Math.max(maximum,reverse);if(reverse>.002)throw new AssertionError("stack inner flyover bends backwards by "+Math.toDegrees(reverse)+" degrees: "+leg.name()+" left="+left+" lanes="+lanes+" angle="+angle+" size="+size);ramps++;}
 if(ramps!=4)throw new AssertionError("lost stack inner movements");tested+=ramps;
 }System.out.println("Stack201Validation PASS innerRamps="+tested+" maximumReverseDegrees="+Math.toDegrees(maximum));}
}
