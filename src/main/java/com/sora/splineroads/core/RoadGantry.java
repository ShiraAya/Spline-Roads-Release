package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadInfrastructure.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Deterministic per-road stations; edits affect one station and survive normal road rebuilds. */
public final class RoadGantry {
  public static final int MANUAL_START=256,MAX_SLOTS=512;
  public record Edit(int slot,double offset,double clearance,Gantry kind,boolean reverse,boolean independent,double position,boolean anchored,boolean automatic) {
    public Edit(int slot,double offset,double clearance,Gantry kind,boolean reverse,boolean independent,double position,boolean anchored){this(slot,offset,clearance,kind,reverse,independent,position,anchored,false);}
    public Edit(int slot,double offset,double clearance,Gantry kind,boolean reverse,boolean independent,double position){this(slot,offset,clearance,kind,reverse,independent,position,false);}
    public Edit(int slot,double offset,double clearance,Gantry kind,boolean reverse){this(slot,offset,clearance,kind,reverse,false,0);}
    public Edit parameters(double shift,double height,Gantry style,boolean flip){return new Edit(slot,shift,height,style,flip,independent,position,anchored,false);}
    public Edit {
      if(slot<0||slot>=MAX_SLOTS||(slot>=MANUAL_START)!=independent||position<0||position>1||kind==null||automatic&&(!anchored||independent)||!RoadGeometry.finite(offset,clearance,position)
          ||Math.abs(offset)>256||clearance<5||clearance>16)
        throw new IllegalArgumentException("龙门架位移 -256–256 格，净高 5–16 格");
    }
    public static Edit defaults(int slot){return new Edit(slot,0,5.6,Gantry.AUTO,false);}
  }
  public record Station(int slot,double distance,Sample sample,Edit edit,Gantry kind) {}
  public static int count(Mesh m){if(m.settings().options().infrastructure().fixedStations())return (int)m.settings().options().infrastructure().gantryEdits().stream().filter(e->!e.independent()).count();return m.settings().structure()==Structure.TUNNEL||m.settings().style().ramp()&&!m.settings().style().connectorRamp()||m.length()<20?0:Math.max(1,(int)Math.floor(m.length()/m.settings().options().infrastructure().spacing()));}
  public static Edit edit(Config c,int slot){return c.gantryEdits().stream().filter(e->e.slot()==slot).findFirst().orElseGet(()->Edit.defaults(slot));}
  public static List<Integer> slots(Mesh m){if(m.settings().options().infrastructure().fixedStations())return m.settings().options().infrastructure().gantryEdits().stream().map(Edit::slot).toList();var slots=new ArrayList<Integer>();for(int i=0;i<count(m);i++)slots.add(i);for(var e:m.settings().options().infrastructure().gantryEdits())if(e.independent())slots.add(e.slot());return List.copyOf(slots);}
  public static boolean exists(Mesh m,int slot){return slot>=0&&(!m.settings().options().infrastructure().fixedStations()&&slot<count(m)||m.settings().options().infrastructure().gantryEdits().stream().anyMatch(e->(e.independent()||e.anchored())&&e.slot()==slot));}
  public static double maxOffset(Mesh m,int slot){var e=edit(m.settings().options().infrastructure(),slot);return Math.min(256,e.independent()||e.anchored()?Math.max(0,Math.min(e.position()*m.length()-2,(1-e.position())*m.length()-2)):Math.max(0,m.length()/Math.max(1,count(m))/2-3));}
  public static int playerFlow(Sample sample,V look){V direction=new V(look.x(),0,look.z());return direction.dot(sample.left().left().mul(-1))>=0?1:-1;}
  public static Edit create(Mesh m,double station){
    if(m.settings().structure()==Structure.TUNNEL||m.settings().style().ramp()||m.length()<6||station<2||station>m.length()-2)throw new IllegalArgumentException("请在普通道路或高速道路上、离端点至少2格的位置创建龙门架");
    var c=m.settings().options().infrastructure();for(int slot:slots(m)){var old=station(m,slot);if(old.kind()!=Gantry.OFF&&Math.abs(old.distance()-station)<4)throw new IllegalArgumentException("此处已有龙门架，请与现有架体保持至少4格间距");}
    for(int slot=MANUAL_START;slot<MAX_SLOTS;slot++)if(!exists(m,slot))return new Edit(slot,0,5.6,Gantry.SIGNS,false,true,station/m.length());
    throw new IllegalArgumentException("每段道路最多放置256座独立龙门架，请拆分路段");
  }
  public static Station station(Mesh m,int slot){
    int n=count(m);if(!exists(m,slot))throw new IllegalArgumentException("该龙门架位置已失效，请重新选择");
    var c=m.settings().options().infrastructure();var e=edit(c,slot);double maximum=maxOffset(m,slot);
    if(Math.abs(e.offset())>maximum+1e-7)throw new IllegalArgumentException(String.format(Locale.ROOT,"该龙门架可前后移动最多 %.1f 格",maximum));
    double d=(e.independent()||e.anchored()?e.position()*m.length():(slot+.5)*m.length()/n)+e.offset();Sample s=RoadStructures.sample(m,d);
    Gantry kind=e.independent()?(e.kind()==Gantry.AUTO?Gantry.SIGNS:e.kind()):c.gantry()==Gantry.OFF?Gantry.OFF:e.kind()==Gantry.AUTO?c.gantry():e.kind();
    if(kind==Gantry.FRAME&&!e.anchored()){
      // Cardinal equipment trusses keep a grid-aligned mounting envelope.
      V f=s.left().left().mul(-1),p=s.center();
      if(Math.abs(f.x())>.999999||Math.abs(f.z())>.999999){
        double coordinate=Math.abs(f.x())>.999999?p.x():p.z();
        double move=(Math.floor(coordinate)+.5-coordinate)*(Math.abs(f.x())>.999999?f.x():f.z());
        d+=move;s=RoadStructures.sample(m,d);
      }
    }
    return new Station(slot,d,s,e,kind);
  }
  private static boolean unsupported(Mesh mesh,Station station){
    for(double delta:new double[]{-.6,0,.6})for(int side:new int[]{-1,1})
      if(LaneDeck.outerOpening(mesh,Math.max(0,Math.min(mesh.length(),station.distance()+delta)),side))return true;
    return false;
  }
  private static boolean enabled(Mesh m,Ground ground,Station st){
    if(m.settings().structure()==Structure.TUNNEL||st.kind()==Gantry.OFF||unsupported(m,st)||RoadStreetscape.underground(m,st.distance())||m.settings().structure()==Structure.AUTO&&RoadAutoTunnels.below(m,st.sample(),ground))return false;var s=st.sample();
    // AUTO is a legacy beam shape, not a terrain-dependent enable policy.
    // The road-level on/off switch governs ground and elevated roads alike.
    return !ground.furnitureClear(s.center())&&!ground.joined(s.center());
  }
  public static boolean near(Mesh m,Ground ground,double d){
    for(int i:slots(m)){var st=station(m,i);if(Math.abs(d-st.distance())<5&&enabled(m,ground,st))return true;}return false;
  }
  public static boolean member(Part p){return p.model().startsWith("sr:gantry/")||p.material()==Material.GANTRY_FRAME;}
  public static List<Part> plan(Mesh m,Ground ground){return plan(m,ground,List.of());}
  public static List<Part> plan(Mesh m,Ground ground,Collection<Part> occupied){
    return plan(m,ground,occupied,false);
  }
  /** Reserve authored stations before any road in a batch searches for movable
   * bridge piers. Unedited automatic stations retain their normal skip policy. */
  public static List<Part> required(Mesh m,Ground ground,Collection<Part> fixed){
    if(m.settings().structure()==Structure.TUNNEL)return List.of();
    return plan(m,ground,fixed,true);
  }
  private static boolean edited(Mesh m,int slot){return m.settings().options().infrastructure().gantryEdits().stream().anyMatch(e->e.slot()==slot&&!e.automatic());}
  private static List<Part> plan(Mesh m,Ground ground,Collection<Part> occupied,boolean requiredOnly){
    var stations=slots(m).stream().filter(i->!requiredOnly||edited(m,i)&&ground.requiredGantry(station(m,i))).toList();
    if(stations.stream().noneMatch(i->ground.preservedGantry(station(m,i))!=null||enabled(m,ground,station(m,i))))return List.of();
    var fixtures=new ArrayList<Part>(occupied);var fixed=ground.fixedSupports();if(fixed!=null)fixtures.addAll(fixed);
    var hard=fixtures.stream().filter(p->requiredOnly||p.pier()||member(p)).toList();
    var solids=new RoadSolidOverlap.Index(hard);
    var boundaries=new RoadSolidOverlap.Index(fixtures.stream().filter(p->!requiredOnly&&!p.pier()&&!member(p)&&RoadGantryMounts.boundaryPart(m,p)).toList());
    var out=new ArrayList<Part>();
    for(int i:stations){
      var st=station(m,i);if(!enabled(m,ground,st))continue;var preserved=ground.preservedGantry(st);if(preserved!=null){out.addAll(preserved);continue;}
      var parts=fittedParts(m,st,ground,solids,boundaries);
      if(parts==null){
        if(edited(m,st.slot())&&ground.requiredGantry(st))
          throw conflict(st);
      }else out.addAll(parts);
    }
    return List.copyOf(out);
  }
  /** Only exact tagged or reconstructed legacy members belong to this station. */
  public static List<Part> members(Mesh mesh,int slot,List<Part> saved) {
    String tag="sr:gantry/"+slot;
    var st=station(mesh,slot);
    var templates=new HashSet<Part>();
    // Old saves did not tag members. Recover their authored foot height from the
    // saved steel shaft, and also include the elevated cantilever variant.
    for(boolean elevated:new boolean[]{false,true}) {
      var ground=new Ground(){public boolean joined(V p){return false;}public boolean blocked(Part p){return false;}public double top(double x,double z,double y){
        if(elevated)return Double.NaN;
        return saved.stream().filter(p->p.pier()&&p.material()==Material.STEEL&&Math.abs(p.width()-.65)<1e-8
            &&Math.hypot(p.a().x()-x,p.a().z()-z)<1e-7).mapToDouble(p->p.a().y()).findFirst().orElse(Double.NaN);
      }};
      for(var p:parts(mesh,st,ground))templates.add(untagged(p));
    }
    return saved.stream().filter(p->p.model().equals(tag)||p.model().isEmpty()&&templates.contains(p)).toList();
  }
  private static Part untagged(Part p){return new Part(p.a(),p.b(),p.width(),p.height(),p.pier(),p.material(),p.frameA(),p.frameB(),"",p.heights());}
  private static List<Part> tagged(List<Part> parts,int slot){return parts.stream().map(p->new Part(p.a(),p.b(),p.width(),p.height(),p.pier(),p.material(),p.frameA(),p.frameB(),"sr:gantry/"+slot,p.heights())).toList();}
  public static List<Part> planStation(Mesh mesh,Ground ground,int slot){
    var st=station(mesh,slot);
    if(st.kind()==Gantry.OFF)return List.of();
    if(!enabled(mesh,ground,st))throw new IllegalArgumentException("此位置无法安装龙门架，请调整位移或类型");
    var parts=fittedParts(mesh,st,ground,new RoadSolidOverlap.Index(List.of()),new RoadSolidOverlap.Index(List.of()));
    if(parts==null)throw conflict(st);
    return parts;
  }

