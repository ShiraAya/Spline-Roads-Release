package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.V;

/** Light is sampled in front of a visible face, never from an opaque lining cell behind it. */
public final class RoadLighting {
  private static final int FULL_BRIGHT=0x00F000F0;
  private static final double[] INSETS={.2,.7,1.2}, OUTWARD={.12,.65,1.15}, SIDEWAYS={.35,.75,1.25};
  public interface Access {
    boolean opaque(int x,int y,int z);
    int packed(int x,int y,int z);
    default int surface(V vertex,V normal,double reach){return surfaceLight(vertex,normal,this,reach);}
  }
  /** Only shared seam samples, scoped to one stable render-thread packing step.
   * Clear before world/light updates can run. Negative results still use each
   * face's own centroid fallback; no world or access object is retained. */
  public static final class SurfaceCache {
    private record Key(V vertex,V normal,double reach){}
    /** Horizontal faces with the same ordered voxel footprint perform exactly
     * the same opacity/light reads, even when their vertices differ slightly. */
    private record FlatKey(int x0,int x1,int x2,int y0,int y1,int y2,int z0,int z1,int z2){}
    private final java.util.Map<Key,Integer> values=new java.util.HashMap<>();
    private final java.util.Map<FlatKey,Integer> flatValues=new java.util.HashMap<>();
    private long hits,misses;
    public int sample(V vertex,V normal,double reach,Access access){
      if(normal.x()==0&&normal.z()==0&&Math.abs(normal.y())==1){
        double x=vertex.x(),y=vertex.y(),z=vertex.z(),ny=normal.y();
        var key=new FlatKey((int)Math.floor(x-reach),(int)Math.floor(x),(int)Math.floor(x+reach),
            (int)Math.floor(y+ny*.12),(int)Math.floor(y+ny*.65),(int)Math.floor(y+ny*1.15),
            (int)Math.floor(z+ny*reach),(int)Math.floor(z),(int)Math.floor(z-ny*reach));
        var saved=flatValues.get(key);if(saved!=null){hits++;return saved;}
        misses++;int result=surfaceLight(vertex,normal,access,reach);
        if(values.size()+flatValues.size()>=4096)clear();flatValues.put(key,result);return result;
      }
      var key=new Key(vertex,normal,reach);
      var saved=values.get(key);
      if(saved!=null){hits++;return saved;}
      misses++;
      int result=surfaceLight(vertex,normal,access,reach);
      if(values.size()+flatValues.size()>=4096)clear();
      values.put(key,result);return result;
    }
    public void clear(){values.clear();flatValues.clear();}
    public long hits(){return hits;}
    public long misses(){return misses;}
  }
  public static V normal(RoadSurface.Face f){
    V origin=f.points().get(0);
    for(int i=1;i+1<f.points().size();i++){
      V a=f.points().get(i),b=f.points().get(i+1);
      double ax=a.x()-origin.x(),ay=a.y()-origin.y(),az=a.z()-origin.z();
      double bx=b.x()-origin.x(),by=b.y()-origin.y(),bz=b.z()-origin.z();
      double nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx;
      double length=Math.sqrt(nx*nx+ny*ny+nz*nz);
      if(length>1e-10){double scale=1/length;return new V(nx*scale,ny*scale,nz*scale);}
    }
    return new V(0,1,0);
  }
  public static int sample(RoadSurface.Face face,V vertex,Access access){
    return sample(face,vertex,access,false);
  }
  public static int sample(RoadSurface.Face face,V vertex,Access access,boolean forceUp){
    if(face.emissive())return FULL_BRIGHT;
    V n=normal(face);
    if(forceUp&&n.y()<0)n=n.mul(-1);
    return sample(face,vertex,access,n,center(face));
  }
  public static V center(RoadSurface.Face face){
    double x=0,y=0,z=0;
    for(V v:face.points()){x+=v.x();y+=v.y();z+=v.z();}
    double scale=1.0/face.points().size();return new V(x*scale,y*scale,z*scale);
  }
  public static int sample(RoadSurface.Face face,V vertex,Access access,V n,V center){
    if(face.emissive())return FULL_BRIGHT;
    // Concrete is a connected surface. Sampling toward each individual
    // triangle's centroid gives the SAME seam vertex different light values.
    boolean walk=face.texture().name().startsWith("WALK_");
    if(face.texture()==RoadSurface.Texture.CONCRETE||walk){int stable=access.surface(vertex,n,walk?1.05:.65);if(stable>=0)return stable;}
    double ix=center.x()-vertex.x(),iy=center.y()-vertex.y(),iz=center.z()-vertex.z();
    double length=Math.sqrt(ix*ix+iy*iy+iz*iz),scale=length<1e-8?0:1/length;
    double dx=length<1e-8?0:ix*scale,dy=length<1e-8?0:iy*scale,dz=length<1e-8?0:iz*scale;
    // The lining is up to one world cell thick; larger offsets only serve opaque-cell fallbacks.
    int nearby=-1;
    for(double inset:INSETS){
      boolean visible=false;
      double amount=Math.min(inset,length);
      double px=vertex.x()+dx*amount,py=vertex.y()+dy*amount,pz=vertex.z()+dz*amount;
      for(double outward:OUTWARD){
        int x=(int)Math.floor(px+n.x()*outward),y=(int)Math.floor(py+n.y()*outward),z=(int)Math.floor(pz+n.z()*outward);
        if(access.opaque(x,y,z)){if(visible)break;continue;}
        visible=true;int light=access.packed(x,y,z);
        nearby=nearby<0?light:merge(nearby,light);
        // Vanilla sky and block light are each at their maximum. The
        // contributing samples and intervening opacity have been recorded.
        if(nearby==FULL_BRIGHT)return nearby;
      }
    }
    // The first transparent collider cell can still carry zero light. Use the
    // nearby visible air samples on this SAME side, stopping at an opaque cell.
    // No fullbright floor and no light sampled through the back of the lining.
    if(nearby>=0)return nearby;
    // Long, narrow pavement triangles can keep their corner inside a wall voxel even
    // after a one-block diagonal inset. Their centroid is safely on the same visible face.
    for(double outward:OUTWARD){
      int x=(int)Math.floor(center.x()+n.x()*outward),y=(int)Math.floor(center.y()+n.y()*outward),z=(int)Math.floor(center.z()+n.z()*outward);
      if(!access.opaque(x,y,z))return access.packed(x,y,z);
    }
    // A narrow edge stripe can remain in the same partially occupied wall voxel
    // along its entire length. Look sideways above an upward-facing surface;
    // never sample below the deck or through an opaque wall face.
    if(n.y()>.7)for(double reach:SIDEWAYS){
      int best=-1;
      for(int sx=-1;sx<=1;sx++)for(int sz=-1;sz<=1;sz++)if(sx!=0||sz!=0){
        int x=(int)Math.floor((vertex.x()+n.x()*.2)+sx*reach),y=(int)Math.floor((vertex.y()+n.y()*.2)+0.0),z=(int)Math.floor((vertex.z()+n.z()*.2)+sz*reach);
        if(!access.opaque(x,y,z)){
          int light=access.packed(x,y,z);best=best<0?light:merge(best,light);
          if(best==FULL_BRIGHT)return best;
        }
      }
      if(best>=0)return best;
    }
    return access.packed((int)Math.floor(vertex.x()+n.x()*.12),(int)Math.floor(vertex.y()+n.y()*.12),(int)Math.floor(vertex.z()+n.z()*.12));
  }
  private static int surfaceLight(V vertex,V n,Access access,double reach){
    double tx=1,tz=0;
    if(Math.abs(n.y())<=.9){
      double length=Math.hypot(n.z(),-n.x());
      if(length<1e-8)throw new IllegalArgumentException("道路出现零长度或折返切线");
      tx=n.z()/length;tz=-n.x()/length;
    }
    double ax=n.y()*tz-n.z()*0.0,ay=n.z()*tx-n.x()*tz,az=n.x()*0.0-n.y()*tx;
    int best=-1;
    for(int i=-1;i<=1;i++)for(int j=-1;j<=1;j++){
      double a=i*reach,b=j*reach;
      boolean visible=false;
      double px=(vertex.x()+tx*a)+ax*b,py=(vertex.y()+0.0*a)+ay*b,pz=(vertex.z()+tz*a)+az*b;
      for(double outward:OUTWARD){
        int x=(int)Math.floor(px+n.x()*outward),y=(int)Math.floor(py+n.y()*outward),z=(int)Math.floor(pz+n.z()*outward);
        if(access.opaque(x,y,z)){if(visible)break;continue;}
        visible=true;int light=access.packed(x,y,z);best=best<0?light:merge(best,light);
        if(best==FULL_BRIGHT)return best;
      }
    }
    return best;
  }
  private static int merge(int a,int b){return Math.max(a&0xffff,b&0xffff)|(Math.max(a>>>16,b>>>16)<<16);}
  private RoadLighting(){}
}
