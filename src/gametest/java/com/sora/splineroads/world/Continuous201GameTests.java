package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import java.util.*;
import java.nio.file.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.gametest.*;

@GameTestHolder("splineroads_201_continuous") @PrefixGameTestTemplate(false)
public final class Continuous201GameTests {
  private static final UUID OWNER=new UUID(201,4096);
  private static Settings settings(){var o=RoadProfile.Options.DEFAULT.cycleFinish(RoadProfile.Options.CycleFinish.NONE).route(RoadProfile.Routing.DEFAULT.fit(false));o=o.infrastructure(o.infrastructure().gantry(RoadInfrastructure.Gantry.OFF));return new Settings(Mode.STRAIGHT,Style.H4_RAIL,Style.H4_RAIL.defaultWidth(),1,.35,90).options(o);}
  private static BlockPos pos(V p){return BlockPos.containing(p.x(),p.y(),p.z());}
  private static RoadRecord record(V a,V b,Settings s){double yaw=RoadPlanner.yaw(b.sub(a));return new RoadRecord(UUID.randomUUID(),OWNER,pos(a),pos(b),new Node(a,yaw,0),new Node(b,yaw,0),s,false,4);}
  private static void put(RoadData d,RoadRecord r){d.index.put(RoadIndex.Built.loading(r));}
  private static CompoundTag input(Node[] nodes,Options options,Settings s){var t=new CompoundTag();var list=new ListTag();long[] points=new long[nodes.length];for(int i=0;i<nodes.length;i++){list.add(RoadRecord.writeNode(nodes[i]));points[i]=pos(nodes[i].position()).asLong();}t.put("Nodes",list);t.putLongArray("Points",points);t.put("Options",Interchanges.write(options));for(int i=1;i<=3;i++)t.put("Main"+i,RoadRecord.writeSettings(s));return t;}
  private static void rejected(GameTestHelper h,Runnable task,String message){boolean rejected=false;try{task.run();}catch(IllegalArgumentException ex){rejected=true;System.out.println("SR201 RANGE EXPECTED "+message+": "+ex.getMessage());}h.assertTrue(rejected,"accepted "+message);}
  @GameTest(template="empty",templateNamespace="splineroads_201_continuous",timeoutTicks=18000)
  public static void capSettingsAndNegativeInputs(GameTestHelper h){
    var d=new RoadData();var s=settings();var road=record(new V(0,0,-300),new V(0,0,300),s);put(d,road);var other=record(new V(-300,12,0),new V(300,12,0),s);put(d,other);
    double z=300;for(int i=0;i<3;i++){var next=record(new V(0,0,z),new V(0,0,z+1600),s);put(d,next);z+=1600;}
    var t=input(new Node[]{road.start(),road.end(),other.start(),other.end()},Options.DEFAULT,s);ContinuousRoads.capture(d,t);var limit=RoadRecord.readNode(t.getList("ContinuousLimits",Tag.TAG_COMPOUND).getCompound(1));h.assertTrue(Math.abs(limit.position().z()-4396)<1e-6,"4096 cap rounded to neighbour endpoint");h.assertTrue(ContinuousRoads.sources(t).size()==5,"wrong captured chain size");
    var changed=road.settings(s.options(s.options().outerRail(RoadProfile.OuterRail.SOUND_LEFT)));h.assertTrue(!ContinuousRoads.sameSettings(road,changed),"different user side furniture accepted");
    var joined=road.settings(s.options(s.options().ends(new RoadTransitions.Ends(null,null,true,12,7,null,4,2,3))));h.assertTrue(ContinuousRoads.sameSettings(road,joined),"generated end metadata blocked same settings");
    var p=new RoadAttachments.Point(UUID.randomUUID(),new V(0,0,0),new V(0,0,0),false);h.assertTrue(ContinuousRoads.sameSettings(road,road.withAttachments(RoadAttachments.Data.EMPTY.points(List.of(p)))),"point identity blocked same settings");
    rejected(h,()->ContinuousRoads.physicalEnd(t,1,new Node(new V(0,0,4400),0,0)),"cap inside a road cannot move original seam");
    var curved=t.copy();HostAxes.write(curved,List.of(RoadAxis.straight(road.start().position(),road.end().position()),new RoadAxis(List.of(new V(-300,12,0),new V(0,12,10),new V(300,12,0)))));rejected(h,()->Interchanges.plan(curved),"curved host not silently straightened");
    var chain=new RoadData();var first=record(new V(0,0,-300),new V(0,0,0),s);var second=record(new V(0,0,0),new V(0,0,300),changed.settings());put(chain,first);put(chain,second);put(chain,other);var mixed=input(new Node[]{first.start(),second.end(),other.start(),other.end()},Options.DEFAULT,s);rejected(h,()->ContinuousRoads.capture(chain,mixed),"selected multi-road chain with different settings");
    System.out.println("SR201 RANGE PASS exact4096/settings/cap-interior/curved/mixed-chain negatives");h.succeed();
  }
  @GameTest(template="empty",templateNamespace="splineroads_201_continuous",timeoutTicks=36000)
  public static void threeAndFiveTerminalContracts(GameTestHelper h){
    var s=settings();var three=new Node[]{new Node(new V(0,12,-500),0,0),new Node(new V(0,12,500),0,0),new Node(new V(500,0,0),90,0)};var d=new RoadData();put(d,record(three[0].position(),three[1].position(),s));var options=new Options(Preset.TRUMPET,false,1,96,20,5,1);var t=input(three,options,s);ContinuousRoads.capture(d,t);System.out.println("SR201 RANGE STAGE three-plan");var p=Interchanges.plan(t);h.assertTrue(p.anchors().size()==3,"three-way exposed virtual fourth anchor");h.assertTrue(p.anchors().get(2).equals(three[2]),"fixed C terminal moved");
    double r=650,k=Math.sqrt(3)/2;var five=new Node[]{new Node(new V(0,16,-r),0,0),new Node(new V(0,16,r),0,0),new Node(new V(-r*k,8,-r*.5),-60,0),new Node(new V(r*k,8,r*.5),-60,0),new Node(new V(r*k,0,-r*.5),60,0)};d=new RoadData();put(d,record(five[0].position(),five[1].position(),s));put(d,record(five[2].position(),five[3].position(),s));t=input(five,new Options(Preset.DIRECTIONAL_FIVE,false,1,96,20,5,1),s);t.putDouble("MultiBaseY",0);ContinuousRoads.capture(d,t);System.out.println("SR201 RANGE STAGE five-plan");p=Interchanges.plan(t);h.assertTrue(p.anchors().size()==5,"five-way anchor count differs");h.assertTrue(p.anchors().get(4).equals(five[4]),"fixed E terminal moved");
    System.out.println("SR201 RANGE STAGE five-auto");var auto=MultiInterchange.plan(five,new Settings[]{s,s,s},new Options(Preset.DIRECTIONAL_FIVE,false,1,96,20,5,1,0,true,true,8),-20);h.assertTrue(auto.anchors().get(4).position().y()==five[4].position().y(),"auto lowering moved bottom E");
    System.out.println("SR201 RANGE PASS real3/5 geometry, C/E fixed, bottom Y preserved");h.succeed();
  }
  private static BlockPos marker(ServerLevel l,V v){var p=pos(v);l.getChunkAt(p);l.setBlock(p,SplineRoads.NODE.get().defaultBlockState(),2);var n=(NodeEntity)l.getBlockEntity(p);n.owner=OWNER;n.heightExplicit=true;n.apply(new Node(v,0,0));return p;}
  private static RoadRecord build(ServerLevel l,V a,V b,Settings s){return RoadData.get(l).connect(l,null,marker(l,a),marker(l,b),s,null);}
  @GameTest(template="empty",templateNamespace="splineroads_201_continuous",timeoutTicks=36000)
  public static void occupiedWorldBuildEditDiskDelete(GameTestHelper h)throws Exception{
    var l=h.getLevel();var d=RoadData.get(l);double x=25100.5,z=25100.5;var s=settings();
    var ab=build(l,new V(x,-59,z-400),new V(x,-59,z+400),s);var cd=build(l,new V(x-400,-47,z),new V(x+400,-47,z),s.structure(Structure.BRIDGE));var neighbour=build(l,new V(x,-59,z+400),new V(x,-59,z+1600),s);
    long[] points={ab.a().asLong(),ab.b().asLong(),cd.a().asLong(),cd.b().asLong()};var t=Interchanges.payload(l,null,points,null);t.put("Options",Interchanges.write(Options.DEFAULT));var p=Interchanges.plan(t);
    h.assertTrue(p.legs().stream().noneMatch(leg->neighbour.id().equals(ContinuousRoads.sourceId(leg.name()))),"successful original roads consumed neighbour");var tails=p.legs().stream().filter(leg->ContinuousRoads.donor(leg.name())).map(leg->ContinuousSections.id(leg.name())).toList();h.assertTrue(tails.size()==4,"did not preserve both ends of both main roads: "+tails.size());
    for(var source:List.of(ab,cd)){double length=p.legs().stream().filter(leg->source.id().equals(ContinuousRoads.sourceId(leg.name()))).mapToDouble(leg->leg.mesh().length()).sum();h.assertTrue(Math.abs(length-source.caps(0).mesh().length())<.01,"occupied+tail coverage changed");}
    System.out.println("SR201 RANGE STAGE world-first-build");Interchanges.build(l,null,t);var group=d.interchanges.entrySet().stream().filter(e->e.getValue().getLongArray("Points").length==4&&e.getValue().getList("ContinuousSources",Tag.TAG_COMPOUND).stream().anyMatch(v->((CompoundTag)v).getCompound("Road").getUUID("Id").equals(ab.id()))).map(Map.Entry::getKey).findFirst().orElseThrow();var saved=d.interchanges.get(group);
    for(UUID tail:tails)h.assertTrue(d.index.roads.containsKey(tail)&&d.index.roads.get(tail).record.assembly()==null,"tail not an independent ordinary road");for(long point:saved.getLongArray("Points"))h.assertTrue(l.getBlockEntity(BlockPos.of(point)) instanceof NodeEntity,"cut point has no endpoint block");for(var end:List.of(ab.a(),ab.b(),cd.a(),cd.b()))h.assertTrue(l.getBlockEntity(end) instanceof NodeEntity,"outer original endpoint disappeared");
    h.assertTrue(d.index.roads.get(neighbour.id()).record.assembly()==null,"unused neighbour became managed");
    var edit=Interchanges.payload(l,null,saved.getLongArray("Points"),group);System.out.println("SR201 RANGE STAGE world-reedit");Interchanges.build(l,null,edit);for(UUID tail:tails)h.assertTrue(d.index.roads.containsKey(tail)&&d.index.roads.get(tail).record.assembly()==null,"re-edit deleted ordinary tail");
    var directory=Path.of("feedback201-continuous");Files.createDirectories(directory);var file=directory.resolve("road-data.nbt").toFile();NbtIo.writeCompressed(d.save(new CompoundTag()),file);var loaded=RoadData.load(NbtIo.readCompressed(file));h.assertTrue(loaded.interchanges.containsKey(group),"disk roundtrip lost assembly");for(UUID tail:tails)h.assertTrue(loaded.index.roads.containsKey(tail)&&loaded.index.roads.get(tail).record.assembly()==null,"disk roundtrip lost tail");
    Interchanges.remove(l,null,group);h.assertTrue(!d.interchanges.containsKey(group),"delete retained group");for(UUID tail:tails)h.assertTrue(d.index.roads.containsKey(tail),"assembly delete removed outside tail");h.assertTrue(d.index.roads.containsKey(neighbour.id()),"assembly delete removed unused neighbour");
    System.out.println("SR201 RANGE PASS WORLD build/cut-points/FM-NA/original-first/re-edit/disk/delete; shared Interchanges service");h.succeed();
  }
}
