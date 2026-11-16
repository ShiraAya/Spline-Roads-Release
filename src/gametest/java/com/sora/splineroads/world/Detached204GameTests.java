package com.sora.splineroads.world;

import com.mojang.authlib.GameProfile;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.GameType;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.*;

/** Actual user paint edit after a valid preview must revoke authority over a detached tail. */
@GameTestHolder("splineroads_204_detached") @PrefixGameTestTemplate(false)
public final class Detached204GameTests {
  @GameTest(template="empty",templateNamespace="splineroads_204_sequence",timeoutTicks=36000)
  public static void editedOutsideTailRejectsConfirmedUpdateWithoutWrites(GameTestHelper h){
    var level=h.getLevel();var data=RoadData.get(level);
    var player=FakePlayerFactory.get(level,new GameProfile(new UUID(20402,20404),"SR204TailEdit"));player.setGameMode(GameType.CREATIVE);
    var points=Sequence204GameTests.connect(h,player,850000.5,false);var groups=new HashSet<>(data.interchanges.keySet());
    var first=Interchanges.payload(level,player,points,null);first.put("Options",Interchanges.write(Sequence204GameTests.options(Preset.CLOVERLEAF)));
    var clover=Interchanges.plan(first);Interchanges.build(level,player,Sequence204GameTests.command(first,Sequence204GameTests.tags(clover.anchors())));
    var added=new HashSet<>(data.interchanges.keySet());added.removeAll(groups);h.assertTrue(added.size()==1,"initial cloverleaf did not build");var group=added.iterator().next();
    var saved=data.interchanges.get(group);var tail=saved.getList("ContinuousSources",Tag.TAG_COMPOUND).stream().map(v->(CompoundTag)v)
        .filter(v->v.getBoolean("Detached")&&v.getInt("Axis")==0).map(v->RoadRecord.load(v.getCompound("Road")))
        .filter(r->r.start().position().x()>850000.5).findFirst().orElseThrow();
    var real=data.index.roads.get(tail.id()).record;h.assertTrue(real.assembly()==null,"outside tail is not ordinary");
    var source=Interchanges.payload(level,player,saved.getLongArray("Points"),group);source.put("Options",Interchanges.write(Sequence204GameTests.options(Preset.TURBINE)));
    var preview=Interchanges.plan(source);var request=Sequence204GameTests.command(source,Sequence204GameTests.tags(preview.anchors()));
    h.assertTrue(Interchanges.confirmedPlan(source,request).anchors().equals(preview.anchors()),"unedited TURBINE preview was not valid");
    // Same authoritative method used by the lane-line editor; geometry remains unchanged.
    var paint=new CompoundTag();paint.putUUID("Id",real.id());paint.putInt("Signature",GantryTool.signature(real));paint.putBoolean("HideArrows",!real.settings().options().hideArrows());
    data.editLaneLine(level,player,paint);var edited=data.index.roads.get(real.id()).record;
    h.assertTrue(edited.settings().options().hideArrows()!=real.settings().options().hideArrows(),"real user paint edit did not save");
    h.assertTrue(edited.mesh().samples().equals(real.mesh().samples()),"paint negative control changed road geometry");
    var before=data.save(new CompoundTag());var editedTag=edited.save();var markers=new HashMap<BlockPos,CompoundTag>();
    for(long value:saved.getLongArray("Points")){var pos=BlockPos.of(value);markers.put(pos,level.getBlockEntity(pos).saveWithoutMetadata());}
    for(var pos:List.of(real.a(),real.b()))markers.put(pos,level.getBlockEntity(pos).saveWithoutMetadata());
    boolean rejected=false;
    try{Interchanges.build(level,player,request);}catch(IllegalArgumentException expected){
      rejected=expected.getMessage()!=null&&expected.getMessage().contains("原立交外侧余段已修改、删除或不再连续，不能自动占用");
      if(!rejected)throw expected;System.out.println("SR204 DETACHED EXPECTED actual user paint edit: "+expected.getMessage());
    }
    h.assertTrue(rejected,"stale confirmed preview overwrote a user-edited outside tail");
    h.assertTrue(before.equals(data.save(new CompoundTag())),"rejected tail update mutated saved roads/assembly/terrain ledger");
    h.assertTrue(editedTag.equals(data.index.roads.get(real.id()).record.save()),"rejected update changed the edited tail");
    for(var entry:markers.entrySet())h.assertTrue(level.getBlockEntity(entry.getKey())!=null&&entry.getValue().equals(level.getBlockEntity(entry.getKey()).saveWithoutMetadata()),"rejected update changed marker "+entry.getKey());
    h.assertTrue(Interchanges.read(data.interchanges.get(group).getCompound("Options")).preset()==Preset.CLOVERLEAF,"rejected update changed current preset");
    h.assertTrue(RoadWorkChunks.heldCount(level)==0,"rejected update leaked chunks");
    System.out.println("SR204 DETACHED WORLD PASS real cloverleaf/valid turbine preview/actual user edit/stale confirmed rejection/no writes");h.succeed();
  }
}
