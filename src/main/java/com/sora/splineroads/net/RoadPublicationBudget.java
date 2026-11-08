package com.sora.splineroads.net;

import java.util.function.LongSupplier;

/** One persistent cursor across bounded publication calls. Work owns no world state here. */
final class RoadPublicationBudget {
  static final int FAMILIES = 4, MAX_VISITS = 64, MAX_BYTES = 262144;
  @FunctionalInterface interface Publisher {
    /** Publish at most one fragment, or advance one bounded preparation step. */
    int step(int family, int remainingBytes);
  }
  private final LongSupplier clock;
  private int nextFamily;
  RoadPublicationBudget() { this(System::nanoTime); }
  RoadPublicationBudget(LongSupplier clock) { this.clock = clock; }

  int publish(long deadline, Publisher publisher) {
    int bytes = 0;
    for (int visits = 0; visits < MAX_VISITS && bytes < MAX_BYTES && clock.getAsLong() < deadline; visits++) {
      int family = nextFamily;
      // An expensive first step must not make the same family first again next tick.
      nextFamily = (family + 1) % FAMILIES;
      int sent = publisher.step(family, MAX_BYTES - bytes);
      if (sent < 0 || sent > MAX_BYTES - bytes) throw new IllegalStateException("Invalid road publication accounting");
      bytes += sent;
    }
    return bytes;
  }
}
