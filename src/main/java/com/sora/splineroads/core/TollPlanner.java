package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import com.sora.splineroads.core.TollPlaza.*;
import java.util.*;

/** One authoritative straight-axis layout: pavement, paint, solids and clearance. */
public final class TollPlanner {
  public static final double ISLAND=1.2, CORE=28, MAX_GRADE=.12;
  // Existing structure collision uses outward 1/4-block cells. Its largest lateral
  // inflation is sqrt(2)/4. Reserve this on BOTH sides of each declared clear passage.
  public static final double COLLISION_MARGIN=.36;
  private static double pitch(Config c){return c.passageWidth()+2*COLLISION_MARGIN+ISLAND;}
  public record Lengths(double current,double a,double core,double b) {
    public double minimum(){return a+core+b;}
    public double deficit(){return Math.max(0,minimum()-current);}
  }
  public record Fit(Spec original,Spec fitted,Lengths lengths,double movedA,double movedB){}
  public record Band(double inner,double outer,int lanes,int sign) {
    public double at(double fraction){return inner+(outer-inner)*fraction;}
  }
  public record Unit(int direction,int index,boolean etc,V center,double clearWidth,double clearHeight){}
  public record Plan(Spec spec,Lengths lengths,Mesh mesh,List<Part> structures,List<RoadSurface.Face> paint,
      List<Unit> units,double coreStart,double coreEnd) {}

