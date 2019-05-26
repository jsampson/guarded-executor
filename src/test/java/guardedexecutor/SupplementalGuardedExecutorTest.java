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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import junit.framework.TestCase;

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

    Checkpoint blockerReady = new Checkpoint();
    Checkpoint blockerDone = new Checkpoint();

    startThread(() -> {
      executor.execute(() -> {
        blockerReady.go();
        blockerDone.stop();
      });
    });

    blockerReady.check();

    assertEquals(0, executor.getQueueLength());

    RuntimeException thrownException = new RuntimeException();
    boolean[] interruptStatus = {false};
    Throwable[] caughtException = {null};
    Checkpoint resultReady = new Checkpoint();

    startThread(() -> {
      Thread threadToInterrupt = Thread.currentThread();
      try {
        executor.executeInterruptibly(() -> {
          threadToInterrupt.interrupt();
          sleepBriefly();
          throw thrownException;
        });
      } catch (Throwable throwable) {
        caughtException[0] = throwable;
      } finally {
        interruptStatus[0] = Thread.interrupted();
        resultReady.go();
      }
    });

    waitForQueueLength(1, executor);

    startThread(() -> executor.execute(() -> {}));

    waitForQueueLength(2, executor);

    blockerDone.go();
    resultReady.check();

    assertNotNull(caughtException[0]);
    assertEquals(CancellationException.class, caughtException[0].getClass());
    assertSame(thrownException, caughtException[0].getCause());
    assertEquals(true, interruptStatus[0]);
  }

  public void testProceedTriggersMisbehavedGuard() throws InterruptedException {
    GuardedExecutor executor = new GuardedExecutor();
    AtomicBoolean misbehavedGuard = new AtomicBoolean();
    Checkpoint misbehavedTask = new Checkpoint();
    startThread(() -> executor.executeWhen(misbehavedGuard::get, misbehavedTask::go));
    waitForQueueLength(1, executor);
    misbehavedGuard.set(true);
    startThread(executor::proceed);
    misbehavedTask.check();
  }

  public void testProceedDoesNotBlock() throws InterruptedException {
    GuardedExecutor executor = new GuardedExecutor();

    Checkpoint blockerReady = new Checkpoint();
    Checkpoint blockerDone = new Checkpoint();

    startThread(() -> {
      executor.execute(() -> {
        blockerReady.go();
        blockerDone.stop();
      });
    });

    blockerReady.check();

    Checkpoint proceeded = new Checkpoint();

    startThread(() -> {
      executor.proceed();
      proceeded.go();
    });

    proceeded.check();

    blockerDone.go();
  }

  public void testProceedWhenSuccess() throws InterruptedException {
    GuardedExecutor executor = new GuardedExecutor();
    AtomicBoolean guard = new AtomicBoolean();
    Checkpoint proceeded = new Checkpoint();

    startThread(() -> {
      executor.proceedWhen(guard::get);
      proceeded.go();
    });

    waitForQueueLength(1, executor);

    startThread(() -> executor.execute(() -> guard.set(true)));

    proceeded.check();
  }

  public void testTryProceedWhenTimeout() throws InterruptedException {
    GuardedExecutor executor = new GuardedExecutor();
    Checkpoint proceeded = new Checkpoint();
    Throwable[] thrown = {null};

    startThread(() -> {
      try {
        executor.tryProceedWhen(() -> false, 10, TimeUnit.MILLISECONDS);
      } catch (Throwable throwable) {
        thrown[0] = throwable;
      } finally {
        proceeded.go();
      }
    });

    proceeded.check();
    assertNotNull(thrown[0]);
    assertEquals(TimeoutException.class, thrown[0].getClass());
  }

  @FunctionalInterface
  private interface Interruptible {
    void run() throws InterruptedException;
  }

  private static void interruptless(Interruptible interruptible) {
    try {
      interruptible.run();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static Thread startThread(Interruptible interruptible) {
    Thread thread = new Thread(() -> interruptless(interruptible));
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private static void sleepBriefly() {
    interruptless(() -> Thread.sleep(10));
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

  private static class Checkpoint {

    private final CountDownLatch latch = new CountDownLatch(1);

    void go() {
      latch.countDown();
    }

    void stop() {
      interruptless(latch::await);
    }

    void check() throws InterruptedException {
      assertTrue("stopped at checkpoint more than 100ms",
          latch.await(100, TimeUnit.MILLISECONDS));
    }

  }

}
