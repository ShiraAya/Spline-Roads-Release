package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Lane guides, chevron gore and direction arrows share the actual two branch paths. */
public final class RoadJunction {
  public record Paint(List<V> points, int color) {}

  public record Zone(double from, double to) {
    public boolean contains(double d) {
      return d >= from && d <= to;
    }
  }

  private record Route(Mesh mesh, double origin, int sign) {
    Sample at(double d) {
      double distance = origin + sign * d;
      if (mesh.closed()) distance = ((distance % mesh.length()) + mesh.length()) % mesh.length();
      if (!mesh.closed() && (distance < 0 || distance > mesh.length())) {
        Sample end = distance < 0 ? mesh.first() : mesh.last();
        double along = distance < 0 ? distance : distance - mesh.length();
        V forward = end.left().left().mul(-1);
        return new Sample(end.center().add(forward.mul(along)), end.left(), distance, end.halfWidth());
      }
      return RoadStructures.sample(mesh, distance);
    }

    double available() {
      return mesh.closed() ? mesh.length() / 2 : sign > 0 ? mesh.length() - origin : origin;
    }
  }

  private record Join(Route a, Route b, double nose, double available, boolean parallel) {}

  /** A declared sampled interchange fork shares a grading throat. Two nearby
   * unrelated decks must not receive this relaxed seam tolerance. */
  public static boolean sharedRampFork(Mesh a,Mesh b){
    if(a==null||b==null||!a.settings().style().ramp()||!b.settings().style().ramp()
        ||a.settings().style().connectorRamp()||b.settings().style().connectorRamp())return false;
    for(boolean first:new boolean[]{true,false})if(join(a,b,first)!=null||join(b,a,first)!=null)return true;
    return false;
  }

  private static Join join(Mesh ramp, Mesh host, boolean first) {
    if(ramp.settings().style().connectorRamp()||host.settings().style().connectorRamp())return null;
    return measureJoin(ramp,host,first);
  }

  private static Join measureJoin(Mesh ramp,Mesh host,boolean first){
    var endpoint = first ? ramp.first() : ramp.last();
    var projection = RoadQueries.project(host, endpoint.center());
    if (projection.horizontalDistance() > projection.sample().halfWidth() + endpoint.halfWidth() - .10
        || Math.abs(projection.sample().center().y() - endpoint.center().y()) > .12) return null;
    V direction = endpoint.left().left().mul(first ? -1 : 1);
    double dot = direction.dot(projection.tangent().horizontalUnit());
    if (Math.abs(dot) < .94) return null;
    Route a = new Route(ramp, first ? 0 : ramp.length(), first ? 1 : -1),
        b = new Route(host, projection.sample().distance(), dot > 0 ? 1 : -1);
    double available = Math.min(256, Math.min(a.available(), b.available()));
    if (available < 8) return null;
    // A highway auxiliary lane welds to the verge by .02 blocks. Do not
    // mistake that narrow shared edge for the physical fork during its taper.
    double separation = highwayExtra(ramp,host,first) ? .05 : -.10;
    for (double d = 1; d <= available; d += .5) {
      Sample aa = a.at(d), bb = paired(a, b, d);
      if (aa.center().sub(bb.center()).horizontalLength() >= aa.halfWidth() + bb.halfWidth() + separation
          || Math.abs(aa.center().y()-bb.center().y())>.12)
        return new Join(a, b, d, available, false);
    }
    // An auxiliary lane may share the mainline edge over its entire feeder. The physical fork
    // is in the next leg; still retain merge guidance at this narrowing terminal.
    if (!host.settings().style().ramp()
        && endpoint.halfWidth() * 2 < ramp.settings().width() * .94
        && a.at(available).halfWidth() * 2 >= ramp.settings().width() * .94)
      return new Join(a, b, available, available, true);
    return null;
  }

  /**
   * Only adjacent branches own a separator; a third branch between them prevents a giant
   * cross-gore.
   */
  private static boolean adjacent(
      Join pair, Mesh ramp, Mesh host, List<Mesh> neighbors, boolean first) {
    double d = Math.min(pair.nose, 32);
    V a = pair.a.at(d).center(), b = paired(pair.a, pair.b, d).center(), axis = b.sub(a);
    double length2 = axis.dot(axis);
    if (length2 < .01) return true;
    for (Mesh other : neighbors) {
      if (other == host || other == ramp || !other.settings().style().ramp()) continue;
      Join candidate = join(ramp, other, first);
      if (candidate == null) continue;
      V q = paired(candidate.a, candidate.b, d).center().sub(a);
      double t = q.dot(axis) / length2;
      if (t > .05 && t < .95 && q.sub(axis.mul(t)).horizontalLength() < 2) return false;
    }
    return true;
  }

  public static List<Zone> dividerZones(Mesh ramp, List<Mesh> neighbors) {
    if(MultiFanPaint.applies(ramp))return MultiFanPaint.zones(ramp);
    if (!ramp.settings().style().ramp()) return List.of();
    List<Zone> zones = new ArrayList<>(closureZones(ramp,neighbors));
    for (Mesh other : neighbors) if (twoLaneRamps(ramp, other))
      for (Join pair : twoLaneForks(ramp, other)) {
        Route route = pair.a.mesh == ramp ? pair.a : pair.b;
        double last = pair.a.mesh == ramp ? pair.a.at(forkLimit(pair)).distance()
            : paired(pair.a, pair.b, forkLimit(pair)).distance();
        double begin=route.at(0).distance();
        zones.add(new Zone(Math.min(begin, last), Math.max(begin, last)));
      }
    for (boolean first : new boolean[] {true, false})
      for (Mesh other : neighbors) {
        if (!other.settings().style().ramp() || twoLaneRamps(ramp, other)) continue;
        Join j = join(ramp, other, first);
        if (j == null) continue;
        double end = forkLimit(j);
        zones.add(first ? new Zone(0, end) : new Zone(ramp.length() - end, ramp.length()));
      }
    return zones;
  }

  private static boolean highwayExtra(Mesh ramp,Mesh host,boolean first){
    var link=ramp.settings().options().lanePoints().link();
    if(link==null||!RoadProfile.highway(host.settings().style())||
        !(first?link.options().sourceExtra():link.options().targetExtra()))return false;
    var ref=first?link.from():link.to();
    return host.settings().options().lanePoints().points().stream().anyMatch(p->p.id().equals(ref.point()));
  }
  private static double closureLength(Mesh ramp, Mesh host, boolean first) {
    if(highwayExtra(ramp,host,first))return Math.min(ramp.length()/2,LaneRampApproach.taper(host,ramp.settings().options().lanePoints().link().options().transition()));
    double reach = Math.min(Math.min(48, ramp.length() * .60),
        Math.max(24, host.settings().width() * 1.5));
    // Parametric road-tool ramps open their full-width lane after the first third
    // of the lead. Interchange ribbons have independently sampled lead geometry.
    if (ramp.settings().separatedPort(first))
      reach = Math.min(reach,
          6 + .35 * ((first ? ramp.settings().startLead() : ramp.settings().endLead()) - 6));
    else {
      // Sampled interchange leads can widen for longer than the legacy fixed
      // shoulder closure. Do not start a divider inside that unfinished taper.
      double limit = Math.min(96, ramp.length() * .60);
      for (double d = 0; d <= limit; d += .5) {
        Sample at = RoadStructures.sample(ramp, first ? d : ramp.length() - d);
        if (at.halfWidth() * 2 >= ramp.settings().width() * .94) {
          var q=RoadQueries.horizontal(host,at.center());
          boolean addedLane=(first?ramp.first():ramp.last()).halfWidth()<.5
              &&Math.abs(at.center().y()-q.sample().center().y())<.12
              &&q.horizontalDistance()<q.sample().halfWidth()+at.halfWidth()-.5;
          reach = addedLane?d:Math.max(reach, d);
          break;
        }
      }
    }
    return Math.max(8, reach);
  }
  private static boolean taperClosure(Mesh ramp,Mesh host,boolean first){
    if(highwayExtra(ramp,host,first))return ForkSignPlacement.onPath(host,first?ramp.first().center():ramp.last().center(),.35);
    // Ordinary roads also have a narrowing auxiliary port, even without a shoulder.
    if(ramp.settings().style().connectorRamp()||!ramp.settings().style().ramp()||host.settings().style().ramp()||host.settings().style()==Style.UNMARKED)return false;
    Sample end=first?ramp.first():ramp.last();if(end.halfWidth()*2>=ramp.settings().width()*.7)return false;
    var projection=RoadQueries.horizontal(host,end.center());return Math.abs(end.center().y()-projection.sample().center().y())<=.1&&projection.horizontalDistance()<=end.halfWidth()+projection.sample().halfWidth()&&Math.abs(end.left().dot(projection.sample().left()))>=.94;
  }
  private static List<Zone> closureZones(Mesh ramp,List<Mesh> neighbors){var out=new ArrayList<Zone>();for(Mesh host:neighbors)for(boolean first:new boolean[]{true,false})if(taperClosure(ramp,host,first)){double reach=closureLength(ramp,host,first);out.add(first?new Zone(0,reach):new Zone(ramp.length()-reach,ramp.length()));}return out;}

