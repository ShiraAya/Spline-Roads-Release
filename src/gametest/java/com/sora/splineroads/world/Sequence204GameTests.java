package com.sora.splineroads.world;

import com.mojang.authlib.GameProfile;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.GameType;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.*;

/** A single real interchange is updated eight times; independent plans are only preparation. */
@GameTestHolder("splineroads_204_sequence") @PrefixGameTestTemplate(false)
public final class Sequence204GameTests {
  static final List<Preset> ORDER=List.of(Preset.CLOVERLEAF,Preset.TURBINE,Preset.STACK,Preset.HYBRID,Preset.DIAMOND,Preset.ROUNDABOUT,Preset.SPUI,Preset.DOUBLE_TRUMPET);
  static final double CENTER=830000.5,CONTROL_CENTER=835000.5;
  static final UUID OWNER=new UUID(20402,20402);
  static Options options(Preset preset){return new Options(preset,false,1,96,20,5,1,5,true,false,0,1,5,true,false,0);}
  static Settings settings(boolean highway){
    var style=highway?Style.H4_RAIL:Style.O4_RAIL;var s=new Settings(Mode.STRAIGHT,style,style.defaultWidth(),1,.4,90);
    return s.options(s.options().extras(false,false,false).cycleFinish(RoadProfile.Options.CycleFinish.NONE).route(RoadProfile.Routing.DEFAULT.fit(false)).infrastructure(s.options().infrastructure().gantry(RoadInfrastructure.Gantry.OFF)));
  }
  static Node[] nodes(double center){return new Node[]{new Node(new V(center-400,-40,center),-90,0),new Node(new V(center+400,-40,center),-90,0),new Node(new V(center,-59,center-400),0,0),new Node(new V(center,-59,center+400),0,0)};}
  static ListTag tags(Collection<Node> nodes){var tags=new ListTag();for(var n:nodes)tags.add(RoadRecord.writeNode(n));return tags;}
  static CompoundTag command(CompoundTag source,ListTag target){var result=source.copy();result.put("SourceNodes",source.getList("Nodes",Tag.TAG_COMPOUND).copy());result.putInt("SourceAxesHash",HostAxes.signature(source));result.put("FittedNodes",target.copy());return result;}
  static BlockPos pos(Node n){return BlockPos.containing(n.position().x(),n.position().y(),n.position().z());}
  static void order(){
    var expected=new ArrayList<>(InterchangePlanner.presets(4));expected.remove(Preset.DOUBLE_TRUMPET);expected.add(Preset.DOUBLE_TRUMPET);
    if(!ORDER.equals(expected))throw new AssertionError("four-way presets changed: "+expected);
  }
  static long[] connect(GameTestHelper h,ServerPlayer player,double center,boolean bothHighways){
    var level=h.getLevel();var data=RoadData.get(level);
    // The shared dimension keeps absolute fixtures after individual tests finish.
    // These two squares are separate from Limits204 (820000.5), 203 and 201.
    for(var existing:data.index.roads.values()){
      var min=existing.mesh.min();var max=existing.mesh.max();
      h.assertTrue(max.x()<center-2000||min.x()>center+2000||max.z()<center-2000||min.z()>center+2000,"sequence fixture overlaps existing road "+existing.record.id());
    }
    var nodes=nodes(center);long[] points=new long[4];
    for(int i=0;i<nodes.length;i++){
      BlockPos p=pos(nodes[i]);level.getChunkAt(p);level.setBlock(p,SplineRoads.NODE.get().defaultBlockState(),2);
      var marker=(NodeEntity)level.getBlockEntity(p);marker.owner=player.getUUID();marker.heightExplicit=true;marker.apply(nodes[i]);points[i]=p.asLong();
    }
    data.connect(level,player,BlockPos.of(points[2]),BlockPos.of(points[3]),settings(bothHighways),null);
    data.connect(level,player,BlockPos.of(points[0]),BlockPos.of(points[1]),settings(true),null);
    return points;
  }
  static void fixedEquals(GameTestHelper h,Plan a,Plan b){
    h.assertTrue(a.anchors().equals(b.anchors()),"fixed preview validation changed endpoints");h.assertTrue(a.legs().size()==b.legs().size(),"fixed validation changed leg count");
    for(int i=0;i<a.legs().size();i++)h.assertTrue(a.legs().get(i).mesh().samples().equals(b.legs().get(i).mesh().samples()),"fixed validation changed geometry at leg "+i);
  }
  static void logStage(RoadData data,UUID group,int step,Preset preset,Plan preview){
    var roads=data.index.roads.values().stream().filter(r->InterchangeJunctions.belongs(data,group,r.record.assembly())).toList();
    long samples=roads.stream().mapToLong(r->r.mesh.samples().size()).sum();double grade=roads.stream().mapToDouble(r->RoadGrades.maximum(r.mesh)).max().orElseThrow();
    long children=data.junctions.values().stream().filter(t->t.hasUUID("Interchange")&&group.equals(t.getUUID("Interchange"))).count();
    var geometry=roads.stream().sorted(Comparator.comparing(r->r.record.id())).map(r->r.record.id()+":"+r.mesh.samples().hashCode()+":"+r.mesh.samples().size()).toList();
    System.out.println("SR204 SEQUENCE STAGE PASS "+step+"/8 "+preset+" group="+group+" anchors="+preview.anchors()+" previewLegs="+preview.legs().size()+" committedRoads="+roads.size()+" childJunctions="+children+" samples="+samples+" maxGrade="+grade+" geometry="+geometry);
  }

