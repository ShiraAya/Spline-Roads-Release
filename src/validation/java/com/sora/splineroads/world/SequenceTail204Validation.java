package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.nbt.*;

/**
 * Read-only replay of the actual first-CLOVERLEAF snapshot: 33 reclaim/guard
 * checks and 24 provenance checks. The sole argument is a compressed NBT
 * fixture containing Saved (the interchange descriptor) and Roads (live records).
 * No audit helper, server, client, original world directory, or output file is used.
 */
public final class SequenceTail204Validation {
  private static final String PROTECTED="原立交外侧余段已修改、删除或不再连续，不能自动占用";
  private final CompoundTag saved;
  private final Map<UUID,RoadRecord> originals;
  private final UUID group,right;
  private int checks;

  private SequenceTail204Validation(CompoundTag fixture){
    if(!fixture.contains("Saved",Tag.TAG_COMPOUND)||!fixture.contains("Roads",Tag.TAG_LIST))
      throw new IllegalArgumentException("Fixture must contain Saved and Roads");
    saved=fixture.getCompound("Saved").copy();
    if(!saved.hasUUID("Id")||!saved.hasUUID("Owner")
        ||Interchanges.read(saved.getCompound("Options")).preset()!=Preset.CLOVERLEAF
        ||saved.getList("Nodes",Tag.TAG_COMPOUND).size()!=4)
      throw new IllegalArgumentException("Expected an actual saved four-way CLOVERLEAF fixture");
    group=saved.getUUID("Id");originals=new LinkedHashMap<>();
    for(Tag value:fixture.getList("Roads",Tag.TAG_COMPOUND)){
      var road=RoadRecord.load((CompoundTag)value);
      if(originals.put(road.id(),road)!=null)throw new IllegalArgumentException("Duplicate fixture road "+road.id());
    }
    var positive=RoadRecord.readNode(saved.getList("Nodes",Tag.TAG_COMPOUND).getCompound(1)).position();
    var matches=new ArrayList<UUID>();
    for(Tag value:saved.getList("ContinuousSources",Tag.TAG_COMPOUND)){
      var q=(CompoundTag)value;if(q.getInt("Axis")!=0||!q.getBoolean("Detached"))continue;
      var road=RoadRecord.load(q.getCompound("Road"));
      if(road.start().position().distance(positive)<1e-9||road.end().position().distance(positive)<1e-9)matches.add(road.id());
    }
    if(matches.size()!=1||!originals.containsKey(matches.get(0)))
      throw new IllegalArgumentException("Fixture needs one live detached tail at the positive first-axis seam");
    right=matches.get(0);
  }

