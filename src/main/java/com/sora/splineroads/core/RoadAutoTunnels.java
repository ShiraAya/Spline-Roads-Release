package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Terrain structure is local and independent of the connector's OVER/UNDER route.
 * Persist roofed and open-cut ranges with their solids; rendering never queries terrain. */
public final class RoadAutoTunnels {
  private static final String PREFIX="sr:auto_tunnel:",OPEN="sr:auto_cut:",PROFILE="sr:auto_roof:";
  public static final double HEADROOM=6,MIN_COVER=7.05,WALL_LIP=.5,ROOF_THICKNESS=1.05,CANOPY=4;
  public static final double LINING=1.55,WALL_WIDTH=1.6;
  public static final double MIN_CUT_DEPTH=2;
  public static double minCutDepth(Mesh mesh){return mesh.settings().style()==Style.RAILBED?1:MIN_CUT_DEPTH;}
  // Auto portals may rise well above an ordinary 16 m tunnel. The writer and
  // reader share the same structural limit; excessive terrain is rejected.
  public static final double MIN_ROOF_HEIGHT=4,MAX_ROOF_HEIGHT=RoadStructures.MAX_DROP;
  private static boolean validRoofHeight(double height){return Double.isFinite(height)&&height>=MIN_ROOF_HEIGHT&&height<=MAX_ROOF_HEIGHT;}
  private static String roofProfile(double from,double to,double first,double last){
    if(!RoadGeometry.finite(from,to)||from<0||to<=from||!validRoofHeight(first)||!validRoofHeight(last))
      throw new IllegalArgumentException("自动隧道洞口高度超出可支撑范围（4–"+(int)MAX_ROOF_HEIGHT+" 格），请调整道路高程或洞口位置");
    return PROFILE+from+":"+to+":"+first+":"+last;
  }
  public record RoofRegion(double from,double to,double first,double last){}
  public static List<RoofRegion> roofRegions(List<Part> parts){
    var out=new LinkedHashSet<RoofRegion>();
    for(var p:parts)if(p.material()==Material.TUNNEL&&p.model().startsWith(PROFILE)){
      var v=parse(p.model(),PROFILE,4);if(v!=null&&validRoofHeight(v[2])&&validRoofHeight(v[3]))out.add(new RoofRegion(v[0],v[1],v[2],v[3]));
    }
    return List.copyOf(out);
  }
  public record Region(double from,double to){}
  public record OpenRegion(double from,double to,double clearance){}
  /** Actual wall caps, including open cuts. Excavation clearance is not wall height. */
  public static java.util.function.DoubleUnaryOperator wallCeiling(Mesh mesh,List<Part> parts){
    var spans=new TreeMap<Double,double[]>();
    for(var p:parts)if(p.material()==Material.TUNNEL&&p.model().startsWith(OPEN)){
      double[] tag=parse(p.model(),OPEN,3);if(tag==null)continue;
      var a=RoadStructures.sample(mesh,tag[0]);var b=RoadStructures.sample(mesh,tag[1]);
      var top=p.top();double ha=(top.get(0).y()+top.get(1).y())/2-a.center().y(),hb=(top.get(2).y()+top.get(3).y())/2-b.center().y();
      var old=spans.get(tag[0]);if(old==null)spans.put(tag[0],new double[]{tag[1],ha,hb});
      else {old[1]=Math.max(old[1],ha);old[2]=Math.max(old[2],hb);}
    }
    return d->{var e=spans.floorEntry(d+1e-7);if(e==null||d>e.getValue()[0]+1e-7)return 0;
      var v=e.getValue();double t=Math.max(0,Math.min(1,(d-e.getKey())/(v[0]-e.getKey())));return v[1]*(1-t)+v[2]*t;};
  }
  public record Section(double from,double to,boolean roof,double leftDepth,double rightDepth,double clearance,boolean surface){
    public Section(double from,double to,boolean roof,double leftDepth,double rightDepth,double clearance){this(from,to,roof,leftDepth,rightDepth,clearance,false);}
  }
  public static final double MAX_SHORT_STRUCTURE=24;
  private record Run(int from,int to,int kind){}
  /** Only bounded short islands change type. Long intervals and both road ends are stable. */
  static int[] normalize(int[] original){
    int[] kinds=original.clone();
    for(int pass=0;pass<original.length;pass++){
      var runs=new ArrayList<Run>();for(int i=0;i<kinds.length;){int j=i+1;while(j<kinds.length&&kinds[j]==kinds[i])j++;runs.add(new Run(i,j,kinds[i]));i=j;}
      Run best=null;int target=0;
      for(int i=1;i<runs.size()-1;i++){var a=runs.get(i-1);var b=runs.get(i);var c=runs.get(i+1);int n=b.to()-b.from();
        if(a.kind()==c.kind()&&n<=MAX_SHORT_STRUCTURE&&a.to()-a.from()>n&&c.to()-c.from()>n&&(best==null||n<best.to()-best.from())){best=b;target=a.kind();}}
      if(best==null)break;Arrays.fill(kinds,best.from(),best.to(),target);
    }
    return kinds;
  }
  public static boolean below(Sample at,Ground ground){return below(at,ground,0,MIN_CUT_DEPTH);}
  public static boolean below(Sample at,Ground ground,int side){return below(at,ground,side,MIN_CUT_DEPTH);}
  public static boolean below(Mesh mesh,Sample at,Ground ground){return below(mesh,at,ground,0);}
  public static boolean below(Mesh mesh,Sample at,Ground ground,int side){
    if(below(at,ground,side,minCutDepth(mesh)))return true;
    // Imported rails can already occupy a cleared trench. Its untouched
    // shoulders still establish a one-block cut even when the deck is air.
    if(mesh.settings().style()==Style.RAILBED)for(int s:side==0?new int[]{-1,1}:new int[]{side})for(double offset:new double[]{.25,.75,1.25}){
      V p=at.at(s*(at.halfWidth()+offset),0);double h=ground.surface(p.x(),p.z(),p.y());
      if(Double.isFinite(h)&&h>=p.y()+minCutDepth(mesh)-1e-7)return true;
    }return false;
  }
  private static boolean below(Sample at,Ground ground,int side,double depth){
    for(double u:side==0?new double[]{-.8,0,.8}:new double[]{.35*side,.65*side,.9*side}){V p=at.at(u*at.halfWidth(),0);double h=ground.surface(p.x(),p.z(),p.y());if(Double.isFinite(h)&&h>=p.y()+depth-1e-7)return true;}return false;
  }
  public static List<Section> sections(Mesh mesh,Ground ground){
    if(mesh.settings().structure()!=Structure.AUTO)return List.of();
    var out=new ArrayList<Section>();
    for(double d=0;d<mesh.length()-1e-7;d+=1){
      RoadPlanningBudget.check();
      double end=Math.min(mesh.length(),d+1),mid=(d+end)/2;var at=RoadStructures.sample(mesh,mid);
      if(!below(mesh,at,ground))continue;
      boolean roof=true;double left=0,right=0,clearance=HEADROOM;
      // Cover is required over the complete roof, including its outside corners.
      // One exposed shoulder/portal makes this whole cross-section an open cut.
      for(double station:new double[]{d+1e-6,mid,end-1e-6}){
        var s=RoadStructures.sample(mesh,station);
        int across=(int)Math.ceil((s.halfWidth()*2+LINING*2)/.5);
        for(int i=0;i<=across;i++){double u=-1+2.0*i/across;
          V p=s.at(u*(s.halfWidth()+LINING),0);double h=ground.surface(p.x(),p.z(),p.y());
          double depth=Double.isFinite(h)?Math.max(0,h-p.y()):0;
          roof&=depth>=MIN_COVER-1e-7;
          // The block straddling the centreline must not classify BOTH halves.
          double lateral=u*(s.halfWidth()+LINING),median=Math.min(1,s.halfWidth()*.35);
          if(lateral<-median)right=Math.max(right,depth);if(lateral>median)left=Math.max(left,depth);
          clearance=Math.max(clearance,depth+WALL_LIP);
        }
      }
      // A deep hillside cut needs a roof even when its downhill shoulder is
      // exposed. Otherwise it becomes an arbitrarily tall retaining wall.
      roof |= Math.max(left,right)+WALL_LIP>HEADROOM+1e-7;
      out.add(new Section(d,end,roof,left,right,Math.min(RoadStructures.MAX_DROP,clearance)));
    }
    int count=(int)Math.ceil(mesh.length());int[] raw=new int[count];Section[] original=new Section[count];
    for(var section:out){int i=(int)Math.round(section.from());raw[i]=section.roof()?2:1;original[i]=section;}
    int[] kinds=normalize(raw);var continuous=new ArrayList<Section>();
    for(int i=0;i<count;i++){
      if(kinds[i]==0&&original[i]==null)continue;
      var old=original[i];double left=old==null?0:old.leftDepth(),right=old==null?0:old.rightDepth(),clear=old==null?HEADROOM:old.clearance();
      continuous.add(new Section(i,Math.min(mesh.length(),i+1),kinds[i]==2,left,right,clear,kinds[i]==0));
    }
    return List.copyOf(continuous);
  }

