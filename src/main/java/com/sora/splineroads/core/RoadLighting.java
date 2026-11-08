package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.V;

/** Light is sampled in front of a visible face, never from an opaque lining cell behind it. */
public final class RoadLighting {
  public interface Access { boolean opaque(int x,int y,int z); int packed(int x,int y,int z); }
  public static V normal(RoadSurface.Face f){
    V origin=f.points().get(0);
    for(int i=1;i+1<f.points().size();i++){
      V a=f.points().get(i).sub(origin),b=f.points().get(i+1).sub(origin);
      V n=new V(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x());
      double length=Math.sqrt(n.dot(n));if(length>1e-10)return n.mul(1/length);
    }
    return new V(0,1,0);
  }
  public static int sample(RoadSurface.Face face,V vertex,Access access){
    return sample(face,vertex,access,false);
  }
  public static int sample(RoadSurface.Face face,V vertex,Access access,boolean forceUp){
    if(face.emissive())return 15728880;
    V n=normal(face),center=new V(0,0,0);
    if(forceUp&&n.y()<0)n=n.mul(-1);
    // Concrete is a connected surface. Sampling toward each individual
    // triangle's centroid gives the SAME seam vertex different light values.
    boolean walk=face.texture().name().startsWith("WALK_");
    if(face.texture()==RoadSurface.Texture.CONCRETE||walk){int stable=surfaceLight(vertex,n,access,walk?1.05:.65);if(stable>=0)return stable;}
    for(V v:face.points())center=center.add(v);
    center=center.mul(1.0/face.points().size());
    V inward=center.sub(vertex);double length=Math.sqrt(inward.dot(inward));
    V direction=length<1e-8?new V(0,0,0):inward.mul(1/length);
    // The lining is up to one world cell thick; larger offsets only serve opaque-cell fallbacks.
    int nearby=-1;
    for(double inset:new double[]{.2,.7,1.2}){
      boolean visible=false;
      for(double outward:new double[]{.12,.65,1.15}){
        V p=vertex.add(direction.mul(Math.min(inset,length))).add(n.mul(outward));
        int x=(int)Math.floor(p.x()),y=(int)Math.floor(p.y()),z=(int)Math.floor(p.z());
        if(access.opaque(x,y,z)){if(visible)break;continue;}
        visible=true;int light=access.packed(x,y,z);
        nearby=nearby<0?light:merge(nearby,light);
      }
    }
    // The first transparent collider cell can still carry zero light. Use the
    // nearby visible air samples on this SAME side, stopping at an opaque cell.
    // No fullbright floor and no light sampled through the back of the lining.
    if(nearby>=0)return nearby;
    // Long, narrow pavement triangles can keep their corner inside a wall voxel even
    // after a one-block diagonal inset. Their centroid is safely on the same visible face.
    for(double outward:new double[]{.12,.65,1.15}){
      V point=center.add(n.mul(outward));int x=(int)Math.floor(point.x()),y=(int)Math.floor(point.y()),z=(int)Math.floor(point.z());
      if(!access.opaque(x,y,z))return access.packed(x,y,z);
    }
    // A narrow edge stripe can remain in the same partially occupied wall voxel
    // along its entire length. Look sideways above an upward-facing surface;
    // never sample below the deck or through an opaque wall face.
    if(n.y()>.7)for(double reach:new double[]{.35,.75,1.25}){
      int best=-1;
      for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dz!=0){
        V q=vertex.add(n.mul(.2)).add(new V(dx*reach,0,dz*reach));
        int x=(int)Math.floor(q.x()),y=(int)Math.floor(q.y()),z=(int)Math.floor(q.z());
        if(!access.opaque(x,y,z)){int light=access.packed(x,y,z);best=best<0?light:Math.max(best&0xffff,light&0xffff)|(Math.max(best>>>16,light>>>16)<<16);}
      }
      if(best>=0)return best;
    }
    V p=vertex.add(n.mul(.12));return access.packed((int)Math.floor(p.x()),(int)Math.floor(p.y()),(int)Math.floor(p.z()));
  }
  private static int surfaceLight(V vertex,V n,Access access,double reach){
    V tangent=Math.abs(n.y())>.9?new V(1,0,0):new V(n.z(),0,-n.x()).horizontalUnit();
    V across=new V(n.y()*tangent.z()-n.z()*tangent.y(),n.z()*tangent.x()-n.x()*tangent.z(),n.x()*tangent.y()-n.y()*tangent.x());
    int best=-1;
    for(double a:new double[]{-reach,0,reach})for(double b:new double[]{-reach,0,reach}){
      boolean visible=false;
      for(double outward:new double[]{.12,.65,1.15}){
        V p=vertex.add(tangent.mul(a)).add(across.mul(b)).add(n.mul(outward));
        int x=(int)Math.floor(p.x()),y=(int)Math.floor(p.y()),z=(int)Math.floor(p.z());
        if(access.opaque(x,y,z)){if(visible)break;continue;}
        visible=true;int light=access.packed(x,y,z);best=best<0?light:merge(best,light);
      }
    }
    return best;
  }
  private static int merge(int a,int b){return Math.max(a&0xffff,b&0xffff)|(Math.max(a>>>16,b>>>16)<<16);}
  private RoadLighting(){}
}
