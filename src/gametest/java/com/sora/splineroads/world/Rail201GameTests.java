package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.*;

@GameTestHolder("splineroads_201_rail") @PrefixGameTestTemplate(false)
public final class Rail201GameTests {
  private static final UUID OWNER=new UUID(126,100);
  private static Settings street(){return new Settings(Mode.STRAIGHT,Style.O4_YELLOW,18,1,.4,90).structure(Structure.AUTO)
      .options(RoadProfile.Options.DEFAULT.infrastructure(RoadInfrastructure.Config.DEFAULT.gantry(RoadInfrastructure.Gantry.OFF)));}
  private static RoadRecord road(UUID id,V a,V b){var na=new Node(a,RoadPlanner.yaw(b.sub(a)),0);var nb=new Node(b,na.yaw(),0);return new RoadRecord(id,OWNER,InterchangeJunctions.pos(a),InterchangeJunctions.pos(b),na,nb,street(),false,4);}
  private static BlockPos marker(net.minecraft.server.level.ServerLevel level,V p){var pos=InterchangeJunctions.pos(p);level.getChunkAt(pos);level.setBlock(pos,SplineRoads.NODE.get().defaultBlockState(),2);var marker=(NodeEntity)level.getBlockEntity(pos);marker.owner=OWNER;marker.heightExplicit=true;marker.apply(new Node(p,0,0));return pos;}
  @GameTest(template="empty",templateNamespace="splineroads_201_rail",timeoutTicks=2000)
  public static void openCutMergeHasTravelSpaceAndSolidNose(GameTestHelper h){
    UUID group=new UUID(126,400);var host=road(new UUID(126,401),new V(0,64,0),new V(0,64,140));
    var mesh=host.mesh();host=host.alignment(group,mesh);
    var samples=new ArrayList<Sample>();
    for(int i=0;i<=120;i++){double grow=Settings.smooth(Math.min(1,i/30.)),peel=Settings.smooth(Math.max(0,Math.min(1,(i-65)/45.)));double x=9.05+2.25*grow+4*peel;double dx=i>65&&i<110?4*6*((i-65)/45.)*(1-(i-65)/45.)/45:0;
      samples.add(new Sample(new V(x,64,i+10),new V(-1,0,dx).horizontalUnit(),0,.25+2.25*grow));}
    var rampMesh=RoadRibbon.mesh(samples,new Settings(Mode.CURVE,Style.R1,5,1,.4,90));
    var ramp=new RoadRecord(new UUID(126,402),OWNER,InterchangeJunctions.pos(rampMesh.first().center()),InterchangeJunctions.pos(rampMesh.last().center()),RoadRibbon.start(rampMesh),RoadRibbon.end(rampMesh),rampMesh.settings(),false,4).alignment(group,rampMesh);
    var ground=new RoadStructures.Ground(){public double top(double x,double z,double y){return Math.min(67,y);}public double surface(double x,double z,double y){return 67;}public boolean joined(V p){return false;}public boolean blocked(RoadStructures.Part p){return false;}};
    host=host.structures(RoadAutoTunnels.enclose(mesh,ground,List.of()));ramp=ramp.structures(RoadAutoTunnels.enclose(rampMesh,ground,List.of()));
    var a=new RoadIndex.Built(host,true);var b=new RoadIndex.Built(ramp,true);var context=List.of(a,b);
    var hostParts=RoadInteractions.openPortal(a,host.structures(),List.of(b),context);var rampParts=RoadInteractions.openPortal(b,ramp.structures(),List.of(a),context);
    for(int d=40;d<=65;d+=5){
      var probe=new RoadStructures.Part(new V(8.9,64.1,d),new V(9.15,64.1,d),.2,3,false,RoadStructures.Material.CONCRETE);
      for(var parts:List.of(hostParts,rampParts))h.assertTrue(RoadShellOpening.solids(parts.stream().filter(p->p.material()==RoadStructures.Material.TUNNEL).toList()).retain(probe).isEmpty(),"wall blocks shared lane at "+d);
    }
    h.assertTrue(hostParts.stream().anyMatch(p->p.model().equals("sr:tunnel_nose")),"open cut lacks a blunt retained divider nose");
    var sign=ForkSignPlacement.find(rampMesh,mesh,hostParts,1,false);h.assertTrue(sign!=null,"open-cut divider cannot carry exit sign");
    h.assertTrue(RoadQueries.horizontal(rampMesh,sign.foot()).sample().distance()>65,"sign precedes physical fork");
    for(boolean distant:new boolean[]{false,true})for(var face:RoadRenderMesh.structureFaces(hostParts,distant))h.assertTrue(face.points().size()>=3&&Double.isFinite(RoadLighting.normal(face).x()),"invalid cut-wall render face");
    System.out.println("SR201 OPEN CUT RAIL CONTROL PASS full-height shared travel, solid open-cut nose and seated sign");h.succeed();
  }
}
