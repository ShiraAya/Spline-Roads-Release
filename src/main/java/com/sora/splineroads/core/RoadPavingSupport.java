package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Physical sidewalk bearings, separate from the original terrain classification.
 * Query actual saved triangles and require the whole plinth, including seams. */
public final class RoadPavingSupport {
  private record Patch(Part part,List<V> top,double high){
    double height(V p){
      V a=top.get(0),b=top.get(1).sub(a),c=top.get(2).sub(a),q=p.sub(a);
      double det=b.x()*c.z()-b.z()*c.x();
      return a.y()+((q.x()*c.z()-q.z()*c.x())*b.y()+(b.x()*q.z()-b.z()*q.x())*c.y())/det;
    }
  }
  private final Map<Long,List<Patch>> grid=new HashMap<>();
  public RoadPavingSupport(Collection<Part> parts){
    for(var part:parts)if(part.material().name().startsWith("WALK_")){
      var top=part.top();for(int i=1;i<top.size()-1;i++){
        var triangle=List.of(top.get(0),top.get(i),top.get(i+1));if(RoadSurface.area(triangle)<1e-10)continue;
        var patch=new Patch(part,triangle,triangle.stream().mapToDouble(V::y).max().orElseThrow());
        for(int x=lo(triangle,true);x<=hi(triangle,true);x++)for(int z=lo(triangle,false);z<=hi(triangle,false);z++)
          grid.computeIfAbsent(key(x,z),k->new ArrayList<>()).add(patch);
      }
    }
  }
  private static int lo(List<V> p,boolean x){return (int)Math.floor(p.stream().mapToDouble(v->x?v.x():v.z()).min().orElseThrow()/16);}
  private static int hi(List<V> p,boolean x){return (int)Math.floor(p.stream().mapToDouble(v->x?v.x():v.z()).max().orElseThrow()/16);}
  private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
  private List<Patch> near(List<V> base){
    var found=new LinkedHashSet<Patch>();
    for(int x=lo(base,true);x<=hi(base,true);x++)for(int z=lo(base,false);z<=hi(base,false);z++)found.addAll(grid.getOrDefault(key(x,z),List.of()));
    return found.stream().sorted(Comparator.comparingDouble(Patch::high).reversed()).toList();
  }
  public double foundation(V center,double top,double width){
    V position=new V(center.x(),top,center.z());var base=new Part(position,position,width,.35,true,Material.CONCRETE).base();
    var uncovered=new ArrayList<List<V>>();uncovered.add(base);double floor=Double.POSITIVE_INFINITY,ceiling=Double.NEGATIVE_INFINITY;
    for(var patch:near(base)){
      var next=new ArrayList<List<V>>();
      for(var polygon:uncovered){
        var clip=RoadSurface.intersect(polygon,patch.top());if(clip.size()<3||RoadSurface.area(clip)<1e-9){next.add(polygon);continue;}
        double low=clip.stream().mapToDouble(patch::height).min().orElseThrow(),high=clip.stream().mapToDouble(patch::height).max().orElseThrow();
        if(high>=top-.4||top-low>RoadStructures.MAX_DROP){next.add(polygon);continue;}
        floor=Math.min(floor,low);ceiling=Math.max(ceiling,high);next.addAll(RoadSurface.subtract(polygon,patch.top()));
      }
      uncovered=next;if(uncovered.isEmpty())break;
    }
    // Distinct stacked walkways cannot jointly masquerade as one level footing.
    return uncovered.stream().mapToDouble(RoadSurface::area).sum()<1e-7&&Double.isFinite(floor)&&ceiling-floor<=width*.5+.02?floor:Double.NaN;
  }
  public double foundation(V center,double top,double width,double median,java.util.function.DoubleSupplier terrain){
    double paving=foundation(center,top,width);
    if(Double.isFinite(median)||Double.isFinite(paving))return Double.isFinite(median)&&Double.isFinite(paving)?Math.max(median,paving):Double.isFinite(median)?median:paving;
    // An incomplete paved footprint cannot fall back to the original terrain:
    // that terrain may have been excavated away and leave a floating plinth.
    var position=new V(center.x(),top,center.z());var base=new Part(position,position,width,.35,true,Material.CONCRETE).base();
    for(var patch:near(base)){
      var clip=RoadSurface.intersect(base,patch.top());if(clip.size()<3||RoadSurface.area(clip)<1e-9)continue;
      double low=clip.stream().mapToDouble(patch::height).min().orElseThrow(),high=clip.stream().mapToDouble(patch::height).max().orElseThrow();
      if(high<top-.4&&top-low<=RoadStructures.MAX_DROP)return Double.NaN;
    }
    return terrain.getAsDouble();
  }
  private static boolean belowPaving(Part part,Part paving){
    var roof=part.top();var surface=paving.top();boolean contact=false;
    for(int i=1;i<roof.size()-1;i++){
      var top=List.of(roof.get(0),roof.get(i),roof.get(i+1));
      if(RoadSurface.area(top)<1e-10)continue;
      var high=new Patch(part,top,0);
      for(int j=1;j<surface.size()-1;j++){
        var floor=List.of(surface.get(0),surface.get(j),surface.get(j+1));
        if(RoadSurface.area(floor)<1e-10)continue;
        var overlap=RoadSurface.intersect(top,floor);if(RoadSurface.area(overlap)<1e-9)continue;
        contact=true;var low=new Patch(paving,floor,0);
        for(var point:overlap)if(high.height(point)>low.height(point)+.025)return false;
      }
    }
    return contact;
  }
  /** Allow a concrete shaft's bottom to bear slightly into a sloped slab.
   * Upper walkways, side impacts, steel furniture and deep penetration still block. */
  public boolean blocks(Part part){return blocks(part,p->false);}
  /** A caller may identify pavement on a joined road. Only a slab which stays
   * below that pavement's actual top can share its floor at the common seam. */
  public boolean blocks(Part part,java.util.function.Predicate<Part> joinedFloor){
    var seen=new HashSet<Part>();
    for(var patch:near(part.base()))if(seen.add(patch.part())&&RoadSolidOverlap.intersects(part,patch.part())){
      if(!part.pier()&&part.material()==Material.CONCRETE&&joinedFloor.test(patch.part())&&belowPaving(part,patch.part()))continue;
      if(!part.pier()||part.material()!=Material.CONCRETE)return true;
      var floor=part.base();
      double bottom=floor.stream().mapToDouble(V::y).min().orElseThrow();
      double bearing=foundation(part.a(),bottom+1,part.width());
      if(!Double.isFinite(bearing)||bottom>bearing+1e-7||bearing-bottom>.35+1e-7)return true;
      for(var face:near(floor))if(face.part()==patch.part()){
        var overlap=RoadSurface.intersect(floor,face.top());
        if(overlap.size()<3||RoadSurface.area(overlap)<1e-9)continue;
        for(var p:overlap){
          if(face.height(p)-bottom>.35+1e-7)return true;
        }
      }
    }
    return false;
  }
}
