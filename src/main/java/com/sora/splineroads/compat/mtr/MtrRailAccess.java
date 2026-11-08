package com.sora.splineroads.compat.mtr;

import com.sora.splineroads.core.RailbedPath;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;
import com.sora.splineroads.core.RoadGeometry.V;
import net.minecraftforge.fml.ModList;

/** No optional types in descriptors or static initializers. Requests carry only values. */
public final class MtrRailAccess {
  record Request(String dimension,RailbedPath.Node a,RailbedPath.Node b,long deadline,CompletableFuture<RailbedPath.Path> result){}
  static final ConcurrentLinkedQueue<Request> requests=new ConcurrentLinkedQueue<>();
  record NearRequest(String dimension,V min,V max,CompletableFuture<List<RailbedPath.Path>> result){}
  static final ConcurrentLinkedQueue<NearRequest> nearby=new ConcurrentLinkedQueue<>();
  private static final AtomicInteger pending=new AtomicInteger();
  private static final ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicLong> revisions=new ConcurrentHashMap<>();
  public static long revision(String dimension){var value=revisions.get(dimension);return value==null?0:value.get();}
  static void changedDimension(String dimension){revisions.computeIfAbsent(dimension,k->new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();}
  public static String unavailable(){
    var mod=ModList.get().getModContainerById("mtr");if(mod.isEmpty())return "请安装 MTR 4.0.5（Minecraft 1.20.1 Forge）";
    String v=mod.get().getModInfo().getVersion().toString();
    return v.equals("4.0.5")||v.equals("4.0.5+1.20.1")||v.equals("FORGE-4.0.5+1.20.1")?null:"此 MTR 版本尚未适配："+v;
  }
  public static CompletableFuture<RailbedPath.Path> read(String dimension,RailbedPath.Node a,RailbedPath.Node b){
    String problem=unavailable();if(problem!=null)return CompletableFuture.failedFuture(new IllegalArgumentException(problem));
    if(a.equals(b))return CompletableFuture.failedFuture(new IllegalArgumentException("请选择不同的铁路节点"));
    if(pending.incrementAndGet()>16){pending.decrementAndGet();return CompletableFuture.failedFuture(new IllegalArgumentException("铁路读取队列繁忙，请稍后重试"));}
    var future=new CompletableFuture<RailbedPath.Path>();future.orTimeout(20,TimeUnit.SECONDS);
    var request=new Request(dimension,a,b,System.nanoTime()+TimeUnit.SECONDS.toNanos(20),future);
    future.whenComplete((value,error)->{pending.decrementAndGet();requests.remove(request);});requests.add(request);return future;
  }
  public static boolean trainNode(Object block){return unavailable()==null&&Mtr4Adapter.trainNode(block);}
  public static boolean railbedItem(Object item){return unavailable()==null&&Mtr4Adapter.railbedItem(item);}
  public static Object nodeShape(Object original,Object world,Object position){return unavailable()==null?Mtr4Adapter.nodeShape(original,world,position):original;}
  public static CompletableFuture<List<RailbedPath.Path>> nearby(String dimension,RailbedPath.Path base){
    String problem=unavailable();if(problem!=null)return CompletableFuture.failedFuture(new IllegalArgumentException(problem));
    if(nearby.size()>=16)return CompletableFuture.failedFuture(new IllegalArgumentException("邻轨查询队列繁忙"));
    var points=base.positions();var min=new V(points.stream().mapToDouble(V::x).min().orElseThrow()-72,points.stream().mapToDouble(V::y).min().orElseThrow()-100,points.stream().mapToDouble(V::z).min().orElseThrow()-72);
    var max=new V(points.stream().mapToDouble(V::x).max().orElseThrow()+72,points.stream().mapToDouble(V::y).max().orElseThrow()+12,points.stream().mapToDouble(V::z).max().orElseThrow()+72);
    var result=new CompletableFuture<List<RailbedPath.Path>>();var request=new NearRequest(dimension,min,max,result);result.orTimeout(30,TimeUnit.SECONDS);result.whenComplete((v,e)->nearby.remove(request));nearby.add(request);return result;
  }
  public static void changed(Object simulator){if(unavailable()==null)MtrNearby.changed(simulator);}
  public static void tick(Object simulator){
    if(unavailable()!=null)return;
    if(!requests.isEmpty()||Mtr4Adapter.active())Mtr4Adapter.tick(simulator);
    MtrNearby.tick(simulator);
  }
  private MtrRailAccess(){}
}
