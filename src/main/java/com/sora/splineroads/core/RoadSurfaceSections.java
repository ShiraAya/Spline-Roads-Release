package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.RoadTerrainMesh.Section;
import java.util.*;

/** Surface draw ranges share the terrain compiler's 16³ ownership boundaries.
 * Furniture keeps its existing 64-block batching. Clipping interpolates both position
 * and UV; a deck exactly on a block boundary belongs to the support cell below it.
 */
public final class RoadSurfaceSections {
  private static final double EPS=1e-8;
  private RoadSurfaceSections(){}
  private record Vertex(V p,RoadSurface.UV uv) {
    Vertex mix(Vertex b,double t){return new Vertex(p.mul(1-t).add(b.p.mul(t)),
        uv==null?null:new RoadSurface.UV(uv.u()+(b.uv.u()-uv.u())*t,uv.v()+(b.uv.v()-uv.v())*t));}
  }
  public static Section owner(RoadSurface.Face face){
    double x=0,y=0,z=0;for(var p:face.points()){x+=p.x();y+=p.y();z+=p.z();}
    double n=face.points().size();return new Section(floor(x/n),floor((y/n)-EPS),floor(z/n));
  }
  private static int floor(double x){return (int)Math.floor(x/16);}
  public static List<RoadSurface.Face> split(List<RoadSurface.Face> faces){
    List<RoadSurface.Face> result=new ArrayList<>();
    for(var face:faces){
      double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=-minX,maxY=-minX,maxZ=-minX;
      for(var p:face.points()){minX=Math.min(minX,p.x());minY=Math.min(minY,p.y());minZ=Math.min(minZ,p.z());maxX=Math.max(maxX,p.x());maxY=Math.max(maxY,p.y());maxZ=Math.max(maxZ,p.z());}
      int x0=floor(minX),x1=floor(maxX-EPS),y0=floor(minY-EPS),y1=floor(maxY-EPS),z0=floor(minZ),z1=floor(maxZ-EPS);
      x1=Math.max(x0,x1);z1=Math.max(z0,z1);
      if(x0==x1&&y0==y1&&z0==z1){result.add(face);continue;}
      // Preserve the renderer's triangle fan, including non-planar quads.
      for(int i=1;i+1<face.points().size();i++){
        var triangle=List.of(vertex(face,0),vertex(face,i),vertex(face,i+1));
        for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++){
          List<Vertex> polygon=clip(triangle,0,x*16.0,true);
          polygon=clip(polygon,0,(x+1)*16.0,false);
          polygon=clip(polygon,1,y*16.0+EPS,true);polygon=clip(polygon,1,(y+1)*16.0+EPS,false);
          polygon=clip(polygon,2,z*16.0,true);
          polygon=clip(polygon,2,(z+1)*16.0,false);
          if(area(polygon)>1e-12)result.add(new RoadSurface.Face(polygon.stream().map(Vertex::p).toList(),
              face.color(),face.emissive(),face.texture(),face.uv().isEmpty()?List.of():polygon.stream().map(Vertex::uv).toList()));
        }
      }
    }
    result.sort(Comparator.comparingInt((RoadSurface.Face f)->owner(f).x())
        .thenComparingInt(f->owner(f).y()).thenComparingInt(f->owner(f).z()));
    return List.copyOf(result);
  }
  private static Vertex vertex(RoadSurface.Face face,int i){return new Vertex(face.points().get(i),face.uv().isEmpty()?null:face.uv().get(i));}
  private static double coordinate(V p,int axis){return axis==0?p.x():axis==1?p.y():p.z();}
  private static List<Vertex> clip(List<Vertex> input,int axis,double plane,boolean above){
    if(input.isEmpty())return input;
    List<Vertex> out=new ArrayList<>();Vertex a=input.get(input.size()-1);double da=coordinate(a.p,axis)-plane;
    for(var b:input){double db=coordinate(b.p,axis)-plane;boolean ina=above?da>=0:da<=0,inb=above?db>=0:db<=0;
      if(ina!=inb)out.add(a.mix(b,da/(da-db)));if(inb)out.add(b);a=b;da=db;
    }return out;
  }
  private static double area(List<Vertex> p){
    double sum=0;if(p.size()<3)return 0;var origin=p.get(0).p;
    for(int i=1;i+1<p.size();i++){var a=p.get(i).p.sub(origin);var b=p.get(i+1).p.sub(origin);
      double x=a.y()*b.z()-a.z()*b.y(),y=a.z()*b.x()-a.x()*b.z(),z=a.x()*b.y()-a.y()*b.x();sum+=Math.sqrt(x*x+y*y+z*z);}
    return sum*.5;
  }
}
