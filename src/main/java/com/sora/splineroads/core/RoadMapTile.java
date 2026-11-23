package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/** A bounded, map-only projection. Keeps stacked surfaces separate by height;
 * no world reads, collision raster, full render mesh or decoration model bake. */
public final class RoadMapTile {
  public enum Material { ASPHALT, GREEN, STONE_BRICKS, BRICKS, ANDESITE, DIORITE,
    GRANITE, SMOOTH_STONE, YELLOW, CONCRETE, SOIL, OAK, BIRCH, SPRUCE, CHERRY }
  public record Source(UUID id, Mesh mesh, List<Part> parts) {}
  private record Pixel(double top, double area, Material material) {}
  private final Int2ObjectOpenHashMap<Pixel> pixels=new Int2ObjectOpenHashMap<>();
  private final int x0,z0;
  public RoadMapTile(int chunkX,int chunkZ,List<Source> sources){
    x0=chunkX*16;z0=chunkZ*16;
    for(var source:sources){
      var mesh=source.mesh();
      if(mesh.max().x()>=x0&&mesh.min().x()<x0+16&&mesh.max().z()>=z0&&mesh.min().z()<z0+16)
        for(var piece:LaneDeck.rasterPieces(mesh,96)){
          var samples=piece.samples();
          for(int i=1;i<samples.size();i++){
            var a=samples.get(i-1);var b=samples.get(i);double w=Math.max(a.halfWidth(),b.halfWidth());
            if(!near(a.center(),b.center(),w))continue;
            for(var strip:LaneDeck.strips(piece,a,b))quad(List.of(strip.al(),strip.ar(),strip.br(),strip.bl()),Material.ASPHALT);
          }
        }
      for(var part:source.parts()){
        Material material=material(part);if(material!=null&&near(part.a(),part.b(),part.halfExtent()))quad(part.top(),material);
      }
    }
  }
  private boolean near(V a,V b,double w){return Math.max(a.x(),b.x())+w>=x0&&Math.min(a.x(),b.x())-w<x0+16&&Math.max(a.z(),b.z())+w>=z0&&Math.min(a.z(),b.z())-w<z0+16;}
  private static int key(int x,int y,int z){return (y<<8)|((z&15)<<4)|(x&15);}
  public Material at(int x,int y,int z){var p=pixels.get(key(x,y,z));return p==null?null:p.material();}
  public int size(){return pixels.size();}
  private static Material material(Part p){
    return switch(p.material()){
      case GREEN -> Material.GREEN;
      case WALK_STONE_BRICKS -> Material.STONE_BRICKS;case WALK_BRICKS -> Material.BRICKS;
      case WALK_ANDESITE -> Material.ANDESITE;case WALK_DIORITE -> Material.DIORITE;
      case WALK_GRANITE -> Material.GRANITE;case WALK_SMOOTH_STONE -> Material.SMOOTH_STONE;
      case TACTILE -> Material.YELLOW;case SOIL -> Material.SOIL;
      case OAK_LEAVES -> Material.OAK;case BIRCH_LEAVES -> Material.BIRCH;
      case SPRUCE_LEAVES -> Material.SPRUCE;case CHERRY_LEAVES -> Material.CHERRY;
      // Broad wall/roof tops are visible; poles, railings and their collision
      // envelopes must not cover a road with elevated grey pixels.
      case TUNNEL,CONCRETE -> p.width()>=.7?Material.CONCRETE:null;
      default -> null;
    };
  }
  private void quad(List<V> q,Material material){
    for(int i=1;i+1<q.size();i++)triangle(List.of(q.get(0),q.get(i),q.get(i+1)),material);
  }
  private void triangle(List<V> triangle,Material material){
    int minX=Math.max(x0,(int)Math.floor(triangle.stream().mapToDouble(V::x).min().orElseThrow()));
    int maxX=Math.min(x0+15,(int)Math.floor(triangle.stream().mapToDouble(V::x).max().orElseThrow()));
    int minZ=Math.max(z0,(int)Math.floor(triangle.stream().mapToDouble(V::z).min().orElseThrow()));
    int maxZ=Math.min(z0+15,(int)Math.floor(triangle.stream().mapToDouble(V::z).max().orElseThrow()));
    for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++){
      var poly=clip(clip(clip(clip(triangle,0,x,true),0,x+1,false),1,z,true),1,z+1,false);
      if(poly.size()<3)continue;double area=0,top=Double.NEGATIVE_INFINITY;
      for(int i=0;i<poly.size();i++){var a=poly.get(i);var b=poly.get((i+1)%poly.size());area+=(a.x()-x)*(b.z()-z)-(b.x()-x)*(a.z()-z);top=Math.max(top,a.y());}
      area=Math.abs(area)*.5;if(area<.015)continue;
      int k=key(x,(int)Math.floor(top-1e-6),z);var old=pixels.get(k);
      if(old==null||top>old.top()+.015||Math.abs(top-old.top())<=.015&&area>old.area())pixels.put(k,new Pixel(top,area,material));
    }
  }
  private static double coord(V p,int axis){return axis==0?p.x():p.z();}
  private static List<V> clip(List<V> source,int axis,double edge,boolean greater){
    if(source.isEmpty())return source;var out=new ArrayList<V>(6);var a=source.get(source.size()-1);double da=coord(a,axis)-edge;boolean insideA=greater?da>=0:da<=0;
    for(var b:source){double db=coord(b,axis)-edge;boolean insideB=greater?db>=0:db<=0;
      if(insideA!=insideB)out.add(a.add(b.sub(a).mul(da/(da-db))));if(insideB)out.add(b);a=b;da=db;insideA=insideB;
    }return out;
  }
}
