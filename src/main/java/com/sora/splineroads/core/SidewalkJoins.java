package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;
/** Clips overlapping ribbons at junctions without dropping whole sidewalk tiles. */
public final class SidewalkJoins {
  private static final class Cut {
    final List<V> points; final int layer; final double low,high,minX,maxX,minZ,maxZ;
    private final int hash;
    Cut(List<V> p,int layer){
      points=List.copyOf(p);this.layer=layer;
      double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY,x0=lo,x1=hi,z0=lo,z1=hi;
      for(var v:points){lo=Math.min(lo,v.y());hi=Math.max(hi,v.y());x0=Math.min(x0,v.x());x1=Math.max(x1,v.x());z0=Math.min(z0,v.z());z1=Math.max(z1,v.z());}
      if(points.isEmpty())throw new NoSuchElementException();
      low=lo;high=hi;minX=x0;maxX=x1;minZ=z0;maxZ=z1;hash=31*points.hashCode()+layer;
    }
    @Override public int hashCode(){return hash;}
    @Override public boolean equals(Object other){return this==other||other instanceof Cut c&&layer==c.layer&&points.equals(c.points);}
  }
  private final Map<Long,List<Cut>> grid=new HashMap<>();
  private Map<Long,Integer> temporary;
  public SidewalkJoins(Collection<Mesh> decks,Collection<Part> prior){
    for(var m:decks)for(int i=1;i<m.samples().size();i++){var a=m.samples().get(i-1);var b=m.samples().get(i);add(new Cut(List.of(a.at(a.halfWidth(),0),a.at(-a.halfWidth(),0),b.at(-b.halfWidth(),0),b.at(b.halfWidth(),0)),-1));}
    for(var p:prior)if(RoadSidewalks.smoothPart(p))add(new Cut(p.base(),layer(p)));
  }
  private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
  private void add(Cut c){if(Math.abs(JunctionPaint.area(c.points))<1e-9)return;for(int x=(int)Math.floor(c.minX/8);x<=(int)Math.floor(c.maxX/8);x++)for(int z=(int)Math.floor(c.minZ/8);z<=(int)Math.floor(c.maxZ/8);z++){long key=key(x,z);var list=grid.computeIfAbsent(key,k->new ArrayList<>());if(temporary!=null)temporary.putIfAbsent(key,list.size());list.add(c);}}
  private static int layer(Part p){return p.material()!=Material.TACTILE?0:p.height()<.018?1:2;}
  private static boolean isConvex(List<V> points){double sign=0;for(int i=0;i<points.size();i++){var a=points.get((i+1)%points.size()).sub(points.get(i));var b=points.get((i+2)%points.size()).sub(points.get((i+1)%points.size()));double cross=a.x()*b.z()-a.z()*b.x();if(Math.abs(cross)<1e-9)continue;if(sign*cross<0)return false;sign=cross;}return true;}
  /** Reuse deck cuts while retaining all overlap checks inside this one call. */
  public List<Part> clipIndependent(List<Part> parts){
    if(temporary!=null)throw new IllegalStateException("nested independent clipping");
    temporary=new HashMap<>();
    try{return clip(parts);}finally{
      for(var entry:temporary.entrySet()){
        var list=grid.get(entry.getKey());list.subList(entry.getValue(),list.size()).clear();
        if(list.isEmpty())grid.remove(entry.getKey());
      }
      temporary=null;
    }
  }
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