  @GameTest(template="empty",templateNamespace="splineroads_204_sequence",timeoutTicks=72000)
  public static void cloverleafThenEveryFourWaySameGroup(GameTestHelper h){
    order();var level=h.getLevel();var data=RoadData.get(level);
    var player=FakePlayerFactory.get(level,new GameProfile(OWNER,"SR204Sequence"));player.setGameMode(GameType.CREATIVE);
    long[] points=connect(h,player,CENTER,false);var originalGroups=new HashSet<>(data.interchanges.keySet());UUID group=null;ListTag lastTargets=null;int step=0;
    for(Preset preset:ORDER){
      step++;System.out.println("SR204 SEQUENCE STAGE START "+step+"/8 "+preset+" group="+group);
      var source=Interchanges.payload(level,player,points,group);source.put("Options",Interchanges.write(options(preset)));
      h.assertTrue(!ContinuousRoads.sources(source).isEmpty(),"sequence accidentally skipped real donor roads");
      if(group!=null)h.assertTrue(source.getUUID("Id").equals(group),"reopen changed assembly identity");
      Plan preview;try(var budget=RoadPlanningBudget.open("sequence preview "+preset,120)){preview=Interchanges.plan(source);}
      var target=tags(preview.anchors());var request=command(source,target);fixedEquals(h,preview,Interchanges.confirmedPlan(source,request));
      try{Interchanges.build(level,player,request);}catch(RuntimeException failure){failure.printStackTrace();throw failure;}
      if(group==null){var added=new HashSet<>(data.interchanges.keySet());added.removeAll(originalGroups);h.assertTrue(added.size()==1,"initial CLOVERLEAF did not create exactly one assembly");group=added.iterator().next();}
      h.assertTrue(data.interchanges.size()==originalGroups.size()+1,"a switch created an extra assembly");
      var saved=data.interchanges.get(group);h.assertTrue(saved!=null&&saved.getUUID("Id").equals(group),"switch lost original assembly identity");
      h.assertTrue(saved.getUUID("Owner").equals(player.getUUID()),"switch changed owner");
      h.assertTrue(Interchanges.read(saved.getCompound("Options")).preset()==preset,"requested layout was not saved");
      h.assertTrue(saved.getList("Nodes",Tag.TAG_COMPOUND).equals(target),"actual transaction changed confirmed endpoints");
      ContinuousService204Coverage.assertPreserved(h,data,source,saved,preview);
      UUID currentGroup=group;var committed=data.index.roads.values().stream().filter(r->InterchangeJunctions.belongs(data,currentGroup,r.record.assembly())).toList();
      h.assertTrue(committed.size()>=8,"layout has no complete real indexed geometry");
      for(var road:committed){h.assertTrue(road.record.owner().equals(player.getUUID()),"generated geometry has wrong owner");h.assertTrue(RoadGrades.maximum(road.mesh)<=.15+1e-6,"committed geometry exceeds slope limit");}
      points=saved.getLongArray("Points");var reopened=Interchanges.payload(level,player,points,group);
      h.assertTrue(reopened.getUUID("Id").equals(group)&&reopened.getList("Nodes",Tag.TAG_COMPOUND).equals(target),"reopen lost the submitted layout endpoints");
      h.assertTrue(Interchanges.read(reopened.getCompound("Options")).preset()==preset,"reopen restored an earlier layout");
      h.assertTrue(RoadWorkChunks.heldCount(level)==0,"stage leaked chunk tickets");logStage(data,group,step,preset,preview);lastTargets=target;
    }
    var loaded=RoadData.load(data.save(new CompoundTag()));level.getDataStorage().set("splineroads",loaded);
    h.assertTrue(loaded.interchanges.get(group).getList("Nodes",Tag.TAG_COMPOUND).equals(lastTargets),"reload changed final DOUBLE_TRUMPET endpoints");
    h.assertTrue(Interchanges.read(loaded.interchanges.get(group).getCompound("Options")).preset()==Preset.DOUBLE_TRUMPET,"last layout was not DOUBLE_TRUMPET");
    Interchanges.remove(level,player,group);h.assertTrue(!loaded.interchanges.containsKey(group),"delete retained final assembly");
    h.assertTrue(RoadWorkChunks.heldCount(level)==0,"sequence leaked chunk tickets");
    System.out.println("SR204 SEQUENCE WORLD PASS SAME_GROUP CLOVERLEAF,TURBINE,STACK,HYBRID,DIAMOND,ROUNDABOUT,SPUI,DOUBLE_TRUMPET; physical-connect/fixed-preview/real-commit/reopen/reload/delete");h.succeed();
  }