  public record EdgeZone(double from, double to, int side) {
    public boolean contains(double d, int edge) {
      return edge == side && d >= from && d <= to;
    }
  }

  public static List<EdgeZone> edgeZones(Mesh mesh, List<Mesh> neighbors) {
    if(MultiFanPaint.applies(mesh))return List.of();
    List<EdgeZone> zones = new ArrayList<>();
    for (Mesh other : neighbors)
      for (boolean first : new boolean[] {true, false}) {
        if (mesh.settings().style().ramp()) {
          Join j = highwayExtra(mesh,other,first)?measureJoin(mesh,other,first):join(mesh, other, first);
          if (j != null && !j.parallel && goreRequired(j) && adjacent(j, mesh, other, neighbors, first))
            addEdge(zones,j,true);
        }
        if (other.settings().style().ramp()) {
          Join j = highwayExtra(other,mesh,first)?measureJoin(other,mesh,first):join(other, mesh, first);
          if (j != null && !j.parallel && goreRequired(j)) addEdge(zones,j,false);
        }
      }
    return zones;
  }

  /** Parallel auxiliary roads use a dashed lane boundary, without a false chevron gore. */
  private static boolean parallelFeeder(Mesh ramp, Mesh host) {
    if (ramp.settings().style().connectorRamp() || !ramp.settings().style().ramp() || host.settings().style().ramp()
        || host.settings().style() == Style.UNMARKED) return false;
    for (double d = 0; d <= ramp.length(); d += Math.max(.5, ramp.length() / 24)) {
      Sample a = RoadStructures.sample(ramp, d);
      var b = RoadQueries.horizontal(host, a.center());
      if (Math.abs(a.center().y() - b.sample().center().y()) > .12
          // Two-lane feeders have a wider lateral taper before their parallel portion.
          || Math.abs(a.left().dot(b.sample().left())) < .90
          || b.horizontalDistance() > b.sample().halfWidth() + a.halfWidth() + .25)
        return false;
    }
    return true;
  }

  private static void addEdge(List<EdgeZone> out,Join pair,boolean ramp) {
    Sample aa = ramp?pair.a.at(pair.nose):paired(pair.a,pair.b,pair.nose);
    Sample bb = ramp?paired(pair.a,pair.b,pair.nose):pair.a.at(pair.nose);
    int side = aa.left().dot(bb.center().sub(aa.center())) > 0 ? 1 : -1;
    // The host's station differs from distance travelled on a curved ramp.
    // Mask exactly the authored gore and its tail, not a fixed nose-minus-28
    // interval which could erase exposed road before/after the real markings.
    double from=goreStart(pair),to=Math.min(pair.available,pair.nose+5);
    double begin=(ramp?pair.a.at(from):paired(pair.a,pair.b,from)).distance();
    double end=(ramp?pair.a.at(to):paired(pair.a,pair.b,to)).distance();
    // Only the painted gore replaces solid borders. Clipping the whole approach erased
    // exposed edges when an auxiliary carriageway continued through a second branch.
    out.add(new EdgeZone(Math.min(begin, end), Math.max(begin, end), side));
  }

  public static List<Mesh> intersections(Mesh road, List<Mesh> neighbors) {
    List<Mesh> result = new ArrayList<>();
    for (Mesh other : neighbors) {
      if (other.settings().style() == Style.UNMARKED) {
        result.add(other);
        continue;
      }
      if (!other.settings().style().ramp()||other.settings().style().connectorRamp()) continue;
      for (int i = 0; i < other.samples().size(); i += 6) {
        Sample p = other.samples().get(i);
        if (!RoadQueries.contains(road, p.center(), 0, .05)) continue;
        var q = RoadQueries.project(road, p.center());
        if (q.horizontalDistance() < Math.max(2, other.settings().width() / 2)
            && Math.abs(q.sample().left().dot(p.left())) < .65) {
          result.add(other);
          break;
        }
      }
    }
    return result;
  }

  public static List<Paint> arrows(Mesh ramp, List<Mesh> neighbors) {
    return arrows(ramp, neighbors, intersections(ramp, neighbors));
  }

  public static List<Paint> arrows(Mesh ramp, List<Mesh> neighbors, List<Mesh> crossings) {
    if(ramp.settings().style().connectorRamp()){
      if(neighbors.stream().anyMatch(host->highwayExtra(ramp,host,false)))return List.of(); // Host owns shoulder paint.
      return LaneRampPaint.arrows(ramp,neighbors);
    }
    if(MultiFanPaint.applies(ramp))return List.of();
    if (RoadProfile.modern(ramp.settings().style())) {
      List<Paint> out = new ArrayList<>();
      var closures=closureZones(ramp,neighbors);
      for (var p : laneArrows(ramp, neighbors))
        if (p.points().stream().noneMatch(v->closures.stream().anyMatch(z->z.contains(RoadQueries.project(ramp,v).sample().distance()))) && crossings.stream()
            .noneMatch(m -> p.points().stream().anyMatch(v -> RoadQueries.contains(m, v, .5, .1))))
          out.add(p);
      for (var entry : RoadSignals.approaches(ramp, neighbors))
        stroke(out, entry.inner(), entry.outer(), .4);
      return out;
    }
    if (!ramp.settings().style().ramp()||ramp.settings().options().hideArrows()) return List.of();
    List<Paint> out = new ArrayList<>();
    for (boolean first : new boolean[] {true, false}) {
      double distance = -1;
      for (Mesh host : neighbors) {
        Join j = join(ramp, host, first);
        if (j != null) distance = Math.max(distance, Math.min(j.a.available() - 2, j.nose + 5));
      }
      if (distance < 0) continue;
      Sample s = RoadStructures.sample(ramp, first ? distance : ramp.length() - distance);
      double lateral =
          RoadProfile.modern(ramp.settings().style())
              ? RoadProfile.layout(ramp.settings(), s.halfWidth() * 2).motorCenter()
              : 0;
      arrow(out, s.at(lateral, 0), s.left().left().mul(-1), s.left());
    }
    return out;
  }

