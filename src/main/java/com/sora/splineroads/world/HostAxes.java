package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

/** Server-derived host paths travel with the preview and the saved assembly descriptor. */
public final class HostAxes {
  public static Node[] nodes(CompoundTag t){return t.getList("Nodes",Tag.TAG_COMPOUND).stream().map(v->RoadRecord.readNode((CompoundTag)v)).toArray(Node[]::new);}
  public static List<RoadAxis> read(CompoundTag t){
    var out=new ArrayList<RoadAxis>();
    for(Tag value:t.getList("HostAxes",Tag.TAG_COMPOUND)){
      var points=new ArrayList<V>();for(Tag p:((CompoundTag)value).getList("Points",Tag.TAG_COMPOUND))points.add(RoadRecord.readNode((CompoundTag)p).position());
      out.add(new RoadAxis(points));
    }
    if(!out.isEmpty())return List.copyOf(out);
    Node[] n=nodes(t);for(int i=0;i+1<n.length;i+=2)out.add(RoadAxis.straight(n[i].position(),n[i+1].position()));
    if(n.length==3){V a=n[0].position(),u=n[1].position().sub(a).horizontalUnit(),c=n[2].position();V foot=a.add(u.mul(c.sub(a).dot(u)));out.add(RoadAxis.straight(c,new V(foot.x(),c.y(),foot.z())));}
    return List.copyOf(out);
  }
  public static void write(CompoundTag t,List<RoadAxis> axes){
    ListTag list=new ListTag();for(RoadAxis axis:axes){var a=new CompoundTag();var points=new ListTag();for(V p:axis.points()){var q=new CompoundTag();q.putDouble("X",p.x());q.putDouble("Y",p.y());q.putDouble("Z",p.z());points.add(q);}a.put("Points",points);list.add(a);}t.put("HostAxes",list);
  }
  static RoadAxis chain(List<RoadRecord> chain,BlockPos start){
    var points=new ArrayList<V>();BlockPos at=start;
    for(var road:chain){boolean forward=road.a().equals(at);var samples=road.mesh().samples();
      for(int j=0;j<samples.size();j++){V p=samples.get(forward?j:samples.size()-1-j).center();if(points.isEmpty()||p.distance(points.get(points.size()-1))>1e-5)points.add(p);}
      at=forward?road.b():road.a();
    }return new RoadAxis(points);
  }
  public static int signature(CompoundTag t){return t.getList("HostAxes",Tag.TAG_COMPOUND).hashCode();}
  public static boolean curved(CompoundTag t){return read(t).stream().anyMatch(RoadAxis::curved);}
  public static boolean retiredInterchange(CompoundTag t){return !t.contains("Corridor")&&curved(t);}
  public static final String RETIRED_MESSAGE="请选择两条直线主路";
  public static List<InterchangePlanner.Preset> presets(CompoundTag t){return ParallelSpui.present(t)?List.of(InterchangePlanner.Preset.SPUI):CurvedRoadPlans.presets(t.getLongArray("Points").length,curved(t));}
  public static boolean fixedFrontage(CompoundTag t){return t.contains("Corridor")&&CurvedRoadPlans.fixedFrontageHeight(nodes(t),Corridors.read(t.getCompound("Corridor")),read(t).get(0));}
  public static void normalizeCorridor(CompoundTag t){
    if(!fixedFrontage(t))return;var c=Corridors.read(t.getCompound("Corridor"));
    t.put("Corridor",Corridors.write(new CorridorPlanner.Config(c.kind(),c.sides(),CorridorPlanner.Access.NONE,c.gap(),nodes(t)[0].position().y(),c.adjustment())));
  }
  static void fit(CompoundTag t,List<Node> target){
    if(target.size()>4)return;
    var axes=read(t);var fitted=new ArrayList<RoadAxis>();
    for(int i=0;i<axes.size();i++){
      RoadAxis axis=axes.get(i);double start=axis.project(target.get(i*2).position()),end=i*2+1<target.size()?axis.project(target.get(i*2+1).position()):axis.length();
      fitted.add(axis.slice(start,end,target.get(i*2).position().y()-axis.at(start).y()));
    }write(t,fitted);
  }
  private HostAxes(){}
}
