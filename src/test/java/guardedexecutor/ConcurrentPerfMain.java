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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static guardedexecutor.ConcurrentPerfOptions.*;
import static java.util.Arrays.*;
import static java.util.Comparator.*;
import static java.util.stream.Collectors.*;

public final class ConcurrentPerfMain {

  private ConcurrentPerfMain() {}

  private static volatile boolean encounteredError;

  private static class GCWaiter {

    private final AtomicReference<Object> atomicReference = new AtomicReference<>(new Object());
    private final ReferenceQueue<Object> referenceQueue = new ReferenceQueue<>();
    @SuppressWarnings("unused") // not accessed, but must remain to keep object phantom-reachable
    private final PhantomReference<Object> phantomReference =
        new PhantomReference<>(atomicReference.get(), referenceQueue);

    void waitForGC() throws InterruptedException {
      if (atomicReference.getAndSet(null) != null) {
        System.gc();
        referenceQueue.remove();
      }
    }

  }

  private static class PerfScenario {

    private final ConcurrentPerfSubjectImpl impl;
    private final GCWaiter preGC = new GCWaiter();
    private final GCWaiter postGC = new GCWaiter();
    private final int numThreads;
    private final int capacity;
    private final int workReps;
    private double throughput;
    private double liveness;
    private double expectedLiveness;
    private double[] sampleNanos;
    private long setUpNanos;
    private long runningNanos;
    private long tearDownNanos;
    private long gcMillis;
    private long workSeedChecksum;
    private long totalCount;
    private long nonQueuedCount;
    private long mainParkCount;
    private long consumeParkCount;
    private long consumeReturnCount;

    private PerfScenario(
        ConcurrentPerfSubjectImpl impl, int numThreads, int capacity, int workReps) {
      this.impl = impl;
      this.numThreads = numThreads;
      this.capacity = capacity;
      this.workReps = workReps;
    }

    @Override
    public String toString() {
      return String.format("{impl=%s, numThreads=%d, capacity=%d, workReps=%d}",
          impl, numThreads, capacity, workReps);
    }

