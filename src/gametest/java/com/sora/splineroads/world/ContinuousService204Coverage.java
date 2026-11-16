package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.InterchangePlanner.Plan;
import com.sora.splineroads.core.InterchangePlanner.Preset;
import java.util.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;

/** Check all original outside intervals immediately after a service-layout transaction. */
public final class ContinuousService204Coverage {
  public static void assertPreserved(GameTestHelper h,RoadData data,CompoundTag before,CompoundTag saved,Plan preview){
    var options=Interchanges.read(saved.getCompound("Options"));
    if(options.preset()!=Preset.DIAMOND&&options.preset()!=Preset.SPUI)return;
    int axis=options.junctionAxis();var origin=preview.anchors().get(axis*2).position();var end=preview.anchors().get(axis*2+1).position();var direction=end.sub(origin).horizontalUnit();double length=end.sub(origin).dot(direction);int intervals=0;
    var current=saved.getList("ContinuousSources",Tag.TAG_COMPOUND).stream().map(v->(CompoundTag)v)
        .filter(q->q.getInt("Axis")==axis&&q.getBoolean("Detached")).toList();
    for(var source:ContinuousRoads.sources(before)){
      if(source.axis()!=axis)continue;var old=source.road();double a=old.start().position().sub(origin).dot(direction),b=old.end().position().sub(origin).dot(direction);
      var actual=new ArrayList<double[]>();
      for(var q:current){var id=q.getCompound("Road").getUUID("Id");var built=data.index.roads.get(id);
        h.assertTrue(built!=null,"saved service outside donor missing immediately after commit: "+id);
        var road=built.record;h.assertTrue(road.assembly()==null&&road.junction()==null,"service outside donor was absorbed into a junction/group: "+id);
        if(!road.owner().equals(old.owner())||!RoadContinuity.eligible(road.caps(0).mesh()))continue;
        var first=road.start().position();var last=road.end().position();
        if(Math.abs(first.sub(origin).dot(direction.left()))>1e-5||Math.abs(last.sub(origin).dot(direction.left()))>1e-5
            ||Math.abs(first.y()-old.start().position().y())>1e-6||Math.abs(last.y()-old.end().position().y())>1e-6)continue;
        double lo=first.sub(origin).dot(direction),hi=last.sub(origin).dot(direction);actual.add(new double[]{Math.min(lo,hi),Math.max(lo,hi)});
      }
      actual.sort(Comparator.comparingDouble(v->v[0]));
      for(var outside:List.of(new double[]{Math.min(a,b),Math.min(0,Math.max(a,b))},new double[]{Math.max(length,Math.min(a,b)),Math.max(a,b)})){
        if(outside[1]-outside[0]<=1e-5)continue;double covered=outside[0];
        for(var span:actual){if(span[0]>covered+1e-5)break;if(span[1]>=covered)covered=Math.max(covered,span[1]);if(covered>=outside[1]-1e-5)break;}
        h.assertTrue(covered>=outside[1]-1e-5,"service layout lost original outside interval axis="+axis+" source="+old.id()+" expected="+Arrays.toString(outside)+" coveredUntil="+covered);
        intervals++;
      }
    }
    h.assertTrue(intervals>0&&!current.isEmpty(),"service fixture did not exercise outside continuations");
    h.assertTrue(saved.contains(ContinuousService.RECEIPT),"service layout omitted verified logical-source receipt");
    System.out.println("SR204 SERVICE OUTSIDE COVERAGE PASS preset="+options.preset()+" axis="+axis+" exactIntervals="+intervals+" recordedOrdinaryDonors="+current.size());
  }
  private ContinuousService204Coverage(){}
}
