package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.SplineRoads;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.gametest.framework.*;
import java.util.*;
@net.minecraftforge.gametest.GameTestHolder("splineroads_203")
@net.minecraftforge.gametest.PrefixGameTestTemplate(false)
public class Structures203GameTests {
 static Settings settings(){var s=RoadLanes.configure(new Settings(Mode.STRAIGHT,Style.O2_YELLOW,9,1,.35,90),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(1,1),4);return s.options(s.options().extras(false,false,false).outerRail(RoadProfile.OuterRail.OFF).route(s.options().routing().fit(false)));}
 static RoadRecord build(net.minecraft.server.level.ServerLevel l,int x,double y,Settings s){var a=Curve201GameTests.marker(l,new Node(new V(x+.5,y,.5),0,0));var b=Curve201GameTests.marker(l,new Node(new V(x+.5,y,64.5),0,0));return RoadData.get(l).connect(l,null,a,b,s,null);}
 @GameTest(template="empty",templateNamespace="splineroads_203",timeoutTicks=4000)
 public static void holeDoesNotCreatePiers(GameTestHelper h){var l=h.getLevel();int x=713000;l.getChunkAt(new BlockPos(x,0,0));double y=l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,0);
  var hole=BlockPos.containing(x,y-1,12);for(int dy=0;dy<8;dy++)l.setBlock(hole.below(dy),Blocks.AIR.defaultBlockState(),3);
  var r=build(l,x,y,settings());h.assertTrue(r.settings().options().streetscape().raisedSpans().isEmpty(),"tiny hole classified elevated");h.assertTrue(r.structures().stream().noneMatch(p->p.pier()&&p.material()==RoadStructures.Material.CONCRETE&&p.a().y()<y-2),"tiny hole generated bridge pier");h.succeed();}
 @GameTest(template="empty",templateNamespace="splineroads_203",timeoutTicks=6000)
 public static void noPiersAndTemplatePersist(GameTestHelper h){var l=h.getLevel();for(int v=0;v<2;v++){int x=714000+v*200;l.getChunkAt(new BlockPos(x,0,0));double y=l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,0)+16;var s=settings().structure(v==0?Structure.AUTO:Structure.BRIDGE);s=s.options(s.options().infrastructure(s.options().infrastructure().bridge(RoadInfrastructure.Bridge.TEMPLATE).supportsEnabled(false)));
   var r=build(l,x,y,s);h.assertTrue(r.structures().stream().noneMatch(p->p.pier()&&p.a().y()<y-1-1e-6),"disabled pier built");
   var saved=RoadData.get(l).save(new CompoundTag());var read=RoadData.load(saved).index.roads.get(r.id()).record;h.assertTrue(!read.settings().options().infrastructure().supportsEnabled(),"support choice lost");h.assertTrue(read.settings().options().infrastructure().bridge()==RoadInfrastructure.Bridge.TEMPLATE,"template lost");
  }h.succeed();}
 @GameTest(template="empty",templateNamespace="splineroads_203",timeoutTicks=8000)
 public static void sidewalkBorderEditsRestoreTerrain(GameTestHelper h){var l=h.getLevel();int x=715000;l.getChunkAt(new BlockPos(x,0,0));double y=l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,0);var s=settings().structure(Structure.GROUND);s=s.options(s.options().sidewalk(new RoadSidewalks.Config(true,RoadSidewalks.Side.BOTH,3,"minecraft:bricks")).streetscape(s.options().streetscape().apron(RoadStreetscape.Apron.ROAD)));var r=build(l,x,y,s);var cells=new ArrayList<BlockPos>();for(int xx=x-12;xx<=x+12;xx++)for(int z=4;z<60;z++){var p=BlockPos.containing(xx,y-.5,z);if(RoadData.get(l).streetLedger(p.asLong()).sidewalk()!=null)cells.add(p);}h.assertTrue(!cells.isEmpty(),"no same-sidewalk border");
  for(var mode:List.of(RoadStreetscape.Apron.TERRAIN,RoadStreetscape.Apron.NONE,RoadStreetscape.Apron.ROAD)){var old=Curve201GameTests.current(l,r.id());var next=old.settings().options(old.settings().options().streetscape(old.settings().options().streetscape().apron(mode)));RoadData.get(l).connect(l,null,old.a(),old.b(),next,old.id(),false);for(var p:cells)h.assertTrue((RoadData.get(l).streetLedger(p.asLong()).sidewalk()!=null)==(mode==RoadStreetscape.Apron.ROAD),"outer border ownership stale "+mode+" "+p);}
  h.succeed();}
 @GameTest(template="empty",templateNamespace="splineroads_203",timeoutTicks=8000)
 public static void missingFoundationDoesNotRejectBridge(GameTestHelper h){var l=h.getLevel();int k=0;
  for(var kind:List.of(RoadInfrastructure.Bridge.BEAM,RoadInfrastructure.Bridge.TEMPLATE)){
   int x=716000+k++*200;l.getChunkAt(new BlockPos(x,0,0));double y=l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,0)+110;
   var s=settings().structure(Structure.BRIDGE);s=s.options(s.options().infrastructure(s.options().infrastructure().bridge(kind)));
   var lower=build(l,x,y-14,s);h.assertTrue(dataHas(l,lower.id()),"lower bridge with no reachable foundation not built");
   var r=build(l,x,y,s);h.assertTrue(dataHas(l,r.id()),"stacked bridge with no reachable shared foundation not built");
   h.assertTrue(r.structures().stream().noneMatch(p->p.pier()&&p.a().y()<y-2),"unreachable foundation produced a shaft");
  }h.assertTrue(RoadWorkChunks.heldCount(l)==0,"missing-foundation build leaked tickets");h.succeed();
 }
 static boolean dataHas(net.minecraft.server.level.ServerLevel l,UUID id){return RoadData.get(l).index.roads.containsKey(id);}

}
