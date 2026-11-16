package com.sora.splineroads.world;

import com.mojang.authlib.GameProfile;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import java.io.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.GameType;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.*;

/** Uses only the explicitly copied 2.0.4 feedback data; never opens an original world. */
@GameTestHolder("splineroads_204_limits") @PrefixGameTestTemplate(false)
public final class Limits204GameTests {
  private static final UUID FEEDBACK_GROUP=UUID.fromString("a358e737-b0df-416c-9d08-e86d4805bb1c");
  private static final double CENTER=820000.5;
  record Fixture(Node[] nodes,ListTag limits,Settings[] settings,CompoundTag options){}
  static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
  static Node translated(Node node,V offset){return new Node(node.position().add(offset),node.yaw(),node.grade());}
  static ListTag tags(Collection<Node> nodes){var out=new ListTag();for(var n:nodes)out.add(RoadRecord.writeNode(n));return out;}
  static ListTag translated(ListTag list,V offset){var out=new ListTag();for(var value:list)out.add(RoadRecord.writeNode(translated(RoadRecord.readNode((CompoundTag)value),offset)));return out;}

  static Settings translated(Settings settings,V offset){
    var lanes=settings.options().lanePoints();var attachments=settings.options().attachments();
    // These guards make a changed snapshot fail loudly instead of leaving unhandled
    // absolute coordinates (or dependency IDs) in the original world's location.
    require(attachments.points().isEmpty()&&attachments.spans().isEmpty(),"fixture now has attached points; translate them explicitly");
    require(lanes.link()==null&&lanes.openings().isEmpty()&&lanes.cuts().isEmpty()&&lanes.additions().isEmpty()&&lanes.bands().isEmpty()&&lanes.access().isEmpty(),"fixture now has lane dependencies; translate them explicitly");
    var encoded=RoadRecord.writeSettings(settings);
    require(encoded.getList("CurveControls",Tag.TAG_COMPOUND).isEmpty(),"fixture now has absolute curve controls");
    require(settings.options().infrastructure().gantryEdits().isEmpty(),"fixture now has custom gantry edits");
    // Street raised ranges are station distances, so they deliberately stay 0..600.
    // Preserve all 8 automatic point identities and translate both position and anchor.
    var moved=lanes.points().stream().map(p->new LanePoints.Point(p.id(),p.origin(),p.lane(),p.position().add(offset),p.anchor()==null?null:p.anchor().add(offset),p.mergeLength())).toList();
    return settings.options(settings.options().lanePoints(lanes.points(moved)));
  }

  static Fixture fixture() throws IOException {
    String path=System.getProperty("splineroads.feedback204Snapshot");
    require(path!=null&&!path.isBlank(),"set splineroads.feedback204Snapshot to the isolated 2.0.4 user-copy.nbt");
    var root=NbtIo.readCompressed(new File(path)).getCompound("data");
    CompoundTag saved=null;
    for(var value:root.getList("Interchanges",Tag.TAG_COMPOUND)){
      var item=(CompoundTag)value;if(item.hasUUID("Id")&&FEEDBACK_GROUP.equals(item.getUUID("Id")))saved=item;
    }
    require(saved!=null,"latest feedback interchange missing from copied snapshot");
    var options=Interchanges.read(saved.getCompound("Options"));
    require(options.preset()==Preset.TURBINE&&options.adjustEndpoints(),"copied feedback no longer describes the original TURBINE");
    var originals=HostAxes.nodes(saved);require(originals.length==4,"feedback requires four endpoints");
    require(Math.abs(originals[2].position().y()-originals[0].position().y()-7)<1e-7,"feedback height gap changed");
    var oldLimits=saved.getList("ContinuousLimits",Tag.TAG_COMPOUND);require(oldLimits.size()==4,"historical limits missing");
    V center=ContinuousFit.center(originals,options),offset=new V(CENTER-center.x(),1,CENTER-center.z());
    var nodes=new Node[4];var settings=new Settings[2];var byId=new HashMap<UUID,RoadRecord>();
    for(var value:root.getList("Roads",Tag.TAG_COMPOUND)){var road=RoadRecord.load((CompoundTag)value);byId.put(road.id(),road);}
    var sources=ContinuousRoads.sources(saved);require(sources.size()==2,"feedback source count changed");
    for(var source:sources){
      int axis=source.axis();var metadata=source.road();var actual=byId.get(metadata.id());
      require(actual!=null&&FEEDBACK_GROUP.equals(actual.assembly()),"source has no matching actual assembly road");
      require(actual.start().equals(metadata.start())&&actual.end().equals(metadata.end()),"cached source and actual road endpoints disagree");
      require(Math.abs(actual.mesh().length()-600)<1e-5,"current feedback road is not 600 blocks");
      for(int end=0;end<2;end++){
        var n=end==0?actual.start():actual.end();
        require(n.position().distance(originals[axis*2+end].position())<1e-7,"snapshot Nodes differs from actual road");
        require(Math.abs(n.yaw()-originals[axis*2+end].yaw())<1e-7&&Math.abs(n.grade()-originals[axis*2+end].grade())<1e-7,"snapshot orientation differs from actual road");
        nodes[axis*2+end]=translated(n,offset);
      }
      settings[axis]=translated(actual.settings(),offset);
      require(settings[axis].options().lanePoints().points().size()==8,"expected eight actual automatic lane points");
      for(var p:settings[axis].options().lanePoints().points()){
        require(p.position().sub(new V(CENTER,0,CENTER)).horizontalLength()<400,"untranslated lane point");
        require(p.anchor()!=null&&p.anchor().sub(new V(CENTER,0,CENTER)).horizontalLength()<400,"untranslated lane anchor");
      }
    }
    require(RoadRecord.readNode(oldLimits.getCompound(2)).position().y()==-50&&originals[2].position().y()==-53,"historical height mismatch changed");
    return new Fixture(nodes,translated(oldLimits,offset),settings,Interchanges.write(options));
  }

