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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.AbstractOwnableSynchronizer;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * An executor that executes each task while holding an exclusive lock, optionally waiting for a
 * guard condition to become true before executing the task. Tasks will usually be executed in the
 * calling thread, but waiting threads may execute tasks for each other.
 *
 * <p>This class is intended as a replacement for {@link java.util.concurrent.locks.ReentrantLock
 * ReentrantLock}. Code using {@code GuardedExecutor} is significantly less error-prone and more
 * readable than code using {@code ReentrantLock} because all signalling is <a
 * href="https://en.wikipedia.org/wiki/Monitor_(synchronization)#Implicit_signaling">implicit</a>.
 * {@code GuardedExecutor} is also perfectly fair, while consistently beating the performance of a
 * {@code ReentrantLock} using the "fair" ordering policy and even beating the performance of a
 * {@code ReentrantLock} using the default non-fair ordering policy for many workloads.
 *
 * <p>The exclusive lock is completely encapsulated within the executor, so the only way to perform
 * any work protected by the lock is by submitting tasks via the various {@code executeXXX} and
 * {@code tryExecuteXXX} methods. A task may not be submitted to the executor from within another
 * executing task (that is, the lock is not reentrant). The encapsulated lock provides the same
 * synchronization semantics as the built-in Java language synchronization primitives.
 *
 * <p>Every guard and task submitted to this executor will be executed only when holding the
 * encapsulated exclusive lock, though they may be executed in arbitrary other threads that are also
 * executing, or attempting to execute, tasks using the same executor, as part of the automatic
 * signalling mechanism.
 *
 * <p><b>You must ensure</b> that any change of state that affects the value of any guard submitted
 * to this executor only occurs within a task executed by the same executor. Every guard must
 * consistently return the same value from the time any task finishes executing until the next task
 * starts executing. This restriction implies that guards themselves must not modify any such state.
 *
 * <p>An instance of this class is serializable, but all of its internal state (including pending
 * tasks) is considered transient and not included in the serialized form.
 *
 * <h2>Limitations</h2>
 *
 * <p>While this class is a potential replacement for many uses of {@link
 * java.util.concurrent.locks.ReentrantLock ReentrantLock}, two limitations reduce its ability to be
 * dropped into some contexts.
 *
 * <p><b>Tasks cannot execute additional tasks:</b> Since a {@code GuardedExecutor} does not allow
 * tasks to be executed from within other tasks, any code that depends on the reentrant nature of
 * {@code ReentrantLock} will have to be rewritten carefully when using {@code GuardedExecutor}.
 *
 * <p><b>Tasks might execute in other threads:</b> Since a {@code GuardedExecutor} will execute
 * tasks in other threads in order to optimize throughput under heavy load, any code that accesses
 * thread-local state will have to be rewritten carefully when using {@code GuardedExecutor}.
 *
 * <h2>Performance and Fairness Benefits</h2>
 *
 * <p>{@code GuardedExecutor} offers several performance and fairness benefits relative to {@code
 * synchronized} and {@code ReentrantLock} due to its implicit signalling design. A {@code
 * GuardedExecutor} tends to be more fair than a "fair" {@code ReentrantLock} and often just as
 * performant as a non-fair one.
 * <ul>
 * <li>With {@code synchronized} or {@code ReentrantLock}, a thread must first block to acquire the
 * lock, then awaken when the lock is available, and then evaluate its guard, and then block again
 * if the guard evaluated as false. With {@code GuardedExecutor}, a thread will often block just a
 * single time and find its task already executed when it awakens.
 * <li>With {@code synchronized} or {@code ReentrantLock}, a thread that encounters a timeout or
 * interrupt must reacquire the lock before continuing, which is itself an untimed and
 * uninterruptible action. With {@code GuardedExecutor}, such a thread continues immediately without
 * blocking again.
 * <li>Even with a "fair" {@code ReentrantLock}, a thread will lose its place in the queue of
 * threads attempting to acquire the lock whenever it blocks on a condition. With {@code
 * GuardedExecutor}, a thread is assigned a position in the queue that is strictly honored right up
 * until its task is actually executed.
 * </ul>
 *
 * <p>Note that {@code GuardedExecutor} counts timeout duration starting from the first time the
 * calling thread actually blocks, and only checks for timeout immediately before blocking, in order
 * to minimize expensive clock accesses. Therefore if a thread ends up executing a long-running task
 * from another thread it will not timeout immediately. However, this is no worse than {@code
 * synchronized} or {@code ReentrantLock} for the reason pointed out above&mdash;if such a thread
 * times out while waiting because another thread is executing a long-running task, it has to wait
 * just as long for that task to complete before being able to acquire the lock and respond to its
 * own timeout.
 *
 * <h2>Comparison with {@code synchronized} and {@code ReentrantLock}</h2>
 *
 * <p>The following examples show a simple threadsafe holder expressed using {@code synchronized},
 * {@link java.util.concurrent.locks.ReentrantLock ReentrantLock}, and {@code GuardedExecutor}.
 *
 * <h3>{@code synchronized}</h3>
 *
 * <p>This version is fairly concise, largely because the synchronization mechanism used is built
 * into the language and runtime. But the programmer has to remember to avoid a couple of common
 * bugs: The {@code wait()} must be inside a {@code while} instead of an {@code if}, and {@code
 * notifyAll()} must be used instead of {@code notify()} because there are two different logical
 * conditions being awaited. <pre>   {@code
 *
 *   public class SafeBox<V> {
 *     private V value;
 *
 *     public synchronized V get() throws InterruptedException {
 *       while (value == null) {
 *         wait();
 *       }
 *       V result = value;
 *       value = null;
 *       notifyAll();
 *       return result;
 *     }
 *
 *     public synchronized void set(V newValue) throws InterruptedException {
 *       Objects.requireNonNull(newValue);
 *       while (value != null) {
 *         wait();
 *       }
 *       value = newValue;
 *       notifyAll();
 *     }
 *   }}</pre>
 *
 * <h3>{@code ReentrantLock}</h3>
 *
 * <p>This version is much more verbose than the {@code synchronized} version, and still suffers
 * from the need for the programmer to remember to use {@code while} instead of {@code if}.
 * However, one advantage is that we can introduce two separate {@code Condition} objects, which
 * allows us to use {@code signal()} instead of {@code signalAll()}, which may be a performance
 * benefit. <pre>   {@code
 *
 *   public class SafeBox<V> {
 *     private final ReentrantLock lock = new ReentrantLock();
 *     private final Condition valuePresent = lock.newCondition();
 *     private final Condition valueAbsent = lock.newCondition();
 *     private V value;
 *
 *     public V get() throws InterruptedException {
 *       lock.lock();
 *       try {
 *         while (value == null) {
 *           valuePresent.await();
 *         }
 *         V result = value;
 *         value = null;
 *         valueAbsent.signal();
 *         return result;
 *       } finally {
 *         lock.unlock();
 *       }
 *     }
 *
 *     public void set(V newValue) throws InterruptedException {
 *       Objects.requireNonNull(newValue);
 *       lock.lock();
 *       try {
 *         while (value != null) {
 *           valueAbsent.await();
 *         }
 *         value = newValue;
 *         valuePresent.signal();
 *       } finally {
 *         lock.unlock();
 *       }
 *     }
 *   }}</pre>
 *
 * <h3>{@code GuardedExecutor}</h3>
 *
 * <p>This version is the shortest of all. {@code GuardedExecutor} implements the same efficient
 * signalling as we had to hand-code in the {@code ReentrantLock} version above, and the programmer
 * no longer has to hand-code the wait loop, and therefore doesn't have to remember to use {@code
 * while} instead of {@code if}. The need to explicitly unlock the lock in a {@code finally} block
 * is also eliminated. <pre>   {@code
 *
 *   public class SafeBox<V> {
 *     private final GuardedExecutor executor = new GuardedExecutor();
 *     private V value;
 *
 *     public V get() throws InterruptedException {
 *       return executor.executeWhen(() -> value != null, () -> {
 *         V result = value;
 *         value = null;
 *         return result;
 *       });
 *     }
 *
 *     public void set(V newValue) throws InterruptedException {
 *       Objects.requireNonNull(newValue);
 *       executor.executeWhen(() -> value == null, () -> {
 *         value = newValue;
 *       });
 *     }
 *   }}</pre>
 *
 * <h2>Memory Consistency Effects</h2>
 *
 * <p>A {@code GuardedExecutor} imposes a total ordering over all task executions and a partial
 * ordering over all guard executions. A task is executed at most once. Any single execution of a
 * guard is ordered between two immediately adjacent task executions, but a guard may be executed
 * any number of times before or after its associated task and no ordering may be assumed among
 * guard executions except as implied transitively by their ordering relative to task executions.
 * The implementation offers the following <i>happens-before</i> guarantees:
 * <ul>
 * <li>The completion of any task execution (whether normal or exceptional) <i>happens-before</i>
 * the execution of any subsequent task or guard by the same executor.
 * <li>The completion of any guard execution (whether normal or exceptional) <i>happens-before</i>
 * the execution of any subsequent task by the same executor.
 * <li>If a task with an associated guard is ever executed, then that guard was executed at least
 * once such that (a) completion of the immediately preceding task <i>happens-before</i> that
 * execution of the guard, (b) that execution of the guard returned {@code true}, and (c) the return
 * of that execution of the guard <i>happens-before</i> the execution of its associated task.
 * <li>The call to any execution method <i>happens-before</i> any execution of the given task or
 * guard.
 * <li>If an execution method returns normally, the given task was executed exactly once and the
 * completion of that execution <i>happens-before</i> the return from the execution method.
 * <li>If an execution method propagates a throwable thrown by the given guard, either by rethrowing
 * the same throwable or by wrapping it before rethrowing, then (a) the completion of that execution
 * of the guard <i>happens-before</i> the execution method propagating that throwable and (b) the
 * given task was not and never will be executed.
 * <li>If an execution method propagates a throwable thrown by the given task, either by rethrowing
 * the same throwable or by wrapping it before rethrowing, then the completion of that execution of
 * the task <i>happens-before</i> the execution method propagating that throwable.
 * <li>If an execution method throws {@code InterruptedException}, {@code TimeoutException}, or
 * {@code RejectedExecutionException} (unless propagating it from an execution of the given task),
 * the given task was not and never will be executed.
 * <li>Even after an execution method returns or throws for any reason, the given guard may still be
 * executed any number of additional times with no necessary ordering relative to the execution
 * method returning or throwing.
 * <li>If an execution method propagates an {@code Error}, the given task might or might not have
 * been executed, and might still be executed in the future, but it is guaranteed to execute at most
 * once.
 * </ul>
 *
 * <h2>Exception Propagation</h2>
 *
 * <p>If a guard or task throws an {@linkplain RuntimeException exception} or {@linkplain Error
 * error} while executing in the same thread that submitted it for execution, that same exception or
 * error will be propagated as-is from the execution method.
 *
 * <p>If a guard or task throws an {@linkplain RuntimeException exception} while executing in any
 * thread <i>other</i> than the one that submitted it for execution, that exception will be wrapped
 * in a new {@link CancellationException} and rethrown from the submitting thread's execution
 * method, but the executing thread's execution method will otherwise continue executing other
 * guards and tasks normally.
 *
 * <p>If a guard or task throws an {@linkplain Error error} while executing in any thread other than
 * the one that submitted it for execution, that error will be wrapped in a new {@link
 * CancellationException} and rethrown from the submitting thread's execution method, <b>and</b> the
 * original error will be propagated as-is from the executing thread's own execution method.
 *
 * @author Justin T. Sampson
 */
