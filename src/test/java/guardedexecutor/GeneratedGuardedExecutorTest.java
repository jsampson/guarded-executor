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
import junit.framework.TestSuite;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly;

/**
 * Tests for {@link GuardedExecutor} based on Guava's {@code GeneratedMonitorTest}.
 */
public class GeneratedGuardedExecutorTest extends TestCase {

  public static TestSuite suite() {
    TestSuite suite = new TestSuite();

    Method[] methods = GuardedExecutor.class.getMethods();
    sortMethods(methods);
    for (Method method : methods) {
      if (isExecuteMethod(method)) {
        validateMethod(method);
        addTests(suite, method);
      }
    }

    assertEquals(158, suite.testCount());

    return suite;
  }

  /**
   * A typical timeout value we'll use in the tests.
   */
  private static final long SMALL_TIMEOUT_MILLIS = 10;

  /**
   * How long to wait when determining that a thread is blocked if we expect it to be blocked.
   */
  private static final long EXPECTED_HANG_DELAY_MILLIS = 75;

  /**
   * How long to wait when determining that a thread is blocked if we DON'T expect it to be blocked.
   */
  private static final long UNEXPECTED_HANG_DELAY_MILLIS = 10000;

  /**
   * Various scenarios to be generated for each method under test. The actual scenario generation
   * (determining which scenarios are applicable to which methods and what the outcome should be)
   * takes place in {@link #addTests(TestSuite, Method)}.
   */
  private enum Scenario {

    SATISFIED_AND_UNOCCUPIED_BEFORE_EXECUTING,
    UNSATISFIED_AND_UNOCCUPIED_BEFORE_EXECUTING,
    SATISFIED_AND_OCCUPIED_BEFORE_EXECUTING,
    SATISFIED_UNOCCUPIED_AND_INTERRUPTED_BEFORE_EXECUTING,

    SATISFIED_WHILE_WAITING,
    INTERRUPTED_WHILE_WAITING

  }

  /**
   * Timeout values to combine with each {@link Scenario}.
   */
  private enum Timeout {

    MIN(Long.MIN_VALUE, "-oo"),
    MINUS_SMALL(-SMALL_TIMEOUT_MILLIS, "-" + SMALL_TIMEOUT_MILLIS + "ms"),
    ZERO(0L, "0ms"),
    SMALL(SMALL_TIMEOUT_MILLIS, SMALL_TIMEOUT_MILLIS + "ms"),
    LARGE(UNEXPECTED_HANG_DELAY_MILLIS * 2, (2 * UNEXPECTED_HANG_DELAY_MILLIS) + "ms"),
    MAX(Long.MAX_VALUE, "+oo");

    final long millis;
    final String label;

    Timeout(long millis, String label) {
      this.millis = millis;
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }

  }

  /**
   * Convenient subsets of the {@link Timeout} enumeration for specifying scenario outcomes.
   */
  @SuppressWarnings("unused") // all values included in TimeoutsToUse.values() iteration
  private enum TimeoutsToUse {

    ANY(Timeout.values()),
    PAST(Timeout.MIN, Timeout.MINUS_SMALL, Timeout.ZERO),
    FUTURE(Timeout.SMALL, Timeout.MAX),
    SMALL(Timeout.SMALL),
    FINITE(Timeout.MIN, Timeout.MINUS_SMALL, Timeout.ZERO, Timeout.SMALL),
    INFINITE(Timeout.LARGE, Timeout.MAX);

    final List<Timeout> timeouts;

    TimeoutsToUse(Timeout... timeouts) {
      this.timeouts = Collections.unmodifiableList(Arrays.asList(timeouts));
    }

  }

  /**
   * Possible outcomes of calling any of the methods under test.
   */
  private enum Outcome {

    /**
     * The method returned normally.
     */
    SUCCESS,

    /**
     * The method throw a TimeoutException.
     */
    TIMEOUT,

    /**
     * The method threw an InterruptedException.
     */
    INTERRUPT,

    /**
     * The method threw a RejectedExecutionException.
     */
    REJECTED,

    /**
     * The method did not return or throw anything.
     */
    HANG

  }

