package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/** Keep a distant side connection attached when its host's other junction is edited. */
final class ConnectedSideJunctions {
  static Map<UUID,CompoundTag> rebase(ServerLevel level,List<RoadIndex.Built> built,Set<UUID> removed,Set<UUID> preserved){
    var data=RoadData.get(level);var receipts=new HashMap<UUID,CompoundTag>();
    var initialRemoved=Set.copyOf(removed);var candidates=new HashMap<UUID,RoadIndex.Built>();
    built.forEach(r->candidates.put(r.record.id(),r));var hosts=new HashMap<UUID,RoadRecord>();var unchanged=new HashSet<UUID>();
    var groups=new HashSet<UUID>();
    for(var entry:data.junctions.entrySet())if(entry.getValue().getBoolean("OneSided")){
      var t=entry.getValue();for(String key:t.getBoolean("Paired")?List.of("OriginalMain","OriginalMainB"):List.of("OriginalMain"))
        if(initialRemoved.contains(t.getCompound(key).getUUID("Id")))groups.add(entry.getKey());
    }
    for(var entry:data.junctions.entrySet()){
      var saved=entry.getValue();if(!saved.getBoolean("OneSided"))continue;
      var keys=saved.getBoolean("Paired")?List.of("OriginalMain","OriginalMainB"):List.of("OriginalMain");
      var originals=keys.stream().map(k->RoadRecord.load(saved.getCompound(k))).toList();
      if(!groups.contains(entry.getKey()))continue;
      UUID group=entry.getKey();var receipt=saved.copy();var specs=new HashMap<V,JunctionSpec>();
      var members=data.index.roads.values().stream().filter(r->group.equals(r.record.assembly())).toList();
      // Host deletion or a new junction consuming this region needs an explicit
      // dependency removal, rather than leaving orphan side-connection pieces.
      for(int side=0;side<keys.size();side++){
        var old=originals.get(side);if(!initialRemoved.contains(old.id()))continue;
        var candidate=candidates.get(old.id());if(candidate==null)throw conflict();
        if(!AutoJunctions.ordinary(candidate.record))throw conflict();
        if(AutoJunctions.sameDeck(old.mesh(),candidate.mesh)&&old.settings().equals(candidate.record.settings())&&old.a().equals(candidate.record.a())&&old.b().equals(candidate.record.b())){
          unchanged.add(old.id());continue;
        }
        String key=side==0?"Spec":"PairSpec";var previous=JunctionCodec.read(saved.getCompound(key));
        var source=previous.oneSide();var mesh=candidate.mesh;
        double from=station(mesh,RoadSlicing.at(source.road(),source.from()).center());
        double to=station(mesh,RoadSlicing.at(source.road(),source.to()).center());
        if(from<2||to>mesh.length()-2||to<=from||old.settings().style()!=candidate.record.settings().style())throw conflict();
        for(double d=source.from();d<=source.to()+1e-6;d+=.5){
          var a=RoadSlicing.at(source.road(),Math.min(d,source.to()));var b=RoadSlicing.at(mesh,from+d-source.from());
          if(a.center().distance(b.center())>1e-5||a.left().distance(b.left())>1e-5||Math.abs(a.halfWidth()-b.halfWidth())>1e-5)throw conflict();
        }
        var next=hosts.get(old.id());
        if(next==null){
          next=candidate.record;
          if(old.furniturePhase()!=null){var at=RoadQueries.horizontal(old.mesh(),mesh.first().center()).sample();double delta=at.distance()+mesh.first().center().sub(at.center()).dot(at.left().left().mul(-1));next=next.furniturePhase(old.furniturePhase().shift(delta));}
          var planning=new ArrayList<RoadIndex.Built>();for(var r:data.index.roads.values())if(!initialRemoved.contains(r.record.id())&&!groups.contains(r.record.assembly()))planning.add(r);
          for(var r:candidates.values())if(!r.record.id().equals(old.id()))planning.add(r);
          var host=new RoadIndex.Built(next);planning.add(host);
          try(var chunks=RoadWorkChunks.open(level)){chunks.roads(List.of(host));next=data.planSideHost(level,host,planning);}
          hosts.put(old.id(),next);
        }
        var changed=previous.oneSide(new OneSidedJunction.Source(next.mesh(),next.structures(),from,to,source.side()));
        OneSidedJunction.prepare(changed);specs.put(previous.center(),changed);
        receipt.put(keys.get(side),next.save());receipt.put(key,JunctionCodec.write(changed));receipt.putInt(side==0?"MainSignature":"MainSignatureB",next.save().hashCode());
      }
      if(specs.isEmpty())continue;
      for(var member:members){
        if(SideHosts.host(member.record))continue;
        var ref=member.record.junction();var spec=specs.getOrDefault(ref.spec().center(),ref.spec());
        RoadRecord base=member.record;
        for(String key:keys){var original=RoadRecord.load(receipt.getCompound(key));if(base.id().equals(original.id()))base=original;}
        built.add(new RoadIndex.Built(base.junction(group,new JunctionPlanner.Ref(spec,ref.piece(),ref.geometryVersion()))));removed.add(base.id());
      }
      receipts.put(group,receipt);
    }
    // Every dependent receipt sees the same candidate. Rebuild each shared host
    // only after all independent cuts have been rebased successfully.
    built.removeIf(r->hosts.containsKey(r.record.id())||unchanged.contains(r.record.id()));
    removed.removeAll(unchanged);preserved.addAll(unchanged);
    for(var host:hosts.values()){built.add(new RoadIndex.Built(SideHosts.rebased(data,host,receipts)));removed.add(host.id());}
    return receipts;
  }
  private static double station(Mesh mesh,V at){var q=RoadQueries.horizontal(mesh,at);if(q.sample().center().distance(at)>1e-5)throw conflict();return q.sample().distance();}
  private static IllegalArgumentException conflict(){return new IllegalArgumentException("本次修改进入已连接附属点的接入范围；请缩小路口范围，或先删除该单边连接");}
  private ConnectedSideJunctions(){}
}