  static void rejected(GameTestHelper h,Runnable action,String name){
    boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=expected.getMessage().contains("均为高速");System.out.println("SR204 SEQUENCE EXPECTED "+name+": "+expected.getMessage());}
    h.assertTrue(rejected,"both-highway "+name+" was not rejected by the intended profile rule");
  }
  @GameTest(template="empty",templateNamespace="splineroads_204_sequence",timeoutTicks=36000)
  public static void twoHighwaysRejectDiamondAndSpui(GameTestHelper h){
    var level=h.getLevel();var data=RoadData.get(level);var player=FakePlayerFactory.get(level,new GameProfile(new UUID(20402,20403),"SR204Profile"));player.setGameMode(GameType.CREATIVE);
    var points=connect(h,player,CONTROL_CENTER,true);var source=Interchanges.payload(level,player,points,null);
    var beforeRoads=new HashSet<>(data.index.roads.keySet());var beforeGroups=new HashSet<>(data.interchanges.keySet());var beforeNodes=source.getList("Nodes",Tag.TAG_COMPOUND).copy();
    for(var preset:List.of(Preset.DIAMOND,Preset.SPUI)){
      var selected=source.copy();selected.put("Options",Interchanges.write(options(preset)));var request=command(selected,beforeNodes);
      rejected(h,()->Interchanges.plan(selected),preset+" preview");rejected(h,()->Interchanges.build(level,player,request),preset+" commit");
      h.assertTrue(data.index.roads.keySet().equals(beforeRoads)&&data.interchanges.keySet().equals(beforeGroups),"rejected service interchange changed real records");
      h.assertTrue(Interchanges.payload(level,player,points,null).getList("Nodes",Tag.TAG_COMPOUND).equals(beforeNodes),"rejected service interchange moved markers");
    }
    for(var road:ContinuousRoads.sources(source))data.remove(level,player,road.road().id());
    h.assertTrue(RoadWorkChunks.heldCount(level)==0,"negative control leaked chunk tickets");
    System.out.println("SR204 SEQUENCE PROFILE CONTROL PASS real two-highway roads reject DIAMOND and SPUI preview+commit without writes");h.succeed();
  }

  /** Standalone prerequisite: all eight independent plans and both profile controls. */
  public static void main(String[]args){
    order();int passed=0;for(var preset:ORDER){
      var source=offline(false);source.put("Options",Interchanges.write(options(preset)));Plan preview;
      try(var budget=RoadPlanningBudget.open("independent "+preset,120)){preview=Interchanges.plan(source);}
      var checked=Interchanges.confirmedPlan(source,command(source,tags(preview.anchors())));
      if(!preview.anchors().equals(checked.anchors())||preview.legs().size()!=checked.legs().size())throw new AssertionError("independent fixed geometry differs");
      for(int i=0;i<preview.legs().size();i++)if(!preview.legs().get(i).mesh().samples().equals(checked.legs().get(i).mesh().samples()))throw new AssertionError("independent samples differ");
      passed++;System.out.println("SR204 SEQUENCE INDEPENDENT PASS "+passed+"/8 "+preset+" legs="+preview.legs().size()+" anchors="+preview.anchors());
    }
    for(var preset:List.of(Preset.DIAMOND,Preset.SPUI)){
      var source=offline(true);source.put("Options",Interchanges.write(options(preset)));boolean rejected=false;
      try{Interchanges.plan(source);}catch(IllegalArgumentException expected){rejected=expected.getMessage().contains("均为高速");}
      if(!rejected)throw new AssertionError("both-highway "+preset+" was not rejected");
    }
    System.out.println("SR204 SEQUENCE OFFLINE PASS independent=8 both-highway-controls=2; real same-group world sequence remains required");
  }
  static CompoundTag offline(boolean bothHighways){
    var nodes=nodes(CENTER);var source=new CompoundTag();source.put("Nodes",tags(Arrays.asList(nodes)));source.put("ContinuousLimits",tags(Arrays.asList(nodes)));source.putLongArray("Points",Arrays.stream(nodes).mapToLong(n->pos(n).asLong()).toArray());
    source.put("Main1",RoadRecord.writeSettings(settings(true)));source.put("Main2",RoadRecord.writeSettings(settings(bothHighways)));var roads=new ListTag();
    for(int axis=0;axis<2;axis++){var road=new RoadRecord(new UUID(20402,axis),OWNER,pos(nodes[axis*2]),pos(nodes[axis*2+1]),nodes[axis*2],nodes[axis*2+1],settings(axis==0||bothHighways),false,4);var item=new CompoundTag();item.putInt("Axis",axis);item.put("Road",road.header());roads.add(item);}source.put("ContinuousSources",roads);return source;
  }
}
