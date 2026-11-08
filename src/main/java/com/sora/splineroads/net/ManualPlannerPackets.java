package com.sora.splineroads.net;

import java.util.*;
import net.minecraft.nbt.*;

/** Map snapshots are application pages as well as transport fragments. Fragmenting
 * a single huge NBT does not reduce its decoded allocation budget. */
public final class ManualPlannerPackets {
  private static final int PAGE_WEIGHT=1024*1024, PAGE_ITEMS=32;

  public static List<CompoundTag> pages(UUID session,UUID snapshot,ListTag roads,ListTag nodes) {
    var result=new ArrayList<CompoundTag>();
    var roadPage=new ListTag();var nodePage=new ListTag();long weight=0;
    for(boolean road:new boolean[]{true,false})for(Tag entry:road?roads:nodes) {
      long next=Math.max(0,entry.sizeInBytes())+128L;
      if(!roadPage.isEmpty()||!nodePage.isEmpty()) {
        if(weight+next>PAGE_WEIGHT||roadPage.size()+nodePage.size()>=PAGE_ITEMS) {
          result.add(page(session,snapshot,roadPage,nodePage));roadPage=new ListTag();nodePage=new ListTag();weight=0;
        }
      }
      (road?roadPage:nodePage).add(entry);weight+=next;
    }
    if(!roadPage.isEmpty()||!nodePage.isEmpty()||result.isEmpty())result.add(page(session,snapshot,roadPage,nodePage));
    for(int i=0;i<result.size();i++){result.get(i).putInt("Page",i);result.get(i).putInt("Pages",result.size());}
    return result;
  }

  private static CompoundTag page(UUID session,UUID snapshot,ListTag roads,ListTag nodes) {
    var tag=new CompoundTag();tag.putString("Kind","manualMapPage");tag.putUUID("PlannerSession",session);tag.putUUID("MapSnapshot",snapshot);
    tag.put("MapRoads",roads);tag.put("Nodes",nodes);return tag;
  }
  private ManualPlannerPackets(){}
}
