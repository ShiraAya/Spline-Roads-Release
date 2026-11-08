package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Bridge superstructures, enclosed tunnels and direction-aware overhead equipment. */
public final class RoadInfrastructure {
  public enum Bridge {
    STANDARD("普通高架",24), BEAM("小河梁桥",32), OVERPASS("跨线桥",64),
    ARCH("大河拱桥",96), CABLE("斜拉桥",192), SUSPENSION("悬索桥",256), VIADUCT("跨海连续梁",64);
    public final String label; public final double span;
    Bridge(String label,double span){this.label=label;this.span=span;}
  }
  /** User-selected conversion on elevated road sections only. */
  public enum MedianConversion {
    OFF("关"), YELLOW("仅双黄线"), GREEN("仅绿化带"), BOTH("开（两者都）");
    public final String label;
    MedianConversion(String label){this.label=label;}
    public boolean converts(RoadProfile.Median median){return median==RoadProfile.Median.DOUBLE_YELLOW&&(this==YELLOW||this==BOTH)
        ||median==RoadProfile.Median.GREEN&&(this==GREEN||this==BOTH);}
  }
  public enum Tunnel {
    BOX("箱形地下通道"), ARCH("拱顶隧道"), ROUND("圆形铁路隧道");
    public final String label; Tunnel(String label){this.label=label;}
  }
  public enum Gantry {
    AUTO("自动龙门架"), OFF("关闭"), FRAME("设备横梁"), SIGNS("普通龙门架");
    public final String label; Gantry(String label){this.label=label;}
  }
  public record Config(Bridge bridge,double span,Tunnel tunnel,double headroom,Gantry gantry,double spacing,
      double tunnelDepth,List<RoadGantry.Edit> gantryEdits,RoadTunnelFit.Adjustment adjustment,boolean efficientDip,double bridgeRise,double maxGrade,boolean autoSpan,boolean fixedStations,MedianConversion medianConversion) {
    public Config(Bridge bridge,double span,Tunnel tunnel,double headroom,Gantry gantry,double spacing,double depth,List<RoadGantry.Edit> edits,RoadTunnelFit.Adjustment adjustment,boolean efficientDip,double rise,double maxGrade,boolean autoSpan){this(bridge,span,tunnel,headroom,gantry,spacing,depth,edits,adjustment,efficientDip,rise,maxGrade,autoSpan,false,MedianConversion.OFF);}
    public static final Config DEFAULT=new Config(Bridge.STANDARD,24,Tunnel.BOX,6,Gantry.OFF,128,0,List.of(),RoadTunnelFit.Adjustment.OFF,true,0,.06,true);
    public Config(Bridge bridge,double span,Tunnel tunnel,double headroom,Gantry gantry,double spacing){
      this(bridge,span,tunnel,headroom,gantry,spacing,0,List.of());
    }
    public Config(Bridge bridge,double span,Tunnel tunnel,double headroom,Gantry gantry,double spacing,double depth,List<RoadGantry.Edit> edits){
      this(bridge,span,tunnel,headroom,gantry,spacing,depth,edits,RoadTunnelFit.Adjustment.OFF,true);
    }
    /** Legacy constructor retains the old geometry and grading policy. */
    public Config(Bridge bridge,double span,Tunnel tunnel,double headroom,Gantry gantry,double spacing,
        double depth,List<RoadGantry.Edit> edits,RoadTunnelFit.Adjustment adjustment,boolean efficientDip){
      this(bridge,span,tunnel,headroom,gantry,spacing,depth,edits,adjustment,efficientDip,0,0,false);
    }
    public Config {
      if(medianConversion==null||bridge==null||tunnel==null||gantry==null||adjustment==null||!RoadGeometry.finite(span,headroom,spacing,tunnelDepth,bridgeRise,maxGrade)
          ||span<12||span>2048||headroom<4||headroom>12||spacing<32||spacing>512||tunnelDepth<0||tunnelDepth>96||bridgeRise<0||bridgeRise>96||maxGrade<0||maxGrade>.2)
        throw new IllegalArgumentException("桥跨 12–2048；净高 4–12；间距 32–512；下潜 / 抬升 0–96 格；坡度不超过 20%");
      gantryEdits=List.copyOf(gantryEdits);
      if(gantryEdits.size()>RoadGantry.MAX_SLOTS||gantryEdits.stream().map(RoadGantry.Edit::slot).distinct().count()!=gantryEdits.size())
        throw new IllegalArgumentException("龙门架编辑记录重复或过多");
    }
    public Config bridge(Bridge v){return new Config(v,v.span,tunnel,headroom,gantry,spacing,tunnelDepth,gantryEdits,adjustment,efficientDip,bridgeRise,maxGrade,autoSpan,fixedStations,medianConversion);}
    public Config span(double v){return new Config(bridge,v,tunnel,headroom,gantry,spacing,tunnelDepth,gantryEdits,adjustment,efficientDip,bridgeRise,maxGrade,autoSpan,fixedStations,medianConversion);}
    public Config tunnel(Tunnel v){return new Config(bridge,span,v,headroom,gantry,spacing,tunnelDepth,gantryEdits,adjustment,efficientDip,bridgeRise,maxGrade,autoSpan,fixedStations,medianConversion);}
    public Config headroom(double v){return new Config(bridge,span,tunnel,v,gantry,spacing,tunnelDepth,gantryEdits,adjustment,efficientDip,bridgeRise,maxGrade,autoSpan,fixedStations,medianConversion);}
    public Config gantry(Gantry v){return new Config(bridge,span,tunnel,headroom,v,spacing,tunnelDepth,gantryEdits,adjustment,efficientDip,bridgeRise,maxGrade,autoSpan,fixedStations,medianConversion);}
    public Config spacing(double v){return new Config(bridge,span,tunnel,headroom,gantry,v,tunnelDepth,gantryEdits,adjustment,efficientDip,bridgeRise,maxGrade,autoSpan,fixedStations,medianConversion);}
    public Config gantryEdits(List<RoadGantry.Edit> values){return new Config(bridge,span,tunnel,headroom,gantry,spacing,tunnelDepth,values,adjustment,efficientDip,bridgeRise,maxGrade,autoSpan,fixedStations,medianConversion);}
    public Config depth(double v){return new Config(bridge,span,tunnel,headroom,gantry,spacing,v,gantryEdits,adjustment,true,bridgeRise,maxGrade,autoSpan,fixedStations,medianConversion);}
    public Config adjustment(RoadTunnelFit.Adjustment v){return new Config(bridge,span,tunnel,headroom,gantry,spacing,tunnelDepth,gantryEdits,v,efficientDip,bridgeRise,maxGrade,autoSpan,fixedStations,medianConversion);}
    public Config profile(boolean v){return new Config(bridge,span,tunnel,headroom,gantry,spacing,tunnelDepth,gantryEdits,adjustment,v,bridgeRise,maxGrade,autoSpan,fixedStations,medianConversion);}
    public Config clearEdits(){return new Config(bridge,span,tunnel,headroom,gantry,spacing,tunnelDepth,List.of(),adjustment,efficientDip,bridgeRise,maxGrade,autoSpan,false,medianConversion);}
    public Config edit(RoadGantry.Edit value){
      var edits=new ArrayList<>(gantryEdits);edits.removeIf(e->e.slot()==value.slot());edits.add(value);
      edits.sort(Comparator.comparingInt(RoadGantry.Edit::slot));
      return new Config(bridge,span,tunnel,headroom,gantry,spacing,tunnelDepth,edits,adjustment,efficientDip,bridgeRise,maxGrade,autoSpan,fixedStations,medianConversion);
    }
    public Config reset(int slot){
      var prior=gantryEdits.stream().filter(e->e.slot()==slot).findFirst().orElse(null);
      if(fixedStations&&prior!=null&&!prior.independent())return edit(prior.parameters(0,5.6,Gantry.AUTO,false));
      return new Config(bridge,span,tunnel,headroom,gantry,spacing,tunnelDepth,gantryEdits.stream().filter(e->e.slot()!=slot).toList(),adjustment,efficientDip,bridgeRise,maxGrade,autoSpan,fixedStations,medianConversion);
    }
    public Config fixedStations(List<RoadGantry.Edit> values){return new Config(bridge,span,tunnel,headroom,gantry,spacing,tunnelDepth,values,adjustment,efficientDip,bridgeRise,maxGrade,autoSpan,true,medianConversion);}
    public Config rise(double v){return new Config(bridge,span,tunnel,headroom,gantry,spacing,tunnelDepth,gantryEdits,adjustment,efficientDip,v,maxGrade,autoSpan,fixedStations,medianConversion);}
    public Config grade(double v){return new Config(bridge,span,tunnel,headroom,gantry,spacing,tunnelDepth,gantryEdits,adjustment,efficientDip,bridgeRise,v,autoSpan,fixedStations,medianConversion);}
    public Config autoSpan(boolean v){return new Config(bridge,span,tunnel,headroom,gantry,spacing,tunnelDepth,gantryEdits,adjustment,efficientDip,bridgeRise,maxGrade,v,fixedStations,medianConversion);}

    public Config medianConversion(MedianConversion v){return new Config(bridge,span,tunnel,headroom,gantry,spacing,tunnelDepth,gantryEdits,adjustment,efficientDip,bridgeRise,maxGrade,autoSpan,fixedStations,v);}

  }
  public static boolean customBridge(Mesh mesh){return mesh.settings().structure()==Structure.BRIDGE&&mesh.settings().options().infrastructure().bridge()!=Bridge.STANDARD;}
  /** Immutable superstructure is visible before a batch reserves gantries.
   * Searchable bridge piers are deliberately left to the normal support pass. */
  public static List<Part> fixedBridgeParts(Mesh mesh,Ground ground){
    if(!customBridge(mesh)||ground.sharedDeck())return List.of();
    var parts=new ArrayList<Part>();bridge(mesh,mesh.settings().options().infrastructure(),ground,parts,false);
    validateOwnClearance(mesh,parts);return checked(parts);
  }
  public static double tunnelRise(Config c,double halfWidth){return c.tunnel()==Tunnel.ROUND?RailbedTunnel.top(c,halfWidth)-c.headroom():c.tunnel()==Tunnel.ARCH?Math.min(4,halfWidth*.38):0;}
  /** Highest interior ceiling; used by excavation, including old saved clearance=4 records. */
  public static double clearance(Settings s){var c=s.options().infrastructure();return s.structure()==Structure.TUNNEL?c.headroom()+tunnelRise(c,s.width()/2)+1.25:0;}
  public static List<Part> plan(Mesh mesh,Ground ground) {return plan(mesh,ground,List.of());}
  public static List<Part> plan(Mesh mesh,Ground ground,Collection<Part> occupied) {
    var out=new ArrayList<Part>(); var c=mesh.settings().options().infrastructure();
    if(mesh.settings().structure()==Structure.TUNNEL){tunnel(mesh,c,out);out.addAll(RailbedTunnel.portals(mesh,List.of(new RoadAutoTunnels.Region(0,mesh.length())),ground));RoadStructures.medianFurniture(mesh,ground,out);RoadStructures.cycleFurniture(mesh,ground,out);return checked(out);}
    if(customBridge(mesh)&&!ground.sharedDeck()){bridge(mesh,c,ground,out);validateOwnClearance(mesh,out);
      for(var part:out)if(ground.blocked(part))throw new IllegalArgumentException("桥梁构件安装空间冲突："+ground.blockedReason(part)+"；请调整桥面高度或桥位");
    }
    var fixtures=new ArrayList<Part>(occupied);fixtures.addAll(out);
    out.addAll(RoadGantry.plan(mesh,ground,fixtures));
    return checked(out);
  }
  private static List<Part> checked(List<Part> parts){
    if(parts.size()>48000)throw new IllegalArgumentException("本段结构过多，请用中间端点拆分桥梁或隧道");
    return List.copyOf(parts);
  }
  private static V at(Sample s,double lateral,double up){return s.at(lateral,-up);}
  private static Sample sample(Mesh m,double d){return RoadStructures.sample(m,Math.max(0,Math.min(m.length(),d)));}
  private static void beam(List<Part> out,V a,V b,double width,double height,Material mat){
    if(a.sub(b).horizontalLength()<1e-6){post(out,a,width,Math.max(height,b.y()-a.y()+height),mat);return;}
    out.add(new Part(a,b,width,height,false,mat));
  }
  private static void post(List<Part> out,V a,double width,double height,Material mat){
    for(double offset=0;offset<height-.001;offset+=RoadStructures.MAX_DROP){
      V base=a.add(new V(0,offset,0));out.add(new Part(base,base,width,Math.min(RoadStructures.MAX_DROP,height-offset),true,mat));
    }
  }
  private static void longitudinal(List<Part> out,Sample a,Sample b,double offsetA,double offsetB,double y,double width,double height,Material mat){
    out.add(new Part(at(a,offsetA,y),at(b,offsetB,y),width,height,false,mat).frames(a.left().mul(width/2),b.left().mul(width/2)));
  }
  /** Pure persisted tunnel geometry, also used for exact ownership at a branch. */
  public static List<Part> tunnelShell(Mesh mesh){var out=new ArrayList<Part>();tunnel(mesh,mesh.settings().options().infrastructure(),out);return List.copyOf(out);}
  private static void tunnel(Mesh mesh,Config c,List<Part> out){
    if(mesh.settings().style()==Style.RAILBED||c.tunnel()==Tunnel.ROUND){out.addAll(RailbedTunnel.shell(mesh));return;}
    // Road edge is the inside face of the lining. No unexcavated gap behind the shoulder.
    for(double d=0;d<mesh.length()-1e-6;d+=2){
      Sample a=sample(mesh,d),b=sample(mesh,d+2);
      for(int side:new int[]{-1,1}){
        longitudinal(out,a,b,side*(a.halfWidth()+.75),side*(b.halfWidth()+.75),-mesh.settings().thickness(),1.6,
            Math.max(wallTop(c,a.halfWidth()),wallTop(c,b.halfWidth()))+mesh.settings().thickness(),Material.TUNNEL);
        longitudinal(out,a,b,side*(a.halfWidth()-.15),side*(b.halfWidth()-.15),.8,.12,.18,Material.SIGN_WHITE);
      }
      int bands=roofBands(a.halfWidth(),b.halfWidth());
      for(int i=0;i<bands;i++){
        double u=-1+2.0*i/bands,v=-1+2.0*(i+1)/bands;
        double wa=a.halfWidth()+1.55,wb=b.halfWidth()+1.55;
        double ya=roof(c,a.halfWidth(),u),za=roof(c,a.halfWidth(),v);
        double yb=roof(c,b.halfWidth(),u),zb=roof(c,b.halfWidth(),v);
        out.add(new Part(at(a,wa*(u+v)/2,(ya+za)/2),at(b,wb*(u+v)/2,(yb+zb)/2),
            Math.max(wa,wb)*(v-u),.85,false,Material.TUNNEL)
            .frames(a.left().mul(wa*(v-u)/2).add(new V(0,(za-ya)/2,0)),
                    b.left().mul(wb*(v-u)/2).add(new V(0,(zb-yb)/2,0))));
      }
      // Ceiling lights follow the actual faceted lining, including the arched roof.
      int rows=Math.max(1,(int)Math.ceil(mesh.settings().width()/4));
      for(int row=0;row<rows;row++){
        double u=-1+(row+.5)*2/rows,oa=a.halfWidth()*u,ob=b.halfWidth()*u;
        double ya=ceiling(c,a.halfWidth(),oa),yb=ceiling(c,b.halfWidth(),ob);
        double tiltA=(ceiling(c,a.halfWidth(),oa+.11)-ceiling(c,a.halfWidth(),oa-.11))/2;
        double tiltB=(ceiling(c,b.halfWidth(),ob+.11)-ceiling(c,b.halfWidth(),ob-.11))/2;
        out.add(new Part(at(a,oa,ya-.22),at(b,ob,yb-.22),.22,.20,false,Material.LAMP,
            a.left().mul(.11).add(new V(0,tiltA,0)),b.left().mul(.11).add(new V(0,tiltB,0)),"sr:tunnel_ceiling_light"));
      }
    }
    // End collars surround the opening; never put a solid cap across the travel lanes.
    for(double d:new double[]{.6,Math.max(.6,mesh.length()-.6)}){
      Sample s=sample(mesh,d);double w=s.halfWidth()+.65;
      for(int side:new int[]{-1,1})post(out,at(s,side*w,-mesh.settings().thickness()),1.3,wallTop(c,s.halfWidth())+mesh.settings().thickness(),Material.TUNNEL);
      int bands=16;
      for(int i=0;i<bands;i++){
        double u=-1+2.0*i/bands,v=-1+2.0*(i+1)/bands;
        beam(out,at(s,w*u,roof(c,s.halfWidth(),u)),at(s,w*v,roof(c,s.halfWidth(),v)),1.3,1.1,Material.TUNNEL);
      }
    }
    for(double d=3;d<mesh.length()-1;d+=8){
      Sample a=sample(mesh,d),b=sample(mesh,d+1.2);
      for(int side:new int[]{-1,1})longitudinal(out,a,b,side*(a.halfWidth()-.2),side*(b.halfWidth()-.2),c.headroom()-.5,.3,.25,Material.LAMP);
    }
  }
  /** The arch spans beyond the pavement, so its height at the INNER wall is above
   * headroom. Stop at that real roof band, not at the outer spring line. */
  private static double wallTop(Config c,double half){return ceiling(c,half,Math.max(0,half-.05))+.03;}

