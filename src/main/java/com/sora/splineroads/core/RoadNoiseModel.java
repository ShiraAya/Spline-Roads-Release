package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.RoadStructures.*;
import com.sora.splineroads.core.RoadSurface.*;
import java.util.*;

/** Original SR acoustic panels: fluted base, glazed top and inward acoustic cap. */
public final class RoadNoiseModel {
  public static final double HEIGHT=2.5043584,BASE_HEIGHT=.5;
  public static List<Part> assembly(Part p){var up=new V(0,BASE_HEIGHT,0);return List.of(new Part(p.a(),p.b(),p.width(),BASE_HEIGHT,false,Material.CONCRETE,p.frameA(),p.frameB()),new Part(p.a().add(up),p.b().add(up),p.width(),p.height(),false,p.material(),p.frameA(),p.frameB(),p.model()));}
  public static List<Face> faces(Part p,boolean distant){
    double length=p.a().sub(p.b()).horizontalLength();var local=new ArrayList<Face>();
    OriginalModels.box(local,0,0,-.075,length,1.35,.075,SmartSigns.GREEN);
    OriginalModels.box(local,0,1.34,-.11,length,1.43,.11,0xB6C5C9);
    OriginalModels.box(local,0,.02,-.11,length,.11,.11,0xB6C5C9);
    for(double x:new double[]{0,Math.max(0,length-.075)})OriginalModels.box(local,x,0,-.13,x+.075,2.28,.13,0xCBD4D6);
    for(int front:new int[]{-1,1}){
      OriginalModels.face(local,List.of(new V(.075,1.43,front*.025),new V(length-.075,1.43,front*.025),new V(length-.075,2.2,front*.025),new V(.075,2.2,front*.025)),new V(0,0,front),0xBED3BF,false,Texture.NOISE_BARRIER_GLASS);
      if(!distant)for(double y=.23;y<1.3;y+=.16)OriginalModels.box(local,.075,y,front>0?.075:-.094,length-.075,y+.035,front>0?.094:-.075,0x4A9D5A);
    }
    OriginalModels.box(local,0,2.2,-.11,length,2.28,.11,0xB6C5C9);
    // Folded solid cap reaches inward, leaving the outer silhouette clean.
    OriginalModels.prism(local,List.of(new V(0,0,-.1),new V(length,0,-.1),new V(length,0,-.4),new V(0,0,-.4)),2.28,2.36,0x247638);
    OriginalModels.box(local,0,2.36,-.4,length,HEIGHT,-.33,0xB6C5C9);
    V along=p.b().sub(p.a()),normal=along.horizontalUnit().left();V na=p.frameA()==null?normal:p.frameA().horizontalUnit(),nb=p.frameB()==null?normal:p.frameB().horizontalUnit();boolean mirror=OriginalModels.cross(along,new V(0,1,0)).dot(na)<0;
    var out=new ArrayList<Face>();for(var f:local){var q=new ArrayList<V>();for(var v:f.points()){double t=v.x()/length;q.add(p.a().add(along.mul(t)).add(na.mul(1-t).add(nb.mul(t)).mul(v.z())).add(new V(0,v.y(),0)));}if(mirror)Collections.reverse(q);out.add(new Face(List.copyOf(q),f.color(),f.emissive(),f.texture()));}return List.copyOf(out);
  }
  private RoadNoiseModel(){}
}
