package com.sora.splineroads.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client-only settings, with no references to client classes on a dedicated server. */
public final class RoadClientConfig {
  public enum SurfaceBackend { AUTO, TERRAIN, VBO }
  public static final ForgeConfigSpec SPEC;
  public static final ForgeConfigSpec.EnumValue<com.sora.splineroads.core.RoadSlicing.Connection> SPLIT_CONNECTION;
  public static final ForgeConfigSpec.EnumValue<com.sora.splineroads.core.StreetPlan.Style> STREET_STYLE;
  public static final ForgeConfigSpec.BooleanValue PERF_OVERLAY,SHOW_SIGNS;
  public static final ForgeConfigSpec.EnumValue<SurfaceBackend> SURFACE_BACKEND;
  public static final ForgeConfigSpec.IntValue TERRAIN_SECTIONS_PER_FRAME;
  public static final ForgeConfigSpec.IntValue VBO_UPLOAD_MICROS;
  public static final ForgeConfigSpec.IntValue INACTIVE_VBO_CACHE_MIB,UNLOADED_VBO_CACHE_MIB,TERRAIN_CACHE_MIB,COLD_ROAD_CACHE_MIB;
  static {
    var b=new ForgeConfigSpec.Builder();b.push("rendering");
    SHOW_SIGNS=b.comment("Show road signs by default. /sr signs off skips sign baking and drawing without changing saved signs; /sr signs on restores display.").define("showSigns",true);
    PERF_OVERLAY=b.comment("Show SR performance overlay; toggle with /sr perf.").define("perfOverlay",false);
    SURFACE_BACKEND=b.comment("AUTO: terrain ONLY while an Iris/Oculus shader pack is actually rendering; otherwise VBO.",
        "TERRAIN submits asphalt/paint through chunk block rendering, enabling terrain shader materials.",
        "Puddle appearance still depends on the shader pack. VBO is the legacy compatibility fallback.",
        "AUTO follows shader toggles at runtime. Manual TERRAIN/VBO overrides are retained; no world migration.")
        .defineEnum("surfaceBackend",SurfaceBackend.AUTO);
    TERRAIN_SECTIONS_PER_FRAME=b.comment("Maximum 16x16x16 model sections published/rebuilt per frame.",
        "A time budget also applies; geometry is computed off the render thread.")
        .defineInRange("terrainSectionsPerFrame",4,1,32);
    VBO_UPLOAD_MICROS=b.comment("Soft render-thread budget for infrastructure VBO upload (microseconds).")
        .defineInRange("vboUploadMicros",900,200,5000);
    INACTIVE_VBO_CACHE_MIB=b.comment("Estimated MiB budget for dormant vertex-encoding VBOs. 0 disables retention; shared CPU geometry is not duplicated.").defineInRange("inactiveVboCacheMiB",64,0,512);
    UNLOADED_VBO_CACHE_MIB=b.comment("Estimated MiB budget for VBO regions with no loaded chunks, across both vertex encodings, including distant parts of an otherwise active road.",
        "0 disables retention. Reclamation is spread across frames; prepared CPU geometry remains available for local re-upload on return.")
        .defineInRange("unloadedVboCacheMiB",64,0,512);
    TERRAIN_CACHE_MIB=b.comment("Estimated MiB budget for unloaded/inactive terrain tiles (256 bytes/quad). 0 disables retention.").defineInRange("terrainCacheMiB",64,0,512);
    COLD_ROAD_CACHE_MIB=b.comment("Estimated MiB retained for roads outside loaded chunks, including CPU geometry and both VBO encodings. Real delete/edit invalidates immediately.").defineInRange("coldRoadCacheMiB",128,0,512);

    b.pop();b.push("tools");SPLIT_CONNECTION=b.defineEnum("splitConnection",com.sora.splineroads.core.RoadSlicing.Connection.CONTINUOUS);STREET_STYLE=b.defineEnum("streetStyle",com.sora.splineroads.core.StreetPlan.Style.DOWNTOWN);b.pop();SPEC=b.build();
  }
  private RoadClientConfig(){}
}
