package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Cached junction mesh. Coplanar overlap has one owner; internal stripes and walls are clipped. */
public final class RoadSurface {
  public enum Texture {
    PLAIN, WALK_TACTILE,
    CONCRETE,
    METAL,
    SOIL,
    LEAVES,
    SIGNAL_HOUSING,
    SIGNAL_GREEN,
    SIGNAL_RED,
    SIGNAL_AMBER,
    SIGNAL_ATLAS, WALK_STONE_BRICKS, WALK_BRICKS, WALK_ANDESITE, WALK_DIORITE, WALK_GRANITE, WALK_SMOOTH_STONE, NOISE_BARRIER, NOISE_BARRIER_GLASS, OAK_LOG, BIRCH_LOG, SPRUCE_LOG, CHERRY_LOG, OAK_LEAVES, BIRCH_LEAVES, SPRUCE_LEAVES, CHERRY_LEAVES, FLOWERS
  }

  public record UV(double u, double v) {}

  public record Face(List<V> points, int color, boolean emissive, Texture texture, List<UV> uv) {
    public Face(List<V> points, int color, boolean emissive, Texture texture) {
      this(points, color, emissive, texture, List.of());
    }
    public Face(List<V> points, int color) {
      this(points, color, false, Texture.PLAIN);
    }

    public Face(List<V> points, int color, boolean emissive) {
      this(points, color, emissive, Texture.PLAIN);
    }
  }

  public record Geometry(List<Face> pavement, List<Face> markings) {}

  /** Optional export at the final clipping boundary; near-only builds allocate no LOD data. */
  @FunctionalInterface public interface SlabSink {void accept(List<V> top,double thickness,int color);}

  /** Ground closure fills are real pavement in both VBO and terrain rendering.
   * Neighboring decks own their coplanar overlap; sides remain structural solids. */
  public static Geometry closurePavement(Geometry geometry,List<RoadStructures.Part> parts,List<Mesh> decks){
    return closurePavement(geometry,parts,decks,null);
  }
  public static Geometry closurePavement(Geometry geometry,List<RoadStructures.Part> parts,List<Mesh> decks,SlabSink sink){
    if(parts.stream().noneMatch(LaneClosureLandscape::paved))return geometry;
    var pavement=new ArrayList<>(geometry.pavement());
    exportClosureSlabs(parts,decks,(poly,depth,color)->{pavement.add(new Face(poly,color));if(sink!=null)sink.accept(poly,depth,color);});
    return new Geometry(List.copyOf(pavement),geometry.markings());
  }
  /** Uses the same final closure clipping without retaining near face lists. */
  public static void exportClosureSlabs(List<RoadStructures.Part> parts,List<Mesh> decks,SlabSink sink){
    exportClosureSlabs(parts,decks,sink,Long.MAX_VALUE);
  }
  public static void exportClosureSlabs(List<RoadStructures.Part> parts,List<Mesh> decks,SlabSink sink,long workspace){
    if(parts.stream().noneMatch(LaneClosureLandscape::paved))return;
    var occupied=new Grid(decks,workspace);
    for(var part:RoadRenderMesh.compact(parts.stream().filter(LaneClosureLandscape::paved).toList(),false)){
      var top=part.base().stream().map(v->v.add(new V(0,part.height(),0))).toList();
      for(int i=1;i<top.size()-1;i++)for(var poly:visible(List.of(top.get(0),top.get(i),top.get(i+1)),occupied,.025))
        sink.accept(poly,part.height(),0xDCDCDC);
    }
  }

  /** A host owns its paint at a lane connector, including when the host is itself a connector. */
  public static boolean higherPriority(UUID candidate,Mesh a,UUID current,Mesh b){
    if(candidate.equals(current))return false;
    if((a.settings().style()==Style.RAILBED)!=(b.settings().style()==Style.RAILBED))return a.settings().style()!=Style.RAILBED;
    var am=a.settings().options().lanePoints();var bm=b.settings().options().lanePoints();
    boolean ar=am.link()!=null||a.settings().style().ramp(),br=bm.link()!=null||b.settings().style().ramp();
    if(ar!=br)return !ar;
    // Compare ONE globally transitive key. Never combine pairwise host tests with UUIDs:
    // that creates A>B>C>A even for an acyclic host chain and removes all three decks.
    int ad=am.link()==null?0:Math.max(1,am.priorityDepth());
    int bd=bm.link()==null?0:Math.max(1,bm.priorityDepth());
    return ad!=bd?ad<bd:candidate.compareTo(current)<0;
  }

  private record Cut(List<V> points, double minX, double minZ, double maxX, double maxZ) {
    Cut(List<V> p) {
      this(
          p,
          p.stream().mapToDouble(V::x).min().orElseThrow(),
          p.stream().mapToDouble(V::z).min().orElseThrow(),
          p.stream().mapToDouble(V::x).max().orElseThrow(),
          p.stream().mapToDouble(V::z).max().orElseThrow());
    }

    double height(V p) {
      V a = points.get(0), b = points.get(1).sub(a), c = points.get(2).sub(a), q = p.sub(a);
      double det = b.x() * c.z() - b.z() * c.x();
      return a.y()
          + ((q.x() * c.z() - q.z() * c.x()) * b.y() + (b.x() * q.z() - b.z() * q.x()) * c.y())
              / det;
    }

    boolean overlaps(Cut b) {
      return maxX >= b.minX - 1e-8
          && b.maxX >= minX - 1e-8
          && maxZ >= b.minZ - 1e-8
          && b.maxZ >= minZ - 1e-8;
    }
  }

  private static final class Grid {
    final Map<Long, List<Cut>> cells = new HashMap<>();
    final long limit;long bytes=4096;

    Grid(List<Mesh> meshes) {this(meshes,Long.MAX_VALUE);}
    Grid(List<Mesh> meshes,long limit) {
      this.limit=limit;
      for (Mesh original : meshes) {
        Mesh mesh=RoadRenderMesh.pavement(original);
        for (int i = 1; i < mesh.samples().size(); i++) {
          Sample a = mesh.samples().get(i - 1), b = mesh.samples().get(i);
          for(var strip:LaneDeck.strips(mesh,a,b)) {
            add(List.of(strip.al(),strip.ar(),strip.br()));add(List.of(strip.al(),strip.br(),strip.bl()));
          }
        }
      }
    }

