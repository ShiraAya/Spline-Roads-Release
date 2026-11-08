package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Authored street amenities. All positions share the same road cross section. */
public final class RoadStreetscape {
  public enum Separator { LINE("白实线"), RAIL("护栏"), GREEN("绿化带");
    public final String label; Separator(String label){this.label=label;} }
  public enum Planting { NONE("关闭"), OAK("橡木"), BIRCH("白桦"), SPRUCE("云杉"), CHERRY("樱花树"), FLOWERS("花坛");
    public final String label; Planting(String label){this.label=label;} }
  public enum Apron { ROAD("道路方块"), TERRAIN("相邻地形"), NONE("不填充"); public final String label; Apron(String label){this.label=label;} }
  public record Config(Separator separator,boolean parking,double lampSpacing,boolean walkLamps,
      double walkLampSpacing,Planting planting,double plantingSpacing,List<Span> raisedSpans,List<RoadsideBypass.Span> crossings,Apron apron,List<Span> tunnels,RoadPlantings plantings,SideStructures sides,boolean lamps) {
    public Config(Separator separator,boolean parking,double lampSpacing,boolean walkLamps,double walkLampSpacing,Planting planting,double plantingSpacing,List<Span> raisedSpans,List<RoadsideBypass.Span> crossings,Apron apron,List<Span> tunnels,RoadPlantings plantings,SideStructures sides){this(separator,parking,lampSpacing,walkLamps,walkLampSpacing,planting,plantingSpacing,raisedSpans,crossings,apron,tunnels,plantings,sides,true);}
    public Config(Separator separator,boolean parking,double lampSpacing,boolean walkLamps,double walkLampSpacing,Planting planting,double plantingSpacing,List<Span> raisedSpans,List<RoadsideBypass.Span> crossings,Apron apron,List<Span> tunnels,RoadPlantings plantings){this(separator,parking,lampSpacing,walkLamps,walkLampSpacing,planting,plantingSpacing,raisedSpans,crossings,apron,tunnels,plantings,new SideStructures(raisedSpans,raisedSpans,List.of(),List.of()));}
    public Config sideStructures(SideStructures value){return new Config(separator,parking,lampSpacing,walkLamps,walkLampSpacing,planting,plantingSpacing,union(value.negativeRaised(),value.positiveRaised()),crossings,apron,tunnels,plantings,value,lamps);}
    public Config(Separator separator,boolean parking,double lampSpacing,boolean walkLamps,double walkLampSpacing,Planting planting,double plantingSpacing){this(separator,parking,lampSpacing,walkLamps,walkLampSpacing,planting,plantingSpacing,List.of());}
    public Config(Separator separator,boolean parking,double lampSpacing,boolean walkLamps,double walkLampSpacing,Planting planting,double plantingSpacing,List<Span> raisedSpans){this(separator,parking,lampSpacing,walkLamps,walkLampSpacing,planting,plantingSpacing,raisedSpans,List.of());}
    public Config(Separator separator,boolean parking,double lampSpacing,boolean walkLamps,double walkLampSpacing,Planting planting,double plantingSpacing,List<Span> raisedSpans,List<RoadsideBypass.Span> crossings){this(separator,parking,lampSpacing,walkLamps,walkLampSpacing,planting,plantingSpacing,raisedSpans,crossings,Apron.ROAD,List.of());}
    public Config apron(Apron value){return new Config(separator,parking,lampSpacing,walkLamps,walkLampSpacing,planting,plantingSpacing,raisedSpans,crossings,value,tunnels,plantings,sides,lamps);}
    public Config tunnels(List<Span> value){return new Config(separator,parking,lampSpacing,walkLamps,walkLampSpacing,planting,plantingSpacing,raisedSpans,crossings,apron,value,plantings,sides,lamps);}
    public Config raisedSpans(List<Span> v){return new Config(separator,parking,lampSpacing,walkLamps,walkLampSpacing,planting,plantingSpacing,v,crossings,apron,tunnels,plantings,new SideStructures(v,v,List.of(),List.of()),lamps);}
    public Config crossings(List<RoadsideBypass.Span> v){return new Config(separator,parking,lampSpacing,walkLamps,walkLampSpacing,planting,plantingSpacing,raisedSpans,v,apron,tunnels,plantings,sides,lamps);}
    public Config(Separator separator,boolean parking,double lampSpacing,boolean walkLamps,double walkLampSpacing,Planting planting,double plantingSpacing,List<Span> raisedSpans,List<RoadsideBypass.Span> crossings,Apron apron,List<Span> tunnels){this(separator,parking,lampSpacing,walkLamps,walkLampSpacing,planting,plantingSpacing,raisedSpans,crossings,apron,tunnels,RoadPlantings.DEFAULT);}
    public Config plantings(RoadPlantings v){return new Config(separator,parking,lampSpacing,walkLamps,walkLampSpacing,planting,plantingSpacing,raisedSpans,crossings,apron,tunnels,v,sides,lamps);}
    public Config lamps(boolean value){return new Config(separator,parking,lampSpacing,walkLamps,walkLampSpacing,planting,plantingSpacing,raisedSpans,crossings,apron,tunnels,plantings,sides,value);}
    public static final Config DEFAULT=new Config(Separator.LINE,false,24,false,24,Planting.NONE,16);
    public Config {
      Objects.requireNonNull(sides);Objects.requireNonNull(plantings);Objects.requireNonNull(apron);tunnels=List.copyOf(tunnels);if(tunnels.size()>8192)throw new IllegalArgumentException("隧道分段过多");
      if(separator==null||planting==null||!RoadGeometry.finite(lampSpacing,walkLampSpacing,plantingSpacing)
          ||lampSpacing<8||lampSpacing>96||walkLampSpacing<8||walkLampSpacing>96||plantingSpacing<8||plantingSpacing>64)
        throw new IllegalArgumentException("路灯间距 8–96 格，绿化间距 8–64 格");
      if(parking)separator=Separator.LINE;
      crossings=List.copyOf(crossings);if(crossings.size()>8192)throw new IllegalArgumentException("非机动车道立体通行分段过多");
      raisedSpans=List.copyOf(raisedSpans);if(raisedSpans.size()>8192)throw new IllegalArgumentException("高架分段过多");
    }
    public Config separator(Separator v){return new Config(v,parking,lampSpacing,walkLamps,walkLampSpacing,planting,plantingSpacing,raisedSpans,crossings,apron,tunnels,plantings,sides,lamps);}
    public Config parking(boolean v){return new Config(separator,v,lampSpacing,walkLamps,walkLampSpacing,planting,plantingSpacing,raisedSpans,crossings,apron,tunnels,plantings,sides,lamps);}
    public Config lampSpacing(double v){return new Config(separator,parking,v,walkLamps,walkLampSpacing,planting,plantingSpacing,raisedSpans,crossings,apron,tunnels,plantings,sides,lamps);}
    public Config walkLamps(boolean v){return new Config(separator,parking,lampSpacing,v,walkLampSpacing,planting,plantingSpacing,raisedSpans,crossings,apron,tunnels,plantings,sides,lamps);}
    public Config walkLampSpacing(double v){return new Config(separator,parking,lampSpacing,walkLamps,v,planting,plantingSpacing,raisedSpans,crossings,apron,tunnels,plantings,sides,lamps);}
    public Config planting(Planting v){return new Config(separator,parking,lampSpacing,walkLamps,walkLampSpacing,v,plantingSpacing,raisedSpans,crossings,apron,tunnels,plantings,sides,lamps);}
    public Config plantingSpacing(double v){return new Config(separator,parking,lampSpacing,walkLamps,walkLampSpacing,planting,v,raisedSpans,crossings,apron,tunnels,plantings,sides,lamps);}
  }
  public record Span(double from,double to){public Span{if(!RoadGeometry.finite(from,to)||from<0||to<=from)throw new IllegalArgumentException("无效的高架范围");}}
  public record SideStructures(List<Span> negativeRaised,List<Span> positiveRaised,List<Span> negativeCut,List<Span> positiveCut){
    public SideStructures {negativeRaised=List.copyOf(negativeRaised);positiveRaised=List.copyOf(positiveRaised);negativeCut=List.copyOf(negativeCut);positiveCut=List.copyOf(positiveCut);if(negativeRaised.size()+positiveRaised.size()+negativeCut.size()+positiveCut.size()>32768)throw new IllegalArgumentException("左右结构分段过多");}
    public List<Span> raised(int side){return side<0?negativeRaised:positiveRaised;}
    public List<Span> cut(int side){return side<0?negativeCut:positiveCut;}
    public SideStructures slice(double from,double to){return new SideStructures(trim(negativeRaised,from,to),trim(positiveRaised,from,to),trim(negativeCut,from,to),trim(positiveCut,from,to));}
    private static List<Span> trim(List<Span> spans,double from,double to){return spans.stream().filter(s->s.to()>from&&s.from()<to).map(s->new Span(Math.max(from,s.from())-from,Math.min(to,s.to())-from)).toList();}
  }
  private static List<Span> union(List<Span> a,List<Span> b){var out=new ArrayList<Span>();for(var s:java.util.stream.Stream.concat(a.stream(),b.stream()).sorted(Comparator.comparingDouble(Span::from)).toList())append(out,s.from(),s.to());return List.copyOf(out);}
  private static List<Span> outsideRoofs(List<Span> spans,List<RoadAutoTunnels.Section> sections){
    var out=new ArrayList<Span>();for(var span:spans)for(double d=span.from();d<span.to()-1e-7;d+=1){final double at=d+.5;if(sections.stream().noneMatch(s->s.roof()&&at>=s.from()&&at<s.to()))append(out,d,Math.min(d+1,span.to()));}return List.copyOf(out);
  }
  private static void append(List<Span> out,double from,double to){if(!out.isEmpty()&&out.get(out.size()-1).to()>=from-1e-7){var last=out.remove(out.size()-1);out.add(new Span(last.from(),Math.max(last.to(),to)));}else out.add(new Span(from,to));}
  public static SideStructures classifySides(Mesh mesh,Ground ground){
    if(mesh.settings().structure()!=Structure.AUTO)return new SideStructures(List.of(),List.of(),List.of(),List.of());
    var sections=RoadAutoTunnels.structuralSections(ground.terrainReference(mesh),ground);
    var negative=new ArrayList<Span>();var positive=new ArrayList<Span>();
    for(var section:sections)if(!section.roof()&&!section.surface()){
      if(section.rightDepth()>=RoadAutoTunnels.minCutDepth(mesh))append(negative,section.from(),section.to());
      if(section.leftDepth()>=RoadAutoTunnels.minCutDepth(mesh))append(positive,section.from(),section.to());
    }
    return new SideStructures(outsideRoofs(classify(mesh,ground,-1),sections),outsideRoofs(classify(mesh,ground,1),sections),negative,positive);
  }
  public static boolean cut(Mesh mesh,Sample at,int side){return mesh.settings().structure()==Structure.AUTO&&mesh.settings().options().streetscape().sides().cut(side).stream().anyMatch(s->at.distance()>=s.from()&&at.distance()<s.to());}
  public static final double MAX_BRIDGE_GROUND_GAP=24;
  public static List<Span> classify(Mesh mesh,Ground ground){return union(classify(mesh,ground,-1),classify(mesh,ground,1));}
  private static List<Span> classify(Mesh mesh,Ground ground,int side){
    if(mesh.settings().structure()!=Structure.AUTO)return List.of();
    mesh=ground.terrainReference(mesh);
    var out=new ArrayList<Span>();double begin=-1;
    for(double d=0;d<mesh.length()-1e-6;d+=1){double end=Math.min(mesh.length(),d+1);var at=RoadStructures.sample(mesh,(d+end)/2);boolean raised=!underground(mesh,at.distance())&&!RoadAutoTunnels.below(mesh,at,ground,side)&&RoadStructures.elevated(mesh,at,ground,side);
      if(raised&&begin<0)begin=d;if(!raised&&begin>=0){out.add(new Span(begin,d));begin=-1;}}
    if(begin>=0)out.add(new Span(begin,mesh.length()));
    var joined=new ArrayList<Span>();
    for(var next:out){
      if(!joined.isEmpty()){
        var previous=joined.get(joined.size()-1);boolean connect=next.from()-previous.to()<=MAX_BRIDGE_GROUND_GAP+1e-7;
        for(double d=previous.to();connect&&d<next.from();d+=.5){
          var at=RoadStructures.sample(mesh,d);
          boolean levelCrossing=false;for(var crossing:mesh.settings().options().levelCrossings())if(d>=crossing.from()-1&&d<=crossing.to()+1){levelCrossing=true;break;}
          if(levelCrossing||underground(mesh,d)||RoadAutoTunnels.below(mesh,at,ground,side)||ground.joined(at.center())||ground.furnitureClear(at.center()))connect=false;
        }
        if(connect){joined.set(joined.size()-1,new Span(previous.from(),next.to()));continue;}
      }
      joined.add(next);
    }
    return List.copyOf(joined);
  }
  public static Mesh resolve(Mesh mesh,Ground ground){
    var options=mesh.settings().options();var next=options.streetscape(options.streetscape().sideStructures(classifySides(mesh,ground)).tunnels(RoadAutoTunnels.detect(mesh,ground).stream().map(s->new Span(s.from(),s.to())).toList()));
    Mesh reference=mesh.reference();
    if(reference!=null) {
      var ro=reference.settings().options();
      var rs=reference.settings().options(ro.streetscape(ro.streetscape().sideStructures(next.streetscape().sides()).tunnels(next.streetscape().tunnels())));
      reference=new Mesh(reference.samples(),rs,reference.min(),reference.max(),reference.length(),reference.closed(),reference.controlPoint(),reference.controls(),reference.reference());
    }
    return new Mesh(mesh.samples(),mesh.settings().options(next),mesh.min(),mesh.max(),mesh.length(),mesh.closed(),mesh.controlPoint(),mesh.controls(),reference);
  }
  public static boolean underground(Mesh mesh,double station){return mesh.settings().structure()==Structure.TUNNEL||mesh.settings().options().streetscape().tunnels().stream().anyMatch(s->station>=s.from()-1e-7&&station<=s.to()+1e-7);}
  public static boolean raised(Mesh mesh,Sample sample){return mesh.settings().structure()==Structure.BRIDGE||mesh.settings().structure()==Structure.AUTO&&mesh.settings().options().streetscape().raisedSpans().stream().anyMatch(s->sample.distance()>=s.from()&&sample.distance()<s.to());}
  public static boolean raised(Mesh mesh,Sample sample,int side){return !underground(mesh,sample.distance())&&(mesh.settings().structure()==Structure.BRIDGE||mesh.settings().structure()==Structure.AUTO&&mesh.settings().options().streetscape().sides().raised(side).stream().anyMatch(s->sample.distance()>=s.from()&&sample.distance()<s.to()));}
  /** Furniture/profile taper stays on supported ground; it never reclassifies
   * a floating deck as ground just to make the green-to-rail transition longer. */
  public static double raisedBlend(Mesh mesh,Sample sample){
    if(mesh.settings().structure()==Structure.BRIDGE)return 1;
    if(mesh.settings().structure()!=Structure.AUTO)return 0;
    double distance=Double.POSITIVE_INFINITY;
    for(var span:mesh.settings().options().streetscape().raisedSpans()){
      if(sample.distance()>=span.from()&&sample.distance()<=span.to())return 1;
      distance=Math.min(distance,Math.min(Math.abs(sample.distance()-span.from()),Math.abs(sample.distance()-span.to())));
    }
    return 1-Settings.smooth(Math.min(1,distance/24));
  }
  /** Conversion weight is separate from physical bridge classification. */
  public static double medianConversionBlend(Mesh mesh,Sample sample){
    // The blended catalog switches enum at the midpoint. Using that enum here
    // applies a second width conversion abruptly halfway through an exact port.
    var median=RoadProfile.catalog(mesh.settings()).median();
    return median==RoadProfile.Median.RAIL
        ||mesh.settings().options().infrastructure().medianConversion().converts(median)?raisedBlend(mesh,sample)*(1-RoadTransitions.greenPortWeight(mesh,sample)):0;
  }
  /** Approach a detected tunnel's rail median on supported ground, retaining yellow markings. */
  public static double tunnelBlend(Mesh mesh,Sample sample){
    if(mesh.settings().structure()==Structure.TUNNEL)return 1;
    if(mesh.settings().structure()!=Structure.AUTO)return 0;
    double distance=Double.POSITIVE_INFINITY;
    for(var span:mesh.settings().options().streetscape().tunnels()){
      if(sample.distance()>=span.from()&&sample.distance()<=span.to())return 1;
      distance=Math.min(distance,Math.min(Math.abs(sample.distance()-span.from()),Math.abs(sample.distance()-span.to())));
    }
    return 1-Settings.smooth(Math.min(1,distance/24));
  }
  public static boolean walkLampAllowed(RoadProfile.Options o,Structure structure,Style style){return RoadProfile.catalog(style).type()==RoadProfile.Type.ORDINARY&&RoadProfile.catalog(style).twoWay()&&structure!=Structure.BRIDGE&&structure!=Structure.TUNNEL&&o.sidewalk().enabled()&&o.cycle()&&o.streetscape().separator()!=Separator.LINE;}
  public static double parkingLength(){return 8;}
  public static double separatorWidth(RoadProfile.Options o){return o.cycle()&&!o.streetscape().parking()&&o.streetscape().separator()==Separator.GREEN?1.5:0;}
  public static boolean walkSide(RoadSidewalks.Config c,int side){return c.enabled()&&(c.side()==RoadSidewalks.Side.BOTH||c.side()==RoadSidewalks.Side.LEFT&&side<0||c.side()==RoadSidewalks.Side.RIGHT&&side>0);}
  public static RoadSidewalks.Config fitWalk(RoadSidewalks.Config walk,Config c){
    if(!walk.enabled())return walk;
    // Inner tree/lamp row, an optional separate pedestrian-lamp row, outer tactile row.
    double inner=c.planting()==Planting.NONE?1.2:c.planting()==Planting.FLOWERS?2.2:3.6;
    double minimum=inner+(c.walkLamps()?1.2:0)+(walk.tactile()?1.4:.4);
    return walk.width(Math.max(walk.width(),(int)Math.ceil(minimum)));
  }
  public static double tactileOffset(double width){return Math.max(.5,width-.8);}
  public static boolean bridge(Mesh mesh,Sample at,Ground ground,int side){return raised(mesh,at,side)||mesh.settings().structure()!=Structure.GROUND&&!underground(mesh,at.distance())&&!RoadAutoTunnels.below(mesh,at,ground,side)&&RoadStructures.elevated(mesh,at,ground,side);}
  public static boolean bridge(Mesh mesh,Sample at,Ground g){return raised(mesh,at)||mesh.settings().structure()!=Structure.GROUND&&RoadStructures.elevated(mesh,at,g);}
  public static List<Part> plan(Mesh mesh,Ground ground,RoadFurniture.Phase phase){
    // Lane connectors are a driving strip. Their host's ordinary-road origin
    // does not opt the connector into urban lamps or sidewalk planting.
    if(mesh.settings().style().ramp()||mesh.settings().options().lanePoints().link()!=null
        ||mesh.settings().structure()==Structure.TUNNEL)return List.of();
    var out=new ArrayList<Part>();var o=mesh.settings().options();var c=o.streetscape();var walk=o.sidewalk();
    if(RoadProfile.catalog(mesh.settings()).type()!=RoadProfile.Type.ORDINARY){stripTrees(out,mesh,ground,phase);return List.copyOf(out);}
    if(c.lamps())for(double d=phase.first(8,c.lampSpacing());d<mesh.length()-1e-6;d+=c.lampSpacing()){
      if(d<1||mesh.length()-d<1||RoadInfrastructure.nearGantry(mesh,ground,d))continue;
      var at=RoadStructures.sample(mesh,d);var l=RoadProfile.layout(mesh,at);boolean raised=bridge(mesh,at,ground);
      if(!l.catalog().twoWay()){
        // Preserve 0.35.3's one-way outside edge / curb and traffic-side convention.
        int side=l.outside();raised=bridge(mesh,at,ground,side);double curb=l.curbWidth()>0&&!raised?RoadProfile.curbExtent(l,at,side):0;
        boolean sound=raised&&o.outerRail().sound(side);
        double offset=sound?at.halfWidth()+.5:at.halfWidth()-(curb>0?curb/2:.28);
        addLamp(out,mesh,ground,at,side*offset,curb>0?.2:0,3,side,sound);continue;
      }
      boolean center=raised?o.cycle():l.catalog().median()==RoadProfile.Median.GREEN&&l.median()>=1.4;
      if(center&&(raised||greenSeat(mesh,d,0,2)))addLamp(out,mesh,ground,at,l.medianCenter(),raised?0:.3,1,1,false);
      for(int side:l.outsideSides()){
        boolean paved=walkSide(walk,side),cycle=o.cycle()&&l.cycleWidth()>1;
        if(bridge(mesh,at,ground,side)){
          int style=cycle?(paved?2:4):(paved?1:4);
          addLamp(out,mesh,ground,at,side*(at.halfWidth()+(paved?.65:.45)),paved?.2:0,style,side,!paved);continue;
        }
        if(cycle){
          if(RoadsideBypass.crossing(mesh,at.distance(),side))continue;
          if(c.separator()==Separator.GREEN&&greenSeat(mesh,d,side,2))addLamp(out,mesh,ground,at,l.outer(side)+side*.75,.3,2,side,false);
          else if(c.separator()==Separator.GREEN)continue;
          else if(c.separator()==Separator.RAIL)addLamp(out,mesh,ground,at,l.outer(side),0,c.walkLamps()&&paved&&walkLampAllowed(o,mesh.settings().structure(),mesh.settings().style())?3:2,side,false);
          else addLamp(out,mesh,ground,at,side*(at.halfWidth()+(paved?.65:.55)),paved?.2:0,1,side,false);
        }else if(!center)addLamp(out,mesh,ground,at,side*(at.halfWidth()+(paved?.65:.55)),paved?.2:0,paved?1:3,side,false);
      }
    }
    if(c.lamps()&&c.walkLamps()&&walkLampAllowed(o,mesh.settings().structure(),mesh.settings().style()))for(double d=phase.first(8,c.walkLampSpacing());d<mesh.length()-1;d+=c.walkLampSpacing()){
      if(d<1||RoadInfrastructure.nearGantry(mesh,ground,d))continue;
      var at=RoadStructures.sample(mesh,d);
      for(int side:new int[]{-1,1})if(walkSide(walk,side)&&!bridge(mesh,at,ground,side)&&!cut(mesh,at,side)){
        // The inner row is reserved for trees/main lamps. The separate path row stays inside tactile paving.
        double offset=Math.max(1.7,walk.width()/2.0);
        addLamp(out,mesh,ground,at,side*(at.halfWidth()+offset),.2,1,side,false);
      }
    }
    if(c.planting()!=Planting.NONE)for(double d=phase.first(8,c.plantingSpacing());d<mesh.length()-3;d+=c.plantingSpacing()){
      if(d<3||RoadInfrastructure.nearGantry(mesh,ground,d))continue;
      var at=RoadStructures.sample(mesh,d);
      for(int side:new int[]{-1,1})if(walkSide(walk,side)&&!bridge(mesh,at,ground,side)&&!cut(mesh,at,side)){
        double offset=c.planting()==Planting.FLOWERS?1.1:1.8;
        V foot=at.at(side*(at.halfWidth()+offset),-.2);
        if(ground.joined(foot)||ground.furnitureClear(foot))continue;
        var plants=plant(foot,c.planting(),at.left().left().mul(-1));
        // A main lamp wins when both schedules land in the same inner planting row.
        if(out.stream().anyMatch(p->p.a().y()<foot.y()+2&&p.a().sub(foot).horizontalLength()<2.3))continue;
        if(plants.stream().anyMatch(p->ground.plantBlocked(p,at)))continue;
        out.addAll(plants);
      }
    }
    stripTrees(out,mesh,ground,phase);
    return List.copyOf(out);
  }
  private static void stripTrees(List<Part> out,Mesh mesh,Ground ground,RoadFurniture.Phase phase){
    var c=mesh.settings().options().streetscape();var plants=c.plantings();
    for(boolean median:new boolean[]{true,false}){
      Planting type=median?plants.medianTrees():plants.separatorTrees();
      if(type==Planting.NONE)continue;
      double spacing=median?plants.medianSpacing():plants.separatorSpacing();
      for(double d=phase.first(8,spacing);d<mesh.length()-3;d+=spacing){
        if(d<3||underground(mesh,d)||RoadInfrastructure.nearGantry(mesh,ground,d))continue;
        var at=RoadStructures.sample(mesh,d);var layout=RoadProfile.layout(mesh,at);
        if(median&&medianConversionBlend(mesh,at)>.01)continue;
        double width=median?layout.median():separatorWidth(mesh.settings().options());
        if(width<1.5||median&&(!layout.catalog().twoWay()||RoadTransitions.green(mesh,at)<.99)
            ||!median&&(c.separator()!=Separator.GREEN||layout.cycleWidth()<2.5))continue;
        for(int side:median?new int[]{0}:layout.outsideSides()){
          if(!greenSeat(mesh,d,side,3.5))continue;
          if(side!=0&&(RoadsideBypass.crossing(mesh,d,side)||bridge(mesh,at,ground,side)||cut(mesh,at,side)))continue;
          V foot=at.at(median?layout.medianCenter():layout.outer(side)+side*width/2,-.3);
          if(ground.joined(foot)||ground.furnitureClear(foot)||ground.lampClear(foot))continue;
          var tree=stripPlant(foot,type,at.left().left().mul(-1),width);
          // The existing lamps and bridge columns win. Check the full tree solid,
          // and keep adjacent crowns clear of lamps already planned in this row.
          if(out.stream().anyMatch(p->p.a().y()<foot.y()+3&&p.a().sub(foot).horizontalLength()<3.2)
              ||tree.stream().anyMatch(p->ground.plantBlocked(p,at)))continue;
          out.addAll(tree);
        }
      }
    }
  }
  /** Final transaction pass: a later road may have added a pier after this
   * road's planting was planned. Remove complete deterministic planting groups. */
  public static boolean hasPlanting(Mesh mesh){
    var c=mesh.settings().options().streetscape();return c.planting()!=Planting.NONE
        ||c.plantings().medianTrees()!=Planting.NONE||c.plantings().separatorTrees()!=Planting.NONE;
  }
  public static List<Part> clearBlockedPlanting(List<Part> parts,Mesh mesh,RoadFurniture.Phase phase,
      java.util.function.Predicate<Part> blocked){
    var c=mesh.settings().options().streetscape();var walk=mesh.settings().options().sidewalk();
    if(!hasPlanting(mesh))return parts;
    var present=new HashSet<>(parts);var remove=new HashSet<Part>();
    java.util.function.Consumer<List<Part>> inspect=group->{
      if(group.stream().anyMatch(p->present.contains(p)&&blocked.test(p)))remove.addAll(group);
    };
    if(c.planting()!=Planting.NONE)for(double d=phase.first(8,c.plantingSpacing());d<mesh.length()-3;d+=c.plantingSpacing()){
      if(d<3)continue;var at=RoadStructures.sample(mesh,d);
      for(int side:new int[]{-1,1})if(walkSide(walk,side)){
        V foot=at.at(side*(at.halfWidth()+(c.planting()==Planting.FLOWERS?1.1:1.8)),-.2);
        inspect.accept(plant(foot,c.planting(),at.left().left().mul(-1)));
      }
    }
    for(boolean median:new boolean[]{true,false}){
      var type=median?c.plantings().medianTrees():c.plantings().separatorTrees();if(type==Planting.NONE)continue;
      double spacing=median?c.plantings().medianSpacing():c.plantings().separatorSpacing();
      for(double d=phase.first(8,spacing);d<mesh.length()-3;d+=spacing){
        if(d<3)continue;var at=RoadStructures.sample(mesh,d);var layout=RoadProfile.layout(mesh,at);
        double width=median?layout.median():separatorWidth(mesh.settings().options());if(width<1.5)continue;
        for(int side:median?new int[]{0}:layout.outsideSides())inspect.accept(stripPlant(
            at.at(median?layout.medianCenter():layout.outer(side)+side*width/2,-.3),type,at.left().left().mul(-1),width));
      }
    }
    return remove.isEmpty()?parts:parts.stream().filter(p->!remove.contains(p)).toList();
  }
  /** Entire planting seat must survive the green taper and local ground/bridge transition. */
  public static boolean greenSeat(Mesh mesh,double distance,int side,double setback){
    if(distance<setback||distance>mesh.length()-setback)return false;
    for(double d=distance-setback;d<=distance+setback+1e-7;d+=.5){
      var at=RoadStructures.sample(mesh,d);var l=RoadProfile.layout(mesh,at);
      if(underground(mesh,d)||(side==0?medianConversionBlend(mesh,at):raisedBlend(mesh,at))>.01)return false;
      if(side==0){if(l.median()<1.4||RoadTransitions.green(mesh,at)<.99)return false;}
      else if(mesh.settings().options().streetscape().separator()!=Separator.GREEN||l.cycleWidth()<2.8||separatorWidth(mesh.settings().options())<.45||cut(mesh,at,side)||RoadsideBypass.crossing(mesh,d,side))return false;
    }return true;
  }
  public static int treeVariant(V foot,Planting type){
    long x=Math.round(foot.x()*16),z=Math.round(foot.z()*16);
    long h=x*0x9E3779B97F4A7C15L^z*0xC2B2AE3D27D4EB4FL^type.ordinal()*0x165667B19E3779F9L;
    h=(h^(h>>>30))*0xBF58476D1CE4E5B9L;h=(h^(h>>>27))*0x94D049BB133111EBL;
    return (int)Math.floorMod(h^(h>>>31),6);
  }
  public static List<Part> stripPlant(V foot,Planting type,V along,double width){
    if(width<1.5||type==Planting.NONE||type==Planting.FLOWERS)return List.of();
    // Keep the narrow trunk in the strip, but never squeeze the whole crown.
    // Branches and foliage begin above motor-vehicle clearance and still pass
    // the full plantBlocked check against other decks, lamps and bridge piers.
    return RoadTrees.model(foot,type,along);
  }
  private static void addLamp(List<Part> out,Mesh mesh,Ground g,Sample at,double lateral,double up,int style,int side,boolean bracket){
    // An outer closure removes the supporting shoulder too. Keep median lamps,
    // but do not leave an exterior lamp/bracket suspended beside that opening.
    if(LaneDeck.outerOpening(mesh,at.distance(),side)&&Math.abs(lateral)>at.halfWidth()-1)return;
    V foot=at.at(lateral,-up);if(g.joined(foot)||g.furnitureClear(foot)||g.lampClear(foot))return;
    var parts=new ArrayList<>(lamp(foot,at.left().mul(-side),style));
    if(bracket){
      // Recess the cross-deck support below the paint; only the outboard riser reaches the pedestal.
      parts.add(new Part(at.at(side*(at.halfWidth()-.55),.47),foot.add(new V(0,-.47,0)),.7,.35,false,Material.CONCRETE));
      parts.add(new Part(foot.add(new V(0,-.12,0)),foot.add(new V(0,-.12,0)),.38,.12,true,Material.CONCRETE));
    }
    if(parts.stream().noneMatch(p->g.lampBlocked(p,at)))out.addAll(parts);
  }
  /** The complete paved ribbon includes legitimate lamp seats (median, separator
   * and kerb). Permit only the generated lamp's own transverse mounting section.
   * Its members extend at most .5 m along the road; the one-metre station window
   * includes tessellation tolerance. Every other pass/layer of this same road is
   * still checked, including arms that meet an upper deck. Open paths never wrap. */
  public static boolean lampOwnRoadBlocked(Part part,Mesh mesh,Sample mount){
    for(var contact:RoadClearance.structureContacts(part,mesh,4.25)){
      if(!contact.blocked())continue;
      if(contact.from()<mount.distance()-1-1e-7||contact.to()>mount.distance()+1+1e-7)return true;
    }
    return false;
  }
  public static List<Part> lamp(V foot,V inward,int style){
    var out=new ArrayList<Part>();double h=style==4?6:7.5;
    out.add(new Part(foot,foot,.38,.16,true,Material.CONCRETE));
    out.add(new Part(foot.add(new V(0,.16,0)),foot.add(new V(0,.16,0)),.22,1.8,true,Material.LAMP_BLUE));
    out.add(new Part(foot.add(new V(0,1.96,0)),foot.add(new V(0,1.96,0)),.14,h-1.96,true,Material.STEEL));
    arm(out,foot,inward,h,style==4?1.45:2.5,style==4?.15:.65);
    if(style==1||style==2)arm(out,foot,inward.mul(-1),style==2?h-1.7:h,style==2?1.7:2.5,.65);
    if(style==1)out.add(new Part(foot.add(new V(0,h,0)),foot.add(new V(0,h,0)),.09,.65,true,Material.STEEL));
    if(style==3){V a=foot.add(new V(0,h-1.4,0)),b=foot.add(new V(0,h-.2,0));V last=a;
      for(int i=1;i<=8;i++){double t=i/8.;V next=a.add(b.sub(a).mul(t)).add(inward.mul(-.42*Math.sin(Math.PI*t)));out.add(new Part(last,next,.07,.07,false,Material.STEEL));last=next;}}
    String anchor="sr:lamp/"+Double.toHexString(foot.x())+"/"+Double.toHexString(foot.y())+"/"+Double.toHexString(foot.z());
    return out.stream().map(p->new Part(p.a(),p.b(),p.width(),p.height(),p.pier(),p.material(),p.frameA(),p.frameB(),anchor,p.heights())).toList();
  }
  public static V lampFoot(Part part){
    if(!part.model().startsWith("sr:lamp/"))return null;
    var values=part.model().substring(8).split("/");if(values.length!=3)return null;
    try{return new V(Double.parseDouble(values[0]),Double.parseDouble(values[1]),Double.parseDouble(values[2]));}catch(NumberFormatException invalid){return null;}
  }
  private static void arm(List<Part> out,V foot,V direction,double h,double length,double rise){
    V root=foot.add(new V(0,h-.2,0)),last=root;
    for(int i=1;i<=8;i++){double t=i/8.;V p=root.add(direction.mul(length*t)).add(new V(0,rise*(2*t-t*t),0));out.add(new Part(last,p,.10,.10,false,Material.STEEL));last=p;}
    out.add(new Part(last.sub(direction.mul(.3)),last.add(direction.mul(.55)),.36,.16,false,Material.LAMP_BLUE));
    out.add(new Part(last.sub(direction.mul(.24)).add(new V(0,-.035,0)),last.add(direction.mul(.45)).add(new V(0,-.035,0)),.28,.05,false,Material.LAMP));
  }
  /** Bounded procedural models: tree pit and grate, branching trunks and species crowns. */
  public static List<Part> plant(V foot,Planting type,V along){
    var out=new ArrayList<Part>();if(type==Planting.NONE)return List.of();
    along=along.horizontalUnit();V side=along.left();
    if(type==Planting.FLOWERS){
      // Recessed soil surrounded by a real continuous rim, with planted clusters and petals.
      box(out,foot.add(new V(0,.025,0)),along,3.5,1.65,.16,Material.SOIL);
      for(int sign:new int[]{-1,1}){
        box(out,foot.add(side.mul(sign*.84)),along,3.8,.16,.32,Material.CONCRETE);
        box(out,foot.add(along.mul(sign*1.84)),side,1.65,.16,.32,Material.CONCRETE);
      }
      for(int row=-1;row<=1;row++)for(int i=-3;i<=3;i++){
        double h=.3+.05*Math.sin(i*2.3+row);V at=foot.add(along.mul(i*.48+row*.05)).add(side.mul(row*.45)).add(new V(0,.16,0));
        box(out,at,along,.43,.36,h,Material.OAK_LEAVES);
        V bloom=at.add(new V(0,h+.02,0));Material petal=((i-row)%3==0)?Material.FLOWER_WHITE:((i+row)%2==0)?Material.FLOWER_PINK:Material.FLOWER_YELLOW;
        box(out,bloom,along,.28,.11,.055,petal);box(out,bloom,side,.28,.11,.055,petal);
        box(out,bloom.add(new V(0,.045,0)),along,.07,.07,.04,Material.FLOWER_YELLOW);
      }return List.copyOf(out);
    }
    // Soil sits just above the paving and the inset grate leaves room for trunk growth.
    box(out,foot.add(new V(0,.008,0)),along,2.25,2.25,.035,Material.SOIL);
    for(int sign:new int[]{-1,1}){
      box(out,foot.add(side.mul(sign*1.16)),along,2.48,.16,.12,Material.CONCRETE);
      box(out,foot.add(along.mul(sign*1.16)),side,2.2,.16,.12,Material.CONCRETE);
    }
    for(int i=-5;i<=5;i++){
      double offset=i*.19;double gap=Math.abs(offset)<.37?.42:0;
      for(int sign:new int[]{-1,1}){double length=1.08-gap;if(length<=0)continue;
        V a=foot.add(side.mul(offset)).add(along.mul(sign*(gap+length/2))).add(new V(0,.055,0));
        box(out,a,along,length,.035,.035,Material.DARK_STEEL);
      }
    }
    out.addAll(RoadTrees.model(foot,type,along));
    return List.copyOf(out);
  }
  private static void box(List<Part> out,V center,V along,double length,double width,double height,Material material){
    out.add(new Part(center.sub(along.mul(length/2)),center.add(along.mul(length/2)),width,height,false,material));
  }
  private RoadStreetscape(){}
}