  /** Roof metadata and all side facilities use the same real canopy footprint. */
  public static List<Section> structuralSections(Mesh mesh,Ground ground){
    var raw=sections(mesh,ground);var roofs=raw.stream().filter(Section::roof).toList();
    return raw.stream().map(s->new Section(s.from(),s.to(),!s.surface()&&(s.roof()||roofs.stream().anyMatch(r->s.to()>r.from()-CANOPY+1e-7&&s.from()<r.to()+CANOPY-1e-7)),s.leftDepth(),s.rightDepth(),s.clearance(),s.surface())).toList();
  }
  public static List<Region> detect(Mesh mesh,Ground ground){
    var out=new ArrayList<Region>();
    for(var section:structuralSections(mesh,ground))if(section.roof()){
      if(!out.isEmpty()&&Math.abs(out.get(out.size()-1).to()-section.from())<1e-7){var old=out.remove(out.size()-1);out.add(new Region(old.from(),section.to()));}
      else out.add(new Region(section.from(),section.to()));
    }
    return List.copyOf(out);
  }
  public static Mesh tube(Mesh mesh,Region r){return tube(mesh,r,HEADROOM);}
  public static Mesh tube(Mesh mesh,Region r,double height){
    var points=new ArrayList<Sample>();
    for(double d=r.from();d<r.to()-1e-7;d+=1)points.add(RoadStructures.sample(mesh,d));
    points.add(RoadStructures.sample(mesh,r.to()));
    var options=mesh.settings().options().infrastructure(RoadInfrastructure.Config.DEFAULT.headroom(Math.max(4,Math.min(12,height))).gantry(RoadInfrastructure.Gantry.OFF));
    return RoadRibbon.mesh(points,mesh.settings().structure(Structure.TUNNEL).options(options));
  }
  public static List<Region> regions(List<Part> parts){
    var out=new LinkedHashSet<Region>(legacyRegions(parts));
    for(var r:roofRegions(parts))out.add(new Region(r.from(),r.to()));
    var merged=new ArrayList<Region>();
    for(var r:out.stream().sorted(Comparator.comparingDouble(Region::from)).toList()){
      if(!merged.isEmpty()&&r.from()<=merged.get(merged.size()-1).to()+1e-7){var old=merged.remove(merged.size()-1);merged.add(new Region(old.from(),Math.max(old.to(),r.to())));}else merged.add(r);
    }
    return List.copyOf(merged);
  }
  /** Legacy four-metre tags must not override a persisted exact roof profile. */
  public static List<Region> legacyRegions(List<Part> parts){
    var out=new LinkedHashSet<Region>();
    for(var p:parts)if(p.material()==Material.TUNNEL&&p.model().startsWith(PREFIX)){
      double[] values=parse(p.model(),PREFIX,2);if(values!=null)out.add(new Region(values[0],values[1]));
    }
    var merged=new ArrayList<Region>();
    for(var r:out.stream().sorted(Comparator.comparingDouble(Region::from)).toList()){
      if(!merged.isEmpty()&&r.from()<=merged.get(merged.size()-1).to()+1e-7){var old=merged.remove(merged.size()-1);merged.add(new Region(old.from(),Math.max(old.to(),r.to())));}else merged.add(r);
    }
    return List.copyOf(merged);
  }
  public static List<OpenRegion> openRegions(List<Part> parts){
    var out=new LinkedHashSet<OpenRegion>();
    for(var p:parts)if(p.material()==Material.TUNNEL&&p.model().startsWith(OPEN)){
      double[] values=parse(p.model(),OPEN,3);if(values!=null&&values[2]>=4&&values[2]<=RoadStructures.MAX_DROP)out.add(new OpenRegion(values[0],values[1],values[2]));
    }
    var merged=new ArrayList<OpenRegion>();
    for(var r:out.stream().sorted(Comparator.comparingDouble(OpenRegion::from)).toList()){
      if(!merged.isEmpty()){var last=merged.get(merged.size()-1);
        if(Math.abs(last.to()-r.from())<1e-7&&Math.abs(last.clearance()-r.clearance())<1e-7){merged.set(merged.size()-1,new OpenRegion(last.from(),r.to(),last.clearance()));continue;}}
      merged.add(r);
    }
    return List.copyOf(merged);
  }
  private static double[] parse(String tag,String prefix,int count){
    String[] pieces=tag.substring(prefix.length()).split(":");if(pieces.length!=count)return null;
    try{double[] values=new double[count];for(int i=0;i<count;i++){values[i]=Double.parseDouble(pieces[i]);if(!Double.isFinite(values[i]))return null;}
      return values[0]>=0&&values[1]>values[0]?values:null;
    }catch(NumberFormatException ignored){return null;}
  }
  public static List<Part> enclose(Mesh mesh,Ground ground,List<Part> original){return enclose(mesh,ground,original,d->HEADROOM);}
  public static List<Part> enclose(Mesh mesh,Ground ground,List<Part> original,java.util.function.DoubleUnaryOperator ceiling){
    var sections=structuralSections(mesh,ground);if(sections.isEmpty())return original;
    var out=new ArrayList<Part>();
    var roofStarts=new HashSet<Double>();var roofEnds=new HashSet<Double>();
    for(var s:sections)if(!s.surface()&&s.roof()){roofStarts.add(s.from());roofEnds.add(s.to());}
    var portals=new HashMap<Double,Double>();
    for(double d:roofStarts)if(d>mesh.first().distance()+1e-7&&!roofEnds.contains(d))portals.put(d,portalTop(mesh,d,ground,ceiling));
    for(double d:roofEnds)if(d<mesh.last().distance()-1e-7&&!roofStarts.contains(d))portals.put(d,portalTop(mesh,d,ground,ceiling));
    java.util.function.DoubleUnaryOperator lining=d->{
      var at=RoadStructures.sample(mesh,d);double head=Math.max(HEADROOM,ceiling.applyAsDouble(d));
      double normal=at.center().y()+head+ROOF_THICKNESS,top=normal;
      for(var portal:portals.entrySet()){
        double distance=Math.abs(d-portal.getKey()),weight=1-Settings.smooth(Math.max(0,Math.min(1,(distance-CANOPY)/16)));
        top=Math.max(top,normal+Math.max(0,portal.getValue()-normal)*weight);
      }
      return head+Math.max(0,top-normal);
    };
    var effective=sections.stream().filter(s->!s.surface()).map(s->new Section(s.from(),s.to(),roofStarts.contains(s.from()),s.leftDepth(),s.rightDepth(),s.clearance())).toList();
    for(var part:original)retainOutside(out,part,mesh,ground,effective);
    var wallHeights=new HashMap<String,Double>();
    var sideEdges=new HashMap<Integer,List<Double>>();
    for(int side:new int[]{-1,1}){
      var cutStarts=new HashSet<Double>();var cutEnds=new HashSet<Double>();
      for(var section:sections)if(!section.surface()&&(section.roof()||(side<0?section.rightDepth():section.leftDepth())>=minCutDepth(mesh))){cutStarts.add(section.from());cutEnds.add(section.to());}
      var edges=new ArrayList<Double>();
      for(double d:cutStarts)if(d>1e-7&&!cutEnds.contains(d))edges.add(d);
      for(double d:cutEnds)if(d<mesh.length()-1e-7&&!cutStarts.contains(d))edges.add(d);
      sideEdges.put(side,edges);
    }
    for(var s:sections){
      var a=RoadStructures.sample(mesh,s.from());var b=RoadStructures.sample(mesh,s.to());
      if(s.surface()){
        // This thin subgrade footing carries the excavation receipt after a short
        // buried island becomes open ground. It stays inside the existing deck.
        out.add(new Part(a.at(0,mesh.settings().thickness()-.1),b.at(0,mesh.settings().thickness()-.1),.1,.05,false,Material.TUNNEL,a.left().mul(.05),b.left().mul(.05),OPEN+s.from()+":"+s.to()+":"+s.clearance()));
        continue;
      }
      var medianA=RoadProfile.layout(mesh,a);var medianB=RoadProfile.layout(mesh,b);
      boolean roof=s.roof();
      if(medianA.catalog().twoWay()&&Math.min(medianA.median(),medianB.median())>=.35&&(roof||s.leftDepth()>=minCutDepth(mesh)&&s.rightDepth()>=minCutDepth(mesh)))RoadStructures.medianBarrier(out,mesh,a,b,medianA.catalog().type()==RoadProfile.Type.HIGHWAY,false,s.from());
      double ha=lining.applyAsDouble(s.from()),hb=lining.applyAsDouble(s.to());
      boolean railBox=mesh.settings().options().railbed().active()&&mesh.settings().options().railbed().fittings().tunnel()!=RoadInfrastructure.Tunnel.ROUND;
      if(railBox)ha=hb=mesh.settings().options().infrastructure().headroom();
      if(roof&&mesh.settings().options().railbed().active()&&mesh.settings().options().railbed().fittings().tunnel()==RoadInfrastructure.Tunnel.ROUND){ha=RailbedTunnel.top(mesh.settings().options().infrastructure(),a.halfWidth());hb=RailbedTunnel.top(mesh.settings().options().infrastructure(),b.halfWidth());}
      String tag=roof?roofProfile(s.from(),s.to(),ha,hb):OPEN+s.from()+":"+s.to()+":"+s.clearance();
      if(roof&&mesh.settings().options().railbed().active()){out.addAll(RailbedTunnel.section(mesh,a,b,tag));continue;}
      for(int side:new int[]{-1,1}){
        if(mesh.settings().options().railbed().active()&&!mesh.settings().options().railbed().fittings().rail(side))continue;
        if(!roof&&(side<0?s.rightDepth():s.leftDepth())<minCutDepth(mesh))continue;
        V pa=a.at(side*(a.halfWidth()+.75),0),pb=b.at(side*(b.halfWidth()+.75),0);
        double ta=roof?pa.y()+ha:smoothWallTop(mesh,s.from(),side,ground,wallHeights),tb=roof?pb.y()+hb:smoothWallTop(mesh,s.to(),side,ground,wallHeights);
        // The roof rests ON the wall. Extending the wall through the roof slab
        // duplicated both top and outside faces and caused the black portal teeth.
        // At the portal the open wall meets the top of the roof slab;
        // away from the portal the normal six-block cap still applies.
        // Railway box roofs are 0.85 m thick, road roofs 1.05 m. Using the
        // road slab here left a 0.20 m tooth above each railway portal edge.
        double roofThickness=railBox?RailbedTunnel.BOX_ROOF_THICKNESS:ROOF_THICKNESS;
        if(!roof&&roofEnds.contains(s.from()))ta=pa.y()+ha+roofThickness;
        if(!roof&&roofStarts.contains(s.to()))tb=pb.y()+hb+roofThickness;
        if(!roof){
          if(!roofEnds.contains(s.from()))ta=Math.min(ta,pa.y()+HEADROOM);
          if(!roofStarts.contains(s.to()))tb=Math.min(tb,pb.y()+HEADROOM);
          ta=taperWall(sideEdges.get(side),s.from(),pa.y(),ta);
          tb=taperWall(sideEdges.get(side),s.to(),pb.y(),tb);
        }
        // Shared absolute endpoint heights eliminate the old one-metre sawtooth on grades.
        double height=Math.min(RoadStructures.MAX_DROP,Math.max(ta-pa.y(),tb-pb.y())+mesh.settings().thickness());
        out.add(new Part(new V(pa.x(),ta-height,pa.z()),new V(pb.x(),tb-height,pb.z()),WALL_WIDTH,height,false,Material.TUNNEL,a.left().mul(WALL_WIDTH/2),b.left().mul(WALL_WIDTH/2),tag));
        if(roof){
          V lightA=a.at(side*(a.halfWidth()-.12),-.8),lightB=b.at(side*(b.halfWidth()-.12),-.8);
          out.add(new Part(lightA,lightB,.12,.18,false,Material.LAMP,a.left().mul(.06),b.left().mul(.06),"sr:auto_wall_light"));
        }
        if(medianA.cycleWidth()>.01&&medianB.cycleWidth()>.01){
          double inset=RoadStreetscape.separatorWidth(mesh.settings().options())/2;
          RoadStructures.barrier(out,a.at(medianA.outer(side)+side*inset,0),b.at(medianB.outer(side)+side*inset,0),false,false,s.from());
        }
      }
      if(!roof)continue;
      int bands=Math.max(1,(int)Math.ceil((Math.max(a.halfWidth(),b.halfWidth())*2+LINING*2)/6));
      for(int i=0;i<bands;i++){
        double u=-1+2.0*i/bands,v=-1+2.0*(i+1)/bands,wa=a.halfWidth()+LINING,wb=b.halfWidth()+LINING;
        out.add(new Part(a.at(wa*(u+v)/2,-ha),b.at(wb*(u+v)/2,-hb),Math.max(wa,wb)*(v-u),ROOF_THICKNESS,false,Material.TUNNEL,
            a.left().mul(wa*(v-u)/2),b.left().mul(wb*(v-u)/2),tag));
      }
      // The luminous body hangs below the lining. It must not depend on a tiny
      // renderer bias to escape concrete, or extend beyond the roofed interval.
      double start=s.from()+(roofEnds.contains(s.from())?0:.08),end=s.to()-(roofStarts.contains(s.to())?0:.08);
      var la=RoadStructures.sample(mesh,start);var lb=RoadStructures.sample(mesh,end);
      out.add(new Part(la.at(0,-lining.applyAsDouble(start)+.14),lb.at(0,-lining.applyAsDouble(end)+.14),.22,.12,false,Material.LAMP,la.left().mul(.11),lb.left().mul(.11),"sr:auto_light"));
    }
    out.addAll(RailbedTunnel.portals(mesh,regions(out),ground));
    return List.copyOf(out);
  }
  private static double taperWall(List<Double> edges,double d,double roadY,double top){
    double fade=1;
    for(double edge:edges)fade=Math.min(fade,Math.abs(d-edge)/4);
    return roadY+Math.max(.15,(top-roadY)*fade);
  }
  private static double portalTop(Mesh mesh,double station,Ground ground,java.util.function.DoubleUnaryOperator ceiling){
    var at=RoadStructures.sample(mesh,station);double top=at.center().y()+Math.max(HEADROOM,ceiling.applyAsDouble(station))+ROOF_THICKNESS;
    // Terrain above a portal does not raise its lining or the adjoining wall.
    // The road clearance is authoritative; high retained soil is roofed instead.
    return top;
  }
  private static double wallTop(Sample at,int side,Ground ground){
    double top=Double.NEGATIVE_INFINITY;
    for(double u:new double[]{0,.5,1}){V p=at.at(side*(at.halfWidth()+LINING)*u,0);double h=ground.surface(p.x(),p.z(),p.y());if(Double.isFinite(h))top=Math.max(top,h);}
    return Math.max(at.at(side*(at.halfWidth()+.75),0).y()+1.2,top+WALL_LIP);
  }
  private static double smoothWallTop(Mesh mesh,double station,int side,Ground ground,Map<String,Double> cache){
    double first=Math.floor(station/4)*4,last=Math.min(mesh.length(),first+4);
    java.util.function.DoubleUnaryOperator knot=d->cache.computeIfAbsent(side+":"+d,key->{
      double top=Double.NEGATIVE_INFINITY;
      // Both ends see the complete interval, so the smooth cap never exposes
      // a block of retained soil between samples. Adjacent walls share knots.
      for(double x=Math.max(0,d-4);x<=Math.min(mesh.length(),d+4)+1e-7;x+=.5)
        top=Math.max(top,wallTop(RoadStructures.sample(mesh,x),side,ground));
      return top;
    });
    double a=knot.applyAsDouble(first),b=knot.applyAsDouble(last);
    return last<=first?a:a+(b-a)*(station-first)/(last-first);
  }
  private static void retainOutside(List<Part> out,Part part,Mesh mesh,Ground ground,List<Section> allSections){
    double partSide=RoadQueries.horizontal(mesh,part.a().add(part.b()).mul(.5)).lateral();
    boolean median=medianFurniture(mesh,part);
    var sections=allSections.stream().filter(s->s.roof()||(median?s.leftDepth()>=minCutDepth(mesh)&&s.rightDepth()>=minCutDepth(mesh):partSide<-.05?s.rightDepth()>=minCutDepth(mesh):partSide>.05?s.leftDepth()>=minCutDepth(mesh):s.leftDepth()>=minCutDepth(mesh)&&s.rightDepth()>=minCutDepth(mesh))).toList();
    double da=RoadQueries.horizontal(mesh,part.a()).sample().distance(),db=RoadQueries.horizontal(mesh,part.b()).sample().distance();
    boolean rail=!part.pier()&&part.width()<=.7&&part.height()<=1.2&&(part.material()==Material.STEEL||part.material()==Material.DARK_STEEL||part.material()==Material.CONCRETE);
    if(!rail||Math.abs(db-da)<1e-8){double d=(da+db)/2;if(sections.stream().noneMatch(s->d>=s.from()&&d<=s.to()))out.add(part);return;}
    var spans=new ArrayList<double[]>();spans.add(new double[]{0,1});
    for(var s:sections){double x=(s.from()-da)/(db-da),y=(s.to()-da)/(db-da),lo=Math.max(0,Math.min(x,y)),hi=Math.min(1,Math.max(x,y));if(hi<=lo)continue;
      var next=new ArrayList<double[]>();for(var r:spans){if(lo>r[0])next.add(new double[]{r[0],Math.min(lo,r[1])});if(hi<r[1])next.add(new double[]{Math.max(hi,r[0]),r[1]});}spans=next;
    }
    for(var r:spans)if(r[1]-r[0]>1e-7){
      V a=part.a().add(part.b().sub(part.a()).mul(r[0])),b=part.a().add(part.b().sub(part.a()).mul(r[1]));
      V fa=frame(part,r[0]),fb=frame(part,r[1]);var kept=new Part(a,b,part.width(),part.height(),false,part.material(),fa,fb,part.model());
      out.add(kept);
      if(part.material()==Material.DARK_STEEL)continue;
      for(V p:List.of(a,b)){
        double d=RoadQueries.horizontal(mesh,p).sample().distance();
        for(var s:sections){double sign=Math.abs(d-s.from())<1e-6?1:Math.abs(d-s.to())<1e-6?-1:0;if(sign==0)continue;
          boolean outer=sections.stream().noneMatch(other->sign>0?Math.abs(other.to()-d)<1e-7:Math.abs(other.from()-d)<1e-7);if(!outer)continue;
          var start=RoadStructures.sample(mesh,d);double lateral=p.sub(start.center()).dot(start.left());if(Math.abs(lateral)<start.halfWidth()-1)continue;
          // Meet the front cap of the wall. Extending .8m along a curved wall
          // left the bar protruding through its opposite face near narrow noses.
          double side=Math.signum(lateral),height=p.y()-start.at(lateral,0).y();
          V wall=start.at(side*(start.halfWidth()+.75),-height);
          var join=new Part(p,wall,part.width(),part.height(),false,part.material(),null,null,"sr:wall_rail");
          if(!ground.railBlocked(join,p,wall))out.add(join);
        }
      }
    }
  }
  /** A one-sided hillside wall replaces the outside rail, never half of the central median. */
  private static boolean medianFurniture(Mesh mesh,Part part){
    if(part.pier()||part.height()>1.2)return false;
    for(V p:part.base()){
      var q=RoadQueries.horizontal(mesh,p);var at=q.sample();var layout=RoadProfile.layout(mesh,at);
      if(!layout.catalog().twoWay()||layout.median()<.35
          ||Math.abs(q.lateral()-layout.medianCenter())>layout.median()/2+.05
          ||p.y()<at.center().y()-.05||p.y()+part.height()>at.center().y()+1.6)return false;
    }
    return true;
  }
  private static V frame(Part part,double t){if(part.frameA()==null||part.frameB()==null)return null;return part.frameA().add(part.frameB().sub(part.frameA()).mul(t));}
  private RoadAutoTunnels(){}
}
