package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

/** Ordinary SR junctions owned by an interchange, with independent editor identities. */
final class InterchangeJunctions {
  static UUID parent(RoadData data,UUID id){
    var child=data.junctions.get(id);
    return child!=null&&child.hasUUID("Interchange")?child.getUUID("Interchange"):id;
  }
  static boolean belongs(RoadData data,UUID parent,UUID assembly){return assembly!=null&&parent.equals(parent(data,assembly));}
  static UUID id(UUID parent,int index){return UUID.nameUUIDFromBytes((parent+":service-junction:"+index).getBytes(StandardCharsets.UTF_8));}
  static CompoundTag descriptor(UUID parent,UUID owner,int index,JunctionSpec spec){
    var t=new CompoundTag();t.putString("Kind","junction");t.putUUID("Id",id(parent,index));t.putUUID("Owner",owner);t.putUUID("Interchange",parent);t.putInt("InterchangeSlot",index);t.put("Spec",JunctionCodec.write(spec));
    long[] points=new long[spec.arms().size()+1];points[0]=pos(spec.center()).asLong();for(int i=0;i<spec.arms().size();i++)points[i+1]=pos(spec.arms().get(i).endpoint().position()).asLong();t.putLongArray("Points",points);return t;
  }
  static BlockPos pos(V p){return BlockPos.containing(p.x(),p.y(),p.z());}
  static RoadRecord piece(UUID parent,UUID owner,int index,JunctionPlanner.Ref ref){
    UUID group=id(parent,index),id=UUID.nameUUIDFromBytes((group+":junction:"+ref.piece()).getBytes(StandardCharsets.UTF_8));
    var mesh=ref.get().mesh();int arm=ref.get().arm();var center=pos(ref.spec().center());
    return new RoadRecord(id,owner,arm<0?center:pos(ref.spec().arms().get(arm).endpoint().position()),center,RoadRibbon.start(mesh),RoadRibbon.end(mesh),mesh.settings(),false,4).junction(group,ref);
  }
  static void forget(RoadData data,UUID parent){data.junctions.entrySet().removeIf(e->e.getValue().hasUUID("Interchange")&&parent.equals(e.getValue().getUUID("Interchange")));}
  static Set<UUID> restoreMain(RoadData data,CompoundTag saved,List<RoadIndex.Built> mains){
    var options=Interchanges.read(saved.getCompound("Options"));
    if(options.preset()!=InterchangePlanner.Preset.DIAMOND&&options.preset()!=InterchangePlanner.Preset.SPUI)return Set.of();
    // Old releases kept an untrimmed cross road; only the new standard-junction
    // topology has gaps to close when removing all ramps and junctions.
    UUID parent=saved.getUUID("Id");boolean children=saved.getBoolean("StandardServiceJunctions")||data.junctions.values().stream().anyMatch(t->t.hasUUID("Interchange")&&parent.equals(t.getUUID("Interchange")));
    if(!children)return Set.of();
    int axis=options.junctionAxis();Set<UUID> pieces=new HashSet<>();
    for(Tag value:saved.getList("SignTopology",Tag.TAG_COMPOUND)){var t=(CompoundTag)value;if(!t.getBoolean("Ramp")&&t.getInt("From")==axis&&t.getInt("To")==axis+2&&t.hasUUID("Road"))pieces.add(t.getUUID("Road"));}
    var found=pieces.stream().map(data.index.roads::get).filter(Objects::nonNull).findFirst();if(found.isEmpty())return Set.of();
    var nodes=saved.getList("Nodes",Tag.TAG_COMPOUND);Node a=RoadRecord.readNode(nodes.getCompound(axis*2)),b=RoadRecord.readNode(nodes.getCompound(axis*2+1));
    var source=found.get();double length=a.position().sub(b.position()).horizontalLength();var samples=new ArrayList<Sample>();V left=b.position().sub(a.position()).horizontalUnit().left();
    for(int i=0,n=(int)Math.ceil(length/.5);i<=n;i++)samples.add(new Sample(a.position().mul(1-i/(double)n).add(b.position().mul(i/(double)n)),left,0,source.mesh.first().halfWidth()));
    Mesh mesh=RoadRibbon.mesh(samples,source.record.settings());UUID id=UUID.nameUUIDFromBytes((parent+":restored-service-main").getBytes(StandardCharsets.UTF_8));
    mains.add(new RoadIndex.Built(new RoadRecord(id,saved.getUUID("Owner"),BlockPos.of(saved.getLongArray("Points")[axis*2]),BlockPos.of(saved.getLongArray("Points")[axis*2+1]),a,b,mesh.settings(),false,4).alignment(null,mesh)));
    return pieces;
  }
  static void captureEdits(RoadData data,UUID parent,CompoundTag payload){
    var tags=new ListTag();data.junctions.values().stream().filter(t->t.hasUUID("Interchange")&&parent.equals(t.getUUID("Interchange")))
        .sorted(Comparator.comparingInt(t->t.getInt("InterchangeSlot"))).forEach(t->{var copy=new CompoundTag();copy.putInt("Slot",t.getInt("InterchangeSlot"));copy.put("Spec",t.getCompound("Spec").copy());tags.add(copy);});
    payload.put("ServiceJunctionEdits",tags);
  }
  static InterchangePlanner.Plan applyEdits(InterchangePlanner.Plan plan,CompoundTag payload){
    var saved=payload.getList("ServiceJunctionEdits",Tag.TAG_COMPOUND);if(saved.isEmpty())return plan;
    var specs=new ArrayList<JunctionSpec>();var legs=new ArrayList<InterchangePlanner.Leg>();
    for(var leg:plan.legs()){
      if(leg.junction()==null){legs.add(leg);continue;}
      var fresh=leg.junction().spec();if(specs.contains(fresh))continue;int slot=specs.size();specs.add(fresh);
      var oldTag=saved.stream().map(t->(CompoundTag)t).filter(t->t.getInt("Slot")==slot).findFirst();
      JunctionSpec spec=fresh;
      if(oldTag.isPresent()){
        var old=JunctionCodec.read(oldTag.get().getCompound("Spec"));
        if(old.arms().size()==fresh.arms().size()){
          var arms=new ArrayList<JunctionSpec.Arm>();
          for(int i=0;i<fresh.arms().size();i++){
            var a=fresh.arms().get(i);var b=old.arms().get(i);
            arms.add(a.incoming()==b.incoming()&&a.outgoing()==b.outgoing()?new JunctionSpec.Arm(a.endpoint(),a.inward(),a.external(),a.incoming(),a.outgoing(),a.width(),a.median(),a.medianKind(),a.cycleWidth(),a.curbWidth(),b.crosswalk(),b.crossingWidth(),b.crossingSetback(),b.stopGap(),b.phase(),b.lanes(),true,b.advanceSign(),b.nameSign()):a);
          }
          spec=new JunctionSpec(fresh.center(),fresh.kind(),fresh.leftTraffic(),old.cornerRadius(),old.islandRadius(),old.ringLanes(),old.ringLaneWidth(),fresh.thickness(),old.control(),old.greenSeconds(),old.yellowSeconds(),old.allRedSeconds(),old.timeOffset(),old.guides(),old.greenIsland(),old.outerRail(),arms,fresh.parallelApproaches());
        }
      }
      var junction=JunctionPlanner.plan(spec);for(int i=0;i<junction.pieces().size();i++)legs.add(new InterchangePlanner.Leg(leg.name(),-1,-1,junction.pieces().get(i).mesh(),new JunctionPlanner.Ref(spec,i)));
    }
    double clearance=Interchanges.read(payload.getCompound("Options")).clearance();
    for(int i=0;i<legs.size();i++)for(int j=0;j<i;j++)if(legs.get(i).junction()!=null||legs.get(j).junction()!=null)
      for(var contact:RoadClearance.detailedContacts(legs.get(i).mesh(),legs.get(j).mesh()))if(!contact.coplanar()&&contact.contact().usableClearance()+1e-7<Math.max(clearance,contact.contact().required()))throw new IllegalArgumentException("编辑后的路口与立交道路净高不足，请调整路口参数");
    return new InterchangePlanner.Plan(List.copyOf(legs),plan.movements(),plan.minRadius(),plan.highest(),plan.center(),plan.anchors());
  }
  static InterchangePlanner.Plan elevationDefaults(InterchangePlanner.Plan plan,CompoundTag payload){
    int axis=Interchanges.read(payload.getCompound("Options")).junctionAxis();
    if(!payload.getBoolean("ServiceElevated"+axis)||!payload.getList("ServiceJunctionEdits",Tag.TAG_COMPOUND).isEmpty())return plan;
    var legs=new ArrayList<InterchangePlanner.Leg>();var seen=new HashSet<JunctionSpec>();
    for(var leg:plan.legs()){
      if(leg.junction()==null){legs.add(leg);continue;}
      var spec=leg.junction().spec();if(!seen.add(spec))continue;
      spec=spec.arms(spec.arms().stream().map(a->a.external().options().sidewalk().enabled()?a:a.crossing(false)).toList());
      var junction=JunctionPlanner.plan(spec);
      for(int i=0;i<junction.pieces().size();i++)legs.add(new InterchangePlanner.Leg(leg.name(),-1,-1,junction.pieces().get(i).mesh(),new JunctionPlanner.Ref(spec,i)));
    }
    return new InterchangePlanner.Plan(List.copyOf(legs),plan.movements(),plan.minRadius(),plan.highest(),plan.center(),plan.anchors());
  }
  private InterchangeJunctions(){}
}
