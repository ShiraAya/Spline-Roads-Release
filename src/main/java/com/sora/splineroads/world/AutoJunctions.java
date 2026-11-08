package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.JunctionSpec.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

/** Logical streets survive trimming. One transaction rebuilds both ends and all affected centers. */
public final class AutoJunctions {
  public record Draft(List<RoadRecord> streets,List<RoadRecord> roads,List<CompoundTag> centers,Map<UUID,List<RoadRecord>> newSplits) {
    public Draft(List<RoadRecord> streets,List<RoadRecord> roads,List<CompoundTag> centers){
      this(streets,roads,centers,Map.of());
    }
  }
  /** One frontier edge and both complete endpoint junctions form an atomic unit. */
  record StreetChange(CompoundTag before,Draft draft,List<RoadData.NodeMove> moves,Set<UUID> centers,UUID added){}
  static StreetChange streetAddition(ServerLevel level,ServerPlayer player,RoadRecord next,List<CompoundTag> plannedCenters){
    var data=RoadData.get(level);if(data.index.roads.containsKey(next.id()))throw new IllegalArgumentException("此规划道路已经存在");
    var active=Set.of(next.a(),next.b());var before=context(data,active);var roads=streets(before);roads.add(next);var configs=centers(before);
    for(var planned:plannedCenters)if(active.contains(BlockPos.of(planned.getLong("CenterPos")))&&configs.stream().noneMatch(c->c.getLong("CenterPos")==planned.getLong("CenterPos"))){
      var config=config(BlockPos.of(planned.getLong("CenterPos")),next.owner());config.putBoolean("LeftTraffic",next.settings().options().leftTraffic());configs.add(config);
    }
    var draft=plan(roads,configs,before);var moves=new ArrayList<RoadData.NodeMove>();
    for(boolean first:new boolean[]{true,false}){var p=first?next.a():next.b();var target=first?next.start():next.end();
      if(level.getBlockEntity(p) instanceof NodeEntity node){if(player!=null)RoadData.requireOwner(player,node.owner);if(node.constructionNode().position().distance(target.position())>.01)throw new IllegalArgumentException("接口端点在预览后移动，请重新规划");}
      else {var tag=new CompoundTag();tag.put("Node",RoadRecord.writeNode(target));tag.putBoolean("HeightExplicit",true);tag.putUUID("Owner",next.owner());moves.add(new RoadData.NodeMove(null,p,target,tag));}
    }
    var groups=new HashSet<UUID>();for(var c:centers(before))groups.add(c.getUUID("Id"));for(var c:draft.centers())groups.add(c.getUUID("Id"));
    StreetNetworkGeometry.checkGrades(draft.roads());
    return new StreetChange(before,draft,List.copyOf(moves),Set.copyOf(groups),next.id());
  }
  static void commitStreet(ServerLevel level,ServerPlayer player,StreetChange change){commit(level,player,change.before(),change.draft(),change.moves(),Set.of(change.added()),Set.of());}
  public static List<RoadRecord> selected(Draft draft,UUID id){
    var centers=new HashMap<UUID,CompoundTag>();draft.centers().forEach(t->centers.put(t.getUUID("Id"),t));
    var ids=new HashSet<String>();ids.add(id.toString());
    // Historical siblings share an alignment but are independently editable.
    for(var piece:draft.newSplits().getOrDefault(id,List.of()))ids.add(piece.id().toString());
    return draft.roads().stream().filter(r->{if(ids.contains(r.id().toString()))return true;if(r.junction()==null)return false;
      var t=centers.get(r.assembly());if(t==null)return false;var arms=t.getList("ArmRoads",Tag.TAG_STRING);int arm=r.junction().get().arm();
      return arm>=0&&arm<arms.size()&&ids.contains(arms.getString(arm));}).toList();
  }
  public static boolean ordinary(RoadRecord r){return r.assembly()==null&&RoadProfile.catalog(r.settings().style()).type()==RoadProfile.Type.ORDINARY&&r.settings().mode()!=Mode.RING;}
  public static Map<UUID,RoadRecord> logical(RoadData data){
    var result=new LinkedHashMap<UUID,RoadRecord>();data.index.roads.values().stream().map(b->b.record).filter(r->ordinary(r)||corridorStreet(data,r)).sorted(Comparator.comparing(RoadRecord::id)).forEach(r->result.put(r.id(),r));data.streets.keySet().forEach(id->result.put(id,data.editableRoad(id)));
    // An untrimmed split's live record is authoritative. 1.0.8 could derive its
    // logical counterpart from a median-offset projection at a different station.
    // True junction approaches still require their longer logical source.
    for(var entry:data.streets.entrySet()){var live=data.index.roads.get(entry.getKey());var saved=entry.getValue();
      if(live!=null&&live.record.fragment()!=null&&saved.fragment()!=null&&ordinary(live.record)
          &&live.record.fragment().source().equals(saved.fragment().source())
          &&center(data,saved.a())==null&&center(data,saved.b())==null&&ringAt(data,saved.a())==null&&ringAt(data,saved.b())==null)result.put(entry.getKey(),live.record);
    }return result;
  }
  private static boolean corridorStreet(RoadData d,RoadRecord r){var group=d.interchanges.get(r.assembly());return r.junction()==null&&group!=null&&group.contains("Corridor")&&RoadProfile.catalog(r.settings().style()).type()==RoadProfile.Type.ORDINARY;}
  public static boolean incompatible(RoadData d,BlockPos a,BlockPos b,UUID id,Settings s){
    var roads=logical(d);var previous=roads.get(id);
    for(var r:roads.values())if(!r.id().equals(id))for(boolean start:new boolean[]{true,false}){
      BlockPos p=start?a:b;if(!touches(r,p))continue;boolean first=r.a().equals(p);
      Settings target=r.fragment()!=null||r.assembly()!=null||r.settings().options().ends().persistent()
          ||RoadEndpointSections.changed(r.mesh(),first)?RoadData.endpointSection(new RoadIndex.Built(r),p):RoadData.authoredSection(r.settings());
      target=RoadEndpointSections.orient(target,first==start);
      if(previous!=null&&previous.sameDerivedLaneEnd(s,start,target))continue;
      if(!RoadTransitions.compatible(s,target))return true;
    }return false;
  }
  public static boolean forced(CompoundTag t,String side){return t.contains("ForceJunction"+side)?t.getBoolean("ForceJunction"+side):t.getBoolean("ForceJunction");}
  public static boolean requiresJunction(CompoundTag t,Settings s){
    if(RoadProfile.catalog(s.style()).type()!=RoadProfile.Type.ORDINARY)return false;
    if(RoadTunnelFit.enabled(s)&&!t.getBoolean("CenterA")&&!t.getBoolean("CenterB")&&t.getInt("DegreeA")<2&&t.getInt("DegreeB")<2&&!forced(t,"A")&&!forced(t,"B"))return false;
    if(t.getBoolean("AutoJunction")||forced(t,"A")||forced(t,"B"))return true;
    for(String side:List.of("A","B"))if(incompatibleSection(t,side,s))return true;
    if(t.contains("CrossingCandidates")){var command=t.copy();command.put("Settings",RoadRecord.writeSettings(s));if(!AutoCrossings.hits(proposed(command),AutoCrossings.candidates(t)).isEmpty())return true;}
    return false;
  }
  private static boolean incompatibleSection(CompoundTag command,String side,Settings proposed){
    if(command.getInt("Degree"+side)==0||!command.contains("JoinSection"+side))return false;
    var candidate=RoadRecord.readSettings(command.getCompound("JoinSection"+side));
    if(command.contains("Road")&&RoadRecord.load(command.getCompound("Road"))
        .sameDerivedLaneEnd(proposed,side.equals("A"),candidate))return false;
    return !RoadTransitions.compatible(proposed,candidate);
  }
  private static boolean touches(RoadRecord r,BlockPos p){return r.a().equals(p)||r.b().equals(p);}
  public static CompoundTag center(RoadData d,BlockPos p){return d.junctions.values().stream().filter(t->t.getBoolean("Auto")&&t.getLong("CenterPos")==p.asLong()).findFirst().orElse(null);}
  public static CompoundTag ringAt(RoadData d,BlockPos p){return d.junctions.values().stream().filter(t->t.getBoolean("GeneratedRing")&&Arrays.stream(t.getLongArray("Ports")).anyMatch(v->v==p.asLong())).findFirst().orElse(null);}
  public static int degree(RoadData d,BlockPos p,UUID exclude){return (int)logical(d).values().stream().filter(r->!r.id().equals(exclude)&&touches(r,p)).count();}
  public static boolean handles(RoadData d,BlockPos a,BlockPos b,UUID id,boolean force){return d.streets.containsKey(id)||d.streets.values().stream().anyMatch(r->touches(r,a)||touches(r,b))||center(d,a)!=null||center(d,b)!=null||ringAt(d,a)!=null||ringAt(d,b)!=null||degree(d,a,id)>=2||degree(d,b,id)>=2||force&&(degree(d,a,id)>0||degree(d,b,id)>0);}
  public static void enrich(CompoundTag t,RoadData d,BlockPos a,BlockPos b,UUID id){
    CompoundTag graph=context(d,Set.of(a,b),false);t.put("JunctionGraph",graph);
    t.putInt("DegreeA",degree(d,a,id));t.putInt("DegreeB",degree(d,b,id));
    t.putBoolean("AutoJunction",handles(d,a,b,id,false));
    t.putBoolean("CenterA",center(d,a)!=null||ringAt(d,a)!=null);t.putBoolean("CenterB",center(d,b)!=null||ringAt(d,b)!=null);
    if(id==null&&!t.contains("ForceJunction")&&corner(d,a,b))t.putBoolean("ForceJunction",true);
    AutoCrossings.enrich(t,d,a,b,id,false);
  }
  private static boolean corner(RoadData d,BlockPos a,BlockPos b){
    V chord=new V(b.getX()-a.getX(),0,b.getZ()-a.getZ()).horizontalUnit();
    for(var p:List.of(a,b)){
      var links=logical(d).values().stream().filter(r->touches(r,p)).toList();
      if(links.size()!=1)continue;
      RoadRecord r=links.get(0);Mesh m=r.mesh();boolean first=r.a().equals(p);
      V away=(first?m.first():m.last()).left().left().mul(first?-1:1);
      V next=p.equals(a)?chord:chord.mul(-1);
      if(away.dot(next)>-.8660254)return true;
    }
    return false;
  }
  private static Set<UUID> borrowed(CompoundTag center){
    var ids=new LinkedHashSet<UUID>();
    for(Tag t:center.getList("SlipRoads",Tag.TAG_STRING))ids.add(UUID.fromString(t.getAsString()));
    return ids;
  }
  private static boolean plainApproach(RoadRecord road){
    if(!ordinary(road))return false;
    var o=road.settings().options();
    var lanes=o.lanePoints();
    return o.attachments().points().isEmpty()&&lanes.points().stream().allMatch(p->p.automatic()&&p.mergeLength()==0)
        &&lanes.points(List.of()).equals(LanePoints.Data.EMPTY)
        &&o.infrastructure().gantryEdits().stream().allMatch(RoadGantry.Edit::automatic)&&o.levelCrossings().isEmpty()
        &&o.streetscape().crossings().isEmpty()&&o.laneLines().isEmpty();
  }
  private static boolean continuous(End from,End next){
    if(!plainApproach(from.road())||!plainApproach(next.road())||!from.road().owner().equals(next.road().owner()))return false;
    var a=from.road().settings();var b=next.road().settings();
    if(a.structure()!=b.structure()||a.thickness()!=b.thickness()||a.options().leftTraffic()!=b.options().leftTraffic())return false;
    var aligned=RoadEndpointSections.orient(RoadData.authoredSection(b),from.first()!=next.first());
    if(!RoadTransitions.Section.of(RoadData.authoredSection(a)).equals(RoadTransitions.Section.of(aligned)))return false;
    // Generated junction arms are straight in plan. Never replace a curved or sloping continuation with a chord.
    V axis=from.away(0);var origin=from.origin();
    for(var end:List.of(from,next))for(var sample:end.mesh().samples()){
      if(Math.abs(sample.center().sub(origin).dot(axis.left()))>.05||Math.abs(sample.center().y()-origin.y())>.05
          ||Math.abs(sample.left().dot(axis.left()))<.9999)return false;
    }
    return next.away(0).dot(axis)>.9999&&next.origin().distance(from.at(from.mesh().length()).center())<.05;
  }
  /** Send optional continuations separately. They enter the edit only if the slip actually needs them. */
  private static ListTag slipCandidates(RoadData data,Map<UUID,RoadRecord> all,Collection<CompoundTag> centers,boolean structures){
    var result=new ListTag();
    if(centers.isEmpty())return result;
    var links=new HashMap<BlockPos,List<End>>();
    for(var r:all.values())for(boolean first:List.of(true,false)){
      var node=first?r.a():r.b();links.computeIfAbsent(node,k->new ArrayList<>()).add(new End(r,node,first));
    }
    for(var center:centers){
      if(center.getBoolean("GeneratedRing"))continue;
      var origin=BlockPos.of(center.getLong("CenterPos"));
      for(var start:links.getOrDefault(origin,List.of())){
        var current=start;var seen=new HashSet<UUID>();double length=0;
        while(seen.add(current.road().id())&&seen.size()<=32&&(length+=current.mesh().length())<256){
          var at=current.first()?current.road().b():current.road().a();
          var incident=links.getOrDefault(at,List.of());
          if(incident.size()!=2||center(data,at)!=null||ringAt(data,at)!=null)break;
          var next=incident.get(incident.get(0).road().id().equals(current.road().id())?1:0);
          if(!continuous(current,next))break;
          var edge=new CompoundTag();edge.putUUID("From",current.road().id());edge.putLong("At",at.asLong());
          edge.put("Road",structures?next.road().save():next.road().header());result.add(edge);
          var far=next.first()?next.road().b():next.road().a();
          if(center(data,far)!=null||ringAt(data,far)!=null){
            // A remote junction already owns its far approach. Leave that road to its own editor.
            result.remove(result.size()-1);break;
          }
          current=next;
        }
      }
    }
    return result;
  }
  public static CompoundTag context(RoadData d,Set<BlockPos> seeds){return context(d,seeds,true);}
  /** Client planning consumes alignment and topology; baked furniture stays server-side. */
  static CompoundTag context(RoadData d,Set<BlockPos> seeds,boolean structures){
    Map<UUID,RoadRecord> all=logical(d);Set<BlockPos> nodes=new HashSet<>(seeds);var centers=new LinkedHashMap<UUID,CompoundTag>();
    // Rebuild only the requested centers, retaining the trim at each unchanged far junction.
    for(var t:d.junctions.values())if(t.getBoolean("Auto")&&(borrowed(t).stream().map(all::get).filter(Objects::nonNull).anyMatch(r->seeds.contains(r.a())||seeds.contains(r.b()))||seeds.contains(BlockPos.of(t.getLong("CenterPos")))||Arrays.stream(t.getLongArray("Ports")).anyMatch(v->seeds.contains(BlockPos.of(v))))){centers.put(t.getUUID("Id"),t.copy());nodes.add(BlockPos.of(t.getLong("CenterPos")));for(long v:t.getLongArray("Ports"))nodes.add(BlockPos.of(v));}
    CompoundTag t=new CompoundTag();ListTag roads=new ListTag(),cs=new ListTag(),trims=new ListTag();
    var restore=new HashSet<UUID>();centers.values().forEach(c->restore.addAll(borrowed(c)));
    t.put("SlipNext",slipCandidates(d,all,centers.values(),structures));
    for(var r:all.values())if(restore.contains(r.id())||nodes.contains(r.a())||nodes.contains(r.b())){
      roads.add(structures?r.save():r.header());var live=d.index.roads.get(r.id());if(live==null)continue;CompoundTag trim=new CompoundTag();trim.putUUID("Id",r.id());Mesh full=r.mesh();
      if(!nodes.contains(r.a())&&(center(d,r.a())!=null||ringAt(d,r.a())!=null))trim.putDouble("Start",RoadQueries.horizontal(full,live.mesh.first().center()).sample().distance());
      if(!nodes.contains(r.b())&&(center(d,r.b())!=null||ringAt(d,r.b())!=null))trim.putDouble("End",full.length()-RoadQueries.horizontal(full,live.mesh.last().center()).sample().distance());trims.add(trim);
    }
    centers.values().forEach(cs::add);t.put("Streets",roads);t.put("Centers",cs);t.put("RemoteTrims",trims);t.putLongArray("ActiveNodes",nodes.stream().mapToLong(BlockPos::asLong).toArray());return t;
  }

