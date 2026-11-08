package com.sora.splineroads.core;

import com.sora.splineroads.core.StreetPlan.*;
import java.util.*;

/** Shared smooth centerline for the map, terrain checks and persisted native road. */
public final class StreetCurves {
  public static double proposed(Input input,Vertex a,Vertex b,int rank){
    var style=input.options().style();if(style==Style.DOWNTOWN||style==Style.NEW_TOWN)return 0;
    double length=a.point().distance(b.point());if(length<72)return 0;
    // Keep reserved and existing interfaces straight, including their lead-in.
    if(input.entries().stream().anyMatch(e->e.point().distance(a.point())<1||e.point().distance(b.point())<1))return 0;
    long key=input.options().seed()^Double.doubleToLongBits(a.point().x()+b.point().x())^Long.rotateLeft(Double.doubleToLongBits(a.point().z()+b.point().z()),23)^rank;
    var random=new SplittableRandom(key);if(random.nextDouble()<.25||style==Style.TOWN&&rank==0)return 0;
    double scale=style==Style.RURAL?.075:style==Style.SUBURB?.06:.045;
    return (random.nextBoolean()?1:-1)*Math.min(16,length*scale)*(.7+.3*random.nextDouble());
  }
  public static Point point(Point a,Point b,double bend,double t){
    var p=a.mix(b,t);double length=a.distance(b),offset=bend*16*t*t*(1-t)*(1-t);
    return new Point(p.x()-(b.z()-a.z())/length*offset,p.z()+(b.x()-a.x())/length*offset);
  }
  public static Point point(Plan plan,Edge edge,double t){return point(plan.vertices().get(edge.a()).point(),plan.vertices().get(edge.b()).point(),edge.bend(),t);}
  public static RoadGeometry.V direction(Point a,Point b,double bend,double t){
    double dx=b.x()-a.x(),dz=b.z()-a.z(),length=a.distance(b),slope=bend*32*t*(1-t)*(1-2*t);
    return new RoadGeometry.V(dx-dz/length*slope,0,dz+dx/length*slope).horizontalUnit();
  }
  private StreetCurves(){}
}
