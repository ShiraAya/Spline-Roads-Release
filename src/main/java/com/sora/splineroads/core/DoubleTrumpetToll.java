package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import java.util.*;

/** Optional station owned by the interchange, on the straight third-road approach. */
public final class DoubleTrumpetToll {
 public enum Near { AB, CD }
 public record Config(boolean enabled,boolean twoWay,boolean reverse,Near near,int forwardEtc,int reverseEtc,String name){
  public static final Config OFF=new Config(false,true,false,Near.AB,0,0,"");
  public Config {
   name=name==null?"":name.strip();
   if(near==null||forwardEtc<0||forwardEtc>7||reverseEtc<0||reverseEtc>7||name.codePointCount(0,name.length())>40||name.codePoints().anyMatch(c->Character.isISOControl(c)||c==0x00a7))
    throw new IllegalArgumentException("双喇叭收费配置无效：ETC 0–7，名称最多40字");
  }
  public Config enabled(boolean value){return new Config(value,twoWay,reverse,near,forwardEtc,reverseEtc,name);}
  public Config mode(boolean both,boolean reversed){return new Config(enabled,both,reversed,near,forwardEtc,reverseEtc,name);}
  public Config near(Near value){return new Config(enabled,twoWay,reverse,value,forwardEtc,reverseEtc,name);}
  public Config etc(int forward,int backward){return new Config(enabled,twoWay,reverse,near,forward,backward,name);}
  public Config named(String value){return new Config(enabled,twoWay,reverse,near,forwardEtc,reverseEtc,value);}
 }
 public static TollPlaza.Config station(Settings road,Options options){
  var c=options.toll();var counts=RoadLanes.counts(road);int f=counts.forward(),b=counts.reverse();
  if(f<1||b<1)throw new IllegalArgumentException("双喇叭第三道必须保持双向通行");
  int fe=Math.min(c.forwardEtc(),f),be=Math.min(c.reverseEtc(),b);
  return new TollPlaza.Config(true,false,options.leftTraffic(),false,
      new TollPlaza.Direction(f,f,fe,f-fe),new TollPlaza.Direction(b,b,be,b-be),
      RoadProfile.layout(road,road.width()).laneWidth(),4,6,road.structure(),c.name(),c.twoWay()?3:c.reverse()?2:1);
 }
 public static double length(Settings road,Options options){
  var s=new TollPlaza.Spec(new Node(new V(0,0,0),0,0),new Node(new V(0,0,1024),0,0),station(road,options),road,road);
  var lengths=TollPlanner.lengths(s);
  return Math.ceil((2*Math.max(lengths.a(),lengths.b())+TollPlanner.CORE+2)/4)*4;
 }
 public static boolean connector(String name){return name.equals(DoubleTrumpetPlanner.CONNECTOR)||name.startsWith(DoubleTrumpetPlanner.CONNECTOR+" · ");}
 public static List<Leg> split(Mesh road,Options options){
  double span=length(road.settings(),options),from=options.toll().near()==Near.AB?8:road.length()-8-span,to=from+span;
  var segment=RoadSlicing.slice(road,from,to,road.settings()).mesh();
  V axis=segment.last().center().sub(segment.first().center()).horizontalUnit();
  for(var p:segment.samples())if(Math.abs(p.center().sub(segment.first().center()).dot(axis.left()))>1e-6||p.left().dot(axis.left())<.99999999)
   throw new IllegalArgumentException("双喇叭收费站必须完整位于所选主路一端的直线段，请扩大范围");
  var spec=new TollPlaza.Spec(RoadRibbon.start(segment),RoadRibbon.end(segment),station(road.settings(),options),road.settings(),road.settings(),segment.samples());
  var station=TollPlanner.plan(spec);
  return List.of(new Leg(DoubleTrumpetPlanner.CONNECTOR+" · AB端",-1,-1,RoadSlicing.slice(road,0,from,road.settings()).mesh()),
      new Leg(DoubleTrumpetPlanner.CONNECTOR+" · 收费站",-1,-1,station.mesh(),null,spec),
      new Leg(DoubleTrumpetPlanner.CONNECTOR+" · CD端",-1,-1,RoadSlicing.slice(road,to,road.length(),road.settings()).mesh()));
 }
 private DoubleTrumpetToll(){}
}
