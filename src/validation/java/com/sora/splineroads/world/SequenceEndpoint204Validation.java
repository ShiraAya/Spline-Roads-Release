package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadGeometry.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

/** Exact real snapshot regression plus sub-block/orientation/non-anchor negative controls. */
public final class SequenceEndpoint204Validation {
  static int checks;
  static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
  static Node shifted(Node n,double x,double y,double z,double yaw,double grade){return new Node(n.position().add(new V(x,y,z)),n.yaw()+yaw,n.grade()+grade);}
  public static void main(String[] args)throws Exception{
    if(args.length!=1)throw new IllegalArgumentException("Usage: SequenceEndpoint204Validation <diamond-endpoint-fixture.nbt>");
    var root=NbtIo.readCompressed(Path.of(args[0]).toFile());var saved=root.getCompound("Saved");
    var points=saved.getLongArray("Points");var anchors=saved.getList("Nodes",Tag.TAG_COMPOUND).stream().map(t->RoadRecord.readNode((CompoundTag)t)).toList();
    var roads=root.getList("Roads",Tag.TAG_COMPOUND).stream().map(t->RoadRecord.load((CompoundTag)t)).toList();
    var markers=root.getList("ObservedMarkers",Tag.TAG_COMPOUND);
    check(points.length==4&&anchors.size()==4&&markers.size()==4,"fixture contains four confirmed anchors and four observed original markers");
    int jittered=0,realCandidates=0;
    for(int i=0;i<points.length;i++){
      BlockPos pos=BlockPos.of(points[i]);Node expected=anchors.get(i);
      // Exact original marker snapshots are embedded in this small read-only audit fixture.
      var actual=markers.getCompound(i);
      check(actual.getLong("Point")==points[i],"observed marker address matches confirmed point "+i);
      Node observed=RoadRecord.readNode(actual.getCompound("Node"));if(!observed.equals(expected))jittered++;
      check(InterchangeEndpoints.generated(points,anchors,pos,observed)==expected,"actual DIAMOND marker "+i+" canonical exact confirmed anchor");
      for(var road:roads)for(boolean first:new boolean[]{true,false})if((first?road.a():road.b()).equals(pos)){
        Node generated=first?road.start():road.end();realCandidates++;
        check(InterchangeEndpoints.generated(points,anchors,pos,generated)==expected,"record endpoint "+road.id()+" first="+first+" canonical independent of iteration order");
      }
      for(Node changed:List.of(shifted(expected,.000001,0,0,0,0),shifted(expected,0,.000001,0,0,0),shifted(expected,0,0,.000001,0,0),shifted(expected,0,0,0,.001,0),shifted(expected,0,0,0,0,.001),shifted(expected,0,0,0,180,0))){
        check(InterchangeEndpoints.generated(points,anchors,pos,changed)==changed,"same block distinct real position/orientation preserved "+i+" "+changed);
      }
      Node other=shifted(expected,.01,.01,.01,0,0);
      check(BlockPos.containing(other.position().x(),other.position().y(),other.position().z()).equals(pos),"same block negative control precondition "+i);
      check(InterchangeEndpoints.generated(points,anchors,pos,other)==other,"same block actual other joint unchanged "+i);
      Node nonAnchor=new Node(expected.position(),expected.yaw(),expected.grade());
      check(InterchangeEndpoints.generated(points,anchors,pos.offset(1,0,0),nonAnchor)==nonAnchor,"non-anchor endpoint unchanged "+i);
    }
    check(jittered==2,"actual regression retains exact original two-marker failure");
    check(realCandidates==6,"all six current indexed road endpoints examined");
    System.out.println("PASS TOTAL "+checks+"; self-contained snapshot validation; exact NBT endpoint assertion unchanged; no server/client run");
  }
}