  private static boolean isExecuteMethod(Method method) {
    return method.getName().startsWith("execute") || method.getName().startsWith("tryExecute");
  }

  private static boolean isNonBlocking(Method method) {
    return throwsTimeout(method) && !isTimed(method);
  }

  private static boolean isGuarded(Method method) {
    Class<?>[] parameterTypes = method.getParameterTypes();
    return parameterTypes.length == 2 || parameterTypes.length == 4;
  }

  private static boolean isTimed(Method method) {
    Class<?>[] parameterTypes = method.getParameterTypes();
    return parameterTypes.length == 3 || parameterTypes.length == 4;
  }

  private static boolean throwsTimeout(Method method) {
    return Arrays.asList(method.getExceptionTypes()).contains(TimeoutException.class);
  }

  private static boolean throwsInterrupted(Method method) {
    return Arrays.asList(method.getExceptionTypes()).contains(InterruptedException.class);
  }

  private static boolean returnsValue(Method method) {
    return method.getReturnType() != void.class;
  }

  /**
   * Sorts the given methods primarily by name and secondarily by number of parameters.
   */
  private static void sortMethods(Method[] methods) {
    Arrays.sort(methods, (m1, m2) -> {
      int nameComparison = m1.getName().compareTo(m2.getName());
      if (nameComparison != 0) {
        return nameComparison;
      } else {
        return Integer.compare(m1.getParameterTypes().length, m2.getParameterTypes().length);
      }
    });
  }

  /**
   * Validates that the given method's signature meets all of our assumptions.
   */
  private static void validateMethod(Method method) {
    String desc = method.toString();
    String name = method.getName();

    Class<?> expectedTaskType = returnsValue(method) ? Supplier.class : Runnable.class;
    Class<?>[] parameterTypes = method.getParameterTypes();
    switch (parameterTypes.length) {
      case 1:
        assertEquals(desc, expectedTaskType, parameterTypes[0]);
        break;
      case 2:
        assertEquals(desc, BooleanSupplier.class, parameterTypes[0]);
        assertEquals(desc, expectedTaskType, parameterTypes[1]);
        break;
      case 3:
        assertEquals(desc, long.class, parameterTypes[0]);
        assertEquals(desc, TimeUnit.class, parameterTypes[1]);
        assertEquals(desc, expectedTaskType, parameterTypes[2]);
        break;
      case 4:
        assertEquals(desc, BooleanSupplier.class, parameterTypes[0]);
        assertEquals(desc, long.class, parameterTypes[1]);
        assertEquals(desc, TimeUnit.class, parameterTypes[2]);
        assertEquals(desc, expectedTaskType, parameterTypes[3]);
        break;
      default:
        fail(desc);
    }

    if (throwsTimeout(method) != name.startsWith("try")) {
      fail("must start with 'try' if and only if throws TimeoutException: " + desc);
    }

    if (isGuarded(method) != (name.contains("When") || name.contains("If"))) {
      fail("must contain 'When' or 'If' if and only if takes BooleanSupplier: " + desc);
    }

    if (name.endsWith("Interruptible") && !throwsInterrupted(method)) {
      fail("must not end with 'Interruptible' unless throws InterruptedException: " + desc);
    }

    if (name.endsWith("Uninterruptible") && throwsInterrupted(method)) {
      fail("must not end with 'Uninterruptible' if throws InterruptedException: " + desc);
    }
  }

