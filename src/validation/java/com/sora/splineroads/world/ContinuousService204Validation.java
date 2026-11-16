package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

/** In-memory record transaction replay; the real server sequence remains separately required. */
public final class ContinuousService204Validation {
  static final UUID GROUP=new UUID(20423,1),OWNER=new UUID(20423,2);static int checks;
  static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
  static BlockPos pos(V p){return BlockPos.containing(p.x(),p.y(),p.z());}
  static Options options(Preset p,int axis){return new Options(p,false,1,96,20,5,axis==0?2:1,5,true,false,0,axis,5,false,false,0);}
  static Settings settings(boolean highway){var style=highway?Style.H4_RAIL:Style.O4_RAIL;var s=new Settings(Mode.STRAIGHT,style,style.defaultWidth(),1,.4,90);return s.options(s.options().extras(false,false,false).cycleFinish(RoadProfile.Options.CycleFinish.NONE).route(s.options().routing().fit(false)).infrastructure(s.options().infrastructure().gantry(RoadInfrastructure.Gantry.OFF)));}
  static CompoundTag fixture(int axis){
    var nodes=new Node[]{new Node(new V(-620,axis==0?-59:-40,0),-90,0),new Node(new V(620,axis==0?-59:-40,0),-90,0),new Node(new V(0,axis==1?-59:-40,-620),0,0),new Node(new V(0,axis==1?-59:-40,620),0,0)};
    var t=new CompoundTag();t.putUUID("Id",GROUP);t.putUUID("Owner",OWNER);var tags=new ListTag();for(var n:nodes)tags.add(RoadRecord.writeNode(n));t.put("Nodes",tags);t.put("ContinuousLimits",tags.copy());t.putLongArray("Points",Arrays.stream(nodes).mapToLong(n->pos(n.position()).asLong()).toArray());t.put("Main1",RoadRecord.writeSettings(settings(axis!=0)));t.put("Main2",RoadRecord.writeSettings(settings(axis!=1)));var sources=new ListTag();var occupied=new ListTag();
    for(int i=0;i<2;i++){var a=nodes[i*2];var b=nodes[i*2+1];var r=new RoadRecord(new UUID(20424,i),OWNER,pos(a.position()),pos(b.position()),a,b,settings(axis!=i),false,4);var q=new CompoundTag();q.putInt("Axis",i);q.put("Road",r.header());sources.add(q);var o=new CompoundTag();o.putUUID("Road",r.id());occupied.add(o);}t.put("ContinuousSources",sources);t.put("OccupiedRoads",occupied);return t;
  }
  static RoadData initial(CompoundTag t){var data=new RoadData();for(var s:ContinuousRoads.sources(t)){var r=s.road();data.index.put(new RoadIndex.Built(r.alignment(GROUP,r.caps(0).mesh())));}data.interchanges.put(GROUP,t.copy());return data;}
  static CompoundTag input(RoadData data,CompoundTag saved,Preset preset,int axis){var t=saved.copy();ContinuousReclaim.refresh(data,t);InterchangeJunctions.captureEdits(data,GROUP,t);t.put("Options",Interchanges.write(options(preset,axis)));return t;}
  static Plan plan(CompoundTag t){try(var ignored=RoadPlanningBudget.open("service record replay",120)){return Interchanges.plan(t);}}
  static CompoundTag commit(RoadData data,CompoundTag source,Plan p){
    var t=source.copy();var records=new ArrayList<RoadIndex.Built>();var specs=new ArrayList<JunctionSpec>();var children=new ArrayList<CompoundTag>();var topology=new ListTag();var removed=new HashSet<UUID>();
    for(var old:data.index.roads.values())if(InterchangeJunctions.belongs(data,GROUP,old.record.assembly()))removed.add(old.record.id());int part=0;
    for(var leg:p.legs()){
      var mesh=leg.mesh();var a=RoadRibbon.start(mesh);var b=RoadRibbon.end(mesh);UUID sid=ContinuousRoads.sourceId(leg.name());UUID id=sid!=null?ContinuousSections.id(leg.name()):UUID.nameUUIDFromBytes((GROUP+":"+part++).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var r=new RoadRecord(id,OWNER,pos(a.position()),pos(b.position()),a,b,mesh.settings(),false,4).alignment(GROUP,mesh);
      if(sid!=null){removed.add(sid);var old=ContinuousSections.source(t,leg.name());r=new RoadRecord(id,old.owner(),a.position().distance(old.start().position())<1e-6?old.a():pos(a.position()),b.position().distance(old.end().position())<1e-6?old.b():pos(b.position()),a,b,mesh.settings(),old.automatic(),old.clearance(),List.of(),old.endCaps(),old.buildVersion(),ContinuousRoads.donor(leg.name())?null:GROUP,mesh.samples(),old.furniturePhase());}
      if(leg.junction()!=null){var spec=leg.junction().spec();int slot=specs.indexOf(spec);if(slot<0){slot=specs.size();specs.add(spec);children.add(InterchangeJunctions.descriptor(GROUP,OWNER,slot,spec));}r=InterchangeJunctions.piece(GROUP,OWNER,slot,leg.junction());}
      UUID currentId=r.id();check(records.stream().noneMatch(q->q.record.id().equals(currentId)),"duplicate staged ID "+r.id());records.add(new RoadIndex.Built(r));
      var role=new CompoundTag();role.putUUID("Road",r.id());role.putInt("From",leg.from());role.putInt("To",leg.to());role.putBoolean("Ramp",mesh.settings().style().ramp());topology.add(role);
    }
    ContinuousSections.updateSources(t,ContinuousService.sourcePlan(t,p));
    ContinuousService.prepare(t,p,records,children,topology,GROUP,OWNER);
    for(var id:removed)data.index.remove(id);for(var built:records)data.index.put(built);InterchangeJunctions.forget(data,GROUP);for(var child:children)data.junctions.put(child.getUUID("Id"),child);
    var nodes=new ListTag();p.anchors().forEach(n->nodes.add(RoadRecord.writeNode(n)));HostAxes.fit(t,p.anchors());t.put("Nodes",nodes);t.put("PlanningNodes",nodes.copy());t.putLongArray("Points",p.anchors().stream().mapToLong(n->pos(n.position()).asLong()).toArray());
    for(var value:t.getList("ContinuousSources",Tag.TAG_COMPOUND)){
      var q=(CompoundTag)value;var old=RoadRecord.load(q.getCompound("Road"));var current=data.index.roads.get(old.id());if(current!=null){if(!q.getBoolean("Detached")&&!q.getBoolean(ContinuousService.LOGICAL))q.put("Road",ContinuousRoads.updatedSource(old,current.record).header());else if(q.getBoolean("Detached")&&(q.getBoolean("Reclaimable")||records.stream().anyMatch(built->built.record.id().equals(old.id())))){q.put("Road",current.record.header());q.putBoolean("DetachedExact",true);}}q.remove("Reclaimable");
    }
    ContinuousService.capture(data,t);t.put("SignTopology",topology);data.interchanges.put(GROUP,t.copy());return t;
  }
  static void outside(RoadData data,Plan p,int axis){
    double low=axis==0?p.anchors().get(0).position().x():p.anchors().get(2).position().z(),high=axis==0?p.anchors().get(1).position().x():p.anchors().get(3).position().z();
    for(double station=-619.75;station<620;station+=.5){if(station>=low&&station<=high)continue;V point=axis==0?new V(station,-59,0):new V(0,-59,station);boolean covered=false;
      for(var b:data.index.roads.values())if(b.record.assembly()==null&&!b.mesh.settings().style().ramp()){var nearest=RoadQueries.horizontal(b.mesh,point).sample().center();if(nearest.distance(point)<1e-6){covered=true;break;}}
      if(!covered)throw new AssertionError("outside source coverage lost axis="+axis+" station="+station);
    }check(true,"all 0.5m outside samples retain ordinary road coverage axis="+axis);
  }
  static void rejected(RoadData data,CompoundTag saved,int axis,String name){var before=data.save(new CompoundTag());boolean refused=false;try{plan(input(data,saved,Preset.ROUNDABOUT,axis));}catch(IllegalArgumentException expected){refused=expected.getMessage().contains("不能自动");}check(refused,"guard failed "+name);check(before.equals(data.save(new CompoundTag())),"guard mutated records "+name);}
  static void editChild(RoadData data,CompoundTag saved){
    var child=data.junctions.values().iterator().next();int slot=child.getInt("InterchangeSlot");UUID id=child.getUUID("Id");var s=JunctionCodec.read(child.getCompound("Spec"));var arms=new ArrayList<>(s.arms());arms.set(0,arms.get(0).crossing(!arms.get(0).crosswalk()));
    var changed=new JunctionSpec(s.center(),s.kind(),s.leftTraffic(),s.cornerRadius(),s.islandRadius(),s.ringLanes(),s.ringLaneWidth(),s.thickness(),JunctionSpec.Control.SIGNALS,Math.min(120,s.greenSeconds()+1),s.yellowSeconds(),s.allRedSeconds(),s.timeOffset(),s.guides(),s.greenIsland(),s.outerRail(),arms,s.parallelApproaches());
    changed=JunctionCodec.read(JunctionCodec.write(changed));
    var ids=data.index.roads.values().stream().filter(b->id.equals(b.record.assembly())).map(b->b.record.id()).toList();ids.forEach(data.index::remove);var p=JunctionPlanner.plan(changed);for(int i=0;i<p.pieces().size();i++)data.index.put(new RoadIndex.Built(InterchangeJunctions.piece(GROUP,OWNER,slot,new JunctionPlanner.Ref(changed,i))));child.put("Spec",JunctionCodec.write(changed));
  }

  static CompoundTag sourceTag(CompoundTag saved,UUID id){return saved.getList("ContinuousSources",Tag.TAG_COMPOUND).stream().map(v->(CompoundTag)v).filter(q->id.equals(q.getCompound("Road").getUUID("Id"))).findFirst().orElseThrow();}
  static RoadData copyData(RoadData original){
    var copy=new RoadData();for(var built:original.index.roads.values())copy.index.put(new RoadIndex.Built(built.record));
    original.junctions.forEach((id,t)->copy.junctions.put(id,t.copy()));original.interchanges.forEach((id,t)->copy.interchanges.put(id,t.copy()));return copy;
  }
  static Plan expandedMain(CompoundTag fresh){
    var nodes=HostAxes.nodes(fresh);nodes[3]=new Node(new V(0,-59,720),nodes[3].yaw(),nodes[3].grade());
    check(ContinuousRoads.virtual(fresh,3,nodes[3]),"expansion stays inside actually captured outside limit");
    var mesh=RoadGeometry.build(nodes[2],nodes[3],RoadRecord.readSettings(fresh.getCompound("Main2")));
    var p=new Plan(List.of(new Leg("主路 2",1,3,mesh)),0,0,0,new V(0,-59,0),Arrays.asList(nodes));
    return ContinuousRoads.confirmed(fresh,p);
  }
  static void unusedNeighbors(){
    var initial=fixture(1);initial.remove("Id");initial.put("Options",Interchanges.write(options(Preset.DIAMOND,1)));
    var base=new RoadData();for(var source:ContinuousRoads.sources(initial))base.index.put(new RoadIndex.Built(source.road()));
    var main=ContinuousRoads.sources(initial).stream().filter(s->s.axis()==1).findFirst().orElseThrow().road();
    var start=main.end();var end=new Node(start.position().add(new V(0,0,200)),start.yaw(),start.grade());UUID neighborId=new UUID(20499,81);
    var neighbor=new RoadRecord(neighborId,OWNER,main.b(),pos(end.position()),start,end,main.settings(),false,4);base.index.put(new RoadIndex.Built(neighbor));
    ContinuousRoads.capture(base,initial);
    check(ContinuousRoads.sources(initial).size()==3,"natural first capture includes unused ordinary neighbour");
    check(!initial.hasUUID("Id"),"brand-new capture has no parent identity");
    var newPayload=initial.copy();ContinuousReclaim.refresh(base,newPayload);
    check(!sourceTag(newPayload,neighborId).getBoolean("Detached"),"refresh does not reclassify new capture without a parent");
    var p=plan(initial);check(p.legs().stream().noneMatch(l->neighborId.equals(ContinuousRoads.sourceId(l.name()))),"first DIAMOND does not consume distant neighbour");
    var saved=commit(base,initial,p);saved.putUUID("Id",GROUP);saved.putUUID("Owner",OWNER);base.interchanges.put(GROUP,saved.copy());
    var baseline=sourceTag(saved,neighborId).copy();
    check(baseline.getBoolean("Detached")&&baseline.getBoolean("DetachedExact")&&!baseline.getBoolean(ContinuousService.LOGICAL),"unused ordinary source receives exact detached baseline, never service logical");
    check(neighbor.header().equals(base.index.roads.get(neighborId).record.header()),"first service build leaves unused neighbour unchanged");
    var logical=saved.getList("ContinuousSources",Tag.TAG_COMPOUND).stream().map(v->(CompoundTag)v).filter(q->q.getBoolean(ContinuousService.LOGICAL)).toList();
    check(!logical.isEmpty()&&logical.stream().noneMatch(q->neighborId.equals(q.getCompound("Road").getUUID("Id"))),"actual service interior remains logical and outside source is excluded");
    for(boolean legacy:List.of(false,true))for(int mutation=0;mutation<3;mutation++){
      var data=copyData(base);var descriptor=saved.copy();
      if(legacy){var old=sourceTag(descriptor,neighborId);old.remove("Detached");old.remove("DetachedExact");}
      var originalBaseline=sourceTag(descriptor,neighborId).getCompound("Road").copy();
      if(mutation==1)data.index.put(new RoadIndex.Built(neighbor.settings(neighbor.settings().options(neighbor.settings().options().hideArrows(true)))));
      if(mutation==2)data.index.remove(neighborId);
      var actual=data.index.roads.get(neighborId);CompoundTag actualBefore=actual==null?null:actual.record.header();
      var before=data.save(new CompoundTag());var fresh=input(data,descriptor,Preset.DIAMOND,1);var outside=sourceTag(fresh,neighborId);
      check(outside.getBoolean("Detached")&&!outside.getBoolean(ContinuousService.LOGICAL),"outside remains protected legacy="+legacy+" mutation="+mutation);
      check(outside.getBoolean("Reclaimable")==(mutation==0),"only live unchanged connected neighbour is reclaimable legacy="+legacy+" mutation="+mutation);
      check(originalBaseline.equals(outside.getCompound("Road")),"refresh never launders the outside baseline legacy="+legacy+" mutation="+mutation);
      var keep=plan(fresh);check(keep.legs().stream().noneMatch(l->neighborId.equals(ContinuousRoads.sourceId(l.name()))),"unchanged occupied range never stages outside neighbour legacy="+legacy+" mutation="+mutation);
      check(before.equals(data.save(new CompoundTag())),"read-only preview preserves data legacy="+legacy+" mutation="+mutation);
      var next=commit(data,fresh,keep);actual=data.index.roads.get(neighborId);
      check(actualBefore==null?actual==null:actual!=null&&actualBefore.equals(actual.record.header()),"normal parent save preserves outside edit/deletion legacy="+legacy+" mutation="+mutation);
      check(originalBaseline.equals(sourceTag(next,neighborId).getCompound("Road")),"normal parent save preserves outside provenance legacy="+legacy+" mutation="+mutation);
      var expanded=input(data,next,Preset.ROUNDABOUT,1);var untouched=data.save(new CompoundTag());boolean refused=false;Plan extension=null;
      try{extension=expandedMain(expanded);}catch(IllegalArgumentException e){if(e.getMessage()==null||!e.getMessage().contains("原立交外侧余段"))throw e;refused=true;}
      check(refused==(mutation!=0),"expansion accepts only unchanged neighbour legacy="+legacy+" mutation="+mutation);
      if(mutation==0){check(extension.legs().stream().anyMatch(l->neighborId.equals(ContinuousRoads.sourceId(l.name()))&&!ContinuousRoads.donor(l.name())),"legitimate expansion consumes recorded live neighbour");}
      check(untouched.equals(data.save(new CompoundTag())),"expansion guard is read-only legacy="+legacy+" mutation="+mutation);
      System.out.println("UNUSED_NEIGHBOR PASS legacy="+legacy+" mutation="+mutation+" ordinary save preserves outside / expansion authorized exactly");
    }
  }

  public static void main(String[] args){
    unusedNeighbors();
    for(var preset:List.of(Preset.DIAMOND,Preset.SPUI))for(int axis=0;axis<2;axis++){
      var initial=fixture(axis);initial.put("Options",Interchanges.write(options(preset,axis)));var data=initial(initial);var t=input(data,initial,preset,axis);var p=plan(t);var saved=commit(data,t,p);outside(data,p,axis);
      var reopened=input(data,saved,Preset.ROUNDABOUT,axis);for(var value:reopened.getList("ContinuousSources",Tag.TAG_COMPOUND)){var q=(CompoundTag)value;if(q.getBoolean("Detached"))check(q.getBoolean("Reclaimable"),"untouched service donor not reclaimable");}
      var round=plan(reopened);check(!round.legs().isEmpty(),"reopened ROUNDABOUT empty");
      editChild(data,saved);var edited=input(data,saved,Preset.ROUNDABOUT,axis);check(!plan(edited).legs().isEmpty(),"legal independent child edit blocks next preset");
      var childId=data.junctions.keySet().iterator().next();var piece=data.index.roads.values().stream().filter(b->childId.equals(b.record.assembly())).findFirst().orElseThrow();data.index.remove(piece.record.id());rejected(data,saved,axis,"missing child member");data.index.put(piece);
      var rootId=saved.getCompound(ContinuousService.RECEIPT).getList("Roots",Tag.TAG_COMPOUND).getCompound(0).getUUID("Id");var root=data.index.roads.get(rootId);data.index.remove(rootId);rejected(data,saved,axis,"missing main witness");data.index.put(root);
      int serviceAxis=axis;var donor=reopened.getList("ContinuousSources",Tag.TAG_COMPOUND).stream().map(v->(CompoundTag)v).filter(q->q.getBoolean("Detached")&&q.getInt("Axis")==serviceAxis).findFirst().orElseThrow();var donorId=donor.getCompound("Road").getUUID("Id");var ordinary=data.index.roads.get(donorId);data.index.put(new RoadIndex.Built(ordinary.record.settings(ordinary.record.settings().options(ordinary.record.settings().options().hideArrows(!ordinary.record.settings().options().hideArrows())))));rejected(data,saved,axis,"edited actual outside donor");data.index.put(ordinary);
      var done=commit(data,edited,round);check(!done.contains(ContinuousService.RECEIPT),"receipt retained after ordinary-main layout");check(!plan(input(data,done,Preset.DOUBLE_TRUMPET,axis)).legs().isEmpty(),"ROUNDABOUT reopen cannot continue to DOUBLE_TRUMPET");
      System.out.println("SERVICE_CONTINUATION PASS "+preset+" axis="+axis+" donors retained / reopen / child edit / guards / next normal layout");
    }
    System.out.println("SERVICE_CONTINUATION204 PASS checks="+checks+"; in-memory records only, server world sequence still required");
  }
}
