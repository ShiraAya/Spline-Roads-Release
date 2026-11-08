package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadTerrainMesh.Section;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.Predicate;

/** Identity ledger for a terrain section. CPU publication and an actual resident
 * upload are deliberately different states. Render adapters commit only after
 * their complete GPU replacement has succeeded on the render thread. */
public final class RoadHandoffState<S> {
  /** Identity equality is intentional: hashing a source must not walk thousands
   * of immutable model cells during a client-thread batch or GPU commit. */
  public static final class Source<S> {
    private final long revision;private final S data;private final Set<UUID> owners,replaces;private final boolean empty;
    Source(long revision,S data,Set<UUID> owners,Set<UUID> replaces,boolean empty){this.revision=revision;this.data=data;this.owners=owners;this.replaces=replaces;this.empty=empty;}
    public long revision(){return revision;}public S data(){return data;}public Set<UUID> owners(){return owners;}
  }
  public static final class Ticket<S> {
    public final Section section;
    public final Source<S> source;
    public final boolean terrain,staged;
    private final long generation,mode;
    private final WeakReference<Object> owner;
    private Ticket(Section section,Source<S> source,boolean terrain,boolean staged,long generation,long mode,Object owner){
      this.section=section;this.source=source;this.terrain=terrain;this.staged=staged;
      this.generation=generation;this.mode=mode;this.owner=new WeakReference<>(owner);
    }
    public Object owner(){return owner.get();}
    public Set<UUID> owners(){return terrain&&source!=null?source.owners():Set.of();}
  }
  public record Resident(Set<UUID> owners,Set<UUID> vboOwners,WeakReference<Object> owner,Object upload,long generation,Object source) {}
  private final Map<Section,Source<S>> sources=new HashMap<>();
  private final Map<Section,Resident> resident=new HashMap<>();
  private record OwnerGeneration(Section section,long generation){}
  private final WeakHashMap<Object,OwnerGeneration> generations=new WeakHashMap<>();
  private record SectionGeneration(WeakReference<Object> owner,long generation,boolean pending){}
  private final Map<Section,SectionGeneration> sectionGenerations=new HashMap<>();
  private final Predicate<? super S> emptySource;
  private long serial,revision,mode;
  private volatile long ownershipRevision;
  private boolean terrain;
  private long accepted,rejected;

  /** Generic callers retain publications unless they can identify empty data. */
  public RoadHandoffState(){this(data->false);}
  public RoadHandoffState(Predicate<? super S> emptySource){this.emptySource=Objects.requireNonNull(emptySource);}

