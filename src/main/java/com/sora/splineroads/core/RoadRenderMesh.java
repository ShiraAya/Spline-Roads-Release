package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Visual-only reduction; collision keeps the authoritative fine mesh. */
public final class RoadRenderMesh {
  public record Section(int x, int y, int z) {
    public V origin() {
      return new V(x * 64.0, y * 64.0, z * 64.0);
    }

    public static Section of(V p) {
      return new Section((int) Math.floor(p.x() / 64), 0, (int) Math.floor(p.z() / 64));
    }
  }

  public static int vertexCount(RoadSurface.Face face) {
    return face.points().size() == 4 ? 4 : 4 * (face.points().size() - 2);
  }

  public record Piece(
      List<RoadSurface.Face> pavement,
      List<RoadSurface.Face> detail,
      List<RoadSurface.Face> distant) {}

  public static Mesh simplify(Mesh mesh) {
    return simplify(mesh, 3, .025);
  }

  /** A width step has two one-sided samples. Neither reduction may bridge it. */
  private static boolean exactCrossSections(Mesh mesh) {
    return LaneDeck.hasOpenings(mesh);
  }

  /** Preserve the one-sided samples at each rectangular step, not the entire road. */
  private static NavigableSet<Double> protectedStations(Mesh mesh){
    var stations=new TreeSet<Double>();
    for(var cut:mesh.settings().options().lanePoints().cuts())if(cut.rectangular())
      for(double edge:new double[]{cut.begin(),cut.end()})
        for(var sample:mesh.samples())if(Math.abs(sample.distance()-edge)<.00003)stations.add(sample.distance());
    return stations;
  }

  /** Markings retain their exact three-block dash cadence; only the solid deck uses this. */
  private static final ThreadLocal<IdentityHashMap<Mesh,Mesh>> PAVEMENTS = ThreadLocal.withInitial(IdentityHashMap::new);
  /** Export jobs must not leave complete source meshes in a long-lived worker's
   * near-renderer cache. Nested calls restore the caller's own cache. */
  static final class PavementScope implements AutoCloseable {
    private final IdentityHashMap<Mesh,Mesh> previous=PAVEMENTS.get();
    PavementScope(){PAVEMENTS.set(new IdentityHashMap<>());}
    @Override public void close(){PAVEMENTS.set(previous);}
  }
  public static Mesh pavement(Mesh mesh) {
    var cache=PAVEMENTS.get();var found=cache.get(mesh);if(found!=null)return found;
    var reduced=pavementUncached(mesh);if(cache.size()>=128)cache.clear();cache.put(mesh,reduced);return reduced;
  }

  private static Mesh pavementUncached(Mesh mesh) {
    if(mesh.slicing()!=null||exactCrossSections(mesh))return mesh;
    var samples = mesh.samples();var protectedAt=protectedStations(mesh);
    List<Sample> out = new ArrayList<>();
    out.add(mesh.first());
    for (int from = 0; from < samples.size() - 1; ) {
      int end = from + 1;
      var boundary=protectedAt.higher(samples.get(from).distance()+1e-8);
      for (int candidate = end + 1; candidate < samples.size(); candidate++) {
        Sample a = samples.get(from), b = samples.get(candidate);
        if (b.distance() - a.distance() > 24 || boundary!=null&&b.distance()>boundary+1e-8) break;
        boolean exact = true;
        for (int k = from + 1; k < candidate && exact; k++) {
          Sample m = samples.get(k);
          double t = (m.distance() - a.distance()) / (b.distance() - a.distance());
          for (int side : new int[] {-1, 1})
            if (a.at(LaneDeck.edge(mesh,a,side), 0)
                    .mul(1 - t)
                    .add(b.at(LaneDeck.edge(mesh,b,side), 0).mul(t))
                    .distance(m.at(LaneDeck.edge(mesh,m,side), 0))
                > 1e-7) {
              exact = false;
              break;
            }
        }
        if (!exact) break;
        end = candidate;
      }
      out.add(samples.get(end));
      from = end;
    }
    return new Mesh(
        List.copyOf(out),
        mesh.settings(),
        mesh.min(),
        mesh.max(),
        mesh.length(),
        mesh.closed(),
        mesh.controlPoint(),mesh.controls(),mesh.reference());
  }

