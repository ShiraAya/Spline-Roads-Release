package com.sora.splineroads.client;
import com.sora.splineroads.SplineRoads;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.io.IOException;
/** VBO world solids need fog from the fully camera-relative vertex, not entity-local
 * components. Shader packs still receive the standard intercepted entity program. */
@Mod.EventBusSubscriber(modid=SplineRoads.ID,bus=Mod.EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class RoadShaders {
  private static ShaderInstance worldSolid,worldOpaque,worldAsphalt;
  private static final java.util.Map<ResourceLocation,Boolean> opaqueTextures=new java.util.HashMap<>();
  static void texturesReloaded(){opaqueTextures.clear();}
  private static boolean isOpaque(ResourceLocation texture){
    return opaqueTextures.computeIfAbsent(texture,key->{
      try(var stream=net.minecraft.client.Minecraft.getInstance().getResourceManager().open(key);var image=com.mojang.blaze3d.platform.NativeImage.read(stream)){
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if((image.getPixelRGBA(x,y)>>>24)!=255)return false;
        return true;
      }catch(IOException failure){return false;}
    });
  }
  @SubscribeEvent public static void register(RegisterShadersEvent event)throws IOException{
    event.registerShader(new ShaderInstance(event.getResourceProvider(),ResourceLocation.fromNamespaceAndPath(SplineRoads.ID,"road_world_solid"),DefaultVertexFormat.NEW_ENTITY),s->worldSolid=s);
    event.registerShader(new ShaderInstance(event.getResourceProvider(),ResourceLocation.fromNamespaceAndPath(SplineRoads.ID,"road_world_opaque"),DefaultVertexFormat.NEW_ENTITY),s->worldOpaque=s);
    event.registerShader(new ShaderInstance(event.getResourceProvider(),ResourceLocation.fromNamespaceAndPath(SplineRoads.ID,"road_world_asphalt"),DefaultVertexFormat.NEW_ENTITY),s->worldAsphalt=s);
  }
  public static ShaderInstance solid(){return worldSolid==null||ShaderPackState.current().active()?GameRenderer.getRendertypeEntityCutoutNoCullShader():worldSolid;}
  public static ShaderInstance opaque(ResourceLocation texture){if(!isOpaque(texture))return solid();return opaque(texture.getPath().equals("textures/road/asphalt.png"));}
  public static ShaderInstance opaque(boolean asphalt){var shader=asphalt?worldAsphalt:worldOpaque;return shader==null||ShaderPackState.current().active()?solid():shader;}
  private RoadShaders(){}
}