  public synchronized void publish(Section section,S data,Set<UUID> owners){
    publish(section,data,owners,Set.of());
  }
  /** A coherent split/merge section replaces old VBO ranges whose UUID can
   * differ from the physical faces in this particular replacement section. */
  public synchronized void publish(Section section,S data,Set<UUID> owners,Set<UUID> replaces){
    sources.put(section,new Source<>(++revision,data,Set.copyOf(owners),Set.copyOf(replaces),owners.isEmpty()&&emptySource.test(data)));
    pruneEmpty(section);
  }
  public synchronized void mode(boolean next){if(terrain!=next){terrain=next;mode++;}}
  public synchronized Ticket<S> begin(Object owner,Section section){
    long generation=++serial;var previous=generations.put(owner,new OwnerGeneration(section,generation));
    if(previous!=null&&!previous.section().equals(section))removeOwnerSection(owner,previous.section());
    sectionGenerations.put(section,new SectionGeneration(new WeakReference<>(owner),generation,true));var source=sources.get(section);
    return new Ticket<>(section,source,terrain,terrain&&source!=null&&!source.owners().isEmpty()||resident.containsKey(section),generation,mode,owner);
  }
  public synchronized boolean current(Ticket<S> ticket){
    Object owner=ticket.owner();var section=sectionGenerations.get(ticket.section);var generation=generations.get(owner);return owner!=null&&ticket.mode==mode
        &&generation!=null&&generation.generation()==ticket.generation&&section!=null&&section.generation()==ticket.generation
        &&section.owner().get()==owner&&sources.get(ticket.section)==ticket.source;
  }
  /** The caller's upload identity must name the real newly installed allocation. */
  public synchronized boolean commit(Ticket<S> ticket,Object upload){
    if(!current(ticket)){rejected++;return false;}
    if(!ticket.owners().isEmpty()){
      var covered=new HashSet<>(ticket.owners());covered.addAll(ticket.source.replaces);
      resident.put(ticket.section,new Resident(ticket.owners(),Set.copyOf(covered),ticket.owner,Objects.requireNonNull(upload),ticket.generation,ticket.source));
      ownershipRevision++;
    }
    else if(resident.remove(ticket.section)!=null)ownershipRevision++;
    accepted++;return true;
  }
  public synchronized Resident resident(Section section){return resident.get(section);}
  public synchronized Source<S> source(Section section){return sources.get(section);}
  public synchronized void release(Object owner){
    var previous=generations.remove(owner);if(previous!=null)removeOwnerSection(owner,previous.section());
  }
  private void removeOwnerSection(Object owner,Section section){
    var allocation=resident.get(section);
    if(allocation!=null&&allocation.owner().get()==owner){resident.remove(section);ownershipRevision++;}
    var generation=sectionGenerations.get(section);
    if(generation!=null&&generation.owner().get()==owner)sectionGenerations.remove(section);
    pruneEmpty(section);
  }
  /** Completed ordinary world builds do not need a lifetime-long road ledger.
   * Keep entries only for in-flight tasks or an actual resident road upload. */
  public synchronized void finish(Ticket<S> ticket){
    Object owner=ticket.owner();if(owner==null){pruneEmpty(ticket.section);return;}
    var generation=generations.get(owner);var allocation=resident.get(ticket.section);
    if(generation!=null&&generation.generation()==ticket.generation){
      var current=sectionGenerations.get(ticket.section);
      if(allocation==null||allocation.owner().get()!=owner){
        generations.remove(owner);
        if(current!=null&&current.generation()==ticket.generation&&current.owner().get()==owner)sectionGenerations.remove(ticket.section);
      }else if(current!=null&&current.generation()==ticket.generation&&current.owner().get()==owner){
        sectionGenerations.put(ticket.section,new SectionGeneration(current.owner(),current.generation(),false));
      }
    }
    pruneEmpty(ticket.section);
  }
  /** An empty publication is a tombstone while a native task or visible upload
   * still needs its identity. Once both are gone, keeping the section forever
   * cannot protect a ticket: its generation has already been retired. */
  private void pruneEmpty(Section section){
    var source=sources.get(section);if(source==null||!source.empty||resident.containsKey(section))return;
    var sectionGeneration=sectionGenerations.get(section);
    if(sectionGeneration!=null){
      Object owner=sectionGeneration.owner().get();var generation=generations.get(owner);
      boolean current=owner!=null&&generation!=null&&generation.generation()==sectionGeneration.generation()&&generation.section().equals(section);
      if(current&&sectionGeneration.pending())return;
      sectionGenerations.remove(section);
      if(current)generations.remove(owner);
    }
    sources.remove(section,source);
  }
  public long ownershipRevision(){return ownershipRevision;}
  public synchronized void invalidAllocation(Section section,Resident expected){
    if(resident.get(section)==expected){resident.remove(section);ownershipRevision++;pruneEmpty(section);}
  }
  public synchronized void invalidate(Section section){sources.remove(section);revision++;}
  /** In-level recovery still has old visible buffers until their replacement. */
  public synchronized void clearSources(){sources.clear();mode++;}
  /** A level/renderer disposal destroys the allocation and all prior task identities. */
  public synchronized void reset(){sources.clear();resident.clear();generations.clear();sectionGenerations.clear();mode++;ownershipRevision++;}
  public synchronized int residentCount(){return resident.size();}
  public synchronized long accepted(){return accepted;}
  public synchronized long rejected(){return rejected;}
}
