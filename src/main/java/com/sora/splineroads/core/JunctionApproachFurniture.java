package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;
/** Approach fixtures share ordinary road section dimensions and terrain classification. */
public final class JunctionApproachFurniture {
  public static double medianEnd(JunctionSpec spec,int arm,Mesh mesh){var a=spec.arms().get(arm);double ring=spec.kind()==JunctionSpec.Kind.ROUNDABOUT?spec.islandRadius()+spec.ringLanes()*spec.ringLaneWidth()-mesh.last().center().sub(spec.center()).horizontalLength():0;return a.crosswalk()?mesh.length()-ring-a.crossingSetback()-a.crossingWidth()-.5:mesh.length()-ring-1;}
  public static List<Part> plan(JunctionSpec spec,int index,Mesh mesh,Ground ground){
    var arm=spec.arms().get(index);var out=new ArrayList<Part>();double end=medianEnd(spec,index,mesh);
    double crossLo=end+.5,crossHi=crossLo+arm.crossingWidth();var stations=new TreeSet<Double>();
    for(double d=0;d<mesh.length();d+=.5)stations.add(d);for(double d:new double[]{end,crossLo,crossHi,mesh.length()})if(d>0&&d<=mesh.length())stations.add(d);
    var list=new ArrayList<>(stations);for(int i=1;i<list.size();i++){
      // Analytic cutbacks can lie a few ULPs past a half-block station.
      // Sampling both values returns the same endpoint; it is not a rail span.
      if(list.get(i)-list.get(i-1)<1e-6)continue;
      var a=RoadStructures.sample(mesh,list.get(i-1));var b=RoadStructures.sample(mesh,list.get(i));var mid=RoadStructures.sample(mesh,(a.distance()+b.distance())/2);boolean raised=RoadStreetscape.bridge(mesh,mid,ground),tunnel=RoadStreetscape.underground(mesh,mid.distance());
      if(a.distance()<end-1e-7&&arm.median()>.2){
        double ca=JunctionPlanner.approachMedian(arm,mesh,a,true,spec.leftTraffic()),cb=JunctionPlanner.approachMedian(arm,mesh,b,true,spec.leftTraffic());
        double wa=JunctionPlanner.approachMedian(arm,mesh,a,false,spec.leftTraffic()),wb=JunctionPlanner.approachMedian(arm,mesh,b,false,spec.leftTraffic());
        double green=arm.medianKind()==RoadProfile.Median.GREEN&&!tunnel?1-RoadStreetscape.medianConversionBlend(mesh,mid):0;
        if(green>.12)out.addAll(RoadStructures.planting(a.at(ca,0),b.at(cb,0),a.left(),b.left(),wa,wb,green,0));
        else {double band=raised?.42:.1;double gap=mesh.settings().options().streetscape().plantings().railGap();double separation=gap==0?0:gap+band;
          double ga=Math.min(separation,Math.max(0,wa-band)),gb=Math.min(separation,Math.max(0,wb-band));
          for(int side:Math.max(ga,gb)>1e-7?new int[]{-1,1}:new int[]{0})RoadStructures.barrier(out,a.at(ca+side*ga/2,0),b.at(cb+side*gb/2,0),false,raised,a.distance());
        }
      }
      for(int side:new int[]{-1,1}){
        boolean sideRaised=RoadStreetscape.bridge(mesh,mid,ground,side);
        if(arm.cycleWidth()>0&&a.distance()<end-1e-7){
          double innerA=JunctionPlanner.approachMotor(arm,mesh,a,side),innerB=JunctionPlanner.approachMotor(arm,mesh,b,side),separation=RoadStreetscape.separatorWidth(arm.external().options());
          if(separation>0){if(!sideRaised&&!tunnel){double fade=Math.min(6,end/2),fa=Settings.smooth(Math.min(1,Math.min(arm.attached()?fade:a.distance(),end-a.distance())/fade)),fb=Settings.smooth(Math.min(1,Math.min(arm.attached()?fade:b.distance(),end-b.distance())/fade));RoadStructures.cyclePlanter(out,a,b,side,innerA,innerB,separation,fa,fb);}
            else RoadStructures.barrier(out,a.at(innerA+side*separation/2,0),b.at(innerB+side*separation/2,0),false,sideRaised,a.distance());}
          if(arm.external().options().cycleRail())RoadStructures.barrier(out,a.at(innerA,0),b.at(innerB,0),false,false,a.distance());
        }
        boolean opening=arm.crosswalk()&&mid.distance()>crossLo&&mid.distance()<crossHi;
        var rail=arm.external().options().outerRail();if(!opening&&!tunnel&&(spec.outerRail()||rail==RoadProfile.OuterRail.ON||rail!=RoadProfile.OuterRail.OFF&&sideRaised))
          RoadStructures.barrier(out,a.at(side*(a.halfWidth()-.16),0),b.at(side*(b.halfWidth()-.16),0),false,sideRaised||spec.outerRail(),a.distance());
      }
    }RoadStructures.closePlantingEnds(out,mesh);return List.copyOf(out);
  }
  private JunctionApproachFurniture(){}
}