  /** Arrow direction follows the configured traffic side; all offsets exclude shoulders/medians. */
  private static List<Paint> laneArrows(Mesh mesh, List<Mesh> neighbors) {
    var raw=rawLaneArrows(mesh,neighbors);
    if(!mesh.settings().style().ramp()||raw.isEmpty())return raw;
    var others=new LinkedHashMap<Mesh,List<Paint>>();
    for(var other:neighbors)if(other.settings().style().ramp()&&!other.settings().style().connectorRamp()){
      var context=new ArrayList<Mesh>(neighbors);context.remove(other);context.add(mesh);
      others.put(other,rawLaneArrows(other,context));
    }
    var out=new ArrayList<Paint>();
    for(int i=0;i+2<raw.size();i+=3){
      var group=raw.subList(i,i+3);V center=arrowCenter(group),direction=arrowDirection(group);
      boolean duplicate=false;
      for(var entry:others.entrySet())for(int j=0;j+2<entry.getValue().size();j+=3){
        var candidate=entry.getValue().subList(j,j+3);V delta=arrowCenter(candidate).sub(center);
        double along=delta.dot(direction);
        // Separate sampled legs of one turbine feeder must share one merge
        // arrow. Keep the downstream group intact, never erase an adjacent lane.
        if(direction.dot(arrowDirection(candidate))>.97&&Math.abs(delta.y())<.12
            &&Math.abs(delta.dot(direction.left()))<.8&&along>=-1e-6&&along<32
            &&(along>1e-6||meshOrder(mesh,entry.getKey())>0))duplicate=true;
      }
      if(!duplicate)out.addAll(group);
    }
    return List.copyOf(out);
  }
  private static V arrowCenter(List<Paint> group){var points=group.stream().flatMap(p->p.points().stream()).toList();V sum=new V(0,0,0);for(var p:points)sum=sum.add(p);return sum.mul(1.0/points.size());}
  private static V arrowDirection(List<Paint> group){var p=group.get(0).points();return p.get(2).add(p.get(3)).sub(p.get(0)).sub(p.get(1)).horizontalUnit();}
  private static List<Paint> rawLaneArrows(Mesh mesh, List<Mesh> neighbors) {
    List<Paint> out = new ArrayList<>(LaneMerge.guides(mesh));
    Join merge = null;
    var closures = closureZones(mesh, neighbors);
    if (mesh.settings().style().ramp())
      for (Mesh host : neighbors) {
        if (host.settings().style().ramp() || host.settings().style() == Style.UNMARKED) continue;
        if (parallelFeeder(mesh, host) && mesh.last().halfWidth() * 2 >= mesh.settings().width() * .94) continue;
        Join candidate = join(mesh, host, false);
        if (candidate != null && (merge == null || candidate.nose > merge.nose)) merge = candidate;
      }
    for (double d = 12; !mesh.settings().style().ramp() && d < mesh.length() - 5; d += 48) {
      if (RoadAttachments.paint(mesh,d).hideArrows())continue;
      if (merge != null && d > mesh.length() - merge.nose - 8) continue;
      Sample s = RoadStructures.sample(mesh, d);
      if (Double.isFinite(RoadTransitions.dropBoundary(mesh, s))) continue;
      if (mesh.settings().style().ramp() && s.halfWidth() * 2 < mesh.settings().width() * .95)
        continue;
      var l = RoadProfile.layout(mesh, s);
      var c = l.catalog();
      if(mesh.reference()!=null||!mesh.settings().options().lanePoints().additions().isEmpty()) {
        var raw=LaneSections.reference(mesh);
        for(int slot:LaneAdditions.slots(raw,d))if(LaneSections.active(mesh,d,slot)&&!LaneMerge.merging(mesh,d,slot)&&!LaneClosureWarnings.covers(mesh,d,slot)) {
          var lane=LanePoints.lane(raw,d,slot);
          arrow(out,lane.position(),lane.direction(),s.left().mul(lane.sign()));
        }
        continue;
      }
      for (int i = 0; i < c.lanes(); i++) {
        double lateral;
        if (c.twoWay()) {
          int per = l.lanesOnSide(-1), side = i < per ? -1 : 1;
          lateral = l.medianCenter() + side * (l.median() / 2 + ((side < 0 ? i : i - per) + .5) * l.laneWidth());
        } else lateral = l.motorMin() + (i + .5) * l.laneWidth();
        int sign = c.twoWay() && (lateral < l.medianCenter() ? -1 : 1) != l.outside() ? -1 : 1;
        arrow(out, s.at(lateral, 0), s.left().left().mul(-sign), s.left().mul(sign));
      }
    }
    if (merge != null) for (int lane = 0; lane < RoadProfile.catalog(mesh.settings()).lanes(); lane++) {
      double back = Math.min(merge.nose - 2, Math.max(36, merge.nose * .60));
      // A two-lane ramp may expose its inner motor lane only just beyond the fork nose.
      for (; back < Math.max(merge.nose + 40, 110) && back < mesh.length() - 4; back += 1) {
        Sample at = RoadStructures.sample(mesh, mesh.length() - back);
        if (at.halfWidth() * 2 < mesh.settings().width() * .94) continue;
        Sample host = RoadQueries.horizontal(merge.b.mesh, at.center()).sample();
        double sign = Math.signum(at.left().dot(host.center().sub(at.center())));
        if (sign == 0) break;
        double inside = guideEdge(merge.b, host, at).sub(at.center()).dot(at.left());
        double outside = -sign * (at.halfWidth() - .3);
        var layout = RoadProfile.layout(mesh, at);
        // The ramp's motor lanes, not the entire two-lane strip, own the merge arrows.
        double laneCenter = layout.motorMin() + (lane + .5) * layout.laneWidth();
        double width = layout.laneWidth();
        if (laneCenter < Math.min(inside, outside) + .7
            || laneCenter > Math.max(inside, outside) - .7) continue;
        if (width < 2) continue;
        int firstPaint = out.size();
        out.addAll(RoadMergeArrow.flat(at.center(),at.left().left().mul(-1),at.left(),laneCenter,width,sign));
        // Fit the complete arrow (including its head) in this lane at every vertex.
        // Per-face closure filtering used to remove the outer arrowhead while leaving
        // its shaft; a center-only check let the inner head cross the main boundary.
        boolean fits = true;
        for (Paint paint : out.subList(firstPaint, out.size())) for (V point : paint.points()) {
          var q = RoadQueries.horizontal(mesh, point);
          var profile = RoadProfile.layout(mesh, q.sample());
          double lo = profile.motorMin() + lane * profile.laneWidth() + .18;
          double hi = lo + profile.laneWidth() - .36;
          var h = RoadQueries.horizontal(merge.b.mesh, point).sample();
          V boundary = guideEdge(merge.b, h, q.sample());
          double towardHost = Math.signum(q.sample().left().dot(h.center().sub(q.sample().center())));
          if (q.lateral() < lo || q.lateral() > hi
              || point.sub(boundary).dot(q.sample().left()) * towardHost > -.18
              || closures.stream().anyMatch(z -> z.contains(q.sample().distance()))) fits = false;
        }
        if (!fits) { out.subList(firstPaint, out.size()).clear(); continue; }
        break;
      }
    }
    return out.stream()
        .map(
            p ->
                new Paint(
                    p.points().stream()
                        .map(
                            v -> {
                              var q = RoadQueries.project(mesh, v);
                              return new V(v.x(), q.sample().center().y(), v.z());
                            })
                        .toList(),
                    p.color()))
        .toList();
  }

  public static List<Paint> markings(Mesh ramp, List<Mesh> hosts) {
    return markings(ramp, hosts, hosts);
  }

  public static List<Paint> markings(Mesh ramp, List<Mesh> hosts, List<Mesh> neighbors) {
    return markings(ramp, hosts, neighbors, false);
  }

  public static List<Paint> markings(Mesh ramp, List<Mesh> hosts, List<Mesh> neighbors, boolean mainOwned) {
    if(MultiFanPaint.applies(ramp))return MultiFanPaint.markings(ramp,neighbors);
    if (!ramp.settings().style().ramp()||ramp.settings().style().connectorRamp()) {
      List<Paint> out = terminalMarkings(ramp);
      if (mainOwned) mainGuides(out, ramp, neighbors);
      return out;
    }
    List<Paint> out = new ArrayList<>();
    // The lower-priority surface emits both bands, regardless of which branch owns
    // the endpoint (a loop can attach to the middle of a continuous collector).
    for (Mesh host : hosts) if (twoLaneRamps(ramp, host))
      for (Join pair : twoLaneForks(ramp, host))
        if (adjacent(pair, pair.a.mesh, pair.b.mesh, neighbors, pair.a.sign > 0))
          twoLaneForkGuides(out, pair);
    for (Mesh host : hosts) if (parallelFeeder(ramp, host)) {
      // One owner and one dash phase for the entire shared lane, not one per endpoint.
      for (double d = 1; !mainOwned && d < ramp.length(); d += 3) {
        Sample a = RoadStructures.sample(ramp, d),
            b = RoadStructures.sample(ramp, Math.min(ramp.length(), d + 1.5));
        var ah = RoadQueries.horizontal(host, a.center()).sample();
        var bh = RoadQueries.horizontal(host, b.center()).sample();
        Route route = new Route(host, 0, 1);
        stroke(out, guideEdge(route, ah, a), guideEdge(route, bh, b), .15);
      }
      taperMarkings(out, ramp, host);
      return out;
    }
    for (boolean first : new boolean[] {true, false})
      for (Mesh host : hosts) {
        if (host.settings().style() == Style.UNMARKED || twoLaneRamps(ramp, host)) continue;
        Join pair = join(ramp, host, first);
        if (pair == null || !adjacent(pair, ramp, host, neighbors, first)) continue;
        Route a = pair.a, b = pair.b;
        double nose = pair.nose, available = pair.available;
        if (parallelFeeder(ramp, host) || pair.parallel) {
          for (double d = 1; !mainOwned && d < available; d += 3)
            stroke(out, guideEdge(b, paired(a, b, d), a.at(d)),
                guideEdge(b, paired(a, b, Math.min(available, d + 1.5)),
                    a.at(Math.min(available, d + 1.5))), .15);
          continue;
        }
        if (host.settings().style().ramp()) {
          rampGuides(out, pair);
          continue;
        }
        if (!goreRequired(pair)) {
          // An unchanged ordinary mainline has no shoulder/lane-drop wedge to hatch.
          // Keep its normal edge and short auxiliary-boundary dashes.
          if (!mainOwned && !host.settings().style().ramp())
            for (double d = 1; d < nose; d += 3)
              stroke(out, guideEdge(b, paired(a, b, d), a.at(d)),
                  guideEdge(b, paired(a, b, Math.min(nose, d + 1.5)),
                      a.at(Math.min(nose, d + 1.5))), .15);
          continue;
        }
        goreMarkings(out,pair,mainOwned);

      }
    for (Mesh host : hosts) if (!host.settings().style().ramp()) taperMarkings(out, ramp, host);
    return out;
  }

