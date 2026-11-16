package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

/** Fresh-world fixtures used only by the opt-in native geometry runner. */
public final class Geometry201Scenes {
  public record Curve(UUID id,Mesh before,double ground){}
  public static Curve curve(ServerPlayer player){
    var level=player.serverLevel();level.getChunkAt(BlockPos.ZERO);
    double ground=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,0,0),y=ground+.125;
    var a=Curve201GameTests.marker(level,new Node(new V(.5,y,.5),0,0));
    var b=Curve201GameTests.marker(level,new Node(new V(.5,y,300.5),0,0));
    ((NodeEntity)level.getBlockEntity(a)).owner=player.getUUID();((NodeEntity)level.getBlockEntity(b)).owner=player.getUUID();
    var settings=RoadLanes.configure(new Settings(Mode.CURVE,Style.O2_YELLOW,9,1,.35,90).structure(Structure.GROUND),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(1,1),4);
    settings=settings.options(settings.options().extras(false,false,false).outerRail(RoadProfile.OuterRail.OFF).route(settings.options().routing().fit(false).controls(List.of(new RoadProfile.Control(UUID.randomUUID(),new V(45.5,y,100.5)),new RoadProfile.Control(UUID.randomUUID(),new V(45.5,y,200.5)))).through(false)));
    var whole=RoadData.get(level).connect(level,player,a,b,settings,null);
    var split=RoadSplitter.plan(level,player,whole,whole.mesh().length()*.3,RoadSlicing.Connection.CONTINUOUS);RoadSplitter.commit(level,player,split);
    var road=RoadData.get(level).index.roads.get(whole.id()).record;var before=road.rawMesh();var fragment=road.fragment();
    var legacy=road.settings(road.settings().options(road.settings().options().route(settings.options().routing()))).fragment(new RoadSlicing.Fragment(fragment.source(),fragment.origin(),fragment.total(),fragment.connection(),fragment.applied(),fragment.geometry(),fragment.supports()));
    var tag=legacy.save();tag.getCompound("Slice").putInt("Version",1);tag.getCompound("Slice").remove("CurveAnchors");legacy=RoadRecord.load(tag);RoadData.get(level).index.put(new RoadIndex.Built(legacy));
    com.sora.splineroads.net.RoadNetwork.road(player,legacy);
    return new Curve(road.id(),before,ground);
  }
  public static void unchanged(ServerPlayer player,Curve scene){
    var road=RoadData.get(player.serverLevel()).index.roads.get(scene.id()).record;
    if(road.fragment().curveAnchors().isEmpty())throw new AssertionError("native save did not migrate legacy controls");
    Curve201GameTests.same(scene.before(),road.rawMesh());
  }
  public static double changed(ServerPlayer player,Curve scene,double expectedX){
    var road=RoadData.get(player.serverLevel()).index.roads.get(scene.id()).record;
    if(road.fragment().curveAnchors().isEmpty())throw new AssertionError("changed native save discarded local anchors");
    if(Math.abs(road.settings().options().routing().controls().get(0).position().x()-expectedX)>1e-8)throw new AssertionError("actual server did not save edited control X");
    var now=road.rawMesh();if(now.first().center().distance(scene.before().first().center())>1e-6||now.last().center().distance(scene.before().last().center())>1e-6)throw new AssertionError("control edit moved shared curve endpoints");
    double delta=0;for(var point:scene.before().samples())delta=Math.max(delta,point.center().distance(RoadSlicing.at(now,point.distance()/scene.before().length()*now.length()).center()));
    if(delta<1e-5)throw new AssertionError("edited control was silently ignored");return delta;
  }
  private Geometry201Scenes(){}
}
