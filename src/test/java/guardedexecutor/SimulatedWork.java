/*
 * Copyright 2019 by Justin T. Sampson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package guardedexecutor;

/**
 * An object that simulates work by repeatedly computing pseudo-random numbers. The constants and
 * formulas are all borrowed from {@link java.util.Random}, but are reimplemented here to eliminate
 * synchronization overhead. Implementing pseudo-random number generation and storing the seed in a
 * field should prevent this code from being optimized away. The {@link #doWork(long)} method adds
 * a pseudo-random offset to the target reps (using the same pseudo-random number generator) to make
 * the work more realistic.
 */
public final class SimulatedWork {

  private static final long multiplier = 0x5DEECE66DL;
  private static final long addend = 0xBL;
  private static final long mask = (1L << 48) - 1;

  private long seed;

  public SimulatedWork(long initialSeed) {
    seed = (initialSeed ^ multiplier) & mask;
  }

  public long doWork(final long targetReps) {
    final long range = Long.highestOneBit(targetReps);
    final long halfRange = range >> 1;
    long seed = (this.seed * multiplier + addend) & mask;
    final long randomBits = seed >>> 17;
    final long offset = (range * randomBits) >> 31;
    final long actualReps = targetReps + offset - halfRange;
    for (long i = 0; i < actualReps; i++) {
      seed = (seed * multiplier + addend) & mask;
    }
    this.seed = seed;
    return actualReps;
  }

  public long getSeed() {
    return seed;
  }

}
