package com.sora.splineroads.core;

/** Endpoint-tangent profile. Compatible signed ports use an integrated, nonnegative
 * grade, not local height bumps whose derivative can reverse inside an uphill route.
 * Opposing port tangents still need a small, local crest/valley; never amplify those
 * boundary conditions by the full length of a long connector. */
public final class LaneRampProfile {
  public static double height(double from,double to,double firstGrade,double lastGrade,double length,double station){
    validate(from,to,firstGrade,lastGrade,length,station);
    double d=Math.max(0,Math.min(length,station)),delta=to-from;
    if(Math.abs(firstGrade)<1e-10&&Math.abs(lastGrade)<1e-10){double t=d/length;return from+delta*t*t*(3-2*t);}
    double sign=Math.signum(delta);
    if(sign!=0&&sign*firstGrade>=0&&sign*lastGrade>=0)
      return monotone(from,to,firstGrade,lastGrade,length,d,Double.POSITIVE_INFINITY);
    double g=delta/length,span=span(length);
    return from+g*d+(firstGrade-g)*bump(d,span)-(lastGrade-g)*bump(length-d,span);
  }
  /** Use the selected connector cap to fit a feasible profile before rejecting it.
   * A smoothstep's 1.5-times-average peak is not a physical lower bound on grade. */
  public static double height(double from,double to,double firstGrade,double lastGrade,double length,double station,double maxGrade){
    validate(from,to,firstGrade,lastGrade,length,station);LaneRampGrade.checked(maxGrade);
    if(LaneRampGrade.exceeds(firstGrade,1,maxGrade)||LaneRampGrade.exceeds(lastGrade,1,maxGrade))
      throw new IllegalArgumentException("端口坡度超过 "+LaneRampGrade.label(maxGrade));
    double delta=to-from,sign=Math.signum(delta),d=Math.max(0,Math.min(length,station));
    if(Math.abs(delta)>maxGrade*length+1e-9)
      throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,"总水平布坡长度 %.1f 格小于端点高差所需下限 %.1f 格（上限 %s）",length,Math.abs(delta)/maxGrade,LaneRampGrade.label(maxGrade)));
    if(sign!=0&&sign*firstGrade>=0&&sign*lastGrade>=0){
      if(Math.abs(firstGrade)<1e-10&&Math.abs(lastGrade)<1e-10&&1.5*Math.abs(delta)/length<=maxGrade)
        return height(from,to,firstGrade,lastGrade,length,d);
      return monotone(from,to,firstGrade,lastGrade,length,d,maxGrade);
    }
    return height(from,to,firstGrade,lastGrade,length,d);
  }
  private static double monotone(double from,double to,double first,double last,double length,double d,double cap){
    double sign=Math.signum(to-from),rise=Math.abs(to-from),a=sign*first,b=sign*last,s=span(length);
    // Integral of each smooth endpoint-grade transition is its mean grade times s.
    // Shorten transitions only when they otherwise spend the available height, or
    // leave too little horizontal distance for the middle grade under the real cap.
    if(a+b>0)s=Math.min(s,1.8*rise/(a+b));
    if(Double.isFinite(cap)&&cap-(a+b)/2>1e-12)
      s=Math.min(s,.98*Math.max(0,cap*length-rise)/(cap-(a+b)/2));
    if(s<1e-10){
      if(Math.abs(a-rise/length)>1e-7||Math.abs(b-rise/length)>1e-7)
        throw new IllegalArgumentException("全部水平长度已耗尽坡比预算，无法保留端口纵坡；请增加少量过渡空间");
      return from+(to-from)*(d/length);
    }
    double middle=(rise-s*(a+b)/2)/(length-s);
    if(d<=s)return from+sign*integral(a,middle,d,s);
    if(d>=length-s)return to-sign*integral(b,middle,length-d,s);
    return from+sign*(s*(a+middle)/2+middle*(d-s));
  }
  /** Integral of a+(b-a)*smoothstep(d/span), with continuous first/second derivatives. */
  private static double integral(double a,double b,double d,double span){double t=d/span;return a*d+(b-a)*span*(t*t*t-.5*t*t*t*t);}
  private static double span(double length){return Math.min(length/2,Math.max(8,Math.min(24,length/4)));}
  private static double bump(double d,double span){if(d>=span)return 0;double t=1-d/span;return d*t*t;}
  private static void validate(double from,double to,double first,double last,double length,double station){
    if(!RoadGeometry.finite(from,to,first,last,length,station)||length<=0)throw new IllegalArgumentException("无效匝道纵断面");
  }
  private LaneRampProfile(){}
}
