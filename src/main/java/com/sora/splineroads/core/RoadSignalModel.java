package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.RoadStructures.Part;
import com.sora.splineroads.core.RoadSurface.*;
import java.io.*;
import java.util.*;

/** Kenney CC0 housings adapted to SR scale, palette and independent signal states. */
public final class RoadSignalModel {
  private record Polygon(int lamp,int color,List<V> points){}
  private static final List<Polygon> HORIZONTAL=load("horizontal"),VERTICAL=load("vertical");
  private static List<Polygon> load(String name){
    try(var stream=RoadSignalModel.class.getResourceAsStream("/assets/splineroads/models/road/sr_signal_"+name+".mesh")){
      if(stream==null)throw new IOException("Missing SR signal: "+name);var in=new DataInputStream(stream);int count=in.readInt();if(count<1||count>10000)throw new IOException("Invalid SR signal");var out=new ArrayList<Polygon>();
      for(int i=0;i<count;i++){int lamp=in.readInt(),color=in.readInt(),n=in.readInt();if(n<3||n>8)throw new IOException("Invalid signal polygon");var p=new ArrayList<V>();for(int k=0;k<n;k++)p.add(new V(in.readFloat(),in.readFloat(),in.readFloat()));out.add(new Polygon(lamp,color,List.copyOf(p)));}return List.copyOf(out);
    }catch(IOException e){throw new IllegalStateException(e);}
  }
  public static List<Face> faces(Part part,boolean lenses,boolean green){return faces(part,lenses,green?1:0);}
  public static List<Face> faces(Part part,boolean lenses,int state){
    if(RoadJunctionSignalModel.supports(part))return RoadJunctionSignalModel.faces(part,lenses,state);
    return head(part,lenses,state,false,false);
  }
  static List<Face> head(Part part,boolean lamps,int state,boolean vertical,boolean arrow){
    boolean right=part.model().equals("sr:signal_right");arrow|=right;
    var local=new ArrayList<Face>();for(var q:vertical?VERTICAL:HORIZONTAL){if((q.lamp()>=0)!=lamps)continue;boolean on=q.lamp()==state;
      int color=lamps?on?lampColor(state):0x23312E:q.color();
      local.add(new Face(vertical||part.model().equals("sr:centered_horizontal")?q.points():q.points().stream().map(v->v.add(new V(0,-.19690625,0))).toList(),arrow&&lamps?0x17221F:color,lamps&&on&&!arrow));
    }
    // A continuous rear spine joins both source-model lugs to the actual mast arm.
    // Apply the housing's exact origin shift to its spine, including tagged
    // centered diamond/SPUI heads, so the back clip cannot remain at the old height.
    if(!lamps){
      if(vertical)OriginalModels.bevel(local,-.075,.17,-.065,.075,1.23,.12,.025,0x8A969D);
      else {double dy=part.model().equals("sr:centered_horizontal")?0:-.19690625;OriginalModels.bevel(local,-.075,.15+dy,-.48,.075,.65+dy,-.15,.025,0x8A969D);}
    }
    if(arrow&&lamps)for(int lamp=0;lamp<3;lamp++){
      double y=(lamp==0?.11265375:lamp==2?.07159375:.03053375)*9.78;int c=lamp==state?lampColor(state):0x33413B;
      // Driver-left arrow in the plane just ahead of the lens.
      if(!right)arrow(local,new V(0,y,.715),.125,c,lamp==state);
      else {var glyph=new ArrayList<Face>();arrow(glyph,new V(0,y,.715),.125,c,lamp==state);for(var f:glyph){var points=new ArrayList<>(f.points().stream().map(v->new V(-v.x(),v.y(),v.z())).toList());Collections.reverse(points);local.add(new Face(points,f.color(),f.emissive(),f.texture()));}}
    }
    V front=part.b().sub(part.a()).horizontalUnit(),side=front.left().mul(-1);V origin=vertical?part.a():part.a().add(part.b()).mul(.5);
    return OriginalModels.transform(local,origin,side,new V(0,1,0),front);
  }
  static int lampColor(int state){return state==1?0x49FF82:state==2?0xFFC84A:0xFF493C;}
  static void arrow(List<Face> out,V c,double size,int color,boolean on){
    OriginalModels.face(out,List.of(c.add(new V(-size*.1,-size*.23,0)),c.add(new V(size,-size*.23,0)),c.add(new V(size,size*.23,0)),c.add(new V(-size*.1,size*.23,0))),new V(0,0,1),color,on,Texture.PLAIN);
    OriginalModels.face(out,List.of(c.add(new V(-size,0,0)),c.add(new V(-size*.05,-size*.85,0)),c.add(new V(-size*.05,size*.85,0))),new V(0,0,1),color,on,Texture.PLAIN);
  }
  private RoadSignalModel(){}
}