  private static void goreMarkings(List<Paint> out, Join pair, boolean mainOwned){
        Route a=pair.a,b=pair.b;Mesh host=b.mesh;double nose=pair.nose,available=pair.available;
        double begin = goreStart(pair);
        // Short dashed separation guides lead into the wider chevron zone.
        for (double d = 2; !mainOwned && !host.settings().style().ramp() && d < begin; d += 3) {
          double e = Math.min(begin, d + 1.5);
          Sample aa = a.at(d), ab = a.at(e), ba = paired(a, b, d), bb = paired(a, b, e);
          if (aa.center().sub(ba.center()).horizontalLength()
              > Math.min(aa.halfWidth(), ba.halfWidth()))
            stroke(out, guideEdge(b, ba, aa), guideEdge(b, bb, ab), .15);
        }
        if (nose > begin + 2) {
          for(double d=Math.max(0,begin-3.2);d<begin-1e-7;d+=.25){
            double e=Math.min(begin,d+.25);V aa=shoulder(b.mesh)?guideEdge(b,paired(a,b,d),a.at(d)):median(a.at(d),paired(a,b,d));
            V bb=shoulder(b.mesh)?guideEdge(b,paired(a,b,e),a.at(e)):median(a.at(e),paired(a,b,e));stroke(out,aa,bb,.16);
          }
          V prevLeft = null, prevRight = null;
          int steps = (int) Math.ceil((nose - begin) / .5);
          for (int step = 0; step <= steps; step++) {
            double d = begin + (nose - begin) * step / steps;
            V[] edge = gore(a, b, d, begin, nose);
            if (prevLeft != null) {
              stroke(out, prevLeft, edge[0], .16);
              stroke(out, prevRight, edge[1], .16);
            }
            prevLeft = edge[0];
            prevRight = edge[1];
          }
          for (double d = begin + 2; d < nose - 1; d += 4.0) {
            V[] edge = gore(a, b, d, begin, nose),
                tip = gore(a, b, Math.max(begin, d - 1.6), begin, nose);
            V center = tip[0].add(tip[1]).mul(.5);
            stroke(out, edge[0], center, .20);
            stroke(out, center, edge[1], .20);
          }
        }
        // Continue the two borders independently after the physical nose. Never paint a
        // connector across the unpaved gap between the separated carriageways.
        double tail = Math.min(5, available - nose);
        for (double d = nose; d < nose + tail - .01; d += .5) {
          double e = Math.min(nose + tail, d + .5);
          V[] left = goreTail(a, b, d, nose), right = goreTail(a, b, e, nose);
          stroke(out, left[0], right[0], .18);
          stroke(out, left[1], right[1], .18);
        }
  }

  /** Main roads own the dashed boundary along every tangential auxiliary contact.
   * This cannot disappear when ramp UUID order changes or a feeder is split into legs. */
  private static void mainGuides(List<Paint> out, Mesh main, List<Mesh> neighbors) {
    if (main.settings().style() == Style.UNMARKED) return;
    var cuts=new ArrayList<List<V>>();for(var neighbor:neighbors)cuts.addAll(closedGuidance(neighbor,main,neighbors));
    var boundary=new RoadRailJoin(main,neighbors.stream().map(n->new RoadRailJoin.Neighbor(n,true)).toList());
    var rampBoundaries=new IdentityHashMap<Mesh,RoadRailJoin>();
    Route route = new Route(main, 0, 1);
    var auxiliary=LaneRampPaint.auxiliary(main,neighbors);
    for(var ramp:neighbors){
      if(!ramp.settings().style().connectorRamp())continue;
      for(boolean first:new boolean[]{true,false})if(taperClosure(ramp,main,first)){
        taperMarkings(out,ramp,main,first);
        if(!first)out.addAll(LaneRampPaint.arrows(ramp,List.of(main)));
        var pair=measureJoin(ramp,main,first);
        if(pair!=null&&!pair.parallel)goreMarkings(out,pair,true);
      }
    }
    for (double d = 1; d < main.length(); d += 3) {
      Sample a = RoadStructures.sample(main, d);
      Sample b = RoadStructures.sample(main, Math.min(main.length(), d + 1.5));
      Sample mid = RoadStructures.sample(main, Math.min(main.length(), d + .75));
      for (int side : new int[] {-1, 1}) {
        var layout=RoadProfile.layout(main,mid);
        if(layout.cycleWidth()>.01){
          // The motor/cycle boundary, not the far sidewalk edge, is the EXTRA
          // mouth. Keep this guide in the existing single-owner paint pass.
          if(LaneRampPaint.auxiliaryAt(main,a,b,side,auxiliary)
              &&auxiliary.stream().anyMatch(item->item.side()==side
                  &&LaneRampPaint.guideFits(main,a,b,side,item.ramp())
                  &&openGuide(main,a,b,side,item.ramp(),rampBoundaries,neighbors))){
            var strokes=new ArrayList<Paint>();stroke(strokes,a.at(RoadProfile.layout(main,a).outer(side),0),b.at(RoadProfile.layout(main,b).outer(side),0),.15);
            for(var paint:strokes)if(cuts.stream().noneMatch(c->RoadSurface.area(RoadSurface.intersect(paint.points(),c))>1e-8))out.add(paint);
          }
          continue;
        }
        boolean highway=layout.catalog().type()==RoadProfile.Type.HIGHWAY;
        // Test connectivity at the paved shoulder edge; the guide itself stays
        // on the motor/shoulder boundary.
        V edge = mid.at(side * mid.halfWidth(), 0);
        for (Mesh ramp : neighbors) {
          if (!ramp.settings().style().ramp() && ramp.settings().options().lanePoints().link()==null) continue;
          var q = RoadQueries.horizontal(ramp, edge);
          // A lane carried away in its entirety is not an auxiliary lane joining
          // the mainline. Keep its normal exposed edge, not an invented merge guide.
          var link=ramp.settings().options().lanePoints().link();
          if(link!=null){
            if(auxiliary.stream().noneMatch(item->item.ramp()==ramp&&item.side()==side
                &&mid.distance()>=item.from()&&mid.distance()<=item.to()))continue;
          }
          if (Math.abs(q.sample().center().y() - mid.center().y()) > .12
              || q.horizontalDistance() > q.sample().halfWidth() + .12
              || Math.abs(q.sample().left().dot(mid.left())) < .90
              || q.sample().center().sub(mid.center()).dot(mid.left()) * side < 0) continue;
          // Use the motor/shoulder boundary for highways, the paved edge for other roads.
          V other = mid.at(side * (mid.halfWidth() + 2), 0);
          Sample target = new Sample(other, mid.left(), 0, 1);
          V first=a.at(side*(a.halfWidth()-RoadRailJoin.INSET),0),last=b.at(side*(b.halfWidth()-RoadRailJoin.INSET),0);
          // A dash belongs only to a fully open, actually paved weaving interval.
          // Do not extrapolate a clamped ramp endpoint or cut half of its width.
          if(!highway&&!boundary.exposed(first,last,first.add(last).mul(.5).add(mid.left().mul(side))).isEmpty())continue;
          if(!LaneRampPaint.guideFits(main,a,b,side,ramp))continue;
          if(!openGuide(main,a,b,side,ramp,rampBoundaries,neighbors))continue;
          var strokes=new ArrayList<Paint>();stroke(strokes, guideEdge(route, a, target), guideEdge(route, b, target), .15);
          for(var paint:strokes)if(cuts.stream().noneMatch(c->RoadSurface.area(RoadSurface.intersect(paint.points(),c))>1e-8))out.add(paint);
          break;
        }
      }
    }
  }

