package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Isolated core candidate: no Minecraft client/server and no world mutation. */
public final class Bridge204Validation {
  static int checks;
  static void ok(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
  static final Ground GROUND=new Ground(){
    public double top(double x,double z,double y){return -20;}
    public boolean joined(V p){return false;}
    public boolean blocked(Part p){return false;}
  };
  static Mesh mesh(RoadInfrastructure.Bridge kind,boolean enabled,boolean auto,double y,Structure structure){
    var s=new Settings(Mode.STRAIGHT,Style.O2_RAIL,22,1,.35,90).structure(structure);
    s=s.options(s.options().route(s.options().routing().fit(false))
      .infrastructure(s.options().infrastructure().bridge(kind).span(24).autoSpan(auto).supportsEnabled(enabled)));
    return RoadGeometry.build(new Node(new V(.5,y,.5),0,0),new Node(new V(.5,y,120.5),0,0),s);
  }
  static boolean under(Part p,double underside){return p.base().stream().anyMatch(v->v.y()<underside-1e-7);}
  static Set<Long> supportStations(List<Part> parts,double underside){
    var out=new TreeSet<Long>();for(var p:parts)if(p.pier()&&under(p,underside))out.add(Math.round((p.a().z()-.5)*1000000));return out;
  }
  static List<LaneDeck.Strip> strips(Mesh mesh){
    var out=new ArrayList<LaneDeck.Strip>();for(int i=1;i<mesh.samples().size();i++)out.addAll(LaneDeck.strips(mesh,mesh.samples().get(i-1),mesh.samples().get(i)));return out;
  }
  public static void main(String[] args){
    boolean legacy=Boolean.getBoolean("sr204.expectLegacy");
    int disappeared=0;
    for(var kind:RoadInfrastructure.Bridge.values()){
      var on=mesh(kind,true,true,12,Structure.BRIDGE);var off=mesh(kind,false,true,12,Structure.BRIDGE);
      var kept=RoadInfrastructure.fixedBridgeParts(on,GROUND);var disabled=RoadInfrastructure.fixedBridgeParts(off,GROUND);
      double underside=12-on.settings().thickness();
      if(kind==RoadInfrastructure.Bridge.STANDARD){ok(disabled.isEmpty(),"standard infrastructure unexpectedly nonempty");continue;}
      if(legacy){
        ok(disabled.stream().anyMatch(p->under(p,underside)),"legacy fixture no longer has under-deck beam "+kind);
        System.out.println("LEGACY "+kind+" fixed-under="+disabled.stream().filter(p->under(p,underside)).count());
        continue;
      }
      ok(RoadInfrastructure.girderDepth(off)==0,"phantom girder depth "+kind);
      ok(disabled.stream().noneMatch(p->under(p,underside)),"disabled fixed under-deck beam "+kind);
      var planned=RoadInfrastructure.plan(off,GROUND);
      ok(planned.stream().noneMatch(p->under(p,underside)),"disabled final under-deck beam/column "+kind);
      ok(kept.stream().anyMatch(p->under(p,underside)),"enabled fixed stage lost beam "+kind);
      ok(kept.stream().filter(p->!under(p,underside)).toList().equals(disabled),"slab/edge/upper bridge assembly changed "+kind);
      ok(strips(on).equals(strips(off)),"physical deck changed "+kind);
      ok(on.samples().equals(off.samples()),"road path changed "+kind);
      ok(RoadInfrastructure.edgeReach(on,RoadStructures.sample(on,60))==RoadInfrastructure.edgeReach(off,RoadStructures.sample(off,60)),"edge shrank on disable "+kind);
      disappeared+=kept.size()-disabled.size();
    }
    if(legacy){System.out.println("PASS legacy reproduced checks="+checks);return;}
    var template=mesh(RoadInfrastructure.Bridge.TEMPLATE,false,true,12,Structure.BRIDGE);
    var ledges=RoadInfrastructure.plan(template,GROUND);
    ok(!ledges.isEmpty(),"empty bridge lost outer ledges");
    ok(ledges.stream().allMatch(p->p.material()==Material.CONCRETE&&!p.pier()&&Math.abs(p.height()-template.settings().thickness())<1e-7),"empty bridge has non-slab part");
    ok(ledges.stream().allMatch(p->p.base().stream().allMatch(v->Math.abs(v.x()-.5)>=10.9)),"empty bridge filled road/cut with slab part");
    ok(Math.abs(RoadInfrastructure.edgeReach(template,RoadStructures.sample(template,60))-2.1)<1e-7,"empty bridge did not retain the fixed 2.1m edge band");
    ok(RoadInfrastructure.edgeReach(template,template.first())==0&&RoadInfrastructure.edgeReach(template,template.last())==0,"empty bridge end taper lost");
    var longTemplate=RoadGeometry.build(new Node(new V(.5,12,.5),0,0),new Node(new V(.5,12,1200.5),0,0),template.settings());
    ok(RoadInfrastructure.edgeReach(longTemplate,RoadStructures.sample(longTemplate,600))==2.1,"empty bridge edge grew with invented tower span");
    // An outer-lane opening must keep the full supporting band open as well.
    var cutSettings=new Settings(Mode.STRAIGHT,Style.H4_RAIL,28,1,.35,90).structure(Structure.BRIDGE);
    cutSettings=cutSettings.options(cutSettings.options().route(cutSettings.options().routing().fit(false)).infrastructure(template.settings().options().infrastructure()));
    var uncut=RoadGeometry.build(new Node(new V(.5,12,.5),0,0),new Node(new V(.5,12,120.5),0,0),cutSettings);
    int outer=LaneAdditions.slots(uncut,60).stream().max(Comparator.comparingDouble(slot->LanePoints.lane(uncut,60,slot).position().sub(RoadStructures.sample(uncut,60).center()).dot(RoadStructures.sample(uncut,60).left()))).orElseThrow();
    var cut=new LaneSections.Cut(new UUID(1,2),outer,1,40,80,2,null,true,false,true);
    cutSettings=cutSettings.options(cutSettings.options().lanePoints(cutSettings.options().lanePoints().cuts(List.of(cut))));
    var opened=RoadGeometry.build(new Node(new V(.5,12,.5),0,0),new Node(new V(.5,12,120.5),0,0),cutSettings);
    var openedParts=RoadInfrastructure.plan(opened,GROUND);
    ok(LaneDeck.outerOpening(opened,60,1),"cut fixture lacks positive outer opening");
    ok(openedParts.stream().noneMatch(p->{var q=RoadQueries.horizontal(opened,p.a().add(p.b()).mul(.5));return q.lateral()>0&&q.sample().distance()>40&&q.sample().distance()<80;}),"empty bridge band bridged a real outer opening");
    ok(openedParts.stream().anyMatch(p->{var q=RoadQueries.horizontal(opened,p.a().add(p.b()).mul(.5));return q.lateral()<0&&q.sample().distance()>40&&q.sample().distance()<80;}),"opposite intact edge disappeared with opening");
    // The new band is real collision geometry even outside the nominal road ribbon.
    var otherSettings=new Settings(Mode.STRAIGHT,Style.C1_RAMP,4,1,.35,90).structure(Structure.BRIDGE);
    otherSettings=otherSettings.options(otherSettings.options().route(otherSettings.options().routing().fit(false)));
    var outside=RoadGeometry.build(new Node(new V(14,12,30),0,0),new Node(new V(14,12,90),0,0),otherSettings);
    ok(RoadClearance.contacts(template,outside).isEmpty(),"edge collision fixture touches nominal deck");
    ok(ledges.stream().anyMatch(p->RoadClearance.structureInvades(p,outside,RoadStructureClearance.headroom(outside))),"edge band has no real obstacle contact");
    Ground outsideGround=new Ground(){
      public double top(double x,double z,double y){return -20;}
      public boolean joined(V p){return false;}
      public boolean blocked(Part p){return RoadClearance.structureInvades(p,outside,RoadStructureClearance.headroom(outside));}
    };
    boolean rejected=false;try{RoadInfrastructure.plan(template,outsideGround);}catch(IllegalArgumentException expected){rejected=true;}
    ok(rejected,"disabled bridge suppressed a real outer-band collision");
    var auto=mesh(RoadInfrastructure.Bridge.TEMPLATE,true,true,12,Structure.BRIDGE);
    var manual=mesh(RoadInfrastructure.Bridge.TEMPLATE,true,false,12,Structure.BRIDGE);
    var automaticStations=supportStations(RoadInfrastructure.plan(auto,GROUND),11);
    var manualStations=supportStations(RoadInfrastructure.plan(manual,GROUND),11);
    ok(automaticStations.size()==2,"empty auto span not whole segment: "+automaticStations);
    ok(manualStations.size()==6,"empty manual span lost authored spacing: "+manualStations);
    var lower=mesh(RoadInfrastructure.Bridge.STANDARD,true,true,0,Structure.BRIDGE);
    for(double usable:new double[]{4.10,4.25,4.50}){
      var upper=mesh(RoadInfrastructure.Bridge.TEMPLATE,false,true,usable+1,Structure.BRIDGE);
      var contacts=RoadClearance.contacts(upper,lower);
      ok(!contacts.isEmpty(),"clearance fixture did not overlap");
      ok(contacts.stream().allMatch(c->Math.abs(c.usableClearance()-usable)<1e-7),"phantom depth in contacts");
      ok(contacts.stream().allMatch(c->c.required()==4.25),"custom-bridge headroom silently lowered");
      ok(contacts.stream().anyMatch(RoadClearance.Contact::blocked)==(usable<4.225),"wrong traffic rejection at "+usable);
      ok(MultiLevelBridges.compatible(lower,upper)==(usable>=4.25),"wrong shared-deck compatibility at "+usable);
      if(usable==4.50){
        var enabled=mesh(RoadInfrastructure.Bridge.TEMPLATE,true,true,usable+1,Structure.BRIDGE);
        ok(RoadClearance.contacts(enabled,lower).stream().anyMatch(RoadClearance.Contact::blocked),"enabled girder collision lost");
        ok(!MultiLevelBridges.compatible(lower,enabled),"enabled girder ignored for shared-deck compatibility");
      }
    }
    for(var mode:List.of(Structure.AUTO,Structure.BRIDGE)){
      var off=mesh(RoadInfrastructure.Bridge.STANDARD,false,true,12,mode);
      Ground fixed=new Ground(){
        public double top(double x,double z,double y){return -20;}
        public boolean joined(V p){return false;}
        public boolean blocked(Part p){return true;}
        public List<Part> fixedSupports(){return List.of(new Part(new V(0,0,40),new V(0,0,40),2,10,true,Material.CONCRETE));}
        public List<Part> sharedSupports(Mesh m,Sample s,double d,double w,double o){throw new AssertionError("disabled supports reached shared generator");}
      };
      ok(RoadStructures.supports(off,fixed,RoadFurniture.Phase.DEFAULT).isEmpty(),"disabled existing supports survived "+mode);
    }
    ok(RoadInfrastructure.Bridge.TEMPLATE.label.equals("空桥梁"),"UI label wrong");
    System.out.println("PASS bridge204 checks="+checks+" removed-fixed-beams="+disappeared+" auto-stations="+automaticStations+" manual-stations="+manualStations);
  }
}
