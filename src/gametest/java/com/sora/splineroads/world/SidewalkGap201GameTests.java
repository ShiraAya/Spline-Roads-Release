package com.sora.splineroads.world;
import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.world.level.block.Blocks;
@net.minecraftforge.gametest.GameTestHolder("splineroads_201_gap") @net.minecraftforge.gametest.PrefixGameTestTemplate(false)
public class SidewalkGap201GameTests {
 public static RoadRecord scene(net.minecraft.server.level.ServerLevel level,int x,int z){return scene(level,x,z,0);}
 static RoadRecord scene(net.minecraft.server.level.ServerLevel level,int x,int z,double shift){level.getChunkAt(new BlockPos(x,0,z));double ground=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);var a=new Node(new V(x+.35,ground-.55+shift,z+.35),0,.02);var b=new Node(new V(x+.35,ground+.25+shift,z+40.35),0,.02);var first=Curve201GameTests.marker(level,a);var last=Curve201GameTests.marker(level,b);var s=RoadLanes.configure(new Settings(Mode.STRAIGHT,Style.O2_YELLOW,9,1,.35,90).structure(Structure.GROUND),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(1,1),4);s=s.options(s.options().extras(false,false,false).outerRail(RoadProfile.OuterRail.OFF).sidewalk(new RoadSidewalks.Config(true,RoadSidewalks.Side.BOTH,3,"minecraft:bricks")).streetscape(s.options().streetscape().apron(RoadStreetscape.Apron.ROAD)));return RoadData.get(level).connect(level,null,first,last,s,null);}
 @net.minecraft.gametest.framework.GameTest(template="empty",templateNamespace="splineroads_201_gap",timeoutTicks=4000)
 public static void actualSlopedFootwayFill(net.minecraft.gametest.framework.GameTestHelper h){var level=h.getLevel();int fills=0,thin=0;for(int variant=0;variant<2;variant++){var road=scene(level,203000+variant*100,0,-variant);for(double d:variant==0?new double[]{5,10,15}:new double[]{20,24,28})for(int side:new int[]{-1,1}){var at=RoadStructures.sample(road.mesh(),d);var top=at.at(side*(at.halfWidth()+2.95),-.2);var p=BlockPos.containing(top.x(),top.y(),top.z());var entity=level.getBlockEntity(p);h.assertTrue(entity instanceof RoadFillEntity,"actual slope edge has no capped original soil at "+p+" state="+level.getBlockState(p));var fill=(RoadFillEntity)entity;h.assertTrue(fill.cap()>=.01&&p.getY()+fill.cap()<=top.y()+1e-6,"actual edge cap outside footway volume");h.assertTrue(fill.fill().canOcclude()&&fill.fill().getFluidState().isEmpty(),"original soil lost");if(fill.cap()<.25)thin++;fills++;}
  if(variant==0){for(double station:new double[]{32,36,39})for(int side:new int[]{-1,1}){
   var at=RoadStructures.sample(road.mesh(),station);var top=at.at(side*(at.halfWidth()+2.95),-.2);double terrain=road.start().position().y()+.55;
   // The visible dark stripe must not be an actual empty column under the slab.
   for(double y=terrain-.08;y<top.y()-.005;y+=.01){var pos=BlockPos.containing(top.x(),y,top.z());double yy=y;var shape=level.getBlockState(pos).getCollisionShape(level,pos);boolean covered=shape.toAabbs().stream().anyMatch(box->top.x()-pos.getX()>box.minX-1e-7&&top.x()-pos.getX()<box.maxX+1e-7&&top.z()-pos.getZ()>box.minZ-1e-7&&top.z()-pos.getZ()<box.maxZ+1e-7&&yy-pos.getY()>box.minY-1e-7&&yy-pos.getY()<box.maxY+1e-7);h.assertTrue(covered,"high footway edge has an air gap at "+pos+" y="+y);}
  }
   int rings=0;double terrain=road.start().position().y()+.55;for(int x=202990;x<=203010;x++)for(int z=25;z<=40;z++){var pos=BlockPos.containing(x,terrain-.5,z);var ledger=RoadData.get(level).streetLedger(pos.asLong());if(ledger.sidewalk()==null)continue;rings++;h.assertTrue(ledger.sidewalk().is(Blocks.BRICKS),"registered outer ring changed material");var actual=level.getBlockState(pos);boolean bricks=actual.is(Blocks.BRICKS)
       ||RoadBlocks.isCollider(actual)&&actual.getValue(RoadBlocks.Road.FILL)==RoadBlocks.Fill.BRICKS
       ||level.getBlockEntity(pos) instanceof RoadFillEntity fill&&fill.fill().is(Blocks.BRICKS);h.assertTrue(bricks,"registered ring not rendered as bricks at "+pos+" state="+actual);}
   h.assertTrue(rings>0,"fixture has no registered outer paving ring");
  }
 }h.assertTrue(thin>=6,"low footway edge cases missing");System.out.println("SR201 ACTUAL SLOPE PASS cappedCells="+fills+" thinCells="+thin);h.succeed();}

 @net.minecraft.gametest.framework.GameTest(template="empty",templateNamespace="splineroads_201_gap",timeoutTicks=400)
 public static void thinFootwayCaps(net.minecraft.gametest.framework.GameTestHelper h){int thin=0,negative=0;
  for(double[] pair:new double[][]{{.18,0},{.4999,0},{.5,.5},{.75,.5},{.999,.5},{1,1}})
   h.assertTrue(GroundTerrainJoin.roadEdgeCap(pair[0])==pair[1],"incorrect asphalt edge fill height for "+pair[0]);
  for(double yaw:new double[]{0,23,90})for(double grade:new double[]{-.02,.02})for(double fraction:new double[]{.05,.1,.18,.24}){
   var a=new Node(new V(.35,4+fraction-.2-grade*20,.35),yaw,grade);var b=new Node(a.position().add(a.direction().mul(40)).add(new V(0,grade*40,0)),yaw,grade);var s=RoadLanes.configure(new Settings(Mode.STRAIGHT,Style.O2_YELLOW,9,1,.35,90).structure(Structure.GROUND),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(1,1),4);s=s.options(s.options().sidewalk(new RoadSidewalks.Config(true,RoadSidewalks.Side.BOTH,3,"minecraft:bricks")).streetscape(s.options().streetscape().apron(RoadStreetscape.Apron.ROAD)));
   for(var structure:new Structure[]{Structure.GROUND,Structure.BRIDGE,Structure.TUNNEL}){var settings=s.structure(structure);var r=new RoadRecord(UUID.randomUUID(),UUID.randomUUID(),BlockPos.containing(a.position().x(),a.position().y(),a.position().z()),BlockPos.containing(b.position().x(),b.position().y(),b.position().z()),a,b,settings,false,0);r=r.structures(RoadSidewalks.parts(r.mesh(),settings.options().sidewalk()));var built=new RoadIndex.Built(r);
    for(int side:new int[]{-1,1}){var at=RoadStructures.sample(built.mesh,20);var top=at.at(side*(at.halfWidth()+2.95),-.2);var p=BlockPos.containing(top.x(),top.y(),top.z());double cap=GroundTerrainJoin.cap(p,Blocks.GRASS_BLOCK.defaultBlockState(),List.of(built));
     if(structure==Structure.GROUND){h.assertTrue(cap>=.01&&cap<.25,"thin footway cap discarded: "+cap);h.assertTrue(p.getY()+cap<=top.y()+1e-6,"thin footway fill crosses surface");h.assertTrue(GroundTerrainJoin.usable(cap),"thin cap cannot reach fill entity");thin++;}
     else{h.assertTrue(cap==1,"bridge/tunnel unexpectedly filled exterior soil");negative++;}
     h.assertTrue(GroundTerrainJoin.cap(p,Blocks.WATER.defaultBlockState(),List.of(built))==1,"water converted into thin fill");
    }
   }
  }h.assertTrue(thin==48&&negative==96,"missing thin-cap matrix cases");System.out.println("SR201 THIN FOOTWAY PASS thin="+thin+" bridgeTunnel="+negative);h.succeed();
 }
 @net.minecraft.gametest.framework.GameTest(template="empty",templateNamespace="splineroads_201_gap",timeoutTicks=200)
 public static void slopedFootwayCaps(net.minecraft.gametest.framework.GameTestHelper h){int checked=0,baselineMissing=0;
 for(double yaw:new double[]{0,23,90})for(double grade:new double[]{-.02,.02}){
 var a=new Node(new V(.35,4.45,.35),yaw,grade);var dir=a.direction();var b=new Node(a.position().add(dir.mul(40)).add(new V(0,grade*40,0)),yaw,grade);var s=RoadLanes.configure(new Settings(Mode.STRAIGHT,Style.O2_YELLOW,9,1,.35,90).structure(Structure.GROUND),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(1,1),4);s=s.options(s.options().sidewalk(new RoadSidewalks.Config(true,RoadSidewalks.Side.BOTH,3,"minecraft:bricks")).streetscape(s.options().streetscape().apron(RoadStreetscape.Apron.ROAD)));
 var r=new RoadRecord(UUID.randomUUID(),UUID.randomUUID(),BlockPos.containing(a.position().x(),a.position().y(),a.position().z()),BlockPos.containing(b.position().x(),b.position().y(),b.position().z()),a,b,s,false,0);r=r.structures(RoadSidewalks.parts(r.mesh(),s.options().sidewalk()));var built=new RoadIndex.Built(r);
 for(double station:new double[]{8,18,28})for(int side:new int[]{-1,1}){var sample=RoadStructures.sample(built.mesh,station);var outside=sample.at(side*(sample.halfWidth()+2.95),-.2);var p=BlockPos.containing(outside.x(),outside.y(),outside.z());double cap=GroundTerrainJoin.cap(p,Blocks.GRASS_BLOCK.defaultBlockState(),List.of(built));
 if(cap>=.25&&cap<.999){if(GroundTerrainJoin201Baseline.cap(p,Blocks.GRASS_BLOCK.defaultBlockState(),List.of(built))==1)baselineMissing++;checked++;if(p.getY()+cap>outside.y()+1e-6)throw new AssertionError("soil invades footway surface yaw="+yaw+" grade="+grade+" station="+station+" cap="+cap+" surface="+outside.y()+" cell="+p);}
 else if(outside.y()-Math.floor(outside.y())>.3&&outside.y()-Math.floor(outside.y())<.9)throw new AssertionError("unfilled sloped footway edge "+p+" cap="+cap+" surface="+outside.y());
 if(GroundTerrainJoin.cap(p,Blocks.WATER.defaultBlockState(),List.of(built))!=1)throw new AssertionError("fluid converted to soil");
 }
 }if(checked<12)throw new AssertionError("insufficient active edge cells "+checked);if(baselineMissing<12)throw new AssertionError("old algorithm control did not expose missing fill");System.out.println("SidewalkGap201Validation PASS partialCells="+checked+" baselineMissingWithCorrectTop="+baselineMissing);h.succeed();}
}
