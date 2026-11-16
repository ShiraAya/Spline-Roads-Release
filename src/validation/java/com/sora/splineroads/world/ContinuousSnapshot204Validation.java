package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;

/** Actual first-DIAMOND incidental normalization, with strict precommit snapshot guards. */
public final class ContinuousSnapshot204Validation {
  static int checks;
  static void check(boolean value,String text){if(!value)throw new AssertionError(text);checks++;System.out.println("PASS "+text);}
  static CompoundTag descriptor(CompoundTag source){var result=new CompoundTag();var list=new ListTag();list.add(source.copy());result.put("ContinuousSources",list);return result;}
  static void clearCaches(CompoundTag header){var decor=header.getCompound("Settings").getCompound("StartSection").getCompound("Streetscape");decor.remove("Raised");var sides=decor.getCompound("SideStructures");sides.remove("NegativeRaised");sides.remove("PositiveRaised");}
  static RoadRecord special(RoadRecord r,UUID owner,UUID assembly,JunctionPlanner.Ref junction,TollPlaza.Spec toll){return new RoadRecord(r.id(),owner,r.a(),r.b(),r.start(),r.end(),r.settings(),r.automatic(),r.clearance(),r.structures(),r.endCaps(),r.buildVersion(),assembly,r.alignment(),r.furniturePhase(),junction,toll,r.fragment());}
  static CompoundTag exactSource(RoadRecord r){var q=new CompoundTag();q.putInt("Axis",1);q.putBoolean("Detached",true);q.putBoolean("DetachedExact",true);q.put("Road",r.header());return q;}
  public static void main(String[]args)throws Exception{
    if(args.length!=1)throw new IllegalArgumentException("Usage: ContinuousSnapshot204Validation <actual-unused-normalization.nbt>");
    var fixture=NbtIo.readCompressed(Path.of(args[0]).toFile());var source=fixture.getCompound("Source");var before=RoadRecord.load(source.getCompound("Road"));var after=RoadRecord.load(fixture.getCompound("Actual"));
    check(source.getBoolean("Detached")&&source.getBoolean("DetachedExact")&&!source.getBoolean(ContinuousService.LOGICAL),"actual unused source is exact detached, not service logical");
    check(!before.header().equals(after.header()),"fixture retains actual first-save header mismatch");
    check(before.caps(0).mesh().samples().equals(after.caps(0).mesh().samples()),"actual complete capless geometry unchanged");
    check(before.start().equals(after.start())&&before.end().equals(after.end()),"actual authored endpoints unchanged");
    var x=before.header();var y=after.header();clearCaches(x);clearCaches(y);
    check(x.equals(y),"only three StartSection streetscape derived caches differ");
    var tag=descriptor(source);var savedTag=tag.copy();var data=new RoadData();data.index.put(new RoadIndex.Built(before));var savedData=data.save(new CompoundTag());
    var permit=ContinuousReclaim.snapshotCandidates(data,tag,before.owner());
    check(permit.equals(Set.of(before.id())),"exact current live baseline obtains one transaction snapshot permission");
    check(tag.equals(savedTag)&&data.save(new CompoundTag()).equals(savedData),"collecting permission performs no writes");
    check(!tag.getList("ContinuousSources",Tag.TAG_COMPOUND).getCompound(0).getBoolean("Reclaimable"),"snapshot permission does not authorize reclamation");
    check(ContinuousReclaim.snapshotCandidates(data,tag,UUID.randomUUID()).isEmpty(),"wrong owner receives no permission");
    data.index.remove(before.id());check(ContinuousReclaim.snapshotCandidates(data,tag,before.owner()).isEmpty(),"missing outside road receives no permission");
    data.index.put(new RoadIndex.Built(before.alignment(UUID.randomUUID(),before.mesh())));check(ContinuousReclaim.snapshotCandidates(data,tag,before.owner()).isEmpty(),"other assembly receives no permission");
    data.index.put(new RoadIndex.Built(before.settings(before.settings().options(before.settings().options().hideArrows(!before.settings().options().hideArrows())))));
    check(ContinuousReclaim.snapshotCandidates(data,tag,before.owner()).isEmpty(),"actual authored paint difference receives no permission");
    var foreign=special(before,UUID.randomUUID(),null,null,null);data.index.put(new RoadIndex.Built(foreign));
    check(ContinuousReclaim.snapshotCandidates(data,descriptor(exactSource(foreign)),before.owner()).isEmpty(),"foreign-owner exact header is still unauthorized");
    var assembled=special(before,before.owner(),UUID.randomUUID(),null,null);data.index.put(new RoadIndex.Built(assembled));
    check(ContinuousReclaim.snapshotCandidates(data,descriptor(exactSource(assembled)),before.owner()).isEmpty(),"exact assembled record is not an ordinary source");
    var child=fixture.getCompound("Saved").getCompound(ContinuousService.RECEIPT).getList("Junctions",Tag.TAG_COMPOUND).getCompound(0);
    var ref=new JunctionPlanner.Ref(JunctionCodec.read(child.getCompound("Spec")),0);
    var junction=special(before,before.owner(),null,ref,null);data.index.put(new RoadIndex.Built(junction));
    check(ContinuousReclaim.snapshotCandidates(data,descriptor(exactSource(junction)),before.owner()).isEmpty(),"exact junction record with null assembly cannot acquire snapshot permission");
    var toll=special(before,before.owner(),null,null,new TollPlaza.Spec(before.start(),before.end(),TollPlaza.Config.DEFAULT));data.index.put(new RoadIndex.Built(toll));
    check(ContinuousReclaim.snapshotCandidates(data,descriptor(exactSource(toll)),before.owner()).isEmpty(),"exact toll record with null assembly cannot acquire snapshot permission");
    data.index.put(new RoadIndex.Built(before));var active=exactSource(before);active.remove("Detached");
    check(ContinuousReclaim.snapshotCandidates(data,descriptor(active),before.owner()).isEmpty(),"active record is outside incidental detached snapshot scope");
    data.index.put(new RoadIndex.Built(after));
    check(ContinuousReclaim.snapshotCandidates(data,tag,before.owner()).isEmpty(),"even derived difference cannot be adopted without precommit equality proof");
    check(!ContinuousReclaim.unchanged(source,before,after),"global Exact comparison remains strict");
    // This models only the post-success metadata branch; a server test separately
    // proves replaceAssembly owns the observed incidental transition update.
    var adopted=source.copy();if(permit.contains(before.id())){adopted.put("Road",data.index.roads.get(before.id()).record.header());adopted.putBoolean("DetachedExact",true);}
    check(adopted.getCompound("Road").equals(after.header()),"successful authorized transaction can capture its final actual header");
    check(ContinuousReclaim.unchanged(adopted,after,after),"new exact baseline recognizes unchanged final record");
    var edited=after.settings(after.settings().options(after.settings().options().hideArrows(!after.settings().options().hideArrows())));
    check(!ContinuousReclaim.unchanged(adopted,after,edited),"later authored edit still fails exact comparison");
    check(ContinuousReclaim.snapshotCandidates(new RoadData(),descriptor(adopted),before.owner()).isEmpty(),"later deletion cannot refresh final baseline");
    check(source.equals(fixture.getCompound("Source")),"fixture and original baseline were not altered");
    var world=new RoadData();for(var v:fixture.getList("Roads",Tag.TAG_COMPOUND))world.index.put(RoadIndex.Built.loading(RoadRecord.load((CompoundTag)v)));
    for(var v:fixture.getList("Junctions",Tag.TAG_COMPOUND)){var c=(CompoundTag)v;world.junctions.put(c.getUUID("Id"),c.copy());}
    check(!world.index.roads.isEmpty()&&world.junctions.size()==2,"fixture includes actual service roots and two live child junctions");
    var denied=fixture.getCompound("Saved").copy();ContinuousReclaim.refresh(world,denied);
    check(!denied.getList("ContinuousSources",Tag.TAG_COMPOUND).stream().map(v->(CompoundTag)v).filter(q->before.id().equals(q.getCompound("Road").getUUID("Id"))).findFirst().orElseThrow().getBoolean("Reclaimable"),"actual old exact baseline cannot reclaim normalized live road");
    var refreshed=fixture.getCompound("Saved").copy();for(var v:refreshed.getList("ContinuousSources",Tag.TAG_COMPOUND)){var q=(CompoundTag)v;if(before.id().equals(q.getCompound("Road").getUUID("Id")))q.put("Road",after.header());}
    var untouched=world.save(new CompoundTag());ContinuousReclaim.refresh(world,refreshed);
    check(refreshed.getList("ContinuousSources",Tag.TAG_COMPOUND).stream().map(v->(CompoundTag)v).filter(q->before.id().equals(q.getCompound("Road").getUUID("Id"))).findFirst().orElseThrow().getBoolean("Reclaimable"),"captured final actual baseline reclaims only after normal connected-root validation");
    check(untouched.equals(world.save(new CompoundTag())),"actual connected-root refresh remains read-only");
    System.out.println("CONTINUOUS_SNAPSHOT204 PASS checks="+checks+"; actual record evidence, no world transaction or global equivalence relaxation");
  }
}
