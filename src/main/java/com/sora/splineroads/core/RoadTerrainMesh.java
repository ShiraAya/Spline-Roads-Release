package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.V;
import java.util.*;

/**
 * Exact, visual-only clipping of asphalt and road paint to their existing collision-block cells.
 * No world access, atlas access or light sampling: safe on the mesh worker. It does not voxelize
 * the visible surface. Every resulting polygon retains the original sloped plane and boundary.
 */
public final class RoadTerrainMesh {
  public record Cell(int x, int y, int z) {
    public Section section() { return new Section(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16)); }
    public int localKey() { return (x&15)|((z&15)<<4)|((y&15)<<8); }
  }
  public record Section(int x,int y,int z) {}
  public record Chunk(int x,int z) {}
  public record Polygon(List<V> vertices,int color,boolean paint,RoadSurface.Texture texture) {
    public Polygon(List<V> vertices,int color,boolean paint){this(vertices,color,paint,RoadSurface.Texture.PLAIN);}
    public Polygon { vertices=List.copyOf(vertices); }
  }
  public record Result(Map<Cell,List<Polygon>> cells,int polygons) {}
  /** Immutable broad-phase face index. Streaming one chunk must not rerun RoadSurface.build
   * or prepare the infrastructure VBOs of the entire road. Lists share immutable face objects. */
  public record Source(Map<Chunk,RoadSurface.Geometry> chunks) {}
  public static Source source(RoadSurface.Geometry geometry) {
    Map<Chunk,List<RoadSurface.Face>> pavement=new LinkedHashMap<>(),paint=new LinkedHashMap<>();
    for(var face:geometry.pavement())if(asphalt(face)||walkSurface(face))index(pavement,face);
    for(var face:geometry.markings())index(paint,face);
    var keys=new LinkedHashSet<>(pavement.keySet());keys.addAll(paint.keySet());
    Map<Chunk,RoadSurface.Geometry> result=new LinkedHashMap<>();
    for(var chunk:keys)result.put(chunk,new RoadSurface.Geometry(
        List.copyOf(pavement.getOrDefault(chunk,List.of())),List.copyOf(paint.getOrDefault(chunk,List.of()))));
    return new Source(Collections.unmodifiableMap(result));
  }
  private static void index(Map<Chunk,List<RoadSurface.Face>> result,RoadSurface.Face face) {
    var points=face.points();if(points.size()<3)return;
    int x0=Math.floorDiv(floor(min(points,0)),16),x1=Math.floorDiv(ceil(max(points,0))-1,16);
    int z0=Math.floorDiv(floor(min(points,2)),16),z1=Math.floorDiv(ceil(max(points,2))-1,16);
    for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)
      result.computeIfAbsent(new Chunk(x,z),key->new ArrayList<>()).add(face);
  }
  private static final double EPS=1e-8;

  public static Result build(RoadSurface.Geometry geometry) {return build(geometry,null);}
  /** A null filter means all chunks (tests/tools); an empty filter means no loaded terrain. */
  public static Result build(RoadSurface.Geometry geometry,Set<Chunk> loaded) {
    Map<Cell,List<Polygon>> cells=new LinkedHashMap<>();
    for(var face:geometry.pavement()) if(asphalt(face)||walkSurface(face)) add(cells,face,false,loaded);
    // Paint and pavement are sampled/triangulated independently. Project each
    // clipped marking onto the actual deck triangles, not its approximate ribbon
    // plane; a tiny depth bias alone cannot fix a twisted/sloping quad.
    Map<Cell,List<Polygon>> paint=new LinkedHashMap<>();
    for(var face:geometry.markings())add(paint,face,true,loaded);
    Map<Cell,List<Polygon>> projected=new LinkedHashMap<>();
    for(var entry:paint.entrySet())for(var marking:entry.getValue()){
      List<List<V>> remaining=List.of(marking.vertices());var cell=entry.getKey();
      for(int dy=-1;dy<=1;dy++){
        var owner=new Cell(cell.x(),cell.y()+dy,cell.z());
        for(var deck:cells.getOrDefault(owner,List.of())){
          if(deck.texture()!=RoadSurface.Texture.PLAIN)continue;
          var polygon=RoadSurface.intersect(marking.vertices(),deck.vertices());
          if(polygon.isEmpty())continue;
          if(polygon.stream().anyMatch(p->Math.abs(p.y()-height(deck.vertices(),p))>.35))continue;
          var surface=new ArrayList<>(polygon.stream().map(p->new V(p.x(),height(deck.vertices(),p),p.z())).toList());
          // Polygon intersection normalizes XZ winding, which faces down in XYZ.
          // Re-establish the upward visible face after the final clipping operation.
          if(normalY(surface)<0)Collections.reverse(surface);
          projected.computeIfAbsent(owner,k->new ArrayList<>()).add(new Polygon(surface,marking.color(),true));
          var next=new ArrayList<List<V>>();
          for(var piece:remaining)next.addAll(RoadSurface.subtract(piece,deck.vertices()));
          remaining=next;
        }
      }
      // The paved union can assign part of this cell to a neighboring road.
      // Matching one local deck must not erase that unprojected part of an arrow.
      // Those pieces (and non-asphalt decoration) retain their original plane.
      for(var piece:remaining){
        var surface=new ArrayList<>(piece);if(normalY(surface)<0)Collections.reverse(surface);
        projected.computeIfAbsent(paintCarrier(cell,surface,cells),k->new ArrayList<>()).add(new Polygon(surface,marking.color(),true));
      }
    }
    projected.forEach((cell,polygons)->cells.computeIfAbsent(cell,k->new ArrayList<>()).addAll(polygons));
    int count=0;
    for(var e:cells.entrySet()) { e.setValue(List.copyOf(e.getValue())); count+=e.getValue().size(); }
    return new Result(Collections.unmodifiableMap(cells),count);
  }
  /** Independently sampled curved paint can protrude a few centimetres beyond
   * the deck into an air cell. Keep its exact vertices, but let a nearby paved
   * cell emit it. Never cross a render section: its VBO/terrain ownership and
   * culling bounds must still describe the original geometry. A larger piece
   * supplied by another road's paved union keeps its original carrier. */
  private static Cell paintCarrier(Cell cell,List<V> paint,Map<Cell,List<Polygon>> pavement){
    if(pavement.containsKey(cell))return cell;
    V center=new V(0,0,0);for(var p:paint)center=center.add(p);center=center.mul(1.0/paint.size());
    Cell best=cell;double score=Double.POSITIVE_INFINITY;
    for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++){
      var next=new Cell(cell.x()+dx,cell.y()+dy,cell.z()+dz);if(!next.section().equals(cell.section()))continue;
      for(var deck:pavement.getOrDefault(next,List.of())){
        if(deck.paint()||deck.texture()!=RoadSurface.Texture.PLAIN||Math.abs(center.y()-height(deck.vertices(),center))>.35)continue;
        // Only tiny rim overhangs qualify; do not move a remote joining arrow
        // to an unrelated deck merely because it shares a section.
        double nearest=Double.POSITIVE_INFINITY;
        for(int i=0;i<deck.vertices().size();i++){
          var a=deck.vertices().get(i);var b=deck.vertices().get((i+1)%deck.vertices().size());var edge=b.sub(a);var delta=center.sub(a);
          double run=edge.x()*edge.x()+edge.z()*edge.z();double t=run<1e-12?0:Math.max(0,Math.min(1,(delta.x()*edge.x()+delta.z()*edge.z())/run));
          var q=a.add(edge.mul(t));nearest=Math.min(nearest,Math.hypot(center.x()-q.x(),center.z()-q.z()));
        }
        if(nearest<=.125&&nearest<score){score=nearest;best=next;}
      }
    }
    return best;
  }
  public static double height(List<V> polygon,V p){
    var n=RoadLighting.normal(new RoadSurface.Face(polygon,0));var a=polygon.get(0);
    return Math.abs(n.y())<1e-10?a.y():a.y()-(n.x()*(p.x()-a.x())+n.z()*(p.z()-a.z()))/n.y();
  }
  public static boolean asphalt(RoadSurface.Face face) {
    return face.texture()==RoadSurface.Texture.PLAIN;
  }
  public static boolean walkSurface(RoadSurface.Face face){return face.texture().name().startsWith("WALK_")&&RoadLighting.normal(face).y()>.05;}
  /** Keep bridge undersides, sidewalls and all infrastructure in the independent renderer. */
  public static RoadSurface.Geometry remainder(RoadSurface.Geometry geometry) {
    return new RoadSurface.Geometry(geometry.pavement().stream().filter(f->!asphalt(f)&&!walkSurface(f)).toList(),List.of());
  }
  private static void add(Map<Cell,List<Polygon>> out,RoadSurface.Face face,boolean paint,Set<Chunk> loaded) {
    List<V> p=face.points();
    if(p.size()<3)return;
    // Triangles also handle the very small non-planarity of curved road quads correctly.
    for(int t=1;t+1<p.size();t++) {
      List<V> tri=List.of(p.get(0),p.get(t),p.get(t+1));
      if(area(tri)<1e-12)continue;
      int x0=floor(min(tri,0)),x1=ceil(max(tri,0)),z0=floor(min(tri,2)),z1=ceil(max(tri,2));
      if(loaded==null)rasterize(out,tri,face.color(),paint,face.texture(),x0,x1,z0,z1);
      else for(var chunk:loaded){
        // Restrict the scan BEFORE clipping. A long face may span kilometres; preparing
        // a single newly loaded chunk must not walk every cell in that face's bounds.
        int cx=chunk.x()*16,cz=chunk.z()*16;
        rasterize(out,tri,face.color(),paint,face.texture(),Math.max(x0,cx),Math.min(x1,cx+16),
            Math.max(z0,cz),Math.min(z1,cz+16));
      }
    }
  }
  private static void rasterize(Map<Cell,List<Polygon>> out,List<V> tri,int color,boolean paint,RoadSurface.Texture texture,
      int x0,int x1,int z0,int z1){
    if(x0>=x1||z0>=z1)return;
    for(int x=x0;x<x1;x++){
      var strip=clip(clip(tri,0,x,true),0,x+1,false);
      if(strip.size()<3)continue;
      for(int z=z0;z<z1;z++){
        var tile=clip(clip(strip,2,z,true),2,z+1,false);
        if(tile.size()<3||area(tile)<1e-12)continue;
        // A flat surface on Y=64 belongs to the collider below it, never to air at Y=64.
        double low=min(tile,1),high=max(tile,1);
        // ceil already applies the same height tolerance as the collision raster.
        // Use that single boundary for both ownership and clipping. Subtracting
        // EPS twice assigned a shallow slope just above an integer to the cell
        // below, whose one-EPS clip then discarded a visible triangular strip.
        int first=ceil(low)-1,last=ceil(high)-1;
        for(int y=first;y<=last;y++){
          var part=clip(clip(tile,1,y+EPS,true,0),1,y+1+EPS,false,0);
          if(part.size()<3||area(part)<1e-12)continue;
          if(normalY(part)<0){var reversed=new ArrayList<>(part);Collections.reverse(reversed);part=reversed;}
          out.computeIfAbsent(new Cell(x,y,z),k->new ArrayList<>()).add(new Polygon(part,color,paint,texture));
        }
      }
    }
  }
  private static List<V> clip(List<V> in,int axis,double plane,boolean greater) {
    return clip(in,axis,plane,greater,EPS);
  }
  private static List<V> clip(List<V> in,int axis,double plane,boolean greater,double tolerance) {
    if(in.isEmpty())return List.of();
    List<V> out=new ArrayList<>(in.size()+2);V a=in.get(in.size()-1);
    double da=coord(a,axis)-plane;boolean ia=greater?da>=-tolerance:da<=tolerance;
    for(V b:in) {
      double db=coord(b,axis)-plane;boolean ib=greater?db>=-tolerance:db<=tolerance;
      if(ia!=ib) {
        double t=da/(da-db);V v=a.add(b.sub(a).mul(Math.max(0,Math.min(1,t))));
        out.add(snap(v,axis,plane));
      }
      if(ib)out.add(b);
      a=b;da=db;ia=ib;
    }
    // Boundary vertices otherwise turn a triangle into many degenerate baked quads.
    for(int i=out.size()-1;i>=0&&out.size()>1;i--)
      if(out.get(i).sub(out.get((i+1)%out.size())).dot(out.get(i).sub(out.get((i+1)%out.size())))<1e-20)out.remove(i);
    return out;
  }
  private static V snap(V p,int axis,double value) {return axis==0?new V(value,p.y(),p.z()):axis==1?new V(p.x(),value,p.z()):new V(p.x(),p.y(),value);}
  private static int floor(double x){return (int)Math.floor(x+EPS);}
  private static int ceil(double x){return (int)Math.ceil(x-EPS);}
  private static double coord(V p,int axis){return axis==0?p.x():axis==1?p.y():p.z();}
  private static double min(List<V> p,int axis){double r=Double.POSITIVE_INFINITY;for(V v:p)r=Math.min(r,coord(v,axis));return r;}
  private static double max(List<V> p,int axis){double r=Double.NEGATIVE_INFINITY;for(V v:p)r=Math.max(r,coord(v,axis));return r;}
  public static double area(List<V> p){return Math.abs(normalY(p))*.5;}
  private static double normalY(List<V> p) {
    V o=p.get(0);double sum=0;
    for(int i=1;i+1<p.size();i++){V a=p.get(i).sub(o),b=p.get(i+1).sub(o);sum+=a.z()*b.x()-a.x()*b.z();}
    return sum;
  }
  private RoadTerrainMesh(){}
}