    void add(List<V> points) {
      if (Math.abs(JunctionPaint.area(points)) < 1e-9) return;
      if(limit!=Long.MAX_VALUE)charge(1024);
      Cut c = new Cut(ccw(points));
      for (int x = cell(c.minX); x <= cell(c.maxX); x++)
        for (int z = cell(c.minZ); z <= cell(c.maxZ); z++){
          if(limit!=Long.MAX_VALUE){RoadPlanningBudget.check();charge(32+(cells.containsKey(key(x,z))?0:256));}
          cells.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(c);
        }
    }
    void charge(long amount){temporary(amount);bytes+=amount;}
    void temporary(long amount){if(amount<0||amount>limit-bytes)throw new IllegalArgumentException("LOD surface workspace budget exceeded");}

    Set<Cut> candidates(List<V> points) {
      Cut box = new Cut(points);
      Set<Cut> found = new LinkedHashSet<>();
      for (int x = cell(box.minX); x <= cell(box.maxX); x++)
        for (int z = cell(box.minZ); z <= cell(box.maxZ); z++)
          for (Cut c : cells.getOrDefault(key(x, z), List.of())) if (c.overlaps(box)){if(limit!=Long.MAX_VALUE)temporary((found.size()+1)*128L);found.add(c);}
      return found;
    }

    static int cell(double v) {
      return (int) Math.floor(v / 8);
    }

    static long key(int x, int z) {
      return ((long) x << 32) ^ (z & 0xffffffffL);
    }
  }

  /** Indexed clipping for old guidance; use the complete face, including dash end caps. */
  public static final class PaintMask {
    private final Grid grid=new Grid(List.of());
    public PaintMask(List<List<V>> polygons){polygons.forEach(grid::add);}
    public List<List<V>> clip(List<V> face){return visible(face,grid,.35);}
    /** Explicit XZ ownership masks also clip raised furniture and sloped surfaces. */
    public List<List<V>> clipXZ(List<V> face){return visible(face,grid,Double.POSITIVE_INFINITY);}
  }

  public static Geometry custom(Geometry base, Mesh mesh, List<Face> paint) {
    List<Face> clipped = new ArrayList<>();
    Grid grid = new Grid(List.of(mesh));
    for (Face f : paint) paintOnRoad(clipped, new RoadJunction.Paint(f.points(), f.color()), grid);
    if(!mesh.settings().options().levelCrossings().isEmpty()){
      clipped=clipCrossingPaint(clipped,new PaintMask(RailwayCrossings.paintCuts(mesh)));
      for(var line:RailwayCrossings.stopLines(mesh))paintOnRoad(clipped,line,grid);
    }
    return new Geometry(base.pavement(), List.copyOf(clipped));
  }

