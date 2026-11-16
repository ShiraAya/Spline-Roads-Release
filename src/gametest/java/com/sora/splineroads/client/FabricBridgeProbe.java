package com.sora.splineroads.client;
import java.lang.reflect.*;import java.util.*;import java.util.concurrent.atomic.*;import net.minecraft.client.resources.model.BakedModel;import net.minecraftforge.client.model.data.ModelData;
public class FabricBridgeProbe {
 public static void main(String[] args)throws Exception{
  boolean present=Boolean.parseBoolean(args[0]);Class<?> fabric=null;if(present)fabric=Class.forName("net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel");
  AtomicInteger block=new AtomicInteger(),item=new AtomicInteger(),forge=new AtomicInteger();
  InvocationHandler bh=(p,m,a)->{switch(m.getName()){case "emitBlockQuads":block.incrementAndGet();return null;case "emitItemQuads":item.incrementAndGet();return null;case "isVanillaAdapter":return false;case "toString":return "CTM test model";default:return null;}};
  var base=(BakedModel)Proxy.newProxyInstance(FabricBridgeProbe.class.getClassLoader(),present?new Class[]{BakedModel.class,fabric}:new Class[]{BakedModel.class},bh);
  var terrain=(BakedModel)Proxy.newProxyInstance(FabricBridgeProbe.class.getClassLoader(),new Class[]{BakedModel.class},(p,m,a)->{if(m.getName().equals("getModelData"))return ModelData.EMPTY;if(m.getName().equals("getQuads")){forge.incrementAndGet();return List.of();}return null;});
  var wrapped=RoadFabricModels.wrap(base,terrain,d->List.of(),(q,d)->q,d->false);
  if(!present){if(wrapped!=terrain)throw new AssertionError("absent path changed");System.out.println("FABRIC ABSENT PASS");return;}
  Class<?> context=Class.forName("net.fabricmc.fabric.api.renderer.v1.render.RenderContext");
  Object ctx=Proxy.newProxyInstance(FabricBridgeProbe.class.getClassLoader(),new Class[]{context},(p,m,a)->m.getName().equals("getModelData")?ModelData.EMPTY:null);
  if(!fabric.isInstance(wrapped)||(boolean)fabric.getMethod("isVanillaAdapter").invoke(wrapped))throw new AssertionError("interface lost");
  for(Method m:fabric.getMethods())if(m.getName().equals("emitBlockQuads"))m.invoke(wrapped,null,null,null,null,ctx);else if(m.getName().equals("emitItemQuads"))m.invoke(wrapped,null,null,ctx);
  for(Method m:BakedModel.class.getMethods())if(m.getName().equals("getQuads")&&m.getParameterCount()==5)m.invoke(wrapped,null,null,null,ModelData.EMPTY,null);
  if(block.get()!=1||item.get()!=1||forge.get()!=1||RoadFabricModels.unwrap(wrapped)!=base)throw new AssertionError("delegate mismatch");
  System.out.println("FABRIC PRESENT PASS block="+block+" item="+item+" forge="+forge);
 }
}
