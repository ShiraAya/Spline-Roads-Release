package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.SmartSigns.*;
import com.sora.splineroads.net.RoadNetwork;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Ray tests the integrated board plane; signs need no independent block entities. */
public final class SmartTextTool extends Item {
  public SmartTextTool(){super(new Properties().stacksTo(1));}
  public record Hit(State state,Field field,double distance){}
  public static Hit hit(V eye,V direction,Collection<State> states,RoadIndex index){Hit best=null;for(var s:states){var b=index.roads.get(s.key().road());if(b==null||s.target()==null||!SmartSigns.valid(b.mesh,s))continue;if(SmartSigns.anchor(b.mesh,s).distance(eye)>144)continue;for(var layout:SmartSigns.views(SmartSigns.layout(b.mesh,s),s.mode())){double facing=direction.dot(layout.front());if(facing>=-1e-5)continue;double t=layout.origin().add(layout.front().mul(SmartSigns.TEXT)).sub(eye).dot(layout.front())/facing;if(t<0||t>96||best!=null&&t>=best.distance())continue;V at=eye.add(direction.mul(t)).sub(layout.origin());double x=at.dot(layout.right()),y=at.y();for(var board:layout.boards())if(!board.branch().equals("emergency")&&Math.abs(x-board.x())<=board.width()/2&&y>=board.bottom()&&y<=board.top()){Field field=s.mode()==Mode.LANE_INDICATOR?new Field(board.branch()+"/status",board.x(),(board.bottom()+board.top())/2,board.width(),board.height(),1):layout.fields().stream().filter(f->f.editable()&&Math.abs(x-f.x())<=f.width()/2&&Math.abs(y-f.y())<=f.height()/2).findFirst().orElseGet(()->layout.fields().stream().filter(Field::editable).min(Comparator.comparingDouble(f->Math.hypot(x-f.x(),y-f.y()))).orElse(null));if(field!=null)best=new Hit(s,field,t);}}}
    return best;
  }
  private static void open(ServerPlayer p){if(!p.mayBuild()||p.isSpectator())return;try{var t=new CompoundTag();if(p.isShiftKeyDown()){t.putString("Kind","smartTextReset");RoadNetwork.open(p,t);return;}
      var data=RoadData.get(p.serverLevel());var eye=p.getEyePosition();var dir=p.getLookAngle();var candidates=new LinkedHashSet<State>();var ids=new HashSet<UUID>();var at=p.blockPosition();for(int x=(at.getX()>>4)-6;x<=(at.getX()>>4)+6;x++)for(int z=(at.getZ()>>4)-6;z<=(at.getZ()>>4)+6;z++)ids.addAll(data.index.inChunk(net.minecraft.world.level.ChunkPos.asLong(x,z)));for(UUID id:ids)candidates.addAll(data.smartSigns.road(id));candidates.addAll(data.smartSigns.nearSupports(new V(eye.x,eye.y,eye.z)));
      var hit=hit(new V(eye.x,eye.y,eye.z),new V(dir.x,dir.y,dir.z),candidates,data.index);if(hit==null){t.putString("Kind","smartTextResume");RoadNetwork.open(p,t);p.displayClientMessage(Component.literal("请对准智能标牌或车道指示牌正面右键"),true);return;}
      var b=data.index.roads.get(hit.state().key().road());RoadData.requireOwner(p,b.record.owner());t.putString("Kind","smartText");t.put("Sign",SmartSignCodec.write(hit.state()));t.put("Road",b.record.header());t.putString("Field",hit.field().id());RoadNetwork.open(p,t);
    }catch(IllegalArgumentException e){p.displayClientMessage(Component.literal(e.getMessage()),false);}}
  @Override public InteractionResult useOn(UseOnContext c){if(c.getPlayer() instanceof ServerPlayer p)open(p);return InteractionResult.sidedSuccess(c.getLevel().isClientSide);}
  @Override public InteractionResultHolder<ItemStack> use(Level l,Player p,InteractionHand h){if(p instanceof ServerPlayer s)open(s);return InteractionResultHolder.sidedSuccess(p.getItemInHand(h),l.isClientSide);}
  @Override public void appendHoverText(ItemStack s,Level l,List<Component> lines,TooltipFlag f){lines.add(Component.literal("右键智能标牌填写文字；右键车道指示牌设置通行／禁行"));lines.add(Component.literal("Shift＋右键清除临时选择，不删除已保存文字"));}
}
