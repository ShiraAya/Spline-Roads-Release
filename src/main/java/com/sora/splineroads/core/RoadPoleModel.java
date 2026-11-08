package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.RoadStructures.*;
import com.sora.splineroads.core.RoadSurface.Face;
import java.util.*;

/** SR tapered galvanized supports; legacy material IDs remain save-compatible. */
public final class RoadPoleModel {
  public static boolean support(Part p){return p.material()==Material.CB_POST||p.material()==Material.CB_ARM||p.material()==Material.CB_BASE;}
  public static List<Face> faces(Part p){
    var out=new ArrayList<Face>();V center=p.a().add(p.b()).mul(.5);int metal=0xCAD2D7,dark=0x52616C;
    if(p.material()==Material.CB_ARM){
      V delta=p.b().sub(p.a());double length=p.a().distance(p.b());
      OriginalModels.cylinder(out,p.a(),p.b(),.105,.07,12,metal);
      for(double t:new double[]{.04,.52,.94}){V a=p.a().add(delta.mul(t));OriginalModels.cylinder(out,a,a.add(delta.mul(Math.min(.12/length,.03))),.11-.03*t,.11-.03*t,12,dark);}
    }else if(p.material()==Material.CB_POST){
      OriginalModels.cylinder(out,center,center.add(new V(0,p.height(),0)),.145,.085,12,metal);
      OriginalModels.cylinder(out,center.add(new V(0,.2,0)),center.add(new V(0,.27,0)),.18,.18,12,dark);
      OriginalModels.cylinder(out,center.add(new V(0,p.height()-.06,0)),center.add(new V(0,p.height(),0)),.095,.095,12,dark);
    }else{
      boolean fitted=p.model().equals("sr:signal_base")||p.model().equals("sr:signal_bolt_plate");
      boolean plate=p.model().equals("sr:signal_bolt_plate");
      var local=new ArrayList<Face>();OriginalModels.bevel(local,-.23,0,-.23,.23,.14,.23,.035,dark);
      if(!plate)OriginalModels.cylinder(local,new V(0,.14,0),new V(0,.34,0),.18,.15,12,metal);
      for(int x:new int[]{-1,1})for(int z:new int[]{-1,1})OriginalModels.cylinder(local,new V(x*.16,plate?.09:.14,z*.16),new V(x*.16,plate?.14:.19,z*.16),.025,.025,6,0xECF0F2);
      V forward=fitted?p.b().sub(p.a()).horizontalUnit():new V(0,0,1);
      V side=fitted?forward.left():new V(1,0,0);
      double scale=fitted?p.width()/.46:1;
      out.addAll(OriginalModels.transform(local,center,side.mul(scale),new V(0,1,0),forward.mul(scale)));
    }return List.copyOf(out);
  }
  private RoadPoleModel(){}
}