  public static boolean painted(Mesh mesh,double station){
    double d=station+mesh.settings().options().ends().paintPhase()+mesh.settings().options().ends().trimmedStart();
    return ((d%6)+6)%6<3;
  }
  /** Split shifted dashes at their exact ends; pavement sampling is unchanged. */
  private static List<Sample> markingSamples(Mesh mesh){
    double phase=mesh.settings().options().ends().paintPhase()+mesh.settings().options().ends().trimmedStart();
    if(Math.abs(phase%3)<1e-8)return RoadAttachments.splitPaint(mesh,mesh.samples());
    var out=new ArrayList<Sample>();var samples=mesh.samples();out.add(samples.get(0));
    for(int i=1;i<samples.size();i++){
      double a=samples.get(i-1).distance(),b=samples.get(i).distance();
      for(double at=(Math.floor((a+phase)/3)+1)*3-phase;at<b-1e-7;at+=3)if(at>a+1e-7)out.add(RoadStructures.sample(mesh,at));
      out.add(samples.get(i));
    }
    return RoadAttachments.splitPaint(mesh,out);
  }
  private static boolean overrideLine(List<Face> out,Mesh mesh,Sample a,Sample b,String key,double x,double y,Grid cuts){
    var e=RoadAttachments.line(mesh,(a.distance()+b.distance())/2,key);var p=e.pattern();
    if(key.equals("center:-1")||key.equals("center:1")){
      var other=RoadAttachments.line(mesh,(a.distance()+b.distance())/2,key.equals("center:-1")?"center:1":"center:-1");
      if(p==RoadLaneLines.Pattern.DEFAULT&&other.pattern().mixed())return true;
      if(p.mixed()&&other.pattern()==RoadLaneLines.Pattern.DEFAULT){x=RoadProfile.layout(mesh,a).medianCenter();y=RoadProfile.layout(mesh,b).medianCenter();}
    }
    if(p==RoadLaneLines.Pattern.DEFAULT)return false;
    if(p==RoadLaneLines.Pattern.NONE||p.dashed()&&!painted(mesh,(a.distance()+b.distance())/2))return true;
    if(p.doubled())for(int side:new int[]{-1,1}){if(p.mixed()&&p.dashedSide(side)&&!painted(mesh,(a.distance()+b.distance())/2))continue;stripe(out,a,b,x+side*(e.width()+.06),y+side*(e.width()+.06),e.width(),p.yellow(),cuts);}
    else stripe(out,a,b,x,y,e.width(),p.yellow(),cuts);return true;
  }
  public static Geometry build(Mesh mesh, List<Mesh> higherPriority, List<Mesh> neighbors) {
    return build(mesh,higherPriority,neighbors,new RoadsideBypass(mesh,neighbors));
  }
  public static Geometry build(Mesh mesh,List<Mesh> higherPriority,List<Mesh> neighbors,RoadsideBypass bypass){
    return buildWindow(mesh,higherPriority,neighbors,bypass,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);
  }
  public static Geometry build(Mesh mesh,List<Mesh> higherPriority,List<Mesh> neighbors,RoadsideBypass bypass,SlabSink sink){
    return new WindowContext(mesh,higherPriority,neighbors,bypass).build(Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,sink);
  }
  /** Export only final physical pavement. Do not construct stripes, arrows,
   * signals, side-face arrays or the near renderer's marking context. */
  public static void exportSlabs(Mesh mesh,List<Mesh> higherPriority,SlabSink sink){
    exportSlabs(mesh,higherPriority,sink,Long.MAX_VALUE);
  }
  public static void exportSlabs(Mesh mesh,List<Mesh> higherPriority,SlabSink sink,long workspace){
    var owners=new Grid(higherPriority,workspace);var deck=RoadRenderMesh.pavement(mesh).samples();
    for(int i=1;i<deck.size();i++){RoadPlanningBudget.check();for(var strip:LaneDeck.strips(mesh,deck.get(i-1),deck.get(i)))slab(strip,owners,mesh.settings().thickness(),sink);}
  }
  private static void slab(LaneDeck.Strip strip,Grid owners,double thickness,SlabSink sink){
    V l=strip.al(),r=strip.ar(),ll=strip.bl(),rr=strip.br();
    for(var triangle:List.of(List.of(l,r,rr),List.of(l,rr,ll)))
      if(area(triangle)>1e-9)for(var poly:visible(triangle,owners,.025))sink.accept(poly,thickness,0xDCDCDC);
  }
  /** Visual work window; original samples, planes, cut slots and phase remain authoritative. */
  public static Geometry buildWindow(Mesh mesh,List<Mesh> higherPriority,List<Mesh> neighbors,RoadsideBypass bypass,double from,double to){
    return new WindowContext(mesh,higherPriority,neighbors,bypass).build(from,to);
  }
  /** Immutable joining/paint context reused by every window of the same road revision. */
  public static final class WindowContext {
    private final Mesh mesh;private final List<Mesh> higherPriority,neighbors;private final RoadsideBypass bypass;
    private final Grid owners,joined,dividers,defaultDividers;private Grid holes;
    private final RoadRailJoin edgeJoin,paintEdgeJoin;
    private final List<RoadSignals.Approach> approaches;
    private final List<Sample> samples,deck;
    private final List<RoadJunction.Zone> junctionZones;
    private final List<RoadJunction.EdgeZone> edgeZones;
    private final List<LaneRampPaint.Auxiliary> auxiliary;
    private final List<Face> globalPaint,bypassPaint,stopBars;
    private final PaintMask railwayStops;
    public WindowContext(Mesh mesh,List<Mesh> higherPriority,List<Mesh> neighbors,RoadsideBypass bypass){
      this.mesh=mesh;this.higherPriority=List.copyOf(higherPriority);this.neighbors=List.copyOf(neighbors);this.bypass=bypass;
    owners = new Grid(higherPriority); joined = new Grid(neighbors);
    edgeJoin=RoadRailJoin.paintBoundary(mesh,neighbors.stream().map(n->new RoadRailJoin.Neighbor(n,higherPriority.contains(n))).toList());
    paintEdgeJoin=RoadRailJoin.paint(mesh,neighbors.stream().map(n->new RoadRailJoin.Neighbor(n,higherPriority.contains(n))).toList());
    // Preserve the main road's dividers. Only subordinate branch markings are suppressed.
    List<Mesh> dividerCuts = new ArrayList<>(higherPriority.stream()
        .filter(other->mesh.settings().style().ramp()||!other.settings().style().ramp()).toList());
    var crossings = RoadJunction.intersections(mesh, neighbors);
    dividerCuts.addAll(crossings);
    for(var neighbor:neighbors)if(RailwayCrossings.any(mesh,neighbor))dividerCuts.add(neighbor);
    dividers = new Grid(dividerCuts);
    // Host dividers remain authoritative at an auxiliary merge. Boundary paint
    // has its own union clipping; a linked ramp alone cannot erase live lane dashes.
    defaultDividers=new Grid(dividerCuts);
    for(var neighbor:neighbors){for(var cut:RoadJunction.closedGuidance(neighbor,mesh,neighbors))defaultDividers.add(cut);for(var cut:RoadJunction.closedGuidance(mesh,neighbor,neighbors))defaultDividers.add(cut);}
    for(var neighbor:neighbors)if(LaneMerge.linkedTo(neighbor,mesh)||LaneMerge.linkedTo(mesh,neighbor)){
      for(int i=1;i<neighbor.samples().size();i++){
        var a=neighbor.samples().get(i-1);var b=neighbor.samples().get(i);
        double da=a.center().y()-RoadQueries.horizontal(mesh,a.center()).sample().center().y();
        double db=b.center().y()-RoadQueries.horizontal(mesh,b.center()).sample().center().y();
        if(Math.abs(da)<.06&&Math.abs(db)<.06)continue;
        for(var strip:LaneDeck.strips(neighbor,a,b)){
          defaultDividers.add(List.of(strip.al(),strip.ar(),strip.br()));defaultDividers.add(List.of(strip.al(),strip.br(),strip.bl()));
        }
      }
    }

    for (var cut : RoadJunction.terminalCuts(mesh)){dividers.add(cut);defaultDividers.add(cut);}
    approaches = RoadSignals.approaches(mesh, neighbors);
    for (var cut : RoadSignals.paintCuts(approaches)){dividers.add(cut);defaultDividers.add(cut);}
    samples = markingSamples(mesh);
    junctionZones = RoadJunction.dividerZones(mesh, neighbors);
    edgeZones = RoadJunction.edgeZones(mesh, neighbors);
    auxiliary=LaneRampPaint.auxiliary(mesh,neighbors);
    railwayStops=new PaintMask(RailwayCrossings.paintCuts(mesh));
    globalPaint=new ArrayList<>();
    List<Mesh> union = new ArrayList<>(neighbors);
    union.add(mesh);
    Grid paving = new Grid(union);
    for (var paint : RoadJunction.markings(mesh, higherPriority, neighbors, true)) {
      // Free lane-connector roads need a single owner in a coplanar throat to avoid
      // duplicate connector paint. Automatic/legacy interchange ramps are different:
      // their fork hatching is generated symmetrically from both branches and must not
      // change when UUID/construction ownership flips. Only real lane links use the
      // ownership clip; style.ramp() by itself is not a lane-connector identity.
      if(mesh.settings().options().lanePoints().link()!=null)
        for(var poly:visible(paint.points(),owners,.025))
          paintOnRoad(globalPaint,new RoadJunction.Paint(poly,paint.color()),paving);
      else paintOnRoad(globalPaint,paint,paving);
    }
    for (var paint : RoadJunction.arrows(mesh, neighbors, crossings)) {
      if(mesh.settings().options().lanePoints().link()==null)paintOnRoad(globalPaint,paint,paving);
      else for(var poly:visible(paint.points(),owners,.025))paintOnRoad(globalPaint,new RoadJunction.Paint(poly,paint.color()),paving);
    }

    for(var warning:LaneClosureWarnings.paint(mesh,neighbors))paintOnRoad(globalPaint,warning,paving);
    stopBars=new ArrayList<>();
    for(var line:RailwayCrossings.stopLines(mesh))paintOnRoad(stopBars,line,paving);

    if(LaneDeck.hasOpenings(mesh)) {
      holes=new Grid(List.of());var points=mesh.samples();
      for(int i=1;i<points.size();i++)for(var q:LaneDeck.holeQuads(mesh,points.get(i-1),points.get(i))) {
        holes.add(List.of(q.get(0),q.get(1),q.get(2)));holes.add(List.of(q.get(0),q.get(2),q.get(3)));
      }
    }
      deck=RoadRenderMesh.pavement(mesh).samples();bypassPaint=bypass.markings();
    }
    public long bytes(){return 4096L+(samples.size()+deck.size())*64L+(globalPaint.size()+bypassPaint.size())*512L+neighbors.stream().mapToLong(m->m.samples().size()*256L).sum();}
    public Geometry build(double from,double to){
      return build(from,to,null);
    }
    public Geometry build(double from,double to,SlabSink sink){
      List<Face> pavement=new ArrayList<>(),markings=new ArrayList<>();
    for (int i = 1; i < samples.size(); i++) { RoadPlanningBudget.check();
      Sample a = samples.get(i - 1), b = samples.get(i);
      if(!inWindow((a.distance()+b.distance())/2,from,to))continue;
      V l = a.at(a.halfWidth(), 0),
          r = a.at(-a.halfWidth(), 0),
          ll = b.at(b.halfWidth(), 0),
          rr = b.at(-b.halfWidth(), 0);
      if ((mesh.settings().style() == Style.UNMARKED || mesh.settings().style()==Style.TOLL || mesh.settings().style()==Style.RAILBED)) continue;
      for (int side : new int[] {-1, 1}) {
        var edgeLayout = RoadProfile.layout(mesh, a);
        boolean curb =
            RoadProfile.modern(mesh.settings().style())
                && edgeLayout.curbWidth() > 0
                && (edgeLayout.catalog().twoWay() || edgeLayout.outside() == side);
        double edgeA=edgeOffset(mesh,a,side)+side*bypass.offset(a.distance(),side),edgeB=edgeOffset(mesh,b,side)+side*bypass.offset(b.distance(),side);
        if ((!curb || edgeLayout.cycleWidth() > .01) && !LaneDeck.outerOpening(mesh,(a.distance()+b.distance())/2,side) && !overrideLine(markings,mesh,a,b,"edge:"+side,edgeA,edgeB,joined)) boundaryStripe(markings,a,b,side,edgeJoin,paintEdgeJoin,edgeZones,edgeA,edgeB);
      }
      Style style = mesh.settings().style();
      if (RoadProfile.modern(style)) {
        var la = RoadProfile.layout(mesh, a);
        var lb = RoadProfile.layout(mesh, b);
        boolean dash = painted(mesh,(a.distance()+b.distance())/2);
        // Classify the interval at its midpoint, not at the reference start. Reversing
        // construction direction must not change yellow/white treatment of the same tile.
        var middle=RoadProfile.layout(mesh,RoadStructures.sample(mesh,(a.distance()+b.distance())/2));
        var median = middle.catalog().median();
        if (median == RoadProfile.Median.DOUBLE_YELLOW && middle.median() < .12) {
          if(!overrideLine(markings,mesh,a,b,"center:-1",la.medianCenter()-(.14+la.median()/2),lb.medianCenter()-(.14+lb.median()/2),dividers))stripe(
              markings,
              a,
              b,
              la.medianCenter()-(.14 + la.median() / 2),
              lb.medianCenter()-(.14 + lb.median() / 2),
              .1,
              true,
              dividers);
          if(!overrideLine(markings,mesh,a,b,"center:1",la.medianCenter()+.14+la.median()/2,lb.medianCenter()+.14+lb.median()/2,dividers))stripe(markings, a, b, la.medianCenter()+.14 + la.median() / 2, lb.medianCenter()+.14 + lb.median() / 2, .1, true, dividers);
        } else if (median == RoadProfile.Median.DASHED_YELLOW) {
          if(!overrideLine(markings,mesh,a,b,"center:0",la.medianCenter(),lb.medianCenter(),dividers)&&dash)stripe(markings, a, b, la.medianCenter(), lb.medianCenter(), .12, true, dividers);
        }
        else if (middle.median() >= .12
            || median == RoadProfile.Median.RAIL
            || median == RoadProfile.Median.GREEN)
          for (int side : new int[] {-1, 1})
            if(!overrideLine(markings,mesh,a,b,"median:"+side,la.medianCenter()+side*(la.median()/2+.12),lb.medianCenter()+side*(lb.median()/2+.12),dividers))stripe(
                markings,
                a,
                b,
                la.medianCenter()+side * (la.median() / 2 + .12),
                lb.medianCenter()+side * (lb.median() / 2 + .12),
                .12,
                false,
                dividers);
        for (int side : la.outsideSides()) {
          if(la.cycleWidth()>.01&&Math.max(bypass.offset(a.distance(),side),bypass.offset(b.distance(),side))>1e-7)stripe(markings,a,b,la.outer(side)+side*bypass.offset(a.distance(),side),lb.outer(side)+side*bypass.offset(b.distance(),side),.15,false,joined);
          boolean extra=LaneRampPaint.auxiliaryAt(mesh,a,b,side,auxiliary);
          // RoadJunction.mainGuides owns the auxiliary separator, including its
          // original 1.5/3 cadence. A second 3/6 stripe here duplicated it.
          if (!extra && (la.shoulderWidth() > 0 || la.cycleWidth() > 0)&&!overrideLine(markings,mesh,a,b,"shoulder:"+side,la.outer(side),lb.outer(side),joined))
            boundaryStripe(markings,a,b,side,edgeJoin,paintEdgeJoin,edgeZones,la.outer(side),lb.outer(side));
          if (la.cycleWidth() > 0 && RoadTransitions.greenCycle(mesh,a)) {
            // Subtle bicycle-lane surfacing, clipped at junctions just like the white boundary.
            double separator=RoadStreetscape.separatorWidth(mesh.settings().options());
            double ia=la.outer(side)+side*(separator+.075+bypass.offset(a.distance(),side)),
                ib=lb.outer(side)+side*(separator+.075+bypass.offset(b.distance(),side));
            double ea=edgeAForCycle(mesh,a,side,bypass),eb=edgeAForCycle(mesh,b,side,bypass);
            // The painted lane is bounded by the actual white lines. A fixed
            // 2.5 m stripe covered the inner line but left an asphalt strip at
            // the outside rim, especially with curbs and changing cross sections.
            if((ea-ia)*side>0&&(eb-ib)*side>0){
              var quad=List.of(a.at(ia,0),a.at(ea,0),b.at(eb,0),b.at(ib,0));
              for(var triangle:List.of(List.of(quad.get(0),quad.get(1),quad.get(2)),List.of(quad.get(0),quad.get(2),quad.get(3))))
                for(var part:visible(triangle,joined,.06))markings.add(new Face(part,0x536F61));
            }
          }
        }
        if(mesh.settings().options().streetscape().parking()&&!RoadStreetscape.underground(mesh,(a.distance()+b.distance())/2))for(int side:la.outsideSides()){
          if(RoadStreetscape.raised(mesh,a,side)||RoadStreetscape.cut(mesh,a,side))continue;
          if(la.cycleWidth()<1||lb.cycleWidth()<1)continue;

          double phase=mesh.settings().options().ends().paintPhase();
          double station=Math.ceil((a.distance()+phase)/RoadStreetscape.parkingLength())*RoadStreetscape.parkingLength()-phase;
          if(station>=a.distance()-1e-7&&station<b.distance()-1e-7){
            Sample p=RoadStructures.sample(mesh,station);var parkingLayout=RoadProfile.layout(mesh,p);
            V x=p.at(parkingLayout.outer(side),-.012),y=p.at(side*(p.halfWidth()-(parkingLayout.curbWidth()>0?RoadProfile.curbExtent(parkingLayout,p,side):.3)),-.012);
            var faces=new ArrayList<Face>();JunctionPaint.line(faces,x,y,.12,0xEEEEDE);
            for(var face:faces){List<List<V>> remaining=List.of(face.points());for(var cut:joined.candidates(face.points())){var next=new ArrayList<List<V>>();for(var poly:remaining)next.addAll(subtract(poly,cut.points()));remaining=next;}for(var poly:remaining)markings.add(new Face(poly,face.color()));}
          }
        }
        // Pavement ownership clips duplicate fork markings. A blanket longitudinal exclusion
        // erased both owners' two-lane dividers throughout an otherwise valid shared throat.
        if ((!style.ramp() || a.halfWidth() + b.halfWidth() >= mesh.settings().width() * .85)
            && junctionZones.stream().noneMatch(zone->zone.contains((a.distance()+b.distance())/2))) {
          var aa = la.dividers();
          var bb = lb.dividers();
          for (int j = 0; j < Math.min(aa.size(),bb.size()); j++)
            if ((aa.get(j)>la.motorMin()+.12&&aa.get(j)<la.motorMax()-.12||bb.get(j)>lb.motorMin()+.12&&bb.get(j)<lb.motorMax()-.12||closedSlotBoundary(mesh,(a.distance()+b.distance())/2,(aa.get(j)+bb.get(j))/2)) && !overrideLine(markings,mesh,a,b,"divider:"+j,aa.get(j),bb.get(j),dividers) && (dash || closedSlotBoundary(mesh,(a.distance()+b.distance())/2,(aa.get(j)+bb.get(j))/2) || RoadSignals.solid(approaches, (a.distance() + b.distance()) / 2,
                (aa.get(j) + bb.get(j)-la.medianCenter()-lb.medianCenter()) / 2)))
              stripe(markings, a, b, aa.get(j), bb.get(j), .12, false, defaultDividers, .35);
        }
        continue;
      }
      if (style == Style.TWO_LANE || style == Style.FOUR_LANE) {
        stripe(markings, a, b, -.14, -.14, .1, true, dividers);
        stripe(markings, a, b, .14, .14, .1, true, dividers);
      }
      if (painted(mesh,(a.distance()+b.distance())/2)
          && junctionZones.stream()
              .noneMatch(zone -> zone.contains((a.distance() + b.distance()) / 2))) {
        if (style == Style.FOUR_LANE) {
          stripe(markings, a, b, a.halfWidth() / 2, b.halfWidth() / 2, .12, false, dividers);
          stripe(markings, a, b, -a.halfWidth() / 2, -b.halfWidth() / 2, .12, false, dividers);
        } else if (style == Style.ONE_WAY
            || (style == Style.RAMP_TWO
                && a.halfWidth() + b.halfWidth() >= mesh.settings().width() * .85))
          stripe(markings, a, b, 0, 0, .12, false, dividers);
      }
    }
    pavement(mesh,deck,owners,joined,from,to,sink,pavement);

    if(mesh.settings().options().lanePoints().link()!=null&&!higherPriority.isEmpty()){
      // Station clipping stops the stripe's centreline. Its finite-width corners
      // can still overlap the host at a shallow merge; clip the painted polygon too.
      var clipped=new ArrayList<Face>();for(var face:markings)for(var poly:visible(face.points(),owners,.025))clipped.add(new Face(poly,face.color(),face.emissive(),face.texture()));markings=clipped;
    }
    for(var face:globalPaint)if(inWindow(mesh,face,from,to))markings.add(face);
    if(holes!=null) {
      var clipped=new ArrayList<Face>();
      for(var face:markings)for(var poly:visible(face.points(),holes,.03))clipped.add(new Face(poly,face.color(),face.emissive(),face.texture()));
      markings=clipped;
    }
    for(var face:bypassPaint)if(inWindow(mesh,face,from,to))markings.add(face);
    if(!mesh.settings().options().levelCrossings().isEmpty()){
      markings=clipCrossingPaint(markings,railwayStops);
      for(var face:stopBars)if(inWindow(mesh,face,from,to))markings.add(face);
    }
    return new Geometry(List.copyOf(pavement), List.copyOf(markings));
  }
  }