  private static Mesh simplify(Mesh mesh, double spacing, double error) {
    if(mesh.slicing()!=null||exactCrossSections(mesh))return mesh;
    List<Sample> out = new ArrayList<>();
    out.add(mesh.first());
    var protectedAt=protectedStations(mesh);
    double d = 0;
    while (d < mesh.length() - 1e-7) { RoadPlanningBudget.check();
      double next = Math.min(mesh.length(), (Math.floor((d + 1e-6) / spacing) + 1) * spacing);
      var boundary=protectedAt.higher(d+1e-8);if(boundary!=null)next=Math.min(next,boundary);
      Sample a = out.get(out.size() - 1), b = RoadStructures.sample(mesh, next);
      while (next - d > .45) {
        boolean good = true;
        for (double f : new double[] {.25, .5, .75}) {
          Sample m = RoadStructures.sample(mesh, d + (next - d) * f);
          for (int side : new int[] {-1, 1}) {
            V linear =
                a.at(LaneDeck.edge(mesh,a,side), 0).mul(1 - f).add(b.at(LaneDeck.edge(mesh,b,side), 0).mul(f));
            if (linear.distance(m.at(LaneDeck.edge(mesh,m,side), 0)) > error) {
              good = false;
              break;
            }
          }
        }
        if (good) break;
        next = (d + next) / 2;
        b = RoadStructures.sample(mesh, next);
      }
      out.add(b);
      d = next;
    }
    return new Mesh(
        List.copyOf(out),
        mesh.settings(),
        mesh.min(),
        mesh.max(),
        mesh.length(),
        mesh.closed(),
        mesh.controlPoint(),mesh.controls(),mesh.reference());
  }

  public record Layers(Piece shared,Piece surface) {}
  /** Shared furniture is built once; only asphalt and paint switch backend. */
  public static Map<Section,Layers> layers(RoadSurface.Geometry geometry,List<RoadStructures.Part> structures){
    return layersFromFaces(geometry,structureFaces(structures,false),structureFaces(structures,true));
  }
  public static Map<Section,Layers> layersFromFaces(RoadSurface.Geometry geometry,List<RoadSurface.Face> near,List<RoadSurface.Face> far){
    Map<Section,List<RoadSurface.Face>> pavement=new HashMap<>(),paint=new HashMap<>(),detail=new HashMap<>(),distant=new HashMap<>();
    for(var f:geometry.pavement())if(f.texture()==RoadSurface.Texture.PLAIN)add(pavement,f.color()==0xDCDCDC?upward(f):f);else{add(detail,f);add(distant,f);}
    for(var f:geometry.markings())add(paint,upward(f));
    for(var f:near)add(detail,f);for(var f:far)add(distant,f);
    Set<Section> keys=new HashSet<>(pavement.keySet());keys.addAll(paint.keySet());keys.addAll(detail.keySet());keys.addAll(distant.keySet());
    Map<Section,Layers> out=new HashMap<>();for(var k:keys){var lines=List.copyOf(paint.getOrDefault(k,List.of()));out.put(k,new Layers(
      new Piece(List.of(),List.copyOf(detail.getOrDefault(k,List.of())),List.copyOf(distant.getOrDefault(k,List.of()))),
      new Piece(List.copyOf(pavement.getOrDefault(k,List.of())),lines,lines)));}return out;
  }
  public static Map<Section,Piece> sections(RoadSurface.Geometry geometry,List<RoadStructures.Part> structures){
    Map<Section,Piece> out=new HashMap<>();layers(geometry,structures).forEach((k,l)->{
      var near=new ArrayList<>(l.surface().detail());near.addAll(l.shared().detail());var far=new ArrayList<>(l.surface().distant());far.addAll(l.shared().distant());
      out.put(k,new Piece(l.surface().pavement(),List.copyOf(near),List.copyOf(far)));});return out;
  }

