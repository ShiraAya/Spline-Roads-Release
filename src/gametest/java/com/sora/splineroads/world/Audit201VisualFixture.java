package com.sora.splineroads.world;
import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;import net.minecraft.server.level.ServerPlayer;
/** Fresh isolated visual world; builds through the normal server transaction APIs. */
public final class Audit201VisualFixture {
 public record Scene(UUID host,UUID connector,List<V> bridgeSeams){}
 public static Scene create(ServerPlayer player){var level=player.serverLevel();var data=RoadData.get(level);var origin=new V(.5,-59.875,.5);var s=AccessBridge201GameTests.road();
  var a=Curve201GameTests.marker(level,new Node(origin,0,0));var b=Curve201GameTests.marker(level,new Node(origin.add(new V(0,0,400)),0,0));var host=data.connect(level,null,a,b,s,null);
  var marker=ElevatedPointTool.create(level,null,data.index.roads.get(host.id()),origin.add(new V(0,0,120)));var end=Curve201GameTests.marker(level,new Node(origin.add(new V(0,12,240)),0,0));var far=Curve201GameTests.marker(level,new Node(origin.add(new V(0,12,345)),0,0));
  var upper=RoadLanes.configure(s.structure(Structure.BRIDGE),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(2,3),4);data.connect(level,null,end,far,upper,null);
  var draft=ElevatedAccessRoads.payload(level,null,host.id(),marker.id(),end,true,null);ElevatedAccessRoads.build(level,null,ElevatedAccessRoads.preview(level,null,draft));var md=LaneTopology.metadata(data.index.roads.get(host.id()).record);if(md.additions().size()!=1||md.additions().get(0).sign()!=-1)throw new AssertionError("visual single-aux fixture incorrect");var id=md.access().get(0).connection();data.smartSigns.refresh();var state=data.smartSigns.peek(new SmartSigns.Key(id,0,1));if(state.support()==null||!state.diagnostic().isEmpty()||state.branches().size()!=1)throw new AssertionError("actual solo elevated sign unavailable: "+state);
  var seams=new ArrayList<V>();int index=0;
  for(var kind:List.of(RoadInfrastructure.Bridge.ARCH,RoadInfrastructure.Bridge.CABLE,RoadInfrastructure.Bridge.SUSPENSION)){
   var p=new V(160.5+index++*120,-42,.5);var aa=Curve201GameTests.marker(level,new Node(p,0,0));var bb=Curve201GameTests.marker(level,new Node(p.add(new V(0,0,100)),0,0));var cc=Curve201GameTests.marker(level,new Node(p.add(new V(0,0,300)),0,0));var standard=s.structure(Structure.BRIDGE);standard=standard.options(standard.options().sidewalk(RoadSidewalks.Config.DEFAULT.enabled(true)));data.connect(level,null,aa,bb,standard,null);data.connect(level,null,bb,cc,standard.options(standard.options().infrastructure(standard.options().infrastructure().bridge(kind))),null);seams.add(p.add(new V(0,0,100)));
  }
  data.smartSigns.refresh();System.out.println("SR201 VISUAL FIXTURE BUILT host="+host.id()+" connector="+id+" singleAux=-1 soloSign=true bridges=3");return new Scene(host.id(),id,List.copyOf(seams));
 }
 private Audit201VisualFixture(){}
}
