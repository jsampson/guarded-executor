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

/**
 * Base class for subjects to be tested by {@link ConcurrentPerfMain}.
 */
public abstract class ConcurrentPerfSubject {

  private final int capacity;
  private final long[] values;
  private int size;
  private int offset;
  private long accumulator;

  protected ConcurrentPerfSubject(int capacity) {
    this.capacity = capacity;
    this.values = new long[capacity];
    this.size = 0;
    this.offset = 0;
    this.accumulator = 0;
  }

  protected final boolean canConsume() {
    return 0 < size;
  }

  protected final long doConsume() {
    final int size = this.size;
    final int capacity = this.capacity;
    assert 0 < size && size <= capacity : "size=" + size + "; capacity=" + capacity;
    this.size = size - 1;
    final int offset = this.offset;
    this.offset = (offset + 1) % capacity;
    return values[offset];
  }

  protected final boolean canProduce() {
    return size < capacity;
  }

  protected final void doProduce(long value) {
    final int size = this.size;
    final int capacity = this.capacity;
    assert 0 <= size && size < capacity : "size=" + size + "; capacity=" + capacity;
    this.size = size + 1;
    values[(this.offset + size) % capacity] = value;
  }

  protected final void doAccumulate(long value) {
    accumulator += value;
  }

  final long getChecksum() {
    long checksum = this.accumulator;
    for (int i = 0; i < size; i++) {
      checksum += values[(this.offset + i) % capacity];
    }
    return -checksum;
  }

  /**
   * Simulates performing an unconditional operation. Implementations must provide proper
   * synchronization and must call {@link #doAccumulate(long)} exactly once with the given value.
   */
  public abstract void accumulate(long value);

  /**
   * Simulates consuming one item. Implementations must provide proper synchronization and must
   * first wait interruptibly for {@link #canConsume()} to become true and then call {@link
   * #doConsume()} exactly once and return its value.
   */
  public abstract long consume() throws InterruptedException;

  /**
   * Simulates producing one item. Implementations must provide proper synchronization and must
   * first wait interruptibly for {@link #canProduce()} to become true and then call {@link
   * #doProduce(long)} exactly once with the given value.
   */
  public abstract void produce(long value) throws InterruptedException;

}