  private void ok(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
  private RoadData data(Map<UUID,RoadRecord> roads){
    var result=new RoadData();for(var road:roads.values())result.index.put(RoadIndex.Built.loading(road));
    result.interchanges.put(group,saved.copy());return result;
  }
  private CompoundTag input(RoadData data){
    var tag=saved.copy();ContinuousReclaim.refresh(data,tag);
    tag.put("Options",Interchanges.write(new Options(Preset.TURBINE,false,1,96,20,5,1,5,true,false,0,1,5,true,false,0)));
    return tag;
  }
  private static CompoundTag command(CompoundTag source,List<Node> anchors){
    var result=source.copy();var fitted=new ListTag();anchors.forEach(n->fitted.add(RoadRecord.writeNode(n)));
    result.put("SourceNodes",source.getList("Nodes",Tag.TAG_COMPOUND).copy());
    result.putInt("SourceAxesHash",HostAxes.signature(source));result.put("FittedNodes",fitted);return result;
  }
  private static boolean guarded(CompoundTag tag){
    try(var budget=RoadPlanningBudget.open("protected detached tail replay",120)){
      try{Interchanges.plan(tag);return false;}
      catch(IllegalArgumentException failure){
        if(failure.getMessage()!=null&&failure.getMessage().contains(PROTECTED))return true;
        throw failure;
      }
    }
  }
  private int externalCollisions(Plan plan){
    var removed=new HashSet<UUID>();
    for(var road:originals.values())if(group.equals(road.assembly()))removed.add(road.id());
    for(var leg:plan.legs()){var id=ContinuousRoads.sourceId(leg.name());if(id!=null)removed.add(id);}
    int collisions=0;
    for(var leg:plan.legs())for(var old:originals.values())
      if(!removed.contains(old.id())&&RoadIndex.overlapXZ(leg.mesh(),old.mesh(),1)){
        try{Interchanges.checkExternal(leg.mesh(),old.mesh());}
        catch(IllegalArgumentException failure){collisions++;System.out.println("EXTERNAL_COLLISION proposed="+leg.name()+" old="+old.id()+" error="+failure.getMessage());}
      }
    System.out.println("EXTERNAL collisions="+collisions+" removed="+removed.size());return collisions;
  }
  private void protectedCase(String name,RoadRecord changed,boolean missing){
    var roads=new LinkedHashMap<>(originals);if(missing)roads.remove(right);else roads.put(right,changed);
    var data=data(roads);var before=data.save(new CompoundTag());var tag=input(data);
    ok(!ContinuousReclaim.eligible(tag,right),"protected donor eligible: "+name);
    ok(guarded(tag),"protected donor reused: "+name);
    ok(before.equals(data.save(new CompoundTag())),"planning mutated data: "+name);
    System.out.println("PROTECTED "+name+" no writes");
  }
  private void reclaimAndGuards(){
    var data=data(originals);var before=data.save(new CompoundTag());var tag=input(data);int eligible=0;
    for(Tag value:tag.getList("ContinuousSources",Tag.TAG_COMPOUND)){
      var q=(CompoundTag)value;if(q.getBoolean("Detached")){
        ok(ContinuousReclaim.eligible(tag,q.getCompound("Road").getUUID("Id")),"unchanged actual detached tail rejected");eligible++;
      }
    }
    ok(eligible==4,"actual fixture missing four tails");Plan plan;
    try(var budget=RoadPlanningBudget.open("actual reclaim positive",120)){plan=Interchanges.plan(tag);}
    ok(plan.legs().stream().anyMatch(l->right.equals(ContinuousRoads.sourceId(l.name()))),"actual positive-side donor not in proposed records");
    ok(externalCollisions(plan)==0,"actual old-tail collision survived");
    ok(before.equals(data.save(new CompoundTag())),"positive planning wrote state");
    ok(Interchanges.confirmedPlan(tag,command(tag,plan.anchors())).anchors().equals(plan.anchors()),"reclaimed confirmed endpoints differ");
    System.out.println("POSITIVE actual legacy snapshot reclaimed=4 anchors="+plan.anchors());
    var road=originals.get(right);
    protectedCase("manual paint",road.settings(road.settings().options(road.settings().options().hideArrows(!road.settings().options().hideArrows()))),false);
    var foreign=road.save();foreign.putUUID("Owner",new UUID(999,999));protectedCase("foreign owner",RoadRecord.load(foreign),false);
    var occupied=road.save();occupied.putUUID("Assembly",new UUID(888,888));protectedCase("foreign assembly",RoadRecord.load(occupied),false);
    protectedCase("deleted source",null,true);
    var disconnected=road.save();disconnected.putLong("A",road.a().offset(0,0,8).asLong());protectedCase("disconnected endpoint identity",RoadRecord.load(disconnected),false);
    var rail=road.settings().options().outerRail()==RoadProfile.OuterRail.OFF?RoadProfile.OuterRail.ON:RoadProfile.OuterRail.OFF;
    protectedCase("changed authored profile",road.settings(road.settings().options(road.settings().options().outerRail(rail))),false);
    var a=new Node(road.start().position().add(new V(0,0,8)),road.start().yaw(),road.start().grade());
    var b=new Node(road.end().position().add(new V(0,0,8)),road.end().yaw(),road.end().grade());
    protectedCase("moved parallel geometry",new RoadRecord(road.id(),road.owner(),road.a().offset(0,0,8),road.b().offset(0,0,8),a,b,road.settings(),road.automatic(),road.clearance()),false);
    // Spatial coincidence cannot authorize an identity absent from the saved group.
    var stranger=road.save();var strangerId=new UUID(9999,9999);stranger.putUUID("Id",strangerId);
    if(originals.containsKey(strangerId))throw new IllegalArgumentException("Fixture already uses negative-control identity");
    var roads=new LinkedHashMap<>(originals);roads.put(strangerId,RoadRecord.load(stranger));var foreignInput=input(data(roads));
    ok(!ContinuousReclaim.eligible(foreignInput,strangerId),"unrecorded identical road absorbed");
    ok(!ContinuousReclaim.eligible(foreignInput,right),"branched seam donor absorbed");
    ok(guarded(foreignInput),"new branch did not block protected span");
    if(checks!=33)throw new AssertionError("Reclaim/guard coverage changed: "+checks+" != 33");
    System.out.println("PASS reclaimAndGuards checks=33");
  }
  private void provenance(){
    int startChecks=checks;var data=data(originals);var refreshed=saved.copy();ContinuousReclaim.refresh(data,refreshed);
    var tails=new LinkedHashMap<UUID,CompoundTag>();
    for(Tag value:refreshed.getList("ContinuousSources",Tag.TAG_COMPOUND)){
      var q=(CompoundTag)value;if(q.getBoolean("Detached"))tails.put(q.getCompound("Road").getUUID("Id"),q.copy());
    }
    var anchors=refreshed.getList("Nodes",Tag.TAG_COMPOUND).stream().map(v->RoadRecord.readNode((CompoundTag)v)).toList();
    var fixed=Interchanges.confirmedPlan(refreshed,command(refreshed,anchors));var next=refreshed.copy();ContinuousSections.updateSources(next,fixed);
    for(var entry:tails.entrySet()){
      var q=next.getList("ContinuousSources",Tag.TAG_COMPOUND).stream().map(v->(CompoundTag)v)
          .filter(v->v.getCompound("Road").getUUID("Id").equals(entry.getKey())).findFirst().orElseThrow();
      ok(q.getBoolean("Detached"),"unused tail became managed");
      ok(q.getCompound("Road").equals(entry.getValue().getCompound("Road")),"unused tail baseline overwritten");
    }
    for(Tag value:saved.getList("ContinuousSources",Tag.TAG_COMPOUND)){
      var q=((CompoundTag)value).copy();if(!q.getBoolean("Detached"))continue;
      var old=RoadRecord.load(q.getCompound("Road"));var live=data.index.roads.get(old.id()).record;
      ok(ContinuousReclaim.unchanged(q,old,live),"actual legacy baseline does not qualify");q.putBoolean("DetachedExact",true);
      ok(!ContinuousReclaim.unchanged(q,old,live),"exact marker still accepted legacy normalization");
      q.put("Road",live.header());ok(ContinuousReclaim.unchanged(q,live,live),"fresh exact baseline rejected itself");
      var changed=live.settings(live.settings().options(live.settings().options().hideArrows(!live.settings().options().hideArrows())));
      ok(!ContinuousReclaim.unchanged(q,live,changed),"exact baseline ignored paint edit");
    }
    if(checks-startChecks!=24)throw new AssertionError("Provenance coverage changed: "+(checks-startChecks)+" != 24");
    System.out.println("PASS provenance checks=24 untouched four tails preserved; exact baselines strict");
  }
  private static String sha(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
  public static void main(String[] args)throws Exception{
    if(args.length!=1)throw new IllegalArgumentException("Usage: SequenceTail204Validation <actual-cloverleaf-reduced.nbt>");
    Path fixture=Path.of(args[0]).toAbsolutePath().normalize();String before=sha(fixture);
    var validation=new SequenceTail204Validation(NbtIo.readCompressed(fixture.toFile()));
    System.out.println("FIXTURE path="+fixture+" sha256="+before+" group="+validation.group+" protectedTail="+validation.right);
    validation.reclaimAndGuards();validation.provenance();
    if(!before.equals(sha(fixture)))throw new AssertionError("Fixture bytes changed");
    if(validation.checks!=57)throw new AssertionError("Expected all 57 evidence checks");
    System.out.println("SequenceTail204Validation PASS checks=57 (33 reclaim/guards + 24 provenance); source SHA256 unchanged; core replay only, real world sequence remains required");
  }
}