  /** The auxiliary lane must have an open inner edge along the whole dash.
   * A bicycle underpass removes the host's former flat verge. The approaching
   * ramp can then retain its own inner separator even though its pavement still
   * covers points just outside the motor line. Those points alone do not make
   * a traversable merge. Use the same inset union boundary as the actual rail. */
  private static boolean openGuide(Mesh main,Sample a,Sample b,int side,Mesh ramp,
      Map<Mesh,RoadRailJoin> boundaries,List<Mesh> neighbors){
    var la=RoadProfile.layout(main,a);var lb=RoadProfile.layout(main,b);
    boolean motor=la.cycleWidth()>.01;
    var qa=RoadQueries.horizontal(ramp,a.at(motor?la.outer(side):side*a.halfWidth(),0));
    var qb=RoadQueries.horizontal(ramp,b.at(motor?lb.outer(side):side*b.halfWidth(),0));
    var ra=qa.sample();var rb=qb.sample();int inside=ra.left().dot(a.center().sub(ra.center()))<0?-1:1;
    double ia=RoadRailJoin.inset(ramp,ra,inside),ib=RoadRailJoin.inset(ramp,rb,inside);
    V first=ra.at(inside*(ra.halfWidth()-ia),0),last=rb.at(inside*(rb.halfWidth()-ib),0);
    V outside=first.add(last).mul(.5).add(ra.left().add(rb.left()).horizontalUnit().mul(inside*.4));
    var boundary=boundaries.computeIfAbsent(ramp,r->new RoadRailJoin(r,java.util.stream.Stream.concat(java.util.stream.Stream.of(main),neighbors.stream().filter(n->n!=r)).distinct().map(n->new RoadRailJoin.Neighbor(n,true)).toList()));
    return boundary.exposed(first,last,outside,(ia+ib)/2).isEmpty();
  }

  /** Ramp-to-ramp throats have a dashed continuation, never a triangular exclusion island. */
  private static void rampGuides(List<Paint> out, Join pair) {
    if (twoLaneFork(pair)) {
      twoLaneForkGuides(out, pair);
      return;
    }
    // Single-lane branches keep the existing common dashed continuation.
    double limit=forkLimit(pair);
    for(double d=0;d<limit;d+=.5){double e=Math.min(limit,d+.5);Sample aa=pair.a.at(d),bb=paired(pair.a,pair.b,d),ab=pair.a.at(e),bc=paired(pair.a,pair.b,e);
      boolean dash=((d+e)/2)%6<3;
      int lanesA=RoadProfile.catalog(pair.a.mesh.settings()).lanes(),lanesB=RoadProfile.catalog(pair.b.mesh.settings()).lanes();
      double separation=aa.center().sub(bb.center()).horizontalLength();
      double lane=Math.max(2.8,Math.min(pair.a.mesh.settings().width()/Math.max(1,lanesA),pair.b.mesh.settings().width()/Math.max(1,lanesB))*.8);
      if(d<pair.nose&&dash&&(lanesA>1||lanesB>1||separation>lane))forkStroke(out,median(aa,bb),median(ab,bc),.15,pair);
      if(dash && (separation>=lane*2 || d>=pair.nose)) {
        if(lanesA>1)forkStroke(out,aa.center(),ab.center(),.12,pair);
        if(lanesB>1)forkStroke(out,bb.center(),bc.center(),.12,pair);
      }
    }
  }

  private static boolean twoLaneRamps(Mesh a, Mesh b) {
    return a.settings().style().ramp() && b.settings().style().ramp()
        && RoadProfile.catalog(a.settings()).lanes() == 2
        && RoadProfile.catalog(b.settings()).lanes() == 2;
  }

  private static int meshOrder(Mesh a, Mesh b) {
    for (double[] values : new double[][] {{a.min().x(), b.min().x()}, {a.min().z(), b.min().z()},
        {a.max().x(), b.max().x()}, {a.max().z(), b.max().z()}, {a.length(), b.length()}}) {
      int compare = Double.compare(values[0], values[1]);
      if (compare != 0) return compare;
    }
    return 0;
  }

  private static List<Join> twoLaneForks(Mesh a, Mesh b) {
    if (meshOrder(a, b) > 0) { Mesh swap = a; a = b; b = swap; }
    List<Join> result = new ArrayList<>();
    for (Mesh ramp : List.of(a, b)) for (boolean first : new boolean[] {true, false}) {
      Join candidate = join(ramp, ramp == a ? b : a, first);
      if (candidate == null || candidate.parallel) continue;
      V origin = candidate.a.at(0).center();
      if (result.stream().anyMatch(p -> p.a.at(0).center().distance(origin) < .5
          && p.a.at(1).center().sub(p.a.at(0).center()).dot(
              candidate.a.at(1).center().sub(origin)) > 0)) continue;
      result.add(candidate);
    }
    return result;
  }

  private static boolean twoLaneFork(Join pair) {
    return RoadProfile.catalog(pair.a.mesh.settings()).lanes() == 2
        && RoadProfile.catalog(pair.b.mesh.settings()).lanes() == 2;
  }

  private static double forkLimit(Join pair) {
    if (!twoLaneFork(pair)) return Math.min(pair.available, pair.nose + 7);
    double lane = Math.max(RoadProfile.layout(pair.a.mesh, pair.a.at(pair.nose)).laneWidth(),
        RoadProfile.layout(pair.b.mesh, paired(pair.a, pair.b, pair.nose)).laneWidth());
    return Math.min(pair.available, pair.nose + Math.max(24, lane * 8));
  }

  /** One lane from each branch forms the two-lane common throat. Each inner lane
   * opens gradually beyond the nose; reversing travel gives the same bounded merge. */
  private static void twoLaneForkGuides(List<Paint> out, Join pair) {
    double limit = forkLimit(pair),begin=Math.max(0,pair.nose-28);
    // One shared divider leads into the fork tip. Painting each overlapping
    // ramp independently creates parallel short dashes as their axes separate.
    for(double d=0;d<Math.max(0,begin-3.2)-1e-7;d+=.25){
      double e=Math.min(begin-3.2,d+.25);if(((d+e)/2)%6>=3)continue;
      forkStroke(out,median(pair.a.at(d),paired(pair.a,pair.b,d)),median(pair.a.at(e),paired(pair.a,pair.b,e)),.12,pair);
    }
    
    for(double d=Math.max(0,begin-3.2);d<begin-1e-7;d+=.25){
      double e=Math.min(begin,d+.25);V a=median(pair.a.at(d),paired(pair.a,pair.b,d)),b=median(pair.a.at(e),paired(pair.a,pair.b,e));
      forkStroke(out,a,b,.16,pair);
    }
    for (double d = begin; d < limit - 1e-6; d += .5) {
      double e = Math.min(limit, d + .5);
      V[][] a = forkEdges(pair, d), b = forkEdges(pair, e);
      double width = a[0][0].sub(a[0][1]).horizontalLength()
          + a[1][0].sub(a[1][1]).horizontalLength();
      if (d < pair.nose && width < .16) {
        forkStroke(out, a[0][0].add(a[1][0]).mul(.5),
              b[0][0].add(b[1][0]).mul(.5), .15, pair);
      } else for (int side = 0; side < 2; side++)
        forkStroke(out, a[side][0], b[side][0], .16, pair);
      // The inner lane reopens while the exclusion band tapers toward the edge.
      // Its center divider must resume here, not only after the entire fork zone.
      if (d >= pair.nose) for (int side = 0; side < 2; side++) {
        Route route = side == 0 ? pair.a : pair.b;
        Sample sa = side == 0 ? pair.a.at(d) : paired(pair.a, pair.b, d);
        Sample sb = side == 0 ? pair.a.at(e) : paired(pair.a, pair.b, e);
        V ca = sa.at(RoadProfile.layout(route.mesh, sa).motorCenter(), 0);
        V cb = sb.at(RoadProfile.layout(route.mesh, sb).motorCenter(), 0);
        if (ca.sub(a[side][0]).horizontalLength() > .3
            && cb.sub(b[side][0]).horizontalLength() > .3
            && ((sa.distance() + sb.distance()) / 2) % 6 < 3)
          forkStroke(out, ca, cb, .12, pair);
      }
    }
    for (int side = 0; side < 2; side++)
      for (double d = begin+2; d < limit - 1; d += 4) {
        V[] edge = forkEdges(pair, d)[side];
        double run = Math.min(limit - d, edge[0].sub(edge[1]).horizontalLength());
        if (run < .35) continue;
        // A hatch is one straight diagonal in plan view. Interpolating between
        // moving cross-sections bent it at every change in the paired road frame.
        forkHatch(out,pair,side,d,d+run);
      }
  }

