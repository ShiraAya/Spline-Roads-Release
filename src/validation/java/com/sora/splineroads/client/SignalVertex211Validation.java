package com.sora.splineroads.client;
import com.mojang.blaze3d.vertex.*;import net.minecraft.client.renderer.texture.OverlayTexture;import java.util.*;import org.joml.*;

/** Compare complete native vertex bytes, including transformed normals/packed light. */
public final class SignalVertex211Validation {
 static float f(int[] d,int i){return Float.intBitsToFloat(d[i]);}
 static void old(int[] d,Matrix4f t,Matrix3f n,VertexConsumer target,int light){for(int i=0;i<d.length;i+=9){int c=d[i+3];target.vertex(t,f(d,i),f(d,i+1),f(d,i+2)).color((c>>16)&255,(c>>8)&255,c&255,255).uv(f(d,i+4),f(d,i+5)).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n,f(d,i+6),f(d,i+7),f(d,i+8)).endVertex();}}
 public static void main(String[] args){var random=new java.util.Random(2111116);int vertices=0;
  for(int scene=0;scene<160;scene++){
   var pose=new PoseStack();pose.translate(random.nextDouble()*8000-4000,random.nextDouble()*300-100,random.nextDouble()*8000-4000);pose.mulPose(new Quaternionf().rotationXYZ(random.nextFloat()*6,random.nextFloat()*6,random.nextFloat()*6));pose.scale(.5f+random.nextFloat()*2,.5f+random.nextFloat()*2,.5f+random.nextFloat()*2);
   int[] data=new int[1024*9];for(int i=0;i<data.length;i+=9){for(int k=0;k<9;k++)data[i+k]=Float.floatToRawIntBits(random.nextFloat()*2-1);data[i+3]=((i/9)%256)*0x010101;}
   int light=random.nextInt();var a=new BufferBuilder(65536);var b=new BufferBuilder(65536);a.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.NEW_ENTITY);b.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.NEW_ENTITY);
   old(data,pose.last().pose(),pose.last().normal(),a,light);RoadSignalBatch.emitVertices(data,pose.last().pose(),pose.last().normal(),b,light);
   var aa=a.end();var bb=b.end();var av=aa.vertexBuffer();var bv=bb.vertexBuffer();if(!av.equals(bv)){for(int i=0;i<av.remaining();i++)if(av.get(i)!=bv.get(i))throw new AssertionError("vertex byte differs scene="+scene+" offset="+i+" old="+av.get(i)+" new="+bv.get(i));}aa.release();bb.release();vertices+=data.length/9;
  }
  System.out.println("PASS exact_vertices="+vertices+" transforms=160 colors=256");
 }
}
