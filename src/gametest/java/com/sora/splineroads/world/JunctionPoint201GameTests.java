package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.*;

@GameTestHolder("splineroads_201") @PrefixGameTestTemplate(false)
public final class JunctionPoint201GameTests {
  private static final UUID OWNER=new UUID(201,201);
  private static Settings settings(){var options=RoadProfile.Options.DEFAULT.route(RoadProfile.Routing.DEFAULT.fit(false));options=options.infrastructure(options.infrastructure().gantry(RoadInfrastructure.Gantry.OFF)).streetscape(options.streetscape().apron(RoadStreetscape.Apron.NONE));return new Settings(Mode.STRAIGHT,Style.O2_YELLOW,9,1,.35,90).options(options);}
  private static BlockPos node(ServerLevel level,V at){var p=BlockPos.containing(at.x(),at.y(),at.z());level.getChunkAt(p);level.setBlock(p,SplineRoads.NODE.get().defaultBlockState(),2);var n=(NodeEntity)level.getBlockEntity(p);n.owner=OWNER;n.heightExplicit=true;n.apply(new Node(at,0,0));return p;}
  private static RoadRecord road(ServerLevel level,double x,double z){var a=node(level,new V(x,-59,z));var b=node(level,new V(x,-59,z+320));return RoadData.get(level).connect(level,null,a,b,settings(),null);}
  private static BlockPos marker(ServerLevel level,RoadRecord road,double station){var data=RoadData.get(level);var live=data.index.roads.get(road.id());return JunctionPointTool.create(level,null,live,RoadSlicing.at(live.mesh,station).center());}
  private static void restored(GameTestHelper h,BlockPos p){var data=RoadData.get(h.getLevel());h.assertTrue(AutoJunctions.center(data,p)==null,"marker retained junction center after connection deletion");h.assertTrue(h.getLevel().getBlockEntity(p) instanceof NodeEntity n&&n.junctionSource!=null,"marker provenance lost");h.assertTrue(ManualPlannerTerrain.nodeTag((NodeEntity)h.getLevel().getBlockEntity(p)).getBoolean("JunctionMarker"),"map did not restore marker kind");}
  private static void reject(GameTestHelper h,Runnable action,String expected){boolean denied=false;try{action.run();}catch(IllegalArgumentException ex){denied=true;h.assertTrue(ex.getMessage().contains(expected),"unexpected rejection: "+ex.getMessage());}h.assertTrue(denied,"accepted invalid connection "+expected);}
  @GameTest(template="empty",templateNamespace="splineroads_201",timeoutTicks=18000)
  public static void endpointLifecycle(GameTestHelper h){
    var level=h.getLevel();var data=RoadData.get(level);var main=road(level,20100.5,20100.5);var marker=marker(level,main,160);restored(h,marker);
    var target=node(level,new V(20260.5,-59,20260.5));var link=data.connect(level,null,marker,target,settings(),null);
    h.assertTrue(AutoJunctions.center(data,marker)!=null,"endpoint connection did not build full junction");reject(h,()->JunctionPointTool.remove(level,null,marker),"请先撤除");h.assertTrue(!ManualPlannerTerrain.nodeTag((NodeEntity)level.getBlockEntity(marker)).getBoolean("JunctionMarker"),"consumed marker still displays as free marker");
    data.remove(level,null,link.id());restored(h,marker);var n=(NodeEntity)level.getBlockEntity(marker);var tag=n.saveWithoutMetadata();n.load(tag);h.assertTrue(main.id().equals(n.junctionSource),"marker NBT lost original source");
    System.out.println("SR201 POINT PASS endpoint create/connect/delete/restore/NBT");h.succeed();
  }
  @GameTest(template="empty",templateNamespace="splineroads_201",timeoutTicks=18000)
  public static void removeFreePointWithHistory(GameTestHelper h){
    var level=h.getLevel();var data=RoadData.get(level);
    var start=node(level,new V(23100.5,110,23100.5));var end=node(level,new V(23100.5,110,23420.5));
    var options=settings().options();
    var main=data.connect(level,null,start,end,settings().structure(Structure.GROUND).options(options),null);
    var at=marker(level,main,160);
    var records=Set.copyOf(data.index.atNode(at));
    var original=records.stream().map(id->data.index.roads.get(id).record).toList();
    var fake=net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(level);
    ((NodeEntity)level.getBlockEntity(at)).owner=fake.getUUID();
    for(var record:original)data.index.put(RoadIndex.Built.loading(new RoadRecord(record.id(),fake.getUUID(),
        record.a(),record.b(),record.start(),record.end(),record.settings(),record.automatic(),
        record.clearance(),record.structures(),record.endCaps(),record.buildVersion(),record.assembly(),
        record.alignment(),record.furniturePhase(),record.junction(),record.toll(),record.fragment())));
    original=records.stream().map(id->data.index.roads.get(id).record).toList();
    var history=new ManualPlannerHistory();
    try(var edit=history.begin(fake)){JunctionPointTool.remove(level,fake,at);edit.finish();}
    var n=(NodeEntity)level.getBlockEntity(at);
    h.assertTrue(!RoadTool.visibleEndpoint(level,at),"removed point remains selectable");
    n.load(n.saveWithoutMetadata());
    h.assertTrue(!n.visibleMarker(),"hidden point became visible when reloaded");
    h.assertTrue(records.equals(data.index.atNode(at))&&original.equals(records.stream().map(id->data.index.roads.get(id).record).toList()),
        "removing the marker changed either paved road");
    reject(h,()->data.connect(level,null,at,main.b(),settings(),null),"路口点已删除");
    history.apply(fake,true);restored(h,at);
    history.apply(fake,false);
    h.assertTrue(!RoadTool.visibleEndpoint(level,at),"redo did not remove the marker");
    var live=data.index.roads.get(main.id());
    h.assertTrue(at.equals(JunctionPointTool.create(level,fake,live,live.mesh.last().center()))&&records.equals(data.index.atNode(at)),
        "re-adding a road point did not reuse its seam");
    restored(h,at);h.succeed();
  }
  @GameTest(template="empty",templateNamespace="splineroads_201",timeoutTicks=18000)
  public static void twoMarkersAndServerSameSource(GameTestHelper h){
    var level=h.getLevel();var data=RoadData.get(level);var a=road(level,21100.5,21100.5);var b=road(level,21300.5,21100.5);var ma=marker(level,a,160);var mb=marker(level,b,160);
    var link=data.connect(level,null,ma,mb,settings(),null);h.assertTrue(AutoJunctions.center(data,ma)!=null&&AutoJunctions.center(data,mb)!=null,"two markers did not build both junctions");data.remove(level,null,link.id());restored(h,ma);restored(h,mb);
    var source=data.index.roads.values().stream().filter(v->v.record.fragment()!=null&&v.record.fragment().source().equals(a.id())&&v.record.a().equals(ma)).findFirst().orElseThrow();var same=marker(level,source.record,80);var before=data.save(new CompoundTag());
    reject(h,()->data.connect(level,null,ma,same,settings(),null),"不同道路");h.assertTrue(before.equals(data.save(new CompoundTag())),"same-source build mutated RoadData");
    var host=data.index.roads.values().stream().filter(v->v.record.fragment()!=null&&v.record.fragment().source().equals(a.id())&&v.record.b().equals(a.b())).findFirst().orElseThrow();var attached=AttachedPointTool.create(level,null,host,RoadSlicing.at(host.mesh,40).center());
    var command=new CompoundTag();command.putUUID("Main",host.record.id());command.putUUID("AttachedPoint",attached.id());command.putLong("Branch",ma.asLong());command.putBoolean("MainFirst",true);command.put("Fallback",RoadRecord.writeSettings(settings()));before=data.save(new CompoundTag());reject(h,()->OneSidedJunctions.build(level,null,command),"路口点");h.assertTrue(before.equals(data.save(new CompoundTag())),"forged same-source side build mutated RoadData");
    System.out.println("SR201 POINT PASS two markers and direct server same-source rejection");h.succeed();
  }
  @GameTest(template="empty",templateNamespace="splineroads_201",timeoutTicks=24000)
  public static void attachedAtomicLifecycleHistory(GameTestHelper h){
    var level=h.getLevel();var data=RoadData.get(level);var a=road(level,22100.5,22100.5);var host=road(level,22300.5,22100.5);var marker=marker(level,a,160);var point=AttachedPointTool.create(level,null,data.index.roads.get(host.id()),RoadSlicing.at(host.mesh(),160).center());
    var command=OneSidedJunctions.create(level,null,host.id(),point.id(),marker,true,settings());h.assertTrue(command.getBoolean("MixedMarker"),"mixed selection not routed");var before=data.save(new CompoundTag());var blocker=BlockPos.containing(22190.5,-59,22260.5);level.setBlock(blocker,Blocks.CHEST.defaultBlockState(),3);
    boolean denied=false;try{OneSidedJunctions.build(level,null,JunctionCodec.editCommand(command));}catch(IllegalArgumentException ex){denied=true;System.out.println("SR201 EXPECTED blocked composite: "+ex.getMessage());}h.assertTrue(denied,"composite overwrote block entity");h.assertTrue(before.equals(data.save(new CompoundTag())),"failed composite changed metadata");restored(h,marker);level.removeBlock(blocker,false);
    var fake=net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(level);var history=new ManualPlannerHistory();try(var scope=history.begin(fake)){OneSidedJunctions.build(level,null,JunctionCodec.editCommand(command));scope.finish();}
    var group=data.junctions.entrySet().stream().filter(e->e.getValue().getBoolean("MixedMarker")&&e.getValue().getLong("Marker")==marker.asLong()).map(Map.Entry::getKey).findFirst().orElseThrow();var saved=data.junctions.get(group);
    h.assertTrue(AutoJunctions.center(data,marker)!=null,"mixed marker did not build full junction");h.assertTrue(SideHosts.host(data.index.roads.get(host.id()).record),"mixed attachment did not retain one-sided host");h.assertTrue(!(level.getBlockEntity(BlockPos.of(saved.getLong("Branch"))) instanceof NodeEntity),"internal seam leaked player endpoint");h.assertTrue(history.undoCount()==1,"composite is not one history action");
    history.apply(fake,true);restored(h,marker);h.assertTrue(!data.junctions.containsKey(group)&&!SideHosts.host(data.index.roads.get(host.id()).record),"undo did not restore both sides");history.apply(fake,false);h.assertTrue(data.junctions.containsKey(group)&&AutoJunctions.center(data,marker)!=null,"redo did not restore both sides");
    var edit=SideRoads.payload(OneSidedJunctions.payload(level,null,group));OneSidedJunctions.build(level,null,JunctionCodec.editCommand(OneSidedJunctions.payload(level,null,group)));h.assertTrue(data.junctions.containsKey(group),"junction settings save lost mixed receipt");
    var reloaded=RoadData.load(data.save(new CompoundTag()));h.assertTrue(reloaded.junctions.get(group).getBoolean("MixedMarker")&&reloaded.streets.containsKey(saved.getUUID("MixedLink")),"save/reload lost mixed ownership");
    data.remove(level,null,saved.getUUID("MixedLink"));restored(h,marker);h.assertTrue(!data.junctions.containsKey(group)&&!SideHosts.host(data.index.roads.get(host.id()).record),"delete half-link did not restore attached host");h.assertTrue(data.index.roads.get(host.id()).record.settings().options().attachments().points().stream().anyMatch(p->p.id().equals(point.id())),"restoration deleted attached point");
    System.out.println("SR201 POINT PASS mixed atomic failure/create/edit/undo/redo/delete/restore/reload");h.succeed();
  }
}
