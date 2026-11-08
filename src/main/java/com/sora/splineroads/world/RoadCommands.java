package com.sora.splineroads.world;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.sora.splineroads.SplineRoads;
import java.util.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import static net.minecraft.commands.Commands.*;

/** Operator world-edit helpers; scans run on the server thread with a shared per-tick budget. */
@Mod.EventBusSubscriber(modid=SplineRoads.ID)
public final class RoadCommands {
  private record Species(TagKey<Block> logs,Block leaves){}
  private static final Map<String,Species> SPECIES=Map.of(
      "oak",new Species(BlockTags.OAK_LOGS,Blocks.OAK_LEAVES),
      "spruce",new Species(BlockTags.SPRUCE_LOGS,Blocks.SPRUCE_LEAVES),
      "birch",new Species(BlockTags.BIRCH_LOGS,Blocks.BIRCH_LEAVES),
      "jungle",new Species(BlockTags.JUNGLE_LOGS,Blocks.JUNGLE_LEAVES),
      "acacia",new Species(BlockTags.ACACIA_LOGS,Blocks.ACACIA_LEAVES),
      "dark_oak",new Species(BlockTags.DARK_OAK_LOGS,Blocks.DARK_OAK_LEAVES),
      "mangrove",new Species(BlockTags.MANGROVE_LOGS,Blocks.MANGROVE_LEAVES),
      "cherry",new Species(BlockTags.CHERRY_LOGS,Blocks.CHERRY_LEAVES));
  private static final Map<MinecraftServer,ArrayDeque<ClearJob>> JOBS=new IdentityHashMap<>();
  private static final long MAX_CELLS=64_000_000;

