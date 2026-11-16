package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;

/** Real isolated curve/split/junction build with the planting phase that hid arm 2. */
public final class Audit201JunctionVisualFixture {
 public record Scene(List<UUID> arms,UUID picturedArm,V eye,V focus){}
 public static Scene create(ServerPlayer player){
  var level=player.serverLevel();var data=RoadData.get(level);double x=200.5,y=-59.875,z=.5;
  var a=Curve201GameTests.marker(level,new Node(new V(x,y,z),0,0));
  var b=Curve201GameTests.marker(level,new Node(new V(x,y,z+300),0,0));
  var s=RoadLanes.configure(new Settings(Mode.CURVE,Style.O2_RAIL,9,1,.35,90).structure(Structure.GROUND),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(3,3),4);
  s=s.options(s.options().sidewalk(RoadSidewalks.Config.DEFAULT.enabled(true)).streetscape(s.options().streetscape().planting(RoadStreetscape.Planting.CHERRY)).route(s.options().routing().fit(false).controls(List.of(new RoadProfile.Control(UUID.randomUUID(),new V(x+45,y,z+100)),new RoadProfile.Control(UUID.randomUUID(),new V(x+45,y,z+200)))).through(false)));
  var whole=data.connect(level,null,a,b,s,null);var plan=RoadSplitter.plan(level,null,whole,whole.mesh().length()*.45,RoadSlicing.Connection.CONTINUOUS);RoadSplitter.commit(level,null,plan);
  var end=Curve201GameTests.marker(level,new Node(plan.pair().node().position().add(new V(150,0,0)),-90,0));
  var branch=RoadLanes.configure(new Settings(Mode.STRAIGHT,Style.O2_GREEN,9,1,.35,90).structure(Structure.GROUND),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(3,3),4);
  branch=branch.options(branch.options().sidewalk(RoadSidewalks.Config.DEFAULT.enabled(true)).streetscape(branch.options().streetscape().planting(RoadStreetscape.Planting.CHERRY)));
  data.connect(level,null,plan.pair().pos(),end,branch,null);data.smartSigns.refresh();
  for(int guard=0;guard<64&&data.smartSigns.sightlinesPending();guard++){data.smartSigns.advanceSightlines(level,Long.MAX_VALUE,8192);data.smartSigns.refresh();}
  var center=AutoJunctions.center(data,plan.pair().pos());if(center==null)throw new AssertionError("visual auto-junction absent");var group=center.getUUID("Id");
  var roads=data.index.roads.values().stream().filter(r->group.equals(r.record.assembly())&&r.record.junction()!=null&&r.record.junction().get().arm()>=0).sorted(Comparator.comparingInt(r->r.record.junction().get().arm())).toList();
  if(roads.size()!=3)throw new AssertionError("visual junction arm count "+roads.size());
  for(var road:roads){var state=data.smartSigns.peek(new SmartSigns.Key(road.record.id(),0,1));if(state.support()==null||!state.diagnostic().isEmpty())throw new AssertionError("visual junction sign invalid "+state);}
  var road=roads.get(2);var state=data.smartSigns.peek(new SmartSigns.Key(road.record.id(),0,1));var layout=SmartSigns.layout(road.mesh,state);var board=layout.boards().get(0);var focus=layout.at(board.x(),board.bottom()+board.height()/2,0);var eye=focus.add(layout.front().mul(26)).add(new V(0,1,0));
  System.out.println("SR201 VISUAL JUNCTION BUILT 3 valid advance signs; pictured arm=2 id="+road.record.id());
  return new Scene(roads.stream().map(r->r.record.id()).toList(),road.record.id(),eye,focus);
 }
 private Audit201JunctionVisualFixture(){}
}
