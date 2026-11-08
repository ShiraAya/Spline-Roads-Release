package com.sora.splineroads.client;

import com.mojang.blaze3d.vertex.*;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.world.NodeEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(
    modid = SplineRoads.ID,
    value = Dist.CLIENT,
    bus = Mod.EventBusSubscriber.Bus.MOD)
public final class NodeRenderer implements BlockEntityRenderer<NodeEntity> {
  public NodeRenderer(BlockEntityRendererProvider.Context context) {}

  @SubscribeEvent
  public static void register(EntityRenderersEvent.RegisterRenderers e) {
    e.registerBlockEntityRenderer(SplineRoads.NODE_ENTITY.get(), NodeRenderer::new);
    // Infill is static chunk geometry; no BER means no per-frame bbox/collision queries.
  }

  @SubscribeEvent
  public static void colors(
      net.minecraftforge.client.event.RegisterColorHandlersEvent.Block event) {
    event.register(
        (state, level, pos, tint) ->
            level != null
                    && pos != null
                    && state.getValue(com.sora.splineroads.world.RoadBlocks.Road.FILL)
                        == com.sora.splineroads.world.RoadBlocks.Fill.GRASS_BLOCK
                ? net.minecraft.client.renderer.BiomeColors.getAverageGrassColor(level, pos)
                : 0xFFFFFF,
        SplineRoads.COLLIDER.get());
    event.register((state,level,pos,tint)->level!=null&&pos!=null&&level.getBlockEntity(pos) instanceof com.sora.splineroads.world.RoadFillEntity fill?Minecraft.getInstance().getBlockColors().getColor(fill.fill(),level,pos,tint):0xFFFFFF,SplineRoads.FILLED_COLLIDER.get());
  }

  @Override
  public void render(
      NodeEntity n,
      float partial,
      PoseStack pose,
      MultiBufferSource buffer,
      int light,
      int overlay) {
    var p = Minecraft.getInstance().player;
    if (p == null
        || (!com.sora.splineroads.world.RoadTool.showsNodes(p.getMainHandItem())
            && !com.sora.splineroads.world.RoadTool.showsNodes(p.getOffhandItem()))) return;
    var renderer = Minecraft.getInstance().getBlockRenderer();
    renderer
        .getModelRenderer()
        .renderModel(
            pose.last(),
            buffer.getBuffer(RenderType.solid()),
            n.getBlockState(),
            RoadTerrainModels.markerModel(renderer.getBlockModel(n.getBlockState())),
            1,
            1,
            1,
            light,
            overlay,
            net.minecraftforge.client.model.data.ModelData.EMPTY,
            RenderType.solid());
    pose.pushPose();
    pose.translate(.5 + n.offsetX, n.offsetY + .05, .5 + n.offsetZ);
    pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-((float) n.yaw)));
    VertexConsumer c = buffer.getBuffer(RenderType.lines());
    line(pose, c, 0, 0, -.6f, 0, 0, .8f);
    line(pose, c, 0, 0, .8f, -.3f, 0, .4f);
    line(pose, c, 0, 0, .8f, .3f, 0, .4f);
    line(pose, c, -.3f, 0, -.3f, .3f, 0, -.3f);
    line(pose, c, .3f, 0, -.3f, .3f, 0, .3f);
    line(pose, c, .3f, 0, .3f, -.3f, 0, .3f);
    line(pose, c, -.3f, 0, .3f, -.3f, 0, -.3f);
    pose.popPose();
  }

  private void line(
      PoseStack p, VertexConsumer c, float x, float y, float z, float xx, float yy, float zz) {
    float dx = xx - x, dy = yy - y, dz = zz - z, l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    c.vertex(p.last().pose(), x, y, z)
        .color(70, 255, 223, 255)
        .normal(p.last().normal(), dx / l, dy / l, dz / l)
        .endVertex();
    c.vertex(p.last().pose(), xx, yy, zz)
        .color(70, 255, 223, 255)
        .normal(p.last().normal(), dx / l, dy / l, dz / l)
        .endVertex();
  }

  @Override
  public boolean shouldRenderOffScreen(NodeEntity n) {
    return true;
  }
}
