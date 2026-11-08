package com.sora.splineroads.compat.mtr;

import com.sora.splineroads.core.RailbedPath;
import com.sora.splineroads.core.RoadGeometry.V;
import java.util.*;
import org.mtr.core.data.*;
import org.mtr.core.simulation.Simulator;

/** Owner-thread spatial summaries, invalidated at MTR's own graph sync boundary. */
final class MtrNearby {
  private static final Map<Simulator,Index> indices=Collections.synchronizedMap(new WeakHashMap<>());
  static final class Index {
    final Map<Long,List<Rail>> cells=new HashMap<>();Iterator<Rail> build;Task task;boolean ready;int count,links;
  }
  static void changed(Object value){if(value instanceof Simulator simulator){MtrRailAccess.changedDimension(simulator.dimension);var old=indices.remove(simulator);if(old!=null&&old.task!=null)old.task.request.result().completeExceptionally(new IllegalArgumentException("MTR 轨道数据已变化，请重新预览"));}}
  static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
  static boolean train(Rail rail){return rail.isValid()&&rail.getTransportMode()==TransportMode.TRAIN&&!rail.canConnectRemotely();}
  static void tick(Object value){if(!(value instanceof Simulator simulator))return;
    boolean requested=MtrRailAccess.nearby.stream().anyMatch(r->r.dimension().equals(simulator.dimension));var index=indices.get(simulator);
    if(index==null&&!requested)return;if(index==null){index=new Index();index.build=simulator.rails.iterator();indices.put(simulator,index);}
    try{long deadline=System.nanoTime()+2_000_000;int steps=0;
      while(!index.ready&&steps++<256&&System.nanoTime()<deadline){
        if(!index.build.hasNext()){index.ready=true;index.build=null;break;}var rail=index.build.next();if(!train(rail))continue;
        if(++index.count>100_000)throw new IllegalArgumentException("MTR 邻轨索引超过首版预算，请减少当前维度轨段数量");var m=rail.railMath;
        for(int x=Math.floorDiv((int)m.minX,64);x<=Math.floorDiv((int)m.maxX,64);x++)for(int z=Math.floorDiv((int)m.minZ,64);z<=Math.floorDiv((int)m.maxZ,64);z++){
          if(++index.links>500_000)throw new IllegalArgumentException("MTR 邻轨索引超过首版空间预算");index.cells.computeIfAbsent(key(x,z),k->new ArrayList<>()).add(rail);
        }
      }
      if(!index.ready)return;
      if(index.task==null)for(var request:MtrRailAccess.nearby)if(request.dimension().equals(simulator.dimension)&&MtrRailAccess.nearby.remove(request)){if(!request.result().isDone())index.task=new Task(index,request);break;}
      if(index.task!=null){while(!index.task.request.result().isDone()&&steps++<256&&System.nanoTime()<deadline)index.task.step(simulator);if(index.task.request.result().isDone())index.task=null;}
    }catch(RuntimeException e){if(index.task!=null)index.task.request.result().completeExceptionally(e);for(var request:MtrRailAccess.nearby)if(request.dimension().equals(simulator.dimension))request.result().completeExceptionally(e);indices.remove(simulator);}
  }
  static final class Task {
    final MtrRailAccess.NearRequest request;final List<Rail> rails;final List<RailbedPath.Path> output=new ArrayList<>();int cursor;RailbedPath.Sampler sampler;
    Task(Index index,MtrRailAccess.NearRequest request){this.request=request;var found=Collections.newSetFromMap(new IdentityHashMap<Rail,Boolean>());var min=request.min();var max=request.max();
      for(int x=(int)Math.floor(min.x()/64);x<=Math.floor(max.x()/64);x++)for(int z=(int)Math.floor(min.z()/64);z<=Math.floor(max.z()/64);z++)for(var rail:index.cells.getOrDefault(key(x,z),List.of())){
        var m=rail.railMath;if(m.maxX>=min.x()&&m.minX<=max.x()&&m.maxZ>=min.z()&&m.minZ<=max.z()&&m.maxY>=min.y()&&m.minY<=max.y())found.add(rail);
        if(found.size()>128)throw new IllegalArgumentException("附近轨道过多，请缩短 A–B 区间");
      }rails=List.copyOf(found);
    }
    void step(Simulator simulator){if(cursor==rails.size()){request.result().complete(List.copyOf(output));return;}var rail=rails.get(cursor);if(simulator.railIdMap.get(rail.getHexId())!=rail)throw new IllegalArgumentException("附近轨道已变化，请重新预览");
      var math=rail.railMath;if(sampler==null)sampler=new RailbedPath.Sampler(math.getLength(),s->{var p=math.getPosition(s,false);return new V(p.x,p.y,p.z);});sampler.step();if(!sampler.done())return;
      var points=sampler.result();var a=points.get(0).position();var b=points.get(points.size()-1).position();
      output.add(new RailbedPath.Path(List.of(new RailbedPath.Segment(rail.getHexId(),node(a),node(b),false,math.getLength(),rail.getStyles(),points))));sampler=null;cursor++;
    }
    static RailbedPath.Node node(V p){return new RailbedPath.Node((int)Math.round(p.x()-.5),(int)Math.round(p.y()),(int)Math.round(p.z()-.5));}
  }
  private MtrNearby(){}
}
