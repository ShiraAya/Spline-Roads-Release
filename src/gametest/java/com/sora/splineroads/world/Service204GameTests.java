package com.sora.splineroads.world;

import com.mojang.authlib.GameProfile;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.GameType;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.*;

/** Legal child edits remain usable; a deleted child cannot be revived by an old parent preview. */
@GameTestHolder("splineroads_204_service") @PrefixGameTestTemplate(false)
public final class Service204GameTests {
  @GameTest(template="empty",templateNamespace="splineroads_204_sequence",timeoutTicks=72000)
  public static void unusedNeighbourEditAndDeletionCannotBeOverwritten(GameTestHelper h){
    var level=h.getLevel();var data=RoadData.get(level);double center=885000.5;
    var player=FakePlayerFactory.get(level,new GameProfile(new UUID(20402,20406),"SR204Unused"));player.setGameMode(GameType.CREATIVE);
    var points=Sequence204GameTests.connect(h,player,center,false);
    var end=new Node(new V(center,-59,center+800),0,0);var endpoint=Sequence204GameTests.pos(end);
    level.getChunkAt(endpoint);level.setBlock(endpoint,com.sora.splineroads.SplineRoads.NODE.get().defaultBlockState(),2);
    var marker=(NodeEntity)level.getBlockEntity(endpoint);marker.owner=player.getUUID();marker.heightExplicit=true;marker.apply(end);
    var ids=new HashSet<>(data.index.roads.keySet());
    data.connect(level,player,BlockPos.of(points[3]),endpoint,Sequence204GameTests.settings(false),null);
    var addedRoads=new HashSet<>(data.index.roads.keySet());addedRoads.removeAll(ids);h.assertTrue(addedRoads.size()==1,"unused neighbour was not a real ordinary road");var neighbour=addedRoads.iterator().next();
    var original=data.index.roads.get(neighbour).record;var groups=new HashSet<>(data.interchanges.keySet());
    var first=Interchanges.payload(level,player,points,null);first.put("Options",Interchanges.write(Sequence204GameTests.options(Preset.DIAMOND)));
    h.assertTrue(ContinuousRoads.sources(first).stream().anyMatch(s->s.road().id().equals(neighbour)),"normal capture did not record outside neighbour");
    var plan=Interchanges.plan(first);h.assertTrue(plan.legs().stream().noneMatch(l->neighbour.equals(ContinuousRoads.sourceId(l.name()))),"initial DIAMOND used outside neighbour");
    Interchanges.build(level,player,Sequence204GameTests.command(first,Sequence204GameTests.tags(plan.anchors())));
    var added=new HashSet<>(data.interchanges.keySet());added.removeAll(groups);h.assertTrue(added.size()==1,"initial DIAMOND did not build");var group=added.iterator().next();
    var saved=data.interchanges.get(group);var entry=saved.getList("ContinuousSources",Tag.TAG_COMPOUND).stream().map(v->(CompoundTag)v).filter(q->q.getCompound("Road").getUUID("Id").equals(neighbour)).findFirst().orElseThrow();
    h.assertTrue(entry.getBoolean("Detached")&&!entry.getBoolean(ContinuousService.LOGICAL),"unused neighbour received logical interior authority");
    var baseline=data.index.roads.get(neighbour).record;
    var beforeHeader=original.header();var afterHeader=baseline.header();
    // A real replacement refreshes generated approach caches on adjacent roads.
    // Permit only those three observed caches; authored fields and pavement stay exact.
    for(var header:List.of(beforeHeader,afterHeader)){
      var streetscape=header.getCompound("Settings").getCompound("StartSection").getCompound("Streetscape");
      streetscape.remove("Raised");var sides=streetscape.getCompound("SideStructures");sides.remove("NegativeRaised");sides.remove("PositiveRaised");
    }
    h.assertTrue(beforeHeader.equals(afterHeader)&&original.caps(0).mesh().samples().equals(baseline.caps(0).mesh().samples()),"initial parent changed unused neighbour authored data or geometry");
    h.assertTrue(entry.getCompound("Road").equals(baseline.header()),"saved unused baseline missed committed generated caches");
    var source=Interchanges.payload(level,player,saved.getLongArray("Points"),group);source.put("Options",Interchanges.write(Sequence204GameTests.options(Preset.STACK)));
    Plan preview;try(var budget=RoadPlanningBudget.open("unused neighbour positive preview",120)){preview=Interchanges.plan(source);}
    h.assertTrue(preview.legs().stream().anyMatch(l->neighbour.equals(ContinuousRoads.sourceId(l.name()))),"positive STACK did not expand into untouched neighbour");
    var request=Sequence204GameTests.command(source,Sequence204GameTests.tags(preview.anchors()));
    var paint=new CompoundTag();paint.putUUID("Id",neighbour);paint.putInt("Signature",GantryTool.signature(baseline));paint.putBoolean("HideArrows",!baseline.settings().options().hideArrows());
    data.editLaneLine(level,player,paint);h.assertTrue(data.index.roads.get(neighbour).record.settings().options().hideArrows()!=original.settings().options().hideArrows(),"actual neighbour edit did not save");
    for(boolean delete:List.of(false,true)){
      if(delete)data.remove(level,player,neighbour);
      var before=data.save(new CompoundTag());boolean rejected=false;
      try{Interchanges.build(level,player,request);}catch(IllegalArgumentException expected){rejected=expected.getMessage().contains("原立交外侧余段");if(!rejected)throw expected;}
      h.assertTrue(rejected,"stale parent overwrote unused neighbour; deleted="+delete);
      h.assertTrue(before.equals(data.save(new CompoundTag())),"rejected update wrote world data; deleted="+delete);
      h.assertTrue(!delete||!data.index.roads.containsKey(neighbour),"deleted neighbour revived");
    }
    h.assertTrue(RoadWorkChunks.heldCount(level)==0,"unused neighbour control leaked chunks");
    System.out.println("SR204 UNUSED NEIGHBOUR WORLD PASS natural capture/unused DIAMOND/valid STACK/actual edit and delete/stale rejection/no writes");h.succeed();
  }
  @GameTest(template="empty",templateNamespace="splineroads_204_sequence",timeoutTicks=72000)
  public static void childEditPersistsAndDeletedCoverageRejectsBeforeWrites(GameTestHelper h){
    var level=h.getLevel();var data=RoadData.get(level);
    var player=FakePlayerFactory.get(level,new GameProfile(new UUID(20402,20405),"SR204ChildEdit"));player.setGameMode(GameType.CREATIVE);
    var points=Sequence204GameTests.connect(h,player,880000.5,false);var groups=new HashSet<>(data.interchanges.keySet());
    var first=Interchanges.payload(level,player,points,null);first.put("Options",Interchanges.write(Sequence204GameTests.options(Preset.DIAMOND)));
    var plan=Interchanges.plan(first);Interchanges.build(level,player,Sequence204GameTests.command(first,Sequence204GameTests.tags(plan.anchors())));
    var added=new HashSet<>(data.interchanges.keySet());added.removeAll(groups);h.assertTrue(added.size()==1,"initial DIAMOND did not build exactly once");var group=added.iterator().next();
    ContinuousService204Coverage.assertPreserved(h,data,first,data.interchanges.get(group),plan);
    var child=data.junctions.values().stream().filter(t->t.hasUUID("Interchange")&&group.equals(t.getUUID("Interchange"))&&t.getInt("InterchangeSlot")==0).findFirst().orElseThrow().getUUID("Id");
    var edit=Junctions.payload(level,player,new long[0],child);var spec=edit.getCompound("Spec");
    int green=spec.getInt("Green")+7;boolean guides=!spec.getBoolean("Guides");
    spec.putInt("Green",green);spec.putBoolean("Guides",guides);Junctions.build(level,player,JunctionCodec.editCommand(edit));
    var actual=data.junctions.get(child).getCompound("Spec");
    h.assertTrue(actual.getInt("Green")==green&&actual.getBoolean("Guides")==guides,"actual child edit did not save");
    var saved=data.interchanges.get(group);var fresh=Interchanges.payload(level,player,saved.getLongArray("Points"),group);
    h.assertTrue(fresh.getList("ServiceJunctionEdits",Tag.TAG_COMPOUND).size()==2,"parent did not capture actual edited child descriptors");
    var editedPlan=Interchanges.plan(fresh);Interchanges.build(level,player,Sequence204GameTests.command(fresh,Sequence204GameTests.tags(editedPlan.anchors())));
    actual=data.junctions.get(child).getCompound("Spec");
    h.assertTrue(actual.getInt("Green")==green&&actual.getBoolean("Guides")==guides,"parent save lost legitimate child settings");
    h.assertTrue(data.interchanges.get(group).getList("Nodes",Tag.TAG_COMPOUND).equals(Sequence204GameTests.tags(editedPlan.anchors())),"parent save changed confirmed endpoints");
    saved=data.interchanges.get(group);var next=Interchanges.payload(level,player,saved.getLongArray("Points"),group);
    next.put("Options",Interchanges.write(Sequence204GameTests.options(Preset.ROUNDABOUT)));
    Plan nextPlan;try(var budget=RoadPlanningBudget.open("edited child next layout",120)){nextPlan=Interchanges.plan(next);}
    var request=Sequence204GameTests.command(next,Sequence204GameTests.tags(nextPlan.anchors()));
    Sequence204GameTests.fixedEquals(h,nextPlan,Interchanges.confirmedPlan(next,request));
    System.out.println("SR204 SERVICE EDIT PASS real child signal/guides edit; parent reopen/save retained settings; next layout preview valid");
    // The normal user delete path marks the parent partial and removes the actual child pieces.
    Junctions.remove(level,player,child);h.assertTrue(!data.junctions.containsKey(child),"real child deletion did not happen");
    h.assertTrue(data.index.roads.values().stream().noneMatch(r->child.equals(r.record.assembly())),"deleted child still has geometry");
    var before=data.save(new CompoundTag());var markers=new HashMap<BlockPos,CompoundTag>();
    for(long point:saved.getLongArray("Points")){var pos=BlockPos.of(point);var entity=level.getBlockEntity(pos);if(entity!=null)markers.put(pos,entity.saveWithoutMetadata());}
    boolean rejected=false;
    try{Interchanges.build(level,player,request);}catch(IllegalArgumentException expected){
      rejected=expected.getMessage()!=null&&expected.getMessage().contains("原立交服务主路记录");
      if(!rejected)throw expected;System.out.println("SR204 SERVICE EXPECTED deleted child: "+expected.getMessage());
    }
    h.assertTrue(rejected,"old parent confirmation revived deleted service coverage");
    h.assertTrue(before.equals(data.save(new CompoundTag())),"rejected service update wrote roads/assembly/terrain ledger");
    for(var entry:markers.entrySet())h.assertTrue(level.getBlockEntity(entry.getKey())!=null&&entry.getValue().equals(level.getBlockEntity(entry.getKey()).saveWithoutMetadata()),"rejected service update changed marker "+entry.getKey());
    h.assertTrue(RoadWorkChunks.heldCount(level)==0,"service test leaked chunk tickets");
    System.out.println("SR204 SERVICE WORLD PASS legal child edit retained; actual child deletion rejects stale confirmed update without writes");h.succeed();
  }
  private Service204GameTests(){}
}
