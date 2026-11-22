package com.sora.splineroads.world;

import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;

/** Regressions using the exact submitted road records; never writes the save. */
public final class Feedback212Core {
  static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  public static void main(String[] args)throws Exception{
    var root=NbtIo.readCompressed(Path.of(args[0]).toFile()).getCompound("data");
    var roads=new LinkedHashMap<UUID,RoadIndex.Built>();
    for(var t:root.getList("Roads",Tag.TAG_COMPOUND)){var r=new RoadIndex.Built(RoadRecord.load((CompoundTag)t));roads.put(r.record.id(),r);}
    var host=roads.get(UUID.fromString("a27d2ab0-7901-3017-9314-277a6afa8f56"));
    var spec=host.record.junction().spec();var arms=new ArrayList<>(spec.arms());
    for(int i=0;i<3;i++){
      var a=arms.get(i);Mesh mesh=i<2?spec.oneSide().road():roads.get(UUID.fromString("0fe15b71-e6cc-44f5-b694-1e9fcd35910b")).mesh;
      Sample at=i<2?RoadSlicing.at(mesh,i==0?spec.oneSide().from():spec.oneSide().to()):mesh.last();
      var settings=OneSidedJunctions.withPort(a.external(),mesh,at,i==1);
      arms.set(i,new JunctionSpec.Arm(a.endpoint(),a.inward(),settings,a.incoming(),a.outgoing(),a.width(),a.median(),a.medianKind(),a.cycleWidth(),a.curbWidth(),a.crosswalk(),a.crossingWidth(),a.crossingSetback(),a.stopGap(),a.phase(),a.lanes(),a.attached(),a.advanceSign(),a.nameSign(),a.rightSlip()));
    }
    spec=spec.arms(arms);var rebuilt=JunctionPlanner.plan(spec);
    int seams=0;
    for(int i=0;i<3;i++){
      var approach=rebuilt.pieces().get(i).mesh();var a=spec.arms().get(i);
      var outside=i<2?spec.oneSide().road():roads.get(UUID.fromString("0fe15b71-e6cc-44f5-b694-1e9fcd35910b")).mesh;
      var port=i<2?RoadSlicing.at(outside,i==0?spec.oneSide().from():spec.oneSide().to()):outside.last();
      for(int side:new int[]{-1,1}){
        int originalSide=i==1?-side:side;
        var layout=RoadProfile.layout(outside,port);
        if(!layout.catalog().twoWay()&&layout.outside()!=originalSide)continue;
        double expected=RoadSurface.edgeOffset(outside,port,originalSide)*(i==1?-1:1);
        double actual=JunctionPlanner.approachEdge(a,approach,approach.first(),side);
        check(Math.abs(expected-actual)<1e-6,"bicycle edge seam arm="+i+" side="+side+" gap="+Math.abs(expected-actual));seams++;
      }
    }
    double oldDrift=0,newDrift=0;
    for(int arm=0;arm<2;arm++){
      var old=new JunctionPlanner.Ref(spec,arm,56).get().mesh();var next=rebuilt.pieces().get(arm).mesh();
      for(var p:old.samples())oldDrift=Math.max(oldDrift,RoadQueries.horizontal(spec.oneSide().road(),p.center()).horizontalDistance());
      for(var p:next.samples())newDrift=Math.max(newDrift,RoadQueries.horizontal(spec.oneSide().road(),p.center()).horizontalDistance());
    }
    check(oldDrift>.1,"control must reproduce curved host drift");check(newDrift<1e-6,"host curve drift "+newDrift);
    check(spec.arms().get(2).advanceSign(),"legacy branch advance sign default");
    var disabled=new ArrayList<>(spec.arms());disabled.set(2,disabled.get(2).signs(false,true));
    check(!JunctionCodec.read(JunctionCodec.write(spec.arms(disabled))).arms().get(2).advanceSign(),"explicit sign preference must survive");
    check(seams>0,"edge test must inspect actual ports");
    System.out.println("FIX1_CORE curve old="+oldDrift+" new="+newDrift+" pieces="+rebuilt.pieces().size()+" signs=PASS seams="+seams);
    var cycle=roads.values().stream().filter(r->RoadsideBypass.eligible(r.mesh)).min(Comparator.comparingDouble(r->RoadQueries.horizontal(r.mesh,new V(-6,56,1412)).horizontalDistance())).orElseThrow();
    System.out.println("FIX1_CORE actual crossing road="+cycle.record.id()+" style="+cycle.mesh.settings().style()+" saved="+cycle.mesh.settings().options().streetscape().crossings());
    var neighbors=roads.values().stream().filter(r->r!=cycle&&RoadIndex.overlapXZ(cycle.mesh,r.mesh,4)).map(r->new RoadsideBypass.Neighbor(r.mesh,RoadAutoTunnels.roofRegions(r.record.structures()))).toList();
    try{System.out.println("FIX1_CORE crossing="+RoadsideBypass.plan(cycle.mesh,neighbors));}catch(Exception e){System.out.println("FIX1_CORE crossing rejection="+e);}
    System.out.println("FIX1_CORE interchange group="+cycle.record.assembly());
    for(var entry:root.getList("Interchanges",Tag.TAG_COMPOUND)){
      var t=(CompoundTag)entry;if(!t.hasUUID("Id")||!t.getUUID("Id").equals(cycle.record.assembly()))continue;
      var plan=Interchanges.plan(t);int merges=0;
      var mains=plan.legs().stream().filter(l->RoadsideBypass.eligible(l.mesh())).map(l->l.mesh()).toList();
      for(var leg:plan.legs())if(leg.mesh().settings().style().ramp())for(var p:List.of(leg.mesh().first(),leg.mesh().last())){
        // Full-width endpoints join another ramp after the shared lead split.
        // The tapered half-metre mouths are the actual main-carriageway joins.
        if(p.halfWidth()>1)continue;
        for(var main:mains){var q=RoadQueries.horizontal(main,p.center());if(q.horizontalDistance()>main.settings().width()||Math.abs(q.sample().center().y()-p.center().y())>1)continue;
          var layout=RoadProfile.layout(main,q.sample());double edge=Math.max(Math.abs(layout.motorMin()),Math.abs(layout.motorMax()));
          System.out.println("FIX1_JOIN "+leg.name()+" endpoint="+p.center()+" lateral="+q.horizontalDistance()+" width="+p.halfWidth()*2+" motor="+edge+" full="+q.sample().halfWidth());
          check(q.horizontalDistance()-p.halfWidth()<=edge+.05,"ramp disconnected from motor edge "+leg.name());merges++;
        }
      }
      check(merges>0,"interchange regression must inspect actual merge mouths");System.out.println("FIX1_CORE interchange motor joins="+merges);
    }
    System.out.println("FIX1_CORE_PASS loaded="+roads.size());
  }
}
