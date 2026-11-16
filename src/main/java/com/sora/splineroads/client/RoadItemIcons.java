package com.sora.splineroads.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sora.splineroads.SplineRoads;
import java.util.List;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SplineRoads.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RoadItemIcons {
  private static final List<String> ITEMS = List.of(
      "elevated_access_creator", "endpoint_creator", "frontage_generator", "gantry_creator",
      "gantry_editor", "interchange_connector", "interchange_connector_3", "interchange_connector_5",
      "interchange_connector_6", "junction_connector", "junction_point_creator", "lane_line_editor",
      "lane_point_tool", "layered_connector", "railbed_connector", "ramp_connector", "road_connector",
      "road_node", "road_remover", "road_splitter", "roundabout_generator", "text_editor",
      "toll_plaza_generator", "y_junction_editor");

  private static ResourceLocation icon(String item) {
    return ResourceLocation.fromNamespaceAndPath(SplineRoads.ID, "item/gui/" + item);
  }

  @SubscribeEvent
  public static void register(ModelEvent.RegisterAdditional event) {
    for (var item : ITEMS) event.register(icon(item));
  }

  @SubscribeEvent
  public static void bake(ModelEvent.ModifyBakingResult event) {
    var models = event.getModels();
    var missing = models.get(new ModelResourceLocation("minecraft", "missing", "missing"));
    for (var item : ITEMS) {
      var id = new ModelResourceLocation(SplineRoads.ID, item, "inventory");
      var original = models.get(id);
      var flat = models.get(icon(item));
      if (original != null && flat != null && flat != missing && !original.isCustomRenderer()) {
        models.put(id, new BakedModelWrapper<>(original) {
          @Override
          public BakedModel applyTransform(ItemDisplayContext context, PoseStack pose, boolean leftHand) {
            return (context == ItemDisplayContext.GUI ? flat : original).applyTransform(context, pose, leftHand);
          }
        });
      }
    }
  }

  private RoadItemIcons() {}
}
