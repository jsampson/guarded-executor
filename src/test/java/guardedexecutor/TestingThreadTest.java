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

import static guardedexecutor.TestingThread.startTestingThread;

public class TestingThreadTest extends TestCase {

  public void testTestingThreadNames() {
    TestingThread<Void> first = startTestingThread(() -> {});
    TestingThread<Void> second = startTestingThread(() -> {});
    TestingThread<Void> third = startTestingThread(() -> {});
    assertEquals("TestingThread-testTestingThreadNames-1", first.getName());
    assertEquals("TestingThread-testTestingThreadNames-2", second.getName());
    assertEquals("TestingThread-testTestingThreadNames-3", third.getName());
  }

  public void testMoreTestingThreadNames() {
    TestingThread<Void> first = startTestingThread(() -> {});
    TestingThread<Void> second = startTestingThread(() -> {});
    TestingThread<Void> third = startTestingThread(() -> {});
    assertEquals("TestingThread-testMoreTestingThreadNames-1", first.getName());
    assertEquals("TestingThread-testMoreTestingThreadNames-2", second.getName());
    assertEquals("TestingThread-testMoreTestingThreadNames-3", third.getName());
  }

  public void testIndirectTestingThreadNames() {
    TestingThread<Void> first = indirectlyStartTestingThread();
    TestingThread<Void> second = startTestingThread(() -> {});
    TestingThread<Void> third = indirectlyStartTestingThread();
    assertEquals("TestingThread-testIndirectTestingThreadNames-1", first.getName());
    assertEquals("TestingThread-testIndirectTestingThreadNames-2", second.getName());
    assertEquals("TestingThread-testIndirectTestingThreadNames-3", third.getName());
  }

  private TestingThread<Void> indirectlyStartTestingThread() {
    return startTestingThread(() -> {});
  }

}
