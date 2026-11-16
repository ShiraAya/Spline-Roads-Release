package com.sora.splineroads.world;
import java.util.*;import java.security.*;import java.nio.*;import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;import com.sora.splineroads.core.InterchangePlanner.*;
import net.minecraft.nbt.*;import net.minecraft.core.BlockPos;
public class ServiceBounds204Validation{
 static int checks;static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static Options options(Preset p,int axis,boolean adjust){return new Options(p,false,1,96,20,5,axis==0?2:1,5,adjust,false,0,axis,5,false,false,0);}
 static Settings settings(boolean highway){var style=highway?Style.H4_RAIL:Style.O4_RAIL;var s=new Settings(Mode.STRAIGHT,style,style.defaultWidth(),1,.4,90);return s.options(s.options().extras(false,false,false).route(s.options().routing().fit(false)).infrastructure(s.options().infrastructure().gantry(RoadInfrastructure.Gantry.OFF)));}
 static Node[] nodes(int axis,double negative,double positive){return new Node[]{new Node(new V(axis==0?-negative:-400,axis==0?-59:-40,0),-90,0),new Node(new V(axis==0?positive:400,axis==0?-59:-40,0),-90,0),new Node(new V(0,axis==1?-59:-40,axis==1?-negative:-400),0,0),new Node(new V(0,axis==1?-59:-40,axis==1?positive:400),0,0)};}
 static Plan fixed(Preset p,int axis,double negative,double positive){return InterchangePlanner.plan(nodes(axis,negative,positive),settings(axis!=0),settings(axis!=1),options(p,axis,false));}
 static void bounded(Plan plan,int axis){
  V start=plan.anchors().get(axis*2).position(),end=plan.anchors().get(axis*2+1).position(),direction=end.sub(start).horizontalUnit();double length=end.sub(start).dot(direction);var seen=new HashSet<JunctionSpec>();
  for(var leg:plan.legs())if(leg.junction()!=null&&seen.add(leg.junction().spec()))for(int i=0;i<2;i++){double at=leg.junction().spec().arms().get(i).endpoint().position().sub(start).dot(direction);check(at>=2-1e-6&&at<=length-2+1e-6,"service port leaves <2m real mainpiece: "+at+" length="+length);}
  check(!seen.isEmpty(),"no service junctions");
  var main=plan.legs().stream().filter(l->l.junction()==null&&l.from()==axis&&l.to()==axis+2&&!l.mesh().settings().style().ramp()).toList();
  check(main.size()>=2,"service main lacks distinct outside pieces");
 }
 static String hash(Plan plan)throws Exception{
  var md=MessageDigest.getInstance("SHA-256");var bytes=ByteBuffer.allocate(64);
  for(var leg:plan.legs()){md.update(leg.name().getBytes(java.nio.charset.StandardCharsets.UTF_8));for(var s:leg.mesh().samples()){bytes.clear();bytes.putDouble(s.center().x()).putDouble(s.center().y()).putDouble(s.center().z()).putDouble(s.left().x()).putDouble(s.left().z()).putDouble(s.halfWidth()).putDouble(s.distance());md.update(bytes.array(),0,bytes.position());}}
  return HexFormat.of().formatHex(md.digest());
 }
 static CompoundTag fixture(int axis){
  var ns=nodes(axis,400,400);var t=new CompoundTag();var tags=new ListTag();for(var n:ns)tags.add(RoadRecord.writeNode(n));t.put("Nodes",tags);t.put("ContinuousLimits",tags.copy());t.putLongArray("Points",Arrays.stream(ns).mapToLong(n->BlockPos.containing(n.position().x(),n.position().y(),n.position().z()).asLong()).toArray());
  t.put("Main1",RoadRecord.writeSettings(settings(axis!=0)));t.put("Main2",RoadRecord.writeSettings(settings(axis!=1)));var sources=new ListTag();
  for(int i=0;i<2;i++){var a=ns[i*2];var b=ns[i*2+1];var r=new RoadRecord(new UUID(20421,i),new UUID(20421,20421),BlockPos.containing(a.position().x(),a.position().y(),a.position().z()),BlockPos.containing(b.position().x(),b.position().y(),b.position().z()),a,b,settings(axis!=i),false,4);var q=new CompoundTag();q.putInt("Axis",i);q.put("Road",r.header());sources.add(q);}t.put("ContinuousSources",sources);return t;
 }
 public static void main(String[]args)throws Exception{
  String mode=args.length==0?"green":args[0];
  if(mode.equals("red")){for(int axis=0;axis<2;axis++){double shortEnd=axis==0?50.1:45.296875;var p=fixed(Preset.SPUI,axis,shortEnd,shortEnd);boolean escaped=false;try{bounded(p,axis);}catch(AssertionError expected){escaped=true;}check(escaped,"baseline SPUI no longer reproduces clamped arm escape");}System.out.println("SERVICE_BOUNDS204 RED both service axes accept ports beyond selected ends");return;}
  for(var preset:List.of(Preset.DIAMOND,Preset.SPUI))for(int axis=0;axis<2;axis++)for(double size:new double[]{80,100,400}){var p=fixed(preset,axis,size,size);bounded(p,axis);System.out.println("SERVICE_BOUNDS204 VALID "+preset+" axis="+axis+" size="+size+" legs="+p.legs().size()+" sha256="+hash(p));}
  if(mode.equals("hashes"))return;
  for(int axis=0;axis<2;axis++){
   double shortEnd=axis==0?50.1:45.296875;
   for(double[] limits:new double[][]{{shortEnd,shortEnd},{55,100},{100,55},{56.9,100},{100,56.9}}){boolean rejected=false;try{fixed(Preset.SPUI,axis,limits[0],limits[1]);}catch(IllegalArgumentException expected){rejected=expected.getMessage().contains("至少2格直路");}check(rejected,"short SPUI service end accepted axis="+axis+" "+Arrays.toString(limits));}
   bounded(fixed(Preset.SPUI,axis,57,57),axis);
   for(var preset:List.of(Preset.DIAMOND,Preset.SPUI)){
    var t=fixture(axis);t.put("Options",Interchanges.write(options(preset,axis,true)));var p=Interchanges.plan(t);bounded(p,axis);
    var command=t.copy();command.put("SourceNodes",t.getList("Nodes",Tag.TAG_COMPOUND).copy());command.putInt("SourceAxesHash",HostAxes.signature(t));var targets=new ListTag();p.anchors().forEach(n->targets.add(RoadRecord.writeNode(n)));command.put("FittedNodes",targets);var checked=Interchanges.confirmedPlan(t,command);check(checked.anchors().equals(p.anchors()),"fixed confirmed endpoints differ");check(hash(checked).equals(hash(p)),"fixed confirmed geometry differs");
    System.out.println("SERVICE_BOUNDS204 FIT PASS "+preset+" axis="+axis+" anchors="+p.anchors());
   }
  }
  System.out.println("SERVICE_BOUNDS204 PASS checks="+checks+" both-axes/valid-hash-equivalence/short-end-rejection/fitted-confirmed");
 }
}
