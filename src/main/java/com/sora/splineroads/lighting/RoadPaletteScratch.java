package com.sora.splineroads.lighting;

import java.util.Arrays;

/** Temporary, thread-confined unpack buffers; never retained by packed chunk data. */
public final class RoadPaletteScratch {
    private static final ThreadLocal<Buffers> LOCAL = ThreadLocal.withInitial(Buffers::new);
    private static final class Buffers {
        int[] blocks;
        int[] biomes;
    }

    public static int[] borrow(int size) {
        if (size != 4096 && size != 64) return new int[size];
        Buffers buffers = LOCAL.get();
        int[] result;
        if (size == 4096) { result = buffers.blocks; buffers.blocks = null; }
        else { result = buffers.biomes; buffers.biomes = null; }
        // Removing the borrowed buffer also makes nested calls independent.
        if (result == null) return new int[size];
        // Preserve new int[] semantics even for another mod's BitStorage.
        Arrays.fill(result, 0);
        return result;
    }

    public static void recycle(int[] array) {
        if (array == null) return;
        if (array.length == 4096) LOCAL.get().blocks = array;
        else if (array.length == 64) LOCAL.get().biomes = array;
    }

    private RoadPaletteScratch() {}
}
