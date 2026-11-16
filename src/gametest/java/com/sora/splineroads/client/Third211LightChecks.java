package com.sora.splineroads.client;
import com.mojang.blaze3d.vertex.*;import com.sora.splineroads.world.*;import com.sora.splineroads.core.*;import java.util.*;import net.minecraft.client.Minecraft;import net.minecraft.client.renderer.RenderType;import net.minecraft.core.*;import net.minecraft.util.RandomSource;import net.minecraftforge.client.model.data.ModelData;
final class Third211LightChecks {
 static void run(Minecraft mc)throws Exception{
  int checked=0,vertices=0;
  for(int x=710;x<720;x++)for(int z=60;z<100;z++){
   var p=new BlockPos(x,55,z);if(!(mc.level.getBlockEntity(p) instanceof RoadFillEntity fill)||fill.outline().isEmpty())continue;
   var state=mc.level.getBlockState(p);var model=mc.getBlockRenderer().getBlockModel(state);
   if(model.useAmbientOcclusion(state,RenderType.solid()))throw new AssertionError("proxy cube AO still enabled at "+p);
   var data=model.getModelData(mc.level,p,state,fill.getModelData());
   var builder=new BufferBuilder(4096);builder.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.BLOCK);
   mc.getBlockRenderer().getModelRenderer().tesselateBlock(mc.level,model,state,p,new PoseStack(),builder,false,RandomSource.create(),state.getSeed(p),0,data,RenderType.solid());
   var rendered=builder.end();try{var v=rendered.vertexBuffer();for(int i=0;i<rendered.drawState().vertexCount();i++){
    int at=i*32;if(v.get(at+29)<120||Math.abs(v.getFloat(at+4)-1)>.003)continue;
    int rgb=v.getInt(at+12);for(int channel=0;channel<3;channel++)if(((rgb>>(channel*8))&255)<250)throw new AssertionError("sunlit infill top darkened "+p+" color="+Integer.toHexString(rgb));vertices++;
   }}finally{rendered.release();}checked++;
  }
  if(checked<3||vertices<12)throw new AssertionError("missing actual clipped fill controls "+checked+"/"+vertices);
  var sandstone=net.minecraft.world.level.block.Blocks.SANDSTONE.defaultBlockState();var ordinary=mc.getBlockRenderer().getBlockModel(sandstone);
  if(!ordinary.useAmbientOcclusion(sandstone,RenderType.solid()))throw new AssertionError("normal material AO disabled");
  // Repeat clipping must preserve a deliberately AO-free triangle (terrain backend).
  int[] raw=new int[32];float[][] points={{0,1,0},{0,1,1},{1,1,1},{1,1,0}};for(int i=0;i<4;i++)for(int c=0;c<3;c++)raw[i*8+c]=Float.floatToRawIntBits(points[i][c]);
  var q=new net.minecraft.client.renderer.block.model.BakedQuad(raw,-1,Direction.UP,null,true,false);int[] cut=raw.clone();for(int i=0;i<4;i++)cut[i*8]=Float.floatToRawIntBits(points[i][0]*.5f);
  var pieces=RoadTerrainModels.exposedFill(List.of(q),List.of(new net.minecraft.client.renderer.block.model.BakedQuad(cut,-1,Direction.UP,null,true,false)));
  if(pieces.isEmpty()||pieces.stream().anyMatch(net.minecraft.client.renderer.block.model.BakedQuad::hasAmbientOcclusion))throw new AssertionError("clipping re-enabled AO");
  Third211Visual.log("LIGHT CHECK PASS actual_cells="+checked+" sunlit_top_vertices="+vertices+" ordinary_AO=true clipped_AO=false");
 }
}
