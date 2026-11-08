package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;
/** Remove internal walls of tessellated paving, including diagonal and subdivided triangle edges. */
final class SidewalkFaces {
  private final Map<Long,List<Part>> grid=new HashMap<>();
  private final long retainedBytes;
  private record Footprint(List<V> polygon,double y,double area,boolean flat){}
  private final IdentityHashMap<Part,Footprint> footprints=new IdentityHashMap<>();
  SidewalkFaces(List<Part> parts){for(var p:parts)if(walk(p)){
    var polygon=p.base();double y=polygon.stream().mapToDouble(V::y).average().orElseThrow();
    footprints.put(p,new Footprint(polygon,y,JunctionPaint.area(polygon),polygon.stream().noneMatch(v->Math.abs(v.y()-y)>1e-6)));
    for(long key:keys(polygon))grid.computeIfAbsent(key,k->new ArrayList<>()).add(p);
  }retainedBytes=footprints.size()*384L+grid.size()*80L+grid.values().stream().mapToLong(v->v.size()*8L).sum();}
  long bytes(){return retainedBytes;}
  static boolean walk(Part p){return p.material().name().startsWith("WALK_");}
  private static List<Long> keys(List<V> poly){int x0=(int)Math.floor(poly.stream().mapToDouble(V::x).min().orElseThrow()/8),x1=(int)Math.floor(poly.stream().mapToDouble(V::x).max().orElseThrow()/8);int z0=(int)Math.floor(poly.stream().mapToDouble(V::z).min().orElseThrow()/8),z1=(int)Math.floor(poly.stream().mapToDouble(V::z).max().orElseThrow()/8);var out=new ArrayList<Long>();for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)out.add(((long)x<<32)^(z&0xffffffffL));return out;}
  List<RoadSurface.Face> exposed(Part owner,RoadSurface.Face face){
    var f=face.points();V a=f.get(0),b=f.get(1);
    // Side faces may have been reversed to get outward normals. Recover the bottom edge.
    var low=f.stream().sorted(Comparator.comparingDouble(V::y)).limit(2).toList();a=low.get(0);b=low.get(1);
    if(a.sub(b).horizontalLength()<1e-8)return List.of();
    var candidates=new HashSet<Part>();for(long key:keys(List.of(a,b)))candidates.addAll(grid.getOrDefault(key,List.of()));
    List<double[]> visible=new ArrayList<>();visible.add(new double[]{0,1});
    for(var other:candidates){if(other==owner||Math.abs(other.height()-owner.height())>1e-6)continue;
      var shape=footprints.get(other);var poly=shape.polygon();double y=shape.y();
      if(!shape.flat()||Math.abs(a.y()-y)>1e-6||Math.abs(b.y()-y)>1e-6)continue;
      double area=shape.area(),sign=Math.signum(area);if(Math.abs(area)<1e-9)continue;double lo=0,hi=1;boolean miss=false;
      for(int i=0;i<poly.size();i++){V p=poly.get(i),q=poly.get((i+1)%poly.size());double aa=sign*RoadAxis.cross(q.sub(p),a.sub(p)),bb=sign*RoadAxis.cross(q.sub(p),b.sub(p));double eps=1e-7*Math.max(1,q.sub(p).horizontalLength());if(Math.abs(aa)<eps)aa=0;if(Math.abs(bb)<eps)bb=0;double d=bb-aa;
        if(Math.abs(d)<1e-12){if(aa<0){miss=true;break;}}else if(d>0)lo=Math.max(lo,-aa/d);else hi=Math.min(hi,-aa/d);}
      if(miss||hi<=lo+1e-8)continue;var next=new ArrayList<double[]>();for(var r:visible){if(lo>r[0]+1e-8)next.add(new double[]{r[0],Math.min(lo,r[1])});if(hi<r[1]-1e-8)next.add(new double[]{Math.max(hi,r[0]),r[1]});}visible=next;
    }
    if(visible.size()==1&&visible.get(0)[0]==0&&visible.get(0)[1]==1)return List.of(face);
    var out=new ArrayList<RoadSurface.Face>();for(var r:visible){V p=a.add(b.sub(a).mul(r[0])),q=a.add(b.sub(a).mul(r[1]));var points=new ArrayList<>(List.of(p,q,q.add(new V(0,owner.height(),0)),p.add(new V(0,owner.height(),0))));var candidate=new RoadSurface.Face(points,face.color(),face.emissive(),face.texture());if(RoadLighting.normal(candidate).dot(RoadLighting.normal(face))<0){Collections.reverse(points);candidate=new RoadSurface.Face(points,face.color(),face.emissive(),face.texture());}out.add(candidate);}return out;
  }
}
