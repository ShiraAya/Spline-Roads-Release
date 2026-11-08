package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.StreetPlan.Plan;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** Converts a checked street graph into editable native logical roads and junctions. */
public final class StreetNetworkGeometry {
  public static List<RoadRecord> streets(Plan plan,UUID task,UUID owner){
    if(!plan.buildable())throw new IllegalArgumentException("请先解决路网预览中的冲突");
    var roads=new ArrayList<RoadRecord>();var options=plan.input().options();
    for(var edge:plan.edges()){
      var a=plan.vertices().get(edge.a());var b=plan.vertices().get(edge.b());
      V av=new V(a.point().x(),a.height(),a.point().z()),bv=new V(b.point().x(),b.height(),b.point().z());
      double yaw=RoadPlanner.yaw(bv.sub(av));
      var settings=new Settings(edge.bend()==0?Mode.STRAIGHT:Mode.CURVE,options.section(edge.rank()),options.width(edge.rank()),1,.4,90).structure(Structure.GROUND);
      settings=settings.options(settings.options().traffic(options.left()).streetscape(settings.options().streetscape().lamps(options.lighting())).sidewalk(RoadSidewalks.Config.DEFAULT.width(2).enabled(options.sidewalks())));
      UUID id=UUID.nameUUIDFromBytes((task+":street:"+edge.a()+":"+edge.b()).getBytes(StandardCharsets.UTF_8));
      var road=new RoadRecord(id,owner,BlockPos.containing(av.x(),av.y(),av.z()),BlockPos.containing(bv.x(),bv.y(),bv.z()),new Node(av,yaw,0),new Node(bv,yaw,0),settings,false,4);
      if(edge.bend()!=0){
        var samples=new ArrayList<Sample>();int steps=Math.max(2,(int)Math.ceil(a.point().distance(b.point())/.5));
        for(int i=0;i<=steps;i++){double t=(double)i/steps;var p=StreetCurves.point(plan,edge,t);double y=a.height()+(b.height()-a.height())*t*t*(3-2*t);
          samples.add(new Sample(new V(p.x(),y,p.z()),StreetCurves.direction(a.point(),b.point(),edge.bend(),t).left(),0,settings.width()/2));}
        road=road.alignment(null,RoadRibbon.mesh(samples,settings));
      }
      roads.add(road);
    }
    return List.copyOf(roads);
  }
  public static AutoJunctions.Draft plan(Plan plan,UUID task,UUID owner){
    var streets=streets(plan,task,owner);var ends=new LinkedHashMap<BlockPos,List<RoadRecord>>();
    for(var r:streets)for(var p:List.of(r.a(),r.b()))ends.computeIfAbsent(p,k->new ArrayList<>()).add(r);
    var configs=new ArrayList<CompoundTag>();
    for(var entry:ends.entrySet()){
      var linked=entry.getValue();if(linked.size()>4)throw new IllegalArgumentException("首版只支持三向或四向平交");
      boolean junction=linked.size()>=3;
      if(linked.size()==2){
        var a=away(linked.get(0),entry.getKey());var b=away(linked.get(1),entry.getKey());
        // A native two-arm corner closes the inside pavement and carries the
        // sidewalks. Collinear degree-two connections remain ordinary joints.
        junction=a.dot(b)>-.999;
      }
      if(junction){var config=AutoJunctions.config(entry.getKey(),owner);config.putBoolean("LeftTraffic",plan.input().options().left());configs.add(config);}
    }
    var draft=AutoJunctions.plan(streets,configs);checkGrades(draft.roads());return draft;
  }
  static void checkGrades(List<RoadRecord> roads){
    for(var road:roads){var samples=road.mesh().samples();for(int i=1;i<samples.size();i++){
      var delta=samples.get(i).center().sub(samples.get(i-1).center());
      if(Math.abs(delta.y())>Math.max(.0001,delta.horizontalLength()*.08001))throw new IllegalArgumentException("原生路口引道或转弯纵坡超过缓坡上限，请降低高差或扩大街坊");
    }}
  }
  private static V away(RoadRecord r,BlockPos p){V d=r.end().position().sub(r.start().position());return d.mul((r.a().equals(p)?1:-1)/d.horizontalLength());}
  static List<RoadRecord> connectedOrder(List<RoadRecord> roads){return connectedOrder(roads,List.of());}
  /** Resume older drafts without rebuilding completed units. Boundary clipping
   * once left dangling roads whose width overlapped a different road's interior. */
  static List<RoadRecord> prunePendingSpurs(List<RoadRecord> roads,int completed){
    var result=new ArrayList<>(roads);var protectedIds=new HashSet<UUID>();for(int i=0;i<completed;i++)protectedIds.add(roads.get(i).id());
    boolean changed;do{changed=false;var degree=new HashMap<BlockPos,Integer>();for(var r:result){degree.merge(r.a(),1,Integer::sum);degree.merge(r.b(),1,Integer::sum);}
      outer:for(var r:result)if(!protectedIds.contains(r.id()))for(boolean first:new boolean[]{true,false}){
        var pos=first?r.a():r.b();if(degree.get(pos)!=1)continue;V end=(first?r.start():r.end()).position();
        for(var other:result)if(!r.id().equals(other.id())&&!r.a().equals(other.a())&&!r.a().equals(other.b())&&!r.b().equals(other.a())&&!r.b().equals(other.b())){
          var near=RoadQueries.horizontal(other.mesh(),end);double gap=(r.settings().width()+other.settings().width())/2+1;
          if(end.sub(near.sample().center()).horizontalLength()<gap&&Math.abs(end.y()-near.sample().center().y())<1){result.remove(r);changed=true;break outer;}
        }
      }
    }while(changed);
    return List.copyOf(result);
  }
  static List<RoadRecord> connectedOrder(List<RoadRecord> roads,List<BlockPos> anchors){
    var remaining=new ArrayList<>(roads);remaining.sort(Comparator.comparingDouble((RoadRecord r)->Math.min(r.start().position().x(),r.end().position().x())).thenComparingDouble(r->Math.min(r.start().position().z(),r.end().position().z())).thenComparingDouble(r->Math.max(r.start().position().x(),r.end().position().x())).thenComparingDouble(r->Math.max(r.start().position().z(),r.end().position().z())));var output=new ArrayList<RoadRecord>();var reached=new HashSet<BlockPos>();
    for(var anchor:anchors)if(remaining.stream().anyMatch(r->r.a().equals(anchor)||r.b().equals(anchor))){reached.add(anchor);break;}
    while(!remaining.isEmpty()){
      int at=0;if(!reached.isEmpty()){at=-1;for(int i=0;i<remaining.size();i++)if(reached.contains(remaining.get(i).a())||reached.contains(remaining.get(i).b())){at=i;break;}if(at<0)throw new IllegalArgumentException("规划含有未连接片区，请重新规划");}
      var r=remaining.remove(at);output.add(r);reached.add(r.a());reached.add(r.b());
    }
    return List.copyOf(output);
  }
  private StreetNetworkGeometry(){}
}