  private static List<Face> clipCrossingPaint(List<Face> paint,PaintMask mask){
    var clipped=new ArrayList<Face>();
    for(var face:paint)for(var poly:mask.clipXZ(face.points()))
      clipped.add(new Face(poly,face.color(),face.emissive(),face.texture()));
    return clipped;
  }

  /** Junction and toll markings are authored by their planners. Build the same
   * deck and clip that paint directly, without generating discarded default stripes. */
  public static Geometry buildCustom(Mesh mesh,List<Mesh> higher,List<Mesh> joined,List<Face> paint){
    var pavement=new ArrayList<Face>();
    pavement(mesh,RoadRenderMesh.pavement(mesh).samples(),new Grid(higher),new Grid(joined),
        Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,null,pavement);
    var base=new Geometry(List.copyOf(pavement),List.of());
    return paint.isEmpty()&&mesh.settings().options().levelCrossings().isEmpty()?base:custom(base,mesh,paint);
  }
  private static void pavement(Mesh mesh,List<Sample> deck,Grid owners,Grid joined,double from,double to,SlabSink sink,List<Face> pavement){
    double thickness=mesh.settings().thickness();
    // Deck and stripes use independent sampling. Long flat deck spans need no extra triangles.
    for (int i = 1; i < deck.size(); i++) {
      Sample a = deck.get(i - 1), b = deck.get(i);
      if(!inWindow((a.distance()+b.distance())/2,from,to))continue;
      for(var strip:LaneDeck.strips(mesh,a,b)) {
        V l=strip.al(),r=strip.ar(),ll=strip.bl(),rr=strip.br();
        slab(strip,owners,thickness,(poly,depth,color)->{
          pavement.add(new Face(poly,color));
          pavement.add(new Face(poly.stream().map(v->v.add(new V(0,-depth,0))).toList(),0xB9B9AD,false,Texture.CONCRETE));
          if(sink!=null)sink.accept(poly,depth,color);
        });
        if(strip.highWall())wall(pavement,ll,l,thickness,joined);
        if(strip.lowWall())wall(pavement,r,rr,thickness,joined);
      }
    }
    if(!mesh.closed())for(boolean start:new boolean[]{true,false}){
      Sample a=start?mesh.first():mesh.last();if(!inWindow(a.distance(),from,to))continue;
      for(var span:LaneDeck.spans(mesh,a))if(span.high()-span.low()>1e-7)
        wall(pavement,a.at(start?span.high():span.low(),0),a.at(start?span.low():span.high(),0),thickness,joined);
    }

    for(var cap:LaneDeck.caps(mesh))if(inWindow(RoadQueries.horizontal(mesh,cap.a().add(cap.b()).mul(.5)).sample().distance(),from,to))wall(pavement,cap.a(),cap.b(),thickness,joined);

  }

