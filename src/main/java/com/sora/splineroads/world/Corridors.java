package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.CorridorPlanner.*;
import com.sora.splineroads.core.RoadGeometry.*;
import net.minecraft.nbt.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import java.util.*;

/** Saved main/frontage relationship and shared client/server configuration. */
public final class Corridors {
  public static CompoundTag write(Config c){
    CompoundTag t=new CompoundTag();t.putString("Mode",c.kind().name());t.putString("Sides",c.sides().name());
    t.putString("Access",c.access().name());t.putDouble("Gap",c.gap());t.putDouble("FrontageY",c.frontageY());t.putString("Adjustment",c.adjustment().name());return t;
  }
  public static Config read(CompoundTag t){
    return new Config(Kind.valueOf(t.getString("Mode")),Sides.valueOf(t.getString("Sides")),
        Access.valueOf(t.getString("Access")),t.getDouble("Gap"),t.getDouble("FrontageY"),t.contains("Adjustment")?Adjustment.valueOf(t.getString("Adjustment")):Adjustment.AUTO);
  }
  public static CompoundTag initialize(CompoundTag t,Kind kind){
    t.putString("Kind","corridor");
    if(!t.contains("Corridor")){
      var a=RoadRecord.readNode(t.getList("Nodes",Tag.TAG_COMPOUND).getCompound(0));
      t.put("Corridor",write(new Config(kind,Sides.BOTH,Access.BOTH,kind==Kind.LAYERED?0:12,a.position().y(),Adjustment.AUTO)));
      t.put("Options",Interchanges.write(new InterchangePlanner.Options(
          InterchangePlanner.Preset.CLOVERLEAF,false,1,48,20,5,1).adjust(true)));
      if(kind==Kind.FRONTAGE)t.put("Main2",RoadRecord.writeSettings(new Settings(
          Mode.STRAIGHT,Style.O2_ONE,Style.O2_ONE.defaultWidth(),1,.4,90)));
    }
    HostAxes.normalizeCorridor(t);return t;
  }
  public static Config effective(CompoundTag t){
    Config c=read(t.getCompound("Corridor"));
    return c.adjustment()==Adjustment.AUTO&&t.contains("ResolvedFit")?c.adjustment(Adjustment.valueOf(t.getString("ResolvedFit"))):c;
  }

  /** Read-only authoritative preview; final construction repeats this against the live world. */
  public static CompoundTag preview(ServerLevel level,ServerPlayer player,CompoundTag command){
    CompoundTag t=Interchanges.payload(level,player,command.getLongArray("Points"),command.hasUUID("Id")?command.getUUID("Id"):null);
    for(String key:List.of("Main1","Main2","Options","Corridor"))t.put(key,command.getCompound(key).copy());
    if(!t.contains("Corridor"))throw new IllegalArgumentException("缺少道路组合参数");
    resolve(level,player,t);return t;
  }

  public static InterchangePlanner.Plan resolve(ServerLevel level,ServerPlayer player,CompoundTag t){
    try(var chunks=RoadWorkChunks.open(level)){
      RoadData data=RoadData.get(level);UUID group=t.hasUUID("Id")?t.getUUID("Id"):null;
      Interchanges.requireNoEndpointJunction(data,group);
      long[] points=t.getLongArray("Points");Set<UUID> replaced=new HashSet<>();
      if(group!=null){for(var road:data.index.roads.values())if(group.equals(road.record.assembly()))replaced.add(road.record.id());}
      else for(int i=0;i+1<points.length;i+=2)for(var road:Interchanges.mainChain(data,BlockPos.of(points[i]),BlockPos.of(points[i+1])))replaced.add(road.id());
      HostAxes.normalizeCorridor(t);Config c=read(t.getCompound("Corridor"));boolean adjust=Interchanges.read(t.getCompound("Options")).adjustEndpoints();
      Adjustment[] candidates=adjust&&c.adjustment()==Adjustment.AUTO?new Adjustment[]{Adjustment.BOTH,Adjustment.END,Adjustment.START}:new Adjustment[]{c.adjustment()==Adjustment.AUTO?Adjustment.BOTH:c.adjustment()};
      List<String> reasons=new ArrayList<>();
      for(Adjustment mode:candidates){
        t.putString("ResolvedFit",mode.name());
        try{
          var plan=Interchanges.plan(t);
          for(var leg:plan.legs()){UUID source=ContinuousRoads.sourceId(leg.name());if(source!=null)replaced.add(source);}
          Set<BlockPos> sources=new HashSet<>();for(long p:points)sources.add(BlockPos.of(p));
          for(int i=0;i<points.length;i++){
            BlockPos from=BlockPos.of(points[i]);var endpoint=InterchangeEndpoints.read(level,player,t,i);Node target=plan.anchors().get(i);
            if(endpoint.node().equals(target)||ContinuousRoads.virtual(t,i,target))continue;
            if(data.index.atNode(from).stream().anyMatch(id->!replaced.contains(id)))SharedRoadEndpoints.validate(data,from,target);
            V pos=target.position();BlockPos to=BlockPos.containing(pos.x(),pos.y(),pos.z());chunks.node(to);
            if(level.isOutsideBuildHeight(to)||!level.getWorldBorder().isWithinBounds(to))throw new IllegalArgumentException("调整后的端点超出世界高度或边界");
            if(player!=null&&!level.mayInteract(player,to))throw new IllegalArgumentException("调整后的端点位于受保护区域");
            var state=level.getBlockState(to);var entity=level.getBlockEntity(to);
            boolean source=sources.contains(to)&&entity instanceof NodeEntity;
            if(!source&&(entity!=null||state.getDestroySpeed(level,to)<0||RoadBlocks.isCollider(state)&&data.index.at(to.asLong()).stream().anyMatch(id->!replaced.contains(id))))
              throw new IllegalArgumentException("端点目标被占用："+to.toShortString()+"（"+net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock())+"）");
          }
          for(var leg:plan.legs())for(var old:data.index.roads.values())if(!replaced.contains(old.record.id())&&RoadIndex.overlapXZ(leg.mesh(),old.mesh,1))Interchanges.checkExternal(leg.mesh(),old.mesh);
          return plan;
        }catch(IllegalArgumentException error){reasons.add(mode.label+"："+error.getMessage());}
      }
      t.remove("ResolvedFit");throw new IllegalArgumentException(String.join("；",reasons));
    }
  }
  private Corridors(){}
}
