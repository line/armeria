# JMH benchmarks

A collection of JMH benchmarks which may be useful for measuring Armeria performance.

## Always prepend `--no-daemon` and `clean`

You must prepend `--no-daemon` and `clean` to your benchmark command due to
[a known bug in jmh-gradle-plugin](https://github.com/melix/jmh-gradle-plugin/issues/132):

```
$ ./gradlew --no-daemon :benchmarks:jmh:clean :benchmarks:jmh:jmh ...
```

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
    - `jmh.extras.Async:asyncProfilerDir=...;flameGraphDir=...`
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

Allow running `perf` as a normal user:

```
# echo 1 > /proc/sys/kernel/perf_event_paranoid
# echo 0 > /proc/sys/kernel/kptr_restrict
```

Install [async-profiler](https://github.com/jvm-profiling-tools/async-profiler) and
[FlameGraph](https://github.com/brendangregg/FlameGraph):

```
$ cd "$HOME"
$ git clone https://github.com/jvm-profiling-tools/async-profiler.git
$ git clone https://github.com/brendangregg/FlameGraph.git
$ cd async-profiler
$ make
```

When running a benchmark, specify `-Pjmh.profilers` option:

```
$ ./gradlew --no-daemon :benchmarks:jmh:clean :benchmarks:jmh:jmh \
  "-Pjmh.profilers=jmh.extras.Async:asyncProfilerDir=$HOME/async-profiler;flameGraphDir=$HOME/FlameGraph"
```

## Notes

- Do not forget to wrap `-Pjmh.params` and `-Pjmh.profilers` option with double quotes, because otherwise your
  shell will interpret `;` as the end of the command.
- See [sbt-jmh documentation](https://github.com/ktoso/sbt-jmh#using-async-profiler) for more profiler options.