  public static RoadSurface.Face upward(RoadSurface.Face f){
    if(RoadLighting.normal(f).y()>=0)return f;
    var points=new ArrayList<>(f.points());Collections.reverse(points);var uv=new ArrayList<>(f.uv());Collections.reverse(uv);
    return new RoadSurface.Face(points,f.color(),f.emissive(),f.texture(),uv);
  }

  private record End(
      long x, long y, long z, double width, double height, RoadStructures.Material material) {
    End(V v, RoadStructures.Part p) {
      this(
          Math.round(v.x() * 100000),
          Math.round(v.y() * 100000),
          Math.round(v.z() * 100000),
          p.width(),
          p.height(),
          p.material());
    }
  }

  /** Bounded-error visual joining; all authoritative parts and collision are retained. */
  public static List<RoadStructures.Part> compact(
      List<RoadStructures.Part> parts, boolean distant) {
    List<RoadStructures.Part> out = new ArrayList<>();
    Map<End, Integer> ends = new HashMap<>();
    Map<Integer,List<RoadStructures.Part>> runs=new HashMap<>();
    for (var p : parts) { RoadPlanningBudget.check();
      if(p.model().equals(RailbedFence.MODEL)||p.model().equals(RailwayCrossingSigns.MODEL)||TollModels.supports(p)||p.heights()!=null||p.material()==RoadStructures.Material.NOISE_BARRIER||(LaneClosureLandscape.paved(p)&&!RoadsideBypass.paved(p))){out.add(p);continue;}
      double length = p.b().sub(p.a()).horizontalLength();
      if (distant
          && p.material()==RoadStructures.Material.TACTILE
          && p.width() < .1
          && p.height() < .025) continue;
      End start = new End(p.a(), p);
      Integer at = ends.get(start);
      if (!p.pier() && length > 1e-6 && at != null) {
        var prev = out.get(at);
        var run=runs.get(at);
        if (canJoin(prev,p,run,distant)) {
          var merged =
              new RoadStructures.Part(
                  prev.a(),
                  p.b(),
                  p.width(),
                  p.height(),
                  false,
                  p.material(),
                  prev.frameA(),
                  p.frameB(),p.model());
          out.set(at, merged);
          run.add(p);
          ends.remove(start);
          ends.put(new End(p.b(), p), at);
          continue;
        }
      }
      if (!p.pier() && length > 1e-6) ends.put(new End(p.b(), p), out.size());
      if(!p.pier()&&length>1e-6)runs.put(out.size(),new ArrayList<>(List.of(p)));
      out.add(p);
    }
    return out;
  }

  private static boolean canJoin(RoadStructures.Part first,RoadStructures.Part next,
      List<RoadStructures.Part> run,boolean distant){
    if(first.heights()!=null||next.heights()!=null||run==null||!first.model().equals(next.model())||first.b().distance(next.a())>1e-6||first.a().distance(next.b())>(distant?64:8))return false;
    V before=run.get(run.size()-1).b().sub(run.get(run.size()-1).a()),after=next.b().sub(next.a());
    if(before.horizontalUnit().dot(after.horizontalUnit())<.985)return false;
    var end=run.get(run.size()-1).base();var start=next.base();
    if(end.get(3).distance(start.get(0))>1e-6||end.get(2).distance(start.get(1))>1e-6)return false;
    var merged=new RoadStructures.Part(first.a(),next.b(),first.width(),first.height(),false,first.material(),first.frameA(),next.frameB());
    var corners=merged.base();V chord=next.b().sub(first.a());double squared=chord.dot(chord);
    // A curved roof and its lights must stay on their exact persisted planes.
    // Independent bounded-error chord joining can make the light enter the roof
    // and the roof enter retained stone even with correct source geometry.
    double error=first.material()==RoadStructures.Material.TUNNEL||first.material()==RoadStructures.Material.LAMP||first.material()==RoadStructures.Material.SIGN_WHITE||first.model().startsWith("sr:cycle_")&&!first.model().equals("sr:cycle_planter")?1e-7:distant?.04:.0125;
    for(var part:run)if(!within(part,corners,first.a(),chord,squared,error))return false;
    return within(next,corners,first.a(),chord,squared,error);
  }
  private static boolean within(RoadStructures.Part part,List<V> corners,V origin,V chord,double squared,double error){
    var base=part.base();
    double a=part.a().sub(origin).dot(chord)/squared,b=part.b().sub(origin).dot(chord)/squared;
    return lerp(corners.get(0),corners.get(3),a).distance(base.get(0))<=error
        &&lerp(corners.get(1),corners.get(2),a).distance(base.get(1))<=error
        &&lerp(corners.get(0),corners.get(3),b).distance(base.get(3))<=error
        &&lerp(corners.get(1),corners.get(2),b).distance(base.get(2))<=error;
  }
  private static V lerp(V a,V b,double t){return a.mul(1-t).add(b.mul(t));}

