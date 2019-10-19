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

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly;
import static java.util.Objects.requireNonNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

class TestingThread<T> extends Thread {

  @FunctionalInterface
  interface ThreadBody<T> {
    T run() throws InterruptedException, TimeoutException;
  }

  @FunctionalInterface
  interface VoidThreadBody {
    void run() throws InterruptedException, TimeoutException;
  }

  static <T> TestingThread<T> startTestingThread(ThreadBody<T> body) {
    TestingThread<T> thread = new TestingThread<>(body);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  static TestingThread<Void> startTestingThread(VoidThreadBody body) {
    return startTestingThread(() -> {
      body.run();
      return null;
    });
  }

  static void pause(String name) {
    CountDownLatch checkpoint = ((TestingThread<?>) Thread.currentThread()).checkpoint(name);
    awaitUninterruptibly(checkpoint);
  }

  static void arrive(String name) {
    ((TestingThread<?>) Thread.currentThread()).checkpoint(name).countDown();
  }

  private final ConcurrentMap<String, CountDownLatch> checkpoints = new ConcurrentHashMap<>();
  private final ThreadBody<T> body;
  private T returnedValue;
  private Throwable caughtAtEnd;
  private boolean interruptedAtEnd;

  private TestingThread(ThreadBody<T> body) {
    super(generateName());
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

  void waitForParked(Object blocker) {
    yieldUntil(
        () -> LockSupport.getBlocker(this) == blocker,
        () -> "thread not parked on " + blocker);
  }

  void waitForExited() throws InterruptedException {
    join(100);
    assertFalse("thread is still running", isAlive());
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

  static void yieldUntil(BooleanSupplier condition, Supplier<String> message) {
    long start = System.nanoTime();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() - start > 100_000_000) {
        fail(message.get());
      }
      Thread.yield();
    }
  }

  private static final ConcurrentMap<String, AtomicInteger> nameCounters =
      new ConcurrentHashMap<>();

  private static String generateName() {
    for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
      if (element.getMethodName().startsWith("test")) {
        AtomicInteger counter =
            nameCounters.computeIfAbsent(element.getMethodName(), key -> new AtomicInteger());
        return "TestingThread-" + element.getMethodName() + "-" + counter.incrementAndGet();
      }
    }
    throw new AssertionError();
  }

}