@SuppressWarnings("unused") // all execute methods are called reflectively by GeneratedGuardedExecutorTest
public final class GuardedExecutor extends AbstractOwnableSynchronizer
    implements Executor, Serializable {

  private final int pseudoSpins;

  /**
   * Constructs a new {@code GuardedExecutor}.
   */
  public GuardedExecutor() {
    this(0);
  }

  public GuardedExecutor(int pseudoSpins) {
    this.pseudoSpins = pseudoSpins;
  }

  // ===============================================================================================
  // Main Public API
  // ===============================================================================================

  /**
   * Executes the task before returning.
   *
   * @param task the task to execute
   * @throws RejectedExecutionException if this thread is already executing a task in this executor
   * @throws NullPointerException if task is null
   */
  @Override
  public final void execute(Runnable task) {
    doExecuteUninterruptibly(requireNonNull(task), null);
  }

  /**
   * Executes the task before returning.
   *
   * @param task the task to execute
   * @return the value supplied by the task
   * @throws RejectedExecutionException if this thread is already executing a task in this executor
   * @throws NullPointerException if task is null
   */
  @SuppressWarnings("unchecked")
  public final <V> V execute(Supplier<V> task) {
    return (V) doExecuteUninterruptibly(null, requireNonNull(task));
  }

  /**
   * Executes the task before returning unless the current thread is interrupted.
   *
   * @param task the task to execute
   * @throws InterruptedException if this thread is interrupted before the task is executed
   * @throws RejectedExecutionException if this thread is already executing a task in this executor
   * @throws NullPointerException if task is null
   */
  public final void executeInterruptibly(Runnable task) throws InterruptedException {
    doExecuteInterruptibly(null, requireNonNull(task), null);
  }

  /**
   * Executes the task before returning unless the current thread is interrupted.
   *
   * @param task the task to execute
   * @return the value supplied by the task
   * @throws InterruptedException if this thread is interrupted before the task is executed
   * @throws RejectedExecutionException if this thread is already executing a task in this executor
   * @throws NullPointerException if task is null
   */
  @SuppressWarnings("unchecked")
  public final <V> V executeInterruptibly(Supplier<V> task) throws InterruptedException {
    return (V) doExecuteInterruptibly(null, null, requireNonNull(task));
  }

  /**
   * Executes the task before returning, first waiting for the guard to be satisfied, unless the
   * current thread is interrupted.
   *
   * @param guard the guard to evaluate
   * @param task the task to execute
   * @throws InterruptedException if this thread is interrupted before the task is executed
   * @throws RejectedExecutionException if this thread is already executing a task in this executor
   * @throws NullPointerException if guard or task is null
   */
  public final void executeWhen(BooleanSupplier guard, Runnable task) throws InterruptedException {
    doExecuteInterruptibly(requireNonNull(guard), requireNonNull(task), null);
  }

  /**
   * Executes the task before returning, first waiting for the guard to be satisfied, unless the
   * current thread is interrupted.
   *
   * @param guard the guard to evaluate
   * @param task the task to execute
   * @return the value supplied by the task
   * @throws InterruptedException if this thread is interrupted before the task is executed
   * @throws RejectedExecutionException if this thread is already executing a task in this executor
   * @throws NullPointerException if guard or task is null
   */
  @SuppressWarnings("unchecked")
  public final <V> V executeWhen(BooleanSupplier guard, Supplier<V> task)
      throws InterruptedException {
    return (V) doExecuteInterruptibly(requireNonNull(guard), null, requireNonNull(task));
  }

  /**
   * Executes the task before returning, if it is possible to do so immediately.
   *
   * @param task the task to execute
   * @throws TimeoutException if executing the task would require blocking
   * @throws RejectedExecutionException if this thread is already executing a task in this executor
   * @throws NullPointerException if task is null
   */
  public final void tryExecute(Runnable task) throws TimeoutException {
    doExecuteImmediately(null, requireNonNull(task), null);
  }

  /**
   * Executes the task before returning, if it is possible to do so immediately.
   *
   * @param task the task to execute
   * @return the value supplied by the task
   * @throws TimeoutException if executing the task would require blocking
   * @throws RejectedExecutionException if this thread is already executing a task in this executor
   * @throws NullPointerException if task is null
   */
  @SuppressWarnings("unchecked")
  public final <V> V tryExecute(Supplier<V> task) throws TimeoutException {
    return (V) doExecuteImmediately(null, null, requireNonNull(task));
  }

  /**
   * Executes the task before returning, if it is possible to do so immediately and the guard is
   * satisfied.
   *
   * @param guard the guard to evaluate
   * @param task the task to execute
   * @throws TimeoutException if executing the task would require blocking
   * @throws RejectedExecutionException if this thread is already executing a task in this executor
   * @throws NullPointerException if guard or task is null
   */
  public final void tryExecuteIf(BooleanSupplier guard, Runnable task) throws TimeoutException {
    doExecuteImmediately(requireNonNull(guard), requireNonNull(task), null);
  }

  /**
   * Executes the task before returning, if it is possible to do so immediately and the guard is
   * satisfied.
   *
   * @param guard the guard to evaluate
   * @param task the task to execute
   * @return the value supplied by the task
   * @throws TimeoutException if executing the task would require blocking
   * @throws RejectedExecutionException if this thread is already executing a task in this executor
   * @throws NullPointerException if guard or task is null
   */
  @SuppressWarnings("unchecked")
  public final <V> V tryExecuteIf(BooleanSupplier guard, Supplier<V> task) throws TimeoutException {
    return (V) doExecuteImmediately(requireNonNull(guard), null, requireNonNull(task));
  }

  /**
   * Executes the task before returning, blocking at most the given time or until the current thread
   * is interrupted.
   *
   * @param time the maximum time to block
   * @param unit the unit of the time parameter
   * @param task the task to execute
   * @throws TimeoutException if executing the task would require blocking longer than specified
   * @throws InterruptedException if this thread is interrupted before the task is executed
   * @throws RejectedExecutionException if this thread is already executing a task in this executor
   * @throws NullPointerException if task is null
   */
  public final void tryExecute(long time, TimeUnit unit, Runnable task)
      throws TimeoutException, InterruptedException {
    doExecuteWithTimeout(null, time, requireNonNull(unit), requireNonNull(task), null);
  }

  /**
   * Executes the task before returning, blocking at most the given time or until the current thread
   * is interrupted.
   *
   * @param time the maximum time to block
   * @param unit the unit of the time parameter
   * @param task the task to execute
   * @return the value supplied by the task
   * @throws TimeoutException if executing the task would require blocking longer than specified
   * @throws InterruptedException if this thread is interrupted before the task is executed
   * @throws RejectedExecutionException if this thread is already executing a task in this executor
   * @throws NullPointerException if task is null
   */
  @SuppressWarnings("unchecked")
  public final <V> V tryExecute(long time, TimeUnit unit, Supplier<V> task)
      throws TimeoutException, InterruptedException {
    return (V) doExecuteWithTimeout(null, time, requireNonNull(unit), null, requireNonNull(task));
  }

  /**
   * Executes the task before returning, first waiting for the guard to be satisfied, blocking at
   * most the given time or until the current thread is interrupted.
   *
   * @param guard the guard to evaluate
   * @param time the maximum time to block
   * @param unit the unit of the time parameter
   * @param task the task to execute
   * @throws TimeoutException if executing the task would require blocking longer than specified
   * @throws InterruptedException if this thread is interrupted before the task is executed
   * @throws RejectedExecutionException if this thread is already executing a task in this executor
   * @throws NullPointerException if guard or task is null
   */
  public final void tryExecuteWhen(BooleanSupplier guard, long time, TimeUnit unit, Runnable task)
      throws TimeoutException, InterruptedException {
    doExecuteWithTimeout(
        requireNonNull(guard), time, requireNonNull(unit), requireNonNull(task), null);
  }

  /**
   * Executes the task before returning, first waiting for the guard to be satisfied, blocking at
   * most the given time or until the current thread is interrupted.
   *
   * @param guard the guard to evaluate
   * @param time the maximum time to block
   * @param unit the unit of the time parameter
   * @param task the task to execute
   * @return the value supplied by the task
   * @throws TimeoutException if executing the task would require blocking longer than specified
   * @throws InterruptedException if this thread is interrupted before the task is executed
   * @throws RejectedExecutionException if this thread is already executing a task in this executor
   * @throws NullPointerException if guard or task is null
   */
  @SuppressWarnings("unchecked")
  public final <V> V tryExecuteWhen(BooleanSupplier guard, long time, TimeUnit unit,
      Supplier<V> task) throws TimeoutException, InterruptedException {
    return (V) doExecuteWithTimeout(
        requireNonNull(guard), time, requireNonNull(unit), null, requireNonNull(task));
  }

  // ===============================================================================================
  // Convenience API
  // ===============================================================================================

  /**
   * Ensures that tasks with ill-behaved guards, that have become satisfied by events outside of
   * task execution within this executor, do get executed. This method may execute guards and tasks
   * of other threads, but never blocks. <b>It should not be necessary to call this method,</b> but
   * it is made available for specialized use cases.
   *
   * @throws RejectedExecutionException if this thread is already executing a task in this executor
   */
  public final void proceed() {
    try {
      tryExecute(() -> {});
    } catch (TimeoutException e) {
      // Ignore, because this means that another thread is executing and will ensure progress.
    }
  }

  /**
   * Waits for the guard to become satisfied before proceeding, unless the current thread is
   * interrupted. Cannot be called from within an executing task, but may be used to wait for a
   * guard <i>without</i> providing an associated task.
   *
   * @param guard the guard to evaluate
   * @throws InterruptedException if this thread is interrupted before the guard becomes satisfied
   * @throws RejectedExecutionException if this thread is already executing a task in this executor
   * @throws NullPointerException if guard is null
   */
  public final void proceedWhen(BooleanSupplier guard) throws InterruptedException {
    executeWhen(guard, () -> {});
  }

  /**
   * Waits for the guard to become satisfied before proceeding, blocking at most the given time or
   * until the current thread is interrupted. Cannot be called from within an executing task, but
   * may be used to wait for a guard <i>without</i> providing an associated task.
   *
   * @param guard the guard to evaluate
   * @param time the maximum time to block
   * @param unit the unit of the time parameter
   * @throws TimeoutException if waiting for the guard would require blocking longer than specified
   * @throws InterruptedException if this thread is interrupted before the guard becomes satisfied
   * @throws RejectedExecutionException if this thread is already executing a task in this executor
   * @throws NullPointerException if guard is null
   */
  public final void tryProceedWhen(BooleanSupplier guard, long time, TimeUnit unit)
      throws TimeoutException, InterruptedException {
    tryExecuteWhen(guard, time, unit, () -> {});
  }

  // ===============================================================================================
  // Monitoring API
  // ===============================================================================================

  /**
   * Returns the thread currently executing guards and tasks in this executor, or {@code null} if
   * there is none. The returned value is a best-effort approximation:
   *
   * <ul>
   * <li>If there is no thread currently executing, this method will definitely return {@code null}.
   * <li>If the current thread is currently executing, this method will definitely return it.
   * <li>If some other thread is currently executing, this method will either return that thread or
   * return {@code null}.
   * </ul>
   *
   * @return either the thread currently executing in this executor or {@code null}
   */
  public final Thread getExecutingThread() {
    return state == UNLOCKED ? null : getExclusiveOwnerThread();
  }

  /**
   * Returns the threads currently waiting for tasks to execute in this executor. The returned value
   * is a best-effort approximation, as threads may start or stop waiting while this collection is
   * being constructed. The returned collection is in no particular order.
   *
   * @return the collection of threads currently waiting
   */
  public final Collection<Thread> getQueuedThreads() {
    List<Thread> threads = new ArrayList<>();
    for (Node node = tail; node != null; node = node.prev) {
      final Thread thread = node.thread;
      if (thread != null && node.status == WAITING) {
        threads.add(thread);
      }
    }
    return threads;
  }

  /**
   * Returns the number of threads currently waiting for tasks to execute in this executor. The
   * returned value is a best-effort approximation, as threads may start or stop waiting while this
   * number is being calculated.
   *
   * @return the number of threads currently waiting
   */
  public final int getQueueLength() {
    int length = 0;
    for (Node node = tail; node != null; node = node.prev) {
      if (node.status == WAITING) {
        length++;
      }
    }
    return length;
  }

  /**
   * Determines whether the given thread is currently waiting for a task to execute in this
   * executor. The returned value is a best-effort approximation, as the given thread may start or
   * stop waiting while the result is being determined.
   *
   * @return {@code true} if the given thread is currently waiting
   */
  public final boolean hasQueuedThread(Thread thread) {
    requireNonNull(thread);
    for (Node node = tail; node != null; node = node.prev) {
      if (node.thread == thread && node.status == WAITING) {
        return true;
      }
    }
    return false;
  }

  /**
   * Determines whether any threads are currently waiting for tasks to execute in this executor. The
   * returned value is a best-effort approximation, as threads may start or stop waiting while the
   * result is being determined.
   *
   * @return {@code true} if any threads are currently waiting
   */
  public final boolean hasQueuedThreads() {
    for (Node node = tail; node != null; node = node.prev) {
      if (node.status == WAITING) {
        return true;
      }
    }
    return false;
  }

  /**
   * Determines whether any thread is currently executing guards and tasks in this executor.
   *
   * @return {@code true} if any thread is currently executing
   */
  public final boolean isExecuting() {
    return state == LOCKED;
  }

  /**
   * Determines whether the current thread is currently executing guards and tasks in this executor.
   *
   * @return {@code true} if the current thread is currently executing
   */
  public final boolean isExecutingInCurrentThread() {
    return getExclusiveOwnerThread() == Thread.currentThread();
  }

  /**
   * Returns a string identifying this executor, as well as its execution state. The state, in
   * brackets, includes either the string {@code "Not executing"} or the string {@code "Executing
   * in thread"} followed by the {@linkplain Thread#getName name} of the owning thread.
   *
   * <p>The execution state is a best-effort approximation in the same manner as {@link
   * #getExecutingThread()}.
   *
   * @return a string identifying this executor
   */
  @Override
  public final String toString() {
    final Thread owner = getExecutingThread();
    return super.toString()
        + (owner == null ? "[Not executing]" : "[Executing in thread " + owner.getName() + "]");
  }

  // ===============================================================================================
  // Top-Level Private Methods
  // ===============================================================================================

  /**
   * Attempt to execute a task, optionally waiting for a guard to become satisfied and obeying a
   * timeout. Interrupts are serviced by throwing InterruptedException.
   *
   * @param guard the guard to wait for, or null if task is unconditional
   * @param time the maximum time to wait (zero or negative means never park)
   * @param unit the unit of the time parameter
   * @param runnable the task, if it is a {@code Runnable} (null if the task is a {@code Supplier})
   * @param supplier the task, if it is a {@code Supplier} (null if the task is a {@code Runnable})
   * @return the value returned by the task, if the task is a {@code Supplier} (null if the task is
   *     a {@code Runnable})
   * @throws TimeoutException the given timeout has elapsed, either because the lock was unavailable
   *     or because the guard was unsatisfied
   * @throws InterruptedException the current thread was interrupted, either before or during the
   *     attempt to acquire the lock or wait for the guard
   */
  private Object doExecuteWithTimeout(
      final BooleanSupplier guard,
      final long time,
      final TimeUnit unit,
      final Runnable runnable,
      final Supplier<?> supplier)
      throws TimeoutException, InterruptedException {

    if (Thread.interrupted()) {
      throw new InterruptedException();
    }

    final Object initialResult = initialAcquireAndExecute(guard, runnable, supplier, time > 0L);

    if (initialResult == NOT_EXECUTED_YET) {
      throw new TimeoutException();
    } else if (initialResult == null || initialResult.getClass() != Node.class) {
      return initialResult;
    }

    assert time > 0L;

    final Node currentNode = (Node) initialResult;

    boolean startedTiming = false;
    long lastNanoTime = 0L; // only valid if startedTiming
    long remainingNanos = 0L; // only valid if startedTiming
    int pseudoSpins = this.pseudoSpins;

    while (true) {
      final Object subsequentResult =
          subsequentAcquireAndExecute(currentNode, guard, runnable, supplier);

      if (subsequentResult != NOT_EXECUTED_YET) {
        return subsequentResult;
      }

      if (!startedTiming) {
        lastNanoTime = System.nanoTime();
        remainingNanos = unit.toNanos(time);
        startedTiming = true;
      } else {
        long elapsedNanos = System.nanoTime() - lastNanoTime;
        if (elapsedNanos > 0L) {
          lastNanoTime += elapsedNanos;
          remainingNanos -= elapsedNanos;
        }
      }

      if (remainingNanos <= 0) {
        final Object lastChanceResult = consumeResult(currentNode, true);

        if (lastChanceResult != NOT_EXECUTED_YET) {
          return lastChanceResult;
        } else {
          throw new TimeoutException();
        }
      } else if (pseudoSpins > 0) {
        Thread.onSpinWait();
        pseudoSpins--;
      } else if (remainingNanos > SPIN_FOR_TIMEOUT_THRESHOLD) {
        LockSupport.parkNanos(this, remainingNanos);
      }

      final Object consumedResult = consumeResultOrInterrupt(currentNode);

      if (consumedResult != NOT_EXECUTED_YET) {
        return consumedResult;
      }
    }
  }

  /**
   * Attempt to execute a task, optionally checking for a guard to be satisfied, only if it can be
   * done without parking. Interrupts are implicitly ignored by never parking.
   *
   * @param guard the guard to wait for, or null if task is unconditional
   * @param runnable the task, if it is a {@code Runnable} (null if the task is a {@code Supplier})
   * @param supplier the task, if it is a {@code Supplier} (null if the task is a {@code Runnable})
   * @return the value returned by the task, if the task is a {@code Supplier} (null if the task is
   *     a {@code Runnable})
   * @throws TimeoutException the lock was unavailable or the guard was unsatisfied
   */
  private Object doExecuteImmediately(
      final BooleanSupplier guard,
      final Runnable runnable,
      final Supplier<?> supplier)
      throws TimeoutException {

    final Object initialResult = initialAcquireAndExecute(guard, runnable, supplier, false);

    if (initialResult != NOT_EXECUTED_YET) {
      return initialResult;
    } else {
      throw new TimeoutException();
    }
  }

  /**
   * Attempt to execute a task, optionally waiting for a guard to become satisfied. Interrupts are
   * serviced by throwing InterruptedException.
   *
   * @param guard the guard to wait for, or null if task is unconditional
   * @param runnable the task, if it is a {@code Runnable} (null if the task is a {@code Supplier})
   * @param supplier the task, if it is a {@code Supplier} (null if the task is a {@code Runnable})
   * @return the value returned by the task, if the task is a {@code Supplier} (null if the task is
   *     a {@code Runnable})
   * @throws InterruptedException the current thread was interrupted, either before or during the
   *     attempt to acquire the lock or wait for the guard
   */
  private Object doExecuteInterruptibly(
      final BooleanSupplier guard,
      final Runnable runnable,
      final Supplier<?> supplier)
      throws InterruptedException {

    if (Thread.interrupted()) {
      throw new InterruptedException();
    }

    final Object initialResult = initialAcquireAndExecute(guard, runnable, supplier, true);

    assert initialResult != NOT_EXECUTED_YET;

    if (initialResult == null || initialResult.getClass() != Node.class) {
      return initialResult;
    }

    final Node currentNode = (Node) initialResult;
    int pseudoSpins = this.pseudoSpins;

    while (true) {
      final Object subsequentResult =
          subsequentAcquireAndExecute(currentNode, guard, runnable, supplier);

      if (subsequentResult != NOT_EXECUTED_YET) {
        return subsequentResult;
      }

      if (pseudoSpins > 0) {
        Thread.onSpinWait();
        pseudoSpins--;
      } else {
        LockSupport.park(this);
      }

      final Object consumedResult = consumeResultOrInterrupt(currentNode);

      if (consumedResult != NOT_EXECUTED_YET) {
        return consumedResult;
      }
    }
  }

  /**
   * Execute a task. Interrupts are ignored by restoring the interrupt status on exit from the
   * method.
   *
   * @param runnable the task, if it is a {@code Runnable} (null if the task is a {@code Supplier})
   * @param supplier the task, if it is a {@code Supplier} (null if the task is a {@code Runnable})
   * @return the value returned by the task, if the task is a {@code Supplier} (null if the task is
   *     a {@code Runnable})
   */
  private Object doExecuteUninterruptibly(
      final Runnable runnable,
      final Supplier<?> supplier) {

    final Object initialResult = initialAcquireAndExecute(null, runnable, supplier, true);

    assert initialResult != NOT_EXECUTED_YET;

    if (initialResult == null || initialResult.getClass() != Node.class) {
      return initialResult;
    }

    final Node currentNode = (Node) initialResult;
    int pseudoSpins = this.pseudoSpins;

    boolean interrupted = false;
    try {
      while (true) {
        final Object subsequentResult =
            subsequentAcquireAndExecute(currentNode, null, runnable, supplier);

        if (subsequentResult != NOT_EXECUTED_YET) {
          return subsequentResult;
        }

        if (Thread.interrupted()) {
          interrupted = true;
        }

        if (pseudoSpins > 0) {
          Thread.onSpinWait();
          pseudoSpins--;
        } else {
          LockSupport.park(this);
        }

        final Object consumedResult = consumeResult(currentNode, false);

        if (consumedResult != NOT_EXECUTED_YET) {
          return consumedResult;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  // ===============================================================================================
  // Lock Acquisition Methods
  // ===============================================================================================

  /**
   * We want to attempt to acquire the lock right away, before adding a node to the queue. Since
   * this thread will execute any tasks before it in the queue, there is no reason NOT to "barge"
   * the lock like this. The only catch is that the thread won't have an "official" position in the
   * queue until it DOES add a node for itself, so we have to be careful about the difference
   * between the "tentative previous node" (the tail at the moment of acquiring the lock) and the
   * "actual previous node" (the tail at the moment of adding a new node).
   *
   * @return either NOT_EXECUTED_YET (only if addToQueue is false), a new Node instance (only if
   *         addToQueue is true), or the result of executing the task
   */
  private Object initialAcquireAndExecute(
      final BooleanSupplier guard,
      final Runnable runnable,
      final Supplier<?> supplier,
      final boolean addToQueue) {

    if (tryAcquireLock()) {
      Node currentNode = null;
      boolean throwingWithoutExecuting = true;
      try {
        final Node tentativePrev = this.tail;
        executeTasksUpTo(tentativePrev, null);

        boolean satisfied = (guard == null || guard.getAsBoolean());
        if (!satisfied) {
          if (addToQueue) {
            currentNode = addNewNodeAtTail(guard, runnable, supplier);
            final Node actualPrev = currentNode.prev;
            if (actualPrev != tentativePrev) {
              executeTasksUpTo(actualPrev, tentativePrev);
              satisfied = guard.getAsBoolean();
            }
          } else {
            return NOT_EXECUTED_YET;
          }
        }

        if (satisfied) {
          if (currentNode != null) {
            cancelBeforeExecuting(currentNode);
          }
          throwingWithoutExecuting = false;
          if (runnable != null) {
            runnable.run();
            return null;
          } else {
            return supplier.get();
          }
        } else {
          throwingWithoutExecuting = false;
          return currentNode;
        }
      } finally {
        if (throwingWithoutExecuting && currentNode != null) {
          cancelBecauseThrowing(currentNode);
        }
        releaseLock(currentNode);
      }
    } else if (getExclusiveOwnerThread() == Thread.currentThread()) {
      throw new RejectedExecutionException();
    } else if (addToQueue) {
      return addNewNodeAtTail(guard, runnable, supplier);
    } else {
      return NOT_EXECUTED_YET;
    }
  }

  /**
   * Attempt to acquire the lock, and execute tasks from the queue in order, up to and including
   * this thread's task, if their guards are satisfied.
   *
   * @return either NOT_EXECUTED_YET or the result of executing the task
   */
  private Object subsequentAcquireAndExecute(
      final Node currentNode,
      final BooleanSupplier guard,
      final Runnable runnable,
      final Supplier<?> supplier) {

    if (tryAcquireLock()) {
      boolean throwingWithoutExecuting = true;
      try {
        final Object consumedResult = consumeResult(currentNode, false);
        if (consumedResult != NOT_EXECUTED_YET) {
          throwingWithoutExecuting = false;
          return consumedResult;
        } else {
          executeTasksUpTo(currentNode.prev, null);
          if (guard == null || guard.getAsBoolean()) {
            cancelBeforeExecuting(currentNode);
            throwingWithoutExecuting = false;
            if (runnable != null) {
              runnable.run();
              return null;
            } else {
              return supplier.get();
            }
          } else {
            throwingWithoutExecuting = false;
            return NOT_EXECUTED_YET;
          }
        }
      } finally {
        if (throwingWithoutExecuting) {
          cancelBecauseThrowing(currentNode);
        }
        releaseLock(currentNode);
      }
    } else {
      return NOT_EXECUTED_YET;
    }
  }

  // ===============================================================================================
  // Inner Execution Methods
  // ===============================================================================================

  private void executeTasksUpTo(Node last, Node priorLast) {
    if (last != null) {
      Node head = updateNextLinksUpTo(last);
      executeTasksFromHead(head, priorLast);
      cleanUpCompletedNodes(head, last);
    }
  }

  /**
   * Traverse backward, updating 'next' links and identifying the head of the queue.
   * The 'next' links form a linked list starting at the returned node and ending at
   * the given node.
   */
  private Node updateNextLinksUpTo(Node last) {
    Node next = last;
    for (Node curr = last.prev; curr != null; next = curr, curr = curr.prev) {
      curr.next = next;
    }
    last.next = null;
    return next;
  }

  /**
   * Actually execute nodes in queue order, starting from head and following 'next' links.
   */
  private void executeTasksFromHead(final Node head, final Node priorLast) {
    Node runningHead = head;
    Node curr = priorLast != null ? priorLast.next : runningHead;

    while (curr != null) {
      final boolean executed = evaluateGuard(curr) && attemptExecute(curr);

      if (curr == runningHead && curr.status != WAITING) {
        curr = runningHead = curr.next;
      } else if (executed) {
        curr = runningHead;
      } else {
        curr = curr.next;
      }
    }
  }

  /**
   * Evaluates the given node's guard, and returns true if the guard is
   * currently satisfied or, possibly, if the node is no longer waiting (and
   * therefore its non-volatile guard field may be nulled out concurrently).
   */
  private boolean evaluateGuard(final Node node) {
    final BooleanSupplier guard = node.guard;
    try {
      return guard == null || guard.getAsBoolean();
    } catch (Throwable t) {
      if (beginExecuting(node)) {
        endExecuting(node, null, t);
      }
      if (t instanceof Error) {
        throw t;
      }
      return false;
    }
  }

  /**
   * Executes the given node's task if it is still waiting.
   */
  private boolean attemptExecute(final Node node) {
    if (beginExecuting(node)) {
      Object returned = null;
      Throwable thrown = null;
      try {
        returned = node.execute();
      } catch (Throwable t) {
        thrown = t;
        if (t instanceof Error) {
          throw t;
        }
      } finally {
        endExecuting(node, returned, thrown);
      }
      return true;
    } else {
      return false;
    }
  }

  /**
   * Traverse forward from 'head', nulling 'next' and updating 'prev' to delete completed nodes.
   * (This cleanup is not essential, so it does not have to be in a finally block.)
   */
  private void cleanUpCompletedNodes(final Node head, final Node last) {
    Node prev = null;
    Node next;
    for (Node curr = head; curr != last; curr = next) {
      next = curr.next;
      curr.next = null;

      if (curr.status == WAITING) {
        curr.prev = prev;
        prev = curr;
      }
    }
    last.prev = prev;
  }

  // ===============================================================================================
  // Private Helper Methods
  // ===============================================================================================

  /**
   * Unpark the waiting thread closest to the tail of the queue, unless that thread is the current
   * thread (which has already determined that there's no work for it to do at this time).
   */
  private void unparkAnotherThreadIfNecessary(final Node currentNode) {
    if (state == LOCKED) {
      // Some thread (possibly the current one) is already holding the lock and will therefore be
      // responsible for unparking some other thread when it releases the lock.
      return;
    }
    for (Node node = this.tail; node != null; node = node.prev) {
      switch (node.status) {
        case WAITING:
          if (node != currentNode) {
            // This read of node.thread is racy -- it might see null if the thread stops waiting
            // right at this moment. However, LockSupport.unpark(null) is simply a no-op, so the
            // call is safe. Returning is still correct because we ensure elsewhere that every
            // transition out of the WAITING state is followed by unparking yet another thread if
            // necessary.
            LockSupport.unpark(node.thread);
          }
          return;
        case EXECUTING:
          // This node is executing in another thread, which will then be responsible for unparking
          // some other thread when it releases the lock.
          return;
        default:
          // This node has already completed so it should not be unparked anymore.
          continue;
      }
    }
  }

  /**
   * Construct a new Node (with the WAITING status) and add it to the end of the queue.
   */
  private Node addNewNodeAtTail(BooleanSupplier guard, Runnable runnable, Supplier<?> supplier) {
    final Node node = new Node(guard, runnable, supplier);

    while (true) {
      final Node oldTail = this.tail;
      node.prev = oldTail;
      if (TAIL.compareAndSet(this, oldTail, node)) {
        return node;
      }
    }
  }

  /**
   * Mark node as cancelled because this thread is about to execute its own task. Only called by the
   * thread that owns the given node, and only while holding the lock.
   */
  private void cancelBeforeExecuting(Node currentNode) {
    currentNode.status = CANCELLED;
    currentNode.complete(null);
  }

  /**
   * Mark node as cancelled because this thread is throwing. Only called by the thread that owns the
   * given node, and only immediately before releasing the lock.
   */
  private void cancelBecauseThrowing(Node currentNode) {
    if (currentNode.status == WAITING) {
      currentNode.status = CANCELLED;
      currentNode.complete(null);
    }
  }

  /**
   * Attempt to mark node as cancelled because this thread doesn't want to wait any longer. Only
   * called by the thread that owns the given node, and only while NOT holding the lock.
   */
  private boolean cancelWithoutExecuting(Node currentNode) {
    if (currentNode.leaveWaitingStatus(CANCELLED)) {
      unparkAnotherThreadIfNecessary(currentNode);
      currentNode.complete(null);
      return true;
    } else {
      return false;
    }
  }

  private boolean beginExecuting(Node node) {
    // It's not necessary to unpark another thread when transitioning out of the WAITING state here
    // because the current thread holds the lock and will unpark another thread when releasing it.
    return node.leaveWaitingStatus(EXECUTING);
  }

  private void endExecuting(Node node, Object returned, Throwable thrown) {
    final Thread thread = node.thread;
    node.complete(thrown != null ? thrown : returned);
    node.status = (thrown != null ? THREW : RETURNED);
    LockSupport.unpark(thread);
  }

  /**
   * Check for conditions that mean this thread shouldn't try to acquire the lock again -- this
   * thread having been interrupted or its task having been executed by another thread.
   */
  private Object consumeResultOrInterrupt(final Node currentNode)
      throws InterruptedException {
    if (Thread.interrupted()) {
      boolean throwingInterruptedException = false;
      try {
        Object result = consumeResult(currentNode, true);
        if (result != NOT_EXECUTED_YET) {
          return result;
        } else {
          throwingInterruptedException = true;
          throw new InterruptedException();
        }
      } finally {
        if (!throwingInterruptedException) {
          Thread.currentThread().interrupt();
        }
      }
    } else {
      return consumeResult(currentNode, false);
    }
  }

  private Object consumeResult(final Node currentNode, final boolean cancelIfNotExecuted) {
    boolean interrupted = false;
    try {
      while (true) {
        final int status = currentNode.status;
        switch (status) {
          case EXECUTING:
            // Wait for other thread to complete execution of this task. It's tempting to spin here,
            // but spinning is problematic if this thread is higher-priority than the one that is
            // executing its task.
            if (Thread.interrupted()) {
              interrupted = true;
            }
            LockSupport.park(this);
            continue;
          case RETURNED:
            Object result = currentNode.taskOrResult;
            currentNode.taskOrResult = null;
            return result;
          case THREW:
            Throwable thrown = (Throwable) currentNode.taskOrResult;
            currentNode.taskOrResult = null;
            throw (CancellationException) new CancellationException(
                    "guard or task threw in another thread").initCause(thrown);
          case WAITING:
            if (!cancelIfNotExecuted || cancelWithoutExecuting(currentNode)) {
              return NOT_EXECUTED_YET;
            } else {
              continue;
            }
          default:
            // Can't have CANCELLED status at any point where this method is called.
            throw new AssertionError("impossible status " + status);
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private boolean tryAcquireLock() {
    if (STATE.compareAndSet(this, UNLOCKED, LOCKED)) {
      setExclusiveOwnerThread(Thread.currentThread());
      return true;
    } else {
      return false;
    }
  }

  private void releaseLock(Node currentNode) {
    setExclusiveOwnerThread(null);
    state = UNLOCKED;
    unparkAnotherThreadIfNecessary(currentNode);
  }

  // ===============================================================================================
  // Internal Data Structures
  // ===============================================================================================

  static {
    // Reduce the risk of rare disastrous classloading in first call to
    // LockSupport.park: https://bugs.openjdk.java.net/browse/JDK-8074773
    // (borrowed from java.util.concurrent.locks.AbstractQueuedSynchronizer)
    @SuppressWarnings("unused")
    Class<?> ensureLoaded = LockSupport.class;
  }

  private static final long serialVersionUID = 0L;

  // The number of nanoseconds for which it is faster to spin
  // rather than to use timed park. A rough estimate suffices
  // to improve responsiveness with very short timeouts.
  // (borrowed from java.util.concurrent.locks.AbstractQueuedSynchronizer)
  private static final long SPIN_FOR_TIMEOUT_THRESHOLD = 1000L;

  // Special value returned from consumeResult() if task has not been executed yet:
  private static final Object NOT_EXECUTED_YET = new Object();

  // Possible values for state field:
  private static final int UNLOCKED = 0;
  private static final int LOCKED = 1;

  // Possible values for Node.status field:
  private static final int WAITING = 0;
  private static final int CANCELLED = 1;
  private static final int EXECUTING = 2;
  private static final int RETURNED = 3;
  private static final int THREW = 4;

  private static final AtomicIntegerFieldUpdater<GuardedExecutor> STATE =
      AtomicIntegerFieldUpdater.newUpdater(GuardedExecutor.class, "state");
  private static final AtomicReferenceFieldUpdater<GuardedExecutor, Node> TAIL =
      AtomicReferenceFieldUpdater.newUpdater(GuardedExecutor.class, Node.class, "tail");

  private transient volatile int state = 0;
  private transient volatile Node tail = null;

  static final class Node {

    private static final AtomicIntegerFieldUpdater<Node> STATUS =
        AtomicIntegerFieldUpdater.newUpdater(Node.class, "status");

    volatile int status;
    Node prev;
    Node next;
    Thread thread;
    BooleanSupplier guard;
    Object taskOrResult;

    Node(BooleanSupplier guard, Runnable runnable, Supplier<?> supplier) {
      this.thread = Thread.currentThread();
      this.guard = guard;

      if (runnable != null) {
        this.taskOrResult = runnable;
      } else if (supplier instanceof Runnable) {
        // This check avoids incorrect behavior in Node.execute().
        throw new IllegalArgumentException(
            "task is statically a Supplier but also dynamically a Runnable");
      } else {
        this.taskOrResult = supplier;
      }
    }

    final Object execute() {
      final Object task = this.taskOrResult;
      if (task instanceof Runnable) {
        ((Runnable) task).run();
        return null;
      } else {
        return ((Supplier<?>) task).get();
      }
    }

    final void complete(Object result) {
      this.thread = null;
      this.guard = null;
      this.taskOrResult = result;
    }

    final boolean leaveWaitingStatus(int newStatus) {
      return STATUS.compareAndSet(this, WAITING, newStatus);
    }

  }

}
