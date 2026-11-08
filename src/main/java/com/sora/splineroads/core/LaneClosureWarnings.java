package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Road-surface warning Xs before a real rectangular closure. No fake pavement. */
public final class LaneClosureWarnings {
  public static List<RoadJunction.Paint> paint(Mesh mesh){
    return paint(mesh,List.of());
  }
  public static List<RoadJunction.Paint> paint(Mesh mesh,List<Mesh> neighbors){
    var out=new ArrayList<RoadJunction.Paint>();var raw=LaneSections.reference(mesh);
    var used=new HashSet<String>();
    for(var cut:mesh.settings().options().lanePoints().cuts())if(cut.rectangular()&&cut.arrival()){
      double start=Math.min(cut.begin(),cut.end()),end=Math.max(cut.begin(),cut.end());
      var lane=LanePoints.lane(raw,Math.max(0,Math.min(raw.length(),start)),cut.lane());
      double boundary=lane.sign()>0?start:end;
      // An adjacent/overlapping reservation has no new approach at this boundary.
      double before=boundary-lane.sign()*.25,after=boundary+lane.sign()*.25;
      if(before<0||before>raw.length()||after<0||after>raw.length()
          ||!LaneSections.active(mesh,before,cut.lane())||LaneSections.active(mesh,after,cut.lane()))continue;
      if(!used.add(cut.lane()+":"+Math.round(boundary*10000)))continue;
      for(double lead:new double[]{8,16,24}){
        double station=boundary-lane.sign()*lead;
        if(station<3||station>raw.length()-3)continue;
        double half=Math.min(1.4,lane.width()*.32);
        for(int slope:new int[]{-1,1}){
          var vertices=new ArrayList<V>();boolean fits=true;
          for(double[] q:new double[][]{{-2,-.10},{-2,.10},{2,.10},{2,-.10}}){
            double d=station+lane.sign()*q[0];var at=LanePoints.lane(raw,d,cut.lane());
            V v=at.position().add(at.direction().left().mul(slope*q[0]*half/2+q[1]));
            if(!LaneSections.active(mesh,d,cut.lane())||!RoadQueries.contains(mesh,v,-.08,.1))fits=false;
            vertices.add(v);
          }
          if(fits)out.add(new RoadJunction.Paint(List.copyOf(vertices),0xEDEEE2));
        }
      }
    }
    boolean elevated=!mesh.settings().options().lanePoints().access().isEmpty();
    var mouths=RoadRailJoin.mouths(neighbors.stream().filter(n->LaneMerge.linkedTo(mesh,n)||LaneMerge.linkedTo(n,mesh)
        ||elevated&&(n.settings().options().ends().startHold()>0||n.settings().options().ends().endHold()>0)).toList());
    // Paint joins the lane/outer stripes, not the narrower physical hole left
    // between rail footings. A connected mouth is open to traffic, never a stop bar.
    for(var cap:LaneDeck.caps(mesh)){
      V axis=cap.b().sub(cap.a()).horizontalUnit(),inward=axis.left().mul(.10);
      var at=RoadQueries.horizontal(mesh,cap.a().add(cap.b()).mul(.5)).sample();
      V a=paintEnd(mesh,at,cap.a()).sub(axis.mul(.06)).add(inward);
      V b=paintEnd(mesh,at,cap.b()).add(axis.mul(.06)).add(inward);
      for(var span:mouths.exposedMouth(a,b))out.add(new RoadJunction.Paint(List.of(span.a().sub(inward.mul(.8)),span.b().sub(inward.mul(.8)),
          span.b().add(inward.mul(.8)),span.a().add(inward.mul(.8))),0xEDEEE2));
    }
    return List.copyOf(out);
  }
  private static V paintEnd(Mesh mesh,Sample at,V point){
    double offset=point.sub(at.center()).dot(at.left());
    if(Math.abs(offset)>=at.halfWidth()-1e-5)return at.at(RoadSurface.edgeOffset(mesh,at,offset>0?1:-1),0);
    double nearest=offset,error=.8;
    for(double boundary:RoadProfile.layout(mesh,at).dividers())if(Math.abs(boundary-offset)<error){nearest=boundary;error=Math.abs(boundary-offset);}
    return at.at(nearest,0);
  }
  /** Do not invite straight-ahead traffic with an arrow underneath an X warning. */
  public static boolean covers(Mesh mesh,double station,int slot){
    for(var cut:mesh.settings().options().lanePoints().cuts())if(cut.rectangular()&&cut.arrival()&&cut.lane()==slot){
      var lane=LanePoints.lane(LaneSections.reference(mesh),station,slot);
      double boundary=lane.sign()>0?Math.min(cut.begin(),cut.end()):Math.max(cut.begin(),cut.end());
      double lead=lane.sign()*(boundary-station);
      double before=boundary-lane.sign()*.25,after=boundary+lane.sign()*.25;
      if(lead>=0&&lead<=29&&before>=0&&after>=0&&before<=mesh.length()&&after<=mesh.length()
          &&LaneSections.active(mesh,before,slot)&&!LaneSections.active(mesh,after,slot))return true;
    }return false;
  }
  private LaneClosureWarnings(){}
}
