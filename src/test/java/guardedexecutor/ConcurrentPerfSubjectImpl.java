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

import com.google.common.util.concurrent.Monitor;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public enum ConcurrentPerfSubjectImpl {

  SYNCHRONIZED {
    @Override public ConcurrentPerfSubject newSubject(int capacity) {
      return new SynchronizedPerfSubject(capacity);
    }
  },

  REENTRANT_LOCK {
    @Override public ConcurrentPerfSubject newSubject(int capacity) {
      return new ReentrantLockPerfSubject(capacity, false);
    }
  },

  REENTRANT_LOCK_FAIR {
    @Override public ConcurrentPerfSubject newSubject(int capacity) {
      return new ReentrantLockPerfSubject(capacity, true);
    }
  },

  GUAVA_MONITOR {
    @Override public ConcurrentPerfSubject newSubject(int capacity) {
      return new GuavaMonitorPerfSubject(capacity, false);
    }
  },

  GUAVA_MONITOR_FAIR {
    @Override public ConcurrentPerfSubject newSubject(int capacity) {
      return new GuavaMonitorPerfSubject(capacity, true);
    }
  },

  GUARDED_EXECUTOR {
    @Override public ConcurrentPerfSubject newSubject(int capacity) {
      return new GuardedExecutorPerfSubject(capacity);
    }
  },

  BARGING_GUARDED_EXECUTOR {
    @Override public ConcurrentPerfSubject newSubject(int capacity) {
      return new BargingGuardedExecutorPerfSubject(capacity);
    }
  };

  public abstract ConcurrentPerfSubject newSubject(int capacity);

  private static class SynchronizedPerfSubject extends ConcurrentPerfSubject {

    SynchronizedPerfSubject(int capacity) {
      super(capacity);
    }

    @Override
    public synchronized long consume() throws InterruptedException {
      while (!canConsume()) {
        wait();
      }
      long value = doConsume();
      notifyAll();
      return value;
    }

    @Override
    public synchronized void produce(long value) throws InterruptedException {
      while (!canProduce()) {
        wait();
      }
      doProduce(value);
      notifyAll();
    }

    @Override
    public synchronized void accumulate(long value) {
      doAccumulate(value);
    }

  }

  private static class ReentrantLockPerfSubject extends ConcurrentPerfSubject {

    private final ReentrantLock lock;
    private final Condition canConsume;
    private final Condition canProduce;

    ReentrantLockPerfSubject(int capacity, boolean fair) {
      super(capacity);
      lock = new ReentrantLock(fair);
      canConsume = lock.newCondition();
      canProduce = lock.newCondition();
    }

    @Override
    public long consume() throws InterruptedException {
      lock.lock();
      try {
        while (!canConsume()) {
          canConsume.await();
        }
        long value = doConsume();
        canProduce.signal();
        return value;
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void produce(long value) throws InterruptedException {
      lock.lock();
      try {
        while (!canProduce()) {
          canProduce.await();
        }
        doProduce(value);
        canConsume.signal();
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void accumulate(long value) {
      lock.lock();
      try {
        doAccumulate(value);
      } finally {
        lock.unlock();
      }
    }

  }

  private static class GuavaMonitorPerfSubject extends ConcurrentPerfSubject {

    private final Monitor monitor;
    private final Monitor.Guard canConsume;
    private final Monitor.Guard canProduce;

    GuavaMonitorPerfSubject(int capacity, boolean fair) {
      super(capacity);
      monitor = new Monitor(fair);
      canConsume = monitor.newGuard(this::canConsume);
      canProduce = monitor.newGuard(this::canProduce);
    }

    @Override
    public long consume() throws InterruptedException {
      monitor.enterWhen(canConsume);
      try {
        return doConsume();
      } finally {
        monitor.leave();
      }
    }

    @Override
    public void produce(long value) throws InterruptedException {
      monitor.enterWhen(canProduce);
      try {
        doProduce(value);
      } finally {
        monitor.leave();
      }
    }

    @Override
    public void accumulate(long value) {
      monitor.enter();
      try {
        doAccumulate(value);
      } finally {
        monitor.leave();
      }
    }

  }

  private static class GuardedExecutorPerfSubject extends ConcurrentPerfSubject {

    private final GuardedExecutor executor;

    GuardedExecutorPerfSubject(int capacity) {
      super(capacity);
      executor = new GuardedExecutor();
    }

    @Override
    public long consume() throws InterruptedException {
      return executor.executeWhen(this::canConsume, this::doConsume);
    }

    @Override
    public void produce(long value) throws InterruptedException {
      executor.executeWhen(this::canProduce, () -> doProduce(value));
    }

    @Override
    public void accumulate(long value) {
      executor.execute(() -> doAccumulate(value));
    }

  }

  private static class BargingGuardedExecutorPerfSubject extends ConcurrentPerfSubject {

    private final BargingGuardedExecutor executor;

    BargingGuardedExecutorPerfSubject(int capacity) {
      super(capacity);
      executor = new BargingGuardedExecutor();
    }

    @Override
    public long consume() throws InterruptedException {
      return executor.executeWhen(this::canConsume, this::doConsume);
    }

    @Override
    public void produce(long value) throws InterruptedException {
      executor.executeWhen(this::canProduce, () -> doProduce(value));
    }

    @Override
    public void accumulate(long value) {
      executor.execute(() -> doAccumulate(value));
    }

  }

}
