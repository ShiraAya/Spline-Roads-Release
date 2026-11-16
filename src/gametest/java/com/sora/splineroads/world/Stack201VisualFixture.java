package com.sora.splineroads.world;
import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.server.level.ServerPlayer;import net.minecraft.world.level.levelgen.Heightmap;
/** Actual STACK transaction with the same authored layout as the new feedback regressions. */
public final class Stack201VisualFixture {
 public record Scene(UUID group,List<UUID> roads,List<UUID> centralRoads,UUID feed,double ground,V nose,V guide){}
 public static Scene create(ServerPlayer player){
  var level=player.serverLevel();level.getChunkAt(BlockPos.ZERO);double ground=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,0,0),y=ground+.125;
  var nodes=List.of(new Node(new V(-600,y,0),-90,0),new Node(new V(600,y,0),-90,0),new Node(new V(0,y+21,-600),0,0),new Node(new V(0,y+21,600),0,0));
  long[] points=nodes.stream().map(n->Curve201GameTests.marker(level,n)).mapToLong(BlockPos::asLong).toArray();
  var command=Interchanges.payload(level,null,points,null);var settings=new Settings(Mode.STRAIGHT,Style.H4_RAIL,28,1,.4,90);command.put("Main1",RoadRecord.writeSettings(settings));command.put("Main2",RoadRecord.writeSettings(settings));command.put("Options",Interchanges.write(new InterchangePlanner.Options(InterchangePlanner.Preset.STACK,false,1,96,20,5,2)));
  var data=RoadData.get(level);var before=Set.copyOf(data.interchanges.keySet());Interchanges.build(level,null,command);var group=data.interchanges.keySet().stream().filter(id->!before.contains(id)).findFirst().orElseThrow();var saved=data.interchanges.get(group);var plan=Interchanges.plan(saved);int part=-1;for(int i=0;i<plan.legs().size();i++)if(plan.legs().get(i).name().equals("共用汇出 0"))part=i;if(part<0)throw new AssertionError("actual STACK lacks shared feed");
  var feed=UUID.nameUUIDFromBytes((group+":"+part).getBytes(java.nio.charset.StandardCharsets.UTF_8));if(!data.index.roads.containsKey(feed))throw new AssertionError("actual STACK feed record missing");var roads=data.index.roads.values().stream().filter(r->group.equals(r.record.assembly())).map(r->r.record.id()).toList();if(roads.size()<10)throw new AssertionError("actual STACK assembly incomplete "+roads.size());
  var nose=new V(-472.5,y,13.66);var guide=new V(-520,y,10);System.out.println("SR201 NATIVE STACK BUILT group="+group+" roads="+roads.size()+" feed="+feed+" nose="+nose);
  var central=data.index.roads.values().stream().filter(r->group.equals(r.record.assembly())&&r.mesh.samples().stream().anyMatch(p->Math.abs(p.center().x())<128&&Math.abs(p.center().z())<128)).map(r->r.record.id()).toList();if(central.size()<4)throw new AssertionError("central STACK fixture lacks interchange movements");
  return new Scene(group,roads,central,feed,ground,nose,guide);
 }
 private Stack201VisualFixture(){}
}
