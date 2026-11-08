package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.Locale;

/** Connector-only grade policy. Legacy overloads and automatic interchanges retain 15%. */
public final class LaneRampGrade {
  public static final double LEGACY=.15;
  public static double limit(boolean involvesHighway,boolean override){
    return involvesHighway?(override?.20:.15):(override?.25:.20);
  }
  public static double checked(double limit){
    if(!Double.isFinite(limit)||limit<.01||limit>.25)throw new IllegalArgumentException("匝道坡比上限无效");
    return limit;
  }
  public static boolean exceeds(double rise,double run,double limit){
    return !RoadGeometry.finite(rise,run,limit)||run<0||Math.abs(rise)>(limit+.000001)*run+1e-9;
  }
  public static String label(double limit){return String.format(Locale.ROOT,"%.0f%%",limit*100);}
  public static void validate(Mesh mesh,double limit){
    checked(limit);
    for(int i=1;i<mesh.samples().size();i++){
      V d=mesh.samples().get(i).center().sub(mesh.samples().get(i-1).center());
      if(exceeds(d.y(),d.horizontalLength(),limit))throw new IllegalArgumentException("匝道实际路面坡度超过 "+label(limit)+"，请扩大间距或降低高差");
    }
  }
  /** Stations follow the road's 3-D arc; grade budgets use horizontal arc length.
   * minimum is a lower bound for THIS profile's vertical travel, not an assertion
   * that endpoint average grade alone proves an obstacle corridor feasible. */
  public record Report(double horizontal,double available,double minimum,double maximum,double travel){}
  public static Report report(Mesh mesh,double from,double to,double limit){
    checked(limit);double horizontal=0,available=0,travel=0,maximum=0;
    for(int i=1;i<mesh.samples().size();i++){
      var a=mesh.samples().get(i-1);var b=mesh.samples().get(i);V d=b.center().sub(a.center());
      double run=d.horizontalLength(),span=b.distance()-a.distance();horizontal+=run;
      maximum=Math.max(maximum,run>1e-10?Math.abs(d.y())/run:Math.abs(d.y())>1e-10?Double.POSITIVE_INFINITY:0);
      double overlap=Math.max(0,Math.min(to,b.distance())-Math.max(from,a.distance()));
      double fraction=span>1e-10?Math.min(1,overlap/span):0;
      available+=run*fraction;travel+=Math.abs(d.y())*fraction;
    }
    return new Report(horizontal,available,travel/limit,maximum,travel);
  }
  private LaneRampGrade(){}
}
