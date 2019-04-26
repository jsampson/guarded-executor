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

import static guardedexecutor.ConcurrentPerfSubjectImpl.*;

public final class ConcurrentPerfOptions {

  // Recommended VM options: -Xcomp -Xbatch -XX:-UseBiasedLocking -XX:+AggressiveOpts

  private ConcurrentPerfOptions() {}

  /** The subjects to include in the performance test. */
  public static final ConcurrentPerfSubjectImpl[] SUBJECTS = {
      SYNCHRONIZED,
      REENTRANT_LOCK,
      REENTRANT_LOCK_FAIR,
      GUAVA_MONITOR,
      GUAVA_MONITOR_FAIR,
      GUARDED_EXECUTOR
  };

  /** The amount of simulated work to do between operations in each test thread. */
  public static final int[] WORK_LOADS = { 10, 1000 };

  /** The number of concurrent threads running in the performance test. Must be even if not 1. */
  public static final int[] THREAD_COUNTS = { 1, 2, 6, 20, 100 };

  /** The capacity of the blocking queue to use in producer/consumer scenarios. */
  public static final int[] CAPACITIES = { 1, 3, 10 };

  /** The number of times to replicate each scenario. Must be odd. */
  public static final int TRIALS_PER_SCENARIO = 3;

  /** The number of consecutive throughout measurements to take. */
  public static final int SAMPLES_PER_TRIAL = 10;

  /** The time between taking throughput measurements. */
  public static final long SAMPLE_MILLIS = 200;

  /** The time to wait before starting the first measurement sample. */
  public static final long WARMUP_MILLIS = 300;

  /** The number of operations that a thread does between updates of its volatile counter. */
  public static final int THROUGHPUT_GRANULARITY = 50;

}