  @SubscribeEvent public static void register(RegisterCommandsEvent event){register(event.getDispatcher());}
  public static void register(CommandDispatcher<CommandSourceStack> dispatcher){
    var root=literal("sr").requires(source->source.hasPermission(2));
    String[][] aliases={{"day","time set day"},{"night","time set midnight"},{"rain","weather rain"},
        {"sun","weather clear"},{"kd","kill @e[type=item]"}};
    for(var pair:aliases)root.then(literal(pair[0]).executes(c->c.getSource().getServer().getCommands().performPrefixedCommand(c.getSource(),pair[1])));
    var treeRadius=argument("r",IntegerArgumentType.integer(1,1024)).executes(c->start(c,true,false,""));
    var treeHeight=argument("h",IntegerArgumentType.integer(1,1024)).executes(c->start(c,true,true,""));
    // Tree species are literals, so numeric heights can never be consumed as an exception.
    for(String species:new TreeSet<>(SPECIES.keySet())){
      treeRadius.then(literal(species).executes(c->start(c,true,false,species)));
      treeHeight.then(literal(species).executes(c->start(c,true,true,species)));
    }
    treeRadius.then(treeHeight);
    root.then(literal("tree").then(treeRadius));
    root.then(literal("plant").then(argument("r",IntegerArgumentType.integer(1,1024)).executes(c->start(c,false,false,""))
        .then(argument("h",IntegerArgumentType.integer(1,1024)).executes(c->start(c,false,true,"")))));
    dispatcher.register(root);
  }
  private static CommandSyntaxException error(String text){return new SimpleCommandExceptionType(Component.literal(text)).create();}
  private static int start(CommandContext<CommandSourceStack> context,boolean trees,boolean hasHeight,String except)throws CommandSyntaxException{
    var source=context.getSource();var player=source.getPlayerOrException();
    int radius=IntegerArgumentType.getInteger(context,"r"),height=hasHeight?IntegerArgumentType.getInteger(context,"h"):radius;
    var queue=JOBS.computeIfAbsent(source.getServer(),s->new ArrayDeque<>());
    if(queue.stream().anyMatch(j->j.player.getUUID().equals(player.getUUID())))throw error("上一次 SR 清理尚未完成，请稍后再执行");
    var job=new ClearJob(source,player,radius,height,trees,except);
    if((long)(2*radius+1)*(2*radius+1)*(job.high-job.low)>MAX_CELLS)throw error("清理范围超过 6400 万格，请减小半径 r 或高度 h");
    queue.add(job);
    source.sendSuccess(()->Component.literal("开始清理"+(trees?"原木和树叶":"杂草和花卉")+"：半径 "+radius+"，高度 "+height+"（从玩家脚下向上）"+(except.isEmpty()?"":"，保留 "+except)),false);
    return 1;
  }
  static boolean matches(BlockState state,boolean trees,String except){
    if(trees){
      if(!state.is(BlockTags.LOGS)&&!state.is(BlockTags.LEAVES))return false;
      Species keep=SPECIES.get(except);return keep==null||!state.is(keep.logs())&&!state.is(keep.leaves());
    }
    // Vanilla's FLOWERS also includes cherry leaves and mangrove propagules.
    return state.is(BlockTags.FLOWERS)&&!state.is(BlockTags.LEAVES)&&!state.is(BlockTags.SAPLINGS)
        ||state.is(Blocks.GRASS)||state.is(Blocks.TALL_GRASS)
        ||state.is(Blocks.FERN)||state.is(Blocks.LARGE_FERN)||state.is(Blocks.DEAD_BUSH);
  }
  @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event){if(event.phase==TickEvent.Phase.END)tick(event.getServer());}
  static void tick(MinecraftServer server){
    var queue=JOBS.get(server);if(queue==null||queue.isEmpty())return;
    long deadline=System.nanoTime()+2_000_000;int budget=8192;
    while(!queue.isEmpty()&&budget>0&&System.nanoTime()<deadline){
      var job=queue.removeFirst();int used=job.step(Math.min(512,budget),deadline);budget-=used;
      if(job.done())job.finish();else queue.addLast(job);
    }
    if(queue.isEmpty())JOBS.remove(server);
  }
  @SubscribeEvent public static void stopped(ServerStoppedEvent event){JOBS.remove(event.getServer());}
  static boolean pending(MinecraftServer server){var q=JOBS.get(server);return q!=null&&!q.isEmpty();}

  private static final class ClearJob {
    final CommandSourceStack source;final ServerPlayer player;final ServerLevel level;
    final int radius,low,high,cx,cz;final boolean trees;final String except;
    final BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
    int x,z,y,removed,skipped;boolean columnChecked;
    ClearJob(CommandSourceStack source,ServerPlayer player,int radius,int height,boolean trees,String except){
      this.source=source;this.player=player;this.level=player.serverLevel();this.radius=radius;this.trees=trees;this.except=except;
      var center=player.blockPosition();cx=center.getX();cz=center.getZ();
      low=Math.max(level.getMinBuildHeight(),Math.min(level.getMaxBuildHeight(),center.getY()));
      high=Math.max(low,Math.min(level.getMaxBuildHeight(),center.getY()+height));x=z=-radius;y=low;
    }
    boolean done(){return x>radius||high<=low;}
    void column(){y=low;columnChecked=false;if(++z>radius){z=-radius;x++;}}
    int step(int budget,long deadline){
      int used=0;
      while(!done()&&used<budget&&(used%32!=0||System.nanoTime()<deadline)){
        used++;
        if(!columnChecked){
          if((long)x*x+(long)z*z>(long)radius*radius){column();continue;}
          pos.set(cx+x,low,cz+z);
          if(!level.hasChunkAt(pos)||!level.getWorldBorder().isWithinBounds(pos)){skipped++;column();continue;}
          columnChecked=true;
        }
        pos.set(cx+x,y,cz+z);
        // A chunk may unload between ticks. Never force terrain generation for this helper.
        if(!level.hasChunkAt(pos)){skipped++;column();continue;}
        BlockState state=level.getBlockState(pos);
        if(matches(state,trees,except)){
          var event=new net.minecraftforge.event.level.BlockEvent.BreakEvent(level,pos.immutable(),state,player);
          if(level.mayInteract(player,pos)&&!net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(event)
              &&level.setBlock(pos,state.getFluidState().createLegacyBlock(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE))removed++;
        }
        if(++y>=high)column();
      }return used;
    }
    void finish(){source.sendSuccess(()->Component.literal("SR 清理完成：移除 "+removed+" 个方块"+(skipped==0?"":"；跳过 "+skipped+" 个未加载或边界外的方块列")),true);}
  }
  private RoadCommands(){}
}
