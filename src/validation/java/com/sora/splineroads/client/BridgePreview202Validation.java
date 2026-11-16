package com.sora.splineroads.client;
import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;import com.sora.splineroads.core.RoadStructures.*;import com.sora.splineroads.world.*;import net.minecraft.core.BlockPos;import java.util.*;
/** Actual client obstacle decision, generated bridge fascia and ordinary saved end caps. */
public final class BridgePreview202Validation {
 private static int checks,cases; private static void ok(boolean v,String msg){checks++;if(!v)throw new AssertionError(msg);}
 private static BlockPos pos(Node n){var p=n.position();return BlockPos.containing(p.x(),p.y(),p.z());}
 public static void main(String[] args){Ground ground=new Ground(){public double top(double x,double z,double y){return -60;}public boolean joined(V p){return false;}public boolean blocked(Part p){return false;}};
 for(boolean walk:new boolean[]{false,true})for(var kind:List.of(RoadInfrastructure.Bridge.ARCH,RoadInfrastructure.Bridge.CABLE,RoadInfrastructure.Bridge.SUSPENSION))for(boolean reverse:new boolean[]{false,true}){
 var s=RoadLanes.configure(new Settings(Mode.STRAIGHT,Style.O2_RAIL,9,1,.35,90),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(3,3),4).structure(Structure.BRIDGE);s=s.options(s.options().route(s.options().routing().fit(false)).sidewalk(RoadSidewalks.Config.DEFAULT.enabled(walk)).extras(false,false,false));
 var a=new Node(new V(-478.5,-40,.5),-90,0);var b=new Node(new V(-578.5,-40,.5),-90,0);var c=new Node(new V(-878.5,-40,.5),-90,0);
 var old=new RoadRecord(UUID.randomUUID(),new UUID(0,0),pos(a),pos(b),a,b,s,false,4).caps(2);if(reverse)old=new RoadRecord(old.id(),old.owner(),pos(b),pos(a),new Node(b.position(),90,0),new Node(a.position(),90,0),s,false,4).caps(1);
 old=old.structures(RoadStructures.plan(old.mesh(),ground));var ns=s.options(s.options().infrastructure(s.options().infrastructure().bridge(kind)));var next=new RoadRecord(UUID.randomUUID(),old.owner(),pos(b),pos(c),b,c,ns,false,4).caps(2);
 var obstacles=List.of(new RoadIndex.Built(old,true));var client=RoadPreviewGround.create(null,next.mesh(),next.id(),obstacles,null,null,null,next);
 var unrelated=new RoadRecord(next.id(),next.owner(),next.a().offset(0,0,2),next.b().offset(0,0,2),next.start(),next.end(),ns,false,4).caps(2);var control=RoadPreviewGround.create(null,unrelated.mesh(),unrelated.id(),obstacles,null,null,null,unrelated);
 int seamCount=0;for(var p:RoadInfrastructure.fixedBridgeParts(next.mesh(),ground)){
   ok(!client.blocked(p),"valid client bridge blocked "+kind+" walk="+walk+" reverse="+reverse+" at="+p.a());
   if(control.blocked(p)&&RoadClearance.joiningFloor(p,next.mesh(),old.mesh())){
    seamCount++;ok(p.a().x()==b.position().x()||p.b().x()==b.position().x(),"unexpected distant blocked component");
    var steel=new Part(p.a(),p.b(),p.width(),p.height(),p.pier(),Material.STEEL).frames(p.frameA(),p.frameB());ok(client.blocked(steel),"steel beam collision was exempt");
    var delta=new V(0,.5,0);var raised=new Part(p.a().add(delta),p.b().add(delta),p.width(),p.height(),p.pier(),p.material()).frames(p.frameA(),p.frameB());ok(client.blocked(raised),"raised concrete enters traffic space");
   }
 }
 ok(seamCount==2,"identity negative control did not reject both seam slabs: "+seamCount);cases++;
 }
 System.out.println("SR202 CLIENT BRIDGE PASS cases="+cases+" checks="+checks+"; actual client obstacle predicates, identity and physical intrusion negative controls");}
}
