package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.Mesh;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Finish guardrail cuts against the final deck shapes, after topology and cap
 * fitting. Keep exact exposed fragments rather than a bar inside a closed slot. */
public final class RoadRailSlabClipping {
  public static boolean guardrail(Part p){
    if(p.pier()||!p.model().isEmpty()&&!p.model().equals("sr:wall_rail"))return false;
    return p.material()==Material.DARK_STEEL&&p.width()>=.1&&p.width()<=.16&&p.height()<=1.3
        ||p.material()==Material.STEEL&&(p.width()==.1&&p.height()==.1||p.width()==.12&&p.height()==.12||p.width()==.3&&p.height()==.18);
  }
  public static List<Part> clip(List<Part> parts,List<Mesh> neighbors){
    var openings=new IdentityHashMap<Mesh,RoadShellOpening>();var result=new ArrayList<Part>();boolean changed=false;
    for(var part:parts){
      if(!guardrail(part)){result.add(part);continue;}
      List<Part> pieces=List.of(part);
      for(var neighbor:neighbors){
        RoadPlanningBudget.check();
        double extent=part.halfExtent();
        if(Math.max(part.a().y(),part.b().y())+part.height()+part.verticalFrame()<=neighbor.min().y()-neighbor.settings().thickness()+.04
            ||Math.min(part.a().y(),part.b().y())-part.verticalFrame()>=neighbor.max().y()
            ||Math.max(part.a().x(),part.b().x())+extent<=neighbor.min().x()||Math.min(part.a().x(),part.b().x())-extent>=neighbor.max().x()
            ||Math.max(part.a().z(),part.b().z())+extent<=neighbor.min().z()||Math.min(part.a().z(),part.b().z())-extent>=neighbor.max().z())continue;
        if(!RoadClearance.structureInDeck(part,neighbor))continue;
        var cutter=openings.computeIfAbsent(neighbor,RoadShellOpening::deck);var next=new ArrayList<Part>();
        for(var piece:pieces)next.addAll(cutter.cut(piece));pieces=next;
        if(pieces.isEmpty())break;
      }
      changed|=!pieces.equals(List.of(part));result.addAll(pieces);
    }
    // Clipping is the last operation on the rails. It can expose new ends after
    // StructurePlanner's terminal pass, so cap the surviving bars here as well.
    RoadStructures.terminalPosts(result,new Ground(){
      public double top(double x,double z,double y){return y;}
      public boolean joined(com.sora.splineroads.core.RoadGeometry.V p){return false;}
      public boolean blocked(Part p){return neighbors.stream().anyMatch(m->RoadClearance.structureInDeck(p,m));}
      public boolean unionRails(){return true;}
      public boolean railBlocked(Part p,com.sora.splineroads.core.RoadGeometry.V a,com.sora.splineroads.core.RoadGeometry.V b){
        return neighbors.stream().anyMatch(m->RoadClearance.structureInDeck(p,m));
      }
    });
    return changed||result.size()!=parts.size()?List.copyOf(result):parts;
  }
  private RoadRailSlabClipping(){}
}
