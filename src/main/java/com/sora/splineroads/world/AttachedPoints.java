package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
/** Reconcile fixed section markers and paint ownership before the world transaction. */
final class AttachedPoints {
  static void reconcile(RoadData data,List<RoadIndex.Built> next,Set<UUID> removed,boolean deleting,Set<UUID> deletedPoints){
    Map<UUID,RoadAttachments.Point> authored=new LinkedHashMap<>();Map<UUID,UUID> owners=new HashMap<>();
    for(var b:next)if(b.record.junction()==null||OneSidedJunction.retainedPiece(b.record.junction()))for(var p:b.record.settings().options().attachments().points()){
      if(authored.putIfAbsent(p.id(),p)!=null)throw new IllegalArgumentException("附属点存在重复归属");owners.put(p.id(),b.record.id());}
    if(!deleting)for(UUID id:removed){var old=data.index.roads.get(id);if(old==null||old.record.junction()!=null&&!OneSidedJunction.retainedPiece(old.record.junction()))continue;for(var p:old.record.settings().options().attachments().points()){if(deletedPoints.contains(p.id()))continue;authored.putIfAbsent(p.id(),p);owners.putIfAbsent(p.id(),id);}}
    var assigned=new HashMap<UUID,List<RoadAttachments.Point>>();
    for(var p:authored.values()){
      // A Bezier handle may be outside the pavement. Its explicit UUID binding
      // owns it; ordinary attached points retain the on-road reconciliation.
      var curveOwner=next.stream().filter(b->b.record.id().equals(owners.get(p.id()))&&b.record.settings().options().routing().controls().stream().anyMatch(c->c.id().equals(p.id())&&c.position().equals(p.position()))).findFirst().orElse(null);
      if(curveOwner!=null)continue;
      RoadIndex.Built target=null;double best=Double.POSITIVE_INFINITY;
      for(var b:next){if(b.record.junction()!=null&&!com.sora.splineroads.core.OneSidedJunction.retainedPiece(b.record.junction())||b.record.settings().style().ramp()&&!b.record.id().equals(owners.get(p.id())))continue;
        var q=RoadQueries.horizontal(b.mesh,p.position());V tangent=q.tangent().horizontalUnit();double extra=p.position().sub(q.sample().center()).dot(tangent);
        if(q.sample().distance()<1e-6&&extra< -1e-5||q.sample().distance()>b.mesh.length()-1e-6&&extra>1e-5)continue;
        if(q.horizontalDistance()>q.sample().halfWidth()+1e-5)continue;
        double score=q.horizontalDistance()+(b.record.id().equals(owners.get(p.id()))?-100:0);
        if(score<best){best=score;target=b;}
      }
      if(target==null)throw new IllegalArgumentException("调整后附属点 "+p.id().toString().substring(0,8)+" 不再位于道路上，操作已取消");
      var q=RoadQueries.horizontal(target.mesh,p.position());V position=p.elevated()?q.sample().at(RoadProfile.layout(target.mesh,q.sample()).medianCenter(),0):p.controlled()?new V(p.position().x(),q.sample().center().y(),p.position().z()):q.sample().center();
      boolean same=target.record.id().equals(owners.get(p.id()));
      assigned.computeIfAbsent(target.record.id(),k->new ArrayList<>()).add(new RoadAttachments.Point(p.id(),position,same?p.anchor():position,same&&p.controlled(),p.origin(),p.elevated()));
    }
    // Clip a complete world-space paint history to every affected road. This retains the old
    // real seam's paint even when the seam passes a marker or the two roads use different paint.
    var oldPaint=new ArrayList<RoadAttachments.Span>();
    if(!deleting)for(UUID id:removed){var old=data.index.roads.get(id);if(old==null||old.record.junction()!=null)continue;
      TreeSet<Double> cuts=new TreeSet<>();cuts.add(0.0);cuts.add(old.mesh.length());
      for(var span:old.record.settings().options().attachments().spans()){cuts.add(RoadAttachments.station(old.mesh,span.start()));cuts.add(RoadAttachments.station(old.mesh,span.end()));}
      var boundaries=new ArrayList<>(cuts);for(int n=1;n<boundaries.size();n++){double a=boundaries.get(n-1),b=boundaries.get(n);if(b-a>1e-6)oldPaint.add(new RoadAttachments.Span(RoadAttachments.at(old.mesh,a),RoadAttachments.at(old.mesh,b),RoadAttachments.paint(old.mesh,(a+b)/2)));}}
    for(int i=0;i<next.size();i++){var b=next.get(i);if(b.record.junction()!=null&&com.sora.splineroads.core.OneSidedJunction.retainedPiece(b.record.junction()))continue;if(b.record.fragment()!=null&&b.record.settings().options().attachments().equals(b.record.fragment().applied()))continue;var original=b.record.settings().options().attachments();var points=assigned.getOrDefault(b.record.id(),List.of());
      var spans=new ArrayList<RoadAttachments.Span>();
      if(b.record.junction()==null&&(!points.isEmpty()||!original.spans().isEmpty()||removed.stream().map(data.index.roads::get).filter(Objects::nonNull).anyMatch(old->!old.record.settings().options().attachments().equals(RoadAttachments.Data.EMPTY)))){
        for(var span:oldPaint){var qa=RoadQueries.horizontal(b.mesh,span.start());var qb=RoadQueries.horizontal(b.mesh,span.end());
          if(Math.abs(qa.lateral())>.01||Math.abs(qb.lateral())>.01)continue;
          double a=qa.sample().distance(),z=qb.sample().distance();if(Math.abs(z-a)>1e-6)spans.add(new RoadAttachments.Span(RoadAttachments.at(b.mesh,a),RoadAttachments.at(b.mesh,z),span.paint()));}
        if(spans.isEmpty())spans.addAll(original.spans());
      }
      // Unchanged metadata must not accumulate duplicate paint spans on every rebuild.
      spans=new ArrayList<>(new LinkedHashSet<>(spans));
      var value=new RoadAttachments.Data(points,spans);
      if(!value.equals(original))next.set(i,new RoadIndex.Built(b.record.withAttachments(value)));
    }
  }
  private AttachedPoints(){}
}
