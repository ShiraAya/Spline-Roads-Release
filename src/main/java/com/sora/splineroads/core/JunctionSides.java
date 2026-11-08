package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.JunctionSpec.*;
import java.util.*;

/** Two complementary one-way mouths may occupy one side of an ordinary intersection. */
final class JunctionSides {
 final List<V> outward=new ArrayList<>();
 final List<Integer> order=new ArrayList<>();
 final int[] partner;
 final double[] offset,envelope;
 JunctionSides(JunctionSpec s,boolean separated){
  int n=s.arms().size();partner=new int[n];Arrays.fill(partner,-1);offset=new double[n];envelope=new double[n];
  for(int i=0;i<n;i++){var arm=s.arms().get(i);outward.add(arm.endpoint().position().sub(s.center()).horizontalUnit());order.add(i);envelope[i]=arm.width();}
  if(separated&&s.kind()==Kind.INTERSECTION)for(int i=0;i<n;i++)for(int j=i+1;j<n;j++){
   Arm a=s.arms().get(i),b=s.arms().get(j);
   boolean complementary=a.incoming()>0&&a.outgoing()==0&&b.incoming()==0&&b.outgoing()>0 || b.incoming()>0&&b.outgoing()==0&&a.incoming()==0&&a.outgoing()>0;
   // These are the close directions rejected by the legacy radial planner. Keep
   // existing wide-angle junctions and their saved triangle identities unchanged.
   if(partner[i]>=0||partner[j]>=0||!complementary||(!s.parallelApproaches()&&outward.get(i).dot(outward.get(j))<=Math.cos(Math.toRadians(25)))||a.inward().dot(b.inward())<.9)continue;
   V axis=a.inward().add(b.inward()).mul(-1).horizontalUnit(),side=axis.left();
   double ai=a.endpoint().position().sub(s.center()).dot(side),bi=b.endpoint().position().sub(s.center()).dot(side);
   double gap=Math.abs(ai-bi)-(a.width()+b.width())/2;
   if(gap<.5)throw new IllegalArgumentException("同侧单行入口边缘至少留 0.5 格间隔，请拉开 B/C 端点");
   if(gap>64)throw new IllegalArgumentException("同侧单行入口间隔最多 64 格，请靠近 B/C 端点");
   partner[i]=j;partner[j]=i;outward.set(i,axis);outward.set(j,axis);offset[i]=ai;offset[j]=bi;
   envelope[i]=envelope[j]=2*Math.max(Math.abs(ai)+a.width()/2,Math.abs(bi)+b.width()/2);
  }
  order.sort(Comparator.<Integer>comparingDouble(i->Math.atan2(outward.get(i).z(),outward.get(i).x())).thenComparingDouble(i->offset[i]));
 }
 boolean paired(int i,int j){return partner[i]==j;}
 boolean separated(){return Arrays.stream(partner).anyMatch(i->i>=0);}
 V mouth(JunctionSpec s,int i,double radius){return s.center().add(outward.get(i).mul(radius)).add(outward.get(i).left().mul(offset[i]));}
 double radius(JunctionSpec s){return radius(s,true);}
 double radius(JunctionSpec s,boolean parallelClearance){
  if(s.kind()==Kind.ROUNDABOUT)return s.islandRadius()+s.ringLanes()*s.ringLaneWidth();
  double result=s.cornerRadius();
  for(int k=0;k<order.size();k++){
   int i=order.get(k),j=order.get((k+1)%order.size());if(paired(i,j))continue;
   V a=outward.get(i),b=outward.get(j);double angle=Math.atan2(a.x()*b.z()-a.z()*b.x(),a.dot(b));if(angle<=0)angle+=2*Math.PI;
   if(angle<Math.toRadians(25))throw new IllegalArgumentException("相邻接入口夹角至少 25°；同侧入口须为一入一出的单行道");
   double tangent=Math.tan(Math.min(angle,Math.PI-.01)/2);
   if(separated())tangent=Math.min(1,tangent);
   result=Math.max(result,(envelope[i]+envelope[j])/4/tangent+s.cornerRadius());
  }
  if(parallelClearance&&s.parallelApproaches())for(int i=0;i<partner.length;i++)if(partner[i]>=0)result=Math.max(result,envelope[i]/2+s.cornerRadius());
  return result;
 }
}
