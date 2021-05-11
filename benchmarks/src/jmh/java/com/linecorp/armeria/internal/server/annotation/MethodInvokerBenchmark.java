package com.linecorp.armeria.internal.server.annotation;

import static java.util.Objects.requireNonNull;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 1)
//@Measurement(iterations = 3)
@Fork(1)
@SuppressWarnings("NotNullFieldNotInitialized")
public class MethodInvokerBenchmark {

    private static final MethodHandles.Lookup lookup = MethodHandles.lookup();

    private final Method method;
    private final Method staticMethod;
    private final Method varargMethod;
    private final MethodHandle methodHandle;
    private final MethodHandle staticMethodHandle;
    private final MethodHandle varargMethodHandle;

    public MethodInvokerBenchmark() {
        try {
            method = MethodInvokerBenchmark.class.getDeclaredMethod("method1",
                                                                    String.class, int.class, Long.class,
                                                                    float.class);
            staticMethod = MethodInvokerBenchmark.class.getDeclaredMethod("method2", // static
                                                                          String.class, int.class, Long.class,
                                                                          float.class);
            varargMethod = MethodInvokerBenchmark.class.getDeclaredMethod("method3", // vararg
                                                                          String.class, int[].class);

            methodHandle = asMethodHandle(method, this);
            staticMethodHandle = asMethodHandle(staticMethod, null); // static
            varargMethodHandle = asMethodHandle(varargMethod, this); // vararg
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private Object[] methodArgs;
    private Object[] varargMethodArgs;
    private Object[] varargMethodArgs2;

    @Setup(Level.Invocation)
    public void setUp() throws Exception {
        methodArgs = new Object[] { "foo", 1, 2L, 3.0f };
        varargMethodArgs = new Object[] { "foo", 1, 2, 3, 4 }; // long take 2 spaces, hence pass 4 varargs
        varargMethodArgs2 = new Object[] { "foo", new int[] { 1, 2, 3, 4 } };
    }

    @Benchmark
    public void invokeMethod(Blackhole bh) throws Exception {
        bh.consume(method1("foo", 1, 2L, 3.0f));
    }

    @Benchmark
    public void invokeMethodReflect(Blackhole bh) throws Exception {
        bh.consume(method.invoke(this, methodArgs));
    }

    @Benchmark
    public void invokeMethodHandle(Blackhole bh) throws Throwable {
        bh.consume(methodHandle.invoke(methodArgs));
    }

    @Benchmark
    public void invokeStaticMethod(Blackhole bh) throws Exception {
        bh.consume(method2("foo", 1, 2L, 3.0f));
    }

    @Benchmark
    public void invokeStaticMethodReflect(Blackhole bh) throws Exception {
        bh.consume(staticMethod.invoke(null, methodArgs));
    }

    @Benchmark
    public void invokeStaticMethodHandle(Blackhole bh) throws Throwable {
        bh.consume(staticMethodHandle.invoke(methodArgs));
    }

    @Benchmark
    public void invokeVarargMethod(Blackhole bh) throws Exception {
        bh.consume(method3("foo", 1, 2, 3, 4));
    }

    @Benchmark
    public void invokeVarargMethodReflect(Blackhole bh) throws Exception {
        bh.consume(varargMethod.invoke(this, varargMethodArgs2));
    }

    @Benchmark
    public void invokeVarargMethodHandle(Blackhole bh) throws Throwable {
        bh.consume(varargMethodHandle.invokeWithArguments(varargMethodArgs));
    }

    public String method1(String param0, int param1, Long param2, float param3) {
        final StringBuilder builder = new StringBuilder()
                .append(param0)
                .append(param1)
                .append(param2)
                .append(param3);
        return builder.toString();
    }

    public static String method2(String param0, int param1, Long param2, float param3) {
        final StringBuilder builder = new StringBuilder()
                .append(param0)
                .append(param1)
                .append(param2)
                .append(param3);
        return builder.toString();
    }

    public String method3(String param0, int... params) {
        final StringBuilder builder = new StringBuilder()
                .append(param0);
        for (int param : params) {
            builder.append(param);
        }
        return builder.toString();
    }

    // This is a replica of AnnotatedService#asMethodHandle(Method, Object)} with additional support for varargs
    private static MethodHandle asMethodHandle(Method method, @Nullable Object object) {
        MethodHandle methodHandle;
        try {
            // additional investigation showed no difference in performance between the MethodHandle
            // obtained via either MethodHandles.Lookup#unreflect or MethodHandles.Lookup#findVirtual
            methodHandle = lookup.unreflect(method);
        } catch (IllegalAccessException e) {
            // this is extremely unlikely considering that we've already executed method.setAccessible(true)
            throw new RuntimeException(e);
        }
        if (!Modifier.isStatic(method.getModifiers())) {
            // bind non-static methods to an instance of the declaring class
            methodHandle = methodHandle.bindTo(requireNonNull(object, "object"));
        }
        final int parameterCount = method.getParameterCount();
        if (method.isVarArgs()) {
            // CAUTION: vararg invocation is much slower than a regular one (known Java problem)
            // try avoiding it, if possible, or simply use reflection
            // CAUTION: result handle must be invoked via MethodHandle#invokeWithArguments
            final Class<?> varArgType = method.getParameterTypes()[parameterCount - 1];
            methodHandle = methodHandle.asVarargsCollector(varArgType);
        } else {
            // allows MethodHandle accepting an Object[] argument and
            // spreading its elements as positional arguments
            methodHandle = methodHandle.asSpreader(Object[].class, parameterCount);
        }
        return methodHandle;
    }
}
