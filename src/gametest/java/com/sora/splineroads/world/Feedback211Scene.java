package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;

/** Only invoked in the explicitly selected isolated copy by Feedback211Visual. */
public final class Feedback211Scene {
  public static String remote(ServerLevel level){
    var data=RoadData.get(level);var host=data.index.roads.get(UUID.fromString("b7017daa-18ee-417d-9fab-e96728670cf3")).record;
    var connector=data.index.roads.get(UUID.fromString("d3a08429-c125-4356-89b2-b792bd637fd3")).record;
    var end=Curve201GameTests.marker(level,new Node(new V(229.5,56,525.5),-90,0));
    var settings=RoadLanes.configure(new Settings(Mode.AUTO,Style.O6_RAIL,32,1,.4,90),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(3,3),5);
    settings=settings.options(settings.options().extras(false,false,false).route(settings.options().routing().fit(false)));
    var built=data.connect(level,null,host.b(),end,settings,null);
    if(!data.index.roads.get(connector.id()).record.rawMesh().samples().equals(connector.rawMesh().samples()))throw new AssertionError("remote junction moved actual elevated connector");
    return "REMOTE CONNECT PASS road="+built.id()+" elevated connector unchanged";
  }
  public static String corner(ServerLevel level){
    var data=RoadData.get(level);var tag=data.junctions.values().stream().filter(t->t.contains("Spec")&&JunctionCodec.read(t.getCompound("Spec")).center().distance(new V(580,56,802))<45).findFirst().orElseThrow();
    AutoJunctions.edit(level,null,AutoJunctions.payload(level,null,tag.getUUID("Id")));
    var roads=data.index.roads.values().stream().filter(b->tag.getUUID("Id").equals(b.record.assembly())&&b.record.junction()!=null).toList();
    if(roads.isEmpty()||roads.stream().anyMatch(b->b.record.junction().geometryVersion()!=56))throw new AssertionError("explicit edit did not upgrade corner geometry");
    return "CORNER WORLD PASS pieces="+roads.size();
  }
  public static String supports(ServerLevel level){
    var data=RoadData.get(level);var before=data.index.roads.get(UUID.fromString("3e39ef94-5b45-471e-b067-6188b26fd29a")).record;
    var after=data.connect(level,null,before.a(),before.b(),RoadProfile.editable(before.settings()),before.id());
    var piers=after.structures().stream().filter(p->p.pier()&&p.material()==RoadStructures.Material.CONCRETE&&p.a().y()<60&&p.a().x()>400&&p.a().x()<750).toList();
    System.out.println("SR211 SUPPORT POSITIONS "+piers.stream().map(p->p.a()).toList());
    if(piers.size()<4)throw new AssertionError("missing median supports count="+piers.size());
    if(piers.stream().noneMatch(p->p.a().sub(new V(572.19125569436,56,1200.07307103939)).horizontalLength()<2))throw new AssertionError("exact intersection centre support missing");
    var greenery=data.index.roads.values().stream().filter(b->!b.record.id().equals(after.id()))
        .flatMap(b->b.record.structures().stream()).filter(p->p.model().equals("sr:planting_soil")&&p.a().y()<57&&p.a().y()>55.5)
        // Foliage is cut around lamp stems. The persisted bed, inset from its
        // soil edge to the foliage edge, measures the actual outer planting
        // boundary without mistaking an internal lamp hole for a traffic lane.
        .map(p->p.frames(inset(p.frameA()),inset(p.frameB())).base()).toList();
    int footprintChecks=0;
    for(var pier:piers){
      if(pier.a().sub(new V(572.19125569436,56,1200.07307103939)).horizontalLength()<2)continue;
      var footprint=pier.base();
      for(int i=0;i<=8;i++)for(int j=0;j<=8;j++){
        V p=footprint.get(0).add(footprint.get(1).sub(footprint.get(0)).mul(i/8.0)).add(footprint.get(3).sub(footprint.get(0)).mul(j/8.0));
        if(greenery.stream().noneMatch(poly->JunctionPaint.inside(poly,p)))throw new AssertionError("pier footprint outside actual planting boundary "+p+" width="+pier.width());
        footprintChecks++;
      }
    }
    return "SUPPORT WORLD PASS positions="+piers.stream().map(p->p.a()).distinct().count()+" actual-green-footprint-checks="+footprintChecks;
  }
  static V inset(V frame){return frame.mul(Math.max(0,frame.horizontalLength()-.1)/frame.horizontalLength());}
  public static String spui(ServerLevel level){
    var data=RoadData.get(level);var input=data.interchanges.values().stream().filter(ParallelSpui::active).findFirst().orElseThrow().copy();
    ParallelSpui.capture(data,input,null);var result=ParallelSpui.build(level,null,input);
    if(RoadWorkChunks.heldCount(level)!=0)throw new AssertionError("SPUI tickets leaked");return "SPUI WORLD PASS "+result;
  }
  public static CompoundTag trumpet(ServerLevel level,TrumpetStyle style){
    double x=style==TrumpetStyle.A?3000:4600,z=3000;
    var nodes=List.of(new Node(new V(x-500,56,z),-90,0),new Node(new V(x+500,56,z),-90,0),new Node(new V(x,56,z-500),0,0));
    long[] positions=nodes.stream().map(n->Curve201GameTests.marker(level,n)).mapToLong(BlockPos::asLong).toArray();
    var s=RoadLanes.configure(new Settings(Mode.STRAIGHT,Style.O4_RAIL,18,1,.4,90),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(2,2),4);
    var command=Interchanges.payload(level,null,positions,null);command.put("Main1",RoadRecord.writeSettings(s));command.put("Main2",RoadRecord.writeSettings(s));
    command.put("Options",Interchanges.write(new Options(Preset.TRUMPET,false,1,96,20,5,2,5,false).trumpet(style)));
    Interchanges.build(level,null,command);
    return command;
  }
  public static CompoundTag doubleTrumpet(ServerLevel level){
    var nodes=List.of(new Node(new V(7000,56,6000),-90,0),new Node(new V(9000,56,6000),-90,0),new Node(new V(8000,66,5000),0,0),new Node(new V(8000,66,7000),0,0));
    long[] positions=nodes.stream().map(n->Curve201GameTests.marker(level,n)).mapToLong(BlockPos::asLong).toArray();
    var s=RoadLanes.configure(new Settings(Mode.STRAIGHT,Style.O4_RAIL,18,1,.4,90),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(2,2),4);
    var command=Interchanges.payload(level,null,positions,null);command.put("Main1",RoadRecord.writeSettings(s));command.put("Main2",RoadRecord.writeSettings(s));
    command.put("Options",Interchanges.write(new Options(Preset.DOUBLE_TRUMPET,false,1,96,20,5,2,5,false)));
    Interchanges.build(level,null,command);
    return Interchanges.payload(level,null,positions,null);
  }
  public static CompoundTag existingTrumpet(ServerLevel level){
    var data=RoadData.get(level);var tag=data.interchanges.values().stream().filter(t->Interchanges.read(t.getCompound("Options")).preset()==Preset.TRUMPET&&Interchanges.read(t.getCompound("Options")).trumpetStyle()==TrumpetStyle.A&&BlockPos.of(t.getLongArray("Points")[0]).getX()==2500).findFirst().orElseThrow();
    return Interchanges.payload(level,null,tag.getLongArray("Points"),tag.getUUID("Id"));
  }
}
