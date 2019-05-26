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

import com.google.common.collect.ImmutableSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
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

  private volatile GuardedExecutor executor;

  public void setUp() {
    executor = new GuardedExecutor();
  }

  public void tearDown() {
    try {
      yieldUntil(
          () -> !executor.isExecuting() && !executor.hasQueuedThreads(),
          () -> String.format("executing: %s; queue length: %d",
              executor.isExecuting(), executor.getQueueLength()));
    } finally {
      executor = null;
    }
  }

  /**
   * Tests for possible lost interrupt status when a thread is interrupted
   * while its task is executing in another thread and ends up throwing.
   *
   * @see <a href="https://github.com/jsampson/guarded-executor/issues/1">Issue #1</a>
   */
  public void testThreadInterruptedButTaskThrew() throws InterruptedException {
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

    waitForQueueLength(1);

    startThread(() -> executor.execute(() -> {}));

    waitForQueueLength(2);

    blockerDone.go();
    resultReady.check();

    assertNotNull(caughtException[0]);
    assertEquals(CancellationException.class, caughtException[0].getClass());
    assertSame(thrownException, caughtException[0].getCause());
    assertEquals(true, interruptStatus[0]);
  }

  public void testProceedTriggersMisbehavedGuard() throws InterruptedException {
    AtomicBoolean misbehavedGuard = new AtomicBoolean();
    Checkpoint misbehavedTask = new Checkpoint();
    startThread(() -> executor.executeWhen(misbehavedGuard::get, misbehavedTask::go));
    waitForQueueLength(1);
    misbehavedGuard.set(true);
    startThread(executor::proceed);
    misbehavedTask.check();
  }

  public void testProceedDoesNotBlock() throws InterruptedException {
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
    AtomicBoolean guard = new AtomicBoolean();
    Checkpoint proceeded = new Checkpoint();

    startThread(() -> {
      executor.proceedWhen(guard::get);
      proceeded.go();
    });

    waitForQueueLength(1);

    startThread(() -> executor.execute(() -> guard.set(true)));

    proceeded.check();
  }

  public void testTryProceedWhenTimeout() throws InterruptedException {
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

  public void testExecutionMonitoringMethods() throws InterruptedException {
    String originalToString = executor.toString();
    assertNull(executor.getExecutingThread());
    assertEquals(false, executor.isExecuting());
    assertEquals(false, executor.isExecutingInCurrentThread());
    assertTrue(originalToString, originalToString.endsWith("[Not executing]"));

    Checkpoint friendLocked = new Checkpoint();
    Checkpoint friendUnlock = new Checkpoint();
    Checkpoint friendDone = new Checkpoint();

    Thread[] friendGetExecutingThread = {null};
    boolean[] friendIsExecuting = {false};
    boolean[] friendIsExecutingInCurrentThread = {false};
    String[] friendToString = {null};

    Thread friend = startThread(() -> {
      Thread.currentThread().setName("FRIEND");
      executor.execute(() -> {
        friendGetExecutingThread[0] = executor.getExecutingThread();
        friendIsExecuting[0] = executor.isExecuting();
        friendIsExecutingInCurrentThread[0] = executor.isExecutingInCurrentThread();
        friendToString[0] = executor.toString();
        friendLocked.go();
        friendUnlock.stop();
      });
      friendDone.go();
    });

    friendLocked.check();

    assertSame(friend, friendGetExecutingThread[0]);
    assertEquals(true, friendIsExecuting[0]);
    assertEquals(true, friendIsExecutingInCurrentThread[0]);
    assertEquals(originalToString.replace("[Not executing]", "[Executing in thread FRIEND]"),
        friendToString[0]);

    assertEquals(true, executor.isExecuting());
    assertEquals(false, executor.isExecutingInCurrentThread());

    friendUnlock.go();
    friendDone.check();

    assertNull(executor.getExecutingThread());
    assertEquals(false, executor.isExecuting());
    assertEquals(false, executor.isExecutingInCurrentThread());
    assertEquals(originalToString, executor.toString());
  }

  public void testQueueMonitoringMethods() throws InterruptedException {
    Checkpoint blockerReady = new Checkpoint();
    Checkpoint blockerDone = new Checkpoint();

    Thread blocker = startThread(() -> {
      executor.execute(() -> {
        blockerReady.go();
        blockerDone.stop();
      });
    });

    blockerReady.check();

    Checkpoint[] waiterReady = new Checkpoint[3];
    Checkpoint[] waiterDone = new Checkpoint[3];
    Thread[] waiter = new Thread[3];

    for (int i = 0; i < 3; i++) {
      int index = i;
      waiterReady[i] = new Checkpoint();
      waiterDone[i] = new Checkpoint();
      waiter[i] = startThread(() -> {
        waiterReady[index].stop();
        try {
          executor.executeInterruptibly(() -> {});
        } catch (InterruptedException interrupted) {
          waiterDone[index].go();
        }
      });
    }

    assertEquals(0, executor.getQueueLength());
    assertEquals(ImmutableSet.of(),
        ImmutableSet.copyOf(executor.getQueuedThreads()));
    assertEquals(false, executor.hasQueuedThreads());
    assertEquals(false, executor.hasQueuedThread(blocker));
    assertEquals(false, executor.hasQueuedThread(waiter[0]));
    assertEquals(false, executor.hasQueuedThread(waiter[1]));
    assertEquals(false, executor.hasQueuedThread(waiter[2]));

    waiterReady[0].go();
    waitForQueueLength(1);
    waiterReady[1].go();
    waitForQueueLength(2);
    waiterReady[2].go();
    waitForQueueLength(3);

    assertEquals(3, executor.getQueueLength());
    assertEquals(ImmutableSet.copyOf(waiter),
        ImmutableSet.copyOf(executor.getQueuedThreads()));
    assertEquals(true, executor.hasQueuedThreads());
    assertEquals(false, executor.hasQueuedThread(blocker));
    assertEquals(true, executor.hasQueuedThread(waiter[0]));
    assertEquals(true, executor.hasQueuedThread(waiter[1]));
    assertEquals(true, executor.hasQueuedThread(waiter[2]));

    waiter[1].interrupt();
    waiterDone[1].check();

    assertEquals(2, executor.getQueueLength());
    assertEquals(ImmutableSet.of(waiter[0], waiter[2]),
        ImmutableSet.copyOf(executor.getQueuedThreads()));
    assertEquals(true, executor.hasQueuedThreads());
    assertEquals(false, executor.hasQueuedThread(blocker));
    assertEquals(true, executor.hasQueuedThread(waiter[0]));
    assertEquals(false, executor.hasQueuedThread(waiter[1]));
    assertEquals(true, executor.hasQueuedThread(waiter[2]));

    waiter[0].interrupt();
    waiterDone[0].check();

    waiter[2].interrupt();
    waiterDone[2].check();

    assertEquals(0, executor.getQueueLength());
    assertEquals(ImmutableSet.of(),
        ImmutableSet.copyOf(executor.getQueuedThreads()));
    assertEquals(false, executor.hasQueuedThreads());
    assertEquals(false, executor.hasQueuedThread(blocker));
    assertEquals(false, executor.hasQueuedThread(waiter[0]));
    assertEquals(false, executor.hasQueuedThread(waiter[1]));
    assertEquals(false, executor.hasQueuedThread(waiter[2]));

    blockerDone.go();
  }

  public void testThrowingExceptionFromGuardInSameThreadBeforeParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    ThrowingThread thread = new ThrowingThread(thrown, null);
    assertSame(thrown, thread.checkCaught(false));
  }

  public void testThrowingErrorFromGuardInSameThreadBeforeParking() throws Exception {
    Error thrown  = new Error();
    ThrowingThread thread = new ThrowingThread(thrown, null);
    assertSame(thrown, thread.checkCaught(false));
  }

  public void testThrowingExceptionFromTaskInSameThreadBeforeParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    ThrowingThread thread = new ThrowingThread(true, thrown);
    assertSame(thrown, thread.checkCaught(true));
  }

  public void testThrowingErrorFromTaskInSameThreadBeforeParking() throws Exception {
    Error thrown = new Error();
    ThrowingThread thread = new ThrowingThread(true, thrown);
    assertSame(thrown, thread.checkCaught(true));
  }

  public void testThrowingExceptionFromGuardInSameThreadAfterParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    ThrowingThread thread = new ThrowingThread(false, null);
    thread.waitForParked();
    startThread(() -> executor.execute(() -> thread.setGuardThrows(thrown)));
    assertSame(thrown, thread.checkCaught(false));
  }

  public void testThrowingErrorFromGuardInSameThreadAfterParking() throws Exception {
    Error thrown = new Error();
    ThrowingThread thread = new ThrowingThread(false, null);
    thread.waitForParked();
    startThread(() -> executor.execute(() -> thread.setGuardThrows(thrown)));
    assertSame(thrown, thread.checkCaught(false));
  }

  public void testThrowingExceptionFromTaskInSameThreadAfterParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    ThrowingThread thread = new ThrowingThread(false, thrown);
    thread.waitForParked();
    startThread(() -> executor.execute(() -> thread.setGuardReturns(true)));
    assertSame(thrown, thread.checkCaught(true));
  }

  public void testThrowingErrorFromTaskInSameThreadAfterParking() throws Exception {
    Error thrown = new Error();
    ThrowingThread thread = new ThrowingThread(false, thrown);
    thread.waitForParked();
    startThread(() -> executor.execute(() -> thread.setGuardReturns(true)));
    assertSame(thrown, thread.checkCaught(true));
  }

  public void testThrowingExceptionFromGuardInOtherThreadBeforeParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    ThrowingThread thread = new ThrowingThread(false, null);
    thread.waitForParked();
    thread.setGuardThrows(thrown);
    ThrowingThread otherThread = new ThrowingThread(true, null);
    assertNull(otherThread.checkCaught(true));
    Throwable caught = thread.checkCaught(false);
    assertEquals(CancellationException.class, caught.getClass());
    assertSame(thrown, caught.getCause());
  }

  public void testThrowingErrorFromGuardInOtherThreadBeforeParking() throws Exception {
    Error thrown = new Error();
    ThrowingThread thread = new ThrowingThread(false, null);
    thread.waitForParked();
    thread.setGuardThrows(thrown);
    ThrowingThread otherThread = new ThrowingThread(true, null);
    assertSame(thrown, otherThread.checkCaught(false));
    Throwable caught = thread.checkCaught(false);
    assertEquals(CancellationException.class, caught.getClass());
    assertSame(thrown, caught.getCause());
  }

  public void testThrowingExceptionFromTaskInOtherThreadBeforeParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    ThrowingThread thread = new ThrowingThread(false, thrown);
    thread.waitForParked();
    thread.setGuardReturns(true);
    ThrowingThread otherThread = new ThrowingThread(true, null);
    assertNull(otherThread.checkCaught(true));
    Throwable caught = thread.checkCaught(true);
    assertEquals(CancellationException.class, caught.getClass());
    assertSame(thrown, caught.getCause());
  }

  public void testThrowingErrorFromTaskInOtherThreadBeforeParking() throws Exception {
    Error thrown = new Error();
    ThrowingThread thread = new ThrowingThread(false, thrown);
    thread.waitForParked();
    thread.setGuardReturns(true);
    ThrowingThread otherThread = new ThrowingThread(true, null);
    assertSame(thrown, otherThread.checkCaught(false));
    Throwable caught = thread.checkCaught(true);
    assertEquals(CancellationException.class, caught.getClass());
    assertSame(thrown, caught.getCause());
  }

  public void testThrowingExceptionFromGuardInOtherThreadAfterParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    ThrowingThread thread = new ThrowingThread(false, null);
    thread.waitForParked();
    ThrowingThread otherThread = new ThrowingThread(false, null);
    otherThread.waitForParked();

    startThread(() -> executor.execute(() -> {
      thread.setGuardThrows(thrown);
      otherThread.setGuardReturns(true);
    }));

    assertNull(otherThread.checkCaught(true));
    Throwable caught = thread.checkCaught(false);
    assertEquals(CancellationException.class, caught.getClass());
    assertSame(thrown, caught.getCause());
  }

  public void testThrowingErrorFromGuardInOtherThreadAfterParking() throws Exception {
    Error thrown = new Error();
    ThrowingThread thread = new ThrowingThread(false, null);
    thread.waitForParked();
    ThrowingThread otherThread = new ThrowingThread(false, null);
    otherThread.waitForParked();

    startThread(() -> executor.execute(() -> {
      thread.setGuardThrows(thrown);
      otherThread.setGuardReturns(true);
    }));

    assertSame(thrown, otherThread.checkCaught(false));
    Throwable caught = thread.checkCaught(false);
    assertEquals(CancellationException.class, caught.getClass());
    assertSame(thrown, caught.getCause());
  }

  public void testThrowingExceptionFromTaskInOtherThreadAfterParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    ThrowingThread thread = new ThrowingThread(false, thrown);
    thread.waitForParked();
    ThrowingThread otherThread = new ThrowingThread(false, null);
    otherThread.waitForParked();

    startThread(() -> executor.execute(() -> {
      thread.setGuardReturns(true);
      otherThread.setGuardReturns(true);
    }));

    assertNull(otherThread.checkCaught(true));
    Throwable caught = thread.checkCaught(true);
    assertEquals(CancellationException.class, caught.getClass());
    assertSame(thrown, caught.getCause());
  }

  public void testThrowingErrorFromTaskInOtherThreadAfterParking() throws Exception {
    Error thrown = new Error();
    ThrowingThread thread = new ThrowingThread(false, thrown);
    thread.waitForParked();
    ThrowingThread otherThread = new ThrowingThread(false, null);
    otherThread.waitForParked();

    startThread(() -> executor.execute(() -> {
      thread.setGuardReturns(true);
      otherThread.setGuardReturns(true);
    }));

    assertSame(thrown, otherThread.checkCaught(false));
    Throwable caught = thread.checkCaught(true);
    assertEquals(CancellationException.class, caught.getClass());
    assertSame(thrown, caught.getCause());
  }

  private class ThrowingThread {

    private final Checkpoint finished = new Checkpoint();
    private final Thread thread;
    private volatile BooleanSupplier guard;
    private volatile Throwable caught;
    private volatile boolean taskExecuted;

    ThrowingThread(boolean initialGuardValue, Throwable thrownFromTask) {
      this(() -> initialGuardValue, thrownFromTask);
    }

    ThrowingThread(Throwable thrownFromGuard, Throwable thrownFromTask) {
      this(() -> {
        throwUnchecked(thrownFromGuard);
        return false;
      }, thrownFromTask);
    }

    ThrowingThread(BooleanSupplier initialGuard, Throwable thrownFromTask) {
      this.guard = initialGuard;
      thread = startThread(() -> {
        try {
          executor.executeWhen(() -> this.guard.getAsBoolean(), () -> {
            taskExecuted = true;
            throwUnchecked(thrownFromTask);
          });
        } catch (Throwable throwable) {
          caught = throwable;
        } finally {
          finished.go();
        }
      });
    }

    void setGuardReturns(boolean value) {
      this.guard = () -> value;
    }

    void setGuardThrows(Throwable thrown) {
      this.guard = () -> {
        throwUnchecked(thrown);
        return false;
      };
    }

    Throwable checkCaught(boolean expectTaskExecuted) throws InterruptedException {
      finished.check();
      assertEquals("whether task executed", expectTaskExecuted, taskExecuted);
      return caught;
    }

    void waitForParked() {
      yieldUntil(
          () -> LockSupport.getBlocker(thread) == executor,
          () -> "thread not parked in executor");
    }

  }

  private static void throwUnchecked(Throwable throwable) {
    if (throwable instanceof RuntimeException) {
      throw (RuntimeException) throwable;
    } else if (throwable instanceof Error) {
      throw (Error) throwable;
    } else if (throwable != null) {
      throw new AssertionError(throwable);
    }
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

  private void waitForQueueLength(int expectedLength) {
    yieldUntil(
        () -> executor.getQueueLength() == expectedLength,
        () -> String.format("expected queue length %d but was %d",
            expectedLength, executor.getQueueLength()));
  }

  private static void yieldUntil(BooleanSupplier condition, Supplier<String> message) {
    long start = System.nanoTime();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() - start > 100_000_000) {
        fail(message.get());
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
