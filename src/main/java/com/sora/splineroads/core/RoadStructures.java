package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Persisted, server-planned structure: no terrain scans or pier searches during rendering. */
public final class RoadStructures {
  public static final double PIER_SPACING = 24, MAX_DROP = 96;
  /** Ground furniture starts only when the pavement itself meets original land. */
  public static final double GROUND_CONTACT=.125;

  public enum Material {
    DEFAULT,
    STEEL,
    DARK_STEEL,
    CONCRETE,
    SOIL,
    GREEN,
    LAMP,
    SIGNAL_MAIN,
    SIGNAL_RAMP,
    CB_POST,
    CB_ARM,
    CB_BASE,
    SIGNAL_VEHICLE,
    SIGNAL_PEDESTRIAN,
    SIGNAL_LEFT, TUNNEL, SIGN_GREEN, SIGN_BLUE, SIGN_WHITE, ARCH_STEEL, GANTRY_FRAME, WALK_STONE_BRICKS, WALK_BRICKS, WALK_ANDESITE, WALK_DIORITE, WALK_GRANITE, WALK_SMOOTH_STONE, TACTILE, NOISE_BARRIER, OAK_LOG, BIRCH_LOG, SPRUCE_LOG, CHERRY_LOG, OAK_LEAVES, BIRCH_LEAVES, SPRUCE_LEAVES, CHERRY_LEAVES, FLOWERS, LAMP_BLUE, FLOWER_PINK, FLOWER_YELLOW, FLOWER_WHITE
  }

  /** Per-corner vertical thickness for a lining cut between two different planes. */
  public record Heights(double a,double b,double c,double d) {
    public Heights {if(!RoadGeometry.finite(a,b,c,d)||Math.min(Math.min(a,b),Math.min(c,d))<0)throw new IllegalArgumentException("Invalid corner heights");}
    public double get(int i){return switch(i){case 0->a;case 1->b;case 2->c;default->d;};}
  }
  public record Part(
      V a, V b, double width, double height, boolean pier, Material material, V frameA, V frameB,String model,Heights heights) {
    public Part(V a,V b,double width,double height,boolean pier,Material material,V frameA,V frameB,String model){this(a,b,width,height,pier,material,frameA,frameB,model,null);}
    public Part(V a,V b,double width,double height,boolean pier,Material material,V frameA,V frameB){this(a,b,width,height,pier,material,frameA,frameB,"");}
    public Part(V a, V b, double width, double height, boolean pier, Material material) {
      this(a, b, width, height, pier, material, null, null);
    }

    public Part frames(V first, V last) {
      return new Part(a, b, width, height, pier, material, first, last,model,heights);
    }

    public double verticalFrame() {return Math.max(frameA==null?0:Math.abs(frameA.y()),frameB==null?0:Math.abs(frameB.y()));}

    public double halfExtent() {
      return Math.max(
          width / 2,
          Math.max(
              frameA == null ? 0 : frameA.horizontalLength(),
              frameB == null ? 0 : frameB.horizontalLength()));
    }

    public Part(V a, V b, double width, double height, boolean pier) {
      this(a, b, width, height, pier, Material.DEFAULT);
    }

    public boolean luminous() {
      return material == Material.LAMP;
    }

    public Part {
      if(model==null||model.length()>128)throw new IllegalArgumentException("无效的设施模型");
      if(heights!=null)for(int i=0;i<4;i++)if(heights.get(i)>height+1e-7)throw new IllegalArgumentException("Corner height exceeds bounds");
      if (!RoadGeometry.finite(a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), width, height)
          || width <= 0
          || width > 8
          || height <= 0
          || height > MAX_DROP + 4) throw new IllegalArgumentException("无效的高架结构尺寸（"+material+"，宽="+width+"，高="+height+"）");
    }

    public List<V> base() {
      V side =
          pier || b.sub(a).horizontalLength() < 1e-8
              ? new V(width / 2, 0, 0)
              : b.sub(a).horizontalUnit().left().mul(width / 2);
      V aa = a, bb = b;
      if (pier) {
        aa = a.add(new V(0, 0, -width / 2));
        bb = b.add(new V(0, 0, width / 2));
      }
      V first = frameA == null ? side : frameA, last = frameB == null ? side : frameB;
      return List.of(aa.add(first), aa.sub(first), bb.sub(last), bb.add(last));
    }

    public List<V> top(){var base=base();var out=new ArrayList<V>(4);for(int i=0;i<4;i++)out.add(base.get(i).add(new V(0,heights==null?height:heights.get(i),0)));return List.copyOf(out);}

    public List<RoadSurface.Face> faces() {
      if(model.equals(RailwayCrossingSigns.MODEL))return RailwayCrossingSigns.faces(this);
      if(model.equals(RailbedFence.MODEL))return RailbedFence.faces(this);
      if(model.equals(RoadCrashCushion.MODEL))return RoadCrashCushion.faces(this);
      if(TollModels.supports(this))return TollModels.faces(this);
      if(material==Material.NOISE_BARRIER)return RoadNoiseModel.faces(this,false);
      if (RoadSignals.signal(this)) return RoadSignalModel.faces(this, false, false);
      if (RoadPoleModel.support(this)) return RoadPoleModel.faces(this);
      var base = base();
      var top = top();
      List<RoadSurface.Face> out = new ArrayList<>();
      int color =
          switch (material) {
            case DEFAULT -> pier ? 0xA5ABAC : 0xD2D8D9;
            case STEEL, GANTRY_FRAME -> 0xC3CDCF;
            case DARK_STEEL -> 0x697C82;
            case TUNNEL -> 0xC7CCC7;
            case SIGN_GREEN -> 0x14734A;
            case SIGN_BLUE, LAMP_BLUE -> 0x235994;
            case FLOWER_PINK -> 0xE783B2;case FLOWER_YELLOW -> 0xF0C73D;case FLOWER_WHITE -> 0xFFF8EB;
            case SIGN_WHITE -> 0xF4F3DC;
            case ARCH_STEEL -> 0xAC4E38;
            case CONCRETE -> 0xB9B9AD;
            case WALK_STONE_BRICKS,WALK_BRICKS,WALK_ANDESITE,WALK_DIORITE,WALK_GRANITE,WALK_SMOOTH_STONE -> 0xFFFFFF;
            case TACTILE -> 0xE9BE42;
            case NOISE_BARRIER -> 0xFFFFFF;
            case SOIL -> 0x66543E;
            case OAK_LOG,BIRCH_LOG,SPRUCE_LOG,CHERRY_LOG,FLOWERS -> 0xFFFFFF;
            case OAK_LEAVES -> 0x6A9D46; case BIRCH_LEAVES -> 0x80A755; case SPRUCE_LEAVES -> 0x4C7455; case CHERRY_LEAVES -> 0xFFFFFF;
            case GREEN -> 0x73AA51;
            case LAMP -> 0xFFF1C7;
            case SIGNAL_MAIN, SIGNAL_RAMP, SIGNAL_VEHICLE, SIGNAL_PEDESTRIAN, SIGNAL_LEFT -> 0x181D20;
            case CB_POST, CB_ARM, CB_BASE -> 0xE7E7E7;
          };
      RoadSurface.Texture texture =
          switch (material) {
            case DEFAULT, CONCRETE, TUNNEL -> RoadSurface.Texture.CONCRETE;
            case STEEL, DARK_STEEL, ARCH_STEEL, GANTRY_FRAME -> RoadSurface.Texture.METAL;
            case CB_POST, CB_ARM, CB_BASE -> RoadSurface.Texture.METAL;
            case SOIL -> RoadSurface.Texture.SOIL;
            case OAK_LOG,BIRCH_LOG,SPRUCE_LOG,CHERRY_LOG,OAK_LEAVES,BIRCH_LEAVES,SPRUCE_LEAVES,CHERRY_LEAVES,FLOWERS -> RoadSurface.Texture.valueOf(material.name());
            case GREEN -> RoadSurface.Texture.LEAVES;
            case WALK_STONE_BRICKS,WALK_BRICKS,WALK_ANDESITE,WALK_DIORITE,WALK_GRANITE,WALK_SMOOTH_STONE -> RoadSurface.Texture.valueOf(material.name());
            case TACTILE -> RoadSurface.Texture.PLAIN;
            case NOISE_BARRIER -> RoadSurface.Texture.NOISE_BARRIER;
            case FLOWER_PINK,FLOWER_YELLOW,FLOWER_WHITE,SIGN_GREEN, SIGN_BLUE, LAMP_BLUE, SIGN_WHITE, LAMP, SIGNAL_MAIN, SIGNAL_RAMP, SIGNAL_VEHICLE, SIGNAL_PEDESTRIAN, SIGNAL_LEFT -> RoadSurface.Texture.PLAIN;
          };
      V center = new V(0,0,0);for(int i=0;i<4;i++)center=center.add(base.get(i)).add(top.get(i));center=center.mul(.125);
      out.add(face(top, color, luminous(), texture, center));
      // No lower leaf face at the soil interface: both used to occupy exactly the same plane.
      if (material != Material.GREEN) out.add(face(base, color, luminous(), texture, center));
      for (int i = 0; i < 4; i++) {
        int j = (i + 1) % 4;
        out.add(
            face(
                List.of(base.get(i), base.get(j), top.get(j), top.get(i)),
                color,
                luminous(),
                texture,
                center));
      }
      out.removeIf(f->surfaceArea(f.points())<1e-10);
      return out;
    }

