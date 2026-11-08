package com.sora.splineroads.compat.mtr;

import com.sora.splineroads.core.RailbedPath;
import com.sora.splineroads.core.RoadGeometry.V;
import java.util.*;
import org.mtr.core.data.*;
import org.mtr.core.simulation.Simulator;

/** Only the MTR simulator owner enters here; no live Rail escapes to Minecraft threads. */
final class Mtr4Adapter {
  private record Edge(Rail rail,Position from,Position to,boolean reverse){}
  private static final Map<Simulator,Task> tasks=Collections.synchronizedMap(new WeakHashMap<>());
  static boolean active(){return !tasks.isEmpty();}
  static boolean trainNode(Object block){return block instanceof org.mtr.mod.block.BlockNode node&&node.transportMode==TransportMode.TRAIN;}
  static boolean railbedItem(Object item){return item instanceof org.mtr.mapping.holder.Item wrapped&&wrapped.data instanceof com.sora.splineroads.world.RailbedTool;}
  static Object nodeShape(Object original,Object world,Object position){
    var view=((org.mtr.mapping.holder.BlockView)world).data;var pos=((org.mtr.mapping.holder.BlockPos)position).data;
    var index=com.sora.splineroads.world.RoadBlocks.index(view);if(index==null)return original;
    for(var id:index.inChunk(net.minecraft.world.level.ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4))){var built=index.roads.get(id);
      if(built!=null&&built.record.railway()&&!built.boxes(pos).isEmpty())return new org.mtr.mapping.holder.VoxelShape(net.minecraft.world.phys.shapes.Shapes.or(((org.mtr.mapping.holder.VoxelShape)original).data,index.shape(pos)));
    }return original;
  }
  static void tick(Object value){
    if(!(value instanceof Simulator simulator))return;
    Task task=tasks.get(simulator);
    if(task==null){for(var request:MtrRailAccess.requests)if(request.dimension().equals(simulator.dimension)&&MtrRailAccess.requests.remove(request)){
      if(request.result().isDone())continue;
      try{task=new Task(simulator,request);tasks.put(simulator,task);}catch(RuntimeException failure){request.result().completeExceptionally(failure);}break;
    }}
    if(task==null)return;
    try{long deadline=System.nanoTime()+2_000_000;int steps=0;
      while(!task.request.result().isDone()&&steps++<256&&System.nanoTime()<deadline)task.step(simulator);
    }catch(RuntimeException failure){task.request.result().completeExceptionally(failure);}
    if(task.request.result().isDone())tasks.remove(simulator);
  }
  private static Position position(RailbedPath.Node p){return new Position(p.x(),p.y(),p.z());}
  private static RailbedPath.Node node(Position p){return new RailbedPath.Node(Math.toIntExact(p.getX()),Math.toIntExact(p.getY()),Math.toIntExact(p.getZ()));}
  private static Map<Position,Rail> adjacent(Simulator simulator,Position p){var map=simulator.positionsToRail.get(p);return map==null?Map.of():map;}
  private static boolean train(Rail r){return r!=null&&r.getTransportMode()==TransportMode.TRAIN&&!r.canConnectRemotely()&&r.isValid();}
  private static Edge edge(Rail r,Position a,Position b){return new Edge(r,a,b,a.compareTo(b)>0);}
  private static List<Edge> path(Simulator simulator,Position a,Position b){
    Rail direct=adjacent(simulator,a).get(b);if(train(direct))return List.of(edge(direct,a,b));
    var starts=adjacent(simulator,a).entrySet().stream().filter(e->train(e.getValue())).toList();
    if(starts.size()>2)throw new IllegalArgumentException("起点有道岔，请分段选择");
    var found=new ArrayList<List<Edge>>();boolean ambiguous=false;
    for(var start:starts){var edges=new ArrayList<Edge>();var visited=new HashSet<Position>();visited.add(a);Position previous=a,current=start.getKey();Rail rail=start.getValue();double length=0;
      while(true){if(!visited.add(current)){ambiguous=true;break;}length+=rail.railMath.getLength();if(edges.size()>=RailbedPath.MAX_SEGMENTS||length>RailbedPath.MAX_LENGTH){ambiguous=true;break;}
        edges.add(edge(rail,previous,current));if(current.equals(b)){found.add(List.copyOf(edges));break;}
        Position before=previous;var next=adjacent(simulator,current).entrySet().stream().filter(e->!e.getKey().equals(before)&&train(e.getValue())).toList();
        if(next.size()!=1){ambiguous|=next.size()>1;break;}previous=current;current=next.get(0).getKey();rail=next.get(0).getValue();
      }
    }
    if(found.size()!=1||ambiguous)throw new IllegalArgumentException(found.isEmpty()&&!ambiguous?"请先使用 MTR 连接这两个轨道节点":"铁路途中存在道岔、环线或超长区间，请分段选择");
    return found.get(0);
  }
  private static final class Task {
    final MtrRailAccess.Request request;final List<Edge> edges;final List<RailbedPath.Segment> output=new ArrayList<>();
    RailbedPath.Sampler sampler;int index,total;
    Task(Simulator simulator,MtrRailAccess.Request request){this.request=request;edges=path(simulator,position(request.a()),position(request.b()));}
    void step(Simulator simulator){
      if(System.nanoTime()>request.deadline())throw new IllegalArgumentException("铁路读取超时，请缩短区间");
      for(var edge:edges)if(simulator.railIdMap.get(edge.rail.getHexId())!=edge.rail)throw new IllegalArgumentException("轨道已变化，请重新选择");
      var edge=edges.get(index);var math=edge.rail.railMath;
      if(sampler==null)sampler=new RailbedPath.Sampler(math.getLength(),s->{var p=math.getPosition(s,edge.reverse);return new V(p.x,p.y,p.z);});
      sampler.step();if(!sampler.done())return;
      var points=sampler.result();total+=points.size();if(total>RailbedPath.MAX_POINTS)throw new IllegalArgumentException("铁路采样超过预算，请分段选择");
      output.add(new RailbedPath.Segment(edge.rail.getHexId(),node(edge.from),node(edge.to),edge.reverse,math.getLength(),edge.rail.getStyles(),points));
      sampler=null;if(++index==edges.size())request.result().complete(new RailbedPath.Path(output));
    }
  }
  private Mtr4Adapter(){}
}
