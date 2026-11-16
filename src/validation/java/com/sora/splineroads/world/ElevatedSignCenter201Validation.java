package com.sora.splineroads.world;
import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;import java.util.*;
public class ElevatedSignCenter201Validation {
 public static void main(String[]args)throws Exception{int checks=0;var method=ElevatedSigns.class.getDeclaredMethod("placement",Mesh.class,LaneSections.Cut.class,double.class,boolean.class);method.setAccessible(true);
 for(boolean left:new boolean[]{false,true})for(int flow:new int[]{-1,1})for(var counts:List.of(new RoadLanes.Counts(3,3),new RoadLanes.Counts(2,3),new RoadLanes.Counts(3,2),new RoadLanes.Counts(3,0))){if(counts.reverse()==0&&flow<0)continue;
 var s=RoadLanes.configure(new Settings(Mode.STRAIGHT,Style.O2_RAIL,9,1,.35,90),RoadProfile.Type.ORDINARY,counts,4);s=s.options(s.options().traffic(left));
 var m=RoadGeometry.build(new Node(new V(0,4,0),0,0),new Node(new V(0,4,400),0,0),s);var cut=new LaneSections.Cut(UUID.randomUUID(),0,flow,200,200+flow*100,32,null,true,false,false,false);
 var support=(SmartSigns.Support)method.invoke(null,m,cut,18.,false);var key=new SmartSigns.Key(UUID.randomUUID(),0,1);var state=SmartSigns.State.empty(key).layout(SmartSigns.Mode.ROADSIDE,key.road(),List.of(new SmartSigns.Branch("elevated",List.of(),0,true,List.of())),"").supported(support);
 var t=SmartSigns.layout(m,state);var board=t.boards().stream().filter(b->b.branch().equals("elevated")).findFirst().orElseThrow();var at=RoadStructures.sample(m,200-flow*18);var profile=RoadProfile.layout(m,at);
 int side=(left?-1:1)*flow;double lateral=counts.reverse()>0?profile.medianCenter()+side*(profile.median()/2+2):side>0?profile.motorMin()+2:profile.motorMax()-2;var expected=at.at(lateral,0);
 var center=t.origin().add(t.right().mul(board.x()));if(Math.hypot(center.x()-expected.x(),center.z()-expected.z())>1e-7)throw new AssertionError("board is not centered on inner lane: left="+left+" flow="+flow+" counts="+counts+" error="+center.sub(expected));checks++;
 var restored=SmartSignCodec.read(SmartSignCodec.write(state)).support();if(!Objects.equals(restored.boardCenter(),support.boardCenter())||restored.foot().distance(support.foot())>1e-9||restored.right().distance(support.right())>1e-9)throw new AssertionError("support NBT center lost "+support+" != "+restored);checks++;
 }System.out.println("ElevatedSignCenter201Validation PASS checks="+checks);}
}