  /** Each pair is [live-lane boundary, inside border of the exclusion band]. */
  private static V[][] forkEdges(Join pair, double d) {
    Sample a = pair.a.at(d), b = paired(pair.a, pair.b, d);
    V middle = median(a, b);
    V[][] edges = new V[2][];
    double open = Settings.smooth(Math.max(0, Math.min(1,
        (d - pair.nose) / Math.max(.5, forkLimit(pair) - pair.nose))));
    for (int i = 0; i < 2; i++) {
      Route route = i == 0 ? pair.a : pair.b;
      Sample at = i == 0 ? a : b, other = i == 0 ? b : a;
      int toward = at.left().dot(other.center().sub(at.center())) >= 0 ? 1 : -1;
      double center = RoadProfile.layout(route.mesh, at).motorCenter();
      double inner = toward * (at.halfWidth() - .3);
      // Within the overlap, partition the band at its common center. Past the
      // physical nose each ramp owns only its own paved strip.
      double common = middle.sub(at.center()).dot(at.left());
      double boundary = center + toward * Math.max(0,
          Math.min((inner - center) * toward, (common - center) * toward));
      V closed = at.at(boundary, 0);
      // Use the very same world-space vertex on both sides of the paved overlap.
      // Projecting it independently onto curved cross-sections leaves a slit in each V.
      if (d < pair.nose && (common - inner) * toward <= 0
          && RoadQueries.contains(pair.a.mesh, middle, .03, .1)
          && RoadQueries.contains(pair.b.mesh, middle, .03, .1)) closed = middle;
      V flow = at.at(center + (boundary - center) * open, 0);
      // The shortened approach must start at one common tip. Simply truncating
      // two existing borders at nose-28 leaves a visibly open pair of stripes.
      if(d<pair.nose){
        double begin=Math.max(0,pair.nose-28);
        double spread=Settings.smooth(Math.max(0,Math.min(1,(d-begin)/Math.min(6,Math.max(.5,pair.nose-begin)))));
        flow=middle.add(flow.sub(middle).mul(spread));
      }
      edges[i] = new V[] {flow, closed};
    }
    return edges;
  }

  private static void forkStroke(List<Paint> out,V a,V b,double width,Join pair){
    // RoadSurface clips the complete stroke to the paved union. Rejecting a
    // whole half-metre stroke because one end corner crosses a curved rim left
    // alternating holes in the roundabout's continuous inner border.
    stroke(out,a,b,width);
  }
  private static void forkHatch(List<Paint> out,Join pair,int side,double from,double to){
    var raw=new ArrayList<Paint>();stroke(raw,forkEdges(pair,from)[side][1],forkEdges(pair,to)[side][0],.18);
    // A rectangular diagonal's end corners otherwise protrude past the curved
    // border at every hatch, making the continuous border appear saw-toothed.
    for(double d=from;d<to-1e-7;d+=.25){
      var a=forkEdges(pair,d)[side];var b=forkEdges(pair,Math.min(to,d+.25))[side];
      var band=List.of(a[0],a[1],b[1],b[0]);
      for(var paint:raw){var clipped=RoadSurface.intersect(paint.points(),band);if(RoadSurface.area(clipped)>1e-9)out.add(new Paint(clipped,paint.color()));}
    }
  }

  private static V taperEdge(Mesh ramp, Mesh host, boolean first, double d, boolean outer) {
    Sample at = RoadStructures.sample(ramp, first ? d : ramp.length() - d);
    Sample hs = RoadQueries.horizontal(host, at.center()).sample();
    if (!outer) return guideEdge(new Route(host, 0, 1), hs, at);
    int toward = at.left().dot(hs.center().sub(at.center())) > 0 ? 1 : -1;
    int outside = hs.left().dot(at.center().sub(hs.center())) >= 0 ? 1 : -1;
    V rampEdge = at.at(ramp.settings().style().connectorRamp()?RoadSurface.edgeOffset(ramp,at,-toward):-toward*(at.halfWidth()-.3),0);
    V mainEdge = hs.at(outside * (hs.halfWidth() - .3), 0);
    // Follow the exposed boundary of the paved union; a narrow ramp footprint may still
    // lie inside the main shoulder, where its own edge would draw a second floating line.
    return rampEdge.sub(mainEdge).dot(hs.left()) * outside > 0 ? rampEdge : mainEdge;
  }

  private static double taperDiagonal(Mesh ramp, Mesh host, boolean first, double inner, double outer) {
    V start = taperEdge(ramp, host, first, inner, false);
    V delta = taperEdge(ramp, host, first, outer, true).sub(start);
    V axis = RoadQueries.horizontal(host, start).tangent().horizontalUnit();
    V away = (first ? ramp.first() : ramp.last()).left().left().mul(first ? -1 : 1);
    if (axis.dot(away) < 0) axis = axis.mul(-1);
    return delta.dot(axis) - Math.abs(delta.dot(axis.left()));
  }

