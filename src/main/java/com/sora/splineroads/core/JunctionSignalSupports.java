package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Small deck-mounted seats keep standard junction masts supported above ground. */
public final class JunctionSignalSupports {
  public static void add(List<Part> parts,List<Mesh> decks,List<Part> sidewalks,Ground ground,double thickness){
    for(var base:List.copyOf(parts))if(base.material()==Material.CB_BASE){
      V foot=base.a().add(base.b()).mul(.5);
      var bottom=RoadPoleModel.faces(base).stream().flatMap(f->f.points().stream()).filter(p->Math.abs(p.y()-foot.y())<1e-7).toList();
      if(bottom.stream().allMatch(p->decks.stream().anyMatch(m->RoadQueries.contains(m,p,-.005,.03))
          ||sidewalks.stream().anyMatch(s->onTop(s,p))||Math.abs(ground.top(p.x(),p.z(),p.y())-p.y())<.025))continue;
      V anchor=null;double best=Double.POSITIVE_INFINITY;
      for(var mesh:decks){
        var q=RoadQueries.horizontal(mesh,foot);var s=q.sample();
        if(Math.abs(s.center().y()-foot.y())>.25)continue;
        double lateral=Math.max(-s.halfWidth()+.25,Math.min(s.halfWidth()-.25,q.lateral()));
        V p=s.at(lateral,0);double distance=p.sub(foot).horizontalLength();
        if(distance<best&&RoadQueries.contains(mesh,p,0,.3)){anchor=p;best=distance;}
      }
      if(anchor==null||best>8)throw new IllegalArgumentException("路口信号灯缺少邻近路面承托，请调整路口参数");
      double depth=Math.max(.4,thickness),y=Math.min(anchor.y(),foot.y())-depth;
      V direction=foot.sub(anchor).horizontalLength()>1e-7?foot.sub(anchor).horizontalUnit():new V(0,0,1);
      var seat=new Part(new V(anchor.x(),y,anchor.z()).sub(direction.mul(.5)),new V(foot.x(),y,foot.z()).add(direction.mul(.5)),.9,foot.y()-y,false,Material.CONCRETE,null,null,"sr:junction_signal_seat");
      if(ground.blocked(seat))throw new IllegalArgumentException("路口信号灯承托与其他道路冲突，请调整路口参数");
      parts.add(seat);
    }
  }
  private static boolean onTop(Part part,V p){
    if(part.material()!=Material.CONCRETE&&!part.material().name().startsWith("WALK_"))return false;
    var top=part.top();if(top.stream().anyMatch(v->Math.abs(v.y()-p.y())>.03))return false;
    return JunctionPaint.inside(top,p);
  }
  private JunctionSignalSupports(){}
}