  public static boolean inWindow(double distance,double from,double to){return distance>=from&&distance<to;}
  private static boolean inWindow(Mesh mesh,Face face,double from,double to){
    if(!Double.isFinite(from)&&!Double.isFinite(to))return true;
    V center=new V(0,0,0);for(var point:face.points())center=center.add(point);center=center.mul(1d/face.points().size());
    return inWindow(RoadQueries.horizontal(mesh,center).sample().distance(),from,to);
  }

  /** Clip generated junction paint to the paved union, with one owner for every overlap. */
  private static void paintOnRoad(List<Face> output, RoadJunction.Paint paint, Grid paving) {
    List<Face> pieces = new ArrayList<>();
    Cut plane = null;
    List<List<V>> remaining = new ArrayList<>();
    remaining.add(ccw(paint.points()));
    for (Cut cut : paving.candidates(paint.points())) {
      if (paint.points().stream().anyMatch(p -> Math.abs(p.y() - cut.height(p)) > .35)) continue;
      if (plane == null) plane = cut;
      List<List<V>> next = new ArrayList<>();
      for (var subject : remaining) {
        var intersection = subject;
        for (int i = 0; i < cut.points.size(); i++)
          intersection =
              halfPlane(
                  intersection,
                  cut.points.get(i),
                  cut.points.get((i + 1) % cut.points.size()),
                  true);
        if (area(intersection) > 1e-9)
          pieces.add(
              new Face(
                  intersection.stream().map(p -> new V(p.x(), cut.height(p), p.z())).toList(),
                  paint.color()));
        next.addAll(subtract(subject, cut.points));
      }
      remaining = next;
      if (remaining.isEmpty()) break;
    }
    if (remaining.isEmpty() && plane != null && !pieces.isEmpty()) {
      Cut flat = plane;
      boolean coplanar =
          pieces.stream()
              .flatMap(f -> f.points().stream())
              .allMatch(p -> Math.abs(p.y() - flat.height(p)) < 1e-7);
      if (coplanar) {
        output.add(
            new Face(
                ccw(paint.points()).stream().map(p -> new V(p.x(), flat.height(p), p.z())).toList(),
                paint.color()));
        return;
      }
    }
    output.addAll(pieces);
  }