  private static double goreStart(Join pair){
    Route a=pair.a,b=pair.b;double nose=pair.nose;Mesh ramp=a.mesh;
    boolean shoulder = shoulder(b.mesh);
    double begin = nose;
    double search = shoulder ? 1 : Math.max(1, nose - 28);
    if(shoulder && a.at(0).halfWidth()*2<ramp.settings().width()*.94){
      // A highway feeder starts at the existing shoulder's positive width,
      // while an ordinary added lane can start almost at a point. Both widen
      // into a drivable parallel transfer window before peeling away. Looking
      // for a wedge at the initial narrow port closes that whole open window
      // and draws a second outline across the separate terminal taper.
      for(double d=0;d<nose;d+=.5){Sample aa=a.at(d),bb=paired(a,b,d);
        double overlap=aa.halfWidth()+bb.halfWidth()-aa.center().sub(bb.center()).horizontalLength();
        if(aa.halfWidth()*2>=ramp.settings().width()*.999&&overlap>.6&&Math.abs(aa.left().dot(bb.left()))>.9999)search=Math.max(search,d);
      }
    }
    // A full-width loop/branch can fork immediately at the end of a collector.
    // Extend the paint approach onto that paved predecessor; never require extra tails.
    if (a.at(0).halfWidth() * 2 >= ramp.settings().width() * .94)
      search = Math.min(search, nose - 18);
    for (double d = search; d < nose; d += .5) {
      Sample aa = a.at(d), bb = paired(a, b, d);
      double overlap = aa.halfWidth() + bb.halfWidth()
          - aa.center().sub(bb.center()).horizontalLength();
      // The painted wedge begins in the paved overlap, before the physical fork.
      // Waiting for the pavement edges to separate left no room for any chevrons.
      V hostEdge = guideEdge(b, bb, aa), rampEdge = innerEdge(a, aa, bb);
      double opening = hostEdge.sub(rampEdge).dot(separation(aa, bb));
      if (shoulder ? opening > .08 : overlap < 3.5) { begin = d; break; }
    }
    if(highwayExtra(ramp,b.mesh,a.sign>0)){
      var link=ramp.settings().options().lanePoints().link();
      begin=Math.min(nose,Math.max(begin,LaneRampApproach.length(b.mesh,link.options().transition())));
    }
    return begin;
  }
  /** The same closed wedge used by the solid borders; never erase a live adjacent lane. */
  public static List<List<V>> closedGuidance(Mesh ramp,Mesh host){
    return closedGuidance(ramp,host,List.of());
  }
  /** Closed paint must use the same complete paved union as the open guide.
   * EXTRA can approach across a predecessor before reaching its named host. */
  public static List<List<V>> closedGuidance(Mesh ramp,Mesh host,List<Mesh> neighbors){
    var out=new ArrayList<List<V>>();
    if(!ramp.settings().style().ramp()||host.settings().style().ramp())return out;
    if(ramp.settings().style().connectorRamp())out.addAll(connectorClosedGuidance(ramp,host,neighbors));
    for(boolean first:new boolean[]{true,false}){
      if(taperClosure(ramp,host,first)){
        double reach=closureLength(ramp,host,first),lo=0,hi=reach;
        for(int i=0;i<24;i++){double mid=(lo+hi)/2;if(taperDiagonal(ramp,host,first,mid,reach)>0)lo=mid;else hi=mid;}
        double inner=(lo+hi)/2;
        for(double d=0;d<inner-1e-6;d+=.5){double e=Math.min(inner,d+.5);
          out.add(List.of(taperEdge(ramp,host,first,d,false),taperEdge(ramp,host,first,d,true),taperEdge(ramp,host,first,e,true),taperEdge(ramp,host,first,e,false)));
        }
        V tip=taperEdge(ramp,host,first,inner,false);
        for(double d=inner;d<reach-1e-6;d+=.5)
          out.add(List.of(tip,taperEdge(ramp,host,first,d,true),taperEdge(ramp,host,first,Math.min(reach,d+.5),true)));
      }
      var pair=highwayExtra(ramp,host,first)?measureJoin(ramp,host,first):join(ramp,host,first);
      if(pair==null||pair.parallel||parallelFeeder(ramp,host)||!goreRequired(pair))continue;
      double begin=goreStart(pair);
      for(double d=begin;d<pair.nose-1e-6;d+=.5){var a=gore(pair.a,pair.b,d,begin,pair.nose);var b=gore(pair.a,pair.b,Math.min(pair.nose,d+.5),begin,pair.nose);
        out.add(List.of(a[0],a[1],b[1],b[0]));}
    }
    var expanded=new ArrayList<List<V>>(out);
    // Borders have width too. Include that width so half of the old dash cannot
    // survive on the closed region's exact edge underneath a new solid stripe.
    for(var polygon:out)for(int i=0;i<polygon.size();i++){var strokes=new ArrayList<Paint>();stroke(strokes,polygon.get(i),polygon.get((i+1)%polygon.size()),.24);for(var stroke:strokes)expanded.add(stroke.points());}
    return List.copyOf(expanded);
  }
  /** A lane connector has no legacy gore descriptor. Its two exposed solid
   * borders delimit the closed shoulder strip; weaving portions have no such pair. */
  private static List<List<V>> connectorClosedGuidance(Mesh ramp,Mesh host,List<Mesh> neighbors){
    if(!LaneMerge.linkedTo(host,ramp))return List.of();
    var context=java.util.stream.Stream.concat(java.util.stream.Stream.of(ramp,host),neighbors.stream()).distinct().toList();
    var ours=RoadRailJoin.paint(ramp,context.stream().filter(m->m!=ramp).map(m->new RoadRailJoin.Neighbor(m,true)).toList());
    var theirs=RoadRailJoin.paint(host,context.stream().filter(m->m!=host).map(m->new RoadRailJoin.Neighbor(m,true)).toList());
    var out=new ArrayList<List<V>>();
    for(double d=0;d<ramp.length()-1e-7;d+=.5){
      var a=RoadStructures.sample(ramp,d);var b=RoadStructures.sample(ramp,Math.min(ramp.length(),d+.5));
      var qa=RoadQueries.horizontal(host,a.center());var qb=RoadQueries.horizontal(host,b.center());var ha=qa.sample();var hb=qb.sample();
      if(Math.abs(a.center().y()-ha.center().y())>.12||Math.abs(b.center().y()-hb.center().y())>.12
          ||qa.horizontalDistance()>a.halfWidth()+ha.halfWidth()+2||Math.abs(a.left().dot(ha.left()))<.9)continue;
      int side=a.left().dot(ha.center().sub(a.center()))>0?1:-1,hostSide=qa.lateral()>0?1:-1;
      V ra=a.at(RoadSurface.edgeOffset(ramp,a,side),0),rb=b.at(RoadSurface.edgeOffset(ramp,b,side),0);
      V pa=ha.at(RoadSurface.edgeOffset(host,ha,hostSide),0),pb=hb.at(RoadSurface.edgeOffset(host,hb,hostSide),0);
      if(ra.sub(pa).horizontalLength()>2.5||rb.sub(pb).horizontalLength()>2.5)continue;
      double ri=(a.halfWidth()-Math.abs(RoadSurface.edgeOffset(ramp,a,side))+b.halfWidth()-Math.abs(RoadSurface.edgeOffset(ramp,b,side)))/2;
      double hi=(ha.halfWidth()-Math.abs(RoadSurface.edgeOffset(host,ha,hostSide))+hb.halfWidth()-Math.abs(RoadSurface.edgeOffset(host,hb,hostSide)))/2;
      var rs=ours.exposed(ra,rb,ra.add(rb).mul(.5).add(a.left().mul(side)),ri);
      var hs=theirs.exposed(pa,pb,pa.add(pb).mul(.5).add(ha.left().mul(hostSide)),hi);
      V r=rb.sub(ra),h=pb.sub(pa);double rl=r.dot(r),hl=h.dot(h);if(rl<1e-10||hl<1e-10)continue;
      for(var one:rs)for(var two:hs){
        double lo=Math.max(one.a().sub(ra).dot(r)/rl,two.a().sub(pa).dot(h)/hl);
        double end=Math.min(one.b().sub(ra).dot(r)/rl,two.b().sub(pa).dot(h)/hl);
        if(end>lo+1e-7)out.add(List.of(pa.add(h.mul(lo)),ra.add(r.mul(lo)),ra.add(r.mul(end)),pa.add(h.mul(end))));
      }
    }
    return List.copyOf(out);
  }

  /** Legacy interchange auxiliary tapers need the same union boundary as authored lane links. */
  public static double[] auxiliaryRange(Mesh ramp,Mesh host){
    if(ramp==null||host==null||!ramp.settings().style().ramp()||host.settings().style().ramp())return null;
    double from=Double.POSITIVE_INFINITY,to=Double.NEGATIVE_INFINITY;
    for(boolean first:new boolean[]{true,false}){
      var pair=join(ramp,host,first);if(pair==null)continue;
      double end=pair.parallel?pair.available:Math.max(0,pair.nose-2*RoadRailJoin.INSET);
      // Stop at the inset nose so the two exposed tips remain connected.
      from=Math.min(from,first?0:ramp.length()-end);to=Math.max(to,first?end:ramp.length());
    }
    return Double.isFinite(from)?new double[]{from,to}:null;
  }

  private static void taperMarkings(List<Paint> out, Mesh ramp, Mesh host) {
    for (boolean first : new boolean[] {true, false}) {
      if (taperClosure(ramp, host, first))taperMarkings(out,ramp,host,first);
    }
  }
  private static void taperMarkings(List<Paint> out,Mesh ramp,Mesh host,boolean first){
      double reach = closureLength(ramp,host,first);
      // Red-line reference: inner end is nearer the narrow terminal, outer end
      // farther along the widening auxiliary road. Mirror in station space at exits.
      double lo = 0, hi = reach;
      for (int step = 0; step < 24; step++) {
        double mid = (lo + hi) / 2;
        if (taperDiagonal(ramp, host, first, mid, reach) > 0) lo = mid; else hi = mid;
      }
      double innerEnd = (lo + hi) / 2;
      for (boolean outer : new boolean[] {false, true}) {
        double limit = outer ? reach : innerEnd;
        for (double d = 0; d < limit - 1e-6; d += .5)
          stroke(out, taperEdge(ramp, host, first, d, outer),
              taperEdge(ramp, host, first, Math.min(limit, d + .5), outer), .16);
      }
      double spacing=(first?ramp.first():ramp.last()).halfWidth()<.5?2.5:4;
      for (double d = 1; d < innerEnd - 2; d += spacing) {
        double from = d, to = reach;
        // The outer boundary widens between endpoints: solve the actual diagonal,
        // rather than using its width at the inner station (which makes steep bars).
        for (int step = 0; step < 24; step++) {
          double mid = (from + to) / 2;
          if (taperDiagonal(ramp, host, first, d, mid) < 0) from = mid; else to = mid;
        }
        double outer = (from + to) / 2;
        if (outer - d > .4)
          stroke(out, taperEdge(ramp, host, first, d, false),
              taperEdge(ramp, host, first, outer, true), .18);
      }
      stroke(out, taperEdge(ramp, host, first, innerEnd, false),
          taperEdge(ramp, host, first, reach, true), .18);
  }

