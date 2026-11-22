package com.sora.splineroads.world;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
public final class Feedback212Inspection {
 public static void main(String[] args)throws Exception{
  var root=NbtIo.readCompressed(Path.of(args[0]).toFile()).getCompound("data");
  var roads=new ArrayList<RoadIndex.Built>();
  for(var t:root.getList("Roads",Tag.TAG_COMPOUND))roads.add(new RoadIndex.Built(RoadRecord.load((CompoundTag)t)));
  System.out.println("ROADS="+roads.size());
  for(var point:List.of(new V(2521,56,-105),new V(2732,56,-128),new V(388,65,69),new V(-6,56,1112),new V(1058,56,1079),new V(1008,40,790),new V(1314,56,596),new V(1213,56,946),new V(1528,56,669),new V(2628,56,-161))){
   System.out.println("POINT="+point);
   roads.stream().sorted(Comparator.comparingDouble(r->RoadQueries.horizontal(r.mesh,point).horizontalDistance())).limit(4).forEach(b->{
    var r=b.record;var q=RoadQueries.horizontal(b.mesh,point);var j=r.junction();
    System.out.println("ROAD="+r.id()+" distance="+q.horizontalDistance()+" at="+q.sample().center()+" ends="+r.start().position()+" / "+r.end().position()+" style="+r.settings().style()+" structure="+r.settings().structure()+" group="+r.assembly()+" fragment="+(r.fragment()!=null)+" junction="+(j==null?null:j.piece()+"/arm="+j.get().arm()+" oneSide="+(j.spec().oneSide()!=null)));
    System.out.println("OPTIONS="+r.settings().options().sidewalk()+" "+r.settings().options().streetscape()+" supports="+r.settings().options().infrastructure().supportsEnabled());
    System.out.println("PARTS="+r.structures().stream().collect(java.util.stream.Collectors.groupingBy(p->p.material()+":"+p.model(),java.util.stream.Collectors.counting())));
   });
  }
 }
}
