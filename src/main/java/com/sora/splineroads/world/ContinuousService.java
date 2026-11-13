package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.nbt.*;

/** A service crossroad replaces the managed interior, never its ordinary outside continuations. */
final class ContinuousService {
  static final String LOGICAL="ServiceLogical", RECEIPT="ContinuousServiceReceipt";
  static final String INVALID="原立交服务主路记录已修改、删除或不再完整，不能自动恢复连续主路；请先处理该道路组合";

  static int axis(CompoundTag tag,Plan plan){
    var options=Interchanges.read(tag.getCompound("Options"));
    int axis=options.junctionAxis();
    return (options.preset()==Preset.DIAMOND||options.preset()==Preset.SPUI)
        &&plan.legs().stream().anyMatch(l->l.junction()!=null)
        &&ContinuousRoads.sources(tag).stream().anyMatch(s->s.axis()==axis)?axis:-1;
  }
  private static Plan coverage(CompoundTag tag,Plan plan,int axis){
    String prefix="主路 "+(axis+1)+" · ";
    var piece=plan.legs().stream().filter(l->l.name().startsWith(prefix)&&l.junction()==null).findFirst()
        .orElseThrow(()->new IllegalArgumentException("服务路口两端需保留至少两格普通主路，请扩大端点范围"));
    V a=plan.anchors().get(axis*2).position(),b=plan.anchors().get(axis*2+1).position();
    var samples=new ArrayList<Sample>();double length=b.sub(a).horizontalLength();
    for(int i=0,n=(int)Math.ceil(length/.5);i<=n;i++)
      samples.add(new Sample(a.mul(1-i/(double)n).add(b.mul(i/(double)n)),b.sub(a).horizontalUnit().left(),0,piece.mesh().first().halfWidth()));
    var full=new Leg("主路 "+(axis+1),axis,axis+2,RoadRibbon.mesh(samples,piece.mesh().settings()));
    var single=new Plan(List.of(full),plan.movements(),plan.minRadius(),plan.highest(),plan.center(),plan.anchors());
    return ContinuousRoads.segment(tag,single,ContinuousRoads.sources(tag));
  }
  static void outside(CompoundTag tag,Plan plan,List<Leg> result){
    int axis=axis(tag,plan);if(axis<0)return;
    // Process the complete occupied range ONCE; per-junction main pieces cannot be
    // independently segmented because that duplicates donors and source identities.
    for(var leg:coverage(tag,plan,axis).legs())if(ContinuousRoads.donor(leg.name()))result.add(leg);
  }
  static Plan sourcePlan(CompoundTag tag,Plan visible){
    int axis=axis(tag,visible);if(axis<0)return visible;
    var legs=new ArrayList<>(visible.legs());
    for(var leg:coverage(tag,visible,axis).legs())if(!ContinuousRoads.donor(leg.name()))legs.add(leg);
    return new Plan(List.copyOf(legs),visible.movements(),visible.minRadius(),visible.highest(),visible.center(),visible.anchors());
  }
  static boolean logical(CompoundTag tag,UUID id){
    return tag.getList("ContinuousSources",Tag.TAG_COMPOUND).stream().map(v->(CompoundTag)v)
        .anyMatch(q->q.getBoolean(LOGICAL)&&q.getCompound("Road").getUUID("Id").equals(id));
  }
  static void prepare(CompoundTag tag,Plan plan,List<RoadIndex.Built> staged,List<CompoundTag> children,ListTag topology,UUID group,UUID owner){
    int axis=axis(tag,plan);
    var occupied=new HashSet<UUID>();
    if(axis>=0)for(var leg:coverage(tag,plan,axis).legs())
      if(leg.name().startsWith(ContinuousRoads.MAIN))occupied.add(ContinuousSections.id(leg.name()));
    tag.remove(RECEIPT);
    for(var value:tag.getList("ContinuousSources",Tag.TAG_COMPOUND))((CompoundTag)value).remove(LOGICAL);
    if(axis<0)return;
    var receipt=new CompoundTag();receipt.putInt("Axis",axis);var sources=new ListTag();
    for(var value:tag.getList("ContinuousSources",Tag.TAG_COMPOUND)){
      var q=(CompoundTag)value;if(q.getInt("Axis")!=axis||q.getBoolean("Detached"))continue;
      if(!occupied.contains(q.getCompound("Road").getUUID("Id")))throw new IllegalArgumentException(INVALID);
      q.putBoolean(LOGICAL,true);var id=new CompoundTag();id.putUUID("Id",q.getCompound("Road").getUUID("Id"));sources.add(id);
    }
    var rootIds=new HashSet<UUID>();
    for(var value:topology){var q=(CompoundTag)value;if(!q.getBoolean("Ramp")&&q.getInt("From")==axis&&q.getInt("To")==axis+2)rootIds.add(q.getUUID("Road"));}
    var roots=new ListTag();var members=new ListTag();var junctions=new ListTag();
    for(var built:staged){
      var road=built.record;
      if(!group.equals(road.assembly()))continue;
      if(rootIds.contains(road.id())){var id=new CompoundTag();id.putUUID("Id",road.id());roots.add(id);}
      if(rootIds.contains(road.id()))members.add(road.header());
    }
    for(var child:children){
      if(!child.getUUID("Owner").equals(owner)||!child.getUUID("Interchange").equals(group))throw new IllegalArgumentException(INVALID);
      var spec=JunctionCodec.read(child.getCompound("Spec"));var expected=JunctionPlanner.plan(spec);UUID childId=child.getUUID("Id");int slot=child.getInt("InterchangeSlot");
      if(staged.stream().filter(b->childId.equals(b.record.assembly())).count()!=expected.pieces().size())throw new IllegalArgumentException(INVALID);
      for(int i=0;i<expected.pieces().size();i++){
        var ref=new JunctionPlanner.Ref(spec,i);var record=InterchangeJunctions.piece(group,owner,slot,ref);
        if(staged.stream().noneMatch(b->b.record.id().equals(record.id())&&b.record.owner().equals(owner)&&b.mesh.samples().equals(ref.get().mesh().samples())))throw new IllegalArgumentException(INVALID);
      }
      junctions.add(child.copy());
    }
    if(sources.isEmpty()||roots.size()<2||members.isEmpty()||junctions.isEmpty())throw new IllegalArgumentException(INVALID);
    receipt.put("Sources",sources);receipt.put("Roots",roots);receipt.put("Members",members);receipt.put("Junctions",junctions);tag.put(RECEIPT,receipt);
  }
  /** Structure and completeness were checked before replaceAssembly. Only snapshot final headers. */
  static void capture(RoadData data,CompoundTag tag){
    if(!tag.contains(RECEIPT))return;
    var receipt=tag.getCompound(RECEIPT);var members=new ListTag();
    for(var value:receipt.getList("Members",Tag.TAG_COMPOUND)){
      var old=(CompoundTag)value;var actual=data.index.roads.get(old.getUUID("Id"));
      members.add(actual==null?old.copy():actual.record.header());
    }
    receipt.put("Members",members);
  }
  static void refresh(RoadData data,CompoundTag tag,Set<UUID> roots,Map<UUID,RoadRecord> records,Map<UUID,Integer> axes){
    var logical=new HashSet<UUID>();
    for(var value:tag.getList("ContinuousSources",Tag.TAG_COMPOUND)){var q=(CompoundTag)value;if(q.getBoolean(LOGICAL))logical.add(q.getCompound("Road").getUUID("Id"));}
    if(logical.isEmpty()){if(tag.contains(RECEIPT))throw new IllegalArgumentException(INVALID);return;}
    var receipt=tag.getCompound(RECEIPT);int axis=receipt.getInt("Axis");var listed=new HashSet<UUID>();
    for(var value:receipt.getList("Sources",Tag.TAG_COMPOUND))listed.add(((CompoundTag)value).getUUID("Id"));
    if(!logical.equals(listed)||!tag.hasUUID("Id")||!tag.hasUUID("Owner"))throw new IllegalArgumentException(INVALID);
    for(var value:tag.getList("ContinuousSources",Tag.TAG_COMPOUND)){var q=(CompoundTag)value;if(q.getBoolean(LOGICAL)&&(q.getBoolean("Detached")||q.getInt("Axis")!=axis))throw new IllegalArgumentException(INVALID);}
    var members=new HashMap<UUID,RoadRecord>();
    for(var value:receipt.getList("Members",Tag.TAG_COMPOUND)){
      var saved=RoadRecord.load((CompoundTag)value);var built=data.index.roads.get(saved.id());
      if(built==null||!built.record.owner().equals(tag.getUUID("Owner"))
          ||!InterchangeJunctions.belongs(data,tag.getUUID("Id"),built.record.assembly())
          ||!saved.header().equals(built.record.header())||members.put(saved.id(),built.record)!=null)throw new IllegalArgumentException(INVALID);
    }
    for(var value:receipt.getList("Junctions",Tag.TAG_COMPOUND)){
      var before=(CompoundTag)value;UUID id=before.getUUID("Id");var current=data.junctions.get(id);
      if(current==null||!current.hasUUID("Owner")||!current.getUUID("Owner").equals(tag.getUUID("Owner"))
          ||!current.hasUUID("Interchange")||!current.getUUID("Interchange").equals(tag.getUUID("Id"))
          ||current.getInt("InterchangeSlot")!=before.getInt("InterchangeSlot"))throw new IllegalArgumentException(INVALID);
      var old=JunctionCodec.read(before.getCompound("Spec"));var spec=JunctionCodec.read(current.getCompound("Spec"));
      if(!old.center().equals(spec.center())||old.arms().size()!=spec.arms().size())throw new IllegalArgumentException(INVALID);
      for(int i=0;i<old.arms().size();i++){
        var a=old.arms().get(i);var b=spec.arms().get(i);
        // Match Junctions.buildRoad's attached-port invariants. Width, lane counts,
        // crossings and controls are legal independent edits and use the current spec.
        if(!a.endpoint().equals(b.endpoint())||!a.inward().equals(b.inward())
            ||a.attached()!=b.attached()||!a.external().equals(b.external()))throw new IllegalArgumentException(INVALID);
      }
      var expected=JunctionPlanner.plan(spec);
      if(data.index.roads.values().stream().filter(b->id.equals(b.record.assembly())).count()!=expected.pieces().size())throw new IllegalArgumentException(INVALID);
      for(int i=0;i<expected.pieces().size();i++){
        var ref=new JunctionPlanner.Ref(spec,i);var record=InterchangeJunctions.piece(tag.getUUID("Id"),tag.getUUID("Owner"),current.getInt("InterchangeSlot"),ref);var live=data.index.roads.get(record.id());
        if(live==null||!live.record.owner().equals(tag.getUUID("Owner"))||!id.equals(live.record.assembly())
            ||live.record.junction()==null||!live.record.junction().spec().equals(ref.spec())||live.record.junction().piece()!=i
            ||!live.mesh.samples().equals(ref.get().mesh().samples()))throw new IllegalArgumentException(INVALID);
      }
    }
    int count=0;
    for(var value:receipt.getList("Roots",Tag.TAG_COMPOUND)){
      UUID id=((CompoundTag)value).getUUID("Id");var road=members.get(id);
      if(road==null||road.junction()!=null||road.toll()!=null||!RoadContinuity.eligible(road.caps(0).mesh()))throw new IllegalArgumentException(INVALID);
      roots.add(id);records.put(id,road);axes.put(id,axis);count++;
    }
    if(count<2)throw new IllegalArgumentException(INVALID);
  }
  private ContinuousService(){}
}