  private record Point(long x, long y, long z) implements Comparable<Point> {
    Point(V p) {
      this(Math.round(p.x() * 100000), Math.round(p.y() * 100000), Math.round(p.z() * 100000));
    }

    public int compareTo(Point p) {
      int c = Long.compare(x, p.x);
      if (c == 0) c = Long.compare(y, p.y);
      return c == 0 ? Long.compare(z, p.z) : c;
    }
  }

  private record Cap(List<Point> points, RoadSurface.Texture texture, int color) {
    Cap(RoadSurface.Face face) {
      this(face.points().stream().map(Point::new).sorted().toList(), face.texture(), face.color());
    }
  }

  public static List<RoadSurface.Face> structureFaces(
      List<RoadStructures.Part> parts, boolean distant) {
    var compacted=compact(parts,distant);return structureFacesCompacted(compacted,compacted,distant);
  }
  /** A window selects globally compacted parts; its joins keep the full-road curvature. */
  /** Immutable sidewalk adjacency for one globally compacted road/LOD. */
  public static final class StructureContext {
    private final SidewalkFaces walks;
    public StructureContext(List<RoadStructures.Part> context){walks=new SidewalkFaces(context);}
    public long bytes(){return walks.bytes();}
  }
  public static List<RoadSurface.Face> structureFacesCompacted(List<RoadStructures.Part> compacted,List<RoadStructures.Part> context,boolean distant){
    return structureFacesCompacted(compacted,new StructureContext(context),distant);
  }
  public static List<RoadSurface.Face> structureFacesCompacted(List<RoadStructures.Part> compacted,StructureContext context,boolean distant){
    List<RoadSurface.Face> faces = new ArrayList<>();
    Map<Cap, Integer> caps = new HashMap<>();
    var biasByFace=new IdentityHashMap<RoadSurface.Face,Double>();
    var walks=context.walks;
    for (var part : compacted) { RoadPlanningBudget.check();
      var solid = part.material()==RoadStructures.Material.NOISE_BARRIER?RoadNoiseModel.faces(part,distant):distant && RoadSignals.signal(part) ? signalHull(part) : part.faces();
      double bias=part.material()==RoadStructures.Material.TUNNEL?.006:part.model().equals("sr:auto_light")?.009:0;
      for(var face:solid)if(bias>0)biasByFace.put(face,bias);
      for (int i = 0; i < solid.size(); i++) {
        if(i==0&&LaneClosureLandscape.paved(part))continue; // top belongs to the asphalt backend
        var face = solid.get(i);
        if(i==0&&part.material()==RoadStructures.Material.TACTILE)face=new RoadSurface.Face(face.points(),face.color(),face.emissive(),RoadSurface.Texture.WALK_TACTILE,face.uv());
        if(SidewalkFaces.walk(part)&&i>=2){faces.addAll(walks.exposed(part,face));continue;}
        // Leaf alpha holes can reveal an adjoining end face; retain those at close range.
        if (!part.model().equals(RailbedFence.MODEL) && !part.pier() && !TollModels.supports(part) && part.material()!=RoadStructures.Material.NOISE_BARRIER && !RoadSignals.signal(part) && !RoadPoleModel.support(part)
            && face.texture() != RoadSurface.Texture.LEAVES && Math.abs(RoadLighting.normal(face).y())<.5) {
          var key = new Cap(face);
          Integer opposite = caps.remove(key);
          if (opposite != null) {
            var previous = faces.get(opposite).points();
            V a = previous.get(1).sub(previous.get(0)), b = previous.get(2).sub(previous.get(0));
            V c = face.points().get(1).sub(face.points().get(0)),
                d = face.points().get(2).sub(face.points().get(0));
            V n =
                new V(
                    a.y() * b.z() - a.z() * b.y(),
                    a.z() * b.x() - a.x() * b.z(),
                    a.x() * b.y() - a.y() * b.x());
            V m =
                new V(
                    c.y() * d.z() - c.z() * d.y(),
                    c.z() * d.x() - c.x() * d.z(),
                    c.x() * d.y() - c.y() * d.x());
            if (n.dot(m) < 0) faces.set(opposite, null);
            else caps.put(key, opposite); // repeated coplanar outward face has one owner
            continue;
          }
          caps.put(key, faces.size());
        }
        // Exterior stone can meet a lining face exactly at an integer voxel plane.
        // A render-only normal bias gives concrete (and recessed lights) one owner;
        // the persisted geometry, four-metre air space and collision remain unchanged.
        faces.add(face);
      }
    }
    faces.removeIf(Objects::isNull);
    for(double bias:new double[]{.006,.009}){
      var group=faces.stream().filter(f->biasByFace.getOrDefault(f,0.0)==bias).toList();
      var biased=closedBias(group,bias);var replacements=new IdentityHashMap<RoadSurface.Face,RoadSurface.Face>();
      for(int i=0;i<group.size();i++)replacements.put(group.get(i),biased.get(i));
      for(int i=0;i<faces.size();i++)faces.set(i,replacements.getOrDefault(faces.get(i),faces.get(i)));
    }
    return faces;
  }

