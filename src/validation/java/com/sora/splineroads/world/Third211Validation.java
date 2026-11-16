package com.sora.splineroads.world;
import java.util.*;import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;import com.sora.splineroads.net.RoadNetwork;import io.netty.buffer.Unpooled;import net.minecraft.network.FriendlyByteBuf;import net.minecraft.nbt.*;
public final class Third211Validation {
 static int checks;static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 public static void main(String[]args)throws Exception{
  var d=Third211Inspection.load();var t=d.interchanges.get(UUID.fromString("c3ba2c13-3ca2-3977-9e62-80fbd51280c3")).copy();ParallelSpui.capture(d,t,null);var old=t.copy();var plan=Interchanges.plan(t);
  var command=Interchanges.command(t,plan,"interchange");var buf=new FriendlyByteBuf(Unpooled.buffer());
  try{new RoadNetwork.Action(command).encode(buf);System.out.println("ACTUAL_ACTION_BYTES="+buf.readableBytes());check(buf.readableBytes()<2048,"parallel request retained generated host data");check(RoadNetwork.Action.decode(buf).tag().equals(command),"actual action roundtrip changed inputs");}finally{buf.release();}
  check(t.equals(old),"submission mutated preview");for(String key:List.of("Id","Points","Options"))check(command.get(key).equals(t.get(key)),"missing authored field "+key);check(!command.contains("Main1")&&!command.contains("Main2"),"parallel host data sent");check(command.getList("FittedNodes",Tag.TAG_COMPOUND).size()==plan.anchors().size(),"missing fitted nodes");
  var ordinary=t.copy();ordinary.remove("ParallelJunction");ordinary.put("Main3",ordinary.getCompound("Main1").copy());var regular=Interchanges.command(ordinary,plan,"interchange");for(String key:List.of("Main1","Main2","Main3"))check(regular.get(key).equals(ordinary.get(key)),"ordinary editable settings lost "+key);
  var tooLarge=new FriendlyByteBuf(Unpooled.buffer());try{tooLarge.writeZero(65537);boolean refused=false;try{RoadNetwork.Action.decode(tooLarge);}catch(IllegalArgumentException expected){refused=true;}check(refused,"wire bound removed");}finally{tooLarge.release();}
  int ramps=0;for(var leg:plan.legs())if(leg.mesh().settings().style().ramp()){
   ramps++;var m=leg.mesh();double grade=0;for(int i=1;i<m.samples().size();i++){var v=m.samples().get(i).center().sub(m.samples().get(i-1).center());grade=Math.max(grade,Math.abs(v.y())/v.horizontalLength());check(Math.abs(v.y())<=.15*v.horizontalLength()+1e-8,"local ramp grade exceeded");}
   check(grade>=.10&&grade<=.15,"not compact grade");check(m.length()<200,"old long ramp retained");System.out.println("RAMP_LENGTH="+m.length()+" PEAK_GRADE="+grade);
  }check(ramps==4,"expected four ramps");
  var a=d.index.roads.get(UUID.fromString("1abf0a51-975f-3088-9f5b-061d4561fced")).mesh;var b=d.index.roads.get(UUID.fromString("6e9458be-f710-418c-896d-af0378a68024")).mesh;
  for(int side:new int[]{-1,1}){check(RoadStructures.cyclePlantingContinues(a,b,false,side),"reported fixed end not joined");check(RoadStructures.cyclePlantingContinues(b,a,true,side),"reciprocal join missing");check(!RoadStructures.cyclePlantingContinues(a,b,true,side),"distant end joined");}
  var moved=RoadRibbon.mesh(b.samples().stream().map(s->new Sample(s.center().add(new V(0,1,0)),s.left(),s.distance(),s.halfWidth())).toList(),b.settings());
  var plain=RoadRibbon.mesh(b.samples(),b.settings().options(b.settings().options().streetscape(b.settings().options().streetscape().separator(RoadStreetscape.Separator.LINE))));
  for(int side:new int[]{-1,1}){check(!RoadStructures.cyclePlantingContinues(a,moved,false,side),"grade separated planting joined");check(!RoadStructures.cyclePlantingContinues(a,plain,false,side),"non-green continuation joined");}
  var method=RoadStructures.class.getDeclaredMethod("cyclePlanting",Mesh.class,RoadStructures.Ground.class,List.class);method.setAccessible(true);
  var ground=new RoadStructures.Ground(){public double top(double x,double z,double y){return y;}public boolean blocked(RoadStructures.Part p){return false;}public boolean joined(V p){return false;}public boolean cyclePlantingContinues(Mesh m,boolean first,int side){return RoadStructures.cyclePlantingContinues(m,b,first,side);}};
  var parts=new ArrayList<RoadStructures.Part>();method.invoke(null,a,ground,parts);
  for(int side:new int[]{-1,1}){var at=a.last();var center=at.at(RoadProfile.layout(a,at).outer(side)+side*.75,-.45);check(parts.stream().anyMatch(p->p.material()==RoadStructures.Material.GREEN&&p.b().distance(center)<1e-6&&Math.abs(p.frameB().horizontalLength()-.6)<1e-6),"reported band still narrows at seam "+side);}
  System.out.println("Third211Validation PASS checks="+checks);
 }
}
