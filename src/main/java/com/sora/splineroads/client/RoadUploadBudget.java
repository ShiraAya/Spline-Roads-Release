package com.sora.splineroads.client;
import com.sora.splineroads.core.*;
import java.util.*;
/** 288 KiB at the current 36-byte NEW_ENTITY vertex stride, not a whole region in one GL call. */
public final class RoadUploadBudget {
  public static final int MAX_VERTICES=8192;
  private static final int UNIT_BYTES=MAX_VERTICES*36,FRAME_BYTES=4*UNIT_BYTES;
  private static final int MAX_SIGN_BYTES=16*1024*1024;
  private static boolean active,uploadsAllowed;
  private static int signReserve;
  private static long deadline;
  private static int roadBytes,signBytes,roadUploads,signUploads;
  static void beginFrame(long micros,boolean signsWaiting){beginFrame(micros,signsWaiting?UNIT_BYTES:0);}
  static void beginFrame(long micros,int signBytesWaiting){active=uploadsAllowed=true;signReserve=Math.min(MAX_SIGN_BYTES,Math.max(0,signBytesWaiting));deadline=System.nanoTime()+micros*1000L;roadBytes=signBytes=roadUploads=signUploads=0;}
  static void drawOnly(){active=true;uploadsAllowed=false;}
  static void endFrame(){active=false;}
  static boolean claimRoad(int bytes){return claim(bytes,false);}
  static boolean claimSign(int bytes){return claim(bytes,true);}
  private static boolean claim(int bytes,boolean sign){
    if(!active)return true;
    if(!uploadsAllowed)return false;
    int used=roadBytes+signBytes,own=sign?signUploads:roadUploads;
    if(sign&&signReserve>FRAME_BYTES&&signUploads==0&&bytes<signReserve)return false;
    // Each family can make one bounded forward-progress upload. Reserving a
    // plate slice prevents a stream of road regions from starving sign updates.
    int limit=sign?FRAME_BYTES:Math.max(0,FRAME_BYTES-signReserve);
    // A plate VBO is indivisible. An exceptional plate larger than the normal
    // byte allowance gets one otherwise empty frame, capped by the 16 MiB GPU
    // admission limit. Its remembered size reserves that frame from roads.
    boolean largePlate=sign&&bytes>FRAME_BYTES&&bytes<=MAX_SIGN_BYTES&&used==0;
    if(!largePlate&&used+bytes>limit)return false;
    if(own>0&&System.nanoTime()>=deadline)return false;
    if(sign){signBytes+=bytes;signUploads++;}else{roadBytes+=bytes;roadUploads++;}
    return true;
  }
  static String metrics(){return "upload roadBytes="+roadBytes+" signBytes="+signBytes+" roadBuffers="+roadUploads+" signBuffers="+signUploads;}
  /** Split exceptional large polygons on the mesh worker, preserving the renderer's triangle fan and UVs. */
  public static List<RoadSurface.Face> faces(List<RoadSurface.Face> input){
    if(input.stream().noneMatch(f->RoadRenderMesh.vertexCount(f)>MAX_VERTICES))return input;
    var out=new ArrayList<RoadSurface.Face>();
    for(var f:input){
      if(RoadRenderMesh.vertexCount(f)<=MAX_VERTICES){out.add(f);continue;}
      for(int first=1;first<f.points().size()-1;){
        int last=Math.min(f.points().size()-1,first+MAX_VERTICES/4);
        var points=new ArrayList<RoadGeometry.V>();points.add(f.points().get(0));points.addAll(f.points().subList(first,last+1));
        var uv=new ArrayList<RoadSurface.UV>();if(!f.uv().isEmpty()){uv.add(f.uv().get(0));uv.addAll(f.uv().subList(first,last+1));}
        out.add(new RoadSurface.Face(List.copyOf(points),f.color(),f.emissive(),f.texture(),List.copyOf(uv)));first=last;
      }
    }
    return List.copyOf(out);
  }
  private RoadUploadBudget(){}
}