  public static RoadRecord proposed(CompoundTag command){
    var s=RoadRecord.readSettings(command.getCompound("Settings"));
    Node a=RoadRecord.readNode(command.getCompound("StartNode")),b=RoadRecord.readNode(command.getCompound("EndNode"));
    if(command.getBoolean("LiveSectionA")&&command.contains("AutoA")){var n=RoadData.readHint(command.getCompound("AutoA")).node();a=new Node(n.position(),a.yaw(),a.grade());}
    if(command.getBoolean("LiveSectionB")&&command.contains("AutoB")){var n=RoadData.readHint(command.getCompound("AutoB")).node();b=new Node(n.position(),b.yaw(),b.grade());}
    if(command.getBoolean("LevelEnds")){a=RoadData.atGround(a,BlockPos.of(command.getLong("A")));b=RoadData.atGround(b,BlockPos.of(command.getLong("B")));}
    V chord=b.position().sub(a.position()).horizontalUnit();
    boolean freeA=free(command,"A"),freeB=free(command,"B");
    var joins=command.copy();if(freeA)joins.remove("JoinSectionA");if(freeB)joins.remove("JoinSectionB");s=RoadData.joinSections(s,joins);
    var ah=endpointHint(command,"A",a,chord,freeA);var bh=endpointHint(command,"B",b,chord,freeB);
    var previous=command.contains("Road")?RoadRecord.load(command.getCompound("Road")):null;
    var plan=previous==null?null:previous.retainedPlan(s,ah,bh,RoadRecord.readNode(command.getCompound("StartNode")),RoadRecord.readNode(command.getCompound("EndNode")));
    if(plan==null)plan=RoadPlanner.plan(ah,bh,s);
    var next=new RoadRecord(command.hasUUID("Id")?command.getUUID("Id"):UUID.nameUUIDFromBytes((command.getLong("A")+":"+command.getLong("B")).getBytes(StandardCharsets.UTF_8)),new UUID(0,0),BlockPos.of(command.getLong("A")),BlockPos.of(command.getLong("B")),s.mode()==Mode.CURVE?a:plan.start(),s.mode()==Mode.CURVE?b:plan.end(),plan.settings(),s.mode()==Mode.AUTO,4);
    if(previous!=null)next=previous.retainAlignment(next,s,RoadRecord.readNode(command.getCompound("StartNode")),RoadRecord.readNode(command.getCompound("EndNode")));
    return RoadCrossingTransitions.preview(next,command);
  }
  private static boolean free(CompoundTag command,String side){return command.getBoolean("Center"+side)||command.getInt("Degree"+side)>=2||forced(command,side)&&command.getInt("Degree"+side)>0
      ||incompatibleSection(command,side,RoadRecord.readSettings(command.getCompound("Settings")));}
  private static RoadPlanner.Hint endpointHint(CompoundTag command,String side,Node node,V chord,boolean center){
    var hint=command.contains("Auto"+side)?RoadData.readHint(command.getCompound("Auto"+side)):RoadPlanner.Hint.free(node);
    // Manual modes preserve the same endpoint headings as the ordinary road tool.
    if(!command.getCompound("Settings").getString("Mode").equals("AUTO"))return RoadPlanner.Hint.free(node);
    if(center||!hint.headingLocked()&&!hint.linked())return RoadPlanner.Hint.free(new Node(node.position(),RoadPlanner.yaw(chord),node.grade()));
    return new RoadPlanner.Hint(new Node(node.position(),hint.node().yaw(),command.getBoolean("LevelEnds")?0:hint.node().grade()),hint.headingLocked(),hint.gradeLocked(),hint.linked());
  }
  public static Draft preview(CompoundTag command){
    var graph=command.getCompound("JunctionGraph").copy();List<RoadRecord> streets=new ArrayList<>();for(Tag entry:graph.getList("Streets",Tag.TAG_COMPOUND))streets.add(RoadRecord.load((CompoundTag)entry));
    var proposal=proposed(command);
    // Editing a fragment must preview the same retained path used by server connect().
    var add=streets.stream().filter(r->r.id().equals(proposal.id())).findFirst().map(r->r.retainAlignment(proposal,RoadRecord.readSettings(command.getCompound("Settings")),RoadRecord.readNode(command.getCompound("StartNode")),RoadRecord.readNode(command.getCompound("EndNode")))).orElse(proposal);
    var hits=AutoCrossings.hits(add,AutoCrossings.candidates(command));graph=AutoCrossings.graph(command,hits);streets=streets(graph);
    streets.removeIf(r->r.id().equals(add.id()));streets.add(add);
    List<CompoundTag> centers=new ArrayList<>();for(Tag entry:graph.getList("Centers",Tag.TAG_COMPOUND))centers.add(((CompoundTag)entry).copy());
    for(BlockPos pos:List.of(add.a(),add.b()))if(free(command,pos.equals(add.a())?"A":"B")&&streets.stream().filter(r->touches(r,pos)).count()>=2&&centers.stream().noneMatch(t->t.getLong("CenterPos")==pos.asLong()))centers.add(config(pos,add.owner()));
    var split=AutoCrossings.split(streets,add,hits,centers);
    var draft=plan(split.roads(),centers,graph);
    return new Draft(draft.streets(),draft.roads(),draft.centers(),split.pieces());
  }
  public static Draft previewEdit(CompoundTag command){var graph=command.getCompound("JunctionGraph");var configs=centers(graph);for(var config:configs)if(config.getUUID("Id").equals(command.getUUID("Id")))config.put("Spec",command.getCompound("Spec").copy());return plan(streets(graph),configs,graph);}
  static CompoundTag config(BlockPos p,UUID owner){CompoundTag t=new CompoundTag();t.putBoolean("Auto",true);t.putBoolean("Forced",true);t.putLong("CenterPos",p.asLong());t.putUUID("Id",UUID.nameUUIDFromBytes(("node-junction:"+p.asLong()).getBytes(StandardCharsets.UTF_8)));t.putUUID("Owner",owner);return t;}
  private record End(RoadRecord road,BlockPos node,boolean first) { Mesh mesh(){return road.mesh();} V origin(){return (first?road.start():road.end()).position();} Sample at(double distance){Mesh m=mesh();return RoadStructures.sample(m,first?distance:m.length()-distance);} V away(double distance){return at(distance).left().left().mul(first?-1:1);} }
  private static JunctionSpec spec(V center,CompoundTag config,List<Arm> arms){
    if(config.contains("Spec")){var old=JunctionCodec.read(config.getCompound("Spec"));arms=new ArrayList<>(arms);for(Tag v:config.getList("ConnectorArms",Tag.TAG_COMPOUND)){var t=(CompoundTag)v;arms.add(RampJunctions.arm(old,LanePointCodec.position(t.getCompound("Mouth")),RoadRecord.readSettings(t.getCompound("Settings")),arms.size()));}return new JunctionSpec(center,old.kind(),old.leftTraffic(),old.cornerRadius(),old.islandRadius(),old.ringLanes(),old.ringLaneWidth(),old.thickness(),old.control(),old.greenSeconds(),old.yellowSeconds(),old.allRedSeconds(),old.timeOffset(),old.guides(),old.greenIsland(),old.outerRail(),arms,old.parallelApproaches());}
    return new JunctionSpec(center,Kind.INTERSECTION,config.getBoolean("LeftTraffic"),4,12,1,4,1,Control.NONE,20,3,1,0,true,true,arms);
  }
  private static Arm arm(End end,double distance,int i){Sample at=end.at(distance);V inward=end.away(distance).mul(-1);Node node=new Node(at.center(),RoadPlanner.yaw(inward),(end.first()?-1:1)*(end.first()?end.road().start():end.road().end()).grade());Settings external=RoadEndpointSections.orient(end.road().settings().taper(at.halfWidth()*2,at.halfWidth()*2),end.first());if(end.first()&&external.options().sidewalk().side()!=RoadSidewalks.Side.BOTH)external=external.options(external.options().sidewalk(external.options().sidewalk().side(external.options().sidewalk().side()==RoadSidewalks.Side.LEFT?RoadSidewalks.Side.RIGHT:RoadSidewalks.Side.LEFT)));var l=RoadProfile.layout(end.mesh(),at);double orientation=end.first()?-1:1;
    var dividers=l.dividers().stream().map(d->d*orientation).sorted().toList();
    double left=RoadProfile.curbExtent(l,at,orientation<0?-1:1);
    double right=RoadProfile.curbExtent(l,at,orientation<0?1:-1);
    var port=new RoadTransitions.Port(orientation<0?-l.motorMax():l.motorMin(),orientation<0?-l.motorMin():l.motorMax(),l.median(),dividers,Math.max(0,left),Math.max(0,right),orientation*l.medianCenter());
    external=external.options(external.options().ends(external.options().ends().port(port)));
    return JunctionSpec.arm(node,inward,external,!end.first(),i%8).attached(true);}
  public static Draft plan(List<RoadRecord> streets,List<CompoundTag> saved){return plan(streets,saved,new CompoundTag());}
  private static RoadTransitions.Section streetSection(RoadRecord own,BlockPos p,Map<BlockPos,List<RoadRecord>> links,Set<BlockPos> centers){
    var peers=links.getOrDefault(p,List.of());if(peers.size()!=2||centers.contains(p))return null;
    boolean first=own.a().equals(p);var ownMesh=own.caps(0).mesh();
    if(RoadEndpointSections.changed(ownMesh,first))return first?own.settings().options().ends().start():own.settings().options().ends().end();
    var other=peers.get(0).id().equals(own.id())?peers.get(1):peers.get(0);boolean otherFirst=other.a().equals(p);
    if(other.fragment()!=null||other.assembly()!=null||RoadEndpointSections.changed(other.mesh(),otherFirst))
      return RoadTransitions.Section.of(RoadEndpointSections.orient(RoadData.endpointSection(new RoadIndex.Built(other),p),first==otherFirst));
    return RoadTransitions.common(RoadData.authoredSection(own.settings()),RoadEndpointSections.orient(RoadData.authoredSection(other.settings()),first==otherFirst));
  }
  private static List<RoadRecord> normalizeStreetJoints(List<RoadRecord> streets,List<CompoundTag> saved,CompoundTag context) {
    var links=new HashMap<BlockPos,List<RoadRecord>>();var centers=new HashSet<BlockPos>();var active=new HashSet<BlockPos>();
    for(long p:context.getLongArray("ActiveNodes"))active.add(BlockPos.of(p));
    for(var t:saved){centers.add(BlockPos.of(t.getLong("CenterPos")));for(long p:t.getLongArray("Ports"))centers.add(BlockPos.of(p));}
    for(var r:streets)for(var p:List.of(r.a(),r.b()))links.computeIfAbsent(p,k->new ArrayList<>()).add(r);
    var result=new ArrayList<RoadRecord>();
    for(var r:streets){if(r.fragment()!=null){result.add(r);continue;}var ends=r.settings().options().ends();
      // Recompute active ends from authored profiles. Old automatic tapers are not inputs.
      // The far end outside this edit's graph remains exactly as saved.
      var a=!active.isEmpty()&&!active.contains(r.a())?ends.start():streetSection(r,r.a(),links,centers);
      var b=!active.isEmpty()&&!active.contains(r.b())?ends.end():streetSection(r,r.b(),links,centers);
      var settings=r.assembly()!=null?r.settings():RoadTransitions.ends(r.settings(),a,b);
      // A former free end may already include a half-block cap. Once it becomes a shared
      // port, remove that cap BEFORE trimming/baking alignment, not after it is irreversible.
      int caps=r.endCaps();
      if(links.get(r.a()).size()>1)caps&=~1;
      if(links.get(r.b()).size()>1)caps&=~2;
      var next=settings.equals(r.settings())?r:r.settings(settings);
      result.add(caps==next.endCaps()?next:next.caps(caps));
    }
    return result;
  }
  private static Draft plan(List<RoadRecord> streets,List<CompoundTag> saved,CompoundTag context){
    streets=normalizeStreetJoints(streets,saved,context);
    Map<BlockPos,List<End>> links=new LinkedHashMap<>();for(var r:streets){links.computeIfAbsent(r.a(),p->new ArrayList<>()).add(new End(r,r.a(),true));links.computeIfAbsent(r.b(),p->new ArrayList<>()).add(new End(r,r.b(),false));}
    Map<Long,CompoundTag> configs=new LinkedHashMap<>();for(var t:saved)configs.put(t.getLong("CenterPos"),t.copy());
    Set<Long> active=new HashSet<>();for(long point:context.getLongArray("ActiveNodes"))active.add(point);
    for(var entry:links.entrySet())if((active.isEmpty()||active.contains(entry.getKey().asLong()))&&entry.getValue().size()>=3){if(entry.getValue().size()>6)throw new IllegalArgumentException("同一端点最多连接六个方向");configs.computeIfAbsent(entry.getKey().asLong(),v->config(entry.getKey(),entry.getValue().get(0).road().owner()));}
    Map<UUID,double[]> trim=new HashMap<>();for(Tag t:context.getList("RemoteTrims",Tag.TAG_COMPOUND)){CompoundTag value=(CompoundTag)t;trim.put(value.getUUID("Id"),new double[]{value.getDouble("Start"),value.getDouble("End")});}var output=new ArrayList<RoadRecord>();var result=new ArrayList<CompoundTag>();
    var consumed=new HashSet<UUID>();
    var originalIds=new HashSet<UUID>();for(Tag t:context.getList("Streets",Tag.TAG_COMPOUND))originalIds.add(((CompoundTag)t).getUUID("Id"));
    var extended=new LinkedHashMap<UUID,RoadRecord>();for(var road:streets)extended.put(road.id(),road);
    for(var config:configs.values()){
      BlockPos centerPos=BlockPos.of(config.getLong("CenterPos"));boolean ring=config.getBoolean("GeneratedRing");
      var ends=new ArrayList<End>();if(ring){for(long port:config.getLongArray("Ports")){var at=links.getOrDefault(BlockPos.of(port),List.of());if(at.size()>1)throw new IllegalArgumentException("环岛每个接路点只能连接一条道路");ends.addAll(at);}}else ends.addAll(links.getOrDefault(centerPos,List.of()));
      if(!ring&&ends.size()+config.getList("ConnectorArms",Tag.TAG_COMPOUND).size()<2)continue;
      if(!ring&&ends.isEmpty())throw new IllegalArgumentException("请先删除依赖匝道，再删除路口最后的普通支路");
      if(ends.stream().anyMatch(e->RoadProfile.catalog(e.road().settings().style()).type()!=RoadProfile.Type.ORDINARY))
        throw new IllegalArgumentException("高速可渐变续接普通道路；多向平交请先接一段普通道路或使用立交");
      ends.sort(Comparator.comparing(e->e.road().id()));
      V center=ring?RoadRecord.readNode(config.getCompound("CenterNode")).position():ends.get(0).origin();
      for(End end:ends)if(Math.abs(end.origin().y()-center.y())>.01)throw new IllegalArgumentException("路口中心高程不一致；请把相接道路设为同高");
      var trial=new ArrayList<Arm>();for(int i=0;i<ends.size();i++){var end=ends.get(i);V outward=end.away(0);trial.add(arm(end,0,i).node(new Node(center.add(outward.mul(200)),RoadPlanner.yaw(outward.mul(-1)),0),outward.mul(-1),end.road().settings()));}
      double radius=JunctionPlanner.radius(spec(center,config,trial));
      var arms=new ArrayList<Arm>();ListTag armRoads=new ListTag();var previousIds=config.getList("ArmRoads",Tag.TAG_STRING);JunctionSpec previous=config.contains("Spec")?JunctionCodec.read(config.getCompound("Spec")):null;
      // A bypass starts upstream of the ordinary 16-block approach. Derive its
      // trim from the authored street graph on every edit, never from old cuts.
      if(previous!=null)for(int i=0;i<ends.size();i++)for(int k=0;k<previousIds.size()&&k<previous.arms().size();k++)
        if(previousIds.getString(k).equals(ends.get(i).road().id().toString()))trial.set(i,trial.get(i).slip(previous.arms().get(k).rightSlip()));
      if(previousIds.size()!=ends.size()){
        var changed=spec(center,config,trial);
        for(int i=0;i<trial.size();i++)if(trial.get(i).rightSlip()&&!JunctionSlips.unavailable(changed,i).isEmpty())trial.set(i,trial.get(i).slip(false));
      }
      var slipLayout=spec(center,config,trial);
      Set<Integer> usedPhases=new HashSet<>();if(previous!=null)for(int k=0;k<previousIds.size();k++){String key=previousIds.getString(k);if(ends.stream().anyMatch(e->e.road().id().toString().equals(key)))usedPhases.add(previous.arms().get(k).phase());}
      var slipRoads=new LinkedHashSet<UUID>();
      for(int i=0;i<ends.size();i++){
        End end=ends.get(i);double approach=Math.max(16,JunctionSlips.approachLength(slipLayout,i));
        if(previous!=null)for(int k=0;k<previousIds.size()&&k<previous.arms().size();k++)if(previousIds.getString(k).equals(end.road().id().toString())){Arm before=previous.arms().get(k);approach=Math.max(approach,before.crossingSetback()+(before.crosswalk()?before.crossingWidth()+before.stopGap():0)+6);}
        double offset=ring?end.origin().sub(center).horizontalLength():0;double cut=Math.max(12,radius+approach-offset);double available=end.mesh().length();
        double remaining=cut;End port=end;
        while(available<remaining+2){
          var at=port.first()?port.road().b():port.road().a();
          var fromId=port.road().id();
          var edge=context.getList("SlipNext",Tag.TAG_COMPOUND).stream().map(v->(CompoundTag)v)
              .filter(v->v.getLong("At")==at.asLong()&&fromId.equals(v.getUUID("From"))).findFirst();
          if(JunctionSlips.approachLength(slipLayout,i)==0||edge.isEmpty()||links.getOrDefault(at,List.of()).size()>2
              ||trim.containsKey(fromId)&&Arrays.stream(trim.get(fromId)).anyMatch(v->v>0)||!consumed.add(fromId))
            throw new IllegalArgumentException("接入口 "+(i+1)+" 的连续可用道路不足，请沿相同断面延长到至少 "+(int)Math.ceil(cut+2)+" 格");
          var candidate=RoadRecord.load(edge.get().getCompound("Road"));
          if(originalIds.contains(candidate.id())&&!extended.containsKey(candidate.id()))
            throw new IllegalArgumentException("相邻路段仍用于独立右转，请先关闭右转匝道");
          var road=extended.getOrDefault(candidate.id(),candidate);
          var next=new End(road,at,road.a().equals(at));
          if(!continuous(port,next))throw new IllegalArgumentException("右转接路的相邻断面或线形已改变");
          extended.put(road.id(),road);slipRoads.add(road.id());
          remaining=Math.max(0,remaining-available);port=next;available=port.mesh().length();
        }
        if(consumed.contains(port.road().id()))throw new IllegalArgumentException("相邻路口之间的右转接路范围发生重叠");
        Arm a=arm(port,remaining,i);int old=-1;for(int k=0;k<previousIds.size();k++)if(previousIds.getString(k).equals(end.road().id().toString()))old=k;
        if(previous!=null&&old>=0&&old<previous.arms().size()){
          Arm before=previous.arms().get(old);var lanes=new ArrayList<Lane>();for(int l=0;l<a.incoming();l++){
            Lane savedLane=l<before.lanes().size()?before.lanes().get(l):Lane.AUTO;var targets=new ArrayList<Integer>();for(int target:savedLane.targets()){int next=-1;if(target>=0&&target<previousIds.size())for(int k=0;k<ends.size();k++)if(previousIds.getString(target).equals(ends.get(k).road().id().toString()))next=k;targets.add(next);}lanes.add(new Lane(savedLane.mask(),targets,savedLane.targetLanes(),savedLane.splitLeft(),savedLane.leftPhase(),savedLane.controlRight()));
          }
          a=new Arm(a.endpoint(),a.inward(),a.external(),a.incoming(),a.outgoing(),a.width(),a.median(),a.medianKind(),a.cycleWidth(),a.curbWidth(),before.crosswalk(),before.crossingWidth(),before.crossingSetback(),before.stopGap(),before.phase(),lanes,true,before.advanceSign(),before.nameSign(),trial.get(i).rightSlip());
        }
        if(old<0){int phase=0;while(phase<7&&usedPhases.contains(phase))phase++;usedPhases.add(phase);a=new Arm(a.endpoint(),a.inward(),a.external(),a.incoming(),a.outgoing(),a.width(),a.median(),a.medianKind(),a.cycleWidth(),a.curbWidth(),a.crosswalk(),a.crossingWidth(),a.crossingSetback(),a.stopGap(),phase,a.lanes(),true,a.advanceSign(),a.nameSign(),a.rightSlip());}
        arms.add(a);armRoads.add(StringTag.valueOf(end.road().id().toString()));trim.computeIfAbsent(port.road().id(),v->new double[2])[port.first()?0:1]=remaining;
      }
      var borrowedRoads=new ListTag();slipRoads.forEach(id->borrowedRoads.add(StringTag.valueOf(id.toString())));config.put("SlipRoads",borrowedRoads);
      JunctionSpec current=spec(center,config,arms);
      // Preserve surviving per-lane choices when a branch is deleted; drop only unavailable turns.
      if(previousIds.size()!=ends.size()){
        var adjusted=new ArrayList<Arm>();
        for(int i=0;i<arms.size();i++){int availableMask=0;for(int j=0;j<arms.size();j++)if(arms.get(j).outgoing()>0)availableMask|=JunctionPlanner.turn(current,i,j);var lanes=new ArrayList<Lane>();for(Lane lane:arms.get(i).lanes()){int mask=lane.mask()<0?-1:lane.mask()&availableMask;lanes.add(new Lane(lane.mask()>0&&mask==0?-1:mask,lane.targets(),lane.targetLanes(),lane.splitLeft(),lane.leftPhase(),lane.controlRight()));}adjusted.add(arms.get(i).lanes(lanes));}
        adjusted.addAll(current.arms().subList(arms.size(),current.arms().size()));current=current.arms(adjusted);
      }

      var plan=JunctionPlanner.plan(current);UUID group=config.getUUID("Id"),owner=config.getUUID("Owner");
      for(int i=0;i<plan.pieces().size();i++){var piece=plan.pieces().get(i);Mesh m=piece.mesh();BlockPos armNode=piece.arm()>=0&&piece.arm()<ends.size()?ends.get(piece.arm()).node():piece.arm()>=ends.size()&&piece.arm()>=0?RampJunctions.at(current.arms().get(piece.arm()).endpoint().position()):centerPos;
        UUID id=UUID.nameUUIDFromBytes((group+":auto:"+i).getBytes(StandardCharsets.UTF_8));output.add(new RoadRecord(id,owner,armNode,centerPos,new Node(m.first().center(),0,0),new Node(m.last().center(),0,0),m.settings(),false,4).junction(group,new JunctionPlanner.Ref(current,i)));}
      for(Tag v:config.getList("ConnectorArms",Tag.TAG_COMPOUND))armRoads.add(StringTag.valueOf(((CompoundTag)v).getUUID("Road").toString()));
      config.put("Spec",JunctionCodec.write(current));config.put("ArmRoads",armRoads);config.putString("Kind","junction");long[] points=new long[current.arms().size()+1];points[0]=centerPos.asLong();for(int i=0;i<current.arms().size();i++)points[i+1]=i<ends.size()?ends.get(i).node().asLong():RampJunctions.at(current.arms().get(i).endpoint().position()).asLong();config.putLongArray("Points",points);result.add(config);
    }
    streets=new ArrayList<>(extended.values());
    for(var r:streets){if(consumed.contains(r.id()))continue;double[] cut=trim.getOrDefault(r.id(),new double[2]);if(cut[0]==0&&cut[1]==0){output.add(r);continue;}Mesh mesh=r.mesh();double from=cut[0],to=mesh.length()-cut[1];if(to-from<2)throw new IllegalArgumentException("相邻路口之间的道路太短，路口范围发生重叠");
      if(r.fragment()!=null){var first=RoadSplitGeometry.node(mesh,from);var last=RoadSplitGeometry.node(mesh,to);
        // Sign seats belong to the rebuilt junction layout. A previous seat can
        // straddle its new throat after deleting an arm; regenerate it downstream.
        var source=r.structures(r.structures().stream().filter(p->!p.model().equals("sr:junction_sign_pedestal")&&!p.model().equals("sr:junction_sign_seat")).toList());
        output.add(RoadSplitGeometry.junctionRange(source,from,to,r.id(),r.a(),r.b(),first,last,r.fragment().connection()));continue;}
      var samples=new ArrayList<Sample>();Sample first=RoadStructures.sample(mesh,from),last=RoadStructures.sample(mesh,to);samples.add(new Sample(first.center(),first.left(),0,first.halfWidth()));for(var s:mesh.samples())if(s.distance()>from+1e-7&&s.distance()<to-1e-7)samples.add(new Sample(s.center(),s.left(),s.distance()-from,s.halfWidth()));samples.add(new Sample(last.center(),last.left(),to-from,last.halfWidth()));
      var clippedSettings=r.settings().options(r.settings().options().ends(r.settings().options().ends().trim(from,mesh.length()-to)));var clipped=RoadRibbon.mesh(samples,clippedSettings);output.add(new RoadRecord(r.id(),r.owner(),r.a(),r.b(),new Node(first.center(),RoadPlanner.yaw(first.left().left().mul(-1)),r.start().grade()),new Node(last.center(),RoadPlanner.yaw(last.left().left().mul(-1)),r.end().grade()),clippedSettings,r.automatic(),r.clearance()).alignment(r.assembly(),clipped));
    }
    return new Draft(List.copyOf(streets),List.copyOf(output),List.copyOf(result));
  }