  private static V[] goreTail(Route a, Route b, double d, double nose) {
    V[] edge = borders(a, b, d);
    Sample aa = a.at(d), bb = paired(a, b, d);
    if (shoulder(b.mesh)) return new V[] {edge[0], guideEdge(b, bb, aa)};
    V side = separation(aa, bb);
    double half = Math.min(1.1, Math.min(aa.halfWidth(), bb.halfWidth()) * .3);
    double extra = Math.max(0, half - .3) * Math.max(0, 1 - (d - nose) / 5);
    return new V[] {edge[0].sub(side.mul(extra)), edge[1].add(side.mul(extra))};
  }

  private static List<Paint> terminalMarkings(Mesh mesh) {
    List<Paint> out = new ArrayList<>();
    for (double d = .5; d <= mesh.length(); d += .5) {
      Sample a = RoadStructures.sample(mesh, d - .5), b = RoadStructures.sample(mesh, d);
      double ia = RoadTransitions.dropBoundary(mesh, a), ib = RoadTransitions.dropBoundary(mesh, b);
      if (!Double.isFinite(ia) || !Double.isFinite(ib)) continue;
      for (int side : new int[] {-1, 1}) {
        double oa = Math.abs(RoadProfile.layout(mesh, a).outer(side)) - .2;
        double ob = Math.abs(RoadProfile.layout(mesh, b).outer(side)) - .2;
        if (oa - ia < .1 || ob - ib < .1) continue;
        stroke(out, a.at(side * ia, 0), b.at(side * ib, 0), .15);
        if (((int)Math.round(d * 2)) % 8 == 0) {
          Sample tip = RoadStructures.sample(mesh, Math.min(mesh.length(), d + 1.3));
          double inner = RoadTransitions.dropBoundary(mesh, tip);
          if (Double.isFinite(inner)) stroke(out, b.at(side * ob, 0), tip.at(side * inner, 0), .18);
        }
      }
    }
    return out;
  }

  /** Exact painted closure polygons: clip full dash faces, including those crossing a taper. */
  public static List<List<V>> terminalCuts(Mesh mesh) {
    List<List<V>> out = new ArrayList<>();
    if (!mesh.settings().options().ends().persistent()) return out;
    for (double d = .25; d <= mesh.length() + .249; d += .25) {
      Sample a = RoadStructures.sample(mesh, Math.max(0, d - .25));
      Sample b = RoadStructures.sample(mesh, Math.min(d, mesh.length()));
      double ia = RoadTransitions.dropBoundary(mesh, a), ib = RoadTransitions.dropBoundary(mesh, b);
      if (!Double.isFinite(ib)) continue;
      if (!Double.isFinite(ia)) ia = Math.abs(RoadProfile.layout(mesh, a).outer(1));
      for (int side : new int[] {-1, 1})
        out.add(List.of(a.at(side * (ia - .15), 0), a.at(side * (a.halfWidth() + 1), 0),
            b.at(side * (b.halfWidth() + 1), 0), b.at(side * (ib - .15), 0)));
    }
    return out;
  }

  private static V separation(Sample a, Sample b) {
    V d = b.center().sub(a.center());
    return d.horizontalLength() < 1e-5 ? a.left() : d.horizontalUnit();
  }

  private static V median(Sample a, Sample b) {
    return a.center()
        .add(b.center())
        .mul(.5)
        .add(separation(a, b).mul((a.halfWidth() - b.halfWidth()) / 2));
  }

  private static V guideEdge(Route route, Sample at, Sample other) {
    var layout = RoadProfile.layout(route.mesh, at);
    int side = at.left().dot(other.center().sub(at.center())) >= 0 ? 1 : -1;
    if (layout.catalog().type() == RoadProfile.Type.HIGHWAY)
      return at.at(layout.outer(side), 0);
    return innerEdge(route, at, other);
  }

  private static V innerEdge(Route route, Sample at, Sample other) {
    int side = at.left().dot(other.center().sub(at.center())) >= 0 ? 1 : -1;
    double offset = route.mesh.settings().style().connectorRamp()?RoadSurface.edgeOffset(route.mesh,at,side):side*(at.halfWidth()-.3);
    // The physical fork is between pavement edges, not between the motor lane and shoulder.
    return at.at(offset, 0);
  }

  private static Sample paired(Route a, Route b, double distance) {
    V point=a.at(distance).center();var nearest=RoadQueries.horizontal(b.mesh,point).sample();
    if(!twoLaneRamps(a.mesh,b.mesh))return nearest;
    // A nearest point on a polygonal ring jumps between chord normals inside
    // a bend. Solve against its interpolated road frame instead, so the fork
    // border and every hatch share a continuous cross-section.
    double station=nearest.distance();
    for(int i=0;i<8;i++){
      var at=pairedSample(b.mesh,station);
      double f=point.sub(at.center()).dot(at.left().left().mul(-1));
      if(Math.abs(f)<1e-7)break;
      double lo=b.mesh.closed()?station-.1:Math.max(0,station-.1),hi=b.mesh.closed()?station+.1:Math.min(b.mesh.length(),station+.1);
      var al=pairedSample(b.mesh,lo);var ah=pairedSample(b.mesh,hi);
      double derivative=(point.sub(ah.center()).dot(ah.left().left().mul(-1))-point.sub(al.center()).dot(al.left().left().mul(-1)))/(hi-lo);
      if(Math.abs(derivative)<.15)break;
      double lower=b.mesh.closed()?nearest.distance()-4:Math.max(0,nearest.distance()-4),upper=b.mesh.closed()?nearest.distance()+4:Math.min(b.mesh.length(),nearest.distance()+4);
      station=Math.max(lower,Math.min(upper,station-Math.max(-1,Math.min(1,f/derivative))));
    }
    return pairedSample(b.mesh,station);
  }
  private static Sample pairedSample(Mesh mesh,double station){
    if(mesh.closed())station=((station%mesh.length())+mesh.length())%mesh.length();
    return RoadStructures.sample(mesh,station);
  }

  private static V[] borders(Route a, Route b, double d) {
    Sample aa = a.at(d), bb = paired(a, b, d);
    return new V[] {innerEdge(a, aa, bb), innerEdge(b, bb, aa)};
  }

  private static boolean shoulder(Mesh mesh) {
    return RoadProfile.layout(mesh, mesh.first()).shoulderWidth() > .1;
  }

  private static boolean goreRequired(Join join) {
    if (join.b.mesh.settings().style().ramp()) return false;
    if (shoulder(join.b.mesh)) return true;
    for (double d = 0; d <= join.nose; d += 2)
      if (Double.isFinite(RoadTransitions.dropBoundary(join.b.mesh, paired(join.a, join.b, d))))
        return true;
    return false;
  }

  private static V[] gore(Route a, Route b, double d, double start, double end) {
    Sample aa = a.at(d), bb = paired(a, b, d);
    if (shoulder(b.mesh)) {
      V host = guideEdge(b, bb, aa), ramp = innerEdge(a, aa, bb);
      double opening = host.sub(ramp).dot(separation(aa, bb));
      if (opening < 0) ramp = host;
      double blend = Settings.smooth(Math.max(0, Math.min(1, (d - start) / Math.min(6, end - start))));
      // Anchor one border to the main motor/shoulder line and the other to the ramp's
      // inner stripe. Never center a constant-width wedge inside a driving lane.
      return new V[] {host.add(ramp.sub(host).mul(blend)), host};
    }
    V center = median(aa, bb), side = separation(aa, bb);
    double half = Math.min(1.1, Math.min(aa.halfWidth(), bb.halfWidth()) * .3);
    half *= Settings.smooth(Math.max(0, Math.min(1, (d - start) / (end - start))));
    return new V[] {center.sub(side.mul(half)), center.add(side.mul(half))};
  }

  private static void arrow(List<Paint> out, V p, V f, V left) {
    stroke(out, p.sub(f.mul(1.4)), p.add(f.mul(.2)), .22);
    out.add(
        new Paint(
            List.of(
                p.add(f.mul(1.5)),
                p.add(f.mul(.2)).add(left.mul(.65)),
                p.add(f.mul(.2)).sub(left.mul(.65))),
            0xEDEEE2));
  }

  private static void stroke(List<Paint> out, V a, V b, double width) {
    V d = b.sub(a);
    if (d.horizontalLength() < 1e-6) return;
    V side = d.horizontalUnit().left().mul(width / 2);
    out.add(new Paint(List.of(a.add(side), a.sub(side), b.sub(side), b.add(side)), 0xEDEEE2));
  }

  private RoadJunction() {}
}