  /**
   * Generates all test cases appropriate for the given method.
   */
  private static void addTests(TestSuite suite, Method method) {
    addTests(suite, method,
        Scenario.SATISFIED_AND_UNOCCUPIED_BEFORE_EXECUTING,
        TimeoutsToUse.ANY,
        Outcome.SUCCESS);
    addTests(suite, method,
        Scenario.UNSATISFIED_AND_UNOCCUPIED_BEFORE_EXECUTING,
        TimeoutsToUse.FINITE,
        isGuarded(method)
            ? (throwsTimeout(method) ? Outcome.TIMEOUT : Outcome.HANG)
            : Outcome.SUCCESS);
    addTests(suite, method,
        Scenario.UNSATISFIED_AND_UNOCCUPIED_BEFORE_EXECUTING,
        TimeoutsToUse.INFINITE,
        isGuarded(method)
            ? (isNonBlocking(method) ? Outcome.TIMEOUT : Outcome.HANG)
            : Outcome.SUCCESS);
    addTests(suite, method,
        Scenario.SATISFIED_AND_OCCUPIED_BEFORE_EXECUTING,
        TimeoutsToUse.FINITE,
        throwsTimeout(method) ? Outcome.TIMEOUT : Outcome.HANG);
    addTests(suite, method,
        Scenario.SATISFIED_AND_OCCUPIED_BEFORE_EXECUTING,
        TimeoutsToUse.INFINITE,
        Outcome.HANG);
    addTests(suite, method,
        Scenario.SATISFIED_UNOCCUPIED_AND_INTERRUPTED_BEFORE_EXECUTING,
        TimeoutsToUse.ANY,
        throwsInterrupted(method) ? Outcome.INTERRUPT : Outcome.SUCCESS);

    if (isGuarded(method)) {
      addTests(suite, method,
          Scenario.SATISFIED_WHILE_WAITING,
          TimeoutsToUse.INFINITE,
          Outcome.SUCCESS);
      addTests(suite, method,
          Scenario.SATISFIED_WHILE_WAITING,
          TimeoutsToUse.PAST,
          Outcome.TIMEOUT);
      addTests(suite, method,
          Scenario.INTERRUPTED_WHILE_WAITING,
          TimeoutsToUse.SMALL,
          throwsInterrupted(method) ? Outcome.INTERRUPT : Outcome.TIMEOUT);
      addTests(suite, method,
          Scenario.INTERRUPTED_WHILE_WAITING,
          TimeoutsToUse.INFINITE,
          throwsInterrupted(method) ? Outcome.INTERRUPT : Outcome.HANG);
    }
  }

  /**
   * Generates test cases for the given combination of scenario and timeouts. For methods that take
   * an explicit timeout value, all of the given timeoutsToUse result in individual test cases. For
   * methods that do not take an explicit timeout value, a single test case is generated only if the
   * implicit timeout of that method matches the given timeoutsToUse. For example, enter() is
   * treated like enter(MAX, MILLIS) and tryEnter() is treated like enter(0, MILLIS).
   */
  private static void addTests(TestSuite suite, Method method, Scenario scenario,
                               TimeoutsToUse timeoutsToUse, Outcome expectedOutcome) {
    if (isTimed(method)) {
      for (Timeout timeout : timeoutsToUse.timeouts) {
        suite.addTest(new GeneratedGuardedExecutorTest(method, scenario, timeout, expectedOutcome));
      }
    } else {
      Timeout implicitTimeout = (isNonBlocking(method) ? Timeout.ZERO : Timeout.MAX);
      if (timeoutsToUse.timeouts.contains(implicitTimeout)) {
        suite.addTest(new GeneratedGuardedExecutorTest(method, scenario, null, expectedOutcome));
      }
    }
  }

  /**
   * A guard that encapsulates a simple, mutable boolean flag.
   */
  static class FlagGuard implements BooleanSupplier {

    private boolean satisfied;

    @Override
    public boolean getAsBoolean() {
      return satisfied;
    }

    public void setSatisfied(boolean satisfied) {
      this.satisfied = satisfied;
    }

  }

  private final Method method;
  private final Scenario scenario;
  private final Timeout timeout;
  private final Outcome expectedOutcome;
  private final GuardedExecutor executor;
  private final FlagGuard guard;
  private final CountDownLatch tearDownLatch;
  private final CountDownLatch doingCallLatch;
  private final CountDownLatch callCompletedLatch;

  private GeneratedGuardedExecutorTest(
      Method method, Scenario scenario, Timeout timeout, Outcome expectedOutcome) {
    super(nameFor(method, scenario, timeout, expectedOutcome));
    this.method = method;
    this.scenario = scenario;
    this.timeout = timeout;
    this.expectedOutcome = expectedOutcome;
    this.executor = new GuardedExecutor();
    this.guard = new FlagGuard();
    this.tearDownLatch = new CountDownLatch(1);
    this.doingCallLatch = new CountDownLatch(1);
    this.callCompletedLatch = new CountDownLatch(1);
  }

