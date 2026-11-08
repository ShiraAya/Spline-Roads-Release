package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.RoadStructures.*;
import com.sora.splineroads.core.RoadStreetscape.Planting;
import java.util.*;

/** Deterministic branching trees. Variants change crown topology, not just scale. */
final class RoadTrees {
  static List<Part> model(V foot,Planting type,V along){
    if(type==Planting.NONE||type==Planting.FLOWERS)return List.of();
    int variant=RoadStreetscape.treeVariant(foot,type);
    along=along.horizontalUnit();V side=along.left();
    Material bark=switch(type){case BIRCH->Material.BIRCH_LOG;case SPRUCE->Material.SPRUCE_LOG;case CHERRY->Material.CHERRY_LOG;default->Material.OAK_LOG;};
    Material leaf=switch(type){case BIRCH->Material.BIRCH_LEAVES;case SPRUCE->Material.SPRUCE_LEAVES;case CHERRY->Material.CHERRY_LEAVES;default->Material.OAK_LEAVES;};
    var out=new ArrayList<Part>();double fork=4.7;
    double stem=type==Planting.BIRCH?.34:type==Planting.SPRUCE?.44:.52;
    out.add(new Part(foot,foot,stem,fork+.45,true,bark));
    V root=foot.add(new V(0,fork,0));
    if(type==Planting.SPRUCE){
      // Whorled, tapered conifers: full, open, swept, twin leader, uneven and broad-base.
      int tiers=new int[]{5,4,6,4,5,3}[variant];
      double step=new double[]{.82,1.15,.72,1.02,.9,1.3}[variant];
      out.add(new Part(root,root,.28,tiers*step+.65,true,bark));
      for(int tier=0;tier<tiers;tier++){
        double radius=(variant==5?2.35:1.95)*(1-tier/(double)(tiers+.3));
        V center=root.add(along.mul(variant==2?tier*.13:0)).add(new V(0,tier*step,0));
        int spokes=(variant==1?4:5)+(tier+variant)%2;
        for(int k=0;k<spokes;k++){
          double angle=k*2*Math.PI/spokes+tier*.63+variant*.7;
          V dir=along.mul(Math.cos(angle)).add(side.mul(Math.sin(angle)));
          double reach=radius*(variant==4&&k%2==0?.66:1);
          V tip=center.add(dir.mul(reach));
          branch(out,center,tip.add(new V(0,.22,0)),.13,bark);
          crown(out,center.add(dir.mul(reach*.65)),along,reach*.72,.64,.62,leaf,variant+k);
        }
        crown(out,center,along,radius*.66,radius*.66,.9,leaf,tier);
      }
      crown(out,root.add(new V(0,tiers*step-.15,0)),along,.36,.36,.95,leaf,variant);
      if(variant==3){V split=root.add(side.mul(.9)).add(new V(0,1.4,0));branch(out,root,split,.19,bark);crown(out,split,along,.68,.68,2.2,leaf,4);}
    }else{
      // Distinct branch graphs: round, spreading, forked, irregular, tiered and open vase.
      int arms=new int[]{5,4,2,6,3,5}[variant];
      double reach=type==Planting.CHERRY?2.05:type==Planting.BIRCH?1.05:1.65;
      double rise=type==Planting.BIRCH?2.0:type==Planting.CHERRY?.65:1.15;
      for(int arm=0;arm<arms;arm++){
        double angle=arm*2*Math.PI/arms+variant*.67;
        V dir=along.mul(Math.cos(angle)).add(side.mul(Math.sin(angle)));
        double spread=reach*(variant==1?1.15:variant==2?.82:variant==5?1.08:1);
        double uneven=variant==3?(arm%3-1)*.45:variant==4?arm*.52:0;
        V elbow=root.add(dir.mul(spread*.48)).add(new V(0,.4+Math.max(0,uneven),0));
        V tip=root.add(dir.mul(spread)).add(new V(0,rise+uneven,0));
        branch(out,root,elbow,stem*.65,bark);branch(out,elbow,tip,.19,bark);
        double rx=type==Planting.BIRCH?.85:type==Planting.CHERRY?1.45:1.2;
        double ry=type==Planting.BIRCH?2.3:type==Planting.CHERRY?1.15:1.65;
        crown(out,tip,along,rx,rx*(variant==1?1.1:.85),ry,leaf,variant+arm);
        if(variant==2||variant==3||variant==5){
          V twig=tip.add(side.mul((arm%2==0?1:-1)*.65)).add(new V(0,.65,0));
          branch(out,elbow,twig,.12,bark);crown(out,twig,along,rx*.75,rx*.7,ry*.78,leaf,arm+2);
        }
      }
      if(variant!=5){
        double height=type==Planting.BIRCH?2.65:type==Planting.CHERRY?1.1:1.8;
        V top=root.add(new V(0,height,0));branch(out,root,top,stem*.6,bark);
        crown(out,top,along,type==Planting.BIRCH?.9:1.6,type==Planting.BIRCH?.85:1.35,type==Planting.CHERRY?1.25:1.65,leaf,variant+1);
      }
    }
    return List.copyOf(out);
  }
  private static void branch(List<Part> out,V a,V b,double size,Material material){out.add(new Part(a,b,size,size,false,material));}
  /** Stepped leaf masses, with clipped corners and offset lobes instead of flat discs. */
  private static void crown(List<Part> out,V base,V along,double rx,double rz,double height,Material leaf,int shape){
    V side=along.left();
    double[] profile=shape%3==0?new double[]{.62,.92,1,.76,.42}:shape%3==1?new double[]{.78,1,.88,.65,.3}:new double[]{.48,.8,1,.9,.56};
    for(int level=0;level<profile.length;level++){
      double f=profile[level],dy=height*level/profile.length;
      V center=base.add(along.mul(Math.sin(shape+level*1.2)*.09)).add(side.mul(Math.cos(shape*1.7+level)*.08)).add(new V(0,dy,0));
      double h=height/profile.length+.035;
      box(out,center,along,2*rx*f,1.32*rz*f,h,leaf);
      box(out,center,along,1.32*rx*f,2*rz*f,h,leaf);
    }
  }
  private static void box(List<Part> out,V center,V along,double length,double width,double height,Material material){
    out.add(new Part(center.sub(along.mul(length/2)),center.add(along.mul(length/2)),width,height,false,material));
  }
  private RoadTrees(){}
}
