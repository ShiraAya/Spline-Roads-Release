package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Real-block sidewalk footprint, shared by preview and server placement. */
public final class RoadSidewalks {
  public enum Side { BOTH("双侧"), LEFT("左侧"), RIGHT("右侧"); public final String label;Side(String label){this.label=label;} }
  public enum Finish {
    STONE_BRICKS("石砖","stone_bricks"), BRICKS("红砖","bricks"), ANDESITE("磨制安山岩","polished_andesite"), DIORITE("磨制闪长岩","polished_diorite"), GRANITE("磨制花岗岩","polished_granite"), SMOOTH_STONE("平滑石头","smooth_stone");
    public final String label,id;Finish(String label,String path){this.label=label;id="minecraft:"+path;}
    public static Finish of(String id){return Arrays.stream(values()).filter(f->f.id.equals(id)).findFirst().orElse(STONE_BRICKS);}
    public RoadStructures.Material material(){return RoadStructures.Material.valueOf("WALK_"+name());}
  }
  public record Config(boolean enabled,Side side,int width,String material,boolean smooth,boolean tactile) {
    public Config(boolean enabled,Side side,int width,String material,boolean smooth){this(enabled,side,width,material,smooth,true);}
    public Config tactile(boolean v){return new Config(enabled,side,width,material,smooth,v);}
    public Config(boolean enabled,Side side,int width,String material){this(enabled,side,width,material,true);}
    public Config smooth(boolean v){return new Config(enabled,side,width,material,v,tactile);}
    public static final Config DEFAULT=new Config(false,Side.BOTH,5,"minecraft:stone_bricks");
    public Config {if(smooth)material=Finish.of(material).id;if(side==null||width<1||width>15||material==null||!material.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("人行道宽度须为 1–15 格，材质须为有效的命名空间:方块名");}
    public Config enabled(boolean v){return new Config(v,side,width,material,true,tactile);}
    public Config side(Side v){return new Config(enabled,v,width,material,true,tactile);}
    public Config width(int v){return new Config(enabled,side,v,material,true,tactile);}
    public Config material(String v){return new Config(enabled,side,width,v,true,tactile);}
  }
  /** Exact local anchor lookup for retained border infill. A long road's complete
   * furniture list must not be scanned again for every border block. */
  public static final class PavingAnchors {
    private final Map<Long,List<V>> cells=new HashMap<>();
    public PavingAnchors(Collection<RoadStructures.Part> parts){
      for(var p:parts)if(p.material().name().startsWith("WALK_"))
        cells.computeIfAbsent(gridKey((int)Math.floor(p.a().x()/16),(int)Math.floor(p.a().z()/16)),k->new ArrayList<>()).add(p.a());
    }
    public boolean near(V point,double radius){
      for(int x=(int)Math.floor((point.x()-radius)/16);x<=(int)Math.floor((point.x()+radius)/16);x++)
        for(int z=(int)Math.floor((point.z()-radius)/16);z<=(int)Math.floor((point.z()+radius)/16);z++)
          for(var a:cells.getOrDefault(gridKey(x,z),List.of()))if(a.sub(point).horizontalLength()<radius)return true;
      return false;
    }
  }
  public static boolean smoothPart(RoadStructures.Part p){return p.material().name().startsWith("WALK_")||p.material()==RoadStructures.Material.TACTILE;}
  public static List<RoadStructures.Part> parts(Mesh mesh,Config config){return parts(mesh,config,null,List.of());}
  public static List<RoadStructures.Part> parts(Mesh mesh,Config config,RoadStructures.Ground ground,List<RoadStructures.Part> obstacles){
    if(!hasWalk(mesh,config)||!config.smooth()||mesh.settings().structure()==Structure.TUNNEL||mesh.settings().style().ramp()||RoadProfile.catalog(mesh.settings().style()).type()==RoadProfile.Type.HIGHWAY)return List.of();
    var out=new ArrayList<RoadStructures.Part>();var samples=mesh.samples();
    var grid=new HashMap<Long,List<RoadStructures.Part>>();
    var bounds=new IdentityHashMap<RoadStructures.Part,double[]>();
    for(var p:obstacles){if(smoothPart(p))continue;var base=p.base();double minX=base.stream().mapToDouble(V::x).min().orElseThrow(),maxX=base.stream().mapToDouble(V::x).max().orElseThrow(),minZ=base.stream().mapToDouble(V::z).min().orElseThrow(),maxZ=base.stream().mapToDouble(V::z).max().orElseThrow();
      bounds.put(p,new double[]{minX,maxX,minZ,maxZ});
      for(int x=(int)Math.floor(minX/16);x<=(int)Math.floor(maxX/16);x++)for(int z=(int)Math.floor(minZ/16);z<=(int)Math.floor(maxZ/16);z++)grid.computeIfAbsent(gridKey(x,z),k->new ArrayList<>()).add(p);
    }
    for(int side:new int[]{-1,1})if(sideUsed(mesh,config,side)){
      double[] offsets=new double[samples.size()];
      for(int i=0;i<samples.size();i++){
        RoadPlanningBudget.check();
        var sample=samples.get(i);double target=RoadStreetscape.tactileOffset(config.width());V edge=sample.at(side*sample.halfWidth(),0),outward=sample.left().mul(side),forward=sample.left().left();
        var near=new HashSet<RoadStructures.Part>();V outer=edge.add(outward.mul(config.width()));
        for(int x=(int)Math.floor(Math.min(edge.x(),outer.x())/16)-1;x<=(int)Math.floor(Math.max(edge.x(),outer.x())/16)+1;x++)for(int z=(int)Math.floor(Math.min(edge.z(),outer.z())/16)-1;z<=(int)Math.floor(Math.max(edge.z(),outer.z())/16)+1;z++)near.addAll(grid.getOrDefault(gridKey(x,z),List.of()));
        for(var p:near){
          double low=Math.min(p.a().y(),p.b().y())-p.verticalFrame(),high=Math.max(p.a().y(),p.b().y())+p.height()+p.verticalFrame();if(high<=edge.y()+.24||low>=edge.y()+2.1)continue;
          // A height slice stays inside the solid's XZ bounds. Reject distant
          // longitudinal slices before constructing/projecting its twelve edges.
          var box=bounds.get(p);
          double minAlong=((forward.x()>=0?box[0]:box[1])-edge.x())*forward.x()+((forward.z()>=0?box[2]:box[3])-edge.z())*forward.z();
          double maxAlong=((forward.x()>=0?box[1]:box[0])-edge.x())*forward.x()+((forward.z()>=0?box[3]:box[2])-edge.z())*forward.z();
          if(minAlong>.80000001||maxAlong<-.80000001)continue;
          double alongLo=Double.POSITIVE_INFINITY,alongHi=-alongLo,lateralLo=alongLo,lateralHi=-alongLo;
          for(V v:obstacleSlice(p,edge.y()+.24,edge.y()+2.1)){V d=v.sub(edge);double l=d.dot(outward),a=d.dot(forward);alongLo=Math.min(alongLo,a);alongHi=Math.max(alongHi,a);lateralLo=Math.min(lateralLo,l);lateralHi=Math.max(lateralHi,l);}
          if(alongLo<=.8&&alongHi>=-.8&&lateralLo<=target+.5&&lateralHi>=target-.5)target=Math.max(target,lateralHi+.55);
        }offsets[i]=target;
      }
      // Nearby anchor bases form one detour, not a repeating sawtooth between every hanger.
      double normal=RoadStreetscape.tactileOffset(config.width());int last=-1;
      for(int i=0;i<offsets.length;i++)if(offsets[i]>normal+.02){
        if(last>=0&&samples.get(i).distance()-samples.get(last).distance()<14){double v=Math.max(offsets[last],offsets[i]);for(int j=last;j<=i;j++)offsets[j]=Math.max(offsets[j],v);}last=i;
      }
      // Smooth approach/departure, retaining clearance at every obstructed station.
      for(int i=1;i<offsets.length;i++)offsets[i]=Math.max(offsets[i],offsets[i-1]-.3*(samples.get(i).distance()-samples.get(i-1).distance()));
      for(int i=offsets.length-2;i>=0;i--)offsets[i]=Math.max(offsets[i],offsets[i+1]-.3*(samples.get(i+1).distance()-samples.get(i).distance()));
      for(int i=1;i<samples.size();i++){var a=samples.get(i-1);var b=samples.get(i);V na=a.left().mul(side),nb=b.left().mul(side);
        double wa=walkWidth(mesh,config,a,side),wb=walkWidth(mesh,config,b,side);
        double da=Math.max(0,offsets[i-1]-normal),db=Math.max(0,offsets[i]-normal);
        double ta=tactileScale(mesh,config,a,side),tb=tactileScale(mesh,config,b,side);
        wa+=da;wb+=db;
        strip(out,a.at(side*a.halfWidth(),0),b.at(side*b.halfWidth(),0),na,nb,config,RoadStreetscape.tactileOffset(wa),RoadStreetscape.tactileOffset(wb),wa,wb,ta,tb);
        if(ground!=null&&mesh.settings().options().outerRail()!=RoadProfile.OuterRail.OFF){
          V aa=a.at(side*(a.halfWidth()+wa-.12),0),bb=b.at(side*(b.halfWidth()+wb-.12),0),mid=aa.add(bb).mul(.5);
          double floor=ground.top(mid.x(),mid.z(),mid.y()+.2);boolean raised=mesh.settings().structure()==Structure.BRIDGE||mesh.settings().structure()!=Structure.GROUND&&(!Double.isFinite(floor)||mid.y()+.2-floor>.35);
          if(raised&&Math.min(wa,wb)>.3&&!ground.joined(mid)){
            for(double y:new double[]{.65,1.3})out.add(new RoadStructures.Part(aa.add(new V(0,y,0)),bb.add(new V(0,y,0)),.12,.12,false,RoadStructures.Material.STEEL).frames(na.mul(.06),nb.mul(.06)));
            if((int)Math.floor(a.distance()/3.5)!=(int)Math.floor(b.distance()/3.5)||i==1)out.add(new RoadStructures.Part(aa.add(new V(0,.2,0)),aa.add(new V(0,.2,0)),.14,1.22,true,RoadStructures.Material.STEEL));
          }
        }
      }
    }
    walkwayTerminals(out);return List.copyOf(out);
  }
  /** Shared edge samples give a variable-width footway the same continuous boundary as its road. */
  static List<RoadStructures.Part> variableParts(Mesh mesh,Config config,List<Double> widths){
    if(widths.size()!=mesh.samples().size())throw new IllegalArgumentException("footway sample count");var out=new ArrayList<RoadStructures.Part>();
    for(int i=1;i<mesh.samples().size();i++){var a=mesh.samples().get(i-1);var b=mesh.samples().get(i);double wa=widths.get(i-1),wb=widths.get(i);
      strip(out,a.at(a.halfWidth(),0),b.at(b.halfWidth(),0),a.left(),b.left(),config,0,0,wa,wb,0,0);
    }return List.copyOf(out);
  }
  private static Config endWalk(RoadTransitions.Section s,Config fallback){return s==null||s.sidewalk()==null?fallback:s.sidewalk();}
  private static boolean hasWalk(Mesh m,Config c){var e=m.settings().options().ends();return c.enabled()||endWalk(e.start(),c).enabled()||endWalk(e.end(),c).enabled();}
  private static boolean sideUsed(Mesh m,Config c,int side){var e=m.settings().options().ends();return enabled(c,side)||enabled(endWalk(e.start(),c),side)||enabled(endWalk(e.end(),c),side);}
  private static double walkValue(Config c,int side,boolean tactile){return enabled(c,side)?tactile?(c.tactile()&&c.width()>=2?1:0):c.width():0;}
  private static double walkBlend(Mesh m,Config c,Sample s,int side,boolean tactile){
    var e=m.settings().options().ends();double span=Math.min(24,m.length()*.45),base=walkValue(c,side,tactile);
    double a=RoadTransitions.endWeight(m.settings(),s.distance()+e.trimmedStart(),m.length()+e.trimmedStart()+e.trimmedEnd(),true,Math.max(1,span));
    double b=RoadTransitions.endWeight(m.settings(),m.length()-s.distance()+e.trimmedEnd(),m.length()+e.trimmedStart()+e.trimmedEnd(),false,Math.max(1,span));
    return base+(walkValue(endWalk(e.start(),c),side,tactile)-base)*a+(walkValue(endWalk(e.end(),c),side,tactile)-base)*b;
  }
  public static double walkWidth(Mesh m,Config c,Sample s,int side){return walkBlend(m,c,s,side,false);}
  public static double tactileScale(Mesh m,Config c,Sample s,int side){return walkBlend(m,c,s,side,true);}
  private static void walkwayTerminals(List<RoadStructures.Part> out){
    var endpoints=new LinkedHashMap<V,Integer>();
    for(var p:out)if(p.material()==RoadStructures.Material.STEEL&&!p.pier()&&p.width()==.12&&p.height()==.12){endpoints.merge(p.a(),1,Integer::sum);endpoints.merge(p.b(),1,Integer::sum);}
    for(var e:endpoints.entrySet())if(e.getValue()==1){V high=e.getKey();boolean lower=endpoints.keySet().stream().anyMatch(v->v.distance(high.add(new V(0,-.65,0)))<1e-6);if(lower){V base=high.add(new V(0,-1.1,0));if(out.stream().noneMatch(p->p.pier()&&p.a().distance(base)<.1))out.add(new RoadStructures.Part(base,base,.14,1.22,true,RoadStructures.Material.STEEL));}}
  }
  private static List<V> obstacleSlice(RoadStructures.Part p,double low,double high){
    var base=p.base();var out=new ArrayList<V>();
    // The solid has four vertical edges and horizontal/sloping top and bottom edges.
    for(int i=0;i<4;i++){
      V a=base.get(i),b=base.get((i+1)%4);
      sliceEdge(out,a,a.add(new V(0,p.height(),0)),low,high);
      sliceEdge(out,a,b,low,high);sliceEdge(out,a.add(new V(0,p.height(),0)),b.add(new V(0,p.height(),0)),low,high);
    }return out;
  }
  private static void sliceEdge(List<V> out,V a,V b,double low,double high){
    if(a.y()>=low&&a.y()<=high)out.add(a);if(b.y()>=low&&b.y()<=high)out.add(b);
    if(Math.abs(b.y()-a.y())<1e-9)return;
    for(double y:new double[]{low,high}){double t=(y-a.y())/(b.y()-a.y());if(t>0&&t<1)out.add(a.add(b.sub(a).mul(t)));}
  }
  private static V cornerNormal(List<V> boundary,int index,double sign){
    int n=boundary.size();V p=boundary.get((index+n-1)%n),q=boundary.get(index),r=boundary.get((index+1)%n);
    V a=q.sub(p).horizontalUnit().left().mul(sign),b=r.sub(q).horizontalUnit().left().mul(sign);
    double den=1+a.dot(b);if(den<.5)return b;
    return a.add(b).mul(1/den);
  }
  private static long gridKey(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
  private static void strip(List<RoadStructures.Part> out,V a,V b,V na,V nb,Config c){
    strip(out,a,b,na,nb,c,RoadStreetscape.tactileOffset(c.width()),RoadStreetscape.tactileOffset(c.width()));
  }
  private static void strip(List<RoadStructures.Part> out,V a,V b,V na,V nb,Config c,double xa,double xb){
    strip(out,a,b,na,nb,c,xa,xb,c.width(),c.width(),c.tactile()?1:0,c.tactile()?1:0);
  }
  private static void strip(List<RoadStructures.Part> out,V a,V b,V na,V nb,Config c,double xa,double xb,double wa,double wb,double ta,double tb){
    if(a.sub(b).horizontalLength()<1e-6||Math.max(wa,wb)<.01)return;
    for(double x=0;x<Math.max(wa,wb);x+=5){double aw=Math.max(0,Math.min(5,wa-x)),bw=Math.max(0,Math.min(5,wb-x)),w=Math.max(aw,bw);
      out.add(new RoadStructures.Part(a.add(na.mul(Math.min(x,wa)+aw/2)).add(new V(0,-.3,0)),b.add(nb.mul(Math.min(x,wb)+bw/2)).add(new V(0,-.3,0)),w,.5,false,Finish.of(c.material()).material()).frames(na.mul(aw/2),nb.mul(bw/2)));
    }
    if(Math.min(wa,wb)>=2&&Math.max(ta,tb)>.01&&xa<=wa-.45&&xb<=wb-.45){
      out.add(new RoadStructures.Part(a.add(na.mul(xa)).add(new V(0,.2,0)),b.add(nb.mul(xb)).add(new V(0,.2,0)),.6,.015,false,RoadStructures.Material.TACTILE).frames(na.mul(.3*ta),nb.mul(.3*tb)));
      for(double ridge:new double[]{-.18,0,.18})out.add(new RoadStructures.Part(a.add(na.mul(xa+ridge*ta)).add(new V(0,.215,0)),b.add(nb.mul(xb+ridge*tb)).add(new V(0,.215,0)),.06,.02,false,RoadStructures.Material.TACTILE).frames(na.mul(.03*ta),nb.mul(.03*tb)));
    }
  }
  public static List<RoadStructures.Part> junctionParts(JunctionSpec spec,List<Mesh> approaches,List<V> boundary){return junctionParts(spec,approaches,boundary,true);}
  public static List<RoadStructures.Part> junctionParts(JunctionSpec spec,List<Mesh> approaches,List<V> boundary,boolean continuous){
    return junctionParts(spec,approaches,boundary,continuous,false);
  }
  public static List<RoadStructures.Part> junctionParts(JunctionSpec spec,List<Mesh> approaches,List<V> boundary,boolean continuous,boolean rounded){
    return junctionParts(spec,approaches,boundary,continuous,rounded,false);
  }
  public static List<RoadStructures.Part> junctionParts(JunctionSpec spec,List<Mesh> approaches,List<V> boundary,boolean continuous,boolean rounded,boolean insetBend){
    var out=new ArrayList<RoadStructures.Part>();double area=0;
    for(int i=0;i<boundary.size();i++){V a=boundary.get(i),b=boundary.get((i+1)%boundary.size());area+=a.x()*b.z()-b.x()*a.z();}
    double sign=area>=0?-1:1;
    for(int i=0;i<boundary.size();i++){
      V a=boundary.get(i),b=boundary.get((i+1)%boundary.size()),mid=a.add(b).mul(.5);
      if(a.distance(b)<1e-8||approaches.stream().anyMatch(m->RoadQueries.contains(m,mid,.02,.1)))continue;
      Config c=null;double best=Double.POSITIVE_INFINITY;
      for(int arm=0;arm<approaches.size();arm++){var mouth=approaches.get(arm).last();int side=mid.sub(mouth.center()).dot(mouth.left())<0?-1:1;double d=mid.distance(mouth.at(side*mouth.halfWidth(),0));
        if(d<best){best=d;var candidate=spec.arms().get(arm).external().options().sidewalk();c=enabled(candidate,side)&&candidate.smooth()?candidate:null;}}
      if(c!=null){V normal=b.sub(a).horizontalUnit().left().mul(sign);strip(out,a,b,continuous?cornerNormal(boundary,i,sign):normal,continuous?cornerNormal(boundary,(i+1)%boundary.size(),sign):normal,rounded?c.tactile(false):c);}
    }if(rounded)out.addAll(TactilePaths.junction(spec,approaches,boundary,insetBend));return List.copyOf(out);
  }
  private record WalkEnd(Config config,int side,V normal){
    double width(){return enabled(config,side)&&config.smooth()?config.width():0;}
  }
  private static WalkEnd cornerEnd(JunctionSpec spec,List<Mesh> approaches,V point){
    WalkEnd result=null;double best=Double.POSITIVE_INFINITY;
    for(int i=0;i<approaches.size();i++)for(int side:new int[]{-1,1}){
      var m=approaches.get(i).last();double d=m.at(side*m.halfWidth(),0).distance(point);
      if(d<best){best=d;result=new WalkEnd(spec.arms().get(i).external().options().sidewalk(),side,m.left().mul(side));}
    }return result;
  }
  /** Insert exact deck/outer-circle intersections before classifying open curb spans. */
  public static List<V> splitBoundary(List<V> boundary,List<Mesh> approaches){
    var result=new ArrayList<V>();
    for(int i=0;i<boundary.size();i++){
      V a=boundary.get(i),b=boundary.get((i+1)%boundary.size()),u=b.sub(a);var cuts=new TreeSet<Double>();cuts.add(0.0);
      for(var mesh:approaches)for(int j=1;j<mesh.samples().size();j++){
        var p=mesh.samples().get(j-1);var q=mesh.samples().get(j);
        for(int side:new int[]{-1,1}){
          V c=p.at(side*p.halfWidth(),0),d=q.at(side*q.halfWidth(),0),v=d.sub(c),w=c.sub(a);
          double den=u.x()*v.z()-u.z()*v.x();if(Math.abs(den)<1e-9)continue;
          double t=(w.x()*v.z()-w.z()*v.x())/den,r=(w.x()*u.z()-w.z()*u.x())/den;
          if(t>1e-8&&t<1-1e-8&&r>=-1e-8&&r<=1+1e-8)cuts.add(t);
        }
      }
      for(double t:cuts)result.add(a.add(u.mul(t)));
    }return List.copyOf(result);
  }
  public static List<V> closeCorner(JunctionSpec spec,List<Mesh> approaches,List<V> edge){
    if(edge.size()<2)return edge;var result=new ArrayList<>(edge);
    for(boolean start:new boolean[]{true,false}){
      int at=start?0:result.size()-1;V point=result.get(at);V closest=null;double best=Double.POSITIVE_INFINITY;
      for(var m:approaches)for(int side:new int[]{-1,1}){V p=m.last().at(side*m.last().halfWidth(),0);double distance=p.distance(point);if(distance<best){best=distance;closest=p;}}
      if(best>.00001&&best<4){if(start)result.add(0,closest);else result.add(closest);}
    }return List.copyOf(result);
  }
  public static List<RoadStructures.Part> taperedJunctionParts(JunctionSpec spec,List<Mesh> approaches,List<V> boundary){
    var out=new ArrayList<RoadStructures.Part>();int n=boundary.size();if(n<3)return out;
    boolean[] open=new boolean[n];int start=-1;double sign=JunctionPaint.area(boundary)>=0?-1:1;
    for(int i=0;i<n;i++){
      V a=boundary.get(i),b=boundary.get((i+1)%n),mid=a.add(b).mul(.5);
      open[i]=a.distance(b)>1e-8&&approaches.stream().noneMatch(m->RoadQueries.contains(m,mid,.02,.1));
      if(!open[i])start=i;
    }
    if(start<0)return junctionParts(spec,approaches,boundary,true,true,true);
    var run=new ArrayList<V>();
    for(int k=1;k<=n;k++){
      int i=(start+k)%n;
      if(open[i]){if(run.isEmpty())run.add(boundary.get(i));run.add(boundary.get((i+1)%n));}
      else if(!run.isEmpty()){taperCorner(out,spec,approaches,run,sign);run.clear();}
    }
    if(!run.isEmpty())taperCorner(out,spec,approaches,run,sign);
    out.addAll(TactilePaths.junction(spec,approaches,boundary,true));return List.copyOf(out);
  }
  private static void taperCorner(List<RoadStructures.Part> out,JunctionSpec spec,List<Mesh> approaches,List<V> edge,double sign){
    edge=closeCorner(spec,approaches,edge);
    if(edge.size()<2)return;var first=cornerEnd(spec,approaches,edge.get(0));var last=cornerEnd(spec,approaches,edge.get(edge.size()-1));
    double a=first.width(),b=last.width();if(a==0&&b==0)return;
    // End a full-width corner against the unpaved arm; tapering to zero made a
    // pinched, uncovered triangle between the road and the surviving sidewalk.
    if(a==0)a=b;else if(b==0)b=a;
    double length=0;for(int i=1;i<edge.size();i++)length+=edge.get(i).distance(edge.get(i-1));
    var normals=new ArrayList<V>();double[] widths=new double[edge.size()];double station=0;
    for(int i=0;i<edge.size();i++){
      if(i>0)station+=edge.get(i).distance(edge.get(i-1));
      double t=Settings.smooth(station/Math.max(1e-9,length));widths[i]=a+(b-a)*t;
      normals.add(i==0?first.normal():i==edge.size()-1?last.normal():cornerNormal(edge,i,sign));
    }
    for(int i=1;i<edge.size();i++){
      var config=(first.width()>0&&(last.width()==0||i*2<edge.size())?first:last).config().tactile(false);
      strip(out,edge.get(i-1),edge.get(i),normals.get(i-1),normals.get(i),config,0,0,widths[i-1],widths[i],0,0);
    }
  }
  static List<RoadStructures.Part> cornerPaving(JunctionSpec spec,List<Mesh> approaches,List<V> edge,double sign){
    var out=new ArrayList<RoadStructures.Part>();taperCorner(out,spec,approaches,edge,sign);return out;
  }
  public static boolean blocksTactile(RoadStructures.Part part,Mesh mesh){
    Config c=mesh.settings().options().sidewalk();if(!c.enabled()||!c.tactile()||c.width()<2)return false;
    V mid=part.a().add(part.b()).mul(.5);var q=RoadQueries.horizontal(mesh,mid);double y=q.sample().center().y();
    if(part.a().y()+part.height()<y+.19||part.a().y()>y+2.1||q.horizontalDistance()>q.sample().halfWidth()+c.width()+part.width())return false;
    double lo=Double.POSITIVE_INFINITY,hi=-lo;
    for(V v:part.base()){double d=v.sub(q.sample().center()).dot(q.sample().left());lo=Math.min(lo,d);hi=Math.max(hi,d);}
    for(int side:new int[]{-1,1})if(enabled(c,side)){
      double center=side*(q.sample().halfWidth()+RoadStreetscape.tactileOffset(c.width()));
      if(lo<center+.5&&hi>center-.5)return true;
    }return false;
  }
  public static boolean overlapsDeck(RoadStructures.Part part,Mesh mesh){
    var poly=part.base();double area=Math.abs(JunctionPaint.area(poly));if(area<1e-10)return false;
    double x0=poly.stream().mapToDouble(V::x).min().orElseThrow(),x1=poly.stream().mapToDouble(V::x).max().orElseThrow(),z0=poly.stream().mapToDouble(V::z).min().orElseThrow(),z1=poly.stream().mapToDouble(V::z).max().orElseThrow();
    for(int i=1;i<mesh.samples().size();i++){var a=mesh.samples().get(i-1);var b=mesh.samples().get(i);var quad=List.of(a.at(a.halfWidth(),0),a.at(-a.halfWidth(),0),b.at(-b.halfWidth(),0),b.at(b.halfWidth(),0));
      if(quad.stream().mapToDouble(V::x).max().orElseThrow()<=x0+1e-7||quad.stream().mapToDouble(V::x).min().orElseThrow()>=x1-1e-7||quad.stream().mapToDouble(V::z).max().orElseThrow()<=z0+1e-7||quad.stream().mapToDouble(V::z).min().orElseThrow()>=z1-1e-7)continue;
      if(area-RoadSurface.subtract(poly,quad).stream().mapToDouble(v->Math.abs(JunctionPaint.area(v))).sum()>1e-6)return true;
    }return false;
  }
  public record Cell(int x,int y,int z) {}
  private record Key(Mesh mesh,Config config){}
  private static final Map<Key,List<Cell>> CACHE=new LinkedHashMap<>(8,.75f,true){protected boolean removeEldestEntry(Map.Entry<Key,List<Cell>> e){return size()>12;}};
  public static synchronized List<Cell> cells(Mesh mesh,Config config){
    if(!config.enabled() || mesh.settings().style().ramp() || RoadProfile.catalog(mesh.settings().style()).type()==RoadProfile.Type.HIGHWAY)return List.of();return CACHE.computeIfAbsent(new Key(mesh,config),k->build(k.mesh(),k.config()));
  }
  private static List<Cell> build(Mesh mesh,Config config){
    var result=new LinkedHashSet<Cell>();
    for(int i=1;i<mesh.samples().size();i++) {
      Sample a=mesh.samples().get(i-1),b=mesh.samples().get(i);
      for(int side:new int[]{-1,1}) {
        // Sample.left is Minecraft's right, as used by the road profile.
        if(config.side()==Side.LEFT&&side>0||config.side()==Side.RIGHT&&side<0)continue;
        var p=List.of(a.at(side*a.halfWidth(),0),a.at(side*(a.halfWidth()+config.width()),0),b.at(side*(b.halfWidth()+config.width()),0),b.at(side*b.halfWidth(),0));
        raster(p,a.center(),b.center(),cell->{
          V at=new V(cell.x()+.5,0,cell.z()+.5);
          // Keep the existing end ownership; widen only the lateral footprint.
          if(a.distance()<1.5 && at.sub(mesh.first().center()).dot(mesh.first().left().left().mul(-1)) < -1e-7)return;
          if(b.distance()>mesh.length()-1.5 && at.sub(mesh.last().center()).dot(mesh.last().left().left().mul(-1)) >= -1e-7)return;
          result.add(cell);
        });
      }
    }
    return List.copyOf(result);
  }
  /** Curbs between approach mouths need their own sidewalk, including roundabouts. */
  public static Map<Cell,Config> junctionCells(JunctionSpec spec,List<Mesh> approaches,List<V> boundary) {
    if(spec.arms().stream().noneMatch(a->a.external().options().sidewalk().enabled()))return Map.of();
    var result=new LinkedHashMap<Cell,Config>();double area=0;
    for(int i=0;i<boundary.size();i++){V a=boundary.get(i),b=boundary.get((i+1)%boundary.size());area+=a.x()*b.z()-b.x()*a.z();}
    double sign=area>=0?-1:1;
    for(int i=0;i<boundary.size();i++) {
      V a=boundary.get(i),b=boundary.get((i+1)%boundary.size()),middle=a.add(b).mul(.5);
      if(a.distance(b)<1e-8 || approaches.stream().anyMatch(m->RoadQueries.contains(m,middle,.02,.1)))continue;
      V normal=b.sub(a).horizontalUnit().left().mul(sign);
      Config config=null;double best=Double.POSITIVE_INFINITY;
      for(int arm=0;arm<approaches.size();arm++) {
        Sample mouth=approaches.get(arm).last();
        int side=middle.sub(mouth.center()).dot(mouth.left())<0?-1:1;
        double distance=middle.distance(mouth.at(side*mouth.halfWidth(),0));
        if(distance<best){best=distance;Config c=spec.arms().get(arm).external().options().sidewalk();config=enabled(c,side)?c:null;}
      }
      if(config==null)continue;
      Config chosen=config;
      // Tiny tangential overlap closes joins between offset segments on convex bends.
      V along=b.sub(a).horizontalUnit().mul(.02);
      var polygon=List.of(a.sub(along),b.add(along),b.add(along).add(normal.mul(config.width())),a.sub(along).add(normal.mul(config.width())));
      raster(polygon,a,b,c->result.putIfAbsent(c,chosen));
    }
    return Collections.unmodifiableMap(result);
  }
  private static boolean enabled(Config c,int side){return c.enabled()&&(c.side()==Side.BOTH||c.side()==Side.LEFT&&side<0||c.side()==Side.RIGHT&&side>0);}
  static void raster(List<V> polygon,V a,V b,java.util.function.Consumer<Cell> out) {
    int x0=(int)Math.floor(polygon.stream().mapToDouble(V::x).min().orElseThrow()),x1=(int)Math.ceil(polygon.stream().mapToDouble(V::x).max().orElseThrow())-1;
    int z0=(int)Math.floor(polygon.stream().mapToDouble(V::z).min().orElseThrow()),z1=(int)Math.ceil(polygon.stream().mapToDouble(V::z).max().orElseThrow())-1;
    V d=b.sub(a);double square=d.x()*d.x()+d.z()*d.z();
    for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++) {
      if(!overlaps(polygon,x,z))continue;
      double t=Math.max(0,Math.min(1,((x+.5-a.x())*d.x()+(z+.5-a.z())*d.z())/Math.max(1e-9,square)));
      // Whole blocks remain at or below the pavement, also on inclined approaches.
      out.accept(new Cell(x,(int)Math.floor(a.y()+d.y()*t+1e-6)-1,z));
    }
  }
  /** Positive area, not merely a center-point hit or a touching bounding box. */
  private static boolean overlaps(List<V> polygon,int x,int z) {
    List<V> p=polygon;
    for(int edge=0;edge<4;edge++) {
      var next=new ArrayList<V>();if(p.isEmpty())return false;
      double limit=edge==0?x:edge==1?x+1:edge==2?z:z+1;boolean horizontal=edge<2,lower=edge%2==0;
      V prev=p.get(p.size()-1);double pv=(horizontal?prev.x():prev.z())-limit;boolean pin=lower?pv>=0:pv<=0;
      for(V at:p){double v=(horizontal?at.x():at.z())-limit;boolean in=lower?v>=0:v<=0;
        if(in!=pin){double t=pv/(pv-v);next.add(prev.add(at.sub(prev).mul(t)));}if(in)next.add(at);prev=at;pv=v;pin=in;}
      p=next;
    }
    double area=0;for(int i=0;i<p.size();i++){V a=p.get(i),b=p.get((i+1)%p.size());area+=(a.x()-x)*(b.z()-z)-(b.x()-x)*(a.z()-z);}
    return Math.abs(area)>1e-7;
  }
  public static List<RoadSurface.Face> preview(Collection<Cell> cells) {
    return cells.stream().map(c->{V a=new V(c.x(),c.y()+1.01,c.z());return new RoadSurface.Face(List.of(a,a.add(new V(0,0,1)),a.add(new V(1,0,1)),a.add(new V(1,0,0))),0x9DE69A);}).toList();
  }
  private RoadSidewalks(){}
}
