package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.*;
import java.util.*;

@net.minecraftforge.gametest.GameTestHolder("splineroads_204_bridge")
@net.minecraftforge.gametest.PrefixGameTestTemplate(false)
public class Bridge204GameTests {
  private static boolean below(Part p,double bottom){return p.base().stream().anyMatch(v->v.y()<bottom-1e-6);}
  private static long underside(RoadRecord r){return r.structures().stream().filter(p->p.material()==Material.CONCRETE&&below(p,r.start().position().y()-r.settings().thickness())).count();}
  private static void emptyUnderside(GameTestHelper h,RoadRecord r){
    h.assertTrue(underside(r)==0,"disabled span retained a concrete girder, crossbeam or foundation");
    double y=r.start().position().y();
    h.assertTrue(r.structures().stream().anyMatch(p->p.material()==Material.CONCRETE&&!p.pier()&&p.base().stream().allMatch(v->Math.abs(v.y()-(y-r.settings().thickness()))<1e-6)),"edge slab lost");
    var kind=r.settings().options().infrastructure().bridge();
    if(kind==RoadInfrastructure.Bridge.CABLE||kind==RoadInfrastructure.Bridge.SUSPENSION)
      h.assertTrue(r.structures().stream().anyMatch(p->p.material()==Material.CONCRETE&&p.pier()&&p.top().stream().anyMatch(v->v.y()>y+8)),"upper towers lost");
  }
  @GameTest(template="empty",templateNamespace="splineroads_203",timeoutTicks=16000)
  public static void spanEditRemovesAndRestoresActualParts(GameTestHelper h){
    var l=h.getLevel();var data=RoadData.get(l);int index=0;
    for(var kind:List.of(RoadInfrastructure.Bridge.TEMPLATE,RoadInfrastructure.Bridge.CABLE,RoadInfrastructure.Bridge.SUSPENSION)){
      int x=830000+index++*300;l.getChunkAt(new BlockPos(x,0,0));
      double y=l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,0)+24;
      var a=Curve201GameTests.marker(l,new Node(new V(x+.5,y,.5),0,0));
      var b=Curve201GameTests.marker(l,new Node(new V(x+.5,y,128.5),0,0));
      var s=Structures203GameTests.settings().structure(Structure.BRIDGE);
      s=s.options(s.options().infrastructure(s.options().infrastructure().bridge(kind).autoSpan(true).supportsEnabled(true)));
      var r=data.connect(l,null,a,b,s,null);h.assertTrue(underside(r)>0,"enabled span positive control has no underside");
      var off=s.options(s.options().infrastructure(s.options().infrastructure().supportsEnabled(false)));
      data.connect(l,null,a,b,off,r.id(),false);r=Curve201GameTests.current(l,r.id());emptyUnderside(h,r);
      var saved=data.save(new CompoundTag());var restored=RoadData.load(saved).index.roads.get(r.id()).record;
      emptyUnderside(h,restored);h.assertTrue(!restored.settings().options().infrastructure().supportsEnabled(),"off choice lost after load");
      h.assertTrue(restored.structures().equals(r.structures()),"serialized bridge parts changed");
      data.connect(l,null,a,b,s,r.id(),false);r=Curve201GameTests.current(l,r.id());
      h.assertTrue(underside(r)>0,"re-enabled span failed to restore underside");
      data.connect(l,null,a,b,off,r.id(),false);emptyUnderside(h,Curve201GameTests.current(l,r.id()));
    }
    h.assertTrue(RoadWorkChunks.heldCount(l)==0,"span edits leaked chunk tickets");h.succeed();
  }
}
