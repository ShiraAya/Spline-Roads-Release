package com.sora.splineroads.world;
import com.sora.splineroads.core.RoadGeometry.*;import java.nio.file.*;import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.nbt.*;
/** Exact actual sequence fixture: confirmed markers win over floating-point-derived source cells. */
public final class SequenceMarker204Validation {
 static int checks;static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 public static void main(String[]args)throws Exception{
  if(args.length!=1)throw new IllegalArgumentException("Usage: SequenceMarker204Validation <actual-retirement-fixture.nbt>");
  var fixture=NbtIo.readCompressed(Path.of(args[0]).toFile());var tag=new CompoundTag();tag.put("Nodes",fixture.getList("Targets",Tag.TAG_COMPOUND).copy());var nodes=tag.getList("Nodes",Tag.TAG_COMPOUND).stream().map(v->RoadRecord.readNode((CompoundTag)v)).toList();long[] points=nodes.stream().mapToLong(n->BlockPos.containing(n.position().x(),n.position().y(),n.position().z()).asLong()).toArray();
  var before=new HashSet<BlockPos>();var after=new HashSet<BlockPos>();int corrected=0;
  for(var value:fixture.getList("GeneratedEnds",Tag.TAG_COMPOUND)){
   var e=(CompoundTag)value;for(String key:List.of("A","B")){
    var old=BlockPos.of(e.getLong(key));var node=RoadRecord.readNode(e.getCompound("Node"+key));var fixed=Interchanges.anchor(points,tag,node.position(),old);before.add(old);after.add(fixed);
    if(!fixed.equals(old)){corrected++;check(old.getY()==-41&&fixed.getY()==-40&&old.getX()==830294&&fixed.getX()==830294,"only proven one-ulp floor cell corrected");check(node.position().distance(nodes.get(1).position())<1e-8,"changed source endpoint is numerically the confirmed B");}
    else if(nodes.stream().noneMatch(n->n.position().distance(node.position())<1e-5))check(fixed==old,"non-anchor source endpoint identities retained without replacement");
    else check(Arrays.stream(points).anyMatch(p->p==fixed.asLong()),"already correct selected anchor address retained");
   }
  }
  check(corrected==2,"both roads sharing the proven B cut must use the same confirmed marker cell");
  check(Arrays.stream(points).filter(p->before.contains(BlockPos.of(p))).count()==3,"fixture reproduces exactly one missing confirmed point");
  for(long point:points)check(after.contains(BlockPos.of(point)),"all four confirmed points now have real generated road endpoints");
  int absent=0,valid=0;for(var value:fixture.getList("Retirement",Tag.TAG_COMPOUND)){var e=(CompoundTag)value;BlockPos p=BlockPos.of(e.getLong("Point"));if(e.getBoolean("Exists")){valid++;check(!after.contains(p),"existing obsolete marker stays obsolete");}else{absent++;check(after.contains(p),"the phantom retirement becomes a real staged marker destination");}}
  check(absent==1&&valid==10,"actual retirement evidence retained: one phantom plus ten real old markers");
  for(int i=0;i<nodes.size();i++){
   var node=nodes.get(i);var fallback=BlockPos.of(points[i]).offset(0,-1,0);
   check(Interchanges.anchor(points,tag,node.position(),fallback).asLong()==points[i],"exact confirmed coordinate preferred "+i);
   var distinct=node.position().add(new V(.001,0,0));check(Interchanges.anchor(points,tag,distinct,fallback)==fallback,"different real endpoint in same block not forced to anchor "+i);
   var unrelated=node.position().add(new V(10,0,10));check(Interchanges.anchor(points,tag,unrelated,fallback)==fallback,"non-anchor source identity preserved "+i);
  }
  System.out.println("SEQUENCE_MARKER204 PASS checks="+checks+" exact actual B source-cell correction, all confirmed markers covered, real old retirements retained, no world writes");
 }
}