    void run(Random random) throws InterruptedException {
      long time1 = System.nanoTime();

      preGC.waitForGC();
      WATCHER.checkGC();

      CountDownLatch startLatch = new CountDownLatch(numThreads);
      ConcurrentPerfSubject subject = impl == null ? null : impl.newSubject(capacity);
      AtomicLong baselineCounter = impl == null ? new AtomicLong() : null;
      PerfRunner[] runners = new PerfRunner[numThreads];
      for (int threadNumber = 0; threadNumber < numThreads; threadNumber++) {
        long workSeed = random.nextLong();
        PerfRunner runner;
        if (impl == null) {
          runner = new BaselineRunner(startLatch, workSeed, workReps, baselineCounter);
        } else if (capacity == 0) {
          runner = new AccumulateRunner(startLatch, workSeed, workReps, subject);
        } else if (numThreads == 1) {
          runner = new ProduceAndConsumeRunner(startLatch, workSeed, workReps, subject);
        } else if (threadNumber % 2 == 0) {
          runner = new ProduceRunner(startLatch, workSeed, workReps, subject);
        } else {
          runner = new ConsumeRunner(startLatch, workSeed, workReps, subject);
        }
        runner.setDaemon(true);
        runner.start();
        runners[threadNumber] = runner;
      }
      int actualSamples = SAMPLES_PER_TRIAL;
      double[] sampleNanos = new double[actualSamples];
      double[] allThroughput = new double[numThreads * actualSamples];
      startLatch.await();

      long time2 = System.nanoTime();

      Thread.sleep(WARMUP_MILLIS);
      long[] threadCountsA = new long[numThreads];
      long[] threadCountsB = new long[numThreads];
      long[] threadCountsC = new long[numThreads];
      long[] threadCountsD = new long[numThreads];
      int sampleIndex = 0;
      while (sampleIndex < actualSamples) {
        for (int threadNumber = 0; threadNumber < numThreads; threadNumber++) {
          threadCountsA[threadNumber] = runners[threadNumber].operations;
        }
        long startNanoTime = System.nanoTime();
        for (int threadNumber = 0; threadNumber < numThreads; threadNumber++) {
          threadCountsB[threadNumber] = runners[threadNumber].operations;
        }
        Thread.sleep(SAMPLE_MILLIS);
        for (int threadNumber = 0; threadNumber < numThreads; threadNumber++) {
          threadCountsC[threadNumber] = runners[threadNumber].operations;
        }
        long endNanoTime = System.nanoTime();
        for (int threadNumber = 0; threadNumber < numThreads; threadNumber++) {
          threadCountsD[threadNumber] = runners[threadNumber].operations;
        }
        long elapsedNanos = endNanoTime - startNanoTime;
        for (int threadNumber = 0; threadNumber < numThreads; threadNumber++) {
          long ops = (threadCountsC[threadNumber] + threadCountsD[threadNumber]
              - threadCountsA[threadNumber] - threadCountsB[threadNumber]) / 2;
          allThroughput[sampleIndex * numThreads + threadNumber] = (double) ops / elapsedNanos;
        }
        sampleNanos[sampleIndex] = (double) elapsedNanos;
        sampleIndex++;
      }

      long time3 = System.nanoTime();

      for (Thread thread : runners) {
        thread.interrupt();
      }
      for (Thread thread : runners) {
        thread.join();
      }

      if (encounteredError) {
        System.err.println("EXITING DUE TO ERROR");
        System.exit(1);
      }

      long workSeedChecksum = 0L;
      long checksum = impl == null ? -baselineCounter.get() : subject.getChecksum();
      for (PerfRunner runner : runners) {
        checksum += runner.checksum;
        workSeedChecksum += runner.work.getSeed();
      }
      if (checksum != 0L) {
        System.err.println("NON-ZERO CHECKSUM: " + checksum);
        System.exit(1);
      }
      this.workSeedChecksum = workSeedChecksum;

      postGC.waitForGC();
      long gcMillis = WATCHER.checkGC();

      double throughputSum = sum(allThroughput);
      double throughputSumOfSquaredErrors =
          sumOfSquaredErrors(allThroughput, throughputSum / allThroughput.length);
      double throughputCoefficientOfVariationSquared =
          (throughputSumOfSquaredErrors * allThroughput.length) / (throughputSum * throughputSum);

      double totalAverageThroughputPerMilli = 1_000_000.0 * throughputSum / actualSamples;
      double averageThroughputPerThreadPerMilli = totalAverageThroughputPerMilli / numThreads;
      double expectedCoefficientOfVariation = THROUGHPUT_GRANULARITY / (averageThroughputPerThreadPerMilli * SAMPLE_MILLIS);

      this.throughput = totalAverageThroughputPerMilli;
      this.liveness = 1.0 / (throughputCoefficientOfVariationSquared + 1.0);
      this.expectedLiveness = 1.0 / (expectedCoefficientOfVariation * expectedCoefficientOfVariation + 1.0);
      this.gcMillis = gcMillis;
      this.sampleNanos = sampleNanos;

      long time4 = System.nanoTime();
      this.setUpNanos = time2 - time1;
      this.runningNanos = time3 - time2;
      this.tearDownNanos = time4 - time3;

      if (impl == ConcurrentPerfSubjectImpl.GUARDED_EXECUTOR) {
        this.totalCount = GuardedExecutor.totalCounter.sumThenReset();
        this.nonQueuedCount = GuardedExecutor.nonQueuedCounter.sumThenReset();
        this.mainParkCount = GuardedExecutor.mainParkCounter.sumThenReset();
        this.consumeParkCount = GuardedExecutor.consumeParkCounter.sumThenReset();
        this.consumeReturnCount = GuardedExecutor.consumeReturnCounter.sumThenReset();
      }
    }

  }

  private static double sum(double[] values) {
    double total = 0.0;
    for (double value : values) {
      total += value;
    }
    return total;
  }