  private static void stripe(
      List<Face> output,
      Sample a,
      Sample b,
      double oa,
      double ob,
      double width,
      boolean yellow,
      Grid joined) {stripe(output,a,b,oa,ob,width,yellow,joined,.06);}
  private static void stripe(List<Face> output,Sample a,Sample b,double oa,double ob,double width,boolean yellow,Grid joined,double tolerance) {
    List<V> quad =
        List.of(
            a.at(oa - width / 2, 0),
            a.at(oa + width / 2, 0),
            b.at(ob + width / 2, 0),
            b.at(ob - width / 2, 0));
    for (List<V> triangle :
        List.of(
            List.of(quad.get(0), quad.get(1), quad.get(2)),
            List.of(quad.get(0), quad.get(2), quad.get(3))))
      for (List<V> part : visible(triangle, joined, tolerance))
        output.add(new Face(part, yellow ? 0xFAC136 : 0xEDEEE2));
  }

  private static void shoulderStripe(
      List<Face> out,
      Sample a,
      Sample b,
      int side,
      double oa,
      double ob,
      Grid joined,
      List<RoadJunction.EdgeZone> zones) {
    TreeSet<Double> cuts = new TreeSet<>(List.of(0.0, 1.0));
    double span = b.distance() - a.distance();
    for (var zone : zones) {
      if (zone.side() != side) continue;
      for (double d : new double[] {zone.from(), zone.to()})
        if (d > a.distance() && d < b.distance()) cuts.add((d - a.distance()) / span);
    }
    double previous = 0;
    for (double t : cuts) {
      double mid = a.distance() + (previous + t) * span / 2;
      if (t > previous && zones.stream().noneMatch(z -> z.contains(mid, side))) {
        Sample p = blend(a, b, previous), q = blend(a, b, t);
        stripe(out, p, q, oa + (ob - oa) * previous, oa + (ob - oa) * t, .15, false, joined);
      }
      previous = t;
    }
  }

  private static Sample blend(Sample a, Sample b, double t) {
    return new Sample(
        a.center().add(b.center().sub(a.center()).mul(t)),
        a.left().add(b.left().sub(a.left()).mul(t)).horizontalUnit(),
        a.distance() + (b.distance() - a.distance()) * t,
        a.halfWidth() + (b.halfWidth() - a.halfWidth()) * t);
  }

  private static List<List<V>> visible(List<V> polygon, Grid grid, double tolerance) {
    List<List<V>> pieces = new ArrayList<>();
    pieces.add(ccw(polygon));
    var candidates=grid.candidates(polygon);
    long retained=candidates.size()*128L;
    for (Cut cut : candidates) {
      RoadPlanningBudget.check();
      if (polygon.stream().anyMatch(p -> Math.abs(p.y() - cut.height(p)) > tolerance)) continue;
      List<List<V>> next = new ArrayList<>();
      if(grid.limit==Long.MAX_VALUE)for(var p:pieces)next.addAll(subtract(p,cut.points));
      else{
        long oldBytes=pieces.stream().mapToLong(p->128L+p.size()*64L).sum(),nextBytes=0;
        for(var p:pieces)for(var part:subtract(p,cut.points)){nextBytes+=128L+part.size()*64L;grid.temporary(retained+oldBytes+nextBytes);next.add(part);}
      }
      pieces = next;
      if (pieces.isEmpty()) break;
    }
    pieces.removeIf(p->!usable(p));
    return pieces;
  }

