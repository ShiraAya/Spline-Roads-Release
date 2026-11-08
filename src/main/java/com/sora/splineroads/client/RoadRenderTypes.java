package com.sora.splineroads.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Raster depth bias keeps a flush road legible at distance without moving collision or geometry.
 */
public final class RoadRenderTypes extends RenderType {
  private RoadRenderTypes() {
    super(
        "splineroads_unused",
        DefaultVertexFormat.NEW_ENTITY,
        VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        () -> {},
        () -> {});
  }

  public static RenderType glass(ResourceLocation texture) {
    return create("splineroads_noise_glass",DefaultVertexFormat.NEW_ENTITY,VertexFormat.Mode.QUADS,262144,false,true,
        CompositeState.builder().setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
            .setTextureState(new TextureStateShard(texture,false,false)).setTransparencyState(TRANSLUCENT_TRANSPARENCY)
            .setLightmapState(LIGHTMAP).setOverlayState(OVERLAY).setCullState(CULL)
            .setDepthTestState(LEQUAL_DEPTH_TEST).setWriteMaskState(COLOR_WRITE).createCompositeState(false));
  }

  /** Diagnostic overlay must remain locatable below an elevated road. It only
   * writes colour, so it cannot hide or change the real world's depth. */
  public static RenderType conflict(ResourceLocation texture) {
    return create("splineroads_conflict",DefaultVertexFormat.NEW_ENTITY,VertexFormat.Mode.QUADS,262144,false,true,
        CompositeState.builder().setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
            .setTextureState(new TextureStateShard(texture,false,false)).setTransparencyState(TRANSLUCENT_TRANSPARENCY)
            .setLightmapState(LIGHTMAP).setOverlayState(OVERLAY).setCullState(NO_CULL)
            .setDepthTestState(NO_DEPTH_TEST).setWriteMaskState(COLOR_WRITE).createCompositeState(false));
  }

  public static RenderType surface(ResourceLocation texture, boolean marking) {
    return surface(texture,marking,false);
  }

  /** Solid deck walls must not receive the slope bias used for coplanar road paint. */
  public static RenderType solid(ResourceLocation texture) {
    return surface(texture,false,true);
  }

  private static RenderType surface(ResourceLocation texture, boolean marking, boolean solid) {
    float factor = solid ? 0 : marking ? -2 : -1, units = solid ? 0 : marking ? -16 : -8;
    var offset =
        new LayeringStateShard(
            marking ? "road_marking_offset" : "road_surface_offset",
            () -> {
              RenderSystem.polygonOffset(factor, units);
              RenderSystem.enablePolygonOffset();
            },
            () -> {
              RenderSystem.polygonOffset(0, 0);
              RenderSystem.disablePolygonOffset();
            });
    return create(
        marking ? "splineroads_markings" : "splineroads_pavement",
        DefaultVertexFormat.NEW_ENTITY,
        VertexFormat.Mode.QUADS,
        262144,
        false,
        false,
        CompositeState.builder()
            .setShaderState(new ShaderStateShard(() -> RoadShaders.opaque(texture)))
            .setTextureState(new TextureStateShard(texture, false, texture.equals(ResourceLocation.fromNamespaceAndPath("splineroads","textures/road/asphalt.png"))))
            .setLightmapState(LIGHTMAP)
            .setOverlayState(OVERLAY)
            .setCullState(CULL)
            .setDepthTestState(LEQUAL_DEPTH_TEST)
            .setWriteMaskState(COLOR_DEPTH_WRITE)
            .setLayeringState(offset)
            .createCompositeState(false));
  }
}
