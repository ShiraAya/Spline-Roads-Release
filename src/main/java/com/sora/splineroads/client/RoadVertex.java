package com.sora.splineroads.client;

import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Use the vanilla whole-vertex writer for its exact standard entity format.
 * Extended shader formats and other consumers retain their original setters. */
final class RoadVertex {
  static final class Buffer extends BufferBuilder {
    private boolean standard;
    Buffer(int capacity){super(capacity);}
    @Override public void begin(VertexFormat.Mode mode,VertexFormat format){
      super.begin(mode,format);
      standard=format==DefaultVertexFormat.NEW_ENTITY&&!ShaderPackState.extendedVertices(ShaderPackState.current());
    }
  }
  static void emit(PoseStack pose,VertexConsumer consumer,float x,float y,float z,
      int color,int alpha,float u,float v,int light,float nx,float ny,float nz){
    if(consumer instanceof Buffer builder&&builder.standard){
      var p=new Vector4f(x,y,z,1).mul(pose.last().pose());
      var n=new Vector3f(nx,ny,nz).mul(pose.last().normal());
      consumer.vertex(p.x(),p.y(),p.z(),((color>>16)&255)/255f,((color>>8)&255)/255f,(color&255)/255f,
          alpha/255f,u,v,OverlayTexture.NO_OVERLAY,light,n.x(),n.y(),n.z());
    }else{
      consumer.vertex(pose.last().pose(),x,y,z).color((color>>16)&255,(color>>8)&255,color&255,alpha)
          .uv(u,v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(pose.last().normal(),nx,ny,nz).endVertex();
    }
  }
  private RoadVertex(){}
}
