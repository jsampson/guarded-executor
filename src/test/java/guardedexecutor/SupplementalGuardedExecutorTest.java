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

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import junit.framework.TestCase;

import static com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly;
import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static guardedexecutor.GeneratedGuardedExecutorTest.startThread;

/**
 * Supplemental tests for {@link GuardedExecutor}.
 *
 * <p>This test class contains various test cases that don't fit into the test case generation in
 * {@link GeneratedGuardedExecutorTest}.
 *
 * @author Justin T. Sampson
 */
public class SupplementalGuardedExecutorTest extends TestCase {

  /**
   * Tests for possible lost interrupt status when a thread is interrupted
   * while its task is executing in another thread and ends up throwing.
   *
   * @see <a href="https://github.com/jsampson/guarded-executor/issues/1">Issue #1</a>
   */
  public void testThreadInterruptedButTaskThrew() throws InterruptedException {
    GuardedExecutor executor = new GuardedExecutor();

    CountDownLatch blockerReady = new CountDownLatch(1);
    CountDownLatch blockerDone = new CountDownLatch(1);

    startThread(() -> {
      executor.execute(() -> {
        blockerReady.countDown();
        awaitUninterruptibly(blockerDone);
      });
    });

    assertTrue(blockerReady.await(100, TimeUnit.MILLISECONDS));

    assertEquals(0, executor.getQueueLength());

    RuntimeException thrownException = new RuntimeException();
    boolean[] interruptStatus = {false};
    Throwable[] caughtException = {null};
    CountDownLatch resultReady = new CountDownLatch(1);

    startThread(() -> {
      Thread threadToInterrupt = Thread.currentThread();
      try {
        executor.executeInterruptibly(() -> {
          threadToInterrupt.interrupt();
          sleepUninterruptibly(10, TimeUnit.MILLISECONDS);
          throw thrownException;
        });
      } catch (Throwable throwable) {
        caughtException[0] = throwable;
      } finally {
        interruptStatus[0] = Thread.interrupted();
        resultReady.countDown();
      }
    });

    waitForQueueLength(1, executor);

    startThread(() -> executor.execute(() -> {}));

    waitForQueueLength(2, executor);

    blockerDone.countDown();

    assertTrue(resultReady.await(100, TimeUnit.MILLISECONDS));

    assertNotNull(caughtException[0]);
    assertEquals(CancellationException.class, caughtException[0].getClass());
    assertSame(thrownException, caughtException[0].getCause());
    assertEquals(true, interruptStatus[0]);
  }

  private static void waitForQueueLength(int expectedLength, GuardedExecutor executor) {
    long start = System.nanoTime();
    int actualLength;
    while ((actualLength = executor.getQueueLength()) != expectedLength) {
      assertEquals(expectedLength - 1, actualLength);
      if (System.nanoTime() - start > 100_000_000) {
        fail("waited too long for queue length to be " + expectedLength);
      }
      Thread.yield();
    }
  }

}
