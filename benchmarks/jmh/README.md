# JMH benchmarks

A collection of JMH benchmarks which may be useful for measuring Armeria performance.

## Options

- `-Pjmh.includes=<pattern>`
  - The benchmarks to run, in a comma-separated regular expression. All benchmarks if unspecified.
    - `grpc.downstream.DownstreamSimpleBenchmark`
    - `grpc.downstream.DownstreamSimpleBenchmark.empty$`
- `-Pjmh.params=<spec>`
  - The benchmark parameters. Uses the parameters specified in the benchmark code if unspecified.
    - `clientType=NORMAL`
    - `num=10,100;flowControl=false`
- `-Pjmh.fork=<integer>`
  - The number of forks. `1` if unspecified.
- `-Pjmh.iterations=<integer>`
  - The number of iterations. JMH default if unspecified.
- `-Pjmh.warmupIterations`
  - The number of iterations. Uses the value of `jmh.iterations` if unspecified.
- `-Pjmh.profilers=<spec>`
  - The profiler settings. Profiler disabled if unspecified.
    - `async:libPath=...;output=flamegraph`
- `-Pjmh.threads=<integer>`
  - The number of threads. JMH default if unspecified.
- `-Pjmh.verbose`
  - Increases the verbosity of JMH to `EXTRA`.
- `-Pjmh.jvmargs=<jvm options>`
  - Additional JVM options.
    - `-Xmx8192m -Xms8192m`
- `-Pjmh.forcegc=<true|false>`
  - Whether to force JVM garbage collection. `false` if unspecified.

## Retrieving flame graph using async-profiler

- Allow running `perf` as a normal user on Linux:
  ```
  # echo 1 > /proc/sys/kernel/perf_event_paranoid
  # echo 0 > /proc/sys/kernel/kptr_restrict
  ```
  - MacOS profiling is limited to user space code only, thus this does not work with MacOS.

- Install [async-profiler](https://github.com/async-profiler/async-profiler):
  ```
  $ cd "$HOME"
  $ git clone https://github.com/async-profiler/async-profiler.git
  $ cd async-profiler
  $ make
  ```

- When running a benchmark, specify `-Pjmh.profilers` option:
  - On Linux
    ```
    $ ./gradlew :benchmarks:jmh:jmh \
      "-Pjmh.profilers=async:libPath=$HOME/async-profiler/build/lib/libasyncProfiler.so;output=flamegraph;dir=$HOME/result"
    ```
  - On MacOS
    ```
    $ ./gradlew :benchmarks:jmh:jmh \
      "-Pjmh.profilers=async:libPath=$HOME/async-profiler/build/lib/libasyncProfiler.dylib;output=flamegraph;dir=$HOME/result"
    ```

## Notes

- Do not forget to wrap `-Pjmh.params` and `-Pjmh.profilers` option with double quotes, because otherwise your
  shell will interpret `;` as the end of the command.
- Run `$ ./gradlew :benchmarks:jmh:jmh "-Pjmh.profilers=async:help"` for more profiler options.