  private static double smooth(double t){t=Math.max(0,Math.min(1,t));return t*t*t*(10+t*(-15+6*t));}
  public static boolean contains(Mesh mesh,V point,double margin,double height){
    V axis=mesh.last().center().sub(mesh.first().center()).horizontalUnit();double d=point.sub(mesh.first().center()).dot(axis),length=mesh.last().center().sub(mesh.first().center()).horizontalLength();
    if(d< -margin||d>length+margin)return false;var q=RoadQueries.horizontal(mesh,point);
    return Math.abs(q.lateral())<=q.sample().halfWidth()+margin&&point.y()>=q.sample().center().y()-mesh.settings().thickness()-margin&&point.y()<=q.sample().center().y()+height+margin;
  }
  private static double lerp(double a,double b,double t){return a+(b-a)*t;}
  public static Lengths lengths(Spec s){
    if(!s.path().isEmpty())return lengths(TollInlineGeometry.local(s));
    V delta=s.b().position().sub(s.a().position());double length=delta.horizontalLength();
    if(!RoadGeometry.finite(length,s.a().position().y(),s.b().position().y(),s.a().grade(),s.b().grade())||length<.1||length>RoadLimits.MAX_ENDPOINT_DISTANCE)
      throw new IllegalArgumentException("收费站 A/B 水平距离须为 0.1–2048 格");
    if(Math.abs(s.a().grade())>MAX_GRADE||Math.abs(s.b().grade())>MAX_GRADE)throw new IllegalArgumentException("收费站接口纵坡最多 12%");
    double dy=Math.abs(delta.y())/2;var c=s.config();double a=18,b=18;
    for(int direction=0;direction<(c.twoWay()?2:1);direction++){
      var core=core(c,direction);
      for(boolean first:new boolean[]{true,false}){
        var port=band(s,first,direction);
        double displacement=Math.max(Math.abs(core.inner()-port.inner()),Math.abs(core.outer()-port.outer()));
        int changes=Math.abs(core.lanes()-port.lanes());
        // Quintic lateral blend has max derivative 1.875; radius grows with sqrt(displacement).
        double side=Math.max(18,Math.max(1.875*displacement/.22,Math.sqrt(5.78*displacement*24)))+changes*3;
        if(first)a=Math.max(a,side);else b=Math.max(b,side);
      }
    }
    double margin=Math.max(.035,MAX_GRADE-.65*Math.max(Math.abs(s.a().grade()),Math.abs(s.b().grade())));
    a=Math.max(a,1.5*dy/margin+4);b=Math.max(b,1.5*dy/margin+4);
    return new Lengths(length,a,CORE,b);
  }
  public static Spec alignFreePort(Spec s,Adjust mode,V fixedAway){
    if(mode!=Adjust.A&&mode!=Adjust.B)return s;
    boolean fixedA=mode==Adjust.B;V requested=s.b().position().sub(s.a().position()).horizontalUnit();
    V axis=fixedAway.horizontalUnit().mul(fixedA?-1:1);
    if(axis.dot(requested)<=0)throw new IllegalArgumentException((fixedA?"A":"B")+" 端外接道路朝向站内，不能通过自动移动翻转整座收费站");
    if(axis.dot(requested)>.99999999)return s;
    double length=s.b().position().sub(s.a().position()).horizontalLength(),yaw=RoadPlanner.yaw(axis);
    V point=fixedA?s.a().position().add(axis.mul(length)):s.b().position().sub(axis.mul(length));
    var a=fixedA?s.a():new Node(new V(point.x(),s.a().position().y(),point.z()),yaw,s.a().grade());
    var b=fixedA?new Node(new V(point.x(),s.b().position().y(),point.z()),yaw,s.b().grade()):s.b();
    return new Spec(a,b,s.config(),s.portA(),s.portB());
  }
  public static Fit fit(Spec s,Adjust mode){
    if(!s.path().isEmpty()){if(mode!=Adjust.NONE)throw new IllegalArgumentException("道路断面收费站固定原路接口，请调整收费通道数量或选址");return new Fit(s,s,lengths(s),0,0);}
    if(mode==null)throw new IllegalArgumentException("自动端点调整模式无效");
    var lengths=lengths(s);double deficit=lengths.deficit();
    if(deficit<1e-6||mode==Adjust.NONE)return new Fit(s,s,lengths,0,0);
    double da=mode==Adjust.B?0:mode==Adjust.A?deficit:deficit/2,db=deficit-da;
    V axis=s.b().position().sub(s.a().position()).horizontalUnit();
    var a=new Node(s.a().position().sub(axis.mul(da)),s.a().yaw(),s.a().grade());
    var b=new Node(s.b().position().add(axis.mul(db)),s.b().yaw(),s.b().grade());
    var fitted=new Spec(a,b,s.config(),s.portA(),s.portB());return new Fit(s,fitted,lengths(fitted),da,db);
  }
  public static Band band(Config c,boolean a,int direction){
    return band(c,TollPlaza.port(c,a),a,direction);
  }
  public static Band band(Spec s,boolean a,int direction){return band(s.config(),TollPlaza.port(s,a),a,direction);}
  private static Band band(Config c,Settings settings,boolean a,int direction){
    var layout=RoadProfile.layout(settings,settings.width());
    int sign=RoadProfile.trafficSign(c.leftTraffic())*(direction==0?1:-1);
    int n=(direction==0?c.forward():c.backward()).lanes(a);
    if(!c.twoWay())return new Band(layout.motorMin(),layout.motorMax(),n,1);
    return new Band(layout.medianCenter()+sign*layout.median()/2,layout.outer(sign),n,sign);
  }
  public static Band core(Config c,int direction){
    double pitch=pitch(c);
    if(!c.twoWay()){double w=c.forward().units()*pitch;return new Band(-w/2,w/2,c.forward().units(),1);}
    int sign=RoadProfile.trafficSign(c.leftTraffic());
    double median=sign*(directionWidth(c,1)-directionWidth(c,0))/2;
    sign*=direction==0?1:-1;
    int n=(direction==0?c.forward():c.backward()).units();
    double inner=median+sign*ISLAND/2;
    return new Band(inner,inner+sign*directionWidth(c,direction),n,sign);
  }
  private static double directionWidth(Config c,int dir){return (dir==0?c.forward():c.backward()).units()*(c.charges(dir)?pitch(c):c.laneWidth());}
  private static double coreWidth(Config c){return directionWidth(c,0)+(c.twoWay()?directionWidth(c,1)+ISLAND:0)+ISLAND+1;}
  private static double elevation(Spec s,double d,double from,double to,double length){
    double y=(s.a().position().y()+s.b().position().y())/2;
    if(d<from){double t=d/from;return lerp(s.a().position().y(),y,t*t*(3-2*t))+s.a().grade()*from*t*(t-1)*(t-1);}
    if(d>to){double n=length-to,t=(d-to)/n;return lerp(y,s.b().position().y(),t*t*(3-2*t))+s.b().grade()*n*t*t*(t-1);}
    return y;
  }
  public static Plan plan(Spec s){
    if(!s.path().isEmpty())return TollInlineGeometry.plan(s);
    return station(s,false);
  }
  static Plan centered(Spec s){return station(s,true);}
  private static Plan station(Spec s,boolean centered){
    var ls=lengths(s);if(ls.deficit()>1e-5)throw new IllegalArgumentException(String.format(Locale.ROOT,"收费站长度 %.1f / 最低 %.1f，尚缺 %.1f 格；请选择 AB、仅 A 或仅 B 自动调整",ls.current(),ls.minimum(),ls.deficit()));
    Config c=s.config();double extra=Math.max(0,ls.current()-ls.minimum()),from=centered?(ls.current()-CORE)/2:ls.a()+extra/2,to=from+CORE;
    if(from+1e-5<ls.a()||ls.current()-to+1e-5<ls.b())throw new IllegalArgumentException("收费站中心两侧过渡空间不足");
    V axis=s.b().position().sub(s.a().position()).horizontalUnit(),left=axis.left();
    double wa=TollPlaza.port(s,true).width(),wb=TollPlaza.port(s,false).width(),wc=coreWidth(c);
    var stations=new TreeSet<Double>();int count=(int)Math.ceil(ls.current()/.5);for(int i=0;i<=count;i++)stations.add(ls.current()*i/count);
    // A sliced curved road can differ from an exact half-block by a few ulps.
    // Keep transition knots, without emitting two effectively identical samples.
    for(double knot:new double[]{from,to}){stations.removeIf(d->Math.abs(d-knot)<1e-7);stations.add(knot);}
    var samples=new ArrayList<Sample>();double lastY=0,lastD=0;
    for(double d:stations){
      double w=d<from?lerp(wa,wc,smooth(d/from)):d>to?lerp(wc,wb,smooth((d-to)/(ls.current()-to))):wc;
      double y=elevation(s,d,from,to,ls.current());
      if(!samples.isEmpty()&&Math.abs(y-lastY)/(d-lastD)>MAX_GRADE+1e-5)throw new IllegalArgumentException("当前高差/接口坡度不能形成平顺收费核心，请增加站区长度");
      var p=s.a().position().add(axis.mul(d));samples.add(new Sample(new V(p.x(),y,p.z()),left,d,w/2));lastY=y;lastD=d;
    }
    var settings=new Settings(Mode.STRAIGHT,Style.TOLL,Math.max(wc,Math.max(wa,wb)),1,.35,90).structure(c.structure())
        .options(RoadProfile.Options.DEFAULT.cycleFinish(RoadProfile.Options.CycleFinish.NONE).hideArrows(true));
    Mesh mesh=RoadRibbon.mesh(samples,settings);var parts=new ArrayList<Part>();var paint=new ArrayList<RoadSurface.Face>();var units=new ArrayList<Unit>();
    var geometry=new Geometry(s,axis,left,from,to,ls.current());
    // Longitudinal rails follow the continuous boundary. No transverse fence at either port.
    for(int i=1;i<samples.size();i++)for(int side:new int[]{-1,1}){
      var a=samples.get(i-1);var b=samples.get(i);
      line(paint,a.at(side*(a.halfWidth()-.28),-.015),b.at(side*(b.halfWidth()-.28),-.015),.12,0xFAFAF0);
      parts.add(new Part(a.at(side*(a.halfWidth()-.09),0),b.at(side*(b.halfWidth()-.09),0),.16,.65,false,Material.CONCRETE,left.mul(.08),left.mul(.08),"sr:toll_outer"));
    }
    if(c.twoWay())for(int i=1;i<samples.size();i++){
      double da=geometry.station(samples.get(i-1).center()),db=geometry.station(samples.get(i).center());
      double ma=median(s,da,from,to,ls.current()),mb=median(s,db,from,to,ls.current());
      parts.add(geometry.part(da,db,ma,mb,.32,.8,0,Material.CONCRETE,"sr:toll_median"));
    }
    for(int dir=0;dir<(c.twoWay()?2:1);dir++){
      var direction=dir==0?c.forward():c.backward();var core=core(c,dir);int flow=c.twoWay()?(dir==0?1:-1):(c.reverse()?-1:1);
      if(!c.charges(dir)){
        // Untolled carriageway remains open with continuous lane dividers.
        for(int k=1;k<core.lanes();k++)for(double dash=0;dash<ls.current();dash+=7)for(double d=dash;d<Math.min(dash+4,ls.current())-1e-7;d+=1){
          double end=Math.min(d+1,Math.min(dash+4,ls.current()));
          line(paint,geometry.at(d,laneAt(s,dir,k,d,from,to,ls.current()),.022),geometry.at(end,laneAt(s,dir,k,end,from,to,ls.current()),.022),.15,0xFAFAF0);
        }
        continue;
      }
      double center=(from+to)/2,pitch=pitch(c);
      for(int k=0;k<=core.lanes();k++){
        double lateral=core.at((double)k/core.lanes());
        parts.add(geometry.part(center-6,center+6,lateral,lateral,ISLAND,.35,0,Material.CONCRETE,"sr:toll_island"));
        parts.add(geometry.part(center-flow*6,center-flow*5,lateral,lateral,1.08,1.25,.35,Material.STEEL,"sr:toll_model_island_nose"));
        // Upright, static barrier stays wholly on the island; no arm blocks the advertised passage.
        if(k<core.lanes())parts.add(geometry.part(center+flow*2.4,center+flow*3.4,lateral,lateral,.94,4.5,.35,Material.STEEL,"sr:toll_model_gate"));
        for(int stripe=0;stripe<12;stripe++)for(int edge:new int[]{-1,1}){
          if(c.twoWay()&&k==0&&edge==-core.sign())continue; // buried seam between the two median islands
          double d=center-6+stripe;parts.add(geometry.part(d,d+.94,lateral+edge*(ISLAND/2+.018),lateral+edge*(ISLAND/2+.018),.055,.20,.08,stripe%2==0?Material.TACTILE:Material.DARK_STEEL,"sr:toll_curb"));
        }
        for(double d:new double[]{center-4.5,center+4.5}){
          parts.add(geometry.part(d-.24,d+.24,lateral,lateral,.46,c.clearHeight()+.8,.35,Material.STEEL,"sr:toll_column"));
          parts.add(geometry.part(d-.32,d+.32,lateral,lateral,.62,.22,.35,Material.DARK_STEEL,"sr:toll_column_plinth"));
          parts.add(geometry.part(d-.27,d+.27,lateral,lateral,.52,.12,1.52,Material.SIGN_BLUE,"sr:toll_column_band"));
          parts.add(geometry.part(d-.30,d+.30,lateral,lateral,.58,.14,c.clearHeight()+.70,Material.SIGN_WHITE,"sr:toll_column_cap"));
        }
      }
      for(int k=0;k<core.lanes();k++){
        double lateral=core.at((k+.5)/core.lanes());boolean etc=k<direction.etc();
        units.add(new Unit(flow,k,etc,geometry.at(center,lateral,0),c.passageWidth(),c.clearHeight()));
        // Canopy/beam undersides and signs are never below the advertised clearance.
        parts.add(geometry.part(center-5.7,center+5.7,lateral,lateral,pitch,.35,c.clearHeight()+1,Material.DARK_STEEL,"sr:toll_canopy"));
        for(double d:new double[]{center-5.86,center+5.68}){
          parts.add(geometry.part(d,d+.18,lateral,lateral,pitch,.44,c.clearHeight()+.75,Material.SIGN_WHITE,"sr:toll_fascia"));
          parts.add(geometry.part(d-.025,d+.205,lateral,lateral,pitch,.09,c.clearHeight()+1.19,Material.SIGN_BLUE,"sr:toll_fascia_trim"));
          parts.add(geometry.part(d+.02,d+.16,lateral,lateral,pitch-.25,.05,c.clearHeight()+.72,Material.LAMP,"sr:toll_edge_light"));
        }
        // Shallow raised seam roof with alternating metal strips and a ridge cap.
        for(int roof=0;roof<8;roof++){
          double d=center-5.6+roof*1.4,up=c.clearHeight()+1.35+.15*(1-Math.abs(roof-3.5)/3.5);
          parts.add(geometry.part(d,d+1.38,lateral,lateral,pitch-.05,.10,up,Material.STEEL,"sr:toll_roof_panel"));
          parts.add(geometry.part(d,d+.035,lateral,lateral,pitch-.05,.035,up+.10,Material.SIGN_WHITE,"sr:toll_roof_seam"));
        }
        for(int edge:new int[]{-1,1}){
          double x=lateral+edge*(pitch/2-.35);
          parts.add(geometry.part(center-4.6,center+4.6,x,x,.13,.13,c.clearHeight()+.53,Material.SIGN_WHITE,"sr:toll_soffit_rib"));
        }
        parts.add(geometry.part(center-flow*5.4-.15,center-flow*5.4+.15,lateral,lateral,c.passageWidth(),.72,c.clearHeight()+.05,etc?Material.SIGN_GREEN:Material.SIGN_BLUE,etc?"sr:toll_ETC":"sr:toll_manual"));
        glyph(parts,geometry,center-flow*5.4-flow*.18,lateral,c.clearHeight()+.16,etc?"ETC":"M",flow);
        parts.add(geometry.part(center-2,center+2,lateral,lateral,.35,.08,c.clearHeight()+.9,Material.LAMP,"sr:toll_light"));
        if(!etc){double booth=core.at((double)k/core.lanes());
          parts.add(geometry.part(center-1.4,center+1.4,booth,booth,.92,1.10,.35,Material.STEEL,"sr:toll_booth_base"));
          parts.add(geometry.part(center-1.36,center+1.36,booth,booth,.84,1.08,1.45,Material.SIGN_BLUE,"sr:toll_window_glass"));
          for(int side:new int[]{-1,1}){
            double x=booth+side*.455;
            for(double d:new double[]{center-1.35,center-.5,center+.5,center+1.28})parts.add(geometry.part(d,d+.07,x,x,.07,1.08,1.45,Material.SIGN_WHITE,"sr:toll_window_frame"));
            for(double y:new double[]{1.43,2.0,2.53})parts.add(geometry.part(center-1.4,center+1.4,x,x,.07,.065,y,Material.SIGN_WHITE,"sr:toll_window_frame"));
            parts.add(geometry.part(center-.62,center+.62,x,x,.13,.08,1.36,Material.DARK_STEEL,"sr:toll_counter"));
            parts.add(geometry.part(center+1.04,center+1.1,x+side*.025,x+side*.025,.06,.18,.91,Material.DARK_STEEL,"sr:toll_door_handle"));
          }
          parts.add(geometry.part(center+1.3,center+1.39,booth,booth,.84,1.08,.35,Material.SIGN_BLUE,"sr:toll_door"));
          parts.add(geometry.part(center-1.52,center+1.52,booth,booth,1.10,.18,2.6,Material.DARK_STEEL,"sr:toll_booth_roof"));
          parts.add(geometry.part(center-1.48,center+1.48,booth,booth,1.02,.1,2.78,Material.SIGN_BLUE,"sr:toll_booth_roof_trim"));
          for(int side:new int[]{-1,1}){
            double x=booth+side*.465;
            parts.add(geometry.part(center-1.3,center+1.3,x,x,.025,.055,.56,Material.SIGN_BLUE,"sr:toll_booth_body_trim"));
            for(int vent=0;vent<5;vent++)parts.add(geometry.part(center+.64,center+.98,x,x,.025,.025,.72+vent*.08,Material.DARK_STEEL,"sr:toll_booth_vent"));
            parts.add(geometry.part(center-.5,center+.12,x+side*.025,x+side*.025,.07,.34,1.03,Material.DARK_STEEL,"sr:toll_payment_panel"));
            parts.add(geometry.part(center-.42,center+.04,x+side*.065,x+side*.065,.015,.13,1.18,Material.LAMP,"sr:toll_payment_screen"));
          }
          parts.add(geometry.part(center-1.45,center-1.24,booth,booth,.18,.13,2.55,Material.LAMP,"sr:toll_booth_door_light"));
        }

      }
      // End interfaces and short booth approaches retain lane separation. The broad
      // fan in the middle intentionally has neither lane dividers nor arrows.
      for(boolean a:new boolean[]{true,false}){
        var port=band(s,a,dir);double taper=a?from:ls.current()-to;
        double endRun=Math.min(10,taper*.22),boothRun=Math.min(16,taper*.28);
        for(int k=1;k<port.lanes();k++)divider(paint,geometry,port.at((double)k/port.lanes()),a?0:ls.current()-endRun,a?endRun:ls.current());
        for(int k=1;k<core.lanes();k++)divider(paint,geometry,core.at((double)k/core.lanes()),a?from-boothRun:center+6,a?center-6:to+boothRun);
      }
    }
    if(!c.name().isEmpty())for(int side:nameSides(c)){
      double d=(from+to)/2+side*5.7,w=nameWidth(c,side),cx=nameCenter(c,side);
      // A shallow name panel rests on a continuous sill, with feet through the roof.
      parts.add(geometry.part(d-.09,d+.09,cx,cx,w,.14,c.clearHeight()+1.50,Material.DARK_STEEL,"sr:toll_model_name_sill"));
      parts.add(geometry.part(d-.055,d+.055,cx,cx,w,1.42,c.clearHeight()+1.64,Material.SIGN_WHITE,"sr:toll_model_name_panel"));
      for(double x:new double[]{cx-w*.35,cx+w*.35})parts.add(geometry.part(d-.22,d+.22,x,x,.12,.35,c.clearHeight()+1.30,Material.STEEL,"sr:toll_model_name_foot"));
    }
    return new Plan(s,ls,mesh,List.copyOf(parts),List.copyOf(paint),List.copyOf(units),from,to);
  }
  private static double laneAt(Spec s,int dir,int lane,double d,double from,double to,double length){
    double fraction=lane/(double)core(s.config(),dir).lanes(),middle=core(s.config(),dir).at(fraction);
    return d<from?lerp(band(s,true,dir).at(fraction),middle,smooth(d/from)):d>to?lerp(middle,band(s,false,dir).at(fraction),smooth((d-to)/(length-to))):middle;
  }
  private static double median(Spec spec,double d,double from,double to,double length){
    var c=spec.config();
    double m=core(c,0).inner()-RoadProfile.trafficSign(c.leftTraffic())*ISLAND/2;
    if(d<from){var s=TollPlaza.port(spec,true);return lerp(RoadProfile.layout(s,s.width()).medianCenter(),m,smooth(d/from));}
    if(d>to){var s=TollPlaza.port(spec,false);return lerp(m,RoadProfile.layout(s,s.width()).medianCenter(),smooth((d-to)/(length-to)));}return m;
  }
  private record Geometry(Spec spec,V axis,V left,double from,double to,double length){
    double station(V v){return v.sub(spec.a().position()).dot(axis);}
    V at(double d,double lateral,double up){var p=spec.a().position().add(axis.mul(d)).add(left.mul(lateral));return new V(p.x(),elevation(spec,d,from,to,length)+up,p.z());}
    Part part(double a,double b,double x,double y,double width,double height,double up,Material material,String model){return new Part(at(a,x,up),at(b,y,up),Math.min(8,width),height,false,material,left.mul(width/2),left.mul(width/2),model);}
  }
  private static void line(List<RoadSurface.Face> out,V a,V b,double width,int color){
    V side=b.sub(a).horizontalUnit().left().mul(width/2);out.add(new RoadSurface.Face(List.of(a.sub(side),b.sub(side),b.add(side),a.add(side)),color));
  }
  private static void divider(List<RoadSurface.Face> out,Geometry g,double lateral,double from,double to){
    // Four-metre dashes, clipped at BOTH zone boundaries; sample grade per metre.
    for(double dash=from;dash<to-1e-7;dash+=7)for(double d=dash;d<Math.min(dash+4,to)-1e-7;d+=1){
      double end=Math.min(d+1,Math.min(dash+4,to));
      line(out,g.at(d,lateral,.022),g.at(end,lateral,.022),.15,0xFAFAF0);
    }
  }
  public static List<Integer> nameSides(Config c){return c.twoWay()?(c.chargedDirections()==3?List.of(-1,1):List.of(c.charges(0)?-1:1)):List.of(c.reverse()?1:-1);}
  public static double nameCenter(Config c,int side){return core(c,c.twoWay()&&side>0?1:0).at(.5);}
  public static double nameWidth(Config c,int side){var band=core(c,c.twoWay()&&side>0?1:0);return Math.max(2,Math.min(Math.abs(band.outer()-band.inner())-.5,c.name().codePoints().mapToDouble(v->v<128?.96:1.44).sum()+.6));}
  public static double nameWidth(Config c){return nameWidth(c,nameSides(c).get(0));}
  private static void glyph(List<Part> parts,Geometry g,double d,double x,double up,String text,int flow){
    parts.add(g.part(d-flow*.025,d+flow*.035,x,x,text.length()*.40-.12,.48,up,Material.SIGN_WHITE,"sr:toll_model_label_"+text));
  }
  private TollPlanner(){}
}