  /** Preserve a legal authored assembly exactly. Only a foot whose sole blocker
   * is a verge/outer barrier gets a bounded lateral search. Fixed bridge supports,
   * other gantries, overhead beams and live lanes are never bypassed by this fit. */
  private static List<Part> fittedParts(Mesh mesh,Station st,Ground ground,RoadSolidOverlap.Index solids,
      RoadSolidOverlap.Index boundaries){
    var original=parts(mesh,st,ground);boolean negative=false,positive=false;
    for(var p:original){
      boolean blocked=ground.blocked(p),boundary=boundaries.intersects(p);
      if(solids.intersects(p))return null;
      if(!blocked&&!boundary)continue;
      if(!RoadGantryMounts.foot(p)||blocked&&!ground.gantryMountConflict(p,st))return null;
      if(p.a().sub(st.sample().center()).dot(st.sample().left())<0)negative=true;else positive=true;
    }
    if(!negative&&!positive)return original;
    // Outward first at each distance; inward alternatives remain wholly outside
    // the host deck. FRAME stations and mounting height retain grid alignment;
    // a fitted foot may use a fractional lateral offset.
    double[] shifts={0,.2,-.2,.4,-.4,.6,-.6,1,1.4,2,3};
    var feet=original.stream().filter(p->p.pier()&&p.material()==Material.STEEL).toList();
    // A compact plinth remains wider than the 0.85 m bearing plate and 0.65 m
    // steel column. Use it only when ordinary feet cannot fit the exterior gap.
    for(double pedestal:new double[]{1.2,1,.95}){
      var first=new ArrayList<Double>();first.add(0.0);var last=new ArrayList<Double>();last.add(0.0);
      for(var foot:feet){
        boolean minus=foot.a().sub(st.sample().center()).dot(st.sample().left())<0;
        if(minus?!negative:!positive)continue;
        var list=minus?first:last;for(double shift:shifts)if(shift!=0)list.add(shift);
        list.add(RoadGantryMounts.tightOffset(mesh,st,minus?-1:1,pedestal,foot.a()));
      }
      var candidates=new ArrayList<double[]>();
      for(double a:first)for(double b:last)if(pedestal!=1.2||a!=0||b!=0)candidates.add(new double[]{a,b});
      candidates.sort(Comparator.comparingDouble(v->Math.abs(v[0])+Math.abs(v[1])));
      for(var shift:candidates){
        RoadPlanningBudget.check();
        var candidate=parts(mesh,st,ground,shift[0],shift[1],negative?pedestal:1.2,positive?pedestal:1.2);
        boolean clear=true;
        for(var p:candidate)if(ground.blocked(p)||solids.intersects(p)||boundaries.intersects(p)
            ||RoadGantryMounts.foot(p)&&RoadGantryMounts.blockedByRoad(p,mesh,4.25)){
          clear=false;break;
        }
        if(clear)return candidate;
      }
    }
    return null;
  }
  private static IllegalArgumentException conflict(Station station){
    V p=station.sample().center();
    return new IllegalArgumentException(String.format(Locale.ROOT,"龙门架（%.0f, %.0f, %.0f）与道路或设施冲突，请调整架位或净高",p.x(),p.y(),p.z()));
  }
  private static V at(Sample s,double lateral,double up){return s.at(lateral,-up);}
  private static void beam(List<Part> out,V a,V b,double width,double height,Material mat){out.add(new Part(a,b,width,height,false,mat));}
  private static void post(List<Part> out,V a,double width,double height,Material mat){if(height>.001)out.add(new Part(a,a,width,height,true,mat));}
  public static double lowerBeamY(Station st){double y=st.sample().center().y()+st.edit().clearance()+1.5;return st.kind()==Gantry.FRAME?Math.ceil(y):y;}
  public static double upperBeamCenterY(Station st){return lowerBeamY(st)+(st.kind()==Gantry.FRAME?.82+.18/2:.7+.3/2);}
  public static List<Part> parts(Mesh mesh,Station st,Ground ground){
    return parts(mesh,st,ground,0,0,1.2,1.2);
  }
  private static List<Part> parts(Mesh mesh,Station st,Ground ground,double negativeShift,double positiveShift,double negativeBase,double positiveBase){
    if(st.kind()==Gantry.OFF||unsupported(mesh,st))return List.of();
    var s=st.sample();var profile=RoadProfile.catalog(mesh.settings().style());var parts=new ArrayList<Part>();
    boolean equipment=st.kind()==Gantry.FRAME;
    double edge=s.halfWidth()+1.3,leftEdge=edge+negativeShift,rightEdge=edge+positiveShift,clear=st.edit().clearance(),lower=clear+1.5;
    if(equipment)lower=Math.ceil(s.center().y()+lower)-s.center().y();
    V left=at(s,-leftEdge,0),right=at(s,rightEdge,0);
    if(equipment&&(Math.abs(s.left().x())>.999999||Math.abs(s.left().z())>.999999)){
      V originalLeft=at(s,-edge,0),originalRight=at(s,edge,0);
      left=new V(Math.floor(originalLeft.x())+.5,originalLeft.y(),Math.floor(originalLeft.z())+.5).add(s.left().mul(-negativeShift));
      right=new V(Math.floor(originalRight.x())+.5,originalRight.y(),Math.floor(originalRight.z())+.5).add(s.left().mul(positiveShift));
    }
    for(V foot:List.of(left,right)){
      double underside=s.center().y()-mesh.settings().thickness();
      // Probe from above the surface, not from inside the road slab: the latter
      // truncates the terrain height and excavates a pit around a ground gantry.
      double floor=ground.top(foot.x(),foot.z(),s.center().y()+2);
      boolean grounded=Double.isFinite(floor)&&Math.abs(s.center().y()-floor)<2;
      var walk=mesh.settings().options().sidewalk();int walkSide=foot.sub(s.center()).dot(s.left())<0?-1:1;
      boolean onWalk=walk.enabled()&&walk.smooth()&&(walk.side()==RoadSidewalks.Side.BOTH||walk.side()==RoadSidewalks.Side.LEFT&&walkSide<0||walk.side()==RoadSidewalks.Side.RIGHT&&walkSide>0)&&Math.abs(foot.sub(s.center()).dot(s.left()))+.6<=s.halfWidth()+walk.width();
      double base=onWalk?s.center().y()+.2:grounded?floor:s.center().y();
      double pedestal=foot.sub(s.center()).dot(s.left())<0?negativeBase:positiveBase;
      post(parts,new V(foot.x(),base,foot.z()),.65,s.center().y()+lower+.9-base,Material.STEEL);
      post(parts,new V(foot.x(),base,foot.z()),pedestal,.45,Material.CONCRETE);
      post(parts,new V(foot.x(),base+.45,foot.z()),.85,.12,Material.DARK_STEEL);
      double side=foot.sub(s.center()).dot(s.left())<0?-1:1;
      if(!grounded&&!onWalk){
        beam(parts,at(s,side*(s.halfWidth()-.5),-.57),new V(foot.x(),s.center().y()-.57,foot.z()),1.2,.45,Material.CONCRETE);
        post(parts,new V(foot.x(),s.center().y()-.12,foot.z()),pedestal,.12,Material.CONCRETE);
      }
    }
    if(equipment){
      // Open box truss: grid-aligned mounting faces, without the old solid 1x1 bar.
      V forward=s.left().left().mul(-1);
      beam(parts,left.add(new V(0,lower,0)),right.add(new V(0,lower,0)),1,.18,Material.GANTRY_FRAME);
      beam(parts,left.add(new V(0,lower+.82,0)),right.add(new V(0,lower+.82,0)),1,.18,Material.GANTRY_FRAME);
      V axis=right.sub(left);int bays=Math.max(2,(int)Math.ceil(axis.horizontalLength()/2.8));
      for(int i=0;i<bays;i++)for(int face:new int[]{-1,1}){
        V a=left.add(axis.mul((double)i/bays)).add(forward.mul(face*.42));
        V b=left.add(axis.mul((double)(i+1)/bays)).add(forward.mul(face*.42));
        beam(parts,a.add(new V(0,lower+(i%2==0?.18:.72),0)),b.add(new V(0,lower+(i%2==0?.72:.18),0)),.12,.1,Material.GANTRY_FRAME);
      }
      for(V foot:List.of(left,right)){
        double sign=foot.sub(s.center()).dot(s.left())<0?1:-1;
        beam(parts,foot.add(new V(0,lower-1.25,0)),foot.add(s.left().mul(sign*1.6)).add(new V(0,lower,0)),.22,.22,Material.STEEL);
      }
      return tagged(parts,st.slot());
    }
    beam(parts,at(s,-leftEdge,lower),at(s,rightEdge,lower),.45,.45,Material.STEEL);
    beam(parts,at(s,-leftEdge,lower+.7),at(s,rightEdge,lower+.7),.45,.3,Material.STEEL);
    for(double u=-leftEdge;u<rightEdge;u+=3)beam(parts,at(s,u,lower+.15),at(s,Math.min(rightEdge,u+3),lower+.7),.12,.12,Material.DARK_STEEL);
    return tagged(parts,st.slot());
  }
  private RoadGantry(){}
}