  private static List<RoadRecord> streets(CompoundTag graph){var list=new ArrayList<RoadRecord>();for(Tag t:graph.getList("Streets",Tag.TAG_COMPOUND))list.add(RoadRecord.load((CompoundTag)t));return list;}
  private static List<CompoundTag> centers(CompoundTag graph){var list=new ArrayList<CompoundTag>();for(Tag t:graph.getList("Centers",Tag.TAG_COMPOUND))list.add(((CompoundTag)t).copy());return list;}
  /** The graph is a replacement closure, not a list of user-edited roads. Include
   * the selected street's live pieces or the selected junction's own pieces only.
   * New neighbours and changed saved crossing surfaces still fail the history proof. */
  static Set<UUID> directlyEditedRoads(Draft draft,Set<UUID> streets,Set<UUID> junctions){
    var ids=new HashSet<UUID>();
    for(var id:streets)for(var road:selected(draft,id))ids.add(road.id());
    for(var road:draft.roads())if(road.assembly()!=null&&junctions.contains(road.assembly()))ids.add(road.id());
    return Set.copyOf(ids);
  }
  private static void commit(ServerLevel level,ServerPlayer player,CompoundTag before,Draft draft,List<RoadData.NodeMove> moves,Set<UUID> editedStreets,Set<UUID> editedJunctions){
    commit(level,player,before,draft,moves,editedStreets,editedJunctions,Map.of());
  }
  private static void commit(ServerLevel level,ServerPlayer player,CompoundTag before,Draft draft,List<RoadData.NodeMove> moves,Set<UUID> editedStreets,Set<UUID> editedJunctions,Map<UUID,List<RoadRecord>> pieces){
    var prepared=commitInput(level,player,before,draft,moves,pieces);
    var built=new ArrayList<>(prepared.built());var removed=new HashSet<>(prepared.removed());
    if(editedStreets.isEmpty()&&!editedJunctions.isEmpty()){
      // A junction-only edit cannot change a remote street profile. Keep the actual
      // record, furniture, metadata and world cells when its trimmed deck is unchanged.
      for(int i=0;i<built.size();i++){var next=built.get(i);var old=RoadData.get(level).index.roads.get(next.record.id());
        if(old!=null&&old.record.junction()==null&&next.record.junction()==null&&old.record.furniturePhase()!=null){
          var at=RoadQueries.horizontal(old.mesh,next.mesh.first().center()).sample();
          double delta=at.distance()+next.mesh.first().center().sub(at.center()).dot(at.left().left().mul(-1));
          built.set(i,new RoadIndex.Built(next.record.furniturePhase(old.record.furniturePhase().shift(delta))));
        }
      }
      built.removeIf(next->{var old=RoadData.get(level).index.roads.get(next.record.id());
        if(old==null||old.record.junction()!=null||next.record.junction()!=null||!sameDeck(old.mesh,next.mesh))return false;
        removed.remove(next.record.id());return true;});
    }
    RoadData.get(level).replaceAssembly(level,player,built,removed,prepared.selected(),moves,prepared.editing(directlyEditedRoads(draft,editedStreets,editedJunctions)));
    commitMetadata(RoadData.get(level),before,draft,pieces);RoadData.get(level).junctions.putAll(prepared.sideReceipts());
  }
  static boolean sameDeck(Mesh a,Mesh b){
    if(a.samples().size()!=b.samples().size())return false;
    for(int i=0;i<a.samples().size();i++){var x=a.samples().get(i);var y=b.samples().get(i);if(x.center().distance(y.center())>1e-7||x.left().distance(y.left())>1e-7||Math.abs(x.halfWidth()-y.halfWidth())>1e-7)return false;}
    return true;
  }
  private record CommitInput(List<RoadIndex.Built> built,Set<UUID> removed,Set<BlockPos> selected,Map<UUID,CompoundTag> sideReceipts,Set<UUID> preservedSideHosts){
    Set<UUID> editing(Set<UUID> selected){
      var direct=new HashSet<>(selected);direct.removeAll(preservedSideHosts);return direct;
    }
  }
  private static CommitInput commitInput(ServerLevel level,ServerPlayer player,CompoundTag before,Draft draft,List<RoadData.NodeMove> moves,Map<UUID,List<RoadRecord>> pieces){
    RoadData data=RoadData.get(level);Set<UUID> removed=new HashSet<>();for(var r:streets(before))removed.add(r.id());Set<UUID> oldGroups=new HashSet<>();for(var t:centers(before))oldGroups.add(t.getUUID("Id"));
    for(var r:data.index.roads.values())if(oldGroups.contains(r.record.assembly()))removed.add(r.record.id());
    for(var c:draft.centers())removed.addAll(borrowed(c));
    Set<BlockPos> selected=new HashSet<>();for(var r:draft.streets()){selected.add(r.a());selected.add(r.b());if(player!=null)RoadData.requireOwner(player,r.owner());}
    for(var t:draft.centers()){selected.add(BlockPos.of(t.getLong("CenterPos")));for(long port:t.getLongArray("Ports"))selected.add(BlockPos.of(port));}
    for(var move:moves)if(move.from()!=null)selected.add(move.from());
    var built=new ArrayList<RoadIndex.Built>();for(var r:draft.roads())built.add(new RoadIndex.Built(AutoCrossings.remap(r,pieces)));
    for(var old:data.index.roads.values())if(!removed.contains(old.record.id())){var next=AutoCrossings.remap(old.record,pieces);if(next!=old.record){removed.add(next.id());built.add(new RoadIndex.Built(next));}}
    var preserved=new HashSet<UUID>();var sideReceipts=ConnectedSideJunctions.rebase(level,built,removed,preserved);
    for(var r:built)for(var old:data.index.roads.values())if(!removed.contains(old.record.id())&&!RoadInteractions.deferredLaneContact(r.record,old.record)&&RoadIndex.overlapXZ(r.mesh,old.mesh,1))Interchanges.checkExternal(r.mesh,old.mesh,data.index.roads.containsKey(r.record.id())?data.index.roads.get(r.record.id()).mesh:null);
    return new CommitInput(built,removed,selected,sideReceipts,Set.copyOf(preserved));
  }
  private static void commitMetadata(RoadData data,CompoundTag before,Draft draft,Map<UUID,List<RoadRecord>> pieces){
    for(var r:streets(before))data.streets.remove(r.id());for(var r:draft.streets())if(r.assembly()==null||draft.centers().stream().anyMatch(c->touches(r,BlockPos.of(c.getLong("CenterPos")))))data.streets.put(r.id(),r);
    for(var old:centers(before))data.junctions.remove(old.getUUID("Id"));draft.centers().forEach(t->data.junctions.put(t.getUUID("Id"),t.copy()));RampJunctions.sync(data);for(var r:draft.streets()){var actual=data.index.roads.get(r.id());if(actual!=null&&data.streets.containsKey(r.id()))data.streets.put(r.id(),r.withLanePoints(ElevatedAccessRoads.logicalLanes(r,actual.record)));}data.setDirty();
    data.streets.replaceAll((id,r)->AutoCrossings.remap(r,pieces));
    for(var entry:pieces.entrySet()){var owners=new HashMap<Integer,UUID>();for(var piece:entry.getValue())for(int slot:RoadGantry.slots(piece.mesh()))owners.put(slot,piece.id());data.smartSigns.splitKeys(entry.getKey(),owners);}
  }
  static void prepareStreet(ServerLevel level,ServerPlayer player,StreetChange change,UUID token){
    var input=commitInput(level,player,change.before(),change.draft(),change.moves(),Map.of());
    var data=RoadData.get(level);var prepared=data.prepareAssembly(level,player,input.built(),input.removed(),input.selected(),change.moves(),token,input.editing(directlyEditedRoads(change.draft(),Set.of(change.added()),Set.of())),false);
    // The complete footprint is part of preparation too. Lazy native rasters
    // must not unexpectedly consume the next tick's world-validation budget.
    var oldIds=new HashSet<>(input.removed());
    for(var road:prepared){RoadPlanningBudget.check();road.cells.size();road.clearanceCells.size();oldIds.add(road.record.id());}
    for(var id:oldIds){var old=data.index.roads.get(id);if(old!=null){RoadPlanningBudget.check();old.cells.size();old.clearanceCells.size();}}
  }
  static void commitPreparedStreet(ServerLevel level,ServerPlayer player,StreetChange change,UUID token){
    var input=commitInput(level,player,change.before(),change.draft(),change.moves(),Map.of());
    RoadData.get(level).buildPrepared(level,player,token);
    commitMetadata(RoadData.get(level),change.before(),change.draft(),Map.of());RoadData.get(level).junctions.putAll(input.sideReceipts());
  }
  public static RoadRecord connect(ServerLevel level,ServerPlayer player,BlockPos a,BlockPos b,Settings settings,UUID id,boolean force,boolean levelEnds){return connect(level,player,a,b,settings,id,force,force,levelEnds);}
  public static RoadRecord connect(ServerLevel level,ServerPlayer player,BlockPos a,BlockPos b,Settings settings,UUID id,boolean forceA,boolean forceB,boolean levelEnds){return connect(level,player,a,b,settings,id,forceA,forceB,levelEnds,List.of());}
  public static RoadRecord connect(ServerLevel level,ServerPlayer player,BlockPos a,BlockPos b,Settings settings,UUID id,boolean forceA,boolean forceB,boolean levelEnds,List<BlockPos> controlNodes){
    if(a.equals(b))throw new IllegalArgumentException("请选择两个不同的端点");
    if(Math.hypot((double)a.getX()-b.getX(),(double)a.getZ()-b.getZ())>RoadLimits.MAX_ENDPOINT_DISTANCE)throw new IllegalArgumentException("端点距离超过 2048 格");
    var data=RoadData.get(level);var na=RoadData.requireNode(level,a,player);var nb=RoadData.requireNode(level,b,player);CompoundTag graph=context(data,Set.of(a,b));var roads=streets(graph);var configs=centers(graph);
    for(var r:roads)if(!r.id().equals(id)&&(r.a().equals(a)&&r.b().equals(b)||r.a().equals(b)&&r.b().equals(a)))throw new IllegalArgumentException("这两个端点已经有道路");
    if(id!=null&&roads.stream().anyMatch(r->r.id().equals(id)&&r.assembly()!=null))throw new IllegalArgumentException("组合道路请使用对应生成器整体编辑");
    if(id!=null&&roads.stream().noneMatch(r->r.id().equals(id)))throw new IllegalArgumentException("道路已不存在");
    var command=new CompoundTag();command.putLong("A",a.asLong());command.putLong("B",b.asLong());command.put("StartNode",RoadRecord.writeNode(data.connectionNode(level,a,true,id,nb.constructionNode().position())));command.put("EndNode",RoadRecord.writeNode(data.connectionNode(level,b,false,id,na.constructionNode().position())));command.put("Settings",RoadRecord.writeSettings(settings));command.putBoolean("LevelEnds",levelEnds);command.putUUID("Id",id==null?UUID.randomUUID():id);
    if(id!=null)command.put("Road",roads.stream().filter(r->r.id().equals(id)).findFirst().orElseThrow().header());
    command.putBoolean("ForceJunctionA",forceA);command.putBoolean("ForceJunctionB",forceB);command.putInt("DegreeA",degree(data,a,id));command.putInt("DegreeB",degree(data,b,id));command.putBoolean("CenterA",center(data,a)!=null||ringAt(data,a)!=null);command.putBoolean("CenterB",center(data,b)!=null||ringAt(data,b)!=null);
    command.put("AutoA",RoadData.writeHint(data.constructionHint(level,a,true,id,nb.constructionNode().position(),levelEnds)));command.put("AutoB",RoadData.writeHint(data.constructionHint(level,b,false,id,na.constructionNode().position(),levelEnds)));data.jointPayload(command,a,b,id);
    RoadRecord next=proposed(command);UUID owner=player==null?new UUID(0,0):player.getUUID();if(id!=null)owner=roads.stream().filter(r->r.id().equals(id)).findFirst().orElseThrow().owner();
    next=new RoadRecord(next.id(),owner,a,b,next.start(),next.end(),next.settings(),next.automatic(),4);if(id!=null){for(var old:roads)if(old.id().equals(id))next=old.retainAlignment(next,settings,na.constructionNode(),nb.constructionNode());}
    return connectRecord(level,player,next,command,controlNodes);
  }
  static RoadRecord connectRecord(ServerLevel level,ServerPlayer player,RoadRecord next,CompoundTag command,List<BlockPos> controlNodes){
    var data=RoadData.get(level);command=command.copy();command.put("JunctionGraph",context(data,Set.of(next.a(),next.b())));AutoCrossings.enrich(command,data,next.a(),next.b(),next.id());
    next=RoadCrossingTransitions.fit(next,RoadCrossingTransitions.candidates(data));
    var fitted=next;var hits=AutoCrossings.hits(next,AutoCrossings.candidates(command));var graph=AutoCrossings.graph(command,hits);var roads=streets(graph);var configs=centers(graph);roads.removeIf(r->r.id().equals(fitted.id()));roads.add(next);
    for(BlockPos pos:List.of(next.a(),next.b()))if(command.contains("Settings")&&free(command,pos.equals(next.a())?"A":"B")&&roads.stream().filter(r->touches(r,pos)).count()>=2&&configs.stream().noneMatch(t->t.getLong("CenterPos")==pos.asLong()))configs.add(config(pos,next.owner()));
    var split=AutoCrossings.split(roads,next,hits,configs);var moves=new ArrayList<>(split.nodes());controlNodes.forEach(p->moves.add(RoadData.NodeMove.retire(p)));var edited=new HashSet<UUID>();edited.add(next.id());for(var pieces:split.pieces().values())pieces.forEach(r->edited.add(r.id()));
    Draft draft=plan(split.roads(),configs,graph);commit(level,player,graph,draft,moves,edited,Set.of(),split.pieces());var live=data.index.roads.get(next.id());return live==null?data.streets.get(next.id()):live.record;
  }
  public static void removeStreet(ServerLevel level,ServerPlayer player,UUID id){var data=RoadData.get(level);var old=data.streets.get(id);if(old==null)throw new IllegalArgumentException("道路已不存在");if(player!=null)RoadData.requireOwner(player,old.owner());var graph=context(data,Set.of(old.a(),old.b()));var roads=streets(graph);roads.removeIf(r->r.id().equals(id));commit(level,player,graph,plan(roads,centers(graph),graph),List.of(),Set.of(id),Set.of());}
  public static CompoundTag payload(ServerLevel level,ServerPlayer player,UUID id){var data=RoadData.get(level);var saved=data.junctions.get(id);if(saved==null)throw new IllegalArgumentException("路口已不存在");if(player!=null)RoadData.requireOwner(player,saved.getUUID("Owner"));var t=saved.copy();t.put("JunctionGraph",context(data,Set.of(BlockPos.of(t.getLong("CenterPos")))));LaneDeletes.junctionPayload(data,id,t);return t;}
  public static String edit(ServerLevel level,ServerPlayer player,CompoundTag command){var data=RoadData.get(level);var current=payload(level,player,command.getUUID("Id"));var graph=current.getCompound("JunctionGraph");var configs=centers(graph);var requested=JunctionCodec.read(command.getCompound("Spec"));
    var original=JunctionCodec.read(current.getCompound("Spec"));if(requested.kind()!=original.kind()||!requested.center().equals(original.center())||requested.arms().size()!=original.arms().size())throw new IllegalArgumentException("连接道路已变化，请重新打开路口");
    if(current.getBoolean("GeneratedRing")&&(requested.islandRadius()!=original.islandRadius()||requested.ringLanes()!=original.ringLanes()||requested.ringLaneWidth()!=original.ringLaneWidth()))throw new IllegalArgumentException("环岛尺寸与八个接路点绑定；请删除环岛后用生成器重设尺寸");
    for(var c:configs)if(c.getUUID("Id").equals(command.getUUID("Id")))c.put("Spec",command.getCompound("Spec").copy());
    commit(level,player,graph,plan(streets(graph),configs,graph),List.of(),Set.of(),Set.of(command.getUUID("Id")));return "路口设置已保存";
  }
  public static void removeCenter(ServerLevel level,ServerPlayer player,UUID id){try(var work=RoadWorkChunks.open(level)){var current=payload(level,player,id);var graph=current.getCompound("JunctionGraph");var roads=streets(graph);BlockPos center=BlockPos.of(current.getLong("CenterPos"));Set<Long> ports=new HashSet<>();for(long p:current.getLongArray("Ports"))ports.add(p);
    if(current.getBoolean("GeneratedRing")&&roads.stream().anyMatch(r->ports.contains(r.a().asLong())||ports.contains(r.b().asLong())))throw new IllegalArgumentException("请先删除环岛接出的道路，再删除环岛");
    if(current.getBoolean("GeneratedRing"))for(long point:ports){BlockPos pos=BlockPos.of(point);work.node(pos);if(level.getBlockEntity(pos) instanceof NodeEntity)RoadData.requireNode(level,pos,player);}
    if(roads.stream().anyMatch(r->r.assembly()!=null&&touches(r,center)))throw new IllegalArgumentException("此路口连接了道路组合；请用拆路器删除外接支路，组合道路会自动恢复");
    roads.removeIf(r->touches(r,center));var configs=centers(graph);configs.removeIf(t->t.getUUID("Id").equals(id));commit(level,player,graph,plan(roads,configs,graph),List.of(),Set.of(),Set.of(id));
    if(current.getBoolean("GeneratedRing"))for(long point:ports){BlockPos pos=BlockPos.of(point);if(!RoadData.get(level).linked(pos)&&level.getBlockEntity(pos) instanceof NodeEntity)level.removeBlock(pos,false);}
  }}
  public static void createRing(ServerLevel level,ServerPlayer player,BlockPos pos,JunctionSpec spec){
    UUID owner=player==null?new UUID(0,0):player.getUUID();CompoundTag c=config(pos,owner);c.putBoolean("GeneratedRing",true);c.put("Spec",JunctionCodec.write(spec));c.put("CenterNode",RoadRecord.writeNode(new Node(spec.center(),0,0)));var ports=RoundaboutTool.ports(spec);long[] positions=new long[ports.size()];var moves=new ArrayList<RoadData.NodeMove>();
    for(int i=0;i<ports.size();i++){Node n=ports.get(i);BlockPos at=BlockPos.containing(n.position().x(),n.position().y(),n.position().z());positions[i]=at.asLong();CompoundTag snapshot=new CompoundTag();snapshot.putUUID("Owner",owner);snapshot.put("Node",RoadRecord.writeNode(n));snapshot.putBoolean("HeightExplicit",true);moves.add(new RoadData.NodeMove(null,at,n,snapshot));}c.putLongArray("Ports",positions);
    commit(level,player,new CompoundTag(),plan(List.of(),List.of(c)),moves,Set.of(),Set.of(c.getUUID("Id")));
  }
  private AutoJunctions(){}
}
