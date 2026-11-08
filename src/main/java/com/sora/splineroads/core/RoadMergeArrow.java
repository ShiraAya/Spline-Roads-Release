package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
/** The existing interchange merge arrow. One source of geometry for every tool. */
public final class RoadMergeArrow {
  public static List<RoadJunction.Paint> flat(V origin,V forward,V left,double lateral,double width,double side){
    V toward=left.mul(side);double shift=Math.min(.95,width*.22);
    V center=origin.add(left.mul(lateral-side*shift*.5));
    V tail=center.sub(forward.mul(2.2)),bend=center.add(forward.mul(.2));
    V base=bend.add(forward.mul(1.55)).add(toward.mul(shift));
    V aim=base.sub(bend).horizontalUnit(),cross=aim.left();
    V normal=forward.left().add(cross).horizontalUnit();
    V miter=normal.mul(.11/Math.max(.5,normal.dot(forward.left())));
    V a=tail.add(forward.left().mul(.11)),b=tail.sub(forward.left().mul(.11));
    V c=bend.add(miter),d=bend.sub(miter),e=base.add(cross.mul(.11)),g=base.sub(cross.mul(.11));
    var group=List.of(new RoadJunction.Paint(List.of(a,b,d,c),0xEDEEE2),new RoadJunction.Paint(List.of(c,d,g,e),0xEDEEE2),
        new RoadJunction.Paint(List.of(base.add(aim.mul(1.1)),base.sub(cross.mul(.52)),base.add(cross.mul(.52))),0xEDEEE2));
    double min=group.stream().flatMap(p->p.points().stream()).mapToDouble(p->p.sub(origin).dot(left)).min().orElse(0);
    double max=group.stream().flatMap(p->p.points().stream()).mapToDouble(p->p.sub(origin).dot(left)).max().orElse(0);
    V correction=left.mul(lateral-(min+max)/2);
    return group.stream().map(p->new RoadJunction.Paint(p.points().stream().map(v->v.add(correction)).toList(),p.color())).toList();
  }
  /** Along/lateral coordinates, centred on the lane. Warp vertices using the caller's actual lane samples. */
  public static List<RoadJunction.Paint> local(double width,double side){return flat(new V(0,0,0),new V(1,0,0),new V(0,0,1),0,width,side);}
  private RoadMergeArrow(){}
}
