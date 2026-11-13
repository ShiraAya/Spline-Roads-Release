package com.sora.splineroads;

import com.sora.splineroads.net.RoadNetwork;
import com.sora.splineroads.world.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.*;

@Mod(SplineRoads.ID)
public final class SplineRoads {
  public static final String ID = "splineroads";
  public static final DeferredRegister<Block> BLOCKS =
      DeferredRegister.create(ForgeRegistries.BLOCKS, ID);
  public static final DeferredRegister<Item> ITEMS =
      DeferredRegister.create(ForgeRegistries.ITEMS, ID);
  public static final DeferredRegister<BlockEntityType<?>> ENTITIES =
      DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ID);
  public static final DeferredRegister<CreativeModeTab> TABS =
      DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);
  public static final RegistryObject<Block> NODE =
      BLOCKS.register("road_node", RoadBlocks.Node::new);
  public static final RegistryObject<Block> ROAD_BLOCK = BLOCKS.register("road_block",()->new Block(net.minecraft.world.level.block.state.BlockBehaviour.Properties.copy(net.minecraft.world.level.block.Blocks.STONE).strength(1.8f,6f)));
  public static final RegistryObject<Block> ROAD_SLAB = BLOCKS.register("road_slab",()->new net.minecraft.world.level.block.SlabBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties.copy(net.minecraft.world.level.block.Blocks.STONE).strength(1.8f,6f)));
  public static final RegistryObject<Block> TUNNEL_AIR = BLOCKS.register("tunnel_air",TunnelAir::new);
  public static final RegistryObject<Block> COLLIDER =
      BLOCKS.register("road_collision", RoadBlocks.Road::new);
  public static final RegistryObject<Block> FILLED_COLLIDER =
      BLOCKS.register("road_infill", RoadBlocks.FilledRoad::new);
  public static final RegistryObject<BlockEntityType<RoadFillEntity>> FILL_ENTITY =
      ENTITIES.register("road_infill",()->BlockEntityType.Builder.of(RoadFillEntity::new,FILLED_COLLIDER.get()).build(null));
  public static final RegistryObject<BlockEntityType<NodeEntity>> NODE_ENTITY =
      ENTITIES.register(
          "road_node", () -> BlockEntityType.Builder.of(NodeEntity::new, NODE.get()).build(null));
  public static final RegistryObject<Item> NODE_ITEM =
      ITEMS.register("road_node", () -> new BlockItem(NODE.get(), new Item.Properties()));
  public static final RegistryObject<Item> ROAD_BLOCK_ITEM = ITEMS.register("road_block",()->new BlockItem(ROAD_BLOCK.get(),new Item.Properties()));
  public static final RegistryObject<Item> ROAD_SLAB_ITEM = ITEMS.register("road_slab",()->new BlockItem(ROAD_SLAB.get(),new Item.Properties()));
  public static final RegistryObject<Item> CONNECTOR =
      ITEMS.register("road_connector", RoadTool::new);
  public static final RegistryObject<Item> RAILBED_CONNECTOR = ITEMS.register("railbed_connector", RailbedTool::new);
  public static final RegistryObject<Item> RAMP_CONNECTOR =
      ITEMS.register("ramp_connector", LaneRampTool::new);
  public static final RegistryObject<Item> JUNCTION = ITEMS.register("junction_connector", JunctionTool::new);
  public static final RegistryObject<Item> ROUNDABOUT = ITEMS.register("roundabout_generator", RoundaboutTool::new);
  public static final RegistryObject<Item> INTERCHANGE =
      ITEMS.register("interchange_connector", InterchangeTool::new);
  public static final RegistryObject<Item> INTERCHANGE_3 =
      ITEMS.register("interchange_connector_3", () -> new InterchangeTool(3));
  public static final RegistryObject<Item> INTERCHANGE_5 =
      ITEMS.register("interchange_connector_5", () -> new InterchangeTool(5));
  public static final RegistryObject<Item> INTERCHANGE_6 =
      ITEMS.register("interchange_connector_6", () -> new InterchangeTool(6));
  public static final RegistryObject<Item> FRONTAGE = ITEMS.register("frontage_generator",
      () -> new InterchangeTool(2,com.sora.splineroads.core.CorridorPlanner.Kind.FRONTAGE));
  public static final RegistryObject<Item> LAYERED = ITEMS.register("layered_connector",
      () -> new InterchangeTool(4,com.sora.splineroads.core.CorridorPlanner.Kind.LAYERED));
  public static final RegistryObject<Item> TOLL = ITEMS.register("toll_plaza_generator",TollTool::new);
  public static final RegistryObject<Item> TEXT_EDITOR = ITEMS.register("text_editor", SmartTextTool::new);
  public static final RegistryObject<Item> GANTRY_CREATOR = ITEMS.register("gantry_creator", GantryCreatorTool::new);
  public static final RegistryObject<Item> GANTRY_EDITOR = ITEMS.register("gantry_editor", GantryTool::new);
  public static final RegistryObject<Item> SPLITTER=ITEMS.register("road_splitter",RoadSplitTool::new);
  public static final RegistryObject<Item> ELEVATED_POINT=ITEMS.register("elevated_access_creator",ElevatedPointTool::new);
  public static final RegistryObject<Item> JUNCTION_POINT=ITEMS.register("junction_point_creator",JunctionPointTool::new);
  public static final RegistryObject<Item> ATTACHED_POINT=ITEMS.register("endpoint_creator",AttachedPointTool::new);
  public static final RegistryObject<Item> LANE_POINT=ITEMS.register("lane_point_tool",LanePointTool::new);
  public static final RegistryObject<Item> LANE_LINES=ITEMS.register("lane_line_editor",LaneLineTool::new);
  public static final RegistryObject<Item> Y_JUNCTION=ITEMS.register("y_junction_editor",YJunctionTool::new);
  public static final RegistryObject<Item> REMOVER =
      ITEMS.register("road_remover", RoadRemover::new);
  public static final RegistryObject<CreativeModeTab> TAB =
      TABS.register(
          "roads",
          () ->
              CreativeModeTab.builder()
                  .title(Component.translatable("itemGroup.splineroads"))
                  .icon(() -> new ItemStack(CONNECTOR.get()))
                  .displayItems(
                      (p, o) -> {
                        o.accept(NODE_ITEM.get());o.accept(ROAD_BLOCK_ITEM.get());o.accept(ROAD_SLAB_ITEM.get());o.accept(JUNCTION_POINT.get());o.accept(ATTACHED_POINT.get());o.accept(ELEVATED_POINT.get());o.accept(SPLITTER.get());o.accept(LANE_POINT.get());
                        o.accept(CONNECTOR.get());
                        o.accept(RAILBED_CONNECTOR.get());
                        o.accept(RAMP_CONNECTOR.get());
                        o.accept(JUNCTION.get());
                        o.accept(ROUNDABOUT.get());
                        o.accept(INTERCHANGE_3.get());
                        o.accept(INTERCHANGE.get());
                        o.accept(INTERCHANGE_5.get());
                        o.accept(INTERCHANGE_6.get());
                        o.accept(FRONTAGE.get());o.accept(TOLL.get());
                        o.accept(LAYERED.get());
                        o.accept(GANTRY_CREATOR.get());o.accept(GANTRY_EDITOR.get());o.accept(TEXT_EDITOR.get());o.accept(LANE_LINES.get());o.accept(Y_JUNCTION.get());
                        o.accept(REMOVER.get());
                      })
                  .build());

  public SplineRoads() {
    IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
    BLOCKS.register(bus);
    ITEMS.register(bus);
    ENTITIES.register(bus);
    TABS.register(bus);
    net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
        net.minecraftforge.fml.config.ModConfig.Type.CLIENT, com.sora.splineroads.config.RoadClientConfig.SPEC);
    RoadNetwork.register();
  }
}
