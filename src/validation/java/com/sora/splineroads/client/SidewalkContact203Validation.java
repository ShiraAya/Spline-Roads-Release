package com.sora.splineroads.client;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;

/** Exact generated footway contacts and non-mutating vanilla quad extension. */
public final class SidewalkContact203Validation {
 static int checks,contacts,misses;
 static void ok(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static List<V> square(double x,double z,double y,double span){return List.of(new V(x,y,z),new V(x+span,y,z),new V(x+span,y,z+span),new V(x,y,z+span));}
 public static void main(String[] args){
  ok(Math.abs(SidewalkBlockContact.bottom(0,1,0,List.of(square(0,0,.2,1)))+.8)<1e-8,"flat 0.8 gap");
  ok(SidewalkBlockContact.bottom(0,1,0,List.of(square(.5,0,.2,1)))==0,"partial footprint must not attach");
  ok(SidewalkBlockContact.bottom(0,1,0,List.of(square(0,0,1,1)))==0,"flush floor no stretch");
  ok(SidewalkBlockContact.bottom(0,2,0,List.of(square(0,0,.2,1)))==0,"no two-block floating attachment");
  ok(SidewalkBlockContact.bottom(0,1,0,List.of(square(0,0,1.2,1)))==0,"no surface above block base");
  for(double yaw:new double[]{0,23,90})for(double grade:new double[]{-.02,0,.02})for(int side:new int[]{-1,1}){
   var start=new Node(new V(-30.35,4.35,-20.35),yaw,grade);var end=new Node(start.position().add(start.direction().mul(40)).add(new V(0,40*grade,0)),yaw,grade);
   var s=RoadLanes.configure(new Settings(Mode.STRAIGHT,Style.O2_YELLOW,9,1,.35,90).structure(Structure.GROUND),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(1,1),4);
   s=s.options(s.options().extras(false,false,false).sidewalk(new RoadSidewalks.Config(true,RoadSidewalks.Side.BOTH,5,"minecraft:bricks")));
   var mesh=RoadGeometry.build(start,end,s);var parts=RoadSidewalks.parts(mesh,s.options().sidewalk());var local=new RoadRaster.Local(mesh,parts);
   for(double station:new double[]{4,8,12,18,22,28,32}){
    var sample=RoadStructures.sample(mesh,station);var p=sample.at(side*(sample.halfWidth()+2.5),.2);int x=(int)Math.floor(p.x()),z=(int)Math.floor(p.z()),y=(int)Math.ceil(p.y());
    var cell=new RoadRaster.Cell(x,y-1,z);var faces=local.structureTops(cell,q->q.material().name().startsWith("WALK_"));
    double bottom=SidewalkBlockContact.bottom(x,y,z,faces);
    var clipped=faces.stream().map(f->RoadSurface.intersect(f,square(x,z,0,1))).flatMap(List::stream).toList();
    double low=clipped.stream().mapToDouble(V::y).min().orElseThrow(),high=clipped.stream().mapToDouble(V::y).max().orElseThrow();
    if(low>=y-1+1e-6&&high<y-1e-6){ok(bottom<0&&Math.abs(bottom-(low-y))<1e-8,"generated footway did not attach "+yaw+" "+grade+" "+side+" "+p);contacts++;}
    else ok(bottom==0,"integer-boundary crossing unexpectedly stretched");
    ok(local.structureTops(cell,q->false).isEmpty(),"material predicate ignored");
    ok(SidewalkBlockContact.bottom(x,y+2,z,faces)==0,"distant cube stretched");
    var outside=sample.at(side*(sample.halfWidth()+6),.2);int xx=(int)Math.floor(outside.x()),zz=(int)Math.floor(outside.z());
    ok(SidewalkBlockContact.bottom(xx,y,zz,local.structureTops(new RoadRaster.Cell(xx,y-1,zz),q->q.material().name().startsWith("WALK_")))==0,"outside footway stretched");misses++;
   }
  }
  ok(contacts>=40,"insufficient generated contact positive controls "+contacts);
  int[] packed=new int[32];double[][] positions={{0,0,0},{0,1,0},{1,1,0},{1,0,0}};
  for(int i=0;i<4;i++){for(int j=0;j<3;j++)packed[i*8+j]=Float.floatToRawIntBits((float)positions[i][j]);for(int j=3;j<8;j++)packed[i*8+j]=100+i*8+j;}
  var quad=new BakedQuad(packed,2,Direction.NORTH,null,true,false);var before=packed.clone();var result=SidewalkBlockQuads.extend(List.of(quad),-.8).get(0);
  ok(Arrays.equals(packed,before),"shared original vertices mutated");ok(result.getDirection()==quad.getDirection()&&result.getTintIndex()==2&&result.isShade()&&!result.hasAmbientOcclusion(),"quad metadata changed");
  for(int i=0;i<4;i++){ok(Math.abs(Float.intBitsToFloat(result.getVertices()[i*8+1])-(i==0||i==3?-.8:1))<1e-6,"base/top wrong");for(int j=0;j<8;j++)if(j!=1)ok(result.getVertices()[i*8+j]==before[i*8+j],"UV/color/normal moved");}
  ok(SidewalkBlockQuads.extend(List.of(quad),0d).get(0)==quad,"inactive block rewritten");
  System.out.println("SR203 SIDEWALK CONTACT PASS checks="+checks+" generatedContacts="+contacts+" outsideControls="+misses);
 }
}