  private static double sumOfSquaredErrors(double[] values, double mean) {
    double sumOfSquaredErrors = 0.0;
    for (double value : values) {
      double error = value - mean;
      sumOfSquaredErrors += error * error;
    }
    return sumOfSquaredErrors;
  }

  public static void main(String... args) throws Exception {
    String fileName = System.getProperty("report.file.name");
    String description = System.getProperty("report.description");

    if (fileName == null) {
      System.out.println("ERROR: -Dreport.file.name is required");
      System.exit(1);
    }
    if (description == null) {
      System.out.println("ERROR: -Dreport.description is required");
      System.exit(1);
    }
    if (args.length != 0) {
      System.out.println("ERROR: extraneous command-line arguments");
      System.exit(1);
    }
    if (!ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-Xcomp")) {
      System.out.println("WARNING: -Xcomp is strongly recommended");
    }

    ConcurrentPerfSubjectImpl[] impls = Arrays.copyOf(SUBJECTS, SUBJECTS.length + 1);
    impls[impls.length - 1] = null; // BASELINE

    OperatingSystemMXBean operatingSystem = ManagementFactory.getOperatingSystemMXBean();
    double startingLoad = operatingSystem.getSystemLoadAverage();
    int processors = operatingSystem.getAvailableProcessors();
    Random random = new SecureRandom();

    List<PerfScenario> scenarios = new ArrayList<>();
    for (int i = 0; i < TRIALS_PER_SCENARIO; i++) {
      for (int workReps : WORK_LOADS) {
        for (int numThreads : THREAD_COUNTS) {
          for (ConcurrentPerfSubjectImpl impl : impls) {
            scenarios.add(new PerfScenario(impl, numThreads, 0, workReps));
            if (impl != null) {
              if (numThreads == 1 && CAPACITIES.length > 0) {
                scenarios.add(new PerfScenario(impl, numThreads, 1, workReps));
              } else {
                for (int capacity : CAPACITIES) {
                  scenarios.add(new PerfScenario(impl, numThreads, capacity, workReps));
                }
              }
            }
          }
        }
      }
    }
    Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
    long workSeedChecksum = 0L;
    int sampleNanosPerScenario = SAMPLES_PER_TRIAL;
    double[] sampleNanos = new double[scenarios.size() * sampleNanosPerScenario];
    Collections.shuffle(scenarios, random);
    int n = scenarios.size();
    int i = 0;
    WATCHER.checkJIT();
    while (i < n) {
      PerfScenario scenario = scenarios.get(i);
      int minutes = (int) Math.ceil((n - i) * (SAMPLES_PER_TRIAL * SAMPLE_MILLIS + WARMUP_MILLIS) / 60_000.0);
      System.out.print((i + 1) + "/" + n + " (" + minutes + "m to go) " + scenario);
      scenario.run(random);
      workSeedChecksum += scenario.workSeedChecksum;
      System.out.print(" [setUp=" + Math.round(scenario.setUpNanos / 1000000.0)
          + "ms, running=" + Math.round(scenario.runningNanos / 1000000.0)
          + "ms, tearDown=" + Math.round(scenario.tearDownNanos / 1000000.0)
          + "ms, gc=" + scenario.gcMillis + "ms]");
      if (WATCHER.checkJIT()) {
        System.out.println(" - rejected due to JIT");
      } else {
        System.out.println();
        assert scenario.sampleNanos.length == sampleNanosPerScenario;
        System.arraycopy(scenario.sampleNanos, 0, sampleNanos,
            i * sampleNanosPerScenario, sampleNanosPerScenario);
        i++;
      }
    }

    double sampleNanosMean = sum(sampleNanos) / sampleNanos.length;
    double sampleNanosSD =
        Math.sqrt(sumOfSquaredErrors(sampleNanos, sampleNanosMean) / sampleNanos.length);

    Date reportDate = new Date();
    String reportTitle = "Performance Test Results "
        + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(reportDate);
    SimpleHtml report = new SimpleHtml();
    report.start("html").nl();
    report.start("head").nl();
    report.comment("workSeedChecksum=", workSeedChecksum).nl();
    report.start("title").text(reportTitle).end("title").nl();
    report.start("style").nl();
    report.text(".odd-outlier, .odd-median { background: #DDE4FF }").nl();
    report.text(".odd-outlier td, .even-outlier td, .borrowed { color: #AAAAAA }").nl();
    report.text("td { text-align: right }").nl();
    report.text(".odd-outlier th, .odd-median th, .even-outlier th, ",
        ".even-median th { text-align: left }").nl();
    report.text("td.throughput { border-left: 1px solid #AAAAAA }").nl();
    report.text("td.liveness { font-size: smaller }").nl();
    report.text("td.gc { font-size: smaller; font-style: italic }").nl();
    report.text("td.fair { text-decoration: wavy underline; }").nl();
    report.end("style").nl();
    report.end("head").nl();
    report.start("body").nl();
    report.start("h1").text(reportTitle).end("h1").nl();
    report.start("p").start("b").text("Description:").end("b").end("p").nl();
    report.start("p").text(description).end("p").nl();
    renderConstants(report);
    renderSystemInfo(report, operatingSystem, startingLoad, processors);
    report.start("p").start("b").text("Legend:").end("b").end("p").nl();
    report.start("ul").nl();
    report.start("li").text("Values in black are the median throughput from three runs; ",
        "values in gray are the other two runs.").end("li").nl();
    report.start("li").text("Each data point is reported as \"1234").nbsp().nbsp().start("small")
        .text("56%").nbsp().nbsp().start("i").text("7ms").end("i").end("small")
        .text("\": the first number represents the average total ",
            "throughput for that run in operations per millisecond; the second number is a measure ",
            "of fairness (100% means all threads had equal throughput); and the third number is the ",
            "time spent in GC.").end("li").nl();
    report.start("li").text("If the fairness percentage has a wavy underline, it is at least as good ",
            "as a benchmark fairness level based on the current throughput granularity. The benchmark value ",
            "is available in a tooltip.").end("li").nl();
    report.start("li").text("The \"Workload\" in the header of each scenario is the ",
        "target average repetitions of simulated work between operations.").end("li").nl();
    report.start("li").text("The \"Capacity\" in the header of each scenario represents the ",
        "simulated capacity of the simulated blocking queue tested in that scenario.")
        .end("li").nl();
    report.start("li").text("The \"BASELINE\" subject is merely adding to an AtomicLong in order ",
        "to give an empirical measurement of the maximum throughput that could be expected for a ",
        "given workload.")
        .end("li").nl();
    report.end("ul").nl();
    report.start("p").start("b").text("Actual Sample Duration:").end("b")
        .text(" mean=", Math.round(sampleNanosMean / 1000000.0), "ms, s.d.=",
            Math.round(sampleNanosSD / 1000000.0), "ms").end("p").nl();
    for (int capacity : IntStream.concat(IntStream.of(0), IntStream.of(CAPACITIES)).toArray()) {
      for (int workReps : WORK_LOADS) {
        report.start("h2");
        if (capacity == 0) {
          report.text("Simple Locking Scenario (");
        } else {
          report.text("Producer/Consumer Scenario (Capacity=", capacity, "; ");
        }
        report.text("Workload=", workReps, ")");
        report.end("h2").nl();
        report.start("table", "border", "0", "cellpadding", "5", "cellspacing", "0").nl();
        report.start("tr").start("th", "rowspan", "2").nbsp().end("th")
            .start("th", "colspan", THREAD_COUNTS.length * 3).text("Number of Threads").end("th")
            .end("tr").nl();
        report.start("tr");
        for (int numThreads : THREAD_COUNTS) {
          String styleClass = numThreads == 1 && capacity > 1 ? "borrowed" : null;
          report.start("th", "colspan", "3", "class", styleClass).text(numThreads).end("th");
        }
        report.end("tr").nl();
        boolean odd = false;
        for (ConcurrentPerfSubjectImpl impl : impls) {
          Map<Integer, List<PerfScenario>> foundScenarios = new HashMap<>();
          for (int numThreads : THREAD_COUNTS) {
            foundScenarios.put(numThreads,
                findScenario(scenarios, impl, numThreads, capacity, workReps));
          }
          String styleClassPrefix = odd ? "odd-" : "even-";
          String medianStyleClass = styleClassPrefix + "median";
          String outlierStyleClass = styleClassPrefix + "outlier";
          for (int r = 0; r < TRIALS_PER_SCENARIO; r++) {
            report.start("tr", "class",
                2 * r + 1 == TRIALS_PER_SCENARIO ? medianStyleClass : outlierStyleClass);
            if (r == 0) {
              if (impl == null) {
                report.start("th", "rowspan", TRIALS_PER_SCENARIO, "class", "borrowed")
                    .text("BASELINE").end("th");
              } else {
                report.start("th", "rowspan", TRIALS_PER_SCENARIO).text(impl).end("th");
              }
            }
            for (int numThreads : THREAD_COUNTS) {
              String borrowedClass = impl == null || numThreads == 1 && capacity > 1
                  ? "borrowed " : "";
              List<PerfScenario> perfScenarios = foundScenarios.get(numThreads);
              assert perfScenarios.size() == TRIALS_PER_SCENARIO : perfScenarios;
              PerfScenario scenario = perfScenarios.get(r);
              String counts = impl != ConcurrentPerfSubjectImpl.GUARDED_EXECUTOR
                  ? null : String.format("total: %d%nnon-queued: %d%nmain park: %d%nconsume park: %d%nconsume return: %d%n", scenario.totalCount, scenario.nonQueuedCount, scenario.mainParkCount, scenario.consumeParkCount, scenario.consumeReturnCount);
              report.start("td", "class", borrowedClass + "throughput", "title", counts)
                  .text(Math.round(scenario.throughput)).end("td");
              String fairClass = scenario.liveness >= scenario.expectedLiveness ? " fair" : "";
              report.start("td", "class", borrowedClass + "liveness" + fairClass,
                      "title", "Benchmark: " + Math.round(scenario.expectedLiveness * 100.0) + "%")
                  .text(Math.round(scenario.liveness * 100.0), "%").end("td");
              report.start("td", "class", borrowedClass + "gc")
                  .text(scenario.gcMillis, "ms").end("td");
            }
            report.end("tr").nl();
          }
          odd = !odd;
        }
        report.end("table").nl();
      }
    }
    report.end("body").nl().end("html").nl();
    String fileURL = writeFile(report.toString(), fileName);
    System.out.println("Report written to: " + fileURL);
  }

