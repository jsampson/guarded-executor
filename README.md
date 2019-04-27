GuardedExecutor Development Repo
================================

***This repository is provided for code review and testing of
GuardedExecutor.** The code is intended to be contributed to another
open source project rather than being released independently.*

I'm the original author of Guava's Monitor class, and I have created a
replacement for it called GuardedExecutor. The new class is faster,
rivaling ReentrantLock; but more importantly, it's even cleaner and
easier to use than Monitor.

In summary, GuardedExecutor is an executor that executes each task while
holding an exclusive lock, optionally waiting for a guard condition to
become true before executing the task. Tasks will usually be executed in
the calling thread, but waiting threads may execute tasks for each other.

Running the Tests
-----------------

You can run the unit tests with "`mvn test`". For the performance test,
the `run-remote.sh` script is provided as a simple example of running on
a remote server. Copy the `run-remote-config-example.sh` file into your
own `run-remote-config.sh` file and edit it according to your particular
environment. Also see the `ConcurrentPerfOptions` class for additional
configuration options for the run.

Reviewing and Contributing
--------------------------

Because this is non-trivial code built on low-level concurrency
primitives, it deserves intensive code review and testing before being
released into the wild.

I am eager to see your comments, questions, and suggestions. The best
way is probably to create an issue for each idea. You can link an issue
to a specific line by clicking on the line number and then the "..."
menu for that line. Please feel free to create wiki pages to discuss
ideas as well. I will be writing more documentation about the design and
testing approach in the near future.

I am hesitant to accept actual code contributions via pull requests for
the time being, simply because I want to be sure to comply with the
contribution guidelines for whichever open source project this code ends
up going into. Thank you for your help and understanding.

*Justin T. Sampson / <justin@krasama.com>*
