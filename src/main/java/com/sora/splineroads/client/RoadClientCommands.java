package com.sora.splineroads.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.config.RoadClientConfig;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import static net.minecraft.commands.Commands.*;

/** Local display preferences are available without operator rights on any server. */
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class RoadClientCommands {
  @SubscribeEvent public static void register(RegisterClientCommandsEvent event){
    var perf=literal("perf").executes(c->perf(c.getSource(),!RoadClientConfig.PERF_OVERLAY.get()));
    perf.then(literal("on").executes(c->perf(c.getSource(),true)));
    perf.then(literal("off").executes(c->perf(c.getSource(),false)));
    event.getDispatcher().register(literal("sr").then(perf)
        .then(literal("signs").executes(c->signs(c.getSource(),!RoadSignVisibility.enabled()))
          .then(literal("on").executes(c->signs(c.getSource(),true)))
          .then(literal("off").executes(c->signs(c.getSource(),false))))
        .then(literal("sf").executes(c->{
          c.getSource().sendSuccess(()->Component.literal("SR 表面模式："+RoadClientConfig.SURFACE_BACKEND.get()+"；/sr sf auto|VBO|terrain"),false);return 1;
        }).then(argument("mode",StringArgumentType.word())
            .suggests((c,b)->SharedSuggestionProvider.suggest(List.of("auto","VBO","terrain"),b))
            .executes(c->{
              String name=StringArgumentType.getString(c,"mode").toUpperCase(Locale.ROOT);
              if(name.equals("TERRIAN"))name="TERRAIN";
              RoadClientConfig.SurfaceBackend mode;
              try{mode=RoadClientConfig.SurfaceBackend.valueOf(name);}
              catch(IllegalArgumentException e){throw new SimpleCommandExceptionType(Component.literal("mode 可选：auto、VBO、terrain")).create();}
              RoadClientConfig.SURFACE_BACKEND.set(mode); // Forge's file config autosaves.
              // The existing frame transition retains valid caches and dirties affected sections.
              RoadTerrainModels.beginFrame();
              c.getSource().sendSuccess(()->Component.literal("SR 表面模式已切换为 "+mode),false);return 1;
            }))));
  }
  private static int signs(net.minecraft.commands.CommandSourceStack source,boolean enabled){
    RoadSignVisibility.set(enabled);source.sendSuccess(()->Component.literal("SR 全部标牌已"+(enabled?"显示":"隐藏（性能排查）")+"；/sr signs on|off"),false);return 1;
  }
  private static int perf(net.minecraft.commands.CommandSourceStack source,boolean enabled){
    RoadClientConfig.PERF_OVERLAY.set(enabled); // Forge's file config autosaves.
    source.sendSuccess(()->Component.literal("SR 性能显示已"+(enabled?"开启":"关闭")),false);return 1;
  }
  private RoadClientCommands(){}
}