  private static void renderConstants(SimpleHtml report) throws IllegalAccessException {
    report.start("p").start("b").text("Test Configuration:").end("b").end("p").nl();
    report.start("ul").nl();
    for (Field field : ConcurrentPerfOptions.class.getFields()) {
      Object value = field.get(null);
      if (value != null) {
        report.start("li").text(field.getName(), ": ");
        if (value instanceof int[]) {
          report.text(stream((int[]) value).mapToObj(Integer::toString).collect(joining(", ")));
        } else if (value instanceof Object[]) {
          report.text(stream((Object[]) value).map(Object::toString).collect(joining(", ")));
        } else {
          report.text(value.toString());
        }
        report.end("li").nl();
      }
    }
    report.end("ul").nl();
  }

  private static void renderSystemInfo(SimpleHtml report, OperatingSystemMXBean operatingSystem,
      double startingLoad, int startingProcessors) {
    List<GarbageCollectorMXBean> garbageCollectors = ManagementFactory.getGarbageCollectorMXBeans();
    CompilationMXBean compilation = ManagementFactory.getCompilationMXBean();
    RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();

    double endingLoad = operatingSystem.getSystemLoadAverage();
    double endingProcessors = operatingSystem.getAvailableProcessors();

    report.start("p").start("b").text("System Information:").end("b").end("p").nl();
    report.start("ul").nl();
    report.start("li").text("OS: ", operatingSystem.getName(), "; ", operatingSystem.getArch(),
        "; ", startingProcessors, startingProcessors == 1 ? " processor" : " processors");
    if (endingProcessors != startingProcessors) {
      report.text(" at start, ", endingProcessors, " at end");
    }
    if (startingLoad >= 0.0) {
      report.text("; ", startingLoad, " load at start, ", endingLoad, " load at end");
    }
    report.end("li").nl();
    report.start("li").text("VM: ", runtime.getSpecVersion(), "; ", runtime.getVmName(),
        "; build ", runtime.getVmVersion()).end("li").nl();
    report.start("li").text("GC: ");
    boolean firstGC = true;
    for (GarbageCollectorMXBean garbageCollector : garbageCollectors) {
      if (!firstGC) {
        report.text("; ");
      }
      report.text(garbageCollector.getName());
      firstGC = false;
    }
    if (firstGC) {
      report.text("None");
    }
    report.end("li").nl();
    report.start("li").text("JIT: ", compilation == null ? "None" : compilation.getName())
        .end("li").nl();
    report.start("li").text("Options:").nl().start("ul").nl();
    for (String option : runtime.getInputArguments()) {
      report.start("li").start("tt").text(option).end("tt").end("li").nl();
    }
    report.end("ul").nl().end("li").nl();
    report.end("ul").nl();
  }

