package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** The interior under the SAME two-metre faceted lining used by RoadInfrastructure.
 * A projected centre + independent maximum deck height is not a real ceiling point.
 * Values never retain the weak Mesh key; queries do not read a Minecraft world.
 */
public final class RoadTunnelSpace {
  private static final double EPS=1e-8;
  private record Vertex(double x,double z,double floor,double roof) {
    Vertex mix(Vertex b,double t){return new Vertex(x+(b.x-x)*t,z+(b.z-z)*t,floor+(b.floor-floor)*t,roof+(b.roof-roof)*t);}
  }
  private record Patch(List<Vertex> polygon,double minX,double minZ,double maxX,double maxZ) {}
  private record Column(List<List<Vertex>> polygons,double top) {}
  private static final class Prepared {
    final Map<Long,List<Patch>> grid=new HashMap<>();
    final Map<Long,Column> columns=new LinkedHashMap<>(64,.75f,true){
      protected boolean removeEldestEntry(Map.Entry<Long,Column> e){return size()>2048;}
    };
    int references;
    Prepared(Mesh mesh){
      var config=mesh.settings().options().infrastructure();
      for(double d=0;d<mesh.length()-1e-6;d+=2){
        var a=RoadStructures.sample(mesh,d);var b=RoadStructures.sample(mesh,Math.min(mesh.length(),d+2));
        var footprint=List.of(a.at(a.halfWidth(),0),a.at(-a.halfWidth(),0),b.at(-b.halfWidth(),0),b.at(b.halfWidth(),0));
        int bands=RoadInfrastructure.roofBands(a.halfWidth(),b.halfWidth());
        for(int i=0;i<bands;i++){
          double u=-1+2.0*i/bands,v=-1+2.0*(i+1)/bands;
          var ah=vertex(a,config,v);var al=vertex(a,config,u);var bl=vertex(b,config,u);var bh=vertex(b,config,v);
          // Match Part.base() and the diagonal used by both faces and structural raster.
          for(var tri:List.of(List.of(ah,al,bl),List.of(ah,bl,bh))){
            var poly=clipFootprint(tri,footprint);if(area(poly)<EPS)continue;
            add(poly);
          }
        }
      }
    }
    Prepared(Mesh mesh,List<RoadAutoTunnels.RoofRegion> regions){
      for(var r:regions){if(r.to()>mesh.length()+1e-6)continue;
        var a=RoadStructures.sample(mesh,r.from());var b=RoadStructures.sample(mesh,r.to());
        var al=profile(a,a.halfWidth(),r.first());var ar=profile(a,-a.halfWidth(),r.first());var bl=profile(b,b.halfWidth(),r.last());var br=profile(b,-b.halfWidth(),r.last());
        add(List.of(al,ar,br));add(List.of(al,br,bl));
      }
    }
    private void add(List<Vertex> poly){
            var patch=new Patch(List.copyOf(poly),poly.stream().mapToDouble(Vertex::x).min().orElseThrow(),poly.stream().mapToDouble(Vertex::z).min().orElseThrow(),poly.stream().mapToDouble(Vertex::x).max().orElseThrow(),poly.stream().mapToDouble(Vertex::z).max().orElseThrow());
            for(int x=tile(patch.minX);x<=tile(patch.maxX);x++)for(int z=tile(patch.minZ);z<=tile(patch.maxZ);z++){
              grid.computeIfAbsent(key(x,z),k->new ArrayList<>()).add(patch);references++;
            }
    }
    synchronized Column column(int x,int z){
      long k=key(x,z);var old=columns.get(k);if(old!=null)return old;
      var polygons=new ArrayList<List<Vertex>>();double top=Double.NEGATIVE_INFINITY;
      for(var patch:grid.getOrDefault(key(tile(x),tile(z)),List.of())){
        if(patch.minX>=x+1-EPS||patch.maxX<=x+EPS||patch.minZ>=z+1-EPS||patch.maxZ<=z+EPS)continue;
        var p=clip(patch.polygon,a->a.x-x);p=clip(p,a->x+1-a.x);p=clip(p,a->a.z-z);p=clip(p,a->z+1-a.z);
        if(area(p)<EPS)continue;polygons.add(List.copyOf(p));for(var a:p)top=Math.max(top,a.roof);
      }
      var result=new Column(List.copyOf(polygons),top);columns.put(k,result);return result;
    }
  }
  private static final WeakIdentityCache<Mesh,Prepared> CACHE=new WeakIdentityCache<>(48,350_000,p->p.references);
  private static Prepared prepared(Mesh mesh){return CACHE.get(mesh,Prepared::new);}
  public static void clearPreparedCache(){CACHE.clear();}
  /** Exact maximum of the roof plane over this column's interior footprint. */
  public static double ceiling(Mesh mesh,int x,int z){return prepared(mesh).column(x,z).top;}
  /** Positive-volume overlap; mere contact with floor/roof/portal plane stays exterior. */
  public static boolean intersects(Mesh mesh,int x,int y,int z,double height){
    if(mesh.settings().structure()!=Structure.TUNNEL||height<=0)return false;
    for(var polygon:prepared(mesh).column(x,z).polygons){
      var p=clip(polygon,a->a.roof-y-EPS);p=clip(p,a->y+height-a.floor-EPS);
      if(area(p)>EPS)return true;
    }
    return false;
  }
  /** Persisted variable ceiling, shared by dry-air reservation and original terrain retention. */
  public static final class Profiled {
    private final Prepared prepared;
    public Profiled(Mesh mesh,List<RoadAutoTunnels.RoofRegion> regions){prepared=new Prepared(mesh,regions);}
    public double ceiling(int x,int z){return prepared.column(x,z).top;}
    public double floor(int x,int z){return prepared.column(x,z).polygons.stream().flatMap(List::stream).mapToDouble(Vertex::floor).min().orElse(Double.POSITIVE_INFINITY);}
    public boolean intersects(int x,int y,int z,double height){
      for(var polygon:prepared.column(x,z).polygons){var p=clip(polygon,a->a.roof-y-EPS);p=clip(p,a->y+height-a.floor-EPS);if(area(p)>EPS)return true;}return false;
    }
  }
  private static Vertex profile(Sample at,double lateral,double height){var p=at.at(lateral,0);return new Vertex(p.x(),p.z(),p.y(),p.y()+height);}
  private static Vertex vertex(Sample sample,RoadInfrastructure.Config c,double u){
    var p=sample.at((sample.halfWidth()+1.55)*u,0);
    return new Vertex(p.x(),p.z(),p.y(),p.y()+RoadInfrastructure.roofHeight(c,sample.halfWidth(),u));
  }
  private interface Distance {double of(Vertex a);}
  private static List<Vertex> clip(List<Vertex> polygon,Distance distance){
    if(polygon.isEmpty())return polygon;var out=new ArrayList<Vertex>();var prev=polygon.get(polygon.size()-1);double before=distance.of(prev);
    for(var now:polygon){double after=distance.of(now);if((before>=0)!=(after>=0))out.add(prev.mix(now,before/(before-after)));if(after>=0)out.add(now);prev=now;before=after;}return out;
  }
  private static List<Vertex> clipFootprint(List<Vertex> p,List<V> footprint){
    double orient=0;var origin=footprint.get(0);for(int i=1;i+1<footprint.size();i++)orient+=cross(footprint.get(i).x()-origin.x(),footprint.get(i).z()-origin.z(),footprint.get(i+1).x()-origin.x(),footprint.get(i+1).z()-origin.z());
    double sign=Math.signum(orient);
    for(int i=0;i<footprint.size();i++){var a=footprint.get(i);var b=footprint.get((i+1)%footprint.size());p=clip(p,v->sign*cross(b.x()-a.x(),b.z()-a.z(),v.x-a.x(),v.z-a.z()));}return p;
  }
  private static double area(List<Vertex> p){if(p.size()<3)return 0;var a=p.get(0);double sum=0;for(int i=1;i+1<p.size();i++)sum+=cross(p.get(i).x-a.x,p.get(i).z-a.z,p.get(i+1).x-a.x,p.get(i+1).z-a.z);return Math.abs(sum)*.5;}
  private static double cross(double ax,double az,double bx,double bz){return ax*bz-az*bx;}
  private static int tile(double x){return (int)Math.floor(x/8);}
  private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
  private RoadTunnelSpace(){}
}
