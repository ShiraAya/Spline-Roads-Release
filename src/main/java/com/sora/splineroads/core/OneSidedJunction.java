package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.JunctionSpec.*;
import static com.sora.splineroads.core.JunctionSpec.*;
import com.sora.splineroads.core.JunctionPlanner.*;
import com.sora.splineroads.core.RoadStructures.*;
import com.sora.splineroads.core.RoadSurface.Face;
import java.util.*;

/** A side-road mouth owns only the near carriageway; the host is retained exactly. */
public final class OneSidedJunction {
  public record Cut(double from,double to,int side){}
  public record Source(Mesh road,List<Part> structures,double from,double to,int side) {
    public Source {
      structures=List.copyOf(structures);
      road=RoadSlicing.attach(RoadSlicing.mesh(road.samples(),road.settings()),road.slicing()==null?new RoadSlicing.Geometry(List.of()):road.slicing());
      if(road.closed()||!RoadProfile.catalog(road.settings().style()).twoWay()
          ||!RoadGeometry.finite(from,to)||from<0||to>road.length()||to-from<16
          ||Math.abs(side)!=1||structures.size()>50000)
        throw new IllegalArgumentException("单边路口主路范围无效");
    }
    public int incoming(){return side>0?0:1;}
    public int outgoing(){return 1-incoming();}
  }
  public static int allowed(JunctionSpec s,int arm){
    var source=s.oneSide();if(source==null)return 15;
    if(arm==source.outgoing())return 0;
    if(arm==2)return RIGHT;
    return STRAIGHT|(s.arms().get(2).outgoing()>0?RIGHT:0);
  }
  /** Apply automatic lane defaults and reject attempts to cross the intact median. */
  public static JunctionSpec prepare(JunctionSpec s){
    var source=s.oneSide();if(source==null)return s;
    if(s.kind()!=Kind.INTERSECTION||s.arms().size()!=3||s.leftTraffic()||s.parallelApproaches()
        ||s.arms().stream().anyMatch(Arm::rightSlip))
      throw new IllegalArgumentException("单边路口使用右侧通行的三向连接，不支持环岛或额外独立右转道");
    var arms=new ArrayList<Arm>();
    for(int i=0;i<3;i++){
      var a=s.arms().get(i);var lanes=new ArrayList<Lane>();int available=allowed(s,i);
      for(int k=0;k<a.incoming();k++){
        var l=a.lanes().get(k);int mask=l.mask();
        if(mask<0)mask=i==source.incoming()?STRAIGHT|(k==a.incoming()-1?available&RIGHT:0):available;
        if((mask&~available)!=0)throw new IllegalArgumentException("单边路口只允许主路直行/右转、支路右转");
        for(int ti=0;ti<4;ti++)if((mask&TURNS[ti])!=0){int target=TURNS[ti]==STRAIGHT?source.outgoing():i==2?source.outgoing():2;
          if(l.targets().get(ti)>=0&&l.targets().get(ti)!=target)throw new IllegalArgumentException("单边路口不能映射到对向车道");
          l=l.target(ti,target,l.targetLanes().get(ti));
        }
        lanes.add(l.mask(mask));
      }
      arms.add((i<2?a.crossing(false):a).lanes(lanes));
    }
    return s.arms(arms).oneSide(null);
  }
  private record Masks(RoadSurface.PaintMask edited,RoadSurface.PaintMask retained){}
  private static final WeakIdentityCache<Source,Masks> MASKS=new WeakIdentityCache<>(16,16,m->1);
  private static Masks masks(Source source){return MASKS.get(source,OneSidedJunction::makeMasks);}
  private static double border(Source source,Sample at){var layout=RoadProfile.layout(source.road(),at);return layout.medianCenter()+source.side()*(layout.median()/2+.16);}
  private static Masks makeMasks(Source source){
    var samples=new ArrayList<Sample>(source.road().samples());samples.add(RoadSlicing.at(source.road(),source.from()));samples.add(RoadSlicing.at(source.road(),source.to()));
    samples.sort(Comparator.comparingDouble(Sample::distance));var edited=new ArrayList<List<V>>();var retained=new ArrayList<List<V>>();
    for(int i=1;i<samples.size();i++){
      var a=samples.get(i-1);var b=samples.get(i);if(b.distance()-a.distance()<1e-8)continue;
      boolean inside=(a.distance()+b.distance())/2>source.from()&&(a.distance()+b.distance())/2<source.to();
      double edgeA=border(source,a),edgeB=border(source,b),extent=256;
      if(inside){edited.add(quad(a,b,edgeA,source.side()*extent,edgeB,source.side()*extent));retained.add(quad(a,b,-source.side()*extent,edgeA,-source.side()*extent,edgeB));}
      else retained.add(quad(a,b,-extent,extent,-extent,extent));
    }
    return new Masks(new RoadSurface.PaintMask(edited),new RoadSurface.PaintMask(retained));
  }
  private static List<V> quad(Sample a,Sample b,double loA,double hiA,double loB,double hiB){return List.of(a.at(loA,0),a.at(hiA,0),b.at(hiB,0),b.at(loB,0));}
  private static List<Face> paint(List<Face> faces,RoadSurface.PaintMask mask){
    var out=new ArrayList<Face>();for(var face:faces)for(var p:mask.clipXZ(face.points()))out.add(new Face(p,face.color(),face.emissive(),face.texture()));return List.copyOf(out);
  }
  /** Keep the original sections: exact clipped triangles are shared by every physical consumer. */
  private static Mesh deck(Mesh source,RoadSurface.PaintMask mask){
    var patches=new ArrayList<RoadSlicing.Patch>();var samples=source.samples();
    for(int i=1;i<samples.size();i++){
      var a=samples.get(i-1);var b=samples.get(i);var strips=new ArrayList<LaneDeck.Strip>();
      for(var strip:LaneDeck.strips(source,a,b))for(var tri:List.of(List.of(strip.al(),strip.ar(),strip.br()),List.of(strip.al(),strip.br(),strip.bl())))
        for(var polygon:mask.clipXZ(tri))for(int k=1;k<polygon.size()-1;k++){
          var x=polygon.get(0);var y=polygon.get(k);var z=polygon.get(k+1);
          if(RoadSurface.area(List.of(x,y,z))>1e-9)strips.add(new LaneDeck.Strip(x,y,z,z,false,false));
        }
      patches.add(new RoadSlicing.Patch(a.distance(),b.distance(),strips,true));
    }
    return RoadSlicing.attach(source,new RoadSlicing.Geometry(patches,true));
  }
  private static List<Part> parts(List<Part> parts,RoadSurface.PaintMask mask){
    var out=new ArrayList<Part>();var lamps=new HashMap<V,Boolean>();
    for(var p:parts){
      V foot=RoadStreetscape.lampFoot(p);
      if(foot!=null){boolean keep=lamps.computeIfAbsent(foot,v->!mask.clipXZ(List.of(v.add(new V(-.001,0,-.001)),v.add(new V(.001,0,-.001)),v.add(new V(.001,0,.001)),v.add(new V(-.001,0,.001)))).isEmpty());if(keep)out.add(p);continue;}
      var polygons=mask.clipXZ(p.base());double area=polygons.stream().mapToDouble(RoadSurface::area).sum();
      if(Math.abs(area-RoadSurface.area(p.base()))<1e-7){out.add(p);continue;}
      if(area<1e-9)continue;
      // Model assemblies and supports are indivisible. A boundary cannot bisect one.
      boolean longitudinal=Set.of("sr:cycle_planter","sr:planting_soil","sr:cycle_wall","sr:cycle_bypass").contains(p.model());
      if(!p.model().isEmpty()&&!longitudinal||p.pier()||RoadSignals.signal(p)||RoadPoleModel.support(p))
        throw new IllegalArgumentException("单边路口边界穿过不可拆构件（"+p.material()+", "+p.model()+"），请调整接入位置");
      for(var polygon:polygons)for(int k=1;k<polygon.size()-1;k++){
        var a=polygon.get(0);var b=polygon.get(k);var c=polygon.get(k+1);
        if(RoadSurface.area(List.of(a,b,c))<1e-9)continue;
        out.add(new Part(a.add(b).mul(.5),c,Math.max(.001,Math.min(8,a.distance(b))),p.height(),false,p.material(),a.sub(b).mul(.5),new V(0,0,0),p.model()));
      }
    }
    return List.copyOf(out);
  }
  public static List<Part> generatedParts(Source source,List<Part> parts){return parts(parts,masks(source).retained());}
  public static boolean retainedPiece(JunctionPlanner.Ref ref){return ref.spec().oneSide()!=null&&ref.get().arm()==-3;}
  /** Shared host pavement is clipped once for every independent side connection. */
  public static Piece retained(Source original,List<Cut> cuts){
    Mesh mesh=original.road();var paint=RoadSurface.build(mesh,List.of(),List.of()).markings();var structures=original.structures();
    for(var cut:cuts){var source=new Source(original.road(),original.structures(),cut.from(),cut.to(),cut.side());var mask=masks(source).edited();
      mesh=deck(mesh,mask);paint=paint(paint,mask);structures=parts(structures,mask);
    }
    return new Piece(mesh,paint,structures,-3);
  }
  public static Plan apply(JunctionSpec spec,Plan base){
    var source=spec.oneSide();var masks=masks(source);var pieces=new ArrayList<Piece>();
    for(var piece:base.pieces())pieces.add(new Piece(deck(piece.mesh(),masks.retained()),paint(piece.paint(),masks.retained()),parts(piece.structures(),masks.retained()),piece.arm()));
    var original=source.road();var markings=RoadSurface.build(original,List.of(),List.of()).markings();
    pieces.add(new Piece(deck(original,masks.edited()),paint(markings,masks.edited()),parts(source.structures(),masks.edited()),-3));
    var walks=new LinkedHashMap<RoadSidewalks.Cell,RoadSidewalks.Config>();
    base.sidewalks().forEach((cell,config)->{if(!masks.retained().clipXZ(List.of(new V(cell.x(),cell.y(),cell.z()),new V(cell.x()+1,cell.y(),cell.z()),new V(cell.x()+1,cell.y(),cell.z()+1),new V(cell.x(),cell.y(),cell.z()+1))).isEmpty())walks.put(cell,config);});
    return new Plan(List.copyOf(pieces),base.movements(),base.boundary(),Map.copyOf(walks),base.signals(),base.heads());
  }
  private OneSidedJunction(){}
}
