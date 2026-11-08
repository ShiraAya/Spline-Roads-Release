package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;

/** Two smooth approaches and a level low section; endpoint position and slope stay fixed. */
public final class RoadTunnel {
  public static boolean dipping(Settings s){return s.structure()==Structure.TUNNEL&&s.options().infrastructure().tunnelDepth()>0;}
  public static double minimum(Node a,Node b,Settings s){return Math.min(a.position().y(),b.position().y())-s.options().infrastructure().tunnelDepth();}
  public static double height(double t,Node a,Node b,Settings s,double scaleA,double scaleB){
    double low=minimum(a,b,s);
    if(a.grade()>1e-7||b.grade()< -1e-7)throw new IllegalArgumentException("自动下潜需入口平直或下坡、出口平直或上坡；请调整端点坡度");
    if(t<=.45)return blend(t/.45,a.position().y(),low,a.grade()*scaleA*.45,0,s);
    if(t>=.55)return blend((t-.55)/.45,low,b.position().y(),0,b.grade()*scaleB*.45,s);
    return low;
  }
  static double blend(double t,double a,double b,double da,double db,Settings s){
    if(!s.options().infrastructure().efficientDip()){double t2=t*t,t3=t2*t,t4=t3*t,t5=t4*t;return a+(b-a)*(10*t3-15*t4+6*t5)+da*(t-6*t3+8*t4-3*t5)+db*(-4*t3+7*t4-3*t5);}
    // Ease the grade at both ends, using the middle for a constant grade. The old
    // all-quintic approach wasted most of its length and peaked at 1.875*average.
    double ease=.2, delta=b-a, grade=(delta-ease*(da+db)/2)/(1-ease);
    if(t<ease)return a+da*t+(grade-da)*ease*integral(t/ease);
    if(t>1-ease){double u=1-t;return b-db*u-(grade-db)*ease*integral(u/ease);}
    return a+ease*(da+grade)/2+grade*(t-ease);
  }
  private static double integral(double u){return u*u*u*(1-u/2);}
  private RoadTunnel(){}
}
