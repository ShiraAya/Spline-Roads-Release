package com.sora.splineroads.world;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;import com.sora.splineroads.core.InterchangePlanner.*;
import com.mojang.authlib.GameProfile;import net.minecraftforge.common.util.FakePlayerFactory;import net.minecraft.server.level.ServerPlayer;import net.minecraft.world.level.GameType;
import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.gametest.framework.*;import net.minecraft.nbt.*;import net.minecraft.server.level.ServerLevel;import net.minecraftforge.gametest.*;
@GameTestHolder("splineroads_201_continuous_ends") @PrefixGameTestTemplate(false)
public final class ContinuousEnds201GameTests {
 private static final UUID OWNER=new UUID(201,815);
 private static Settings settings(){var o=RoadProfile.Options.DEFAULT.cycleFinish(RoadProfile.Options.CycleFinish.NONE).route(RoadProfile.Routing.DEFAULT.fit(false));o=o.infrastructure(o.infrastructure().gantry(RoadInfrastructure.Gantry.OFF));return new Settings(Mode.STRAIGHT,Style.H4_RAIL,Style.H4_RAIL.defaultWidth(),1,.35,90).options(o);}
 private static BlockPos node(ServerLevel l,V v){var pos=BlockPos.containing(v.x(),v.y(),v.z());l.getChunkAt(pos);l.setBlock(pos,SplineRoads.NODE.get().defaultBlockState(),2);var n=(NodeEntity)l.getBlockEntity(pos);n.owner=OWNER;n.heightExplicit=true;n.apply(new Node(v,0,0));return pos;}
 private static RoadRecord road(ServerLevel l,ServerPlayer player,V a,V b,Settings s){return RoadData.get(l).connect(l,player,node(l,a),node(l,b),s,null);}
 @GameTest(template="empty",templateNamespace="splineroads_201_continuous_ends",timeoutTicks=36000)
 public static void neighboursBeforeAutomaticAdjustment(GameTestHelper h){run(h,26100.5,false,12);}
 @GameTest(template="empty",templateNamespace="splineroads_201_continuous_ends",timeoutTicks=36000)
 public static void independentOutermostEndpointAndInsideCuts(GameTestHelper h){run(h,28100.5,true,12);}
 @GameTest(template="empty",templateNamespace="splineroads_201_continuous_ends",timeoutTicks=36000)
 public static void raisedUpperKeepsValidOutsideAndShortensBottom(GameTestHelper h){run(h,30100.5,true,4);}
 private static void run(GameTestHelper h,double x,boolean auto,double upperGap){
  var l=h.getLevel();var d=RoadData.get(l);var player=auto?FakePlayerFactory.get(l,new GameProfile(OWNER,"SR204Fallback")):null;if(player!=null)player.setGameMode(GameType.CREATIVE);double z=x,far=auto?80:400;var s=settings();var ab=road(l,player,new V(x,-59,z-400),new V(x,-59,z+40),s);var cd=road(l,player,new V(x-400,-59+upperGap,z),new V(x+400,-59+upperGap,z),s.structure(Structure.BRIDGE));var neighbour=road(l,player,new V(x,-59,z+40),new V(x,-59,z+far),s);
  var t=Interchanges.payload(l,player,new long[]{ab.a().asLong(),ab.b().asLong(),cd.a().asLong(),cd.b().asLong()},null);t.put("Options",Interchanges.write(Options.DEFAULT.adjust(auto)));
  var originalOnly=t.copy();originalOnly.put("ContinuousLimits",t.getList("Nodes",Tag.TAG_COMPOUND).copy());originalOnly.put("Options",Interchanges.write(Options.DEFAULT));var before=d.save(new CompoundTag());boolean rejected=false;try{Interchanges.plan(originalOnly);}catch(IllegalArgumentException ex){rejected=true;}h.assertTrue(rejected,"original B=40 unexpectedly feasible; test cannot prove neighbour fallback");h.assertTrue(before.equals(d.save(new CompoundTag())),"failed planning mutated data");
  if(auto){var neighbourOnly=t.copy();neighbourOnly.put("Options",Interchanges.write(Options.DEFAULT));rejected=false;try{Interchanges.plan(neighbourOnly);}catch(IllegalArgumentException ex){rejected=true;}h.assertTrue(rejected,"neighbour end=80 unexpectedly feasible; test cannot prove automatic fallback");}
  var p=Interchanges.plan(t);double end=p.anchors().get(1).position().z()-z;
  h.assertTrue(p.legs().stream().anyMatch(leg->neighbour.id().equals(ContinuousRoads.sourceId(leg.name()))),"required neighbour was not used");
  h.assertTrue(p.anchors().get(0).position().z()>ab.start().position().z()+1,"successful auto/fixed plan did not trim long A into ordinary tail");
  h.assertTrue(ContinuousRoads.rangeBoundary(t,0,p.anchors().get(0)),"long A should become an internal cut, not a moved outer node");
  if(auto){h.assertTrue(end>80&&end<400,"automatic B did not independently extend outer neighbour: "+end);h.assertTrue(ContinuousRoads.physicalEnd(t,1,p.anchors().get(1)).equals(neighbour.b()),"automatic adjustment targeted inner selected B instead of farthest neighbour");}
  else h.assertTrue(end>40&&end<400,"neighbour range did not cut inside donor: "+end);
  if(upperGap<7){h.assertTrue(p.anchors().get(2).position().y()>-59+upperGap&&p.anchors().get(3).position().y()>-59+upperGap,"required upper lift was lost during tightening");h.assertTrue(p.anchors().get(0).position().y()==-59&&p.anchors().get(1).position().y()==-59,"bottom layer moved");h.assertTrue(p.anchors().get(2).position().x()==x-400&&p.anchors().get(3).position().x()==x+400,"raised axis was shortened across an infeasible old-height tail");}
  var oldA=((NodeEntity)l.getBlockEntity(ab.a())).constructionNode();var oldB=((NodeEntity)l.getBlockEntity(ab.b())).constructionNode();var tails=p.legs().stream().filter(leg->ContinuousRoads.donor(leg.name())).map(leg->ContinuousSections.id(leg.name())).toList();
  // A free fit may tighten farther on a warm cache. Commit the exact plan whose tail IDs
  // the test asserts, using the same non-null-player confirmation path as the real editor.
  var request=t.copy();var groupsBefore=Set.copyOf(d.interchanges.keySet());
  if(auto){
   h.assertTrue(ConfirmedInterchangeFit.eligible(t),"automatic fixture did not reach confirmed-fit path");
   var fitted=new ListTag();p.anchors().forEach(n->fitted.add(RoadRecord.writeNode(n)));
   request.put("SourceNodes",t.getList("Nodes",Tag.TAG_COMPOUND).copy());request.putInt("SourceAxesHash",HostAxes.signature(t));request.put("FittedNodes",fitted);
   var checked=Interchanges.confirmedPlan(t,request);
   h.assertTrue(checked.anchors().equals(p.anchors()),"confirmed fallback changed preview endpoints");
   h.assertTrue(checked.legs().stream().filter(leg->ContinuousRoads.donor(leg.name())).map(leg->ContinuousSections.id(leg.name())).toList().equals(tails),"confirmed fallback changed preview outside remainder IDs");
  }
  Interchanges.build(l,player,request);
  if(auto){var added=d.interchanges.keySet().stream().filter(id->!groupsBefore.contains(id)).toList();h.assertTrue(added.size()==1,"confirmed fallback did not create exactly one assembly");h.assertTrue(d.interchanges.get(added.get(0)).getList("Nodes",Tag.TAG_COMPOUND).equals(request.getList("FittedNodes",Tag.TAG_COMPOUND)),"saved fallback endpoints differ from preview");}

  h.assertTrue(l.getBlockEntity(ab.a()) instanceof NodeEntity n&&n.constructionNode().equals(oldA),"sufficient outer A moved");h.assertTrue(l.getBlockEntity(ab.b()) instanceof NodeEntity n&&n.constructionNode().equals(oldB),"inner selected B seam moved");
  var target=BlockPos.containing(p.anchors().get(1).position().x(),p.anchors().get(1).position().y(),p.anchors().get(1).position().z());h.assertTrue(l.getBlockEntity(target) instanceof NodeEntity,"fitted endpoint not committed");if(auto)h.assertTrue(!(l.getBlockEntity(neighbour.b()) instanceof NodeEntity),"old outer neighbour endpoint was not moved");else h.assertTrue(l.getBlockEntity(neighbour.b()) instanceof NodeEntity,"unused far neighbour endpoint was moved");
  for(UUID tail:tails)h.assertTrue(d.index.roads.containsKey(tail)&&d.index.roads.get(tail).record.assembly()==null,"outside remainder lost during fallback");
  System.out.println("SR201 RANGE END WORLD PASS upperGap="+upperGap+" auto="+auto+" original40 < neighbour="+far+" finalB="+end+" Aphysical="+oldA.position()+" outer="+neighbour.b()+" tails="+tails.size());h.succeed();
 }
}
