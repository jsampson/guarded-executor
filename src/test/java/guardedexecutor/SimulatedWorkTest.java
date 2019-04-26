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

import junit.framework.TestCase;

import java.util.Random;

/**
 * Tests for {@link SimulatedWork}.
 */
public class SimulatedWorkTest extends TestCase {

  public void testDoWork() {
    testDoWork(0, 0, 0);
    testDoWork(1, 1, 1);
    testDoWork(5, 3, 6);
    testDoWork(10, 6, 13);
    testDoWork(100, 68, 131);
    testDoWork(200, 136, 263);
    testDoWork(500, 372, 627);
    testDoWork(1000, 744, 1255);
    testDoWork(2000, 1488, 2511);
  }

  private static void testDoWork(int targetReps, int expectedMinReps, int expectedMaxReps) {
    long initialSeed = System.nanoTime();
    Random random = new Random(initialSeed);
    SimulatedWork work = new SimulatedWork(initialSeed);

    int expectedRange = expectedMaxReps - expectedMinReps + 1;
    int numTrials = Math.max(1000, expectedRange * 100);

    for (int trial = 0; trial < numTrials; trial++) {
      // Do the same calculations using the standard Random implementation.
      int expectedReps = expectedMinReps + random.nextInt(expectedRange);
      for (int i = 0; i < expectedReps; i++) {
        random.nextInt();
      }

      long actualReps = work.doWork(targetReps);
      assertEquals(expectedReps, actualReps);
    }
  }

}