  private static String nameFor(
      Method method, Scenario scenario, Timeout timeout, Outcome expectedOutcome) {
    return String.format(Locale.ROOT,
        "%s(%s)/%s->%s",
        method.getName(),
        (timeout == null) ? "untimed" : timeout,
        scenario,
        expectedOutcome);
  }

  @Override
  protected void runTest() throws Throwable {
    FutureTask<Void> task = new FutureTask<>(this::runChosenTest, null);
    startThread(task);
    doingCallLatch.await();
    long hangDelayMillis = (expectedOutcome == Outcome.HANG)
        ? EXPECTED_HANG_DELAY_MILLIS
        : UNEXPECTED_HANG_DELAY_MILLIS;
    boolean hung = !callCompletedLatch.await(hangDelayMillis, TimeUnit.MILLISECONDS);
    if (hung) {
      assertEquals(expectedOutcome, Outcome.HANG);
    } else {
      assertNull(task.get(UNEXPECTED_HANG_DELAY_MILLIS, TimeUnit.MILLISECONDS));
    }
  }

  @Override
  protected void tearDown() throws Exception {
    // We don't want to leave stray threads running after each test. At this point, every thread
    // launched by this test is either:
    //
    // (a) Blocked attempting to enter the monitor.
    // (b) Waiting for the single guard to become satisfied.
    // (c) Occupying the monitor and awaiting the tearDownLatch.
    //
    // Except for (c), every thread should occupy the monitor very briefly, and every thread leaves
    // the monitor with the guard satisfied. Therefore as soon as tearDownLatch is triggered, we
    // should be able to enter the monitor, and then we set the guard to satisfied for the benefit
    // of any remaining waiting threads.

    tearDownLatch.countDown();
    executor.tryExecute(UNEXPECTED_HANG_DELAY_MILLIS, TimeUnit.MILLISECONDS,
        () -> guard.setSatisfied(true));
  }

  private void runChosenTest() {
    if (scenario.name().endsWith("_WAITING")) {
      runWaitingTest();
    } else {
      runExecutingTest();
    }
  }

  private void runExecutingTest() {
    assertFalse(Thread.currentThread().isInterrupted());
    assertFalse(executor.isExecutingInCurrentThread());

    doEnterScenarioSetUp();

    boolean interruptedBeforeCall = Thread.currentThread().isInterrupted();
    AtomicBoolean taskExecuted = new AtomicBoolean();
    Outcome actualOutcome = doCall(() -> {
      taskExecuted.set(true);
      guard.setSatisfied(true);
      assertTrue(executor.isExecutingInCurrentThread());
    });
    boolean interruptedAfterCall = Thread.currentThread().isInterrupted();

    assertFalse(executor.isExecutingInCurrentThread());
    assertEquals(expectedOutcome, actualOutcome);
    assertEquals(expectedOutcome == Outcome.SUCCESS, taskExecuted.get());
    assertEquals(interruptedBeforeCall && expectedOutcome != Outcome.INTERRUPT,
        interruptedAfterCall);
  }

  private void doEnterScenarioSetUp() {
    switch (scenario) {
      case SATISFIED_AND_UNOCCUPIED_BEFORE_EXECUTING:
        enterSatisfyGuardAndLeaveInCurrentThread();
        break;
      case UNSATISFIED_AND_UNOCCUPIED_BEFORE_EXECUTING:
        break;
      case SATISFIED_AND_OCCUPIED_BEFORE_EXECUTING:
        enterSatisfyGuardAndLeaveInCurrentThread();
        enterAndRemainOccupyingInAnotherThread();
        break;
      case SATISFIED_UNOCCUPIED_AND_INTERRUPTED_BEFORE_EXECUTING:
        enterSatisfyGuardAndLeaveInCurrentThread();
        Thread.currentThread().interrupt();
        break;
      default:
        throw new AssertionError("unsupported scenario: " + scenario);
    }
  }