  /** Bias a closed solid, not six independent planes. Every incident face uses
   * the same displaced corner, including triangular boolean-cut fragments. */
  private static List<RoadSurface.Face> closedBias(List<RoadSurface.Face> faces,double amount){
    var normals=new HashMap<Point,List<V>>();
    for(var face:faces){V normal=RoadLighting.normal(face);for(var p:face.points()){
      var at=normals.computeIfAbsent(new Point(p),k->new ArrayList<>());
      if(at.stream().noneMatch(n->n.dot(normal)>.999999))at.add(normal);
    }}
    var corners=new HashMap<Point,V>();
    normals.forEach((p,ns)->{
      V sum=new V(0,0,0);for(var n:ns)sum=sum.add(n);
      double limit=Double.POSITIVE_INFINITY;for(var n:ns)limit=Math.min(limit,sum.dot(n));
      V delta=limit>1e-6?sum.mul(amount/limit):new V(0,0,0);
      double length=delta.distance(new V(0,0,0));if(length>amount*4)delta=delta.mul(amount*4/length);
      corners.put(p,delta);
    });
    return faces.stream().map(f->new RoadSurface.Face(f.points().stream().map(p->p.add(corners.get(new Point(p)))).toList(),f.color(),f.emissive(),f.texture(),f.uv())).toList();
  }

