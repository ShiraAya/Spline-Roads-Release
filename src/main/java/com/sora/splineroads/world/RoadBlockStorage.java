package com.sora.splineroads.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.state.BlockState;

/** Lossless restoration palette. One NBT state per type, eight bytes per position. */
final class RoadBlockStorage {
  private static final class Group {
    int count, cursor;
    long[] positions;
  }

  static ListTag write(Long2ObjectOpenHashMap<BlockState> blocks) {
    Map<BlockState, Group> groups = new IdentityHashMap<>();
    var entries = blocks.long2ObjectEntrySet().fastIterator();
    while (entries.hasNext()) groups.computeIfAbsent(entries.next().getValue(), s -> new Group()).count++;
    for (var group : groups.values()) group.positions = new long[group.count];
    entries = blocks.long2ObjectEntrySet().fastIterator();
    while (entries.hasNext()) {
      var entry = entries.next();
      var group = groups.get(entry.getValue());
      group.positions[group.cursor++] = entry.getLongKey();
    }
    ListTag result = new ListTag();
    // Stable palette order also makes unchanged saves comparable without sorting millions of positions.
    groups.entrySet().stream().sorted(java.util.Comparator.comparing(e -> e.getKey().toString())).forEach(e -> {
      CompoundTag tag = new CompoundTag();
      tag.put("State", NbtUtils.writeBlockState(e.getKey()));
      tag.putLongArray("Positions", e.getValue().positions);
      result.add(tag);
    });
    return result;
  }

  static void read(ListTag palette, Long2ObjectOpenHashMap<BlockState> blocks) {
    long count = 0;
    for (Tag value : palette) count += ((CompoundTag)value).getLongArray("Positions").length;
    if (count > Integer.MAX_VALUE - 8) throw new IllegalStateException("Too many road restoration blocks");
    for (Tag value : palette) {
      CompoundTag tag = (CompoundTag)value;
      if (!tag.contains("State", Tag.TAG_COMPOUND) || !tag.contains("Positions", Tag.TAG_LONG_ARRAY))
        throw new IllegalStateException("Incomplete road restoration palette");
      BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound("State"));
      for (long pos : tag.getLongArray("Positions"))
        if (blocks.put(pos, state) != null) throw new IllegalStateException("Duplicate road restoration position");
    }
  }

  private RoadBlockStorage() {}
}
