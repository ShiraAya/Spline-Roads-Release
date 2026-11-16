package com.sora.splineroads.core;
import java.util.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
public final class Curved211Validation {
 static int checks;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static List<RoadAxis> axes(boolean single,double angle){
  var a=new ArrayList<V>();var b=new ArrayList<V>();double r=Math.toRadians(angle);
  for(int i=0;i<=32;i++){double x=-900+i*1800./32;a.add(new V(x,66,20*(1-x*x/810000)));}
  for(int i=0;i<=32;i++){double z=-900+i*(single?900.:1800.)/32;double x=12*(1-z*z/810000);b.add(new V(x*Math.cos(r)-z*Math.sin(r),56,x*Math.sin(r)+z*Math.cos(r)));}
  return List.of(new RoadAxis(a),new RoadAxis(b));
 }
 static Node[] nodes(List<RoadAxis> axes,boolean single){var list=new ArrayList<Node>();for(int i=0;i<2;i++){var a=axes.get(i);list.add(new Node(a.at(0),RoadPlanner.yaw(a.tangent(0)),0));if(i==0||!single)list.add(new Node(a.at(a.length()),RoadPlanner.yaw(a.tangent(a.length())),0));}return list.toArray(Node[]::new);}
 public static void main(String[]args)throws Exception{
  var main=new Settings(Mode.STRAIGHT,Style.O4_RAIL,18,1,.4,90);
  for(var preset:List.of(Preset.TRUMPET,Preset.DOUBLE_TRUMPET))for(var style:TrumpetStyle.values())for(boolean left:List.of(false,true))for(double skew:List.of(-20.,8.,20.)){
   boolean single=preset==Preset.TRUMPET;var axes=axes(single,skew);var nodes=nodes(axes,single);var options=new Options(preset,left,1,96,20,5,2,5,false).trumpet(style);
   check(CurvedRoadPlans.presets(nodes.length,true).contains(preset),"preset hidden");
   Plan plan;
   try{plan=CurvedRoadPlans.interchange(nodes,main,main,options,axes);}
   catch(IllegalArgumentException rejected){
    if(skew==8)throw rejected; // All eight canonical single/double/mirrored cases must build.
    check(rejected.getMessage().contains("曲线立交无法满足半径、纵坡或净空"),"missing physical fit reason");
    check(options.preset()==preset&&options.trumpetStyle()==style,"fit failure changed selected design");
    System.out.println("CURVED FIT REJECTION PASS "+preset+" "+style+" left="+left+" skew="+skew);continue;
   }
   check(plan.movements()==(single?4:8),"changed turn topology");
   for(int i=0;i<nodes.length;i++)check(plan.anchors().get(i).position().distance(nodes[i].position())<1e-5,"moved fixed host endpoint");
   for(int i=0;i<(single?1:2);i++)for(var sample:plan.legs().get(i).mesh().samples())check(axes.get(i).at(axes.get(i).project(sample.center())).sub(sample.center()).horizontalLength()<.03,"host curve drift");
   check(plan.minRadius()+.1>=20,"mapped curve below requested radius");
   int tails=0;
   for(var leg:plan.legs())if(leg.name().endsWith(left?"右转":"左转")){
    var axis=axes.get(leg.name().startsWith("CD")?1:0);int runs=0;boolean last=false;
    for(var sample:leg.mesh().samples()){boolean inside=sample.center().sub(axis.at(axis.project(sample.center()))).horizontalLength()<main.width()/2+sample.halfWidth();if(inside&&!last)runs++;last=inside;}
    check(runs==2,"curved directional ramp enters mainline again: "+leg.name()+" runs="+runs);tails++;
   }
   check(tails==(single?1:2),"directional ramp coverage");
   if(skew==8&&!left){var csv=new StringBuilder("leg,x,y,z,width\n");for(var leg:plan.legs())for(var sample:leg.mesh().samples())csv.append('"').append(leg.name().replace("\"","\"\"")).append('"').append(',').append(sample.center().x()).append(',').append(sample.center().y()).append(',').append(sample.center().z()).append(',').append(sample.halfWidth()*2).append('\n');java.nio.file.Files.writeString(java.nio.file.Path.of("work/feedback-211-nov15-followup/curved-"+(single?"single":"double")+"-"+style+".csv"),csv);}
   System.out.println("CURVED PASS "+preset+" "+style+" left="+left+" skew="+skew+" minRadius="+plan.minRadius());
  }
  var bad=axes(false,50);boolean rejected=false;try{CurvedRoadPlans.interchange(nodes(bad,false),main,main,new Options(Preset.DOUBLE_TRUMPET,false,1,96,20,5,2,5,false),bad);}catch(IllegalArgumentException expected){rejected=expected.getMessage().contains("65°");}check(rejected,"unsuitable crossing angle accepted");
  var valid=axes(false,8);var unsupported=new Options(Preset.PARCLO,false,1,96,20,5,2,5,false);boolean kept=false;
  try{CurvedRoadPlans.interchange(nodes(valid,false),main,main,unsupported,valid);}catch(IllegalArgumentException expected){kept=expected.getMessage().contains("当前样式已保留");}
  check(kept&&unsupported.preset()==Preset.PARCLO,"unsupported preset was substituted");
  System.out.println("CURVED ALL PASS assertions="+checks);
 }
}
