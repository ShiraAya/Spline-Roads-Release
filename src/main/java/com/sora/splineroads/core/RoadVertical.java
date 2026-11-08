package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;

/** Explicit bridge rise and tunnel dip, sharing endpoint fitting and grade validation. */
public final class RoadVertical {
  public static boolean rising(Settings s){return s.structure()==Structure.BRIDGE&&s.options().infrastructure().bridgeRise()>0;}
  public static boolean automatic(Settings s){return rising(s)||RoadTunnel.dipping(s);}
  public static double maximum(Node a,Node b,Settings s){return Math.max(a.position().y(),b.position().y())+s.options().infrastructure().bridgeRise();}
  public static double limit(Settings s){
    if(s.structure()!=Structure.TUNNEL&&s.structure()!=Structure.BRIDGE)return .5;
    double configured=s.options().infrastructure().maxGrade();
    return configured>0?configured:RoadTunnel.dipping(s)?.2:.5;
  }
  public static double height(double t,Node a,Node b,Settings s,double scaleA,double scaleB){
    if(!rising(s))return RoadTunnel.height(t,a,b,s,scaleA,scaleB);
    if(a.grade() < -1e-7||b.grade()>1e-7)throw new IllegalArgumentException("自动抬升需入口平直或上坡、出口平直或下坡");
    double high=maximum(a,b,s);
    if(t<=.30)return RoadTunnel.blend(t/.30,a.position().y(),high,a.grade()*scaleA*.30,0,s);
    if(t>=.70)return RoadTunnel.blend((t-.70)/.30,high,b.position().y(),0,b.grade()*scaleB*.30,s);
    return high;
  }
  private RoadVertical(){}
}
