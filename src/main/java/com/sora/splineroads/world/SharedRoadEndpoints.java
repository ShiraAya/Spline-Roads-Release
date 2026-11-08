package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import java.util.*;
/** Validates both sides and stages neighbor changes in the caller's single world transaction. */
final class SharedRoadEndpoints {
  static void validate(RoadData data,BlockPos at,Node target){
    validate(data,at,target,Set.of(),null);
  }
  private static void validate(RoadData data,BlockPos at,Node target,Set<UUID> replaced,V original){
    var candidates=data.index.atNode(at);
    var source=candidates.stream().filter(replaced::contains).map(id->data.index.roads.get(id).record).filter(r->original==null||(r.a().equals(at)?r.start():r.end()).position().distance(original)<1e-6).findFirst();
    V exact=original!=null?original:source.map(r->(r.a().equals(at)?r.start():r.end()).position()).orElse(null);
    var ids=candidates.stream().filter(id->!replaced.contains(id)||source.isPresent()&&id.equals(source.get().id())).filter(id->{var r=data.index.roads.get(id).record;return exact==null||(r.a().equals(at)?r.start():r.end()).position().distance(exact)<1e-6;}).toList();if(ids.isEmpty()||ids.size()==1&&original==null)return;
    if(ids.size()>2||AutoJunctions.center(data,at)!=null)throw new IllegalArgumentException("共享端点属于路口或分岔，不能自动移动");
    var roads=ids.stream().map(id->data.index.roads.get(id).record).toList();var a=roads.get(0);var b=roads.get(roads.size()-1);V shared=a.a().equals(at)?a.start().position():a.end().position();
    if(a.assembly()!=null&&!replaced.contains(a.id())||b.assembly()!=null&&!replaced.contains(b.id())||a.junction()!=null||b.junction()!=null||(roads.size()==1?!RoadContinuity.eligible(a.mesh()):!RoadContinuity.compatible(a.mesh(),b.mesh(),shared)))throw new IllegalArgumentException("共享端点邻路须为适用范围内共线、水平且分方向车道数相同的道路");
    V axis=a.end().position().sub(a.start().position()).horizontalUnit();
    if(Math.abs(target.position().y()-shared.y())>.001||Math.abs(target.position().sub(shared).dot(axis.left()))>.001)throw new IllegalArgumentException("共享端点只能沿原水平道路移动，不能引入折角或坡度");
    for(var r:roads){V far=r.a().equals(at)?r.end().position():r.start().position();if(target.position().sub(far).dot(shared.sub(far))<=0||target.position().sub(far).horizontalLength()<2)throw new IllegalArgumentException("共享端点不能越过外端点或使相邻道路不足两格");}
  }
  static void stage(RoadData data,ServerLevel level,ServerPlayer player,BlockPos at,Node target,List<RoadIndex.Built> built,Set<UUID> removed){
    stage(data,level,player,at,null,target,built,removed);
  }
  static void stage(RoadData data,ServerLevel level,ServerPlayer player,BlockPos at,Node original,Node target,List<RoadIndex.Built> built,Set<UUID> removed){
    // The block index is a broad phase: generated alignment ends can occupy the
    // same block without being the same construction endpoint. Only an exact
    // seam to a replaced road is an external connection that must move with it.
    var sources=data.index.atNode(at).stream().filter(removed::contains).map(id->data.index.roads.get(id).record)
        .map(r->(r.a().equals(at)?r.start():r.end()).position()).toList();
    var outside=data.index.atNode(at).stream().filter(id->!removed.contains(id)).filter(id->{
      var r=data.index.roads.get(id).record;V p=(r.a().equals(at)?r.start():r.end()).position();
      return original!=null?p.distance(original.position())<1e-6:!sources.isEmpty()&&sources.stream().anyMatch(s->s.distance(p)<1e-6);
    }).toList();if(outside.isEmpty())return;
    // An interchange refit normalizes heading/grade even when the position stays
    // put. That is not a move of the external neighbour (which may be curved).
    boolean moved=outside.stream().map(id->data.index.roads.get(id).record)
        .anyMatch(r->(r.a().equals(at)?r.start():r.end()).position().distance(target.position())>1e-6);
    if(!moved){
      for(UUID id:outside){var r=data.index.roads.get(id).record;if(r.fragment()==null)continue;var current=r.a().equals(at)?r.start():r.end();double yaw=Math.abs(Math.IEEEremainder(target.yaw()-current.yaw(),360));
        if(yaw>1e-4||Math.abs(target.grade()-current.grade())>1e-6)throw new IllegalArgumentException("分割共享端点的朝向和纵坡须同时调整两段；当前曲坡联动暂不支持，请保持原接头条件");}
      return;
    }
    validate(data,at,target,removed,original==null?null:original.position());
    BlockPos to=BlockPos.containing(target.position().x(),target.position().y(),target.position().z());
    for(UUID id:outside){var r=data.index.roads.get(id).record;if(player!=null)RoadData.requireOwner(player,r.owner());boolean first=r.a().equals(at);Node a=first?new Node(target.position(),r.start().yaw(),0):r.start(),b=first?r.end():new Node(target.position(),r.end().yaw(),0);
      var next=new RoadRecord(r.id(),r.owner(),first?to:r.a(),first?r.b():to,a,b,r.settings(),r.automatic(),r.clearance(),List.of(),r.endCaps(),r.buildVersion(),null,List.of(),r.furniturePhase());
      built.add(new RoadIndex.Built(next));removed.add(id);
    }
  }
  private SharedRoadEndpoints(){}
}
