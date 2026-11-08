package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Immutable local index of main concrete shafts, not their plinth/capital pieces.
 * Same-elevation nearby supports must not become independent duplicate foundations
 * just because two road records use different endpoint IDs. */
public final class RoadPierSpacing {
  public static final double MIN_SPACING=8, MAX_TOP_DIFFERENCE=2;
  private record Cell(int x,int z){}
  private record Shaft(double x,double z,double bottom,double top){}
  private final Map<Cell,List<Shaft>> cells;
  private static boolean main(Part p){return p.pier()&&p.material()==Material.CONCRETE&&p.height()>2;}
  private static Shaft shaft(Part p){return new Shaft((p.a().x()+p.b().x())*.5,(p.a().z()+p.b().z())*.5,Math.min(p.a().y(),p.b().y()),Math.max(p.a().y(),p.b().y())+p.height());}
  private static int grid(double v){return (int)Math.floor(v/MIN_SPACING);}
  public RoadPierSpacing(Collection<Part> parts){
    var mutable=new HashMap<Cell,List<Shaft>>();
    for(var p:parts)if(main(p)){var s=shaft(p);mutable.computeIfAbsent(new Cell(grid(s.x()),grid(s.z())),key->new ArrayList<>()).add(s);}
    var frozen=new HashMap<Cell,List<Shaft>>();mutable.forEach((key,value)->frozen.put(key,List.copyOf(value)));cells=Map.copyOf(frozen);
  }
  public boolean tooClose(Part part){
    if(!main(part))return false;var p=shaft(part);int x=grid(p.x()),z=grid(p.z());
    for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(var q:cells.getOrDefault(new Cell(x+dx,z+dz),List.of())){
      if(Math.abs(p.top()-q.top())>MAX_TOP_DIFFERENCE||Math.min(p.top(),q.top())<=Math.max(p.bottom(),q.bottom())+.1)continue;
      double sx=p.x()-q.x(),sz=p.z()-q.z();if(sx*sx+sz*sz<MIN_SPACING*MIN_SPACING-1e-8)return true;
    }return false;
  }
}
