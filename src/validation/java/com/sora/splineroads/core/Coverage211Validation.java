package com.sora.splineroads.core;
import java.util.*;
/** Compare lifecycle and immutable coverage against the preserved original algorithm. */
public final class Coverage211Validation {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static Map<Integer,Set<UUID>> original(Map<Integer,Set<UUID>> previous,ReferenceHandoffCohorts<Integer> cohorts,Map<UUID,List<Integer>> transitions){
  var out=new HashMap<>(previous);
  for(var key:cohorts.keys())out.merge(key,cohorts.members(key),(a,b)->{var ids=new HashSet<>(a);ids.addAll(b);return Set.copyOf(ids);});
  transitions.forEach((road,sections)->{for(var key:sections){var ids=new HashSet<>(out.getOrDefault(key,Set.of()));ids.add(road);out.put(key,Set.copyOf(ids));}});
  return out;
 }
 public static void main(String[] args){
  var rng=new Random(2111116);var ids=new ArrayList<UUID>();for(int i=0;i<64;i++)ids.add(new UUID(0,i+1));
  var reference=new ReferenceHandoffCohorts<Integer>();var actual=new RoadHandoffCohorts<Integer>();
  Map<Integer,Set<UUID>> coverage=new HashMap<>();int checks=0;
  for(int step=0;step<1200;step++){
   var members=new ArrayList<UUID>();for(int i=0;i<1+rng.nextInt(8);i++)members.add(ids.get(rng.nextInt(ids.size())));
   var keys=new ArrayList<Integer>();for(int i=0;i<1+rng.nextInt(7);i++)keys.add(rng.nextInt(32)-16);
   if(step%17==0){reference.clear();actual.clear();coverage.clear();}
   switch(rng.nextInt(4)){
    case 0 -> check(reference.begin(members,keys).equals(actual.begin(members,keys)),"begin");
    case 1 -> {reference.include(members.get(0),keys.get(0));actual.include(members.get(0),keys.get(0));}
    case 2 -> {reference.installed(members);actual.installed(members);}
    case 3 -> {int salt=rng.nextInt(5);check(reference.ready(keys.get(0),(id,key)->(id.getLeastSignificantBits()+key+salt)%3!=0)==actual.ready(keys.get(0),(id,key)->(id.getLeastSignificantBits()+key+salt)%3!=0),"ready");}
   }
   check(reference.keys().equals(actual.keys())&&reference.members().equals(actual.members())&&reference.size()==actual.size(),"lifecycle");
   for(int key=-16;key<16;key++){check(reference.members(key).equals(actual.members(key)),"members per tile");checks++;}
   var transitions=new LinkedHashMap<UUID,List<Integer>>();for(var id:members)transitions.put(id,keys);
   var retained=new HashMap<>(coverage);var immutableCopy=new HashMap<Integer,Set<UUID>>();retained.forEach((key,value)->immutableCopy.put(key,Set.copyOf(value)));
   var expected=original(coverage,reference,transitions);actual.rememberCoverage(coverage,transitions,k->k);
   check(coverage.equals(expected),"coverage union");check(retained.equals(immutableCopy),"mutated previous publication");
   for(var value:coverage.values()){try{value.add(new UUID(-1,-1));throw new AssertionError("mutable publication");}catch(UnsupportedOperationException correct){}}
   checks+=3;
  }
  var largeRef=new ReferenceHandoffCohorts<Integer>();var large=new RoadHandoffCohorts<Integer>();var owners=new ArrayList<UUID>();var keys=new ArrayList<Integer>();
  for(int i=0;i<400;i++)owners.add(new UUID(1,i));for(int i=0;i<128;i++)keys.add(i);
  largeRef.begin(owners,keys);large.begin(owners,keys);var transitions=new HashMap<UUID,List<Integer>>();owners.forEach(id->transitions.put(id,keys));
  long start=System.nanoTime();var expected=original(Map.of(),largeRef,transitions);double before=(System.nanoTime()-start)/1e6;
  var out=new HashMap<Integer,Set<UUID>>();start=System.nanoTime();large.rememberCoverage(out,transitions,k->k);double after=(System.nanoTime()-start)/1e6;
  check(expected.equals(out),"large overlapping batch");
  System.out.println("COVERAGE211 PASS checks="+checks+" immutable_snapshots=true large_owners=400 tiles=128 diagnostic_ms_original="+before+" optimized="+after);
 }
}
