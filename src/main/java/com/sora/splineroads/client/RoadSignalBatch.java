package com.sora.splineroads.client;

import com.mojang.blaze3d.vertex.*;
import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.RoadSurface.*;
import java.util.*;
import java.util.function.Function;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;

/** Identical float bits in contiguous storage: no retained object per vertex. */
final class RoadSignalBatch {
  private static final int STRIDE=9,BASE_BYTES=96+Texture.values().length*4;
  private static final class Group {final int[] data;int size;Group(int vertices){data=new int[vertices*STRIDE];}}
  private final V origin;
  private static final RoadUploadArena PLATE_ARENA=new RoadUploadArena();
  private VertexBuffer plateBuffer;private int plateLight;private long plateUploads;
  void close(){if(plateBuffer!=null){plateBuffer.close();plateBuffer=null;}}
  long plateUploads(){return plateUploads;}
  /** Immutable opaque sign ink/boards use the same text vertex format and depth
   * shader. Only light changes upload vertices; camera transforms stay on GPU. */
  void drawPlate(PoseStack pose,V camera,RenderType type,int light,org.joml.Matrix4f projection){
    buffer(light);
    type.setupRenderState();try{
      var matrix=new org.joml.Matrix4f(pose.last().pose()).translate((float)(origin.x()-camera.x()),(float)(origin.y()-camera.y()),(float)(origin.z()-camera.z()));
      plateBuffer.bind();plateBuffer.drawWithShader(matrix,projection,com.mojang.blaze3d.systems.RenderSystem.getShader());
    }finally{VertexBuffer.unbind();type.clearRenderState();}
  }
  boolean currentLight(int light){return plateBuffer!=null&&plateLight==light;}
  int residentLight(){return plateLight;}
  boolean preparePlate(int light){
    if(currentLight(light))return true;
    if(!RoadUploadBudget.claimSign(vertexBytes))return plateBuffer!=null;
    buffer(light);return true;
  }
  private VertexBuffer buffer(int light){
    if(plateBuffer!=null&&plateLight==light)return plateBuffer;
    var builder=PLATE_ARENA.begin(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);
    for(var data:data().values())for(int i=0;i<data.length;i+=STRIDE){int color=data[i+3];builder.vertex(value(data,i),value(data,i+1),value(data,i+2)).color((color>>16)&255,(color>>8)&255,color&255,255).uv(value(data,i+4),value(data,i+5)).uv2(light).endVertex();}
    var built=builder.end();var next=new VertexBuffer(VertexBuffer.Usage.STATIC);try{next.bind();next.upload(built);}catch(RuntimeException|Error failure){next.close();throw failure;}finally{VertexBuffer.unbind();}
    close();plateBuffer=next;plateLight=light;plateUploads++;return next;
  }
  /** Static opaque artwork shares one shader/material setup; only origin matrices change. */
  static void drawPlates(List<RoadSignalBatch> batches,List<Integer> lights,PoseStack pose,V camera,RenderType type,org.joml.Matrix4f projection){
    if(batches.isEmpty())return;
    var buffers=new ArrayList<VertexBuffer>(batches.size());for(int i=0;i<batches.size();i++)buffers.add(batches.get(i).buffer(lights.get(i)));
    type.setupRenderState();var shader=com.mojang.blaze3d.systems.RenderSystem.getShader();
    try{RoadRenderer.setupShader(shader,pose,projection);var transform=new org.joml.Matrix4f();
      for(int i=0;i<batches.size();i++){var origin=batches.get(i).origin;transform.set(pose.last().pose()).translate((float)(origin.x()-camera.x()),(float)(origin.y()-camera.y()),(float)(origin.z()-camera.z()));if(shader.MODEL_VIEW_MATRIX!=null){shader.MODEL_VIEW_MATRIX.set(transform);shader.MODEL_VIEW_MATRIX.upload();}buffers.get(i).bind();buffers.get(i).draw();}
    }finally{shader.clear();VertexBuffer.unbind();type.clearRenderState();}
  }
  private final EnumMap<Texture,int[]> materials=new EnumMap<>(Texture.class);
  private final EnumMap<Texture,byte[]> compressed=new EnumMap<>(Texture.class);
  private final EnumMap<Texture,Integer> lengths=new EnumMap<>(Texture.class);
  private int vertexBytes;
  private static long inflations,inflatedBytes;
  static long inflations(){return inflations;}
  static long inflatedBytes(){return inflatedBytes;}
  void warm(){data();}
  boolean compacted(){return !compressed.isEmpty();}
  int gpuBytes(){return vertexBytes;}
  /** Lossless cold/overflow representation; admission pressure never reruns sign layout. */
  void compact(){
    for(var it=materials.entrySet().iterator();it.hasNext();){var entry=it.next();int[] input=entry.getValue();if(input.length<1024)continue;
      var raw=java.nio.ByteBuffer.allocate(input.length*4);raw.asIntBuffer().put(input);
      var deflater=new java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED);
      try{deflater.setInput(raw.array());deflater.finish();var output=new java.io.ByteArrayOutputStream();byte[] block=new byte[8192];while(!deflater.finished()){int n=deflater.deflate(block);output.write(block,0,n);}byte[] packed=output.toByteArray();if(packed.length<input.length*3){compressed.put(entry.getKey(),packed);lengths.put(entry.getKey(),input.length);it.remove();}}
      finally{deflater.end();}
    }
  }
  private Map<Texture,int[]> data(){
    if(compressed.isEmpty())return materials;
    var result=new EnumMap<Texture,int[]>(materials);
    for(var entry:compressed.entrySet()){
      byte[] raw=new byte[lengths.get(entry.getKey())*4];var inflater=new java.util.zip.Inflater();
      try{inflater.setInput(entry.getValue());int at=0;while(!inflater.finished()&&at<raw.length){int n=inflater.inflate(raw,at,raw.length-at);if(n==0)throw new IllegalStateException("Truncated sign geometry");at+=n;}if(at!=raw.length)throw new IllegalStateException("Invalid sign geometry size");int[] expanded=new int[raw.length/4];java.nio.ByteBuffer.wrap(raw).asIntBuffer().get(expanded);result.put(entry.getKey(),expanded);inflations++;inflatedBytes+=raw.length;}
      catch(java.util.zip.DataFormatException e){throw new IllegalStateException("Invalid sign cache",e);}finally{inflater.end();}
    }
    // A newly hot sign retains the expanded arrays until it becomes cold.
    // Rendering its body and overflow plates does not re-inflate on every call.
    materials.clear();materials.putAll(result);compressed.clear();lengths.clear();return materials;
  }
  RoadSignalBatch(List<Face> faces){
    origin=faces.stream().filter(f->!f.points().isEmpty()).map(f->f.points().get(0)).findFirst().orElse(new V(0,0,0));
    var counts=new EnumMap<Texture,Integer>(Texture.class);
    for(var f:faces){int n=f.points().size();if(n<3)continue;if(!f.uv().isEmpty()&&f.uv().size()!=n)throw new IllegalArgumentException("Signal UV count does not match its polygon");counts.merge(f.texture(),n==4?4:4*(n-2),Integer::sum);}
    var groups=new EnumMap<Texture,Group>(Texture.class);counts.forEach((t,n)->groups.put(t,new Group(n)));
    for(var f:faces){int n=f.points().size();if(n<3)continue;var out=groups.get(f.texture());if(n==4)quad(out,f,0,1,2,3);else for(int i=1;i<n-1;i++)quad(out,f,0,i,i+1,i+1);}
    groups.forEach((t,g)->materials.put(t,g.size==g.data.length?g.data:Arrays.copyOf(g.data,g.size)));
    for(var data:materials.values())vertexBytes+=data.length/STRIDE*28;
  }
  private void quad(Group out,Face face,int a,int b,int c,int d){
    V ab=face.points().get(b).sub(face.points().get(a)),ac=face.points().get(c).sub(face.points().get(a));
    V n=new V(ab.y()*ac.z()-ab.z()*ac.y(),ab.z()*ac.x()-ab.x()*ac.z(),ab.x()*ac.y()-ab.y()*ac.x());double length=Math.sqrt(n.dot(n));
    if(length<1e-10){if(c!=d)quad(out,face,a,c,d,d);return;}n=n.mul(1/length);
    // Shader packs may derive a tangent frame from UV derivatives, even for
    // flat-colour art. Stay inside the original white texel, with nonzero area.
    vertex(out,face,a,n,0);vertex(out,face,b,n,1);vertex(out,face,c,n,2);vertex(out,face,d,n,c==d?2:3);
  }
  private void vertex(Group out,Face face,int index,V n,int corner){
    V p=face.points().get(index).sub(origin);UV uv=face.uv().isEmpty()?null:face.uv().get(index);int[] data=out.data;int i=out.size;
    data[i]=bits(p.x());data[i+1]=bits(p.y());data[i+2]=bits(p.z());data[i+3]=face.color();data[i+4]=bits(uv==null?(corner==0||corner==3?.0078125:.0546875):uv.u());data[i+5]=bits(uv==null?(corner<2?.0078125:.0546875):uv.v());data[i+6]=bits(n.x());data[i+7]=bits(n.y());data[i+8]=bits(n.z());out.size=i+STRIDE;
  }
  private static int bits(double v){return Float.floatToRawIntBits((float)v);}
  private static float value(int[] data,int i){return Float.intBitsToFloat(data[i]);}
  int retainedBytes(){int size=BASE_BYTES;for(var v:materials.values())size+=16+v.length*4;for(var v:compressed.values())size+=48+v.length;return size;}
  int retainedPlateBytes(){return retainedBytes()+vertexBytes;}
  void emit(PoseStack pose,MultiBufferSource buffers,V camera,Function<Texture,RenderType> types,Set<RenderType> used){emit(pose,buffers,camera,types,used,LightTexture.FULL_BRIGHT);}
  void emitPlate(PoseStack pose,MultiBufferSource buffers,V camera,RenderType type,Set<RenderType> used,int light){
    pose.pushPose();pose.translate(origin.x()-camera.x(),origin.y()-camera.y(),origin.z()-camera.z());
    try{var transform=pose.last().pose();var target=buffers.getBuffer(type);used.add(type);
      for(var data:data().values())for(int i=0;i<data.length;i+=STRIDE){int color=data[i+3];target.vertex(transform,value(data,i),value(data,i+1),value(data,i+2)).color((color>>16)&255,(color>>8)&255,color&255,255).uv(value(data,i+4),value(data,i+5)).uv2(light).endVertex();}
    }finally{pose.popPose();}
  }
  void emit(PoseStack pose,MultiBufferSource buffers,V camera,Function<Texture,RenderType> types,Set<RenderType> used,int light){
    pose.pushPose();pose.translate(origin.x()-camera.x(),origin.y()-camera.y(),origin.z()-camera.z());var transform=pose.last().pose();var normals=pose.last().normal();
    try{for(var entry:data().entrySet()){var type=Objects.requireNonNull(types.apply(entry.getKey()),"Missing SR signal material: "+entry.getKey());used.add(type);var target=buffers.getBuffer(type);var data=entry.getValue();
      for(int i=0;i<data.length;i+=STRIDE){int color=data[i+3];target.vertex(transform,value(data,i),value(data,i+1),value(data,i+2)).color((color>>16)&255,(color>>8)&255,color&255,255).uv(value(data,i+4),value(data,i+5)).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(normals,value(data,i+6),value(data,i+7),value(data,i+8)).endVertex();}
    }}finally{pose.popPose();}
  }
}
