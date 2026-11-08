package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

/** Extra incoming arms are real JunctionPlanner inputs, including on generated roundabouts. */
final class RampJunctions {
  static JunctionSpec spec(RoadData data,UUID id){var t=data.junctions.get(id);if(t==null||data.interchanges.containsKey(id))throw new IllegalArgumentException("所选路口不存在或属于自动立交");return JunctionCodec.read(t.getCompound("Spec"));}
  static V mouth(RoadData data,UUID id,V source,Settings settings){var s=spec(data,id);V radial=source.sub(s.center());if(radial.horizontalLength()<1)throw new IllegalArgumentException("起点过于靠近路口中心");double angle=Math.atan2(radial.z(),radial.x());double distance=Math.max(JunctionPlanner.radius(s)+22,s.arms().stream().mapToDouble(a->a.endpoint().position().sub(s.center()).horizontalLength()).max().orElse(30));
    for(double delta:new double[]{0,30,-30,60,-60,90,-90,120,-120,180}){double t=angle+Math.toRadians(delta);V p=s.center().add(new V(Math.cos(t),0,Math.sin(t)).mul(distance));try{var arms=new ArrayList<>(s.arms());arms.add(arm(s,p,settings,arms.size()));JunctionPlanner.plan(s.arms(arms));return p;}catch(IllegalArgumentException ignored){}}
    throw new IllegalArgumentException("路口没有可用接入口角度或空间；请调整起点或现有支路");}
  static JunctionSpec.Arm arm(JunctionSpec s,V mouth,Settings input,int phase){V inward=s.center().sub(mouth).horizontalUnit();Settings external=new Settings(Mode.STRAIGHT,Style.O1_ONE,Math.max(4,input.width()),input.thickness(),.35,90).options(RoadProfile.Options.DEFAULT.traffic(s.leftTraffic()));
    var a=JunctionSpec.arm(new Node(mouth,RoadPlanner.yaw(inward),0),inward,external,true,Math.min(7,phase));return new JunctionSpec.Arm(a.endpoint(),a.inward(),a.external(),1,0,a.width(),0,RoadProfile.Median.NONE,0,0,false,4,3,1.5,a.phase(),List.of(JunctionSpec.Lane.AUTO),true);}
  static void reconcile(RoadData data,Map<UUID,RoadRecord> all){reconcile(data,all,null);}
  static void reconcile(RoadData data,Map<UUID,RoadRecord> all,Set<UUID> scope){Set<UUID> groups=new HashSet<>();for(var r:all.values()){var l=LaneTopology.metadata(r).link();if(l!=null&&l.to().junction()!=null)groups.add(l.to().junction());}for(var b:data.index.roads.values()){var l=LaneTopology.metadata(b.record).link();if(l!=null&&l.to().junction()!=null)groups.add(l.to().junction());}
    for(var group:groups){
      if(scope!=null){boolean touched=false;for(UUID id:scope){var r=all.get(id);var old=data.index.roads.get(id);for(var candidate:Arrays.asList(r,old==null?null:old.record)){if(candidate==null)continue;var link=LaneTopology.metadata(candidate).link();if(group.equals(candidate.assembly())||link!=null&&group.equals(link.to().junction()))touched=true;}}if(!touched)continue;}
      var pieces=all.values().stream().filter(r->group.equals(r.assembly())&&r.junction()!=null).toList();var links=all.values().stream().filter(r->{var l=LaneTopology.metadata(r).link();return l!=null&&group.equals(l.to().junction());}).sorted(Comparator.comparing(RoadRecord::id)).toList();if(pieces.isEmpty()){if(!links.isEmpty())throw new IllegalArgumentException("路口仍被匝道引用，请确认级联删除");continue;}
      JunctionSpec base=pieces.get(0).junction().spec();Set<V> previous=new HashSet<>();for(var b:data.index.roads.values()){var l=LaneTopology.metadata(b.record).link();if(l!=null&&group.equals(l.to().junction()))previous.add(l.junctionMouth());}for(var r:links)previous.add(LaneTopology.metadata(r).link().junctionMouth());
      var arms=new ArrayList<>(base.arms().stream().filter(a->previous.stream().noneMatch(p->p.distance(a.endpoint().position())<.01)).toList());
      for(var r:links)arms.add(arm(base,LaneTopology.metadata(r).link().junctionMouth(),r.settings(),arms.size()));var next=base.arms(arms);if(next.equals(base))continue;
      var plan=JunctionPlanner.plan(next);for(var piece:plan.pieces())for(var other:all.values())if(!group.equals(other.assembly())){var connection=LaneTopology.metadata(other).link();if(connection!=null&&group.equals(connection.to().junction()))continue;if(RoadIndex.overlapXZ(piece.mesh(),other.mesh(),1))Interchanges.checkExternal(piece.mesh(),other.mesh());}var first=pieces.get(0);BlockPos center=first.b();all.values().removeIf(r->group.equals(r.assembly())&&r.junction()!=null);
      for(int i=0;i<plan.pieces().size();i++){var p=plan.pieces().get(i);Mesh m=p.mesh();BlockPos start=p.arm()>=0?at(next.arms().get(p.arm()).endpoint().position()):center;var id=UUID.nameUUIDFromBytes((group+":lane-junction:"+i).getBytes(StandardCharsets.UTF_8));all.put(id,new RoadRecord(id,first.owner(),start,center,RoadRibbon.start(m),RoadRibbon.end(m),m.settings(),false,4).junction(group,new JunctionPlanner.Ref(next,i)));}
    }
  }
  static BlockPos at(V p){return BlockPos.containing(p.x(),p.y(),p.z());}
  /** Merge added arms back into descriptors after each caller commits its normal junction graph. */
  static void sync(RoadData data){sync(data,data.junctions.keySet());}
  static void sync(RoadData data,Collection<UUID> groups){for(UUID group:groups){var t=data.junctions.get(group);if(t==null)continue;var built=data.index.roads.values().stream().filter(b->group.equals(b.record.assembly())&&b.record.junction()!=null).findFirst().orElse(null);if(built==null)continue;var s=built.record.junction().spec();t.put("Spec",JunctionCodec.write(s));ListTag additions=new ListTag();for(var b:data.index.roads.values()){var l=LaneTopology.metadata(b.record).link();if(l!=null&&group.equals(l.to().junction())){var e=new CompoundTag();e.putUUID("Road",b.record.id());e.put("Mouth",LanePointCodec.position(l.junctionMouth()));e.put("Settings",RoadRecord.writeSettings(b.record.settings()));additions.add(e);}}t.put("ConnectorArms",additions);
      if(!t.getBoolean("Auto")){long[] points=new long[s.arms().size()+1];points[0]=built.record.b().asLong();for(int i=0;i<s.arms().size();i++)points[i+1]=at(s.arms().get(i).endpoint().position()).asLong();t.putLongArray("Points",points);}
    }}
  static JunctionSpec preserve(CompoundTag config,JunctionSpec base){var arms=new ArrayList<>(base.arms());for(Tag v:config.getList("ConnectorArms",Tag.TAG_COMPOUND)){var t=(CompoundTag)v;arms.add(arm(base,LanePointCodec.position(t.getCompound("Mouth")),RoadRecord.readSettings(t.getCompound("Settings")),arms.size()));}return base.arms(arms);}
  private RampJunctions(){}
}