  private static boolean usable(List<V> p){
    double edge=0;for(int i=0;i<p.size();i++)edge=Math.max(edge,p.get(i).sub(p.get((i+1)%p.size())).horizontalLength());
    return area(p)*2>edge*1e-7;
  }

  /** Disjoint convex pieces of subject minus clip; coordinates are relative in every predicate. */
  public static List<List<V>> subtract(List<V> subject, List<V> clip) {
    List<List<V>> result = new ArrayList<>();
    List<V> remaining = ccw(subject), boundary = ccw(clip);
    for (int i = 0; i < boundary.size() && !remaining.isEmpty(); i++) {
      V a = boundary.get(i), b = boundary.get((i + 1) % boundary.size());
      if(a.sub(b).horizontalLength()<1e-9)continue; // Triangle prisms may repeat the tip vertex.
      List<V> outside = halfPlane(remaining, a, b, false);
      if (area(outside) > 1e-10) result.add(outside);
      remaining = halfPlane(remaining, a, b, true);
    }
    return result;
  }

  /** Convex XZ intersection, retaining the subject's interpolated height. */
  public static List<V> intersect(List<V> subject,List<V> clip){return intersect(subject,clip,1e-10);}
  /** Physical boolean operations must also remove microscopic fragments that
   * remain solid to collision; a visual-area cutoff can leave them untouched. */
  static List<V> intersectVolume(List<V> subject,List<V> clip){return intersect(subject,clip,0);}
  private static List<V> intersect(List<V> subject,List<V> clip,double minimumArea){
    var remaining=ccw(subject);var boundary=ccw(clip);
    for(int i=0;i<boundary.size()&&!remaining.isEmpty();i++){
      var a=boundary.get(i);var b=boundary.get((i+1)%boundary.size());
      if(a.sub(b).horizontalLength()>1e-9)remaining=halfPlane(remaining,a,b,true,minimumArea==0?0:1e-9);
    }
    return area(remaining)>minimumArea?remaining:List.of();
  }

  private static List<V> halfPlane(List<V> input, V a, V b, boolean inside) {return halfPlane(input,a,b,inside,1e-9);}
  private static List<V> halfPlane(List<V> input, V a, V b, boolean inside,double tolerance) {
    if (input.isEmpty()) return input;
    List<V> out = new ArrayList<>();
    V last = input.get(input.size() - 1);
    double prior = cross(a, b, last) * (inside ? 1 : -1);
    boolean was = prior >= 0;
    for (V p : input) {
      double value = cross(a, b, p) * (inside ? 1 : -1);
      boolean now = value >= 0;
      if (was != now) {
        double t = prior / (prior - value);
        out.add(last.add(p.sub(last).mul(t)));
      }
      if (now) out.add(p);
      last = p;
      prior = value;
      was = now;
    }
    List<V> clean = new ArrayList<>();
    for (V p : out)
      if (clean.isEmpty() || p.distance(clean.get(clean.size() - 1)) > tolerance) clean.add(p);
    if (clean.size() > 1 && clean.get(0).distance(clean.get(clean.size() - 1)) <= tolerance)
      clean.remove(clean.size() - 1);
    return clean;
  }

  private static void wall(List<Face> output, V a, V b, double thickness, Grid neighbors) {
    var intervals = exposed(a, b, neighbors);
    for (double[] range : intervals)
      if (range[1] > range[0] + 1e-9) {
        V p = a.add(b.sub(a).mul(range[0])), q = a.add(b.sub(a).mul(range[1]));
        output.add(
            new Face(
                List.of(p, q, q.add(new V(0, -thickness, 0)), p.add(new V(0, -thickness, 0))),
                0xB9B9AD,false,Texture.CONCRETE));
      }
  }

  private static List<double[]> exposed(V a, V b, Grid neighbors) {
    List<double[]> intervals = new ArrayList<>();
    intervals.add(new double[] {0, 1});
    for (Cut cut : neighbors.candidates(List.of(a, b))) {
      if (Math.abs(a.y() - cut.height(a)) > .025 || Math.abs(b.y() - cut.height(b)) > .025)
        continue;
      double lo = 0, hi = 1;
      boolean empty = false;
      for (int i = 0; i < cut.points.size(); i++) {
        V p = cut.points.get(i), q = cut.points.get((i + 1) % cut.points.size());
        double va = cross(p, q, a), vb = cross(p, q, b);
        // Adjacent triangle samples reconstruct their shared edge independently.
        // Roundoff away from the origin must not turn a coincident edge into an
        // exposed internal wall (visible as radial seams under depth bias).
        double epsilon = 1e-7 * Math.max(1, q.sub(p).horizontalLength());
        if (Math.abs(va) < epsilon) va = 0;
        if (Math.abs(vb) < epsilon) vb = 0;
        double d = vb - va;
        if (Math.abs(d) < 1e-12) {
          if (va < -1e-9) {
            empty = true;
            break;
          }
        } else if (d > 0) lo = Math.max(lo, -va / d);
        else hi = Math.min(hi, -va / d);
      }
      if (empty || hi <= lo + 1e-9) continue;
      List<double[]> next = new ArrayList<>();
      for (double[] range : intervals) {
        if (lo > range[0] + 1e-9) next.add(new double[] {range[0], Math.min(lo, range[1])});
        if (hi < range[1] - 1e-9) next.add(new double[] {Math.max(hi, range[0]), range[1]});
      }
      intervals = next;
    }
    return intervals;
  }