  static BlockPos marker(ServerLevel level,ServerPlayer player,Node node){
    var p=BlockPos.containing(node.position().x(),node.position().y(),node.position().z());level.getChunkAt(p);
    level.setBlock(p,SplineRoads.NODE.get().defaultBlockState(),2);
    var entity=(NodeEntity)level.getBlockEntity(p);entity.owner=player.getUUID();entity.heightExplicit=true;entity.apply(node);return p;
  }
  static CompoundTag command(CompoundTag source,ListTag targets){
    var result=source.copy();result.put("SourceNodes",source.getList("Nodes",Tag.TAG_COMPOUND).copy());
    result.putInt("SourceAxesHash",HostAxes.signature(source));result.put("FittedNodes",targets.copy());return result;
  }
  static void rejected(GameTestHelper h,Runnable action,String name){
    boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;System.out.println("SR204 LIMITS EXPECTED "+name+": "+expected.getMessage());}
    h.assertTrue(rejected,"accepted "+name);
  }
  static void exactSaved(GameTestHelper h,RoadData data,UUID group,ListTag target){
    h.assertTrue(data.interchanges.get(group).getList("Nodes",Tag.TAG_COMPOUND).equals(target),"transaction changed confirmed endpoints");
    long count=data.index.roads.values().stream().filter(r->group.equals(r.record.assembly())).count();h.assertTrue(count>=18,"assembly was not committed to the real road index");
    for(var r:data.index.roads.values())if(group.equals(r.record.assembly()))h.assertTrue(RoadGrades.maximum(r.mesh)<=.15+1e-6,"committed geometry exceeds grade limit");
  }

  @GameTest(template="empty",templateNamespace="splineroads_204_limits",timeoutTicks=36000)
  public static void copiedFeedbackStackBuildReloadEdit(GameTestHelper h) throws IOException {
    var fixture=fixture();var level=h.getLevel();var data=RoadData.get(level);
    var player=FakePlayerFactory.get(level,new GameProfile(new UUID(204,204),"SR204Limits"));player.setGameMode(GameType.CREATIVE);
    // Absolute fixtures persist in the shared GameTest dimension. 820000.5 is
    // deliberately separate from 201 ranges, 203 confirmed 703100.5, and 713000..716200.
    for(var old:data.index.roads.values()){
      var min=old.mesh.min();var max=old.mesh.max();
      h.assertTrue(max.x()<CENTER-1500||min.x()>CENTER+1500||max.z()<CENTER-1500||min.z()>CENTER+1500,"limits fixture area overlaps existing road "+old.record.id());
    }
    long[] points=new long[4];for(int i=0;i<4;i++)points[i]=marker(level,player,fixture.nodes()[i]).asLong();
    for(int axis=0;axis<2;axis++)data.connect(level,player,BlockPos.of(points[2*axis]),BlockPos.of(points[2*axis+1]),fixture.settings()[axis],null);
    var initial=Interchanges.payload(level,player,points,null);initial.put("Options",fixture.options().copy());
    var before=new HashSet<>(data.interchanges.keySet());
    Interchanges.build(level,player,command(initial,initial.getList("Nodes",Tag.TAG_COMPOUND)));
    var added=new HashSet<>(data.interchanges.keySet());added.removeAll(before);h.assertTrue(added.size()==1,"missing original TURBINE transaction");UUID group=added.iterator().next();
    var saved=data.interchanges.get(group);
    h.assertTrue(saved.getList("Nodes",Tag.TAG_COMPOUND).equals(initial.getList("Nodes",Tag.TAG_COMPOUND)),"TURBINE moved the authentic source endpoints");
    // Recreate the one historical metadata defect after authentic materialization.
    // Current roads, nodes, ownership, indexes and assembly membership stay real.
    saved.put("ContinuousLimits",fixture.limits().copy());data.setDirty();
    var edit=Interchanges.payload(level,player,saved.getLongArray("Points"),group);
    edit.getCompound("Options").putString("Preset","STACK");edit.put("Options",Interchanges.write(Interchanges.read(edit.getCompound("Options"))));
    var off=edit.copy();off.put("Options",Interchanges.write(Interchanges.read(off.getCompound("Options")).adjust(false)));
    rejected(h,()->Interchanges.plan(off),"endpoint adjustment disabled");
    var preview=Interchanges.plan(edit);var targets=tags(preview.anchors());
    for(int i=2;i<4;i++){
      h.assertTrue(preview.anchors().get(i).position().sub(fixture.nodes()[i].position()).horizontalLength()<1e-5,"upper road shortened across old-height tails");
      h.assertTrue(Math.abs(preview.anchors().get(i).position().y()-fixture.nodes()[0].position().y()-19)<1e-6,"stack gap was not raised from 7 to 19");
    }
    var request=command(edit,targets);var checked=Interchanges.confirmedPlan(edit,request);
    h.assertTrue(tags(checked.anchors()).equals(targets),"fixed preview revalidation chose different anchors");
    var bad=request.copy();var first=bad.getList("FittedNodes",Tag.TAG_COMPOUND).getCompound(0);first.putDouble("Z",first.getDouble("Z")+2);
    var snapshotBefore=data.interchanges.get(group).copy();int roadsBefore=data.index.roads.size();
    rejected(h,()->Interchanges.build(level,player,bad),"off-axis submitted endpoint");
    var other=FakePlayerFactory.get(level,new GameProfile(new UUID(204,205),"SR204Other"));other.setGameMode(GameType.CREATIVE);
    rejected(h,()->Interchanges.build(level,other,request),"foreign owner");
    h.assertTrue(data.interchanges.get(group).equals(snapshotBefore)&&data.index.roads.size()==roadsBefore,"rejected request partially wrote the assembly");
    Interchanges.build(level,player,request);exactSaved(h,data,group,targets);
    var loaded=RoadData.load(data.save(new CompoundTag()));level.getDataStorage().set("splineroads",loaded);exactSaved(h,loaded,group,targets);
    var reopened=Interchanges.payload(level,player,loaded.interchanges.get(group).getLongArray("Points"),group);
    var second=Interchanges.plan(reopened);var secondTargets=tags(second.anchors());
    Interchanges.build(level,player,command(reopened,secondTargets));exactSaved(h,loaded,group,secondTargets);
    Interchanges.remove(level,player,group);h.assertTrue(!loaded.interchanges.containsKey(group),"delete retained group");
    h.assertTrue(RoadWorkChunks.heldCount(level)==0,"chunk tickets leaked");
    System.out.println("SR204 LIMITS WORLD PASS fresh-copy/actual-records/translated-points/TURBINE-build/STACK-preview/fixed-commit/reload/edit-save/delete/adjust-off/forged-fit/owner");h.succeed();
  }

  // Offline preparation guard; intentionally does not stand in for the GameTest.
  public static void main(String[]args)throws IOException{
    var f=fixture();var t=new CompoundTag();t.put("Nodes",tags(Arrays.asList(f.nodes())));t.put("Options",f.options());t.putLongArray("Points",Arrays.stream(f.nodes()).mapToLong(n->BlockPos.containing(n.position().x(),n.position().y(),n.position().z()).asLong()).toArray());
    t.put("Main1",RoadRecord.writeSettings(f.settings()[0]));t.put("Main2",RoadRecord.writeSettings(f.settings()[1]));t.put("ContinuousLimits",f.limits());
    var sources=new ListTag();for(int axis=0;axis<2;axis++){
      var a=f.nodes()[axis*2];var b=f.nodes()[axis*2+1];var road=new RoadRecord(new UUID(204,axis),new UUID(204,204),BlockPos.containing(a.position().x(),a.position().y(),a.position().z()),BlockPos.containing(b.position().x(),b.position().y(),b.position().z()),a,b,f.settings()[axis],false,4);
      var source=new CompoundTag();source.putInt("Axis",axis);source.put("Road",road.header());sources.add(source);
    }t.put("ContinuousSources",sources);
    var old=Interchanges.confirmedPlan(t,command(t,t.getList("Nodes",Tag.TAG_COMPOUND)));require(old.anchors().size()==4,"TURBINE fixed geometry invalid");
    t.getCompound("Options").putString("Preset","STACK");t.put("Options",Interchanges.write(Interchanges.read(t.getCompound("Options"))));
    if(args.length>0&&args[0].equals("red")){
      boolean rejected=false;try{Interchanges.plan(t);}catch(IllegalArgumentException expected){rejected=expected.getMessage().contains("50%");expected.printStackTrace();}
      require(rejected,"old classes did not reproduce the translated source seam error");System.out.println("SR204 TRANSLATED FIXTURE RED reproduced 50% seam with actual-road settings");return;
    }
    var plan=Interchanges.plan(t);var confirmed=Interchanges.confirmedPlan(t,command(t,tags(plan.anchors())));require(confirmed.anchors().equals(plan.anchors()),"translated STACK confirmation differs");
    System.out.println("SR204 TRANSLATED FIXTURE PASS actual-road settings, lane positions+anchors translated, TURBINE legs="+old.legs().size()+", STACK legs="+plan.legs().size()+", anchors="+plan.anchors());
  }
}
