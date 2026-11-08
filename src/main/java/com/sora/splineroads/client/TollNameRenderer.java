package com.sora.splineroads.client;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.TollPlaza.Spec;
import com.mojang.math.Axis;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraftforge.client.event.RenderLevelStageEvent;

/** Roof letters use Minecraft's actual language font and normal world depth. */
public final class TollNameRenderer {
  private static long revision=-1;private static int chunkX=Integer.MIN_VALUE,chunkZ=Integer.MIN_VALUE;
  private static List<Spec> nearby=List.of();
  static void clear(){revision=-1;chunkX=chunkZ=Integer.MIN_VALUE;nearby=List.of();}
  static List<Spec> nearby(int cx,int cz){
    var index=ClientRoads.INDEX;if(revision==index.revision()&&cx==chunkX&&cz==chunkZ)return nearby;
    var ids=new HashSet<UUID>();for(int x=cx-12;x<=cx+12;x++)for(int z=cz-12;z<=cz+12;z++)ids.addAll(index.inChunk(net.minecraft.world.level.ChunkPos.asLong(x,z)));
    var specs=new LinkedHashSet<Spec>();for(UUID id:ids){var road=index.roads.get(id);if(road!=null&&ClientRoads.active(id)&&road.record.toll()!=null&&!road.record.toll().config().name().isEmpty())specs.add(road.record.toll());}
    nearby=List.copyOf(specs);revision=index.revision();chunkX=cx;chunkZ=cz;return nearby;
  }
  public static void render(RenderLevelStageEvent event){
    if(!RoadSignVisibility.enabled())return;
    var mc=Minecraft.getInstance();if(mc.level==null)return;var eye=event.getCamera().getPosition();
    for(var spec:nearby((int)Math.floor(eye.x)>>4,(int)Math.floor(eye.z)>>4))draw(spec,event);
    if(TollPreview.namedPreview!=null&&ClientRoads.preview!=null)draw(TollPreview.namedPreview.spec(),event);
  }
  private static void draw(Spec spec,RenderLevelStageEvent event){
    String text=spec.config().name();if(text.isEmpty())return;var mc=Minecraft.getInstance();var camera=event.getCamera().getPosition();
    V axis=spec.b().position().sub(spec.a().position()).horizontalUnit();var lengths=TollPlanner.lengths(spec);V station=spec.a().position().add(axis.mul((lengths.current()+lengths.a()-lengths.b())/2));V center=new V(station.x(),(spec.a().position().y()+spec.b().position().y())/2,station.z());
    if(!spec.path().isEmpty()){double d=lengths.current()/2;center=TollInlineGeometry.equipmentAt(spec,d,0,0);axis=TollInlineGeometry.equipmentAt(spec,d+.05,0,0).sub(TollInlineGeometry.equipmentAt(spec,d-.05,0,0)).horizontalUnit();}
    V eye=new V(camera.x,camera.y,camera.z);if(eye.distance(center)>192)return;
    var styled=Component.literal(text).withStyle(ChatFormatting.BOLD);
    var pose=event.getPoseStack();var buffers=mc.renderBuffers().bufferSource();
    for(int side:TollPlanner.nameSides(spec.config())){double span=TollPlanner.nameWidth(spec.config(),side)-.36,scale=Math.min(.16,span/Math.max(1,mc.font.width(styled)));V front=axis.mul(side),right=front.left().mul(-1);V at=center.add(axis.left().mul(TollPlanner.nameCenter(spec.config(),side))).add(front.mul(5.765)).add(new V(0,spec.config().clearHeight()+2.35+4*scale,0));
      if(eye.sub(at).dot(front)<=0)continue;
      var box=new net.minecraft.world.phys.AABB(at.x()-span,at.y()-2,at.z()-span,at.x()+span,at.y()+1,at.z()+span);if(!event.getFrustum().isVisible(box))continue;
      for(int layer=0;layer<4;layer++){V point=at.add(front.mul(layer*.035));pose.pushPose();pose.translate(point.x()-camera.x,point.y()-camera.y,point.z()-camera.z);
        pose.mulPose(Axis.YP.rotation((float)Math.atan2(-right.z(),right.x())));pose.scale((float)scale,(float)-scale,(float)scale);
        mc.font.drawInBatch(styled,-mc.font.width(styled)/2f,0,layer==3?0xEC4238:0x8D2925,false,pose.last().pose(),buffers,Font.DisplayMode.NORMAL,0,LightTexture.FULL_BRIGHT);pose.popPose();
      }
    }
  }
  private TollNameRenderer(){}
}