  /** Maximum of the actual faceted roof above this cell's interior footprint.
   * Shell volume is handled separately; no centre-height padding or extrapolation
   * outside a portal may turn exterior terrain into a reserved air cell. */
  public static double excavationTop(Mesh mesh,int x,int z,double deckTop){
    double top=RoadTunnelSpace.ceiling(mesh,x,z);
    // A rounded raster boundary without actual interior needs no air reservation.
    return Double.isFinite(top)?top:deckTop;
  }
  static int roofBands(double halfA,double halfB){return Math.max(16,(int)Math.ceil(Math.max(halfA,halfB)/3));}
  static double roofHeight(Config c,double half,double u){return roof(c,half,u);}

  /** Height of the actual planar roof band at a lateral position. */
  public static double ceiling(Config c,double half,double lateral){
    if(c.tunnel()==Tunnel.ROUND)return RailbedTunnel.ceiling(c,half,lateral);
    int bands=Math.max(16,(int)Math.ceil(half/3));
    double t=Math.max(0,Math.min(bands-1e-9,(lateral/(half+1.55)+1)*bands/2));
    int i=(int)t;double u=-1+2.0*i/bands,v=-1+2.0*(i+1)/bands;
    return roof(c,half,u)*(1-(t-i))+roof(c,half,v)*(t-i);
  }
  private static double roof(Config c,double half,double u){if(c.tunnel()==Tunnel.ROUND)return RailbedTunnel.ceiling(c,half,u*(half+1.55));return c.headroom()+tunnelRise(c,half)*Math.sqrt(Math.max(0,1-u*u));}
  public static double endTaper(Mesh mesh,Sample s){
    double t=Math.max(0,Math.min(1,Math.min(s.distance(),mesh.length()-s.distance())/Math.min(12,mesh.length()*.12)));
    return t*t*(3-2*t);
  }
  public static double girderDepth(Mesh mesh){
    if(!customBridge(mesh))return 0;
    var bridge=mesh.settings().options().infrastructure().bridge();return bridge==Bridge.BEAM?.7:bridge==Bridge.OVERPASS?.9:bridge==Bridge.CABLE||bridge==Bridge.SUSPENSION?Math.max(1.2,towerWidth(towerHeight(cableSpan(mesh),bridge))*.45):1.2;
  }
  public static double edgeReach(Mesh mesh,Sample sample){
    if(!customBridge(mesh))return 0;
    var bridge=mesh.settings().options().infrastructure().bridge();
    return (bridge==Bridge.CABLE||bridge==Bridge.SUSPENSION?Math.max(2.1,2*towerRadius(sample,towerWidth(towerHeight(cableSpan(mesh),bridge)))+.95):bridge==Bridge.ARCH?1.25:.35)*endTaper(mesh,sample);
  }
  private static void bridge(Mesh mesh,Config c,Ground ground,List<Part> out){
    bridge(mesh,c,ground,out,true);
  }
  private static void bridge(Mesh mesh,Config c,Ground ground,List<Part> out,boolean includeSupports){
    double depth=girderDepth(mesh);
    // Two longitudinal box girders remain below the driving slab, following curve and grade.
    var stations=new TreeSet<Double>();for(double d=0;d<mesh.length();d+=2)stations.add(d);stations.add(mesh.length());
    for(var cut:mesh.settings().options().lanePoints().cuts())for(double d:new double[]{cut.begin(),cut.end()})for(double e:new double[]{-1e-5,0,1e-5})if(d+e>0&&d+e<mesh.length())stations.add(d+e);
    var distances=new ArrayList<>(stations);
    for(int index=1;index<distances.size();index++){
      Sample a=sample(mesh,distances.get(index-1)),b=sample(mesh,distances.get(index));double middle=(a.distance()+b.distance())/2;
      var bearing=mesh;
      // A temporarily removed lane has no box girder. Its small closure-rail
      // footing belongs to the remaining slab, not to an invisible beam over the ramp.
      for(var cut:mesh.settings().options().lanePoints().cuts())if(cut.temporary()&&cut.removed(middle)>.999)bearing=LaneDeck.excludingSlot(bearing,cut.lane());
      for(int side:new int[]{-1,1}){
        girder(out,bearing,a,b,side*a.halfWidth()*.62,side*b.halfWidth()*.62,
            -mesh.settings().thickness()-depth,Math.min(2.4,mesh.settings().width()/5),depth);
        if(c.bridge()==Bridge.BEAM||c.bridge()==Bridge.OVERPASS)continue;
        if(LaneDeck.outerOpening(mesh,middle,side))continue;
        // A continuous edge slab attaches outboard ribs, hangers and cable anchors to the deck.
        double ra=edgeReach(mesh,a),rb=edgeReach(mesh,b);
        int strips=Math.max(1,(int)Math.ceil((Math.max(ra,rb)+.05)/7.5));
        for(int strip=0;strip<strips;strip++){
          double wa=(ra+.05)/strips,wb=(rb+.05)/strips;
          out.add(new Part(at(a,side*(a.halfWidth()-.05+wa*(strip+.5)),-mesh.settings().thickness()),
              at(b,side*(b.halfWidth()-.05+wb*(strip+.5)),-mesh.settings().thickness()),Math.max(wa,wb),
              mesh.settings().thickness(),false,Material.CONCRETE).frames(a.left().mul(wa/2),b.left().mul(wb/2)));
        }
      }
    }
    // These two bridge types have the same non-bearing fascia as AUTO decks.
    // Clip it at joined paved edges; the full slab and box girders remain checked.
    if(c.bridge()==Bridge.BEAM||c.bridge()==Bridge.OVERPASS)out.addAll(RoadStructures.edgeSlabs(mesh,ground));
    int spans=c.autoSpan()&&(c.bridge()==Bridge.CABLE||c.bridge()==Bridge.SUSPENSION)?1:Math.max(1,(int)Math.ceil(mesh.length()/c.span()-1e-3));double span=mesh.length()/spans;
    if(includeSupports)for(int i=0;i<=spans;i++)support(mesh,Math.min(mesh.length()-1,Math.max(1,i*span)),depth,c.bridge(),ground,out);
    for(int i=0;i<spans;i++){
      double start=i*span;
      if(c.bridge()==Bridge.ARCH)arch(mesh,start,span,out);
      if(c.bridge()==Bridge.CABLE||c.bridge()==Bridge.SUSPENSION)cables(mesh,start,span,depth,c.bridge(),ground,out,includeSupports);
    }
  }
  private static void girder(List<Part> out,Mesh bearing,Sample a,Sample b,double offsetA,double offsetB,double y,double width,double height){
    for(var strip:LaneDeck.strips(bearing,a,b)){
      double loA=Math.max(offsetA-width/2,strip.ar().sub(a.center()).dot(a.left())),hiA=Math.min(offsetA+width/2,strip.al().sub(a.center()).dot(a.left()));
      double loB=Math.max(offsetB-width/2,strip.br().sub(b.center()).dot(b.left())),hiB=Math.min(offsetB+width/2,strip.bl().sub(b.center()).dot(b.left()));
      if(hiA-loA<1e-6||hiB-loB<1e-6)continue;
      out.add(new Part(at(a,(loA+hiA)/2,y),at(b,(loB+hiB)/2,y),Math.max(hiA-loA,hiB-loB),height,false,Material.CONCRETE)
          .frames(a.left().mul((hiA-loA)/2),b.left().mul((hiB-loB)/2)));
    }
  }
  private static void support(Mesh mesh,double station,double depth,Bridge style,Ground ground,List<Part> out){
    boolean portal=style==Bridge.OVERPASS;double shaftWidth=portal?1.8:style==Bridge.CABLE||style==Bridge.SUSPENSION?Math.max(2.2,towerWidth(towerHeight(cableSpan(mesh),style))):2.2;
    var at=sample(mesh,station);var shared=ground.sharedSupports(mesh,at,depth,shaftWidth,portal?at.halfWidth()+1.4:at.halfWidth()*.62);
    if(shared!=null){out.addAll(shared);return;}
    for(double shift:new double[]{0,-4,4,-8,8,-12,12}){
      if(station+shift<1||station+shift>mesh.length()-1)continue;
      Sample s=sample(mesh,station+shift);var parts=new ArrayList<Part>();
      var bands=RoadSupports.bearing(mesh,station+shift,1.5);
      boolean cut=bands.size()!=1||bands.get(0).center().distance(s.center())>1e-6||Math.abs(bands.get(0).halfWidth()-s.halfWidth())>1e-6;
      if(LaneDeck.hasOpenings(mesh)&&cut){
        // A portal cap belongs below a continuous deck. Across a reserved slot
        // support each surviving strip independently, with no outboard beam stub.
        for(var band:bands){
          var strip=RoadSupports.standard(band,mesh.settings().thickness()+Math.max(0,depth-.8),ground);
          if(strip.stream().noneMatch(ground::blocked))parts.addAll(strip);
        }
        if(!parts.isEmpty()){out.addAll(parts);return;}continue;
      }
      double offset=portal?s.halfWidth()+1.4:s.halfWidth()*.62;
      double top=s.center().y()-mesh.settings().thickness()-depth;
      boolean valid=true;
      for(int side:new int[]{-1,1}){
        V foot=at(s,side*offset,0);
        if(!RoadSupports.shaft(parts,foot,top,shaftWidth,ground)){valid=false;break;}
      }
      if(!valid||parts.stream().anyMatch(ground::blocked))continue;
      if(!parts.isEmpty())beam(parts,at(s,-offset-(shaftWidth+.75)/2,-mesh.settings().thickness()-depth),at(s,offset+(shaftWidth+.75)/2,-mesh.settings().thickness()-depth),Math.max(2.4,shaftWidth+.2),depth,Material.CONCRETE);
      if(parts.stream().anyMatch(ground::blocked))continue;
      out.addAll(parts);return;
    }
    if(mesh.settings().style().ramp())return;
    Sample portalSample=sample(mesh,station);
    // The fallback portal must not reintroduce the transverse beam just removed.
    var bearing=RoadSupports.bearing(mesh,station,1.5);
    boolean gap=LaneDeck.hasOpenings(mesh)&&(bearing.size()!=1||bearing.get(0).center().distance(portalSample.center())>1e-6||Math.abs(bearing.get(0).halfWidth()-portalSample.halfWidth())>1e-6);
    var relocated=gap?List.<Part>of():RoadSupports.portal(portalSample,mesh.settings().thickness(),depth,
        portal?portalSample.halfWidth()+1.4:portalSample.halfWidth()*.62,shaftWidth,ground);
    if(!relocated.isEmpty()){out.addAll(relocated);return;}
    // A support may land on the approach terrain, but not silently vanish over deep water.
    Sample s=sample(mesh,station);double floor=ground.top(s.center().x(),s.center().z(),s.center().y());
    if(!Double.isFinite(floor)||s.center().y()-floor>mesh.settings().thickness()+depth+1)
      throw new IllegalArgumentException("桥墩无可用基础或与下方道路冲突；请调整桥跨、桥位或桥面高度");
  }
  private static void arch(Mesh mesh,double start,double length,List<Part> out){
    // Leave the bridge-to-approach seam clear. Anchor on the widened slab inside
    // the bridge, never inside the next road's endpoint/collision cell.
    double trim=Math.min(6,length*.12),trimA=start<1e-6?trim:0,trimB=start+length>mesh.length()-1e-6?trim:0;
    start+=trimA;length-=trimA+trimB;
    double rise=Math.max(7,Math.min(28,length*.22));int steps=Math.max(12,(int)Math.ceil(length/3));
    for(int side:new int[]{-1,1}){
      V previous=null;
      for(int i=0;i<=steps;i++){
        double t=(double)i/steps;Sample s=sample(mesh,start+length*t);
        double y=1+rise*4*t*(1-t);V p=at(s,side*(s.halfWidth()+.8*endTaper(mesh,s)),y);
        if(previous!=null)beam(out,previous,p,.8,.8,Material.ARCH_STEEL);
        if(i==0||i==steps)post(out,at(s,side*(s.halfWidth()+.8*endTaper(mesh,s)),-mesh.settings().thickness()),.8,y+mesh.settings().thickness(),Material.CONCRETE);
        if(i>0&&i<steps&&i%2==0)post(out,at(s,side*(s.halfWidth()+.8*endTaper(mesh,s)),-.4),.18,y+.4,Material.STEEL);
        previous=p;
      }
    }
    for(double t:new double[]{.3,.5,.7}){
      Sample s=sample(mesh,start+length*t);double y=1+rise*4*t*(1-t);
      beam(out,at(s,-s.halfWidth()-.8,y),at(s,s.halfWidth()+.8,y),.45,.5,Material.ARCH_STEEL);
    }
  }
  private static double cableSpan(Mesh mesh){var c=mesh.settings().options().infrastructure();return mesh.length()/(c.autoSpan()?1:Math.max(1,(int)Math.ceil(mesh.length()/c.span()-1e-3)));}
  /** Visual proportions: tall towers gain mass without widening vehicle lanes. */
  public static double towerWidth(double height){return Math.min(7,1.5+Math.max(0,height-24)*.035);}
  public static double towerHeight(double span,Bridge style){return Math.max(12,span*(style==Bridge.CABLE?.22:.18));}
  // Pier footprints are world-axis squares. On an oblique road their actual
  // lateral half-width is larger than width/2, especially for the new tall towers.
  private static double towerRadius(Sample s,double width){return width*.5*(Math.abs(s.left().x())+Math.abs(s.left().z()));}
  private static double towerOffset(Sample s,double width){return towerRadius(s,width)+.55;}
  private static void cables(Mesh mesh,double start,double length,double depth,Bridge style,Ground ground,List<Part> out,boolean includeSupports){
    // Leave the bridge-to-approach seam clear. Anchor on the widened slab inside
    // the bridge, never inside the next road's endpoint/collision cell.
    double trim=Math.min(6,length*.12),trimA=start<1e-6?trim:0,trimB=start+length>mesh.length()-1e-6?trim:0;
    start+=trimA;length-=trimA+trimB;
    double tower=towerHeight(length,style),width=towerWidth(tower);
    double brace=Math.max(1.1,width*.7),stay=Math.min(.8,.14+Math.max(0,tower-24)*.003),mainCable=Math.min(1.5,.32+Math.max(0,tower-24)*.006),hanger=Math.min(.7,.12+Math.max(0,tower-24)*.0025);
    for(double fraction:new double[]{.2,.8}){
      Sample s=sample(mesh,start+length*fraction);
      double offset=towerOffset(s,width);
      if(includeSupports)support(mesh,start+length*fraction,depth,style,ground,out);
      for(int side:new int[]{-1,1})post(out,at(s,side*(s.halfWidth()+offset),-mesh.settings().thickness()),width,tower+mesh.settings().thickness(),Material.CONCRETE);
      beam(out,at(s,-s.halfWidth()-offset,tower*.8),at(s,s.halfWidth()+offset,tower*.8),brace,brace,Material.CONCRETE);
      // Transverse foundation beam ties the outside towers to the paired piers.
      beam(out,at(s,-s.halfWidth()-offset,-mesh.settings().thickness()-depth),at(s,s.halfWidth()+offset,-mesh.settings().thickness()-depth),Math.max(2.4,width+.4),depth,Material.CONCRETE);
      if(style==Bridge.CABLE)for(int side:new int[]{-1,1})for(int j=0;j<=10;j++){
        double t=(fraction<.5?0:.5)+j*.05;Sample anchor=sample(mesh,start+length*t);
        if(Math.abs(t-fraction)<.03)continue;
        double anchorOffset=side*(anchor.halfWidth()+towerOffset(anchor,width)*endTaper(mesh,anchor));
        post(out,at(anchor,anchorOffset,-.15),Math.max(.45,stay*2),Math.max(.5,stay),Material.DARK_STEEL);
        beam(out,at(anchor,anchorOffset,.12),at(s,side*(s.halfWidth()+offset),tower-.8),stay,stay+.02,Material.STEEL);
      }
    }
    if(style==Bridge.SUSPENSION){
      int steps=Math.max(24,(int)Math.ceil(length/3));
      for(int side:new int[]{-1,1}){
        V prev=null;
        for(int i=0;i<=steps;i++){
          double t=(double)i/steps;Sample s=sample(mesh,start+length*t);double offset=towerOffset(s,width);
          double h=t<.2?1+(tower-1)*t/.2:t>.8?1+(tower-1)*(1-t)/.2:tower*.35+tower*.65*Math.pow((t-.5)/.3,2);
          V p=at(s,side*(s.halfWidth()+offset*endTaper(mesh,s)),h);
          if(prev!=null)beam(out,prev,p,mainCable,mainCable+.03,Material.DARK_STEEL);
          if(i==0||i==steps)post(out,at(s,side*(s.halfWidth()+offset*endTaper(mesh,s)),-mesh.settings().thickness()),Math.max(.6,mainCable*1.6),h+mesh.settings().thickness(),Material.CONCRETE);
          if(i>0&&i<steps&&i%2==0)post(out,at(s,side*(s.halfWidth()+offset*endTaper(mesh,s)),.1),hanger,h-.1,Material.STEEL);
          prev=p;
        }
      }
    }
  }
  private static void validateOwnClearance(Mesh mesh,List<Part> parts){
    for(var part:parts){
      int steps=Math.max(1,(int)Math.ceil(part.b().sub(part.a()).horizontalLength()/2));
      for(int k=0;k<=steps;k++){
        V p=part.a().add(part.b().sub(part.a()).mul((double)k/steps));var q=RoadQueries.horizontal(mesh,p);var s=q.sample();
        if(Math.abs(q.lateral())<s.halfWidth()-.5+part.width()/2
            &&p.y()+part.height()>s.center().y()+.5&&p.y()<s.center().y()+5)
          throw new IllegalArgumentException("桥拱或拉索侵入本桥行车净空；请缩短桥跨、减小弯曲或改用梁桥");
      }
    }
  }
  public static boolean nearGantry(Mesh mesh,Ground ground,double distance){return RoadGantry.near(mesh,ground,distance);}
  private RoadInfrastructure(){}
}