  /** Far signals retain their silhouette and separately rendered live lenses,
   * but not thousands of authored screws, hoods and hidden internal faces. */
  private static List<RoadSurface.Face> signalHull(RoadStructures.Part part) {
    V front=part.b().sub(part.a()).horizontalUnit(),side=front.left();
    var points=part.faces().stream().flatMap(f->f.points().stream()).toList();
    double x0=Double.POSITIVE_INFINITY,x1=-x0,y0=x0,y1=-x0,z0=x0,z1=-x0;
    for(V p:points){V q=p.sub(part.a());double x=q.dot(side),z=q.dot(front);x0=Math.min(x0,x);x1=Math.max(x1,x);y0=Math.min(y0,q.y());y1=Math.max(y1,q.y());z0=Math.min(z0,z);z1=Math.max(z1,z);}
    double lens=RoadSignalModel.faces(part,true,0).stream().flatMap(f->f.points().stream()).mapToDouble(v->v.sub(part.a()).dot(front)).min().orElse(z1);
    z1=Math.max(z0+.02,Math.min(z1,lens-.025));
    V a=part.a().add(side.mul((x0+x1)/2)).add(front.mul(z0)).add(new V(0,y0,0));
    V b=part.a().add(side.mul((x0+x1)/2)).add(front.mul(z1)).add(new V(0,y0,0));
    return new RoadStructures.Part(a,b,x1-x0,y1-y0,false,RoadStructures.Material.DARK_STEEL).faces();
  }

  /** Clip both LODs to the same spatial boundaries: independently merged faces must
   * never change ownership while neighbouring sections use different LODs. */
  private static void add(Map<Section, List<RoadSurface.Face>> map, RoadSurface.Face f) {
    RoadPlanningBudget.check();
    double minX=f.points().stream().mapToDouble(V::x).min().orElseThrow(),maxX=f.points().stream().mapToDouble(V::x).max().orElseThrow();
    double minZ=f.points().stream().mapToDouble(V::z).min().orElseThrow(),maxZ=f.points().stream().mapToDouble(V::z).max().orElseThrow();
    for(int x=(int)Math.floor(minX/64);x<=(int)Math.floor((maxX-1e-9)/64)||x==(int)Math.floor(minX/64);x++)
      for(int z=(int)Math.floor(minZ/64);z<=(int)Math.floor((maxZ-1e-9)/64)||z==(int)Math.floor(minZ/64);z++){
        var v=new ArrayList<CutVertex>();for(int i=0;i<f.points().size();i++)v.add(new CutVertex(f.points().get(i),f.uv().isEmpty()?null:f.uv().get(i)));
        List<CutVertex> cut=clip(v,0,x*64,true);cut=clip(cut,0,(x+1)*64,false);cut=clip(cut,2,z*64,true);cut=clip(cut,2,(z+1)*64,false);
        if(cut.size()<3)continue;
        V o=cut.get(0).p();double area=0;for(int i=1;i+1<cut.size();i++){V a=cut.get(i).p().sub(o),b=cut.get(i+1).p().sub(o);area+=new V(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x()).distance(new V(0,0,0));}
        if(area<1e-12)continue;
        map.computeIfAbsent(new Section(x,0,z),k->new ArrayList<>()).add(new RoadSurface.Face(cut.stream().map(CutVertex::p).toList(),f.color(),f.emissive(),f.texture(),f.uv().isEmpty()?List.of():cut.stream().map(CutVertex::uv).toList()));
      }
  }
  private record CutVertex(V p,RoadSurface.UV uv){}
  private static List<CutVertex> clip(List<CutVertex> in,int axis,double edge,boolean above){
    var out=new ArrayList<CutVertex>();if(in.isEmpty())return out;
    CutVertex a=in.get(in.size()-1);double da=(axis==0?a.p().x():a.p().z())-edge;
    for(var b:in){double db=(axis==0?b.p().x():b.p().z())-edge;boolean ai=above?da>=-1e-9:da<=1e-9,bi=above?db>=-1e-9:db<=1e-9;
      if(ai!=bi){double t=da/(da-db);V p=a.p().mul(1-t).add(b.p().mul(t));var uv=a.uv()==null?null:new RoadSurface.UV(a.uv().u()*(1-t)+b.uv().u()*t,a.uv().v()*(1-t)+b.uv().v()*t);out.add(new CutVertex(p,uv));}
      if(bi)out.add(b);a=b;da=db;
    }return out;
  }

  private RoadRenderMesh() {}
}