  private static String writeFile(String fileContents, String fileName) throws IOException {
    File file = new File(fileName);
    try (FileWriter fileWriter = new FileWriter(file)) {
      fileWriter.write(fileContents);
    }
    return file.toURI().toString();
  }

  private static final SystemWatcher WATCHER = new SystemWatcher();

  private static class SystemWatcher {

    private final List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
    private final CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
    private long gcTime;
    private long jitTime;

    SystemWatcher() {
      assert !gcs.isEmpty();
      assert jit != null;
      assert jit.isCompilationTimeMonitoringSupported();
      checkGC();
      checkJIT();
    }

    long checkGC() {
      long newTime = 0L;
      for (GarbageCollectorMXBean gc : gcs) {
        newTime += gc.getCollectionTime();
      }
      long result = (newTime - gcTime);
      gcTime = newTime;
      return result;
    }

    boolean checkJIT() {
      long newTime = jit.getTotalCompilationTime();
      boolean result = (newTime - jitTime) > 0;
      jitTime = newTime;
      return result;
    }

  }

  private static List<PerfScenario> findScenario(
      List<PerfScenario> scenarios, ConcurrentPerfSubjectImpl impl,
      int numThreads, int capacity, int workReps) {
    List<PerfScenario> found = new ArrayList<>();
    for (PerfScenario scenario : scenarios) {
      if (scenario.impl == impl
          && scenario.numThreads == numThreads
          && (impl == null
              || (numThreads == 1 && capacity > 0 && scenario.capacity > 0)
              || scenario.capacity == capacity)
          && scenario.workReps == workReps) {
        found.add(scenario);
      }
    }
    found.sort(comparingDouble(scenario -> scenario.throughput));
    return found;
  }