    private static double surfaceArea(List<V> points){
      double sum=0;for(int i=1;i+1<points.size();i++){V a=points.get(i).sub(points.get(0)),b=points.get(i+1).sub(points.get(0));
        V n=new V(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x());sum+=Math.sqrt(n.dot(n))/2;}return sum;
    }
    private static RoadSurface.Face face(
        List<V> points, int color, boolean light, RoadSurface.Texture texture, V center) {
      // A clipped prism stores its triangle as [a,b,c,c]. Reversing that quad
      // starts with two identical vertices and renderers discard its underside.
      // Emit a real triangle, while keeping the persisted prism representation.
      var unique=new ArrayList<V>();
      for(V p:points)if(unique.isEmpty()||p.distance(unique.get(unique.size()-1))>1e-9)unique.add(p);
      if(unique.size()>1&&unique.get(0).distance(unique.get(unique.size()-1))<1e-9)unique.remove(unique.size()-1);
      if(unique.size()>=3)points=unique;
      V a = points.get(1).sub(points.get(0)), b = points.get(2).sub(points.get(0));
      V n =
          new V(
              a.y() * b.z() - a.z() * b.y(),
              a.z() * b.x() - a.x() * b.z(),
              a.x() * b.y() - a.y() * b.x());
      if (n.dot(points.get(0).sub(center)) < 0) {
        points = new ArrayList<>(points);
        Collections.reverse(points);
      }
      return new RoadSurface.Face(points, color, light, texture);
    }
  }

  public interface Ground {
    /** Split paths own their existing foundations, including an intentionally empty list. */
    default List<Part> fixedSupports(){return null;}
    default boolean sharedDeck(){return false;}
    default List<Part> sharedSupports(Mesh mesh,Sample at,double depth,double width,double offset){return null;}
    /** Only the transaction may prove that an unchanged automatic split anchor
     * had no saved members. Every other authored station remains mandatory. */
    default boolean requiredGantry(RoadGantry.Station station){return true;}
    /** Only a transaction can prove an unchanged remote dependency is outside
     * its edit. Null keeps the ordinary placement and collision checks. */
    default List<Part> preservedGantry(RoadGantry.Station station){return null;}
    default void missingSupport(double station,String reason){}
    /** Underground connector reservations remain openings, without a planter lid. */
    default boolean closedLanePlanting(UUID connection){return true;}
    /** Terrain classification cannot feed back through its own derived median/closure. */
    default Mesh terrainReference(Mesh mesh){return mesh;}
    /** Highest solid terrain below the deck; NaN means no valid foundation in range. */
    double top(double x, double z, double deckY);

    /** Physical bearing for the complete pier/plinth footprint. Terrain
     * classification still uses top(), independently of a lower road's cut. */
    default double foundation(V center,double top,double width){
      double floor=Double.POSITIVE_INFINITY;
      for(double dx:new double[]{-.5,0,.5})for(double dz:new double[]{-.5,0,.5}){
        double y=top(center.x()+dx*width,center.z()+dz*width,top);
        if(!Double.isFinite(y))return Double.NaN;floor=Math.min(floor,y);
      }
      return floor;
    }

    /** Original land surface above a buried road; separate from its bearing below. */
    default double surface(double x,double z,double roadY){
      // Follow the original solid column FROM the road plane. Looking down from
      // MAX_DROP above the road mistakes an unrelated bridge/roof for overburden.
      // Stop at the first air gap; the same query works with restored excavation
      // on the server and retained terrain in the client preview.
      double probe=roadY+.0001;
      for(int i=0;i<=MAX_DROP;i++,probe+=1){
        RoadPlanningBudget.check();
        double surface=top(x,z,probe);
        if(!Double.isFinite(surface)||Math.abs(surface-probe)>1e-7)return surface;
      }
      return roadY+MAX_DROP;
    }

    /** True when a proposed volume would occupy another road's driving corridor. */
    boolean blocked(Part part);

    /** A generated lamp is mounted on its own verge/median, not a bridge shaft.
     * The server may allow that local mount while retaining every other obstacle. */
    default boolean plantBlocked(Part part,Sample mount){return blocked(part);}
    default boolean lampBlocked(Part part, Sample mount) { return blocked(part); }

    /** True only when a blocked gantry foot has a roadside-boundary contact that
     * can be resolved by moving the foot. Every candidate is checked again with
     * blocked(); this does not permit overlap with any final deck or fixture. */
    default boolean gantryMountConflict(Part part,RoadGantry.Station station){return false;}

    default String blockedReason(Part part){return String.format(Locale.ROOT,"%s构件位于 X=%.1f Y=%.1f Z=%.1f",part.pier()?"桥墩":"桥梁",part.a().x(),part.a().y(),part.a().z());}

    /** An overlapping deck at the same height: omit its internal guardrail. */
    boolean joined(V point);

    /** Fine boundary clipping is separate from broad furniture openings. Legacy callers
     * retain their old policy; the lane-connector planner supplies exact material clips. */
    default V railJoint(V position,V direction){return null;}
    default boolean railPost(V position){return true;}
    default V railJoint(V position,V direction,boolean highway,boolean raised){return railJoint(position,direction);}
    default boolean railPost(V position,boolean highway,boolean raised){return railPost(position);}
    default List<RoadRailJoin.Span> railSpans(V a,V b,V outside) {
      return joined(outside)?List.of():List.of(new RoadRailJoin.Span(a,b));
    }

    default List<RoadRailJoin.Span> railSpans(V a,V b,V outside,double inset){return railSpans(a,b,outside);}

    default List<RoadRailJoin.Span> capRailSpans(V a,V b,V outside){return railSpans(a,b,outside);}
    default boolean railBlocked(Part part,V a,V b){return blocked(part);}
    default boolean unionRails(){return false;}

    /** Leave the node's editing target clear of raised median furniture. */
    default boolean marker(V point) {
      return false;
    }

    default boolean furnitureClear(V point) { return false; }
    /** A conductor may end at a shared bed boundary only with a live open-air continuation. */
    default boolean wireContinues(boolean first) { return false; }
    default boolean lampClear(V point) { return false; }
  }

  public static List<Part> plan(Mesh mesh, Ground ground) {
    return plan(mesh, ground, RoadFurniture.Phase.DEFAULT);
  }

  /** Generated unmarked intersection pads share the ordinary approach parapet. */
  public static boolean railModern(Style style){return RoadProfile.modern(style)||style==Style.UNMARKED;}

  public static List<Part> plan(Mesh mesh, Ground ground, RoadFurniture.Phase phase) {
    if(mesh.settings().structure()==Structure.TUNNEL){
      var out=new ArrayList<>(RoadInfrastructure.plan(mesh,ground));

      return List.copyOf(out);
    }
    mesh=RoadStreetscape.resolve(mesh,ground);
    boolean modern = railModern(mesh.settings().style());
    boolean highway =
        RoadProfile.highway(mesh.settings().style());
    if (!modern && mesh.settings().style()!=Style.RAILBED && mesh.settings().structure() == Structure.GROUND) return RoadGantry.plan(mesh,ground);
    List<Part> out = new ArrayList<>();
    boundaryRails(out,mesh,ground,modern,highway);
    if (RoadProfile.modern(mesh.settings().style())) furniture(mesh, ground, out, phase);
    if (modern) terminalPosts(out,ground);
    out.addAll(RoadInfrastructure.plan(mesh,ground,out));
    out.addAll(RailbedBridges.plan(mesh,ground));
    out.addAll(RailwayCrossingSigns.plan(mesh,ground));
    out.addAll(LaneClosureLandscape.plan(mesh,ground));

    if (RoadInfrastructure.customBridge(mesh)&&!ground.sharedDeck()){out.addAll(RoadSidewalks.parts(mesh,mesh.settings().options().sidewalk(),ground,out).stream().filter(p->!ground.blocked(p)).toList());return List.copyOf(out);}
    out.addAll(edgeSlabs(mesh,ground));
    out.addAll(groundSkirts(mesh,ground));
    out.addAll(supports(mesh,ground,phase,out));
    out.addAll(RoadSidewalks.parts(mesh,mesh.settings().options().sidewalk(),ground,out).stream().filter(p->!ground.blocked(p)).toList());
    return List.copyOf(out);
  }

  /** Existing boundary fixtures only: signals may use these bearings without
   * running a second bridge-pier or gantry planner. */
  public static List<Part> roadsideSupports(Mesh mesh,Ground ground){
    if(mesh.settings().structure()==Structure.TUNNEL)return List.of();
    mesh=RoadStreetscape.resolve(mesh,ground);
    var out=new ArrayList<Part>();
    boundaryRails(out,mesh,ground,railModern(mesh.settings().style()),RoadProfile.highway(mesh.settings().style()));
    if(railModern(mesh.settings().style()))terminalPosts(out,ground);
    if(!RoadInfrastructure.customBridge(mesh))out.addAll(edgeSlabs(mesh,ground));
    return List.copyOf(out);
  }

  private static void boundaryRails(List<Part> out,Mesh mesh,Ground ground,boolean modern,boolean highway){
    // Accumulate each continuous exposed edge before creating rail parts. Tiny AUTO islands
    // at coincident fan roots are overlap artifacts, not separate barrier installations.
    for(int side:new int[]{-1,1}) {
      if(mesh.settings().style()==Style.RAILBED&&!mesh.settings().options().railbed().fittings().rail(side))continue;
      var run=new ArrayList<RailSpan>();
      double trim=mesh.settings().style()==Style.UNMARKED?Math.min(RoadRailJoin.INSET,mesh.length()/4):0;
      var stations=new TreeSet<Double>();
      for(double d=trim;d<mesh.length()-trim-1e-6;d+=.5)stations.add(d);
      stations.add(mesh.length()-trim);
      // Whole-lane departures have a real transverse step. Do not replace it
      // with a half-metre diagonal sound panel across the connecting mouth.
      for(var cut:mesh.settings().options().lanePoints().cuts())if(cut.rectangular()&&!cut.temporary())
        for(double edge:new double[]{cut.begin(),cut.end()})for(double d:new double[]{edge-1e-5,edge,edge+1e-5})
          if(d>trim&&d<mesh.length()-trim)stations.add(d);
      var ordered=new ArrayList<>(stations);
      for(int i=1;i<ordered.size();i++) {
        double d=ordered.get(i-1);Sample a=sample(mesh,d),b=sample(mesh,ordered.get(i));
        double insetA=RoadRailJoin.inset(mesh,a,side),insetB=RoadRailJoin.inset(mesh,b,side);
        V aa=a.at(side*(a.halfWidth()-insetA),0),bb=b.at(side*(b.halfWidth()-insetB),0),mid=aa.add(bb).mul(.5);
        boolean raised=RoadStreetscape.raised(mesh,sample(mesh,(a.distance()+b.distance())/2),side);
        if(!raised)for(V point:List.of(aa,mid,bb))if(elevated(mesh,point,ground)){
          raised=true;break;
        }
        V sum=a.left().add(b.left());V normal=sum.horizontalLength()<1e-7?a.left():sum.horizontalUnit();
        V outside=mid.add(normal.mul(side*(modern?.40:.14)));
        boolean visible=bb.sub(aa).horizontalLength()>1e-7
            && !LaneDeck.outerOpening(mesh,(a.distance()+b.distance())/2,side)
            && mesh.settings().options().outerRail()!=RoadProfile.OuterRail.OFF
            && (mesh.settings().style()==Style.RAILBED || mesh.settings().options().outerRail()==RoadProfile.OuterRail.ON || highway
                ||modern&&mesh.settings().style().ramp() ||mesh.settings().structure()==Structure.BRIDGE
                ||mesh.settings().structure()!=Structure.GROUND&&raised);
        boolean transverse=b.distance()-a.distance()<3e-5&&bb.sub(aa).horizontalLength()>.01;
        if(visible&&transverse){
          emitOuterRailRun(out,run,mesh,ground,modern,highway,side);run.clear();
          for(var span:ground.capRailSpans(aa,bb,outside))emitCapRail(out,ground,span,highway,raised);
          continue;
        }
        var spans=visible?ground.railSpans(aa,bb,outside,(insetA+insetB)/2):List.<RoadRailJoin.Span>of();
        if(spans.isEmpty()){emitOuterRailRun(out,run,mesh,ground,modern,highway,side);run.clear();}
        for(var span:spans){
          if(!run.isEmpty()&&run.get(run.size()-1).b().distance(span.a())>1e-5){emitOuterRailRun(out,run,mesh,ground,modern,highway,side);run.clear();}
          double fraction=aa.sub(bb).horizontalLength()<1e-9?0:aa.sub(span.a()).horizontalLength()/aa.sub(bb).horizontalLength();
          double endFraction=aa.sub(bb).horizontalLength()<1e-9?1:aa.sub(span.b()).horizontalLength()/aa.sub(bb).horizontalLength();
          run.add(new RailSpan(span.a(),span.b(),raised,d+fraction*(b.distance()-a.distance()),d+endFraction*(b.distance()-a.distance())));
        }
        if(!spans.isEmpty()&&spans.get(spans.size()-1).b().distance(bb)>1e-5){emitOuterRailRun(out,run,mesh,ground,modern,highway,side);run.clear();}
      }
      emitOuterRailRun(out,run,mesh,ground,modern,highway,side);
    }
    if(mesh.settings().style()==Style.RAILBED)return;
    padEndRails(out,mesh,ground);
    closureRails(out,mesh,ground,modern,highway);
  }

  /** Continuous concrete fascia for ordinary elevated road slabs. */
  public static List<Part> edgeSlabs(Mesh mesh,Ground ground){
    var out=new ArrayList<Part>();double depth=mesh.settings().thickness();
    for(int i=1;i<mesh.samples().size();i++){
      var a=mesh.samples().get(i-1);var b=mesh.samples().get(i);var mid=sample(mesh,(a.distance()+b.distance())/2);
      for(int side:new int[]{-1,1}){
        if(!RoadStreetscape.raised(mesh,mid,side))continue;
        if(LaneDeck.outerOpening(mesh,mid.distance(),side))continue;
        double ra=.35*RoadInfrastructure.endTaper(mesh,a),rb=.35*RoadInfrastructure.endTaper(mesh,b);
        V first=a.at(side*(a.halfWidth()+(ra-.05)/2),depth),last=b.at(side*(b.halfWidth()+(rb-.05)/2),depth);
        var part=new Part(first,last,Math.max(ra,rb)+.05,depth,false,Material.CONCRETE).frames(a.left().mul((ra+.05)/2),b.left().mul((rb+.05)/2));
        for(var span:ground.railSpans(a.at(side*a.halfWidth(),0),b.at(side*b.halfWidth(),0),mid.at(side*(mid.halfWidth()+.2),0),0)){
          double length=a.center().sub(b.center()).horizontalLength();
          double ta=length<1e-7?0:span.a().sub(a.at(side*a.halfWidth(),0)).horizontalLength()/Math.max(1e-7,a.at(side*a.halfWidth(),0).sub(b.at(side*b.halfWidth(),0)).horizontalLength());
          double tb=length<1e-7?1:span.b().sub(a.at(side*a.halfWidth(),0)).horizontalLength()/Math.max(1e-7,a.at(side*a.halfWidth(),0).sub(b.at(side*b.halfWidth(),0)).horizontalLength());
          var clipped=new Part(first.add(last.sub(first).mul(ta)),first.add(last.sub(first).mul(tb)),part.width(),depth,false,Material.CONCRETE)
              .frames(part.frameA().add(part.frameB().sub(part.frameA()).mul(ta)),part.frameA().add(part.frameB().sub(part.frameA()).mul(tb)));
          if(!ground.blocked(clipped))out.add(clipped);
        }
      }
    }return List.copyOf(out);
  }

  /** Seal only shallow exposed gaps between the deck underside and its supporting
   * terrain. The AUTO elevated threshold is intentionally larger than these gaps;
   * previously it left a daylight slit at the foot of a gentle ramp. Never fill a
   * real bridge opening, tunnel, or another road's vehicle corridor. */
  public static List<Part> groundSkirts(Mesh mesh,Ground ground){
    if(mesh.settings().structure()==Structure.BRIDGE||mesh.settings().structure()==Structure.TUNNEL)
      return List.of();
    var out=new ArrayList<Part>();double depth=mesh.settings().thickness();
    for(double d=mesh.first().distance();d<mesh.last().distance()-1e-7;d+=.5){
      var a=sample(mesh,d);var b=sample(mesh,Math.min(mesh.last().distance(),d+.5));
      for(int side:new int[]{-1,1}){
        V aa=a.at(side*(a.halfWidth()-.035),depth),bb=b.at(side*(b.halfWidth()-.035),depth);
        double ga=ground.top(aa.x(),aa.z(),aa.y()+depth),gb=ground.top(bb.x(),bb.z(),bb.y()+depth);
        double gapA=aa.y()-ga,gapB=bb.y()-gb;
        if(!RoadGeometry.finite(ga,gb)||Math.max(gapA,gapB)<=.005||Math.min(gapA,gapB)<-.025
            ||Math.max(gapA,gapB)>.6)continue;
        double height=Math.max(gapA,gapB)+.02;
        var part=new Part(new V(aa.x(),ga-.01,aa.z()),new V(bb.x(),gb-.01,bb.z()),.10,height,false,Material.CONCRETE)
            .frames(a.left().mul(.05),b.left().mul(.05));
        if(!ground.blocked(part))out.add(part);
      }
    }
    return List.copyOf(out);
  }

  /** Shared by ordinary roads and elevated junction surfaces. */
  public static List<Part> supports(Mesh mesh,Ground ground,RoadFurniture.Phase phase){return supports(mesh,ground,phase,List.of());}
  private static List<Part> supports(Mesh mesh,Ground ground,RoadFurniture.Phase phase,Collection<Part> fixtures){
    if(ground.sharedDeck())return List.of();
    var gantries=new RoadSolidOverlap.Index(fixtures.stream().filter(RoadGantry::member).toList());
    java.util.function.Predicate<Part> blocked=p->ground.blocked(p)||gantries.intersects(p);
    var fixed=ground.fixedSupports();if(fixed!=null){for(var part:fixed)if(blocked.test(part))throw new IllegalArgumentException("原桥墩与新的通行区冲突，请先调整相关道路");return fixed;}
    var out=new ArrayList<Part>();
    double previous = -100;
    var missing=new ArrayList<Double>();var occupied=new ArrayList<Double>();
    for (double target = phase.first(12, PIER_SPACING); target < mesh.length() - 1e-6;
        target += PIER_SPACING) {
      if(RailbedBridges.customAt(mesh,target))continue;
      var shared=ground.sharedSupports(mesh,sample(mesh,target),.9,1.8,sample(mesh,target).halfWidth()*.62);
      if(shared!=null){out.addAll(shared);occupied.add(target);previous=target;continue;}
      boolean placed=false;
      // Local clearance detours never alter the phase of subsequent spans.
      for (double shift : new double[] {0, -4, 4, -8, 8}) {
        double d = target + shift;
        if (d < 0 || d >= mesh.length() || d - previous < 10) continue;
        Sample s = sample(mesh, d);
        boolean negative=RoadStreetscape.raised(mesh,s,-1),positive=RoadStreetscape.raised(mesh,s,1);
        boolean halfBridge=mesh.settings().structure()==Structure.AUTO&&negative!=positive&&!LaneDeck.hasOpenings(mesh);
        var bearing=halfBridge?new Sample(s.at((positive?1:-1)*s.halfWidth()/2,0),s.left(),s.distance(),s.halfWidth()/2):s;
        var support=mesh.settings().style().connectorRamp()?RoadSupports.ramp(mesh,d,ground):
            mesh.settings().style().ramp()?RoadSupports.ramp(bearing,mesh.settings().thickness(),ground):halfBridge?RoadSupports.clearStandard(bearing,mesh.settings().thickness(),ground):RoadSupports.clearStandard(mesh,d,ground);
        if(support.isEmpty()||support.stream().anyMatch(blocked))continue;
        out.addAll(support);
        previous = d;occupied.add(d);
        placed=true;
        break;
      }
      if(!placed&&mesh.settings().style().ramp())missing.add(target);
      // Keep every successful standard support exactly as it was. Only a failed
      // station on AUTO / ordinary viaduct gets a second, portal-style attempt.
      boolean fallback=RoadStreetscape.raised(mesh,sample(mesh,target),-1)==RoadStreetscape.raised(mesh,sample(mesh,target),1)&&!mesh.settings().style().ramp()&&mesh.settings().options().lanePoints().link()==null
          &&(mesh.settings().structure()==Structure.AUTO||mesh.settings().structure()==Structure.BRIDGE
          &&mesh.settings().options().infrastructure().bridge()==RoadInfrastructure.Bridge.STANDARD);
      if(!placed&&fallback&&target-previous>=10){
        Sample s=sample(mesh,target);double floor=ground.top(s.center().x(),s.center().z(),s.center().y()-mesh.settings().thickness());
        if(Double.isFinite(floor)&&s.center().y()-mesh.settings().thickness()-floor<2)continue;
        var bands=RoadSupports.bearing(mesh,target,1.5);
        if(LaneDeck.hasOpenings(mesh)&&(bands.size()!=1||bands.get(0).center().distance(s.center())>1e-6||Math.abs(bands.get(0).halfWidth()-s.halfWidth())>1e-6))continue;
        var portal=RoadSupports.portal(s,mesh.settings().thickness(),.9,s.halfWidth()+1.4,1.8,ground);
        if(!portal.isEmpty()&&portal.stream().noneMatch(blocked)){out.addAll(portal);previous=target;}
      }
    }
    // Fill only holes after the original pass. New candidates must not displace
    // any previously legal support or change the phase of later spans.
    for(double target:missing){
      boolean placed=false;
      for(double shift:new double[]{0,-2,2,-4,4,-6,6,-8,8,-10,10}){
        double d=target+shift;if(d<1.12||d>mesh.length()-1.12||occupied.stream().anyMatch(x->Math.abs(x-d)<10))continue;
        var at=sample(mesh,d);
        for(double reach=.5;reach<=Math.min(3,at.halfWidth()-1.12)+1e-7;reach+=.5){
          for(int side:new int[]{-1,1}){
            var support=RoadSupports.ramp(mesh,d,side*reach,ground);
            if(support.isEmpty()||support.stream().anyMatch(blocked))continue;
            out.addAll(support);occupied.add(d);placed=true;break;
          }
          if(placed)break;
        }
        if(placed)break;
      }
      if(!placed){var at=sample(mesh,target);double top=at.center().y()-mesh.settings().thickness(),floor=ground.top(at.center().x(),at.center().z(),top);
        if(!Double.isFinite(floor)||top-floor>MAX_DROP)ground.missingSupport(target,"附近候选缺少可达地基");
        else if(top-floor<2)ground.missingSupport(target,"路面贴近地面，无需桥墩");
        else if(RoadSupports.bearing(mesh,target,1.12).isEmpty())ground.missingSupport(target,"可承载桥面不足以容纳完整单柱");
        else ground.missingSupport(target,"附近单柱候选受机动车、慢行净空或既有支撑间距阻挡");
      }
    }
    return List.copyOf(out);
  }

  /** An interchange pad is a finite slab, not an open-ended through road.
   * Its transverse perimeter remains guarded between the actual ramp mouths. */
  private static void padEndRails(List<Part> out,Mesh mesh,Ground ground){
    if(mesh.settings().style()!=Style.UNMARKED||mesh.settings().structure()==Structure.GROUND
        ||mesh.settings().options().outerRail()==RoadProfile.OuterRail.OFF)return;
    double trim=Math.min(RoadRailJoin.INSET,mesh.length()/4);
    for(boolean first:new boolean[]{true,false}){
      var at=sample(mesh,first?trim:mesh.length()-trim);
      boolean raised=mesh.settings().structure()==Structure.BRIDGE||elevated(mesh,at,ground);
      if(!raised)continue;
      V a=at.at(-at.halfWidth()+RoadRailJoin.INSET,0),b=at.at(at.halfWidth()-RoadRailJoin.INSET,0);
      V outside=a.add(b).mul(.5).add(at.left().left().mul(first?.4:-.4));
      for(var span:ground.railSpans(a,b,outside,RoadRailJoin.INSET)){
        int n=Math.max(1,(int)Math.ceil(span.a().distance(span.b())/.5));
        var run=new ArrayList<RailSpan>();
        for(int i=0;i<n;i++)run.add(new RailSpan(span.a().mul(1-i/(double)n).add(span.b().mul(i/(double)n)),
            span.a().mul(1-(i+1)/(double)n).add(span.b().mul((i+1)/(double)n)),true,i*.5,(i+1)*.5));
        emitRailRun(out,run,mesh,ground,true,false,1);
      }
    }
  }

  private static void closureRails(List<Part> out,Mesh mesh,Ground ground,boolean modern,boolean highway){
    if(!LaneDeck.hasOpenings(mesh)||mesh.settings().structure()==Structure.GROUND||mesh.settings().structure()==Structure.TUNNEL
        ||mesh.settings().options().outerRail()==RoadProfile.OuterRail.OFF)return;
    var samples=mesh.samples();
    var caps=LaneDeck.caps(mesh);
    var runs=new ArrayList<ClosureRailRun>();
    for(int i=1;i<samples.size();i++){
      var a=samples.get(i-1);var b=samples.get(i);double station=(a.distance()+b.distance())/2;
      for(var strip:LaneDeck.strips(mesh,a,b))for(int side:new int[]{-1,1}){
        boolean wall=side>0?strip.highWall():strip.lowWall();if(!wall)continue;
        V first=side>0?strip.al():strip.ar(),last=side>0?strip.bl():strip.br();
        if(first.distance(a.at(side*a.halfWidth(),0))<1e-6&&last.distance(b.at(side*b.halfWidth(),0))<1e-6)continue;
        double widthA=strip.al().distance(strip.ar()),widthB=strip.bl().distance(strip.br());if(Math.min(widthA,widthB)<.5)continue;
        V rawFirst=first,rawLast=last;
        first=first.sub(a.left().mul(side*RoadRailJoin.INSET));last=last.sub(b.left().mul(side*RoadRailJoin.INSET));
        // At a concave corner the longitudinal rail extends into intact
        // pavement to meet the inset transverse cap. Clipping every nearby
        // sample to the cap's inward half-plane removed the first panel.
        // Intersect only the actual corner vertex, retaining the other samples.
        V tangent=last.sub(first).horizontalUnit();
        first=closureCorner(first,rawFirst,tangent,caps);
        last=closureCorner(last,rawLast,tangent,caps);
        if(first.distance(last)<1e-7)continue;
        V mid=first.add(last).mul(.5);
        // A narrow retained construction spine cannot turn an exposed bridge
        // ledge into supported ground. Use the resolved side classification,
        // just as the outer rail does; clipping and collision still apply below.
        boolean raised=RoadStreetscape.raised(mesh,sample(mesh,station),side)||elevated(mesh,mid,ground.top(mid.x(),mid.z(),mid.y()),ground);
        if(!raised)continue;
        V outside=mid.add(a.left().add(b.left()).horizontalUnit().mul(side*.4));
        // The declared paved mouth also trims its short longitudinal returns.
        // Applying it only to transverse caps left a full-height narrow panel
        // just before the ramp's half-block endpoint overlap.
        for(var span:ground.capRailSpans(first,last,outside)){
          ClosureRailRun target=null;
          for(var candidate:runs)if(candidate.side()==side&&!candidate.spans().isEmpty()
              &&candidate.spans().get(candidate.spans().size()-1).b().distance(span.a())<1e-5){target=candidate;break;}
          if(target==null){target=new ClosureRailRun(side,new ArrayList<>());runs.add(target);}
          target.spans().add(new RailSpan(span.a(),span.b(),true,a.distance(),b.distance()));
        }
      }
    }
    for(var run:runs)emitRailRun(out,seatClosureRun(run.spans(),mesh,ground,run.side(),highway),mesh,ground,modern,highway,run.side());
    // Transverse ends are also exposed edges. Previously only the two sides of
    // the hole had rails, allowing vehicles to enter the void straight ahead.
    for(var cap:caps){
      V axis=cap.b().sub(cap.a()).horizontalUnit(),mid=cap.a().add(cap.b()).mul(.5);
      var query=RoadQueries.horizontal(mesh,mid);
      boolean raised=RoadStreetscape.raised(mesh,query.sample(),query.lateral()<0?-1:1)||elevated(mesh,mid,ground.top(mid.x(),mid.z(),mid.y()),ground);
      if(!raised)continue;
      if(cap.a().distance(cap.b())<2*RoadRailJoin.INSET+.05)continue;
      V inward=axis.left().mul(RoadRailJoin.INSET);
      // A hole is concave: its side rails sit OUTSIDE the hole in intact pavement.
      // Extend across the two corners instead of shortening into the empty slot.
      var at=query.sample();
      boolean outerA=Math.abs(cap.a().sub(at.center()).dot(at.left()))>=at.halfWidth()-1e-5;
      boolean outerB=Math.abs(cap.b().sub(at.center()).dot(at.left()))>=at.halfWidth()-1e-5;
      V first=cap.a().add(axis.mul((outerA?1:-1)*RoadRailJoin.INSET)).add(inward),last=cap.b().add(axis.mul((outerB?-1:1)*RoadRailJoin.INSET)).add(inward);
      V outside=mid.sub(axis.left().mul(.4));
      for(var span:ground.capRailSpans(first,last,outside))emitCapRail(out,ground,span,highway,true);
    }
  }
  private static V closureCorner(V point,V raw,V tangent,List<LaneDeck.Cap> caps){
    for(var cap:caps){
      if(Math.min(raw.distance(cap.a()),raw.distance(cap.b()))>1e-5)continue;
      V inward=cap.b().sub(cap.a()).horizontalUnit().left();double den=tangent.dot(inward);
      if(Math.abs(den)<.1)continue;
      return point.add(tangent.mul((RoadRailJoin.INSET-point.sub(cap.a()).dot(inward))/den));
    }
    return point;
  }
  private record ClosureRailRun(int side,List<RailSpan> spans){}
  /** Keep a closure rail on its retained ledge when a descending ramp just
   * touches the nominal footing. Move into intact pavement, never into the
   * hole, and retain the same motor-clearance checks for the entire assembly. */
  private static List<RailSpan> seatClosureRun(List<RailSpan> run,Mesh mesh,Ground ground,int side,boolean highway){
    if(run.size()<2||highway)return run;
    int n=run.size();double[] offset=new double[n+1],distance=new double[n+1];var points=new ArrayList<V>();var normals=new ArrayList<V>();
    for(int i=0;i<=n;i++){
      var r=run.get(Math.min(i,n-1));V point=i==n?r.b():r.a();points.add(point);
      V normal=sample(mesh,i==n?r.endDistance():r.distance()).left().mul(-side);normals.add(normal);
      if(i>0)distance[i]=distance[i-1]+point.distance(points.get(i-1));
      if(closureSeatClear(point,normal,ground))continue;
      for(double shift=.02;shift<=.160001;shift+=.02)if(closureSeatClear(point.add(normal.mul(shift)),normal,ground)){offset[i]=shift;break;}
    }
    // Spread the move before its first contact. Endpoints stay on their
    // transverse cap/neighbor, rather than acquiring a new sideways gap.
    if(Arrays.stream(offset).allMatch(v->v==0))return run;
    for(int i=1;i<=n;i++)offset[i]=Math.max(offset[i],offset[i-1]-.025*(distance[i]-distance[i-1]));
    for(int i=n-1;i>=0;i--)offset[i]=Math.max(offset[i],offset[i+1]-.025*(distance[i+1]-distance[i]));
    for(int i=0;i<=n;i++){
      double shift=Math.min(offset[i],.025*Math.min(distance[i],distance[n]-distance[i]));
      V moved=points.get(i).add(normals.get(i).mul(shift));
      if(shift>0&&closureSeatClear(moved,normals.get(i),ground))points.set(i,moved);
    }
    var seated=new ArrayList<RailSpan>();
    for(int i=0;i<n;i++){var r=run.get(i);seated.add(new RailSpan(points.get(i),points.get(i+1),r.raised(),r.distance(),r.endDistance()));}
    return seated;
  }
  private static boolean closureSeatClear(V p,V inward,Ground ground){
    V step=inward.left().mul(.005),a=p.sub(step),b=p.add(step);
    if(ground.railBlocked(new Part(a,b,.18,.45,false,Material.CONCRETE),a,b))return false;
    for(double height:new double[]{.7,1.05})if(ground.railBlocked(new Part(a.add(new V(0,height,0)),b.add(new V(0,height,0)),.12,.12,false,Material.STEEL),a,b))return false;
    return true;
  }
  private static void emitCapRail(List<Part> out,Ground ground,RoadRailJoin.Span span,boolean highway,boolean raised){
    int steps=Math.max(1,(int)Math.ceil(span.a().distance(span.b())/.5));
    for(int j=0;j<steps;j++){
      V a=span.a().add(span.b().sub(span.a()).mul(j/(double)steps));
      V b=span.a().add(span.b().sub(span.a()).mul((j+1)/(double)steps));
      var pieces=new ArrayList<Part>();barrier(pieces,a,b,highway,raised,j*.5);
      out.addAll(clearBarrier(pieces,ground,a,b));
    }
  }
  private record RailSpan(V a,V b,boolean raised,double distance,double endDistance){}
  private static void emitOuterRailRun(List<Part> out,List<RailSpan> run,Mesh mesh,Ground ground,boolean modern,boolean highway,int side){
    // Explicit ON still describes a continuous guardrail, not centimetre-long
    // overlap slivers with full-size terminal posts in a shared collector.
    if(mesh.settings().style()!=Style.UNMARKED&&!run.isEmpty()
        &&run.get(0).distance()>1&&run.get(run.size()-1).endDistance()<mesh.length()-1
        &&run.stream().mapToDouble(r->r.a().distance(r.b())).sum()<.75)return;
    emitRailRun(out,run,mesh,ground,modern,highway,side);
  }
  private static void emitRailRun(List<Part> out,List<RailSpan> run,Mesh mesh,Ground ground,boolean modern,boolean highway,int side){
    if(run.isEmpty())return;
    double length=run.stream().mapToDouble(r->r.a.distance(r.b)).sum();
    if(modern && mesh.settings().style()!=Style.UNMARKED && !LaneDeck.hasOpenings(mesh) && mesh.settings().options().lanePoints().link()==null && mesh.settings().options().lanePoints().openings().isEmpty() && mesh.length()>8 && length<3
        && mesh.settings().options().outerRail()==RoadProfile.OuterRail.AUTO)return;
    if(modern&&mesh.settings().options().outerRail().sound(side)){
      var ordinary=new ArrayList<RailSpan>();
      for(int i=0;i<run.size();){var first=run.get(i);
        if(!first.raised()&&mesh.settings().structure()!=Structure.BRIDGE){ordinary.add(first);i++;continue;}
        int end=i;double panelLength=first.a().distance(first.b());
        while(end+1<run.size()&&panelLength<1.999&&run.get(end).b().distance(run.get(end+1).a())<1e-6&&(run.get(end+1).raised()||mesh.settings().structure()==Structure.BRIDGE)){end++;panelLength+=run.get(end).a().distance(run.get(end).b());}
        // A section breakpoint can leave micrometres after a complete module.
        // Retain that real edge as part of the preceding module, rather than
        // emitting another pair of full terminal posts on a paper-thin panel.
        double tail=0;int last=end;
        while(last+1<run.size()&&tail<=.25&&run.get(last).b().distance(run.get(last+1).a())<1e-6
            &&(run.get(last+1).raised()||mesh.settings().structure()==Structure.BRIDGE)){
          last++;tail+=run.get(last).a().distance(run.get(last).b());
        }
        if(last==run.size()-1&&tail<=.25)end=last;
        // Original CB module is two metres long. Side sign mirrors its inward cap.
        var part=new Part(first.a(),run.get(end).b(),.7,RoadNoiseModel.HEIGHT,false,Material.NOISE_BARRIER).frames(sample(mesh,first.distance()).left().mul(side*.35),sample(mesh,run.get(end).endDistance()).left().mul(side*.35));
        var assembly=RoadNoiseModel.assembly(part);
        // Validate and keep/remove the footing and panel as one unit, never half a wall.
        if(assembly.stream().noneMatch(ground::blocked))out.addAll(assembly);else for(int j=i;j<=end;j++)ordinary.add(run.get(j));i=end+1;
      }
      run=ordinary;
    }
    if(mesh.settings().style()==Style.RAILBED&&mesh.settings().options().railbed().fittings().fence()==RailbedOptions.Fence.MESH){
      var solid=new ArrayList<RailSpan>();
      for(int i=0;i<run.size();){var first=run.get(i);if(fenceRaised(mesh,run,i)){solid.add(first);i++;continue;}
        int end=i;double lengthPanel=first.a().distance(first.b());
        while(end+1<run.size()&&!fenceRaised(mesh,run,end+1)&&lengthPanel<2.49&&run.get(end).b().distance(run.get(end+1).a())<1e-6
            &&first.b().sub(first.a()).horizontalUnit().dot(run.get(end+1).b().sub(run.get(end+1).a()).horizontalUnit())>.998){end++;lengthPanel+=run.get(end).a().distance(run.get(end).b());}
        var panel=new Part(first.a(),run.get(end).b(),.10,1.65,false,Material.SIGN_GREEN,null,null,RailbedFence.MODEL);
        if(!ground.blocked(panel))out.add(panel);i=end+1;
      }
      run=solid;
    }
    for(var r:run) {
      if(modern){
        var pieces=new ArrayList<Part>();barrier(pieces,r.a,r.b,highway,r.raised,r.distance);
        V direction=r.b.sub(r.a),first=ground.railJoint(r.a,direction,highway,r.raised),last=ground.railJoint(r.b,direction,highway,r.raised);
        var assembly=new ArrayList<Part>();
        for(var piece:pieces){
          if(piece.material()==Material.DARK_STEEL&&!ground.railPost(r.a,highway,r.raised))continue;
          if(piece.a().sub(r.a).horizontalLength()<1e-6&&piece.b().sub(r.b).horizontalLength()<1e-6)
            piece=piece.frames(first==null?piece.frameA():first.mul(piece.width()/2),last==null?piece.frameB():last.mul(piece.width()/2));
          assembly.add(piece);
        }
        // A raised rail and its footing form one assembly. Removing only the
        // blocked footing leaves steel/posts suspended above a joining deck.
        boolean check=ground.unionRails()||LaneDeck.hasOpenings(mesh)||mesh.settings().options().lanePoints().link()!=null||!mesh.settings().options().lanePoints().openings().isEmpty();
        for(var piece:check?clearBarrier(assembly,ground,r.a,r.b):assembly)add(out,piece);
      }
      else {var part=new Part(r.a,r.b,.24,1.05,false);if(!ground.blocked(part))add(out,part);}
    }
  }

  private static boolean fenceRaised(Mesh mesh,List<RailSpan> run,int index){
    if(mesh.settings().structure()==Structure.BRIDGE)return true;
    if(!run.get(index).raised())return false;
    // A free end cap can sample just beyond its original terrain. Do not turn
    // that short return into concrete when the adjoining run is a mesh fence.
    int from=index,to=index;double length=run.get(index).a().distance(run.get(index).b());
    while(from>0&&run.get(from-1).raised()&&length<=.51){from--;length+=run.get(from).a().distance(run.get(from).b());}
    while(to+1<run.size()&&run.get(to+1).raised()&&length<=.51){to++;length+=run.get(to).a().distance(run.get(to).b());}
    return !(length<=.51&&(from==0||to==run.size()-1)&&(from>0||to+1<run.size()));
  }

  /** Fit the concrete bearing to a tight seam before discarding the whole rail.
   * Keep bar/post axes and height fixed, with a footing wider than the supported
   * steel. Every fitted part still passes the same exact clearance predicate. */
  private static List<Part> clearBarrier(List<Part> pieces,Ground ground,V a,V b){
    return clearBarrier(pieces,ground,a,b,0);
  }
  private static List<Part> clearBarrier(List<Part> pieces,Ground ground,V a,V b,int depth){
    var fitted=fitBarrier(pieces,ground,a,b);if(!fitted.isEmpty()||pieces.isEmpty())return fitted;
    // A sloping/curving join may occupy only one end of this sampled panel.
    // Retain the clear interval, keeping footing and bars together. Dropping the
    // whole panel made visible gaps depend on the unrelated sample spacing.
    double length=a.distance(b);if(depth>=8||length<.02)return List.of();
    boolean usable=false;
    for(double t:new double[]{0,.5,1}){
      double from=Math.max(0,t-.001/length),to=Math.min(1,t+.001/length);
      V first=a.add(b.sub(a).mul(from)),last=a.add(b.sub(a).mul(to));
      if(!fitBarrier(barrierSlice(pieces,a,b,from,to),ground,first,last).isEmpty()){usable=true;break;}
    }
    if(!usable)return List.of();
    V mid=a.add(b).mul(.5);var out=new ArrayList<Part>();
    out.addAll(clearBarrier(barrierSlice(pieces,a,b,0,.5),ground,a,mid,depth+1));
    out.addAll(clearBarrier(barrierSlice(pieces,a,b,.5,1),ground,mid,b,depth+1));
    return out;
  }
  private static List<Part> barrierSlice(List<Part> pieces,V a,V b,double from,double to){
    var out=new ArrayList<Part>();V axis=b.sub(a);double length2=axis.dot(axis);
    for(var p:pieces){
      if(p.material()==Material.DARK_STEEL){
        // Posts have their own 16 cm footprint; do not copy one into each slice.
        double d=p.a().sub(a).dot(axis)/length2;
        if(d>=from-1e-8&&d<to-1e-8)out.add(p);
      }else{
        var base=p.base();V fa=base.get(0).sub(p.a()),fb=base.get(3).sub(p.b());
        out.add(new Part(p.a().add(p.b().sub(p.a()).mul(from)),p.a().add(p.b().sub(p.a()).mul(to)),p.width(),p.height(),p.pier(),p.material(),
            fa.mul(1-from).add(fb.mul(from)),fa.mul(1-to).add(fb.mul(to)),p.model()));
      }
    }return out;
  }
  private static List<Part> fitBarrier(List<Part> pieces,Ground ground,V a,V b){
    if(pieces.stream().noneMatch(p->ground.railBlocked(p,a,b)))return pieces;
    var fitted=new ArrayList<Part>();
    for(var p:pieces){
      if(!ground.railBlocked(p,a,b)){fitted.add(p);continue;}
      // A periodic post extending into the joining deck must not erase clear
      // horizontal bars and their bearing. Try the other end of this interval;
      // final exposed ends receive independently checked terminal posts below.
      if(p.material()==Material.DARK_STEEL){
        V delta=b.sub(a).horizontalUnit().mul(Math.max(0,b.sub(a).horizontalLength()-.16));
        var moved=new Part(p.a().add(delta),p.b().add(delta),p.width(),p.height(),p.pier(),p.material(),p.frameA(),p.frameB(),p.model());
        if(!ground.railBlocked(moved,a,b))fitted.add(moved);
        continue;
      }
      boolean urban=p.material()==Material.CONCRETE&&Math.abs(p.width()-.42)<1e-7&&Math.abs(p.height()-.45)<1e-7;
      boolean highway=p.material()==Material.CONCRETE&&Math.abs(p.width()-.62)<1e-7&&Math.abs(p.height()-.8)<1e-7;
      if(!urban&&!highway)return List.of();
      Part clear=null;double minimum=urban?.18:.40;
      for(double width=p.width()-.02;width>=minimum-1e-7;width-=.02){
        double scale=width/p.width();var candidate=new Part(p.a(),p.b(),width,p.height(),false,p.material(),p.frameA()==null?null:p.frameA().mul(scale),p.frameB()==null?null:p.frameB().mul(scale),p.model());
        if(!ground.railBlocked(candidate,a,b)){clear=candidate;break;}
      }
      if(clear==null)return List.of();fitted.add(clear);
    }
    return fitted;
  }

  /** A marker hole is harmless; a one-block construction spine is not a supported deck. */
  public static boolean elevated(Mesh mesh,V point,double top,Ground ground) {return elevated(mesh,point,ground);}
  private static boolean elevated(Mesh mesh,V point,Ground ground) {
    double threshold=GROUND_CONTACT;
    int unsupported=0;
    for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)if(x!=0||z!=0) {
      double near=ground.top(point.x()+x*.65,point.z()+z*.65,point.y());
      if(Double.isNaN(near)||point.y()-near>threshold)unsupported++;
    }
    return unsupported>=5;
  }

  /** Classify the usable cross section, independently of blocks used to lay out its axis. */
  public static boolean elevated(Mesh mesh,Sample at,Ground ground){return elevated(mesh,at,ground,-1)||elevated(mesh,at,ground,1);}
  public static boolean elevated(Mesh mesh,Sample at,Ground ground,int side){
    if(mesh.settings().structure()==Structure.BRIDGE)return true;
    if(mesh.settings().structure()==Structure.GROUND||mesh.settings().structure()==Structure.TUNNEL)return false;
    // Longitudinal voting filters marker holes. Inspect each half separately: a bank
    // under the opposite carriageway must not classify an overhanging deck as ground.
    double threshold=GROUND_CONTACT;int raisedRows=0;
    for(double step:new double[]{-.8,0,.8}){
      var sample=sample(mesh,Math.max(0,Math.min(mesh.length(),at.distance()+step)));boolean raised=false;
      {int missing=0;for(double fraction:new double[]{.35,.65,.9}){
        V p=sample.at(side*sample.halfWidth()*fraction,0);double top=ground.top(p.x(),p.z(),p.y());
        if(!Double.isFinite(top)||p.y()-top>threshold)missing++;
      }if(missing>=2)raised=true;}if(raised)raisedRows++;
    }
    return raisedRows>=2;
  }

  /** The entire shaft plus its vehicle margin must fit within an uninterrupted lower median. */
  public static boolean fitsMedian(Part pier, Mesh lower, List<Mesh> neighbors) {
    if (!pier.pier() || !RoadProfile.modern(lower.settings().style())
        || !RoadProfile.catalog(lower.settings().style()).twoWay()) return false;
    double margin = pier.height()<=.4?.04:.10;
    for (V p : pier.base()) {
      var q = RoadQueries.horizontal(lower, p);
      var s = q.sample();
      var layout = RoadProfile.layout(lower,s);
      double usable = layout.median();
      if (RoadTransitions.green(lower,s) * (1-RoadStreetscape.medianConversionBlend(lower,s)) < .12) {
        boolean raised=RoadStreetscape.raised(lower,s);
        double band = RoadProfile.highway(lower.settings().style()) ? (raised?.62:.3) : (raised?.42:.1);
        double separation = Math.min(RoadTransitions.railSeparation(lower,s,band), Math.max(0,layout.median()-band));
        // The median includes both rails. Only their clear inner gap can hold
        // a foundation; a single central rail has no usable opening.
        usable = Math.max(0,separation-band);
      }
      if (s.distance() < 2 || s.distance() > lower.length() - 2
          || Math.abs(q.lateral()-layout.medianCenter()) + margin > usable / 2)
        return false;
      for (var road : neighbors) {
        if (road == lower || road.settings().style() != Style.UNMARKED) continue;
        if (RoadQueries.contains(road, s.at(RoadProfile.layout(lower,s).medianCenter(),0), 2, .2)) return false;
      }
    }
    return true;
  }

  /** Furniture is baked once together with the collision; never generated in the render loop. */
  private static void furniture(Mesh mesh, Ground ground, List<Part> out, RoadFurniture.Phase phase) {
    var profile = RoadProfile.catalog(mesh.settings().style());
    boolean highway = RoadProfile.highway(mesh.settings().style());
    for (double d = 0; d < mesh.length() - 1e-6; d += 2) {
      Sample a = sample(mesh, d), b = sample(mesh, Math.min(mesh.length(), d + 2));
      var la = RoadProfile.layout(mesh, a);
      var lb = RoadProfile.layout(mesh, b);
      if (profile.twoWay())
        for (double[] span : medianSpans(mesh, ground, d, Math.min(mesh.length(), d + 2)))
          median(out, mesh, ground, sample(mesh, span[0]), sample(mesh, span[1]), highway, span[0]);
      for (int side : la.outsideSides()) {
        cycleBarrier(mesh,ground,out,a,b,side);
        if (la.curbWidth() > 0 || lb.curbWidth() > 0) {
          for(double[] span:openSpans(d,Math.min(mesh.length(),d+2),station->{
            Sample at=sample(mesh,station);
            return !bridgeAt(mesh,at,ground,side) && !ground.joined(at.at(side*(at.halfWidth()+.02),0));
          })) {
            Sample first=sample(mesh,span[0]),last=sample(mesh,span[1]);
            var fl=RoadProfile.layout(mesh,first);var ll=RoadProfile.layout(mesh,last);
            double ca=RoadProfile.curbExtent(fl,first,side);
            double cb=RoadProfile.curbExtent(ll,last,side);
            if(Math.max(ca,cb)<.001)continue;
            add(out,new Part(first.at(side*(first.halfWidth()-ca/2),0),last.at(side*(last.halfWidth()-cb/2),0),
                Math.max(ca,cb),.2,false,Material.CONCRETE).frames(first.left().mul(ca/2),last.left().mul(cb/2)));
          }
        }
      }
    }
    cyclePlanting(mesh,ground,out);
    closePlantingEnds(out,mesh);
    var amenities=RoadStreetscape.plan(mesh,ground,phase);
    var lampBases=amenities.stream().filter(p->p.material()==Material.CONCRETE&&p.width()==.38&&p.height()==.16).toList();
    trimHedges(out,lampBases);
    out.addAll(amenities);
  }

  /** Only bicycle separation is shared with tunnels; no open-air planting or lamps. */
  public static void cycleFurniture(Mesh mesh,Ground ground,List<Part> out) {
    for(double d=0;d<mesh.length()-1e-6;d+=2){
      var a=sample(mesh,d);var b=sample(mesh,Math.min(mesh.length(),d+2));
      for(int side:RoadProfile.layout(mesh,a).outsideSides())cycleBarrier(mesh,ground,out,a,b,side);
    }
  }
  private static void cycleBarrier(Mesh mesh,Ground ground,List<Part> out,Sample a,Sample b,int side) {
    var la=RoadProfile.layout(mesh,a);var lb=RoadProfile.layout(mesh,b);
    double mid=(a.distance()+b.distance())/2,separation=RoadStreetscape.separatorWidth(mesh.settings().options());
    boolean raised=mesh.settings().structure()!=Structure.TUNNEL&&bridgeAt(mesh,sample(mesh,mid),ground,side);
    if(!RoadsideBypass.crossing(mesh,mid,side)&&la.cycleWidth()>0&&lb.cycleWidth()>0
        &&(mesh.settings().options().cycleRail()||raised&&separation>0)){
      V aa=a.at(la.outer(side)+side*separation/2,0),bb=b.at(lb.outer(side)+side*separation/2,0);
      if(!ground.joined(aa.add(bb).mul(.5)))barrier(out,aa,bb,false,false,a.distance());
    }
  }
  private static boolean plantingContinues(Mesh mesh,boolean start,boolean cycle,int side) {
    var ends=mesh.settings().options().ends();var section=start?ends.start():ends.end();
    if(section==null)return (start?ends.trimmedStart():ends.trimmedEnd())>0;
    var layout=section.layout(mesh.settings().options().leftTraffic());
    if(cycle)return layout.cycleWidth()>0&&section.streetscape()!=null
        &&section.streetscape().separator()==RoadStreetscape.Separator.GREEN
        &&!section.streetscape().parking()&&java.util.Arrays.stream(layout.outsideSides()).anyMatch(v->v==side);
    return layout.catalog().median()==RoadProfile.Median.GREEN&&layout.median()>.5;
  }

  /** Taper each connected bicycle planter run, including a crossing mouth. */
  private static void cyclePlanting(Mesh mesh,Ground ground,List<Part> out){
    double separation=RoadStreetscape.separatorWidth(mesh.settings().options());if(separation<=0)return;
    for(int side:new int[]{-1,1}){
      var runs=new ArrayList<double[]>();
      for(double d=0;d<mesh.length()-1e-7;d+=.5){double end=Math.min(mesh.length(),d+.5),mid=(d+end)/2;var at=sample(mesh,mid);var l=RoadProfile.layout(mesh,at);
        V center=at.at(l.outer(side)+side*separation/2,0);
        if(l.cycleWidth()<=0||java.util.Arrays.stream(l.outsideSides()).noneMatch(v->v==side)||RoadsideBypass.crossing(mesh,mid,side)||bridgeAt(mesh,at,ground,side)||RoadStreetscape.cut(mesh,at,side)||ground.joined(center)||ground.furnitureClear(center))continue;
        if(!runs.isEmpty()&&Math.abs(runs.get(runs.size()-1)[1]-d)<1e-7)runs.get(runs.size()-1)[1]=end;else runs.add(new double[]{d,end});
      }
      for(var run:runs){double fade=Math.min(6,(run[1]-run[0])/2);
        for(double d=run[0];d<run[1]-1e-7;){double step=d-run[0]>=fade&&run[1]-d>fade+2?2:.5;double end=Math.min(run[1],d+step);var a=sample(mesh,d);var b=sample(mesh,end);
          boolean openStart=run[0]<1e-7&&plantingContinues(mesh,true,true,side);
          boolean openEnd=Math.abs(run[1]-mesh.length())<1e-7&&plantingContinues(mesh,false,true,side);
          double fa=Settings.smooth(Math.min(1,Math.min(openStart?fade:d-run[0],openEnd?fade:run[1]-d)/fade));
          double fb=Settings.smooth(Math.min(1,Math.min(openStart?fade:end-run[0],openEnd?fade:run[1]-end)/fade));
          cyclePlanter(out,a,b,side,RoadProfile.layout(mesh,a).outer(side),RoadProfile.layout(mesh,b).outer(side),separation,fa,fb);
          d=end;
        }
      }
    }
  }

  /** Shared bicycle separator cross-section for ordinary roads and junction approaches. */
  public static void cyclePlanter(List<Part> out,Sample a,Sample b,int side,double innerA,double innerB,double separation,double fa,double fb){
    double wa0=.6+(separation-.6)*fa,wb0=.6+(separation-.6)*fb;
    double ca=innerA+side*wa0/2,cb=innerB+side*wb0/2;
    double[] insets={0,.24,.3},ys={0,.3,.45},hs={.3,.15,.4};Material[] mats={Material.CONCRETE,Material.SOIL,Material.GREEN};
    for(int k=0;k<3;k++){double wa=wa0-insets[k],wb=wb0-insets[k];
      out.add(new Part(a.at(ca,-ys[k]),b.at(cb,-ys[k]),Math.max(wa,wb),hs[k],false,mats[k],a.left().mul(wa/2),b.left().mul(wb/2),"sr:cycle_planter"));
    }
  }
  /** Upgrade only authored planting parts; never re-plan bridges, earthworks or ramps. */
  public static List<Part> repairJoinedPlanting(Mesh mesh,List<Part> saved){
    var out=new ArrayList<>(saved);var caps=saved.stream().filter(p->p.model().equals("sr:planting_end")
        &&(joinedPlantingEnd(mesh,p.a())||joinedPlantingEnd(mesh,p.b()))).toList();
    for(var cap:caps){
      boolean first=joinedPlantingEnd(mesh,cap.a());V outer=first?cap.a():cap.b(),inner=first?cap.b():cap.a();
      V frame=(first?cap.frameA():cap.frameB());frame=frame.sub(frame.horizontalUnit().mul(.2));
      for(int i=0;i<out.size();i++){var soil=out.get(i);if(!soil.model().equals("sr:planting_soil"))continue;
        if(soil.a().distance(inner)<1e-5)out.set(i,new Part(outer,soil.b(),Math.max(soil.width(),frame.horizontalLength()*2),soil.height(),false,soil.material(),frame,soil.frameB(),soil.model()));
        else if(soil.b().distance(inner)<1e-5)out.set(i,new Part(soil.a(),outer,Math.max(soil.width(),frame.horizontalLength()*2),soil.height(),false,soil.material(),soil.frameA(),frame,soil.model()));
      }
      out.remove(cap);
    }
    double separation=RoadStreetscape.separatorWidth(mesh.settings().options());if(separation<=0)return List.copyOf(out);
    for(int side:new int[]{-1,1}){
      boolean start=plantingContinues(mesh,true,true,side),end=plantingContinues(mesh,false,true,side);if(!start&&!end)continue;
      var runs=new ArrayList<double[]>();
      var base=saved.stream().filter(p->p.model().equals("sr:cycle_planter")&&p.material()==Material.CONCRETE)
          .filter(p->Math.signum(RoadQueries.horizontal(mesh,p.a().add(p.b()).mul(.5)).lateral())==side)
          .sorted(Comparator.comparingDouble(p->RoadQueries.horizontal(mesh,p.a()).sample().distance())).toList();
      for(var p:base){double a=RoadQueries.horizontal(mesh,p.a()).sample().distance(),b=RoadQueries.horizontal(mesh,p.b()).sample().distance();
        if(!runs.isEmpty()&&Math.abs(runs.get(runs.size()-1)[1]-a)<1e-5)runs.get(runs.size()-1)[1]=b;else runs.add(new double[]{a,b});
      }
      for(var run:runs){boolean openStart=start&&run[0]<1e-5,openEnd=end&&Math.abs(run[1]-mesh.length())<1e-5;if(!openStart&&!openEnd)continue;
        out.removeIf(p->p.model().equals("sr:cycle_planter")&&Math.signum(RoadQueries.horizontal(mesh,p.a().add(p.b()).mul(.5)).lateral())==side
            &&RoadQueries.horizontal(mesh,p.a()).sample().distance()>=run[0]-1e-5&&RoadQueries.horizontal(mesh,p.b()).sample().distance()<=run[1]+1e-5);
        double fade=Math.min(6,(run[1]-run[0])/2);if(fade<1e-6)continue;
        for(double d=run[0];d<run[1]-1e-7;){double step=d-run[0]>=fade&&run[1]-d>fade+2?2:.5,stop=Math.min(run[1],d+step);var a=sample(mesh,d);var b=sample(mesh,stop);
          double fa=Settings.smooth(Math.min(1,Math.min(openStart?fade:d-run[0],openEnd?fade:run[1]-d)/fade));
          double fb=Settings.smooth(Math.min(1,Math.min(openStart?fade:stop-run[0],openEnd?fade:run[1]-stop)/fade));
          cyclePlanter(out,a,b,side,RoadProfile.layout(mesh,a).outer(side),RoadProfile.layout(mesh,b).outer(side),separation,fa,fb);d=stop;
        }
      }
    }
    return List.copyOf(out);
  }

  /** Subtract only the pedestal footprint, retaining both sides of the planted strip. */
  public static void trimHedges(List<Part> out,List<Part> bases){
    trimPlanting(out,bases,false);
  }
  /** A sign pedestal replaces both foliage and the soil above its finished top. */
  public static void trimPlanting(List<Part> out,List<Part> bases,boolean soil){
    var result=new ArrayList<Part>();
    for(var p:out){if(p.material()!=Material.GREEN&&!(soil&&p.material()==Material.SOIL)){result.add(p);continue;}
      subtractFootprint(result,p,bases);
    }out.clear();out.addAll(result);
  }
  static void subtractFootprint(List<Part> result,Part p,List<Part> bases){
      List<List<V>> polygons=List.of(p.base());boolean changed=false;
      for(var b:bases){double before=polygons.stream().mapToDouble(RoadSurface::area).sum();var next=new ArrayList<List<V>>();for(var poly:polygons)next.addAll(RoadSurface.subtract(poly,b.base()));
        if(before-next.stream().mapToDouble(RoadSurface::area).sum()>1e-8){changed=true;polygons=next;}}
      if(!changed){result.add(p);return;}
      for(var poly:polygons)for(int i=1;i+1<poly.size();i++){V a=poly.get(0),b=poly.get(i),c=poly.get(i+1);if(RoadSurface.area(List.of(a,b,c))<1e-8)continue;
          result.add(new Part(a.add(b).mul(.5),c,Math.max(.001,Math.min(8,a.distance(b))),p.height(),false,p.material(),null,null,p.model()).frames(a.sub(b).mul(.5),new V(0,0,0)));}

  }
  private static boolean bridgeAt(Mesh mesh,Sample at,Ground ground,int side){return RoadStreetscape.raised(mesh,at,side)||mesh.settings().structure()!=Structure.GROUND&&!RoadStreetscape.underground(mesh,at.distance())&&!RoadAutoTunnels.below(mesh,at,ground,side)&&elevated(mesh,at,ground,side);}
  private static boolean bridgeAt(Mesh mesh,Sample at,Ground ground) {
    return RoadStreetscape.raised(mesh,at) || mesh.settings().structure()!=Structure.GROUND
        && elevated(mesh,at,ground);
  }

  private static boolean medianOpen(Mesh mesh, Ground ground, double distance) {
    var at=sample(mesh,distance);
    for(var access:mesh.settings().options().lanePoints().access())
      for(var gap:access.gaps())if(gap.at(distance))return false;
    V center=at.at(RoadProfile.layout(mesh,at).medianCenter(),0);
    return !ground.joined(center) && !ground.furnitureClear(center);
  }

  /** Trim furniture at the actual stop/pad boundary instead of dropping a whole 2m tile. */
  private static List<double[]> medianSpans(Mesh mesh, Ground ground, double from, double to) {
    return openSpans(from,to,d->medianOpen(mesh,ground,d));
  }

  private static List<double[]> openSpans(double from,double to,java.util.function.DoublePredicate visible) {
    List<double[]> spans = new ArrayList<>();
    double previous = from, begin = from;
    boolean open = visible.test(from);
    int steps = (int)Math.ceil((to - from) / .5);
    for (int i = 1; i <= steps; i++) {
      double next = from + (to - from) * i / steps;
      boolean after = visible.test(next);
      if (after != open) {
        double lo = previous, hi = next;
        for (int j = 0; j < 16; j++) {
          double mid = (lo + hi) / 2;
          if (visible.test(mid) == open) lo = mid; else hi = mid;
        }
        double boundary = (lo + hi) / 2;
        if (open && boundary - begin > 1e-5) spans.add(new double[] {begin, boundary});
        else begin = boundary;
        open = after;
      }
      previous = next;
    }
    if (open && to - begin > 1e-5) spans.add(new double[] {begin, to});
    return spans;
  }

  /** Tunnel and open-road medians use the same actual transition profile. Do not
   * replace tunnel medians by a fixed narrow concrete bar or omit its last 2m tile. */
  public static void medianFurniture(Mesh mesh,Ground ground,List<Part> out) {
    var catalog=RoadProfile.catalog(mesh.settings().style());if(!catalog.twoWay())return;
    for(double d=0;d<mesh.length()-1e-6;d+=2)
      for(double[] span:medianSpans(mesh,ground,d,Math.min(mesh.length(),d+2)))
        median(out,mesh,ground,sample(mesh,span[0]),sample(mesh,span[1]),catalog.type()==RoadProfile.Type.HIGHWAY,span[0]);
    closePlantingEnds(out,mesh);
  }

  private static void median(List<Part> out, Mesh mesh, Ground ground,
      Sample a, Sample b, boolean highway, double d) {
    var la = RoadProfile.layout(mesh, a);
    var lb = RoadProfile.layout(mesh, b);
    boolean raised = mesh.settings().structure()!=Structure.TUNNEL&&bridgeAt(mesh,sample(mesh,(a.distance()+b.distance())/2),ground);
    double greenA=RoadTransitions.green(mesh,a)*(1-RoadStreetscape.medianConversionBlend(mesh,a));
    double greenB=RoadTransitions.green(mesh,b)*(1-RoadStreetscape.medianConversionBlend(mesh,b));
    double green = Math.min(greenA,greenB);
    double terrainBlend = mesh.settings().structure()==Structure.TUNNEL ? 0 : 1;
    green *= terrainBlend;
    double median = Math.min(la.median(), lb.median());
    if (median >= .35 && (green < .12 || median <= .5)) {
      medianBarrier(out,mesh,a,b,highway,raised,d);
    }
    if (green >= .12 && median > .5) {
      out.addAll(planting(a.at(la.medianCenter(),0),b.at(lb.medianCenter(),0),a.left(),b.left(),
          la.median(),lb.median(),greenA,greenB,0));
    }
  }

  public static void medianBarrier(List<Part> out,Mesh mesh,Sample a,Sample b,boolean highway,boolean raised,double d){
    var la=RoadProfile.layout(mesh,a);var lb=RoadProfile.layout(mesh,b);
    double band=highway?(raised?.62:.3):(raised?.42:.1);
    double ga=Math.min(RoadTransitions.railSeparation(mesh,a,band),Math.max(0,la.median()-band));
    double gb=Math.min(RoadTransitions.railSeparation(mesh,b,band),Math.max(0,lb.median()-band));
    // Preserve lane axes, including green medians converted to tunnel barriers.
    if(Math.max(ga,gb)>1e-7)for(int side:new int[]{-1,1}){
      barrier(out,a.at(la.medianCenter()+side*ga/2,0),b.at(lb.medianCenter()+side*gb/2,0),highway,raised,d);
    }else barrier(out,a.at(la.medianCenter(),0),b.at(lb.medianCenter(),0),highway,raised,d);
  }

  /** Common raised planting bed: normal medians and temporarily closed ground lanes.
   * Positive foundationDepth extends the soil below the removed deck; the visible
   * curb/soil/foliage heights remain the same as a normal-road median. */
  public static List<Part> planting(V a,V b,V leftA,V leftB,double widthA,double widthB,double green,double foundationDepth){
    return planting(a,b,leftA,leftB,widthA,widthB,green,green,foundationDepth);
  }
  private static List<Part> planting(V a,V b,V leftA,V leftB,double widthA,double widthB,double greenA,double greenB,double foundationDepth){
    double green=Math.min(greenA,greenB);
    var out=new ArrayList<Part>();double wa=Math.max(.08,(widthA-.3)*greenA),wb=Math.max(.08,(widthB-.3)*greenB);
    double depth=Math.max(0,foundationDepth);
    if(Math.max(widthA,widthB)<=8){
      out.add(new Part(a.add(new V(0,-depth,0)),b.add(new V(0,-depth,0)),Math.max(wa,wb),depth+.3,false,Material.SOIL,leftA.mul(wa/2),leftB.mul(wb/2),"sr:planting_soil"));
      out.add(new Part(a.add(new V(0,.3,0)),b.add(new V(0,.3,0)),Math.max(.06,(Math.min(widthA,widthB)-.5)*green),.55*green,false,Material.GREEN)
          .frames(leftA.mul(Math.max(.03,(widthA-.5)*greenA/2)),leftB.mul(Math.max(.03,(widthB-.5)*greenB/2))));
      out.add(new Part(a.add(new V(0,.3+.52*green,0)),b.add(new V(0,.3+.52*green,0)),Math.max(.04,(Math.min(widthA,widthB)-.85)*green),.25*green,false,Material.GREEN)
          .frames(leftA.mul(Math.max(.02,(widthA-.85)*greenA/2)),leftB.mul(Math.max(.02,(widthB-.85)*greenB/2))));
    }else{
      plantingLayer(out,a.add(new V(0,-depth,0)),b.add(new V(0,-depth,0)),leftA,leftB,wa,wb,depth+.3,Material.SOIL,"sr:planting_soil");
      plantingLayer(out,a.add(new V(0,.3,0)),b.add(new V(0,.3,0)),leftA,leftB,Math.max(.06,(widthA-.5)*greenA),Math.max(.06,(widthB-.5)*greenB),.55*green,Material.GREEN,"");
      plantingLayer(out,a.add(new V(0,.3+.52*green,0)),b.add(new V(0,.3+.52*green,0)),leftA,leftB,Math.max(.04,(widthA-.85)*greenA),Math.max(.04,(widthB-.85)*greenB),.25*green,Material.GREEN,"");
    }
    for(int side:new int[]{-1,1})out.add(new Part(a.add(leftA.mul(side*(wa/2+.1))).add(new V(0,-depth,0)),
        b.add(leftB.mul(side*(wb/2+.1))).add(new V(0,-depth,0)),.2,depth+.35,false,Material.CONCRETE)
        .frames(leftA.mul(.1),leftB.mul(.1)));
    return List.copyOf(out);
  }

  /** Tile broad solids within the existing eight-block part bound. Only the
   * outer edges of the complete bed receive kerbs. */
  static void plantingLayer(List<Part> out,V a,V b,V leftA,V leftB,double widthA,double widthB,double height,Material material,String model){
    int count=Math.max(1,(int)Math.ceil(Math.max(widthA,widthB)/8));
    for(int i=0;i<count;i++){
      double offset=(i+.5)/count-.5;
      out.add(new Part(a.add(leftA.mul(offset*widthA)),b.add(leftB.mul(offset*widthB)),Math.max(widthA,widthB)/count,height,false,material,
          leftA.mul(widthA/(2*count)),leftB.mul(widthB/(2*count)),model));
    }
  }

  /** Close only exposed run ends; neighboring bed tiles share no transverse cap. */
  private static boolean joinedPlantingEnd(Mesh mesh,V p){
    for(boolean start:new boolean[]{true,false}){
      var at=start?mesh.first():mesh.last();var l=RoadProfile.layout(mesh,at);var delta=p.sub(at.center());
      if(Math.abs(delta.dot(at.left().left()))<1e-5&&Math.abs(delta.dot(at.left())-l.medianCenter())<.01
          &&plantingContinues(mesh,start,false,0))return true;
    }
    return false;
  }
  public static void closePlantingEnds(List<Part> out,Mesh mesh){
    record End(long x,long y,long z){End(V p){this(Math.round(p.x()*1e6),Math.round(p.y()*1e6),Math.round(p.z()*1e6));}}
    var counts=new HashMap<End,Integer>();
    for(var p:out)if(p.material()==Material.SOIL&&p.model().equals("sr:planting_soil")){
      counts.merge(new End(p.a()),1,Integer::sum);counts.merge(new End(p.b()),1,Integer::sum);
    }
    var caps=new ArrayList<Part>();
    for(int i=0;i<out.size();i++){
      var p=out.get(i);if(p.material()!=Material.SOIL||!p.model().equals("sr:planting_soil"))continue;
      boolean first=counts.get(new End(p.a()))==1&&!joinedPlantingEnd(mesh,p.a()),last=counts.get(new End(p.b()))==1&&!joinedPlantingEnd(mesh,p.b());
      if(!first&&!last)continue;
      double length=p.a().sub(p.b()).horizontalLength();if(length<1e-7)continue;
      double t=Math.min(.25/length,first&&last?.3:.6);
      V delta=p.b().sub(p.a()),df=p.frameB().sub(p.frameA());
      V a=first?p.a().add(delta.mul(t)):p.a(),b=last?p.b().sub(delta.mul(t)):p.b();
      V fa=first?p.frameA().add(df.mul(t)):p.frameA(),fb=last?p.frameB().sub(df.mul(t)):p.frameB();
      out.set(i,new Part(a,b,p.width(),p.height(),false,p.material(),fa,fb,p.model()));
      for(boolean head:new boolean[]{true,false})if(head?first:last){
        V outer=head?p.a():p.b(),inner=head?a:b,fo=head?p.frameA():p.frameB(),fi=head?fa:fb;
        fo=fo.add(fo.horizontalUnit().mul(.2));fi=fi.add(fi.horizontalUnit().mul(.2));
        // Concrete owns this end volume; soil no longer occupies an almost
        // coplanar face behind it and cannot bleed through as depth precision falls.
        caps.add(new Part(outer,inner,Math.min(8,Math.max(fo.horizontalLength(),fi.horizontalLength())*2),p.height()+.05,false,Material.CONCRETE,fo,fi,"sr:planting_end"));
      }
    }
    out.addAll(caps);
  }

  /** Tapered pole, collar, swept arm, housing and recessed LED lens. */
  private static List<Part> lamp(V foot, V along, V inward, boolean bridge) {
    List<Part> parts = new ArrayList<>();
    double height = bridge ? 5.9 : 7, lower = bridge ? 2.6 : 3.4;
    double armLength = bridge ? 1.35 : 2.2;
    parts.add(postPart(foot, along, .4, .18, Material.CONCRETE));
    parts.add(postPart(foot.add(new V(0, .16, 0)), along, .29, .12, Material.DARK_STEEL));
    parts.add(postPart(foot.add(new V(0, .26, 0)), along, .19, lower, Material.DARK_STEEL));
    parts.add(
        postPart(
            foot.add(new V(0, lower + .24, 0)), along, .13, height - lower - .24, Material.STEEL));
    parts.add(postPart(foot.add(new V(0, lower + .21, 0)), along, .25, .13, Material.DARK_STEEL));
    V root = foot.add(new V(0, height - .3, 0));
    V bend = root.add(inward.mul(armLength * .55)).add(new V(0, bridge ? .15 : .35, 0));
    V head = root.add(inward.mul(armLength)).add(new V(0, bridge ? .15 : .35, 0));
    parts.add(new Part(root, bend, .12, .10, false, Material.STEEL));
    parts.add(new Part(bend, head, .12, .10, false, Material.STEEL));
    parts.add(
        new Part(
            head.sub(inward.mul(.36)),
            head.add(inward.mul(.55)),
            .46,
            .16,
            false,
            Material.DARK_STEEL));
    parts.add(
        new Part(
            head.sub(inward.mul(.26)).add(new V(0, -.04, 0)),
            head.add(inward.mul(.45)).add(new V(0, -.04, 0)),
            .34,
            .06,
            false,
            Material.LAMP));
    return parts;
  }

  private static Part postPart(V center, V along, double width, double height, Material material) {
    return new Part(
        center.sub(along.mul(width / 2)),
        center.add(along.mul(width / 2)),
        width,
        height,
        false,
        material);
  }

  /** Four distinct assemblies: urban rail, urban bridge rail, highway beam, highway parapet. */
  public static void barrier(
      List<Part> out, V a, V b, boolean highway, boolean raised, double distance) {
    if (highway && raised) {
      add(out, new Part(a, b, .62, .8, false, Material.CONCRETE));
      add(
          out,
          new Part(
              a.add(new V(0, .8, 0)), b.add(new V(0, .8, 0)), .36, .35, false, Material.CONCRETE));
    } else if (raised) {
      add(out, new Part(a, b, .42, .45, false, Material.CONCRETE));
      for (double y : new double[] {.65, 1.05})
        add(
            out,
            new Part(
                a.add(new V(0, y, 0)), b.add(new V(0, y, 0)), .12, .12, false, Material.STEEL));
      if (((int) Math.floor(distance * 2)) % 4 == 0) post(out, a, b, .45, 1.15);
    } else if (highway) {
      for (double y : new double[] {.58, .8})
        add(
            out,
            new Part(a.add(new V(0, y, 0)), b.add(new V(0, y, 0)), .3, .18, false, Material.STEEL));
      if (((int) Math.floor(distance * 2)) % 4 == 0) post(out, a, b, 0, .94);
    } else {
      for (double y : new double[] {.38, .95})
        add(
            out,
            new Part(a.add(new V(0, y, 0)), b.add(new V(0, y, 0)), .1, .1, false, Material.STEEL));
      if (((int) Math.floor(distance * 2)) % 2 == 0) post(out, a, b, 0, 1.05);
    }
  }

  private record RailEndKey(long x,long y,long z,double width,double height) {
    RailEndKey(V p,Part part){this(Math.round(p.x()*100000),Math.round(p.y()*100000),Math.round(p.z()*100000),part.width(),part.height());}
  }
  private record RailEnd(V p,V into,Part part) {}
  public static void terminalPosts(List<Part> parts) {terminalPosts(parts,null);}
  public static void terminalPosts(List<Part> parts,Ground ground) {
    var ends=new LinkedHashMap<RailEndKey,List<RailEnd>>();
    var lower=new HashSet<RailEndKey>();
    for(Part p:parts)if(p.material()==Material.STEEL&&
        (p.width()==.1&&p.height()==.1||p.width()==.12&&p.height()==.12||p.width()==.3&&p.height()==.18)) {
      V dir=p.b().sub(p.a()).horizontalUnit();
      ends.computeIfAbsent(new RailEndKey(p.a(),p),k->new ArrayList<>()).add(new RailEnd(p.a(),dir,p));
      ends.computeIfAbsent(new RailEndKey(p.b(),p),k->new ArrayList<>()).add(new RailEnd(p.b(),dir.mul(-1),p));
      lower.add(new RailEndKey(p.a(),p));lower.add(new RailEndKey(p.b(),p));
    }
    var posts=new HashSet<String>();
    for(Part p:parts)if(p.material()==Material.DARK_STEEL&&p.width()>=.1&&p.width()<=.16&&p.height()<1.3)posts.add(postKey(p.a()));
    for(var group:ends.values())if(group.size()==1){
      RailEnd e=group.get(0);double separation=e.part().width()==.1?.57:e.part().width()==.12?.4:.22;
      if(!lower.contains(new RailEndKey(e.p().add(new V(0,-separation,0)),e.part())))continue;
      double top=e.part().width()==.1?1.05:e.part().width()==.12?1.15:.94;
      double railY=e.part().width()==.1?.95:e.part().width()==.12?1.05:.8;
      double bottom=e.part().width()==.12?.45:0;
      V base=e.p().add(new V(0,-railY,0));
      if(ground!=null&&!ground.railPost(base,e.part().width()==.3,e.part().width()==.12))continue;
      if(posts.add(postKey(base.add(new V(0,bottom>0?bottom-.02:bottom,0))))){
        var terminal=new ArrayList<Part>();post(terminal,base,base.add(e.into()),bottom,top);
        // Legacy junction Grounds expose a broad fixture envelope, not the
        // exact rail union. Keep their existing validated terminal placement.
        if(ground==null||!ground.unionRails()){parts.addAll(terminal);continue;}
        var p=terminal.get(0);Part fitted=null;
        for(double width:new double[]{.16,.14,.12,.1}){
          if(fitted!=null)break;
          for(double length:new double[]{.16,.08,.04}){
            // A clipped return can be narrower than the normal post footprint.
            // Its column still covers both bar ends, without extending into the
            // crossing/opening which caused that return in the first place.
            var candidate=new Part(p.a(),p.a().add(e.into().mul(length)),width,railY+e.part().height()-(bottom>0?bottom-.02:bottom),false,Material.DARK_STEEL);
            if(ground==null||!ground.railBlocked(candidate,base,base.add(e.into().mul(length)))){fitted=candidate;break;}
          }
        }
        if(fitted!=null)parts.add(fitted);
      }
    }
  }
  private static String postKey(V p){return Math.round(p.x()*100000)+":"+Math.round(p.y()*100000)+":"+Math.round(p.z()*100000);}

  private static void post(List<Part> out, V a, V b, double bottom, double top) {
    V dir = b.sub(a).horizontalUnit(), p = a.add(new V(0, bottom > 0 ? bottom - .02 : bottom, 0));
    add(
        out,
        new Part(
            p,
            p.add(dir.mul(.16)),
            .16,
            top - bottom + .03 + (bottom > 0 ? .02 : 0),
            false,
            Material.DARK_STEEL));
  }

  private static void add(List<Part> out, Part part) {
    for (int i = out.size() - 1; i >= Math.max(0, out.size() - 12); i--) {
      Part old = out.get(i);
      if (old.pier()
          || old.material() != part.material()
          || old.width() != part.width()
          || old.height() != part.height()
          || old.b().distance(part.a()) > 1e-6) continue;
      V before = old.b().sub(old.a()), after = part.b().sub(part.a());
      if (before.horizontalLength() < 1e-8 || after.horizontalLength() < 1e-8) continue;
      V da = before.horizontalUnit(), db = after.horizontalUnit();
      double dot = da.dot(db);
      if (dot > .99999999
          && old.a().distance(part.b()) <= 8
          && Math.abs(before.y() / before.horizontalLength() - after.y() / after.horizontalLength())
              < 1e-6) {
        out.set(
            i,
            new Part(
                old.a(),
                part.b(),
                old.width(),
                old.height(),
                false,
                old.material(),
                old.frameA(),
                part.frameB()));
        return;
      }
      if (dot > -.9 && old.frameB()==null && part.frameA()==null) {
        V joint = da.left().add(db.left()).mul(old.width() / 2 / (1 + dot));
        out.set(i, old.frames(old.frameA(), joint));
        part = part.frames(joint, part.frameB());
      }
      break;
    }
    out.add(part);
  }

  public static Sample sample(Mesh mesh, double distance) {
    var s = mesh.samples();
    int lo = 0, hi = s.size() - 1;
    distance = Math.max(0, Math.min(mesh.length(), distance));
    while (hi - lo > 1) {
      int mid = (lo + hi) / 2;
      if (s.get(mid).distance() < distance) lo = mid;
      else hi = mid;
    }
    Sample a = s.get(lo), b = s.get(hi);
    double span = b.distance() - a.distance();
    double t = span < 1e-9 ? 0 : (distance - a.distance()) / span;
    V direction = a.left().add(b.left().sub(a.left()).mul(t));
    if (direction.horizontalLength() < 1e-7) direction = a.left();
    return new Sample(
        a.center().add(b.center().sub(a.center()).mul(t)),
        direction.horizontalUnit(),
        distance,
        a.halfWidth() + (b.halfWidth() - a.halfWidth()) * t);
  }

  private RoadStructures() {}
}
