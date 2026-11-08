package com.sora.splineroads.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sora.splineroads.world.RoadFillEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.*;

public final class RoadFillRenderer implements BlockEntityRenderer<RoadFillEntity> {
  public RoadFillRenderer(BlockEntityRendererProvider.Context context){}
  @Override public int getViewDistance(){return 256;}
  @Override public void render(RoadFillEntity entity,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
    if(entity.fill().isAir()||entity.cap()<1)return; // Ground caps are baked into ordinary chunk meshes.
    pose.pushPose();pose.translate(0,-.002,0);
    Minecraft.getInstance().getBlockRenderer().renderSingleBlock(entity.fill(),pose,buffers,light,overlay);
    pose.popPose();
  }
}