  private static abstract class PerfRunner extends Thread {

    final SimulatedWork work;
    final int workReps;
    final CountDownLatch startLatch;
    volatile long operations;
    long checksum;

    private PerfRunner(CountDownLatch startLatch, long workSeed, int workReps) {
      this.work = new SimulatedWork(workSeed);
      this.workReps = workReps;
      this.startLatch = startLatch;
      setPriority(Thread.NORM_PRIORITY);
    }

    @Override
    public final void run() {
      try {
        startLatch.countDown();
        runInterruptibly();
        encounteredError = true; // Should only end by being interrupted.
        System.err.println("EXCEEDED 2^63-1 OPERATIONS");
      } catch (InterruptedException ie) {
        // okay, just quit
      } catch (Throwable t) {
        encounteredError = true;
        t.printStackTrace();
      }
    }

    final void reportOperations(long operations) throws InterruptedException {
      if (operations % THROUGHPUT_GRANULARITY == 0) {
        if (Thread.interrupted()) {
          throw new InterruptedException();
        }
        this.operations = operations;
      }
    }

    abstract void runInterruptibly() throws InterruptedException;

  }

  private static class BaselineRunner extends PerfRunner {

    private final AtomicLong counter;

    BaselineRunner(CountDownLatch startLatch, long workSeed, int workReps, AtomicLong counter) {
      super(startLatch, workSeed, workReps);
      this.counter = counter;
    }

