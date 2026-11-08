package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import com.sora.splineroads.core.RoadSurface.*;
import java.util.*;
/** Automatic unsignalled crossing markers, owned and removed with the road profile. */
public final class RailwayCrossingSigns {
  public static final String MODEL="sr:railway_crossing_sign";
  public static List<Part> plan(Mesh mesh,Ground ground){
    var out=new ArrayList<Part>();for(var crossing:mesh.settings().options().levelCrossings())for(int end:new int[]{-1,1}){
      int side=end*(mesh.settings().options().leftTraffic()?-1:1);
      for(double setback:new double[]{2,4,6}){double station=(end<0?crossing.from():crossing.to())+end*setback;if(station<1||station>mesh.length()-1)continue;
        var at=RoadStructures.sample(mesh,station);double walk=RoadSidewalks.walkWidth(mesh,mesh.settings().options().sidewalk(),at,side);
        var foot=at.at(side*(at.halfWidth()+walk+1.2),0);double floor=ground.top(foot.x(),foot.z(),foot.y()+1);
        if(!Double.isFinite(floor)||Math.abs(floor-foot.y())>1.5)continue;foot=new V(foot.x(),floor,foot.z());
        var sign=new Part(foot.sub(at.left().mul(.6)),foot.add(at.left().mul(.6)),.12,3.2,false,Material.STEEL,null,null,MODEL);
        if(!ground.blocked(sign)&&out.stream().noneMatch(p->p.a().distance(sign.a())<2)){out.add(sign);break;}
      }
    }return List.copyOf(out);
  }
  public static List<Face> faces(Part p){
    var local=new ArrayList<Face>();for(double y=0;y<2.6;y+=.4)OriginalModels.box(local,-.06,y,-.06,.06,Math.min(y+.4,2.6),.06,((int)Math.round(y/.4)%2)==0?0xECEDE7:0xBD342D);
    for(int side:new int[]{-1,1}){var bar=new ArrayList<Face>();for(int i=0;i<3;i++)OriginalModels.box(bar,i==0?-.6:i==1?-.4:.4,-.07,-.035,i==0?-.4:i==1?.4:.6,.07,.035,i==1?0xECEDE7:0xBD342D);
      double angle=side*Math.toRadians(35),c=Math.cos(angle),s=Math.sin(angle);for(var face:bar)local.add(new Face(face.points().stream().map(v->new V(v.x()*c-v.y()*s,2.65+v.x()*s+v.y()*c,v.z())).toList(),face.color(),false,Texture.PLAIN));
    }
    var origin=p.a().add(p.b()).mul(.5);var right=p.b().sub(p.a()).horizontalUnit();var front=right.left();return local.stream().map(f->new Face(f.points().stream().map(v->origin.add(right.mul(v.x())).add(front.mul(v.z())).add(new V(0,v.y(),0))).toList(),f.color(),false,Texture.PLAIN)).toList();
  }
  private RailwayCrossingSigns(){}
}
