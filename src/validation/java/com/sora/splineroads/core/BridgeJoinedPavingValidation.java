package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;
/** Actual generated custom-bridge edge slabs against a saved joining road's capped sidewalks. */
public final class BridgeJoinedPavingValidation {
  private static int checks,seams;
  private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
  public static void main(String[] args){boolean legacy=Boolean.getBoolean("sr201.expectLegacy");
    Ground ground=new Ground(){public double top(double x,double z,double below){return 0;}public boolean joined(V p){return false;}public boolean blocked(Part p){return false;}};
    for(var kind:List.of(RoadInfrastructure.Bridge.ARCH,RoadInfrastructure.Bridge.CABLE,RoadInfrastructure.Bridge.SUSPENSION))
      for(double grade:new double[]{0,.005,-.005}){
        var s=RoadLanes.configure(new Settings(Mode.STRAIGHT,Style.O2_RAIL,9,1,.35,90),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(3,3),4);
        s=s.options(s.options().route(s.options().routing().fit(false)));
        var prior=RoadGeometry.build(new Node(new V(.5,18-101*grade,-100.5),0,grade),new Node(new V(.5,18,.5),0,grade),s);
        prior=RoadGeometry.endCaps(prior,2,grade,grade);
        s=s.options(s.options().infrastructure(s.options().infrastructure().bridge(kind))).structure(Structure.BRIDGE);
        var current=RoadGeometry.build(new Node(new V(.5,18,.5),0,grade),new Node(new V(.5,18+300*grade,300.5),0,grade),s);
        var paving=RoadSidewalks.parts(prior,RoadSidewalks.Config.DEFAULT.enabled(true));var support=new RoadPavingSupport(paving);
        final var joining=prior;int blocked=0;
        for(var part:RoadInfrastructure.fixedBridgeParts(current,ground))if(support.blocks(part)){
          blocked++;seams++;
          check(RoadClearance.joiningFloor(part,current,prior),"unexpected non-floor collision");
          if(legacy){checks++;continue;}
          check(!support.blocks(part,walk->RoadClearance.joiningFloor(part,current,joining)&&RoadClearance.belowSurface(part,current,.025)),"connected floor still blocked");
          check(support.blocks(part,walk->false),"unrelated sidewalk became exempt");
          var delta=new V(0,.5,0);var raised=new Part(part.a().add(delta),part.b().add(delta),part.width(),part.height(),false,Material.CONCRETE).frames(part.frameA(),part.frameB());
          check(support.blocks(raised,walk->true),"slab protruding above walkway was exempt");
          var steel=new Part(part.a(),part.b(),part.width(),part.height(),false,Material.STEEL).frames(part.frameA(),part.frameB());
          check(support.blocks(steel,walk->true),"steel fixture was exempt");
        }
        check(blocked==2,"expected both old capped sidewalk contacts for "+kind+" grade="+grade+" got="+blocked);
      }
    check(seams==18,"missing seam fixtures");System.out.println("PASS seams="+seams+" checks="+checks+" legacy="+legacy);
  }
}
