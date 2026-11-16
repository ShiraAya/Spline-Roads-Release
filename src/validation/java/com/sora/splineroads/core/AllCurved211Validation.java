package com.sora.splineroads.core;
import java.util.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
public final class AllCurved211Validation {
 static int checks,passed,failed;
 static void check(boolean ok,String s){checks++;if(!ok)throw new AssertionError(s);}
 public static void main(String[]args){
  var main=new Settings(Mode.STRAIGHT,Style.O4_RAIL,18,1,.4,90);
  for(int count:new int[]{3,4,5,6})for(var preset:InterchangePlanner.presets(count))for(boolean left:new boolean[]{false,true}){
   try{
    var options=new Options(preset,left,1,96,20,5,2,5,false);
    List<RoadAxis> axes;Node[] nodes;Plan plan;
    if(count<5){
     var raw=Curved211Validation.axes(count==3,0);double gap=InterchangePlanner.minimumDifference(main,main,CurvedRoadPlans.planningOptions(options));
     axes=List.of(new RoadAxis(raw.get(0).points().stream().map(v->new V(v.x(),56+gap,v.z())).toList()),raw.get(1));nodes=Curved211Validation.nodes(axes,count==3);
     plan=CurvedRoadPlans.interchange(nodes,main,main,options,axes);
    }else{
     var list=new ArrayList<RoadAxis>();var ends=new ArrayList<Node>();
     for(int a=0;a<3;a++){var pts=new ArrayList<V>();double angle=a*Math.PI/3;V u=new V(Math.cos(angle),0,Math.sin(angle));
      for(int i=0;i<=64;i++){double t=-900+i*(a==2&&count==5?900.:1800.)/64;V q=u.mul(t).add(u.left().mul(t*t*.00001));pts.add(new V(q.x(),56+(2-a)*16,q.z()));}
      var axis=new RoadAxis(pts);list.add(axis);ends.add(new Node(axis.at(0),RoadPlanner.yaw(axis.tangent(0)),0));if(a<2||count==6)ends.add(new Node(axis.at(axis.length()),RoadPlanner.yaw(axis.tangent(axis.length())),0));
     }axes=list;nodes=ends.toArray(Node[]::new);plan=CurvedRoadPlans.multi(nodes,new Settings[]{main,main,main},options,56,axes);
    }
    check(CurvedRoadPlans.presets(count,true).contains(preset),"hidden preset");check(plan.anchors().size()==count,"anchor count");
    for(int i=0;i<count;i++)check(plan.anchors().get(i).equals(nodes[i]),"fixed endpoint moved");
    check(plan.minRadius()+.1>=options.radius(),"radius");
    if(preset==Preset.ROUNDABOUT)check(plan.legs().stream().anyMatch(l->l.mesh().closed()),"ring lost closed metadata");
    for(var leg:plan.legs())if(leg.name().startsWith("主路 ")||leg.name().equals("E 支路")){
      int axis=leg.name().equals("E 支路")?2:Integer.parseInt(leg.name().substring(3,4))-1;
      var path=axes.get(axis);for(int i=0;i<leg.mesh().samples().size();i+=32){V point=leg.mesh().samples().get(i).center();check(path.at(path.project(point)).sub(point).horizontalLength()<.03,"host path straightened: "+leg.name());}
    }
    for(var leg:plan.legs()){RoadGrades.validate(leg.mesh());if(leg.junction()!=null){check(leg.mesh().equals(leg.junction().get().mesh()),"junction metadata differs");check(!leg.junction().get().paint().isEmpty()||leg.junction().get().arm()<0,"missing junction paint");}}
    if(preset==Preset.SPUI||preset==Preset.DIAMOND)check(plan.legs().stream().anyMatch(l->l.junction()!=null),"lost junction metadata");
    passed++;System.out.println("ALL CURVED PASS "+preset+" left="+left+" legs="+plan.legs().size()+" radius="+plan.minRadius());
   }catch(Exception|AssertionError failure){failed++;System.out.println("ALL CURVED FAIL "+preset+" left="+left+" "+failure);failure.printStackTrace();}
  }
  System.out.println("ALL CURVED RESULT passed="+passed+" failed="+failed+" checks="+checks);if(failed>0)throw new AssertionError("curved types failed");
 }
}