  /** A divider bordering a physically closed slot is no longer a divider between
   * two through lanes. Keep that short boundary continuous; do not turn the whole
   * host road solid and do not override an explicit line-editor selection. */
  public static boolean closedSlotBoundary(Mesh mesh,double station,double lateral){
    var sample=RoadStructures.sample(mesh,station);var raw=LaneSections.reference(mesh);
    // During ADD's widening, the old edge is still an outer boundary. The
    // incoming lane only becomes a through lane at its actual joining station.
    for(var added:LaneAdditions.ordered(raw))if(!Double.isFinite(added.end())&&added.sign()*(station-added.station())<0&&added.fraction(station)>1e-7){
      var lane=LaneAdditions.lane(raw,station,added.slot());
      var layout=RoadProfile.layout(raw,RoadStructures.sample(raw,station));
      int side=LaneAdditions.side(layout,added.sign());
      double edge=lane.position().sub(sample.center()).dot(sample.left())-side*lane.width()*added.fraction(station)/2;
      if(Math.abs(lateral-edge)<.2)return true;
    }
    if(!LaneDeck.hasOpenings(mesh))return false;
    for(int slot:LaneAdditions.slots(raw,station))if(mesh.settings().options().lanePoints().cuts().stream().anyMatch(c->c.temporary()&&c.lane()==slot&&c.removed(station)>.999)||LaneClosureWarnings.covers(mesh,station,slot)){
      var lane=LanePoints.lane(raw,station,slot);double center=lane.position().sub(sample.center()).dot(sample.left());
      if(Math.abs(Math.abs(lateral-center)-lane.width()/2)<.2)return true;
    }
    return LaneDeck.present(mesh,sample,lateral-.14,0)!=LaneDeck.present(mesh,sample,lateral+.14,0);
  }
  /** At lane-point mouths the edge paint meets the selected lane boundary, not the independent road shoulder rim. */
  public static double edgeOffset(Mesh mesh,Sample sample,int side){
    double normal=side*Math.max(.08,sample.halfWidth()-.3);
    var layout=RoadProfile.layout(mesh,sample);
    if(layout.cycleWidth()>.01&&(layout.catalog().twoWay()||layout.outside()==side))normal=side*Math.max(.08,sample.halfWidth()-Math.max(.3,RoadProfile.curbExtent(layout,sample,side)+.16));
    var link=mesh.settings().options().lanePoints().link();
    if(link==null){
      // Ordinary roads without a separate verge mark their actual driving edge,
      // including asymmetric lane cuts. The old fixed .3 inset widened only the
      // outside painted lane by absorbing the remaining pavement margin.
      if(layout.catalog().type()==RoadProfile.Type.ORDINARY&&layout.cycleWidth()<.01&&layout.curbWidth()<.01)return layout.outer(side);
      return normal;
    }
    // A connector has a persisted four-block motor band plus separate rail shoulders.
    // Mark that band, so the outer lane cannot visually absorb both shoulders.
    if(mesh.settings().style().connectorRamp())return layout.outer(side);
    if(layout.catalog().type()!=RoadProfile.Type.ORDINARY||layout.cycleWidth()>.01||layout.curbWidth()>.01)return normal;
    double span=Math.min(mesh.length()/2,Math.max(16,link.options().transition()));
    double distance=Math.min(sample.distance(),mesh.length()-sample.distance());
    double weight=1-Settings.smooth(Math.min(1,distance/Math.max(.01,span)));
    return normal+(layout.outer(side)-normal)*weight;
  }
  private static double edgeAForCycle(Mesh mesh,Sample sample,int side,RoadsideBypass bypass){
    return edgeOffset(mesh,sample,side)+side*(bypass.offset(sample.distance(),side)-.075);
  }
  private static void boundaryStripe(
      List<Face> out,
      Sample a,
      Sample b,
      int side,
      RoadRailJoin neighbors,RoadRailJoin paintNeighbors,
      List<RoadJunction.EdgeZone> zones,double oa,double ob) {
    V a0 = a.at(oa - .06, 0),
        a1 = a.at(oa + .06, 0),
        b0 = b.at(ob - .06, 0),
        b1 = b.at(ob + .06, 0);
    // Every longitudinal edge stripe stops at the physical separator nose,
    // including a highway's inset motor/shoulder line. Clip in station space:
    // clipping the painted polygon laterally left half-width dashed remnants.
    V first=a.at(side*(a.halfWidth()-RoadRailJoin.INSET),0),last=b.at(side*(b.halfWidth()-RoadRailJoin.INSET),0),delta=last.sub(first);
    double inset=RoadRailJoin.INSET;
    List<double[]> ranges=new ArrayList<>();double length=delta.dot(delta);
    if(length<1e-12)return;
    V outside=first.add(last).mul(.5).add(a.left().add(b.left()).horizontalUnit().mul(side*.4));
    for(var span:neighbors.exposed(first,last,outside,inset))ranges.add(new double[]{span.a().sub(first).dot(delta)/length,span.b().sub(first).dot(delta)/length});
    V pa=a.at(oa,0),pb=b.at(ob,0),pd=pb.sub(pa);double pl=pd.dot(pd);
    if(pl<1e-12)return;
    var painted=paintNeighbors.exposed(pa,pb,pa.add(pb).mul(.5).add(a.left().add(b.left()).horizontalUnit().mul(side*.4)),(a.halfWidth()-Math.abs(oa)+b.halfWidth()-Math.abs(ob))/2);
    var common=new ArrayList<double[]>();
    for(var r:ranges)for(var span:painted){double lo=Math.max(r[0],span.a().sub(pa).dot(pd)/pl),hi=Math.min(r[1],span.b().sub(pa).dot(pd)/pl);if(hi>lo+1e-8)common.add(new double[]{lo,hi});}ranges=common;
    for (var zone : zones) {
      if (zone.side() != side) continue;
      double lo = Math.max(0, (zone.from() - a.distance()) / (b.distance() - a.distance())),
          hi = Math.min(1, (zone.to() - a.distance()) / (b.distance() - a.distance()));
      if (hi <= lo) continue;
      List<double[]> next = new ArrayList<>();
      for (double[] r : ranges) {
        if (lo > r[0] + 1e-9) next.add(new double[] {r[0], Math.min(lo, r[1])});
        if (hi < r[1] - 1e-9) next.add(new double[] {Math.max(hi, r[0]), r[1]});
      }
      ranges = next;
    }
    for (var r : ranges) {
      if (r[1] <= r[0] + 1e-9) continue;
      var polygon=List.of(a0.add(b0.sub(a0).mul(r[0])),a1.add(b1.sub(a1).mul(r[0])),
          a1.add(b1.sub(a1).mul(r[1])),a0.add(b0.sub(a0).mul(r[1])));
      out.add(new Face(polygon,0xEDEEE2));
    }
  }

  private static double cross(V a, V b, V p) {
    return (b.x() - a.x()) * (p.z() - a.z()) - (b.z() - a.z()) * (p.x() - a.x());
  }

  private static double signedArea(List<V> p) {
    if (p.size() < 3) return 0;
    double a = 0;
    for (int i = 1; i < p.size() - 1; i++) a += cross(p.get(0), p.get(i), p.get(i + 1));
    return a / 2;
  }

  public static double area(List<V> p) {
    return Math.abs(signedArea(p));
  }

  private static List<V> ccw(List<V> p) {
    if (signedArea(p) >= 0) return p;
    List<V> r = new ArrayList<>(p);
    Collections.reverse(r);
    return r;
  }

  private RoadSurface() {}
}
