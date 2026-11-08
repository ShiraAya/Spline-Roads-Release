package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadRaster.Box;
import java.util.*;
import net.minecraft.world.phys.shapes.*;

/** Assemble the exact box union once, without repeated voxel boolean merges on slopes. */
public final class RoadCollisionShape {
  private static final class GridShape extends ArrayVoxelShape {
    GridShape(DiscreteVoxelShape shape,double[] x,double[] y,double[] z){super(shape,x,y,z);}
  }
  public static VoxelShape build(List<Box> boxes) {
    if(boxes.isEmpty())return Shapes.empty();
    if(boxes.size()==1){Box b=boxes.get(0);return Shapes.box(b.x0(),b.y0(),b.z0(),b.x1(),b.y1(),b.z1());}
    double[] x=axis(boxes,0),y=axis(boxes,1),z=axis(boxes,2);
    long size=(long)(x.length-1)*(y.length-1)*(z.length-1);
    if(size>1_000_000)return legacy(boxes,0,boxes.size());
    var grid=new BitSetDiscreteVoxelShape(x.length-1,y.length-1,z.length-1);
    for(Box b:boxes){
      int x0=Arrays.binarySearch(x,b.x0()),x1=Arrays.binarySearch(x,b.x1());
      int y0=Arrays.binarySearch(y,b.y0()),y1=Arrays.binarySearch(y,b.y1());
      int z0=Arrays.binarySearch(z,b.z0()),z1=Arrays.binarySearch(z,b.z1());
      for(int i=x0;i<x1;i++)for(int j=y0;j<y1;j++)for(int k=z0;k<z1;k++)grid.fill(i,j,k);
    }
    // optimize() reconstructs the union with boolean operations; the grid already is exact.
    return new GridShape(grid,x,y,z);
  }
  private static double[] axis(List<Box> boxes,int axis){
    double[] values=new double[boxes.size()*2];int i=0;
    for(Box b:boxes){values[i++]=axis==0?b.x0():axis==1?b.y0():b.z0();values[i++]=axis==0?b.x1():axis==1?b.y1():b.z1();}
    Arrays.sort(values);int n=0;for(double v:values)if(n==0||v!=values[n-1])values[n++]=v;
    return Arrays.copyOf(values,n);
  }
  private static VoxelShape legacy(List<Box> boxes,int from,int to){
    if(to-from==1){Box b=boxes.get(from);return Shapes.box(b.x0(),b.y0(),b.z0(),b.x1(),b.y1(),b.z1());}
    int mid=(from+to)/2;return Shapes.joinUnoptimized(legacy(boxes,from,mid),legacy(boxes,mid,to),BooleanOp.OR);
  }
  private RoadCollisionShape(){}
}
