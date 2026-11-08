package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;
/** Clips overlapping ribbons at junctions without dropping whole sidewalk tiles. */
public final class SidewalkJoins {
  private record Cut(List<V> points,int layer,double low,double high,double minX,double maxX,double minZ,double maxZ){
    Cut(List<V> p,int layer){this(p,layer,p.stream().mapToDouble(V::y).min().orElseThrow(),p.stream().mapToDouble(V::y).max().orElseThrow(),p.stream().mapToDouble(V::x).min().orElseThrow(),p.stream().mapToDouble(V::x).max().orElseThrow(),p.stream().mapToDouble(V::z).min().orElseThrow(),p.stream().mapToDouble(V::z).max().orElseThrow());}
  }
  private final Map<Long,List<Cut>> grid=new HashMap<>();
  public SidewalkJoins(Collection<Mesh> decks,Collection<Part> prior){
    for(var m:decks)for(int i=1;i<m.samples().size();i++){var a=m.samples().get(i-1);var b=m.samples().get(i);add(new Cut(List.of(a.at(a.halfWidth(),0),a.at(-a.halfWidth(),0),b.at(-b.halfWidth(),0),b.at(b.halfWidth(),0)),-1));}
    for(var p:prior)if(RoadSidewalks.smoothPart(p))add(new Cut(p.base(),layer(p)));
  }
  private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
  private void add(Cut c){if(Math.abs(JunctionPaint.area(c.points))<1e-9)return;for(int x=(int)Math.floor(c.minX/8);x<=(int)Math.floor(c.maxX/8);x++)for(int z=(int)Math.floor(c.minZ/8);z<=(int)Math.floor(c.maxZ/8);z++)grid.computeIfAbsent(key(x,z),k->new ArrayList<>()).add(c);}
  private static int layer(Part p){return p.material()!=Material.TACTILE?0:p.height()<.018?1:2;}
  private static boolean isConvex(List<V> points){double sign=0;for(int i=0;i<points.size();i++){var a=points.get((i+1)%points.size()).sub(points.get(i));var b=points.get((i+2)%points.size()).sub(points.get((i+1)%points.size()));double cross=a.x()*b.z()-a.z()*b.x();if(Math.abs(cross)<1e-9)continue;if(sign*cross<0)return false;sign=cross;}return true;}
  public List<Part> clip(List<Part> parts){
    var out=new ArrayList<Part>();
    var convex=new ArrayList<Part>();
    for(var part:parts){if(!RoadSidewalks.smoothPart(part)){convex.add(part);continue;}var poly=part.base();if(isConvex(poly)){convex.add(part);continue;}for(int i=1;i<poly.size()-1;i++){V a=poly.get(0),b=poly.get(i),c=poly.get(i+1);if(Math.abs(JunctionPaint.area(List.of(a,b,c)))>1e-8)convex.add(new Part(a.add(b).mul(.5),c,Math.max(.001,Math.min(8,a.distance(b))),part.height(),false,part.material()).frames(a.sub(b).mul(.5),new V(0,0,0)));}}
    for(var p:convex){if(!RoadSidewalks.smoothPart(p)){out.add(p);continue;}var original=p.base();var box=new Cut(original,layer(p));var candidates=new LinkedHashSet<Cut>();
      for(int x=(int)Math.floor(box.minX/8);x<=(int)Math.floor(box.maxX/8);x++)for(int z=(int)Math.floor(box.minZ/8);z<=(int)Math.floor(box.maxZ/8);z++)candidates.addAll(grid.getOrDefault(key(x,z),List.of()));
      List<List<V>> polygons=List.of(original);boolean changed=false;
      for(var cut:candidates){
        if(cut.layer>=0&&cut.layer!=box.layer||cut.maxX<=box.minX+1e-7||cut.minX>=box.maxX-1e-7||cut.maxZ<=box.minZ+1e-7||cut.minZ>=box.maxZ-1e-7||box.low>cut.high+.6||box.high<cut.low-.6)continue;
        var next=new ArrayList<List<V>>();for(var poly:polygons)next.addAll(RoadSurface.subtract(poly,cut.points));
        double before=polygons.stream().mapToDouble(v->Math.abs(JunctionPaint.area(v))).sum(),after=next.stream().mapToDouble(v->Math.abs(JunctionPaint.area(v))).sum();
        if(before-after>1e-7){changed=true;polygons=next;}if(polygons.isEmpty())break;
      }
      if(!changed){out.add(p);add(box);continue;}
      for(var poly:polygons)if(Math.abs(JunctionPaint.area(poly))>1e-7){add(new Cut(poly,box.layer));for(int i=1;i<poly.size()-1;i++){V a=poly.get(0),b=poly.get(i),c=poly.get(i+1);if(Math.abs(JunctionPaint.area(List.of(a,b,c)))<1e-8)continue;V frame=a.sub(b).mul(.5);out.add(new Part(a.add(b).mul(.5),c,Math.max(.001,Math.min(8,a.distance(b))),p.height(),false,p.material()).frames(frame,new V(0,0,0)));}}
    }return List.copyOf(out);
  }
}
