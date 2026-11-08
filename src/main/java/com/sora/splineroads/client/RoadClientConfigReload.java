package com.sora.splineroads.client;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileWatcher;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.config.RoadClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.IConfigEvent;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/** NightConfig 3.6 reloads mutate the same map that commands write. Serialize
 * this client's reloads with its commands; retain file watching and validation. */
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
public final class RoadClientConfigReload {
  @SubscribeEvent public static void loaded(ModConfigEvent.Loading event){
    var config=event.getConfig();if(config.getSpec()!=RoadClientConfig.SPEC||!(config.getConfigData() instanceof CommentedFileConfig file))return;
    try{
      FileWatcher.defaultInstance().setWatch(file.getNioPath(),()->Minecraft.getInstance().execute(()->{
        file.load();RoadClientConfig.SPEC.setConfig(file);
        ModList.get().getModContainerById(SplineRoads.ID).orElseThrow().dispatchConfigEvent(IConfigEvent.reloading(config));
      }));
    }catch(java.io.IOException e){throw new java.io.UncheckedIOException("Cannot watch SR client preferences",e);}
  }
  private RoadClientConfigReload(){}
}
