package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

/** Two attached points share a branch seam and one atomic assembly/restore receipt. */
public final class PairedSideJunctions {
  static boolean saved(RoadData data,UUID id){return id!=null&&data.junctions.getOrDefault(id,new CompoundTag()).getBoolean("Paired");}
  public static CompoundTag create(ServerLevel level,ServerPlayer player,UUID a,UUID pa,UUID b,UUID pb,Settings settings){
    if(a.equals(b))throw new IllegalArgumentException("请选择两条不同主路的附属点");
    var data=RoadData.get(level);var first=data.index.roads.get(a);var second=data.index.roads.get(b);
    for(var road:Arrays.asList(first,second))if(road==null||!SideHosts.attachable(road.record))throw new IllegalArgumentException("请选择两条普通主路路段的附属点");
    var t=new CompoundTag();t.putBoolean("OneSided",true);t.putBoolean("Paired",true);t.putUUID("Main",a);t.putUUID("AttachedPoint",pa);t.putUUID("MainB",b);t.putUUID("AttachedPointB",pb);t.putBoolean("MainFirst",true);
    t.put("OriginalMain",SideHosts.original(data,first.record).save());t.put("OriginalMainB",SideHosts.original(data,second.record).save());t.put("Fallback",RoadRecord.writeSettings(settings));
    return fresh(level,player,t);
  }
  public static CompoundTag payload(ServerLevel level,ServerPlayer player,UUID id){
    var t=RoadData.get(level).junctions.get(id);if(t==null||!t.getBoolean("Paired"))throw new IllegalArgumentException("双附属点连接已不存在");
    if(player!=null)RoadData.requireOwner(player,t.getUUID("Owner"));return fresh(level,player,t.copy());
  }
  private static V point(RoadRecord road,UUID id){return road.settings().options().attachments().points().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("附属点已变化，请重新选择")).position();}
  private static CompoundTag fresh(ServerLevel level,ServerPlayer player,CompoundTag t){
    var a=RoadRecord.load(t.getCompound("OriginalMain"));var b=RoadRecord.load(t.getCompound("OriginalMainB"));
    V av=point(a,t.getUUID("AttachedPoint")),bv=point(b,t.getUUID("AttachedPointB")),mid=av.add(bv).mul(.5);
    if(av.distance(bv)<48)throw new IllegalArgumentException("两附属点距离不足，请至少预留48格连接空间");
    var node=new Node(mid,RoadPlanner.yaw(bv.sub(av)),0);var pos=BlockPos.containing(mid.x(),mid.y(),mid.z());
    t.putLong("Branch",pos.asLong());t.put("BranchNode",RoadRecord.writeNode(node));
    for(int side=0;side<2;side++){
      var input=t.copy();input.remove("Paired");input.putUUID("Main",side==0?a.id():b.id());input.putUUID("AttachedPoint",t.getUUID(side==0?"AttachedPoint":"AttachedPointB"));input.putBoolean("MainFirst",side==0);
      if(side==1){input.remove("Spec");if(t.contains("PairSpec"))input.put("Spec",t.getCompound("PairSpec").copy());}
      var result=OneSidedJunctions.fresh(level,player,input,side==0?a:b,node);
      t.put(side==0?"Spec":"PairSpec",result.getCompound("Spec"));t.putInt(side==0?"MainSignature":"MainSignatureB",result.getInt("MainSignature"));
    }
    t.putString("Kind","junction");t.putInt("SelectedArm",2);t.putLongArray("Points",new long[]{a.a().asLong(),a.b().asLong(),b.a().asLong(),b.b().asLong(),pos.asLong()});return t;
  }
  public static String build(ServerLevel level,ServerPlayer player,CompoundTag command){
    return build(level,player,command,null);
  }
  static String build(ServerLevel level,ServerPlayer player,CompoundTag command,Settings roadSettings){
    try(var work=RoadWorkChunks.open(level)){
      var data=RoadData.get(level);boolean editing=command.hasUUID("Id");UUID group=editing?command.getUUID("Id"):UUID.randomUUID();
      var t=editing?payload(level,player,group):create(level,player,command.getUUID("Main"),command.getUUID("AttachedPoint"),command.getUUID("MainB"),command.getUUID("AttachedPointB"),RoadRecord.readSettings(command.getCompound("Fallback")));
      if(t.getInt("MainSignature")!=command.getInt("MainSignature")||t.getInt("MainSignatureB")!=command.getInt("MainSignatureB"))throw new IllegalArgumentException("道路在预览后发生变化，请重新选择");
      if(roadSettings!=null){
        if(SideRoads.signature(t)!=command.getInt("Signature"))throw new IllegalArgumentException("连接道路或路口已变化，请重新打开道路编辑器");
        t.put("ConnectionSettings",RoadRecord.writeSettings(roadSettings));t=fresh(level,player,t);
      }
      var originals=List.of(RoadRecord.load(t.getCompound("OriginalMain")),RoadRecord.load(t.getCompound("OriginalMainB")));
      var removed=new HashSet<UUID>();originals.forEach(r->removed.add(r.id()));if(editing)data.index.roads.values().stream().filter(r->group.equals(r.record.assembly())).forEach(r->removed.add(r.record.id()));
      var built=new ArrayList<RoadIndex.Built>();var nodes=new HashSet<BlockPos>();var seam=BlockPos.of(t.getLong("Branch"));nodes.add(seam);
      for(int side=0;side<2;side++){
        String key=side==0?"Spec":"PairSpec";var authority=t.copy();authority.put("Spec",t.getCompound(key));var request=command.copy();request.put("Spec",command.getCompound(key));
        var spec=roadSettings==null?OneSidedJunctions.validated(authority,request):JunctionCodec.read(t.getCompound(key));OneSidedJunction.prepare(spec);t.put(key,JunctionCodec.write(spec));
        var original=originals.get(side);nodes.add(original.a());nodes.add(original.b());var plan=JunctionPlanner.plan(spec);var center=BlockPos.containing(spec.center().x(),spec.center().y(),spec.center().z());
        for(int i=0;i<plan.pieces().size();i++){
          var p=plan.pieces().get(i);var mesh=p.mesh();boolean retained=p.arm()==-3;
          if(retained){built.add(new RoadIndex.Built(SideHosts.retained(data,original,group,spec)));continue;}
          UUID id=retained?original.id():UUID.nameUUIDFromBytes((group+":paired:"+side+":"+i).getBytes(StandardCharsets.UTF_8));
          var start=retained?original.start():new Node(mesh.first().center(),RoadPlanner.yaw(mesh.first().left().left().mul(-1)),0);
          var end=retained?original.end():new Node(mesh.last().center(),RoadPlanner.yaw(mesh.last().left().left().mul(-1)),0);
          built.add(new RoadIndex.Built(new RoadRecord(id,original.owner(),retained?original.a():p.arm()==2?seam:center,retained?original.b():center,start,end,retained?original.settings():mesh.settings(),false,4).junction(group,new JunctionPlanner.Ref(spec,i))));
        }
      }
      for(var proposed:built)for(var old:data.index.roads.values())if(!removed.contains(old.record.id())&&RoadIndex.overlapXZ(proposed.mesh,old.mesh,1))Interchanges.checkExternal(proposed.mesh,old.mesh,null);
      // The seam is an internal logical port, not a player endpoint block.
      data.replaceAssembly(level,player,built,removed,nodes);
      t.putUUID("Id",group);t.putUUID("Owner",originals.get(0).owner());data.junctions.put(group,t);data.setDirty();return "双附属点连接已保存：两端单边路口，单行方向 A→B";
    }
  }
  public static void remove(ServerLevel level,ServerPlayer player,UUID group){
    SideHosts.remove(level,player,group);
  }
  private PairedSideJunctions(){}
}