  private void runWaitingTest() {
    Thread originalThread = Thread.currentThread();
    assertFalse(Thread.currentThread().isInterrupted());
    assertFalse(executor.isExecutingInCurrentThread());
    AtomicBoolean guardExecuted = new AtomicBoolean();
    AtomicBoolean taskExecuted = new AtomicBoolean();

    Outcome actualOutcome = doCall(() -> {
      guardExecuted.set(true);
      switch (scenario) {
        case SATISFIED_WHILE_WAITING:
          startThread(() -> executor.execute(() -> guard.setSatisfied(true)));
          break;
        case INTERRUPTED_WHILE_WAITING:
          startThread(originalThread::interrupt);
          break;
        default:
          throw new AssertionError("unsupported scenario: " + scenario);
      }
    }, () -> {
      taskExecuted.set(true);
      guard.setSatisfied(true);
      assertTrue(executor.isExecutingInCurrentThread());
    });
    assertFalse(executor.isExecutingInCurrentThread());

    boolean interruptedAfterCall = Thread.currentThread().isInterrupted();

    assertEquals(expectedOutcome, actualOutcome);
    assertEquals(
        scenario == Scenario.INTERRUPTED_WHILE_WAITING && expectedOutcome != Outcome.INTERRUPT,
        interruptedAfterCall);
  }

  private Outcome doCall(Runnable duringTaskExecution) {
    return doCall(null, duringTaskExecution);
  }

  private Outcome doCall(Runnable afterFirstGuardEvaluation, Runnable duringTaskExecution) {
    boolean guarded = isGuarded(method);
    boolean timed = isTimed(method);
    Object[] arguments = new Object[(guarded ? 1 : 0) + (timed ? 2 : 0) + 1];
    if (guarded) {
      if (afterFirstGuardEvaluation == null) {
        arguments[0] = guard;
      } else {
        AtomicBoolean evaluatedYet = new AtomicBoolean();
        arguments[0] = (BooleanSupplier) () -> {
          boolean result = guard.getAsBoolean();
          if (evaluatedYet.compareAndSet(false, true)) {
            afterFirstGuardEvaluation.run();
          }
          return result;
        };
      }
    } else {
      assertNull(afterFirstGuardEvaluation);
    }
    if (timed) {
      arguments[guarded ? 1 : 0] = timeout.millis;
      arguments[guarded ? 2 : 1] = TimeUnit.MILLISECONDS;
    }
    Object value;
    if (returnsValue(method)) {
      value = Math.random();
      arguments[arguments.length - 1] = (Supplier<Object>) () -> {
        duringTaskExecution.run();
        return value;
      };
    } else {
      value = null;
      arguments[arguments.length - 1] = duringTaskExecution;
    }
    try {
      Object result;
      doingCallLatch.countDown();
      try {
        result = method.invoke(executor, arguments);
      } finally {
        callCompletedLatch.countDown();
      }
      if (Objects.equals(value, result)) {
        return Outcome.SUCCESS;
      } else {
        throw new AssertionError(String.format(
            "wrong return value: expected <%s> but was <%s>", value, result));
      }
    } catch (InvocationTargetException targetException) {
      Throwable actualException = targetException.getTargetException();
      if (actualException instanceof InterruptedException) {
        return Outcome.INTERRUPT;
      } else if (actualException instanceof TimeoutException) {
        return Outcome.TIMEOUT;
      } else if (actualException instanceof RejectedExecutionException) {
        return Outcome.REJECTED;
      } else {
        throw new AssertionError("unexpected exception", targetException);
      }
    } catch (IllegalAccessException e) {
      throw new AssertionError("unexpected exception", e);
    }
  }

  private void enterSatisfyGuardAndLeaveInCurrentThread() {
    executor.execute(() -> guard.setSatisfied(true));
  }

  private void enterAndRemainOccupyingInAnotherThread() {
    CountDownLatch enteredLatch = new CountDownLatch(1);
    startThread(() -> executor.execute(() -> {
      enteredLatch.countDown();
      awaitUninterruptibly(tearDownLatch);
      guard.setSatisfied(true);
    }));
    awaitUninterruptibly(enteredLatch);
  }

  static void startThread(Runnable runnable) {
    Thread thread = new Thread(runnable);
    thread.setDaemon(true);
    thread.start();
  }

}
