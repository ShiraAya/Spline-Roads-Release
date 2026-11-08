package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.RoadStructures.*;
import com.sora.splineroads.core.RoadSurface.*;
import java.util.*;

/** Kenney vehicle head plus SR-authored two-aspect pedestrian head. */
public final class RoadJunctionSignalModel {
  public static boolean supports(Part p){return p.material()==Material.SIGNAL_VEHICLE||p.material()==Material.SIGNAL_PEDESTRIAN||p.material()==Material.SIGNAL_LEFT;}
  public static List<Face> faces(Part p,boolean lamps,int state){
    if(p.material()!=Material.SIGNAL_PEDESTRIAN)return RoadSignalModel.head(p,lamps,state,true,p.material()==Material.SIGNAL_LEFT);
    var local=new ArrayList<Face>();
    if(!lamps){
      OriginalModels.bevel(local,-.29,0,-.13,.29,1.05,.23,.06,0x424D55);
      for(double y:new double[]{.29,.76}){
        OriginalModels.box(local,-.24,y-.2,.23,.24,y+.2,.27,0x8A969D);
        OriginalModels.box(local,-.20,y-.17,.27,.20,y+.17,.28,0x18241F);
        OriginalModels.box(local,-.22,y+.17,.25,.22,y+.20,.46,0x222C32);
      }
      OriginalModels.bevel(local,-.12,.38,-.51,.12,.66,-.13,.035,0x727F84);
    }else for(boolean walking:new boolean[]{false,true}){
      double cy=walking?.29:.76;boolean on=walking?state==1:state!=1;int color=on?RoadSignalModel.lampColor(walking?1:0):0x2A3830;
      String[] glyph=walking?new String[]{"0011000","0011000","0001000","0111100","1001010","0001001","0010100","0100010","1000001"}:new String[]{"0011100","0011100","0001000","0111110","0101010","0101010","0010100","0010100","0010100"};
      for(int y=0;y<glyph.length;y++)for(int x=0;x<7;x++)if(glyph[y].charAt(x)=='1'){
        double x0=(x-3.5)*.036,y0=cy+(3.5-y)*.034;
        OriginalModels.face(local,List.of(new V(x0,y0,.283),new V(x0+.036,y0,.283),new V(x0+.036,y0+.034,.283),new V(x0,y0+.034,.283)),new V(0,0,1),color,on,Texture.PLAIN);
      }
    }
    V front=p.b().sub(p.a()).horizontalUnit();return OriginalModels.transform(local,p.a().add(front.mul(.247)),front.left().mul(-.96),new V(0,.98,0),front.mul(.95));
  }
  private RoadJunctionSignalModel(){}
}
