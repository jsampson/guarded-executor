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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import junit.framework.TestCase;

import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static com.google.common.util.concurrent.Uninterruptibles.joinUninterruptibly;
import static guardedexecutor.TestingThread.arrive;
import static guardedexecutor.TestingThread.pause;
import static guardedexecutor.TestingThread.startTestingThread;
import static guardedexecutor.TestingThread.yieldUntil;
import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;

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
    TestingThread<Void> blocker = startTestingThread(() -> executor.execute(() -> {
      arrive("blocked");
      pause("return");
    }));

    blocker.checkArrived("blocked");
    assertEquals(0, executor.getQueueLength());

    RuntimeException thrownException = new RuntimeException();

    TestingThread<Void> submitter = startTestingThread(() -> {
      Thread threadToInterrupt = Thread.currentThread();
      executor.executeInterruptibly(() -> {
        threadToInterrupt.interrupt();
        sleepUninterruptibly(10, TimeUnit.MILLISECONDS);
        throw thrownException;
      });
    });

    submitter.waitForParked(executor);
    assertEquals(1, executor.getQueueLength());

    TestingThread<Void> tail = startTestingThread(() -> executor.execute(() -> {}));

    tail.waitForParked(executor);
    assertEquals(2, executor.getQueueLength());

    blocker.unpause("return");
    blocker.assertNotInterruptedAtEnd();
    blocker.assertNothingCaughtAtEnd();

    submitter.assertInterruptedAtEnd();
    submitter.assertCaughtAtEnd(CancellationException.class, thrownException);

    tail.assertNotInterruptedAtEnd();
    tail.assertNothingCaughtAtEnd();
  }

  public void testThreadInterruptedButTaskReturned() throws InterruptedException {
    TestingThread<Void> blocker = startTestingThread(() -> executor.execute(() -> {
      arrive("blocked");
      pause("return");
    }));

    blocker.checkArrived("blocked");
    assertEquals(0, executor.getQueueLength());

    TestingThread<String> submitter = startTestingThread(() -> {
      Thread threadToInterrupt = Thread.currentThread();
      return executor.executeInterruptibly(() -> {
        threadToInterrupt.interrupt();
        sleepUninterruptibly(10, TimeUnit.MILLISECONDS);
        return "foo";
      });
    });

    submitter.waitForParked(executor);
    assertEquals(1, executor.getQueueLength());

    TestingThread<Void> tail = startTestingThread(() -> executor.execute(() -> {}));

    tail.waitForParked(executor);
    assertEquals(2, executor.getQueueLength());

    blocker.unpause("return");
    blocker.assertNotInterruptedAtEnd();
    blocker.assertNothingCaughtAtEnd();

    submitter.assertInterruptedAtEnd();
    submitter.assertNothingCaughtAtEnd();
    submitter.assertReturnedValue("foo");

    tail.assertNotInterruptedAtEnd();
    tail.assertNothingCaughtAtEnd();
  }

  public void testUninterruptibleExecuteRestoresInterruptStatusAfterParking() throws Exception {
    TestingThread<Void> blocker = startTestingThread(() -> executor.execute(() -> {
      arrive("blocked");
      pause("return");
    }));

    blocker.checkArrived("blocked");
    assertEquals(0, executor.getQueueLength());

    TestingThread<String> submitter = startTestingThread(() -> {
      Thread.currentThread().interrupt();
      return executor.execute(() -> "foo");
    });

    submitter.waitForParked(executor);
    assertEquals(1, executor.getQueueLength());

    blocker.unpause("return");
    blocker.assertNotInterruptedAtEnd();
    blocker.assertNothingCaughtAtEnd();

    submitter.assertInterruptedAtEnd();
    submitter.assertNothingCaughtAtEnd();
    submitter.assertReturnedValue("foo");
  }

  public void testAdditionalTaskArrivesAfterTentativeTail() throws InterruptedException {
    AtomicBoolean flag = new AtomicBoolean();
    List<String> list = new ArrayList<>();

    TestingThread<Void> thread1 = startTestingThread(() -> {
      executor.executeWhen(() -> {
        if (!flag.get()) {
          arrive("first time in guard");
          pause("returning from guard");
        }
        return flag.get();
      }, () -> list.add("a"));
    });

    thread1.checkArrived("first time in guard");

    TestingThread<Void> thread2 = startTestingThread(() -> executor.execute(() -> {
      flag.set(true);
      list.add("b");
    }));

    thread2.waitForParked(executor);
    assertEquals(1, executor.getQueueLength());

    thread1.unpause("returning from guard");
    thread1.waitForExited();
    assertEquals(0, executor.getQueueLength());

    assertEquals(asList("b", "a"), list);
  }

  public void testAdditionalTaskArrivesAfterTentativeTailAndThrows() throws InterruptedException {
    AtomicBoolean flag = new AtomicBoolean();
    Error thrown = new Error();

    TestingThread<Void> thread1 = startTestingThread(() -> {
      executor.executeWhen(() -> {
        arrive("first time in guard");
        pause("returning from guard");
        return false;
      }, () -> {});
    });

    thread1.checkArrived("first time in guard");

    TestingThread<Void> thread2 = startTestingThread(() -> {
      executor.execute(() -> {
        throw thrown;
      });
    });

    thread2.waitForParked(executor);
    assertEquals(1, executor.getQueueLength());

    thread1.unpause("returning from guard");
    thread1.waitForExited();
    thread2.waitForExited();
    assertEquals(0, executor.getQueueLength());

    thread1.assertCaughtAtEnd(thrown);
    thread2.assertCaughtAtEnd(CancellationException.class, thrown);
  }

  public void testReentrantExecutionDisallowed() throws InterruptedException {
    AtomicBoolean innerExecuted = new AtomicBoolean();

    TestingThread<Void> thread = startTestingThread(() -> {
      executor.execute(() -> {
        executor.execute(() -> innerExecuted.set(true));
      });
    });

    thread.waitForExited();
    thread.assertCaughtAtEnd(RejectedExecutionException.class);
    assertFalse(innerExecuted.get());
  }

  public void testProceedTriggersMisbehavedGuard() throws InterruptedException {
    AtomicBoolean misbehavedGuard = new AtomicBoolean();
    TestingThread<String> misbehavedTask = startTestingThread(() -> {
      return executor.executeWhen(misbehavedGuard::get, () -> "foo");
    });
    misbehavedTask.waitForParked(executor);
    assertEquals(1, executor.getQueueLength());
    misbehavedGuard.set(true);
    startTestingThread(executor::proceed);
    misbehavedTask.assertReturnedValue("foo");
  }

  public void testProceedDoesNotBlock() throws InterruptedException {
    TestingThread<Void> blocker = startTestingThread(() -> executor.execute(() -> {
      arrive("blocked");
      pause("return");
    }));
    blocker.checkArrived("blocked");

    TestingThread<Void> proceed = startTestingThread(executor::proceed);
    proceed.waitForExited();

    blocker.unpause("return");
  }

  public void testProceedWhenSuccess() throws InterruptedException {
    AtomicBoolean guard = new AtomicBoolean();

    TestingThread<Void> proceedWhen = startTestingThread(() -> {
      executor.proceedWhen(guard::get);
    });

    proceedWhen.waitForParked(executor);
    assertEquals(1, executor.getQueueLength());

    startTestingThread(() -> executor.execute(() -> guard.set(true)));

    proceedWhen.waitForExited();
  }

  public void testTryProceedWhenTimeout() throws InterruptedException {
    TestingThread<Void> tryProceedWhen = startTestingThread(() -> {
      executor.tryProceedWhen(() -> false, 10, TimeUnit.MILLISECONDS);
    });

    tryProceedWhen.assertCaughtAtEnd(TimeoutException.class);
  }

  public void testTryProceedWhenSuccess() throws InterruptedException {
    AtomicBoolean guard = new AtomicBoolean();

    TestingThread<Void> tryProceedWhen = startTestingThread(() -> {
      executor.tryProceedWhen(guard::get, 1, TimeUnit.SECONDS);
    });

    tryProceedWhen.waitForParked(executor);
    startTestingThread(() -> executor.execute(() -> guard.set(true)));

    tryProceedWhen.assertNothingCaughtAtEnd();
  }

  public void testExecutionMonitoringMethods() throws InterruptedException {
    String originalToString = executor.toString();
    assertNull(executor.getExecutingThread());
    assertEquals(false, executor.isExecuting());
    assertEquals(false, executor.isExecutingInCurrentThread());
    assertTrue(originalToString, originalToString.endsWith("[Not executing]"));

    Thread[] friendGetExecutingThread = {null};
    boolean[] friendIsExecuting = {false};
    boolean[] friendIsExecutingInCurrentThread = {false};
    String[] friendToString = {null};

    TestingThread<Void> friend = startTestingThread(() -> {
      executor.execute(() -> {
        friendGetExecutingThread[0] = executor.getExecutingThread();
        friendIsExecuting[0] = executor.isExecuting();
        friendIsExecutingInCurrentThread[0] = executor.isExecutingInCurrentThread();
        friendToString[0] = executor.toString();
        arrive("locked");
        pause("unlock");
      });
    });

    friend.checkArrived("locked");

    assertSame(friend, friendGetExecutingThread[0]);
    assertEquals(true, friendIsExecuting[0]);
    assertEquals(true, friendIsExecutingInCurrentThread[0]);
    assertEquals(
        originalToString.replace("[Not executing]",
            "[Executing in thread TestingThread-testExecutionMonitoringMethods-1]"),
        friendToString[0]);

    assertEquals(true, executor.isExecuting());
    assertEquals(false, executor.isExecutingInCurrentThread());

    friend.unpause("unlock");
    friend.waitForExited();

    assertNull(executor.getExecutingThread());
    assertEquals(false, executor.isExecuting());
    assertEquals(false, executor.isExecutingInCurrentThread());
    assertEquals(originalToString, executor.toString());
  }

  public void testQueueMonitoringMethods() throws InterruptedException {
    TestingThread<Void> blocker = startTestingThread(() -> executor.execute(() -> {
      arrive("blocked");
      pause("return");
    }));

    blocker.checkArrived("blocked");

    TestingThread<?>[] waiter = new TestingThread<?>[3];

    for (int i = 0; i < 3; i++) {
      waiter[i] = startTestingThread(() -> {
        pause("ready");
        executor.executeInterruptibly(() -> {});
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

    waiter[0].unpause("ready");
    waiter[0].waitForParked(executor);
    assertEquals(1, executor.getQueueLength());

    waiter[1].unpause("ready");
    waiter[1].waitForParked(executor);
    assertEquals(2, executor.getQueueLength());

    waiter[2].unpause("ready");
    waiter[2].waitForParked(executor);
    assertEquals(3, executor.getQueueLength());

    assertEquals(3, executor.getQueueLength());
    assertEquals(ImmutableSet.copyOf(waiter),
        ImmutableSet.copyOf(executor.getQueuedThreads()));
    assertEquals(true, executor.hasQueuedThreads());
    assertEquals(false, executor.hasQueuedThread(blocker));
    assertEquals(true, executor.hasQueuedThread(waiter[0]));
    assertEquals(true, executor.hasQueuedThread(waiter[1]));
    assertEquals(true, executor.hasQueuedThread(waiter[2]));

    waiter[1].interrupt();
    waiter[1].waitForExited();

    assertEquals(2, executor.getQueueLength());
    assertEquals(ImmutableSet.of(waiter[0], waiter[2]),
        ImmutableSet.copyOf(executor.getQueuedThreads()));
    assertEquals(true, executor.hasQueuedThreads());
    assertEquals(false, executor.hasQueuedThread(blocker));
    assertEquals(true, executor.hasQueuedThread(waiter[0]));
    assertEquals(false, executor.hasQueuedThread(waiter[1]));
    assertEquals(true, executor.hasQueuedThread(waiter[2]));

    waiter[0].interrupt();
    waiter[0].waitForExited();

    waiter[2].interrupt();
    waiter[2].waitForExited();

    assertEquals(0, executor.getQueueLength());
    assertEquals(ImmutableSet.of(),
        ImmutableSet.copyOf(executor.getQueuedThreads()));
    assertEquals(false, executor.hasQueuedThreads());
    assertEquals(false, executor.hasQueuedThread(blocker));
    assertEquals(false, executor.hasQueuedThread(waiter[0]));
    assertEquals(false, executor.hasQueuedThread(waiter[1]));
    assertEquals(false, executor.hasQueuedThread(waiter[2]));

    blocker.unpause("return");
  }

  /**
   * A sequence of tasks carefully designed to cover all of the branches that
   * are otherwise uncovered in {@code executeTasksFromHead}.
   */
  public void testExecuteTasksFromHeadEdgeCases() throws InterruptedException {
    int[] turn = {-1};
    List<String> list = new ArrayList<>();

    TestingThread<String> thread1 = startTestingThread(() -> {
      return executor.executeWhen(() -> turn[0] == 1, () -> {
        list.add("1");
        turn[0] = 3;
        return "1 okay";
      });
    });
    thread1.waitForParked(executor);

    TestingThread<String> cancelled = startTestingThread(() -> {
      return executor.executeWhen(() -> false, () -> {
        list.add("cancelled");
        return "cancelled okay";
      });
    });
    cancelled.waitForParked(executor);

    TestingThread<String> thread2a = startTestingThread(() -> {
      Thread self = Thread.currentThread();
      return executor.executeWhen(() -> {
        if (turn[0] != 2) {
          return false;
        }
        self.interrupt();
        joinUninterruptibly(self);
        throw new RuntimeException();
      }, () -> {
        list.add("2a");
        return "2a okay";
      });
    });
    thread2a.waitForParked(executor);

    TestingThread<String> thread2b = startTestingThread(() -> {
      Thread self = Thread.currentThread();
      return executor.executeWhen(() -> {
        if (turn[0] != 2) {
          return false;
        }
        self.interrupt();
        joinUninterruptibly(self);
        return true;
      }, () -> {
        list.add("2b");
        return "2b okay";
      });
    });
    thread2b.waitForParked(executor);

    TestingThread<String> thread2c = startTestingThread(() -> {
      Thread self = Thread.currentThread();
      return executor.executeWhen(() -> turn[0] == 2, () -> {
        list.add("2c");
        turn[0] = 1;
        return "2c okay";
      });
    });
    thread2c.waitForParked(executor);

    TestingThread<String> primary = startTestingThread(() -> {
      return executor.executeWhen(() -> {
        if (turn[0] == -1) {
          arrive("first time in guard");
          pause("returning from guard");
        }
        return turn[0] == 3;
      }, () -> {
        list.add("primary");
        return "primary okay";
      });
    });
    primary.checkArrived("first time in guard");

    TestingThread<String> satisfied = startTestingThread(() -> {
      return executor.execute(() -> {
        list.add("satisfied");
        turn[0] = 2;
        return "satisfied okay";
      });
    });
    satisfied.waitForParked(executor);

    cancelled.interrupt();
    cancelled.waitForExited();

    primary.unpause("returning from guard");
    primary.waitForExited();

    assertEquals(asList("satisfied", "2c", "1", "primary"), list);

    thread1.assertReturnedValue("1 okay");
    cancelled.assertCaughtAtEnd(InterruptedException.class);
    thread2a.assertCaughtAtEnd(InterruptedException.class);
    thread2b.assertCaughtAtEnd(InterruptedException.class);
    thread2c.assertReturnedValue("2c okay");
    primary.assertReturnedValue("primary okay");
    satisfied.assertReturnedValue("satisfied okay");
  }

  public void testRunnableSupplierPassedAsRunnableWithoutParking() {
    RunnableSupplier task = new RunnableSupplier();
    executor.execute((Runnable) task);
    assertTrue("run() should be called", task.runCalled);
    assertFalse("get() should not be called", task.getCalled);
  }

  public void testRunnableSupplierPassedAsSupplierWithoutParking() {
    RunnableSupplier task = new RunnableSupplier();
    assertEquals("foo", executor.execute((Supplier<String>) task));
    assertFalse("run() should not be called", task.runCalled);
    assertTrue("get() should be called", task.getCalled);
  }

  public void testRunnableSupplierPassedAsRunnableWithParking() throws InterruptedException {
    AtomicBoolean guard = new AtomicBoolean();
    RunnableSupplier task = new RunnableSupplier();
    TestingThread<Void> submitter = startTestingThread(() -> {
      executor.executeWhen(guard::get, (Runnable) task);
    });
    submitter.waitForParked(executor);
    assertFalse("run() should not be called yet", task.runCalled);
    assertFalse("get() should not be called yet", task.getCalled);
    executor.execute(() -> guard.set(true));
    submitter.waitForExited();
    assertTrue("run() should be called", task.runCalled);
    assertFalse("get() should not be called", task.getCalled);
  }

  public void testRunnableSupplierPassedAsSupplierWithParking() throws InterruptedException {
    RunnableSupplier task = new RunnableSupplier();
    try {
      executor.executeWhen(() -> false, (Supplier<?>) task);
      fail("should have thrown an exception");
    } catch (IllegalArgumentException expected) {
      assertFalse("run() should not be called", task.runCalled);
      assertFalse("get() should not be called", task.getCalled);
    }
  }

  private static class RunnableSupplier implements Runnable, Supplier<String> {

    volatile boolean runCalled = false;
    volatile boolean getCalled = false;

    @Override
    public void run() {
      runCalled = true;
    }

    @Override
    public String get() {
      getCalled = true;
      return "foo";
    }

  }

  public void testThrowingExceptionFromGuardInSameThreadBeforeParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    TestingThread<Void> thread = startThrowingThread(thrown, null);
    assertTaskNotExecuted(thread);
    thread.assertCaughtAtEnd(thrown);
  }

  public void testThrowingErrorFromGuardInSameThreadBeforeParking() throws Exception {
    Error thrown  = new Error();
    TestingThread<Void> thread = startThrowingThread(thrown, null);
    assertTaskNotExecuted(thread);
    thread.assertCaughtAtEnd(thrown);
  }

  public void testThrowingExceptionFromTaskInSameThreadBeforeParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    TestingThread<Void> thread = startThrowingThread(true, thrown);
    assertTaskExecuted(thread);
    thread.assertCaughtAtEnd(thrown);
  }

  public void testThrowingErrorFromTaskInSameThreadBeforeParking() throws Exception {
    Error thrown = new Error();
    TestingThread<Void> thread = startThrowingThread(true, thrown);
    assertTaskExecuted(thread);
    thread.assertCaughtAtEnd(thrown);
  }

  public void testThrowingExceptionFromGuardInSameThreadAfterParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    TestingThread<Void> thread = startThrowingThread(false, null);
    thread.waitForParked(executor);
    startTestingThread(() -> executor.execute(() -> setGuardThrows(thread, thrown)));
    assertTaskNotExecuted(thread);
    thread.assertCaughtAtEnd(thrown);
  }

  public void testThrowingErrorFromGuardInSameThreadAfterParking() throws Exception {
    Error thrown = new Error();
    TestingThread<Void> thread = startThrowingThread(false, null);
    thread.waitForParked(executor);
    startTestingThread(() -> executor.execute(() -> setGuardThrows(thread, thrown)));
    assertTaskNotExecuted(thread);
    thread.assertCaughtAtEnd(thrown);
  }

  public void testThrowingExceptionFromTaskInSameThreadAfterParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    TestingThread<Void> thread = startThrowingThread(false, thrown);
    thread.waitForParked(executor);
    startTestingThread(() -> executor.execute(() -> setGuardReturns(thread, true)));
    assertTaskExecuted(thread);
    thread.assertCaughtAtEnd(thrown);
  }

  public void testThrowingErrorFromTaskInSameThreadAfterParking() throws Exception {
    Error thrown = new Error();
    TestingThread<Void> thread = startThrowingThread(false, thrown);
    thread.waitForParked(executor);
    startTestingThread(() -> executor.execute(() -> setGuardReturns(thread, true)));
    assertTaskExecuted(thread);
    thread.assertCaughtAtEnd(thrown);
  }

  public void testThrowingExceptionFromGuardInOtherThreadBeforeParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    TestingThread<Void> thread = startThrowingThread(false, null);
    thread.waitForParked(executor);
    setGuardThrows(thread, thrown);
    TestingThread<Void> otherThread = startThrowingThread(true, null);
    assertTaskExecuted(otherThread);
    otherThread.assertNothingCaughtAtEnd();
    assertTaskNotExecuted(thread);
    thread.assertCaughtAtEnd(CancellationException.class, thrown);
  }

  public void testThrowingErrorFromGuardInOtherThreadBeforeParking() throws Exception {
    Error thrown = new Error();
    TestingThread<Void> thread = startThrowingThread(false, null);
    thread.waitForParked(executor);
    setGuardThrows(thread, thrown);
    TestingThread<Void> otherThread = startThrowingThread(true, null);
    assertTaskNotExecuted(otherThread);
    otherThread.assertCaughtAtEnd(thrown);
    assertTaskNotExecuted(thread);
    thread.assertCaughtAtEnd(CancellationException.class, thrown);
  }

  public void testThrowingExceptionFromTaskInOtherThreadBeforeParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    TestingThread<Void> thread = startThrowingThread(false, thrown);
    thread.waitForParked(executor);
    setGuardReturns(thread, true);
    TestingThread<Void> otherThread = startThrowingThread(true, null);
    assertTaskExecuted(otherThread);
    otherThread.assertNothingCaughtAtEnd();
    assertTaskExecuted(thread);
    thread.assertCaughtAtEnd(CancellationException.class, thrown);
  }

  public void testThrowingErrorFromTaskInOtherThreadBeforeParking() throws Exception {
    Error thrown = new Error();
    TestingThread<Void> thread = startThrowingThread(false, thrown);
    thread.waitForParked(executor);
    setGuardReturns(thread, true);
    TestingThread<Void> otherThread = startThrowingThread(true, null);
    assertTaskNotExecuted(otherThread);
    otherThread.assertCaughtAtEnd(thrown);
    assertTaskExecuted(thread);
    thread.assertCaughtAtEnd(CancellationException.class, thrown);
  }

  public void testThrowingExceptionFromGuardInOtherThreadAfterParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    TestingThread<Void> thread = startThrowingThread(false, null);
    thread.waitForParked(executor);
    TestingThread<Void> otherThread = startThrowingThread(false, null);
    otherThread.waitForParked(executor);

    startTestingThread(() -> executor.execute(() -> {
      setGuardThrows(thread, thrown);
      setGuardReturns(otherThread, true);
    }));

    assertTaskExecuted(otherThread);
    otherThread.assertNothingCaughtAtEnd();
    assertTaskNotExecuted(thread);
    thread.assertCaughtAtEnd(CancellationException.class, thrown);
  }

  public void testThrowingErrorFromGuardInOtherThreadAfterParking() throws Exception {
    Error thrown = new Error();
    TestingThread<Void> thread = startThrowingThread(false, null);
    thread.waitForParked(executor);
    TestingThread<Void> otherThread = startThrowingThread(false, null);
    otherThread.waitForParked(executor);

    startTestingThread(() -> executor.execute(() -> {
      setGuardThrows(thread, thrown);
      setGuardReturns(otherThread, true);
    }));

    assertTaskNotExecuted(otherThread);
    otherThread.assertCaughtAtEnd(thrown);
    assertTaskNotExecuted(thread);
    thread.assertCaughtAtEnd(CancellationException.class, thrown);
  }

  public void testThrowingExceptionFromTaskInOtherThreadAfterParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    TestingThread<Void> thread = startThrowingThread(false, thrown);
    thread.waitForParked(executor);
    TestingThread<Void> otherThread = startThrowingThread(false, null);
    otherThread.waitForParked(executor);

    startTestingThread(() -> executor.execute(() -> {
      setGuardReturns(thread, true);
      setGuardReturns(otherThread, true);
    }));

    assertTaskExecuted(otherThread);
    otherThread.assertNothingCaughtAtEnd();
    assertTaskExecuted(thread);
    thread.assertCaughtAtEnd(CancellationException.class, thrown);
  }

  public void testThrowingErrorFromTaskInOtherThreadAfterParking() throws Exception {
    Error thrown = new Error();
    TestingThread<Void> thread = startThrowingThread(false, thrown);
    thread.waitForParked(executor);
    TestingThread<Void> otherThread = startThrowingThread(false, null);
    otherThread.waitForParked(executor);

    startTestingThread(() -> executor.execute(() -> {
      setGuardReturns(thread, true);
      setGuardReturns(otherThread, true);
    }));

    assertTaskNotExecuted(otherThread);
    otherThread.assertCaughtAtEnd(thrown);
    assertTaskExecuted(thread);
    thread.assertCaughtAtEnd(CancellationException.class, thrown);
  }

  private final ConcurrentMap<TestingThread<Void>, AtomicReference<BooleanSupplier>> throwingGuards =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<TestingThread<Void>, AtomicBoolean> throwingTasksExecuted =
      new ConcurrentHashMap<>();

  private TestingThread<Void> startThrowingThread(boolean initialGuardValue, Throwable thrownFromTask) {
    return startThrowingThread(() -> initialGuardValue, thrownFromTask);
  }

  private TestingThread<Void> startThrowingThread(Throwable thrownFromGuard, Throwable thrownFromTask) {
    return startThrowingThread(() -> {
      throwUnchecked(thrownFromGuard);
      return false;
    }, thrownFromTask);
  }

  private TestingThread<Void> startThrowingThread(
      BooleanSupplier initialGuard, Throwable thrownFromTask) {
    AtomicReference<BooleanSupplier> guard = new AtomicReference<>(initialGuard);
    AtomicBoolean taskExecuted = new AtomicBoolean();

    TestingThread<Void> thread = startTestingThread(() -> {
      executor.executeWhen(() -> guard.get().getAsBoolean(), () -> {
        taskExecuted.set(true);
        throwUnchecked(thrownFromTask);
      });
      return null;
    });

    throwingGuards.put(thread, guard);
    throwingTasksExecuted.put(thread, taskExecuted);
    return thread;
  }

  private void setGuardReturns(TestingThread<Void> thread, boolean value) {
    throwingGuards.get(thread).set(() -> value);
  }

  private void setGuardThrows(TestingThread<Void> thread, Throwable thrown) {
    throwingGuards.get(thread).set(() -> {
      throwUnchecked(thrown);
      return false;
    });
  }

  private void assertTaskExecuted(TestingThread<Void> thread) throws InterruptedException {
    thread.waitForExited();
    assertTrue("task should have executed", throwingTasksExecuted.get(thread).get());
  }

  private void assertTaskNotExecuted(TestingThread<Void> thread) throws InterruptedException {
    thread.waitForExited();
    assertFalse("task should not have executed", throwingTasksExecuted.get(thread).get());
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

}
