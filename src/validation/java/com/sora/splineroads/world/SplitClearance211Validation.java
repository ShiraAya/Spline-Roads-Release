package com.sora.splineroads.world;
import java.nio.file.*;import java.util.*;import net.minecraft.nbt.*;import com.sora.splineroads.core.*;
/** Real remote ramp mouth, plus new junction and newly exposed pavement controls. */
public final class SplitClearance211Validation {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception{
  System.setProperty("inspection.world",args[0]);var data=Followup211Inspection.load();
  var state=NbtIo.readCompressed(Path.of(args[1]).toFile());var original=data.index.roads.get(state.getCompound("Roads").getUUID("s1-south")).record;
  RoadSplitGeometry.Pair pair=null;
  for(int i=0;i<=80;i++)try{pair=RoadSplitGeometry.split(original,original.mesh().length()*.55+(i==0?0:(i%2==1?1:-1)*((i+1)/2)*.5),RoadSlicing.Connection.JUNCTION,UUID.randomUUID());break;}catch(IllegalArgumentException blocked){}
  check(pair!=null,"no captured cut station");
  var draft=AutoJunctions.plan(List.of(pair.first(),pair.second()),List.of(AutoJunctions.config(pair.pos(),original.owner())));
  int preserved=0;
  for(var r:draft.roads())if(r.junction()==null)for(var old:data.index.roads.values()){
   if(old.record.id().equals(original.id())||!RoadIndex.overlapXZ(r.mesh(),old.mesh,1))continue;
   if(RoadClearance.contacts(r.mesh(),old.mesh).stream().noneMatch(RoadClearance.Contact::blocked))continue;
   RoadSplitter.checkExternal(original,r,old.mesh);preserved++;
  }
  check(preserved>0,"must reproduce original remote ramp rejection");
  var junction=draft.roads().stream().filter(r->r.junction()!=null).findFirst().orElseThrow();
  boolean newJunctionBlocked=false;try{RoadSplitter.checkExternal(original,junction,junction.mesh());}catch(RoadClearance.Conflict expected){newJunctionBlocked=true;}
  check(newJunctionBlocked,"new junction pavement skipped clearance");
  var unrelated=data.index.roads.values().stream().map(b->b.record).filter(r->r.junction()==null&&r.mesh().min().x()>original.mesh().max().x()+100).findFirst().orElseThrow();
  boolean newPavementBlocked=false;try{RoadSplitter.checkExternal(original,unrelated,unrelated.mesh());}catch(RoadClearance.Conflict expected){newPavementBlocked=true;}
  check(newPavementBlocked,"pavement outside original road skipped clearance");
  System.out.println("SPLIT_CLEARANCE211 PASS preserved_remote_contacts="+preserved+" new_junction_rejected=true new_pavement_rejected=true");
 }
}
