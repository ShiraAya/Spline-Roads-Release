package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Filled top-down view. Internal tessellation edges are deliberately never displayed. */
public final class JunctionPreview {
  public record Label(int arm,int x,int y) {}
  public record Image(int width,int height,int[] pixels,List<Label> labels) {}
  public static Image render(JunctionPlanner.Plan plan,int width,int height,int arm,int lane,boolean movement) {
    var result=new Painter(plan,width,height);
    var walks=new LinkedHashSet<RoadSidewalks.Cell>(plan.sidewalks().keySet());
    for(var piece:plan.pieces())if(piece.arm()>=0)walks.addAll(RoadSidewalks.cells(piece.mesh(),piece.mesh().settings().options().sidewalk()));
    for(var face:RoadSidewalks.preview(walks))result.polygon(face.points(),0xff8fa19b);
    for(var piece:plan.pieces()) {
      var points=piece.mesh().samples();
      int color=piece.arm()==arm?0xff506974:0xff50565d;
      for(int i=1;i<points.size();i++) {
        Sample a=points.get(i-1),b=points.get(i);
        for(var strip:LaneDeck.strips(piece.mesh(),a,b))result.polygon(List.of(strip.al(),strip.ar(),strip.br(),strip.bl()),color);
      }
    }
    var seen=new HashSet<RoadSurface.Face>();
    for(var piece:plan.pieces())for(var face:piece.paint())if(seen.add(face))result.marking(face.points(),0xff000000|face.color());
    for(var piece:plan.pieces())for(var part:piece.structures()) {
      if(RoadSignals.signal(part)||RoadPoleModel.support(part))continue;
      int color=switch(part.material()){case GREEN->0xff71995b;case SOIL->0xff776447;case CONCRETE->0xffa8aaa5;default->0xffb0bdc5;};
      result.polygon(part.base(),color);
    }
    if(movement)for(var move:plan.movements())if(move.from()==arm&&move.lane()==lane) {
      for(int k=1;k<move.path().size();k++)result.line(move.path().get(k-1),move.path().get(k),0xfff4cb77);
      int at=move.path().size()*3/4;
      V center=move.path().get(at),f=move.path().get(at+1).sub(move.path().get(at-1)).horizontalUnit();
      result.line(center,center.sub(f.mul(2)).add(f.left()),0xfff4cb77);
      result.line(center,center.sub(f.mul(2)).sub(f.left()),0xfff4cb77);
    }
    var labels=new ArrayList<Label>();
    for(var piece:plan.pieces())if(piece.arm()>=0){V p=piece.mesh().first().center();labels.add(new Label(piece.arm(),result.x(p),result.y(p)));}
    return new Image(width,height,result.pixels,List.copyOf(labels));
  }
  static final class Painter {
    final int w,h;final int[] pixels;final double scale,ox,oz;
    Painter(JunctionPlanner.Plan plan,int w,int h) {
      this.w=w;this.h=h;pixels=new int[w*h];Arrays.fill(pixels,0xff202c30);
      double minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=-minX,maxZ=-minX;
      for(var piece:plan.pieces()){minX=Math.min(minX,piece.mesh().min().x());minZ=Math.min(minZ,piece.mesh().min().z());maxX=Math.max(maxX,piece.mesh().max().x());maxZ=Math.max(maxZ,piece.mesh().max().z());}
      scale=Math.min((w-30)/Math.max(1,maxX-minX),(h-30)/Math.max(1,maxZ-minZ));
      ox=w/2.-(minX+maxX)*scale/2;oz=h/2.-(minZ+maxZ)*scale/2;
    }
    Painter(V min,V max,int w,int h){this.w=w;this.h=h;pixels=new int[w*h];Arrays.fill(pixels,0xff202c30);scale=Math.min((w-30)/Math.max(1,max.x()-min.x()),(h-30)/Math.max(1,max.z()-min.z()));ox=w/2.-(min.x()+max.x())*scale/2;oz=h/2.-(min.z()+max.z())*scale/2;}
    int x(V p){return (int)Math.round(ox+p.x()*scale);}int y(V p){return (int)Math.round(oz+p.z()*scale);}
    void pixel(int x,int y,int color){if(x>=0&&x<w&&y>=0&&y<h)pixels[y*w+x]=color;}
    void line(V a,V b,int color){int x=x(a),y=y(a),dx=x(b)-x,dy=y(b)-y,n=Math.max(1,Math.max(Math.abs(dx),Math.abs(dy)));for(int k=0;k<=n;k++)pixel(x+dx*k/n,y+dy*k/n,color);}
    void marking(List<V> poly,int color){
      polygon(poly,color);
      double minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=-minX,maxZ=-minX;
      for(V p:poly){minX=Math.min(minX,p.x());minZ=Math.min(minZ,p.z());maxX=Math.max(maxX,p.x());maxZ=Math.max(maxZ,p.z());}
      if(Math.min(maxX-minX,maxZ-minZ)*scale<1){V a=poly.get(0),b=a;double longest=0;for(V p:poly)for(V q:poly)if(p.distance(q)>longest){longest=p.distance(q);a=p;b=q;}line(a,b,color);}
    }
    void polygon(List<V> poly,int color) {
      int minY=h,maxY=-1;for(V p:poly){minY=Math.min(minY,(int)Math.floor(oz+p.z()*scale-.5));maxY=Math.max(maxY,(int)Math.ceil(oz+p.z()*scale));}
      for(int row=Math.max(0,minY);row<=Math.min(h-1,maxY);row++) {
        var intersections=new ArrayList<Double>();
        for(int i=0;i<poly.size();i++){
          V a=poly.get(i),b=poly.get((i+1)%poly.size());double ax=ox+a.x()*scale,ay=oz+a.z()*scale,bx=ox+b.x()*scale,by=oz+b.z()*scale;
          if((ay<=row+.5&&by>row+.5)||(by<=row+.5&&ay>row+.5))intersections.add(ax+(row+.5-ay)*(bx-ax)/(by-ay));
        }
        Collections.sort(intersections);
        for(int i=1;i<intersections.size();i+=2){int lo=Math.max(0,(int)Math.floor(intersections.get(i-1))),hi=Math.min(w-1,(int)Math.ceil(intersections.get(i)));for(int col=lo;col<=hi;col++)pixels[row*w+col]=color;}
      }
    }
  }
  private JunctionPreview() {}
}
