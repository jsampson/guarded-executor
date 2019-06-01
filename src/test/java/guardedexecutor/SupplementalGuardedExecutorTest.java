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
    TestingThread<Void> blocker = startTestingThread("blocker", () -> executor.execute(() -> {
      arrive("blocked");
      pause("return");
    }));

    blocker.checkArrived("blocked");
    assertEquals(0, executor.getQueueLength());

    RuntimeException thrownException = new RuntimeException();

    TestingThread<Void> submitter = startTestingThread("submitter", () -> {
      Thread threadToInterrupt = Thread.currentThread();
      executor.executeInterruptibly(() -> {
        threadToInterrupt.interrupt();
        sleepBriefly();
        throw thrownException;
      });
    });

    submitter.waitForParked();
    assertEquals(1, executor.getQueueLength());

    TestingThread<Void> tail = startTestingThread("tail", () -> executor.execute(() -> {}));

    tail.waitForParked();
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
    TestingThread<Void> blocker = startTestingThread("blocker", () -> executor.execute(() -> {
      arrive("blocked");
      pause("return");
    }));

    blocker.checkArrived("blocked");
    assertEquals(0, executor.getQueueLength());

    TestingThread<String> submitter = startTestingThread("submitter", () -> {
      Thread threadToInterrupt = Thread.currentThread();
      return executor.executeInterruptibly(() -> {
        threadToInterrupt.interrupt();
        sleepBriefly();
        return "foo";
      });
    });

    submitter.waitForParked();
    assertEquals(1, executor.getQueueLength());

    TestingThread<Void> tail = startTestingThread("tail", () -> executor.execute(() -> {}));

    tail.waitForParked();
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
    TestingThread<Void> blocker = startTestingThread("blocker", () -> executor.execute(() -> {
      arrive("blocked");
      pause("return");
    }));

    blocker.checkArrived("blocked");
    assertEquals(0, executor.getQueueLength());

    TestingThread<String> submitter = startTestingThread("submitter", () -> {
      Thread.currentThread().interrupt();
      return executor.execute(() -> "foo");
    });

    submitter.waitForParked();
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

    TestingThread<Void> thread1 = startTestingThread("thread1", () -> {
      executor.executeWhen(() -> {
        if (!flag.get()) {
          arrive("first time in guard");
          pause("returning from guard");
        }
        return flag.get();
      }, () -> list.add("a"));
    });

    thread1.checkArrived("first time in guard");

    TestingThread<Void> thread2 = startTestingThread("thread2", () -> executor.execute(() -> {
      flag.set(true);
      list.add("b");
    }));

    thread2.waitForParked();
    assertEquals(1, executor.getQueueLength());

    thread1.unpause("returning from guard");
    thread1.waitForExited();
    assertEquals(0, executor.getQueueLength());

    assertEquals(asList("b", "a"), list);
  }

  public void testAdditionalTaskArrivesAfterTentativeTailAndThrows() throws InterruptedException {
    AtomicBoolean flag = new AtomicBoolean();
    Error thrown = new Error();

    TestingThread<Void> thread1 = startTestingThread("thread1", () -> {
      executor.executeWhen(() -> {
        arrive("first time in guard");
        pause("returning from guard");
        return false;
      }, () -> {});
    });

    thread1.checkArrived("first time in guard");

    TestingThread<Void> thread2 = startTestingThread("thread2", () -> {
      executor.execute(() -> {
        throw thrown;
      });
    });

    thread2.waitForParked();
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

    TestingThread<Void> thread = startTestingThread("thread", () -> {
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
    TestingThread<String> misbehavedTask = startTestingThread("misbehavedTask", () -> {
      return executor.executeWhen(misbehavedGuard::get, () -> "foo");
    });
    misbehavedTask.waitForParked();
    assertEquals(1, executor.getQueueLength());
    misbehavedGuard.set(true);
    startTestingThread("proceed", executor::proceed);
    misbehavedTask.assertReturnedValue("foo");
  }

  public void testProceedDoesNotBlock() throws InterruptedException {
    TestingThread<Void> blocker = startTestingThread("blocker", () -> executor.execute(() -> {
      arrive("blocked");
      pause("return");
    }));
    blocker.checkArrived("blocked");

    TestingThread<Void> proceed = startTestingThread("proceed", executor::proceed);
    proceed.waitForExited();

    blocker.unpause("return");
  }

  public void testProceedWhenSuccess() throws InterruptedException {
    AtomicBoolean guard = new AtomicBoolean();

    TestingThread<Void> proceedWhen = startTestingThread("proceedWhen", () -> {
      executor.proceedWhen(guard::get);
    });

    proceedWhen.waitForParked();
    assertEquals(1, executor.getQueueLength());

    startTestingThread("satisfyGuard", () -> executor.execute(() -> guard.set(true)));

    proceedWhen.waitForExited();
  }

  public void testTryProceedWhenTimeout() throws InterruptedException {
    TestingThread<Void> tryProceedWhen = startTestingThread("tryProceedWhen", () -> {
      executor.tryProceedWhen(() -> false, 10, TimeUnit.MILLISECONDS);
    });

    tryProceedWhen.assertCaughtAtEnd(TimeoutException.class);
  }

  public void testTryProceedWhenSuccess() throws InterruptedException {
    AtomicBoolean guard = new AtomicBoolean();

    TestingThread<Void> tryProceedWhen = startTestingThread("tryProceedWhen", () -> {
      executor.tryProceedWhen(guard::get, 1, TimeUnit.SECONDS);
    });

    tryProceedWhen.waitForParked();
    startTestingThread("satisfyGuard", () -> executor.execute(() -> guard.set(true)));

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

    TestingThread<Void> friend = startTestingThread("FRIEND", () -> {
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
    assertEquals(originalToString.replace("[Not executing]", "[Executing in thread FRIEND]"),
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
    TestingThread<Void> blocker = startTestingThread("blocker", () -> executor.execute(() -> {
      arrive("blocked");
      pause("return");
    }));

    blocker.checkArrived("blocked");

    TestingThread<?>[] waiter = new TestingThread<?>[3];

    for (int i = 0; i < 3; i++) {
      waiter[i] = startTestingThread("waiter" + i, () -> {
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
    waiter[0].waitForParked();
    assertEquals(1, executor.getQueueLength());

    waiter[1].unpause("ready");
    waiter[1].waitForParked();
    assertEquals(2, executor.getQueueLength());

    waiter[2].unpause("ready");
    waiter[2].waitForParked();
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
    TestingThread<Void> submitter = startTestingThread("submitter", () -> {
      executor.executeWhen(guard::get, (Runnable) task);
    });
    submitter.waitForParked();
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
    ThrowingThread thread = startThrowingThread(thrown, null);
    thread.assertTaskNotExecuted();
    thread.assertCaughtAtEnd(thrown);
  }

  public void testThrowingErrorFromGuardInSameThreadBeforeParking() throws Exception {
    Error thrown  = new Error();
    ThrowingThread thread = startThrowingThread(thrown, null);
    thread.assertTaskNotExecuted();
    thread.assertCaughtAtEnd(thrown);
  }

  public void testThrowingExceptionFromTaskInSameThreadBeforeParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    ThrowingThread thread = startThrowingThread(true, thrown);
    thread.assertTaskExecuted();
    thread.assertCaughtAtEnd(thrown);
  }

  public void testThrowingErrorFromTaskInSameThreadBeforeParking() throws Exception {
    Error thrown = new Error();
    ThrowingThread thread = startThrowingThread(true, thrown);
    thread.assertTaskExecuted();
    thread.assertCaughtAtEnd(thrown);
  }

  public void testThrowingExceptionFromGuardInSameThreadAfterParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    ThrowingThread thread = startThrowingThread(false, null);
    thread.waitForParked();
    startThread(() -> executor.execute(() -> thread.setGuardThrows(thrown)));
    thread.assertTaskNotExecuted();
    thread.assertCaughtAtEnd(thrown);
  }

  public void testThrowingErrorFromGuardInSameThreadAfterParking() throws Exception {
    Error thrown = new Error();
    ThrowingThread thread = startThrowingThread(false, null);
    thread.waitForParked();
    startThread(() -> executor.execute(() -> thread.setGuardThrows(thrown)));
    thread.assertTaskNotExecuted();
    thread.assertCaughtAtEnd(thrown);
  }

  public void testThrowingExceptionFromTaskInSameThreadAfterParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    ThrowingThread thread = startThrowingThread(false, thrown);
    thread.waitForParked();
    startThread(() -> executor.execute(() -> thread.setGuardReturns(true)));
    thread.assertTaskExecuted();
    thread.assertCaughtAtEnd(thrown);
  }

  public void testThrowingErrorFromTaskInSameThreadAfterParking() throws Exception {
    Error thrown = new Error();
    ThrowingThread thread = startThrowingThread(false, thrown);
    thread.waitForParked();
    startThread(() -> executor.execute(() -> thread.setGuardReturns(true)));
    thread.assertTaskExecuted();
    thread.assertCaughtAtEnd(thrown);
  }

  public void testThrowingExceptionFromGuardInOtherThreadBeforeParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    ThrowingThread thread = startThrowingThread(false, null);
    thread.waitForParked();
    thread.setGuardThrows(thrown);
    ThrowingThread otherThread = startThrowingThread(true, null);
    otherThread.assertTaskExecuted();
    otherThread.assertNothingCaughtAtEnd();
    thread.assertTaskNotExecuted();
    thread.assertCaughtAtEnd(CancellationException.class, thrown);
  }

  public void testThrowingErrorFromGuardInOtherThreadBeforeParking() throws Exception {
    Error thrown = new Error();
    ThrowingThread thread = startThrowingThread(false, null);
    thread.waitForParked();
    thread.setGuardThrows(thrown);
    ThrowingThread otherThread = startThrowingThread(true, null);
    otherThread.assertTaskNotExecuted();
    otherThread.assertCaughtAtEnd(thrown);
    thread.assertTaskNotExecuted();
    thread.assertCaughtAtEnd(CancellationException.class, thrown);
  }

  public void testThrowingExceptionFromTaskInOtherThreadBeforeParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    ThrowingThread thread = startThrowingThread(false, thrown);
    thread.waitForParked();
    thread.setGuardReturns(true);
    ThrowingThread otherThread = startThrowingThread(true, null);
    otherThread.assertTaskExecuted();
    otherThread.assertNothingCaughtAtEnd();
    thread.assertTaskExecuted();
    thread.assertCaughtAtEnd(CancellationException.class, thrown);
  }

  public void testThrowingErrorFromTaskInOtherThreadBeforeParking() throws Exception {
    Error thrown = new Error();
    ThrowingThread thread = startThrowingThread(false, thrown);
    thread.waitForParked();
    thread.setGuardReturns(true);
    ThrowingThread otherThread = startThrowingThread(true, null);
    otherThread.assertTaskNotExecuted();
    otherThread.assertCaughtAtEnd(thrown);
    thread.assertTaskExecuted();
    thread.assertCaughtAtEnd(CancellationException.class, thrown);
  }

  public void testThrowingExceptionFromGuardInOtherThreadAfterParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    ThrowingThread thread = startThrowingThread(false, null);
    thread.waitForParked();
    ThrowingThread otherThread = startThrowingThread(false, null);
    otherThread.waitForParked();

    startThread(() -> executor.execute(() -> {
      thread.setGuardThrows(thrown);
      otherThread.setGuardReturns(true);
    }));

    otherThread.assertTaskExecuted();
    otherThread.assertNothingCaughtAtEnd();
    thread.assertTaskNotExecuted();
    thread.assertCaughtAtEnd(CancellationException.class, thrown);
  }

  public void testThrowingErrorFromGuardInOtherThreadAfterParking() throws Exception {
    Error thrown = new Error();
    ThrowingThread thread = startThrowingThread(false, null);
    thread.waitForParked();
    ThrowingThread otherThread = startThrowingThread(false, null);
    otherThread.waitForParked();

    startThread(() -> executor.execute(() -> {
      thread.setGuardThrows(thrown);
      otherThread.setGuardReturns(true);
    }));

    otherThread.assertTaskNotExecuted();
    otherThread.assertCaughtAtEnd(thrown);
    thread.assertTaskNotExecuted();
    thread.assertCaughtAtEnd(CancellationException.class, thrown);
  }

  public void testThrowingExceptionFromTaskInOtherThreadAfterParking() throws Exception {
    RuntimeException thrown = new RuntimeException();
    ThrowingThread thread = startThrowingThread(false, thrown);
    thread.waitForParked();
    ThrowingThread otherThread = startThrowingThread(false, null);
    otherThread.waitForParked();

    startThread(() -> executor.execute(() -> {
      thread.setGuardReturns(true);
      otherThread.setGuardReturns(true);
    }));

    otherThread.assertTaskExecuted();
    otherThread.assertNothingCaughtAtEnd();
    thread.assertTaskExecuted();
    thread.assertCaughtAtEnd(CancellationException.class, thrown);
  }

  public void testThrowingErrorFromTaskInOtherThreadAfterParking() throws Exception {
    Error thrown = new Error();
    ThrowingThread thread = startThrowingThread(false, thrown);
    thread.waitForParked();
    ThrowingThread otherThread = startThrowingThread(false, null);
    otherThread.waitForParked();

    startThread(() -> executor.execute(() -> {
      thread.setGuardReturns(true);
      otherThread.setGuardReturns(true);
    }));

    otherThread.assertTaskNotExecuted();
    otherThread.assertCaughtAtEnd(thrown);
    thread.assertTaskExecuted();
    thread.assertCaughtAtEnd(CancellationException.class, thrown);
  }

  private TestingThread<Void> startTestingThread(String name, VoidThreadBody body) {
    return startTestingThread(name, () -> {
      body.run();
      return null;
    });
  }

  private <T> TestingThread<T> startTestingThread(String name, ThreadBody<T> body) {
    TestingThread<T> thread = new TestingThread<>(name, body);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private void pause(String name) {
    CountDownLatch checkpoint = ((TestingThread<?>) Thread.currentThread()).checkpoint(name);
    interruptless(checkpoint::await);
  }

  private void arrive(String name) {
    ((TestingThread<?>) Thread.currentThread()).checkpoint(name).countDown();
  }

  private class TestingThread<T> extends Thread {

    private final ConcurrentMap<String, CountDownLatch> checkpoints = new ConcurrentHashMap<>();
    private final ThreadBody<T> body;
    private T returnedValue;
    private Throwable caughtAtEnd;
    private boolean interruptedAtEnd;

    TestingThread(String name, ThreadBody<T> body) {
      super(name);
      this.body = requireNonNull(body);
    }

    @Override
    public final void run() {
      try {
        returnedValue = body.run();
      } catch (Throwable throwable) {
        caughtAtEnd = throwable;
      } finally {
        interruptedAtEnd = Thread.interrupted();
      }
    }

    private CountDownLatch checkpoint(String name) {
      return checkpoints.computeIfAbsent(name, key -> new CountDownLatch(1));
    }

    void checkArrived(String name) throws InterruptedException {
      assertTrue("stopped at " + name + " more than 100ms",
          checkpoint(name).await(100, TimeUnit.MILLISECONDS));
    }

    void unpause(String name) {
      checkpoint(name).countDown();
    }

    void assertReturnedValue(T expectedValue) throws InterruptedException {
      waitForExited();
      assertEquals("expected returned value", expectedValue, returnedValue);
    }

    void assertNothingCaughtAtEnd() throws InterruptedException {
      waitForExited();
      if (caughtAtEnd != null) {
        fail("should not have thrown anything but threw " + caughtAtEnd);
      }
    }

    void assertCaughtAtEnd(Throwable expectedThrowable) throws InterruptedException {
      waitForExited();
      assertSame("expected to be thrown", expectedThrowable, caughtAtEnd);
    }

    void assertCaughtAtEnd(Class<? extends Throwable> expectedClass) throws InterruptedException {
      waitForExited();
      assertNotNull("expected something to be thrown", caughtAtEnd);
      assertEquals("expected class", expectedClass, caughtAtEnd.getClass());
    }

    void assertCaughtAtEnd(Class<? extends Throwable> expectedClass, Throwable expectedCause)
        throws InterruptedException {
      waitForExited();
      assertNotNull("expected something to be thrown", caughtAtEnd);
      assertEquals("expected class", expectedClass, caughtAtEnd.getClass());
      assertSame("expected cause", expectedCause, caughtAtEnd.getCause());
    }

    void assertNotInterruptedAtEnd() throws InterruptedException {
      waitForExited();
      assertFalse("interrupt status should be cleared but is set", interruptedAtEnd);
    }

    void assertInterruptedAtEnd() throws InterruptedException {
      waitForExited();
      assertTrue("interrupt status should be set but is not", interruptedAtEnd);
    }

    void waitForParked() {
      yieldUntil(
          () -> LockSupport.getBlocker(this) == executor,
          () -> "thread not parked in executor");
    }

    void waitForExited() throws InterruptedException {
      join(100);
      assertFalse("thread is still running", isAlive());
    }

  }

  private ThrowingThread startThrowingThread(boolean initialGuardValue, Throwable thrownFromTask) {
    return startThrowingThread(() -> initialGuardValue, thrownFromTask);
  }

  private ThrowingThread startThrowingThread(Throwable thrownFromGuard, Throwable thrownFromTask) {
    return startThrowingThread(() -> {
      throwUnchecked(thrownFromGuard);
      return false;
    }, thrownFromTask);
  }

  private ThrowingThread startThrowingThread(BooleanSupplier initialGuard, Throwable thrownFromTask) {
    AtomicReference<BooleanSupplier> guard = new AtomicReference<>(initialGuard);
    AtomicBoolean taskExecuted = new AtomicBoolean();
    ThreadBody<Void> body = () -> {
      executor.executeWhen(() -> guard.get().getAsBoolean(), () -> {
        taskExecuted.set(true);
        throwUnchecked(thrownFromTask);
      });
      return null;
    };
    ThrowingThread thread = new ThrowingThread(guard, taskExecuted, body);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private class ThrowingThread extends TestingThread<Void> {

    private final AtomicReference<BooleanSupplier> guard;
    private final AtomicBoolean taskExecuted;

    ThrowingThread(AtomicReference<BooleanSupplier> guard,
        AtomicBoolean taskExecuted, ThreadBody<Void> body) {
      super("ThrowingThread", body);
      this.guard = guard;
      this.taskExecuted = taskExecuted;
    }

    void setGuardReturns(boolean value) {
      guard.set(() -> value);
    }

    void setGuardThrows(Throwable thrown) {
      guard.set(() -> {
        throwUnchecked(thrown);
        return false;
      });
    }

    void assertTaskExecuted() throws InterruptedException {
      waitForExited();
      assertTrue("task should have executed", taskExecuted.get());
    }

    void assertTaskNotExecuted() throws InterruptedException {
      waitForExited();
      assertFalse("task should not have executed", taskExecuted.get());
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
  private interface ThreadBody<T> {
    T run() throws InterruptedException, TimeoutException;
  }

  @FunctionalInterface
  private interface VoidThreadBody {
    void run() throws InterruptedException, TimeoutException;
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

  private static void yieldUntil(BooleanSupplier condition, Supplier<String> message) {
    long start = System.nanoTime();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() - start > 100_000_000) {
        fail(message.get());
      }
      Thread.yield();
    }
  }

}
