package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.*;
import net.minecraftforge.gametest.*;

@GameTestHolder("splineroads_203_sidewalk") @PrefixGameTestTemplate(false)
public final class Sidewalk203GameTests {
 @GameTest(template="empty",templateNamespace="splineroads_203_sidewalk",timeoutTicks=4000)
 public static void actualVanillaContact(GameTestHelper h){
  var level=h.getLevel();int x=230000,z=100;level.getChunkAt(new BlockPos(x,0,z));
  double y=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
  var a=Curve201GameTests.marker(level,new Node(new V(x+.5,y,z+.5),0,0));
  var b=Curve201GameTests.marker(level,new Node(new V(x+.5,y,z+40.5),0,0));
  var s=RoadLanes.configure(new Settings(Mode.STRAIGHT,Style.O2_YELLOW,9,1,.35,90).structure(Structure.GROUND),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(1,1),4);
  s=s.options(s.options().extras(false,false,false).outerRail(RoadProfile.OuterRail.OFF).sidewalk(new RoadSidewalks.Config(true,RoadSidewalks.Side.BOTH,5,"minecraft:bricks")));
  var data=RoadData.get(level);var road=data.connect(level,null,a,b,s,null);
  var sample=RoadStructures.sample(road.mesh(),20);var contact=sample.at(sample.halfWidth()+2.5,-.2);
  var p=new BlockPos((int)Math.floor(contact.x()),(int)Math.ceil(contact.y()),(int)Math.floor(contact.z()));
  h.assertTrue(level.getBlockState(p).isAir(),"fixture upper cell is not empty");
  var saved=data.save(new CompoundTag());int positive=0;
  for(var block:List.of(Blocks.BLUE_WOOL,Blocks.STONE,Blocks.GLASS,Blocks.OAK_PLANKS)){
   var state=block.defaultBlockState();level.setBlock(p,state,3);
   double bottom=SidewalkBlockContact.bottom(level,data.index,p,state);
   h.assertTrue(Math.abs(bottom+.8)<1e-6,"full vanilla block did not contact footway: "+block+" bottom="+bottom);
   var lower=p.below();var shape=level.getBlockState(lower).getCollisionShape(level,lower);
   double gap=1+bottom*.5;
   h.assertTrue(shape.toAabbs().stream().anyMatch(box->box.contains(.5,gap,.5)),"visible contact has no matching lower-cell collision");
   h.assertTrue(level.getBlockState(p).equals(state),"vanilla state replaced");
   h.assertTrue(saved.equals(data.save(new CompoundTag())),"contact wrote into road save data");
   var reloaded=RoadData.load(data.save(new CompoundTag()));
   h.assertTrue(Math.abs(SidewalkBlockContact.bottom(level,reloaded.index,p,state)-bottom)<1e-8,"contact changed after NBT reload");
   level.setBlock(p,Blocks.AIR.defaultBlockState(),3);
   h.assertTrue(SidewalkBlockContact.bottom(level,data.index,p,Blocks.AIR.defaultBlockState())==0,"removed block keeps contact");
   var released=level.getBlockState(lower).getCollisionShape(level,lower);
   h.assertTrue(released.toAabbs().stream().noneMatch(box->box.contains(.5,gap,.5)),"removed block leaves stale collision");positive++;
  }
  for(var block:List.of(Blocks.CHEST,Blocks.OAK_STAIRS,Blocks.STONE_SLAB,Blocks.OAK_FENCE,Blocks.DANDELION,Blocks.WATER)){
   h.assertTrue(!SidewalkBlockContact.eligible(level,p,block.defaultBlockState()),"unsupported special block stretched: "+block);
  }
  h.assertTrue(positive==4&&RoadWorkChunks.heldCount(level)==0,"missing contact checks or leaked chunk tickets");
  System.out.println("SR203 VANILLA CONTACT WORLD PASS positive="+positive+" specialNegative=6 removal+NBT+state+ledger");h.succeed();
 }
 @GameTest(template="empty",templateNamespace="splineroads_203_sidewalk",timeoutTicks=8000)
 public static void actualApronModesEditAndRestore(GameTestHelper h){
  var level=h.getLevel();var road=SidewalkGap201GameTests.scene(level,231000,100);var data=RoadData.get(level);
  var originals=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
  int low=(int)Math.floor(road.start().position().y())-2,high=(int)Math.ceil(road.end().position().y())+2;
  for(int xx=230985;xx<=231015;xx++)for(int zz=100;zz<=140;zz++)for(int yy=low;yy<=high;yy++){
   var p=new BlockPos(xx,yy,zz);var ledger=data.streetLedger(p.asLong());
   if(ledger.sidewalk()!=null&&level.getBlockState(p).is(Blocks.BRICKS)&&ledger.original()!=null&&!ledger.original().is(Blocks.BRICKS))originals.put(p,ledger.original());
  }
  h.assertTrue(!originals.isEmpty(),"same-sidewalk mode fixture has no outside border");
  for(var mode:List.of(RoadStreetscape.Apron.TERRAIN,RoadStreetscape.Apron.NONE,RoadStreetscape.Apron.ROAD)){
   var current=data.index.roads.get(road.id()).record;var next=current.settings().options(current.settings().options().streetscape(current.settings().options().streetscape().apron(mode)));
   data.connect(level,null,current.a(),current.b(),next,current.id(),false);
   for(var e:originals.entrySet()){
    var ledger=data.streetLedger(e.getKey().asLong());
    if(mode==RoadStreetscape.Apron.ROAD)h.assertTrue(ledger.sidewalk()!=null&&ledger.sidewalk().is(Blocks.BRICKS),"same sidewalk border not restored");
    else {h.assertTrue(ledger.sidewalk()==null,"old artificial border retained in mode "+mode);h.assertTrue(level.getBlockState(e.getKey()).getBlock()==e.getValue().getBlock(),"terrain not restored in mode "+mode);}
   }
   var loaded=RoadData.load(data.save(new CompoundTag()));
   h.assertTrue(loaded.index.roads.get(road.id()).record.settings().options().streetscape().apron()==mode,"apron mode lost on NBT reload");
  }
  h.assertTrue(RoadWorkChunks.heldCount(level)==0,"apron edit leaked chunk tickets");
  System.out.println("SR203 APRON MODES WORLD PASS borderCells="+originals.size()+" modes=3 edit+restore+NBT");h.succeed();
 }
}

