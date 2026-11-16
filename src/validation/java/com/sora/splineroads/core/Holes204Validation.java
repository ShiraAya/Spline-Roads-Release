package com.sora.splineroads.core;

import java.util.*;
import java.util.function.ToDoubleFunction;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;

/** Terrain-shape controls for the shared AUTO classifier, including real railbed geometry. */
public final class Holes204Validation {
  static int checks,smallCases,controls;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
  static Ground ground(ToDoubleFunction<V> floor){return new Ground(){public double top(double x,double z,double y){return floor.applyAsDouble(new V(x,y,z));}public boolean joined(V p){return false;}public boolean blocked(Part p){return false;}};}
  static Mesh road(double width,boolean diagonal,double length){
    var style=width<9?Style.O1_ONE:Style.O2_RAIL;var s=new Settings(Mode.STRAIGHT,style,width,1,.35,90).structure(Structure.AUTO);
    s=s.options(s.options().extras(false,false,false).route(s.options().routing().fit(false)).infrastructure(s.options().infrastructure().gantry(RoadInfrastructure.Gantry.OFF)));
    V direction=new V(diagonal?1:0,0,1).horizontalUnit();var a=new V(.5,8,.5);var b=a.add(direction.mul(Math.max(length,2+1e-8)));
    return RoadGeometry.build(new Node(a,diagonal?-45:0,0),new Node(b,diagonal?-45:0,0),s);
  }
  public static List<RailbedPath.Path> paths(int tracks,boolean diagonal,boolean curved){
    var paths=new ArrayList<RailbedPath.Path>();V direction=new V(diagonal?1:0,0,1).horizontalUnit(),left=direction.left();
    for(int track=0;track<tracks;track++){
      var points=new ArrayList<RailbedPath.Point>();for(int i=0;i<=64;i++){
        var p=new V(.5,8,.5).add(direction.mul(i)).add(left.mul(track*4+(curved?.001*i*(64-i):0)));points.add(new RailbedPath.Point(i,p));
      }
      var a=points.get(0).position();var b=points.get(64).position();
      paths.add(new RailbedPath.Path(List.of(new RailbedPath.Segment("holes204-"+track,new RailbedPath.Node((int)Math.floor(a.x()),8,(int)Math.floor(a.z())),new RailbedPath.Node((int)Math.floor(b.x()),8,(int)Math.floor(b.z())),false,64,List.of("default"),points))));
    }return paths;
  }
  static Mesh railway(double width,int tracks,boolean diagonal,boolean curved){
    var paths=paths(tracks,diagonal,curved);var plan=RailbedGeometry.plan(paths,width,Structure.AUTO);var raw=plan.mesh();
    var s=raw.settings().structure(Structure.AUTO).options(raw.settings().options().railbed(new RailbedProfile(paths,width,RailbedOptions.DEFAULT)).infrastructure(RoadInfrastructure.Config.DEFAULT));
    return new Mesh(raw.samples(),s,raw.min(),raw.max(),raw.length(),raw.closed());
  }
  static String label(Mesh m){return m.settings().style()+" width="+m.settings().width()+" tracks="+m.settings().options().railbed().paths().size();}
  static Ground hole(int x,int z,int width,int length){return ground(p->p.x()>=x&&p.x()<x+width&&p.z()>=z&&p.z()<z+length?0:8);}
  static void small(Mesh m,int hx,int hz,int sx,int sz){
    var g=hole(hx,hz,sx,sz);var desc=label(m)+" hole="+hx+","+hz+" "+sx+"x"+sz;
    check(RoadStreetscape.classify(m,g).isEmpty(),"local pit classified as bridge: "+desc);
    // Check the raw support consumer as well: filtering only cached raised spans
    // would leave the original bridge-pier defect in place.
    check(RoadStructures.supports(m,g,RoadFurniture.Phase.DEFAULT).isEmpty(),"local pit generated support: "+desc);smallCases++;
  }
  static double station(Mesh mesh,V point){var a=mesh.first().center();return point.sub(a).dot(mesh.last().center().sub(a).horizontalUnit());}
  static double lateral(Mesh mesh,V point){var a=mesh.first().center();return point.sub(a).dot(mesh.last().center().sub(a).horizontalUnit().left());}
  static Mesh withMode(Mesh m,Structure mode){return new Mesh(m.samples(),m.settings().structure(mode),m.min(),m.max(),m.length(),m.closed());}
  static void controls(Mesh mesh){
    String label=label(mesh);var supported=ground(p->8);var floating=ground(p->0);var missing=ground(p->Double.NaN);
    check(RoadStreetscape.classify(mesh,supported).isEmpty(),"supported road became bridge "+label);
    check(!RoadStreetscape.classify(mesh,floating).isEmpty(),"real suspended deck lost "+label);
    check(!RoadStructures.supports(RoadStreetscape.resolve(mesh,floating),floating,RoadFurniture.Phase.DEFAULT).isEmpty(),"real suspended deck lost supports "+label);
    check(!RoadStreetscape.classify(mesh,missing).isEmpty(),"missing terrain lost elevated classification "+label);
    check(RoadStructures.supports(mesh,missing,RoadFurniture.Phase.DEFAULT).isEmpty(),"no foundation produced invented supports "+label);
    for(int side:new int[]{-1,1}){
      var half=ground(p->side*lateral(mesh,p)>0?0:8);
      for(double at=.25;at<mesh.length();at+=.5){var s=RoadStructures.sample(mesh,at);check(RoadStructures.elevated(mesh,s,half,side),"long half span interrupted "+label+" station="+at);check(!RoadStructures.elevated(mesh,s,half,-side),"supported half promoted "+label);}
      check(!RoadStructures.supports(RoadStreetscape.resolve(mesh,half),half,RoadFurniture.Phase.DEFAULT).isEmpty(),"half span lost supports "+label);
      for(double length:new double[]{4,5,8}){
        var band=ground(p->side*lateral(mesh,p)>0&&station(mesh,p)>=20&&station(mesh,p)<20+length?0:8);
        for(double at=20.25;at<20+length;at+=.5){check(RoadStructures.elevated(mesh,RoadStructures.sample(mesh,at),band,side),"finite half span interrupted "+label+" length="+length+" station="+at);check(!RoadStructures.elevated(mesh,RoadStructures.sample(mesh,at),band,-side),"finite band promoted opposite side");}
        check(!RoadStructures.elevated(mesh,RoadStructures.sample(mesh,18),band,side),"finite half span leaked before opening");
        check(!RoadStructures.elevated(mesh,RoadStructures.sample(mesh,22+length),band,side),"finite half span leaked after opening");
      }
    }
    // The 2m opening crosses BOTH halves. It must survive the local-side-pit filter.
    var trench=ground(p->station(mesh,p)>=11&&station(mesh,p)<13?0:8);
    check(!RoadStreetscape.classify(mesh,trench).isEmpty(),"two-block full-width trench erased "+label);
    check(RoadStructures.elevated(mesh,RoadStructures.sample(mesh,12),trench,-1)&&RoadStructures.elevated(mesh,RoadStructures.sample(mesh,12),trench,1),"full-width trench midpoint lost "+label);
    check(!RoadStructures.supports(RoadStreetscape.resolve(mesh,trench),trench,RoadFurniture.Phase.DEFAULT).isEmpty(),"full-width trench lost physical supports "+label);
    var spine=ground(p->Math.abs(lateral(mesh,p))<=.5?8:0);
    check(RoadStructures.elevated(mesh,RoadStructures.sample(mesh,24),spine,-1)&&RoadStructures.elevated(mesh,RoadStructures.sample(mesh,24),spine,1),"one-block construction spine incorrectly supports deck "+label);
    var fixed=withMode(mesh,Structure.BRIDGE);check(RoadStructures.elevated(fixed,RoadStructures.sample(fixed,12),supported),"explicit BRIDGE overridden "+label);
    var fixedGround=withMode(mesh,Structure.GROUND);check(!RoadStructures.elevated(fixedGround,RoadStructures.sample(fixedGround,12),floating),"explicit GROUND overridden "+label);controls++;
  }
  public static void main(String[] args){
    if(args.length>0&&args[0].equals("red")){
      var road=road(5,false,64);var rail=railway(6,1,false,false);
      check(!RoadStreetscape.classify(road,hole(-2,12,1,1)).isEmpty(),"baseline narrow-road red missing");
      check(!RoadStreetscape.classify(rail,hole(-3,12,2,2)).isEmpty(),"baseline railway red missing");
      check(!RoadStructures.supports(RoadStreetscape.resolve(rail,hole(-3,12,2,2)),hole(-3,12,2,2),RoadFurniture.Phase.DEFAULT).isEmpty(),"baseline railway support red missing");
      System.out.println("HOLES204 RED narrow O1 single pit and real RailbedGeometry 2x2 side pit reproduced, including railway supports");return;
    }
    for(boolean rail:new boolean[]{false,true})for(double width:rail?new double[]{6,7,8,10,18,34,64}:new double[]{5,6,9,13,22,28})for(boolean diagonal:new boolean[]{false,true}){
      Mesh m=rail?railway(width,1,diagonal,false):road(width,diagonal,64);controls(m);
      for(int size:new int[]{1,2})for(int hx=-5;hx<=25;hx++)for(int hz=10;hz<=24;hz++){
        if(diagonal&&Math.abs(hx-hz)>width/2+3||!diagonal&&Math.abs(hx)>width/2+2)continue;small(m,hx,hz,size,size);
      }
      // An elongated 1x2 hole still differs from a true full-width trench.
      for(int hx=-5;hx<=5;hx++)for(int hz=11;hz<=14;hz++){small(m,hx,hz,1,2);small(m,hx,hz,2,1);}
      System.out.println("HOLES204 MATRIX PASS "+label(m)+" diagonal="+diagonal);
    }
    for(int tracks:new int[]{2,4,8}){var m=railway(0,tracks,false,false);controls(m);for(int x=-4*tracks;x<=4;x++)for(int z=11;z<=14;z++)small(m,x,z,2,2);}
    var curved=railway(0,1,false,true);check(curved.settings().width()==6,"curved single-track fixture width changed");for(int x=-5;x<=5;x++)for(int z=8;z<=48;z++)small(curved,x,z,1,1);
    for(double length:new double[]{2,2.5,3})for(boolean diagonal:new boolean[]{false,true}){
      var m=road(5,diagonal,length);for(int side:new int[]{-1,1}){var half=ground(p->side*lateral(m,p)>0?0:8);for(double d=0;d<=length;d+=.25)check(RoadStructures.elevated(m,RoadStructures.sample(m,d),half,side),"short whole-road half span lost length="+length+" diagonal="+diagonal+" at="+d);}
    }
    // Full structure generation uses the same result, not just the exposed classifier.
    for(var m:List.of(road(5,false,64),railway(6,1,false,false))){var g=hole(-3,12,2,2);var all=RoadStructures.plan(m,g,RoadFurniture.Phase.DEFAULT);check(all.stream().noneMatch(p->p.pier()&&p.a().y()<7),"full structure path restored pit supports "+label(m));}
    // The old 24m raised-span joining rule amplified six isolated side pits into
    // one 42m bridge. Cover the complete chain in both raw and resolved consumers.
    for(var m:List.of(road(5,false,64),road(9,false,64),railway(6,1,false,false))){
      var g=ground(p->{boolean pit=false;for(int z:new int[]{12,20,28,36,44,52})pit|=p.x()>=-3&&p.x()< -1&&p.z()>=z&&p.z()<z+2;return pit?0:8;});
      check(RoadStreetscape.classify(m,g).isEmpty(),"separate local pits merged into long bridge "+label(m));
      check(RoadStructures.supports(m,g,RoadFurniture.Phase.DEFAULT).isEmpty(),"side-pit chain generated raw supports "+label(m));
      check(RoadStructures.supports(RoadStreetscape.resolve(m,g),g,RoadFurniture.Phase.DEFAULT).isEmpty(),"side-pit chain generated resolved supports "+label(m));
      check(RoadStructures.plan(m,g,RoadFurniture.Phase.DEFAULT).stream().noneMatch(p->p.pier()&&p.a().y()<7),"full structure generation restored side-pit chain supports "+label(m));
    }
    System.out.println("HOLES204 PASS checks="+checks+" smallCases="+smallCases+" positiveFixtureFamilies="+controls+" sideChainFamilies=3 realRailbedGeometry/small-pits/side-chain/trench/finite-half/short-half/spine/modes/foundation");
  }
}