    @Override
    void runInterruptibly() throws InterruptedException {
      final SimulatedWork work = this.work;
      final int workReps = this.workReps;
      for (long operations = 1; operations > 0; operations++) {
        long value = work.doWork(workReps);
        reportOperations(operations);
        counter.getAndAdd(value);
        checksum += value;
      }
    }

  }

  private static class AccumulateRunner extends PerfRunner {

    private final ConcurrentPerfSubject subject;

    AccumulateRunner(
        CountDownLatch startLatch, long workSeed, int workReps, ConcurrentPerfSubject subject) {
      super(startLatch, workSeed, workReps);
      this.subject = subject;
    }

    @Override
    void runInterruptibly() throws InterruptedException {
      final ConcurrentPerfSubject subject = this.subject;
      final SimulatedWork work = this.work;
      final int workReps = this.workReps;
      for (long operations = 1; operations > 0; operations++) {
        long value = work.doWork(workReps);
        reportOperations(operations);
        subject.accumulate(value);
        checksum += value;
      }
    }

  }

  private static class ProduceRunner extends PerfRunner {

    private final ConcurrentPerfSubject subject;

    ProduceRunner(
        CountDownLatch startLatch, long workSeed, int workReps, ConcurrentPerfSubject subject) {
      super(startLatch, workSeed, workReps);
      this.subject = subject;
    }

    @Override
    void runInterruptibly() throws InterruptedException {
      final ConcurrentPerfSubject subject = this.subject;
      final SimulatedWork work = this.work;
      final int workReps = this.workReps;
      for (long operations = 1; operations > 0; operations++) {
        long value = work.doWork(workReps);
        reportOperations(operations);
        subject.produce(value);
        checksum += value;
      }
    }

  }

  private static class ConsumeRunner extends PerfRunner {

    private final ConcurrentPerfSubject subject;

    ConsumeRunner(
        CountDownLatch startLatch, long workSeed, int workReps, ConcurrentPerfSubject subject) {
      super(startLatch, workSeed, workReps);
      this.subject = subject;
    }

    @Override
    void runInterruptibly() throws InterruptedException {
      final ConcurrentPerfSubject subject = this.subject;
      final SimulatedWork work = this.work;
      final int workReps = this.workReps;
      for (long operations = 1; operations > 0; operations++) {
        work.doWork(workReps);
        reportOperations(operations);
        checksum -= subject.consume();
      }
    }

  }

  private static class ProduceAndConsumeRunner extends PerfRunner {

    private final ConcurrentPerfSubject subject;

    ProduceAndConsumeRunner(
        CountDownLatch startLatch, long workSeed, int workReps, ConcurrentPerfSubject subject) {
      super(startLatch, workSeed, workReps);
      this.subject = subject;
    }

    @Override
    void runInterruptibly() throws InterruptedException {
      final ConcurrentPerfSubject subject = this.subject;
      final SimulatedWork work = this.work;
      final int workReps = this.workReps;
      for (long operations = 1; operations + 1 > 0; operations += 2) {
        long value = work.doWork(workReps);
        reportOperations(operations);
        subject.produce(value);
        checksum += value;
        work.doWork(workReps);
        reportOperations(operations + 1);
        checksum -= subject.consume();
      }
    }

  }

}
