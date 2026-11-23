package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import com.sora.splineroads.net.*;
import java.util.*;
import java.nio.file.*;
import net.minecraft.nbt.*;
public final class FeedbackFix4Core {
 static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
 static Ground flat(double top){return new Ground(){public double top(double x,double z,double y){return top;}public boolean joined(V p){return false;}public boolean blocked(Part p){return false;}};}
 public static void main(String[] args)throws Exception{
  var root=NbtIo.readCompressed(Path.of(args[0]).toFile()).getCompound("data");var all=new LinkedHashMap<UUID,RoadRecord>();
  for(var t:root.getList("Roads",Tag.TAG_COMPOUND)){var r=RoadRecord.load((CompoundTag)t);all.put(r.id(),r);}
  int packets=0;
  for(var road:all.values()){
   var draft=new CompoundTag();draft.putString("Kind","road");draft.putUUID("Id",road.id());draft.putLong("A",road.a().asLong());draft.putLong("B",road.b().asLong());
   var current=RoadRecord.writeSettings(road.settings());draft.put("Settings",current);draft=RoadDraft.baseline(draft);
   var action=RoadDraft.action(draft,false);var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
   try{new RoadNetwork.Action(action).encode(buffer);int bytes=buffer.readableBytes();check(bytes<65536,"packet still oversized "+road.id()+" "+bytes);
    var decoded=RoadNetwork.Action.decode(buffer).tag();var merged=RoadDraft.reconcileSettings(decoded.getCompound("BaseSettings"),decoded.getCompound("Settings"),current);
    check(merged.equals(current),"tool metadata changed "+road.id());packets++;
    if(road.id().toString().equals("2618586e-ceb0-4a05-839d-cd6080c8d27c"))System.out.println("FIX4_PACKET_BRIDGE old="+FeedbackFix4Inspect.bytes(current)+" new="+bytes);
   }finally{buffer.release();}
  }System.out.println("FIX4_PACKET_PASS roads="+packets);
  var settings=new Settings(Mode.STRAIGHT,Style.R1,5,1,.35,90).structure(Structure.AUTO);
  for(double epsilon:new double[]{-1e-12,0,1e-12}){
   var mesh=RoadGeometry.build(new Node(new V(0,56.125+epsilon,0),0,0),new Node(new V(0,56.125+epsilon,40),0,0),settings);
   check(RoadStreetscape.classifySides(mesh,flat(56)).negativeRaised().isEmpty(),"ground epsilon became bridge");
   check(!RoadStreetscape.classifySides(mesh,flat(50)).negativeRaised().isEmpty(),"true bridge flattened");
  }System.out.println("FIX4_CLASSIFICATION_PASS");
  var bars=new ArrayList<Part>();RoadStructures.barrier(bars,new V(0,0,0),new V(0,0,4),false,false,.5);bars.removeIf(p->p.material()==Material.DARK_STEEL);
  var finished=RoadRailSlabClipping.clip(bars,List.of());check(finished.stream().filter(p->p.material()==Material.DARK_STEEL).count()==2,"final rail terminals missing");
  check(RoadRailSlabClipping.clip(finished,List.of()).equals(finished),"terminal pass not idempotent");System.out.println("FIX4_TERMINALS_PASS");
  var mesh=RoadGeometry.build(new Node(new V(0,0,0),0,0),new Node(new V(0,0,40),0,0),settings);
  var ground=new Ground(){public double top(double x,double z,double y){return 0;}public double surface(double x,double z,double y){return 0;}public double walkwaySurface(double x,double z,double y){return x>2.5?1:Double.NaN;}public boolean joined(V p){return false;}public boolean blocked(Part p){return false;}};
  var walls=RoadAutoTunnels.enclose(mesh,ground,List.of());check(!walls.isEmpty(),"shallow sidewalk not retained");check(walls.stream().noneMatch(p->p.model().startsWith("sr:auto_roof:")),"sidewalk became roof");
  var tactile=new Part(new V(3.25,1,8),new V(3.25,1,9),.4,.02,false,Material.TACTILE);
  check(TactileSurface.outsideWalls(List.of(tactile),walls).isEmpty(),"tactile visible inside wall");System.out.println("FIX4_SIDEWALK_WALL_TACTILE_PASS");
  var host=all.get(UUID.fromString("680cdaf5-5dfb-4521-ab08-6319d0ba7bd8"));System.out.println("FIX4_HOST_CUTS "+LaneTopology.metadata(host).cuts());
  var noPlants=new Ground(){public double top(double x,double z,double y){return 56;}public boolean joined(V p){return false;}public boolean blocked(Part p){return false;}public boolean closedLanePlanting(UUID id){return false;}};
  var paved=LaneClosureLandscape.plan(host.mesh(),noPlants);check(paved.stream().anyMatch(LaneClosureLandscape::paved),"underground ramp gore has no asphalt");check(paved.stream().noneMatch(p->p.material()==Material.GREEN),"gore planted instead of paved");System.out.println("FIX4_GORE_PASS parts="+paved.size());
  var cut=LaneTopology.metadata(host).cuts().get(0);var at=RoadStructures.sample(host.mesh(),cut.begin()+5);var lane=LanePoints.lane(host.mesh(),at.distance(),cut.lane());var edge=at.at(Math.signum(lane.position().sub(at.center()).dot(at.left()))*(at.halfWidth()-.05),0);
  check(paved.stream().anyMatch(p->{var top=p.top();for(int i=1;i<top.size()-1;i++)if(Double.isFinite(TactileSurface.triangle(edge,top.get(0),top.get(i),top.get(i+1))))return true;return false;}),"closed shoulder still exposes terrain");System.out.println("FIX4_GORE_SHOULDER_PASS");
  var id=UUID.fromString("161cbbbe-5b24-41c4-8f95-b3e2c8187856");var ramp=all.get(id);
  System.out.println("FIX4_RAMP_BEFORE max="+ramp.mesh().max().y()+" link="+LaneTopology.metadata(ramp).link());long start=System.nanoTime();
  var rebuilt=LaneRamps.reconfigure(ramp,ramp.settings(),all);
  check(LaneTopology.metadata(rebuilt).link().equals(LaneTopology.metadata(ramp).link()),"ramp links changed");
  check(rebuilt.mesh().first().center().distance(ramp.mesh().first().center())<1e-5&&rebuilt.mesh().last().center().distance(ramp.mesh().last().center())<1e-5,"ramp endpoints moved");
  check(rebuilt.mesh().max().y()<=Math.max(rebuilt.start().position().y(),rebuilt.end().position().y())+1e-5,"gratuitous crest persists "+rebuilt.mesh().max().y());
  LaneRamps.validate(rebuilt.mesh(),all,id,LaneTopology.metadata(rebuilt).link());
  System.out.println("FIX4_RAMP_PASS max="+rebuilt.mesh().max().y()+" ms="+(System.nanoTime()-start)/1e6);
  System.out.println("FIX4_CORE_PASS");
 }
}
