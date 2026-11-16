package com.sora.splineroads.core;
import java.util.*;import java.nio.file.*;import com.sora.splineroads.core.RoadGeometry.*;import com.sora.splineroads.core.InterchangePlanner.*;
public class Trumpet211Validation {
 public static void main(String[] args)throws Exception{
  var main=new Settings(Mode.STRAIGHT,Style.O4_RAIL,18,1,.4,90);
  for(var style:TrumpetStyle.values())for(boolean left:List.of(false,true)){
   var o=new Options(Preset.TRUMPET,left,1,96,20,5,2,5,false).trumpet(style);
   var nodes=new Node[]{new Node(new V(-600,56,0),-90,0),new Node(new V(600,56,0),-90,0),new Node(new V(0,56,-600),0,0)};
   try{var p=InterchangePlanner.plan(nodes,main,main,o);System.out.println("TRUMPET "+style+" left="+left+" legs="+p.legs().size()+" radius="+p.minRadius()+" height="+p.highest());
    var crossing=p.legs().stream().filter(l->l.mesh().settings().style().ramp()).map(l->l.mesh().samples().stream().min(Comparator.comparingDouble(s->Math.hypot(s.center().x(),s.center().z()))).orElseThrow()).filter(s->Math.abs(s.center().x())<10&&Math.abs(s.center().z())<1).toList();
    if(crossing.size()!=2)throw new AssertionError("trumpet must have two adjacent crossing carriageways");
    double separation=Math.abs(crossing.get(0).center().x()-crossing.get(1).center().x());
    double heightGap=Math.abs(crossing.get(0).center().y()-crossing.get(1).center().y());
    System.out.println("PAIRED CROSSING separation="+separation+" heightGap="+heightGap);
    if(Math.abs(separation-(o.rampWidth()+1))>.1||heightGap>.25)throw new AssertionError("crossing carriageways separated");
    if(!left){
      // On this fixed 1,200-block fixture, the directional tail must have
      // already turned inward by x=200. The old outward bulge reached z=87.
      double x=style==TrumpetStyle.A?-200:200;
      var tail=p.legs().get(p.legs().size()-1).mesh().samples().stream().min(Comparator.comparingDouble(s->Math.abs(s.center().x()-x))).orElseThrow();
      if(tail.center().z()>40||tail.center().z()<10)throw new AssertionError("directional tail is not inward concave: "+tail.center());
      System.out.println("CONCAVE TAIL PASS "+style+" at="+tail.center());
    }
    StringBuilder svg=new StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' viewBox='-650 -650 1300 1000' width='1300' height='1000'><rect x='-650' y='-650' width='1300' height='1000' fill='white'/>");String[] colors={"#888","#888","#c33","#08b","#8a0","#72a"};int k=0;
    for(var l:p.legs()){svg.append("<polyline fill='none' stroke='"+colors[k++%6]+"' stroke-width='"+l.mesh().settings().width()+"' points='");for(var a:l.mesh().samples())svg.append(a.center().x()+","+a.center().z()+" ");svg.append("'/>");}Files.writeString(Path.of("work/feedback-211-nov15/trumpet-"+style+"-"+left+".svg"),svg.append("</svg>"));
   }catch(Exception e){System.out.println("TRUMPET FAIL "+style+" left="+left+" "+e);throw e;}
  }
  int failures=0;
  for(var preset:List.of(Preset.TRUMPET,Preset.DOUBLE_TRUMPET))for(var style:TrumpetStyle.values())for(int lanes:new int[]{1,2})for(double skew:new double[]{0,12}){
    double z= Math.toRadians(skew),span=skew==0?1000:1500;var o=new Options(preset,false,lanes,96,20,5,2,5*lanes,false).trumpet(style);
    Node[] nodes=preset==Preset.TRUMPET?new Node[]{new Node(new V(-600,56,0),-90,0),new Node(new V(600,56,0),-90,0),new Node(new V(-600*Math.sin(z),56,-600*Math.cos(z)),skew,0)}:new Node[]{new Node(new V(-span,56,0),-90,0),new Node(new V(span,56,0),-90,0),new Node(new V(-span*Math.sin(z),66,-span*Math.cos(z)),skew,0),new Node(new V(span*Math.sin(z),66,span*Math.cos(z)),skew,0)};
    try{var p=InterchangePlanner.plan(nodes,main,main,o);System.out.println("CASE PASS "+preset+" "+style+" lanes="+lanes+" skew="+skew+" legs="+p.legs().size()+" radius="+p.minRadius());}
    catch(Exception e){failures++;System.out.println("CASE FAIL "+preset+" "+style+" lanes="+lanes+" skew="+skew+" "+e);}
  }
  if(failures>0)throw new AssertionError("failures="+failures);
 }
}
