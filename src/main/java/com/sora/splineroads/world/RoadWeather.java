package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import java.util.EnumSet;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Refresh old saved precipitation heights after dynamic road blocks became solid. */
@Mod.EventBusSubscriber(modid=SplineRoads.ID)
public final class RoadWeather {
  @SubscribeEvent public static void loaded(ChunkEvent.Load event){refresh(event.getChunk());}
  public static boolean refresh(ChunkAccess chunk){
    boolean road=false;
    for(var section:chunk.getSections())if(section.maybeHas(RoadBlocks::isCollider)){road=true;break;}
    if(!road)return false;
    Heightmap.primeHeightmaps(chunk,EnumSet.of(Heightmap.Types.MOTION_BLOCKING,Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
        Heightmap.Types.OCEAN_FLOOR));
    return true;
  }
  private RoadWeather(){}
}
