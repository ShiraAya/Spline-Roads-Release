package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Diagnostic display filter only. Never changes saved geometry or collider ownership. */
public final class RoadSignFilter {
  public static boolean signPart(Part p,Mesh mesh){
    String id=p.model();
    if(id.startsWith("sr:gantry/")||id.startsWith("sr:toll_model_name_")||id.startsWith("sr:toll_model_label_")||id.equals("sr:toll_ETC")||id.equals("sr:toll_manual"))return true;
    return id.isEmpty()&&mesh.settings().structure()!=Structure.TUNNEL&&(p.material()==Material.SIGN_GREEN||p.material()==Material.SIGN_BLUE||p.material()==Material.SIGN_WHITE||p.material()==Material.GANTRY_FRAME);
  }
  public static List<Part> withoutSigns(Mesh mesh,List<Part> saved){
    var removed=new HashSet<Part>();
    if(saved.stream().noneMatch(p->p.model().startsWith("sr:gantry/")))for(int slot:RoadGantry.slots(mesh))try{removed.addAll(RoadGantry.members(mesh,slot,saved));}catch(IllegalArgumentException ignored){}
    return saved.stream().filter(p->!signPart(p,mesh)&&!removed.contains(p)).toList();
  }
  private RoadSignFilter(){}
}
