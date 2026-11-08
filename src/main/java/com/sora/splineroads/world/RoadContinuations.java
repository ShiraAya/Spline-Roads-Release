package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;

/** Transaction-local continuation repair. Follow actual OLD coincident road ports,
 * not merely nearby centerlines. Rebuild a changed end, leave the other end pinned,
 * and let the normal transaction validate all resulting decks before writing. */
public final class RoadContinuations {
  private static final class View {
    final IdentityHashMap<RoadRecord,Mesh> meshes=new IdentityHashMap<>();
    Mesh mesh(RoadRecord r){return meshes.computeIfAbsent(r,v->v.caps(0).mesh());}
  }
  private static boolean ordinary(View view,RoadRecord r){
    return r!=null&&r.junction()==null&&r.assembly()==null&&LaneTopology.metadata(r).link()==null
        &&RoadProfile.modern(r.settings().style())&&!r.settings().style().ramp()&&!view.mesh(r).closed();
  }
  private static Sample port(View view,RoadRecord r,boolean first){var m=view.mesh(r);return first?m.first():m.last();}
  private static boolean same(Sample a,Sample b){return a.center().distance(b.center())<1e-6&&Math.abs(a.halfWidth()-b.halfWidth())<1e-6&&Math.abs(a.left().dot(b.left()))>.999999;}
  public static void reconcile(Map<UUID,RoadRecord> before,Map<UUID,RoadRecord> all,Set<UUID> scope){
    var view=new View();
    var queue=new ArrayDeque<UUID>();var queued=new HashSet<UUID>();
    for(UUID id:new TreeSet<>(scope)){
      var old=before.get(id);var now=all.get(id);if(old==null||now==null)continue;
      // Merge and DETACH reservations are the only new authority for this pass.
      if(!LaneTopology.metadata(old).cuts().equals(LaneTopology.metadata(now).cuts())||!LaneTopology.metadata(old).additions().equals(LaneTopology.metadata(now).additions())){queue.add(id);queued.add(id);}
    }
    if(queue.isEmpty())return; // Ordinary builds with no lane changes need no graph or mesh reconstruction.
    var byNode=new HashMap<BlockPos,List<RoadRecord>>();
    for(var road:before.values()){
      byNode.computeIfAbsent(road.a(),v->new ArrayList<>()).add(road);
      if(!road.a().equals(road.b()))byNode.computeIfAbsent(road.b(),v->new ArrayList<>()).add(road);
    }
    for(var roads:byNode.values())roads.sort(Comparator.comparing(RoadRecord::id));
    int steps=0;
    while(!queue.isEmpty()){
      if(++steps>1024)throw new IllegalArgumentException("续接联动超过 1024 段，请分段修改；本次未写入道路");
      UUID id=queue.removeFirst();queued.remove(id);var old=before.get(id);var now=all.get(id);
      if(!ordinary(view,old)||!ordinary(view,now))continue;
      for(boolean first:new boolean[]{true,false}){
        var previous=port(view,old,first);var target=port(view,now,first);if(same(previous,target))continue;
        BlockPos node=first?now.a():now.b();
        for(var oldNext:byNode.getOrDefault(node,List.of())){
          if(oldNext.id().equals(id)||!ordinary(view,oldNext)||!oldNext.a().equals(node)&&!oldNext.b().equals(node))continue;
          boolean nextFirst=oldNext.a().equals(node);if(!same(previous,port(view,oldNext,nextFirst)))continue;
          var next=all.get(oldNext.id());if(!ordinary(view,next))continue;
          var current=port(view,next,nextFirst);if(same(current,target))continue;
          if(!now.owner().equals(next.owner()))throw new IllegalArgumentException("合并修改需要联动其他所有者的续接道路 "+next.id()+"，请先处理该接头");
          if(current.center().distance(previous.center())>1e-5)
            throw new IllegalArgumentException("相接道路两端同时发生不一致修改："+next.id()+"；不能覆盖另一个端点编辑");
          if(RoadEndpointSections.changed(view.mesh(next),nextFirst))
            throw new IllegalArgumentException("续接两侧均有独立的车道收窄约束，请分开调整合并点："+next.id());
          boolean reverse=current.left().dot(target.left())<0;
          var section=RoadEndpointSections.section(view.mesh(now),first,reverse);
          try{RoadTransitions.requireCompatible(next.settings(),section);}
          catch(IllegalArgumentException e){throw new IllegalArgumentException("原合并变化后续接断面需跨多级过渡："+e.getMessage(),e);}
          var ends=next.settings().options().ends();
          var settings=RoadTransitions.ends(next.settings(),nextFirst?RoadTransitions.Section.of(section):ends.start(),nextFirst?ends.end():RoadTransitions.Section.of(section));
          V dir=target.left().left().mul(reverse?1:-1);
          double grade=(first?now.start():now.end()).grade()*(reverse?-1:1);
          var pinned=new Node(RoadMedianAnchor.position(view.mesh(now),first),RoadPlanner.yaw(dir),grade);
          Node a=nextFirst?pinned:next.start(),b=nextFirst?next.end():pinned;
          var plan=RoadPlanner.plan(new RoadPlanner.Hint(a,true,true,true),new RoadPlanner.Hint(b,true,true,true),RoadPlanner.mode(settings,Mode.AUTO,settings.arcDegrees()));
          var moved=new RoadRecord(next.id(),next.owner(),next.a(),next.b(),a,b,plan.settings(),next.automatic(),next.clearance(),List.of(),next.endCaps(),next.buildVersion(),null,List.of(),next.furniturePhase(),null);
          var rebuiltPort=port(view,moved,nextFirst);
          if(!same(rebuiltPort,target))throw new IllegalArgumentException("续接联动不能保持精确端口，请调整相邻路段长度："+next.id());
          all.put(next.id(),moved);scope.add(next.id());
          if(queued.add(next.id()))queue.addLast(next.id());
        }
      }
    }
    // A connector attached to a moved continuation must be regenerated in this
    // transaction too, even when it was not part of the original edit's scope.
    boolean added;do{added=false;for(var r:all.values()){var l=LaneTopology.metadata(r).link();
      if(l!=null&&(scope.contains(l.from().road())||scope.contains(l.to().road())))added|=scope.add(r.id());
    }}while(added);
  }
  private RoadContinuations(){}
}
