package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.Part;
import com.sora.splineroads.core.RoadSurface.*;
import java.util.*;
/** Green framed mesh. Persist one thin collision panel per module, bake wires only once. */
public final class RailbedFence {
  public static final String MODEL="sr:railway_fence";
  public static List<Face> faces(Part p){
    double length=p.a().sub(p.b()).horizontalLength();if(length<.001)return List.of();
    var local=new ArrayList<Face>();int green=0x287958;
    for(double x:new double[]{0,Math.max(0,length-.06)})OriginalModels.box(local,x,0,-.05,Math.min(length,x+.06),1.65,.05,green);
    for(double y:new double[]{.08,1.55})OriginalModels.box(local,0,y,-.025,length,y+.045,.025,green);
    // Fine wire uses two crossed planes rather than six faces of a tiny cuboid.
    for(double x=.2;x<length-.06;x+=.2)wire(local,x,.12,x+.016,1.55,green);
    for(double y=.3;y<1.5;y+=.2)wire(local,.06,y,Math.max(.06,length-.06),y+.016,green);
    var along=p.b().sub(p.a());var normal=along.horizontalUnit().left();var out=new ArrayList<Face>();
    for(var f:local){var q=f.points().stream().map(v->p.a().add(along.mul(v.x()/length)).add(normal.mul(v.z())).add(new V(0,v.y(),0))).toList();out.add(new Face(q,f.color(),false,f.texture()));}
    return List.copyOf(out);
  }
  private static void wire(List<Face> out,double x0,double y0,double x1,double y1,int color){
    for(int side:new int[]{-1,1})OriginalModels.face(out,List.of(new V(x0,y0,0),new V(x1,y0,0),new V(x1,y1,0),new V(x0,y1,0)),new V(0,0,side),color,false,Texture.PLAIN);
  }
  private RailbedFence(){}
}
