package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;
public final class Structures203Validation {
 static int checks; static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static Mesh mesh(double width,Structure structure,RoadInfrastructure.Config infra){var s=new Settings(Mode.STRAIGHT,Style.O2_RAIL,width,1,.35,90).structure(structure);s=s.options(s.options().route(s.options().routing().fit(false)).infrastructure(infra));return RoadGeometry.build(new Node(new V(.5,8,.5),0,0),new Node(new V(.5,8,64.5),0,0),s);}
 static Ground ground(double floor,boolean blocked){return new Ground(){public double top(double x,double z,double y){return floor;}public boolean joined(V p){return false;}public boolean blocked(Part p){return blocked&&p.pier()&&p.a().y()<7-1e-6;}};}
 public static void main(String[] args){
  if(args.length==0||args[0].equals("holes"))for(double w:new double[]{9,13,22,28}){
   var m=mesh(w,Structure.AUTO,RoadInfrastructure.Config.DEFAULT);
   for(double hx:new double[]{.1,.5,1,2,3,5,9})for(double hz:new double[]{12,12.3,12.8,20,20.3,20.8}){
    Ground g=new Ground(){public double top(double x,double z,double y){return x>=hx&&x<hx+1&&z>=hz&&z<hz+1?2:8;}public boolean joined(V p){return false;}public boolean blocked(Part p){return false;}};
    check(RoadStructures.supports(m,g,RoadFurniture.Phase.DEFAULT).isEmpty(),"hole generated supports width="+w+" hole="+hx+","+hz);
    check(RoadStreetscape.classify(m,g).isEmpty(),"single hole classified bridge width="+w+" hole="+hx+","+hz+" spans="+RoadStreetscape.classify(m,g));
   }
   check(!RoadStreetscape.classify(m,ground(0,false)).isEmpty(),"floating deck lost");
   Ground half=new Ground(){public double top(double x,double z,double y){return x<.5?8:0;}public boolean joined(V p){return false;}public boolean blocked(Part p){return false;}};
   check(RoadStructures.elevated(m,RoadStructures.sample(m,32),half,-1)!=RoadStructures.elevated(m,RoadStructures.sample(m,32),half,1),"half bank lost");
  }
  if(args.length==0||args[0].equals("supports")){
   var c=RoadInfrastructure.Config.DEFAULT.supportsEnabled(false);check(!c.bridge(RoadInfrastructure.Bridge.TEMPLATE).span(32).autoSpan(false).medianConversion(RoadInfrastructure.MedianConversion.GREEN).clearEdits().supportsEnabled(),"copy reenabled supports");
   for(var b:RoadInfrastructure.Bridge.values())for(var mode:List.of(Structure.AUTO,Structure.BRIDGE)){
    var m=mesh(22,mode,c.bridge(b));var parts=new ArrayList<Part>(RoadInfrastructure.plan(m,ground(0,false)));parts.addAll(RoadStructures.supports(m,ground(0,false),RoadFurniture.Phase.DEFAULT));
    check(parts.stream().noneMatch(p->p.pier()&&p.a().y()<8-m.settings().thickness()-1e-6),"disabled foundation present "+b+mode);
    RoadInfrastructure.plan(mesh(22,mode,RoadInfrastructure.Config.DEFAULT.bridge(b)),ground(Double.NaN,true));checks++;
   }
   var parts=RoadInfrastructure.plan(mesh(22,Structure.BRIDGE,c.bridge(RoadInfrastructure.Bridge.TEMPLATE)),ground(0,false));check(!parts.isEmpty(),"empty bridge missing edge slabs");check(parts.stream().allMatch(p->p.material()==Material.CONCRETE&&p.top().stream().allMatch(v->v.y()<=8+.001)),"template has upper fixtures");
  }
  var lower=mesh(22,Structure.BRIDGE,RoadInfrastructure.Config.DEFAULT);
  var upper=RoadGeometry.build(new Node(new V(.5,22,.5),0,0),new Node(new V(.5,22,64.5),0,0),lower.settings());
  var at=RoadStructures.sample(upper,32);check(MultiLevelBridges.compatible(lower,upper),"stacked fixture invalid");
  check(!MultiLevelBridges.supports(upper,List.of(lower),at,.35,.9,1.8,12,ground(0,false)).isEmpty(),"valid shared support lost");
  check(MultiLevelBridges.supports(upper,List.of(lower),at,.35,.9,1.8,12,ground(Double.NaN,false)).isEmpty(),"unreachable shared support retained");
  check(MultiLevelBridges.supports(upper,List.of(lower),at,.35,.9,1.8,12,ground(0,true)).isEmpty(),"blocked shared shafts retained");
  var beamBlocked=new Ground(){public double top(double x,double z,double y){return 0;}public boolean joined(V p){return false;}public boolean blocked(Part p){return !p.pier()&&p.a().y()<10;}};
  check(MultiLevelBridges.supports(upper,List.of(lower),at,.35,.9,1.8,12,beamBlocked).isEmpty(),"blocked lower crossbeam retained a partial frame");
  System.out.println("PASS structures203 checks="+checks);
 }
}



