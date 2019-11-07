/*
 * Copyright 2019 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.thrift.text;

import java.security.Permission;
import java.util.function.Supplier;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import com.linecorp.armeria.thrift.services.HelloService.hello_args;

/**
 * Compares the performance between {@link Thread#getStackTrace()}, {@code StackWalker}
 * and {@code StackWalker} with the {@code RETAIN_CLASS_REFERENCE} option when finding
 * the current Thrift message class.
 *
 * <p>20190928 Macbook Pro 2018 2.2 GHz Intel Core i7
 * <pre>
 * # Run complete. Total time: 00:25:10
 *
 * Benchmark                                          Mode  Cnt       Score       Error  Units
 * StackTracingBenchmark.defaultStackTrace           thrpt   25   65969.836 ±  4714.734  ops/s
 * StackTracingBenchmark.usingStackWalker            thrpt   25  131919.125 ±  7730.472  ops/s
 * StackTracingBenchmark.usingStackWalkerWithOption  thrpt   25  325797.300 ± 33808.269  ops/s
 * </pre>
 */

@State(Scope.Benchmark)
public class StackTracingBenchmark extends hello_args {

    private Supplier<Class<?>> defaultThriftMessageClassFinder;
    private Supplier<Class<?>> stackWalkingThriftMessageClassFinder;
    private Supplier<Class<?>> stackWalkingFinderWithRetainOption;

    @Setup
    public void makeFinder() throws Throwable {
        defaultThriftMessageClassFinder = new DefaultThriftMessageClassFinder();
        stackWalkingFinderWithRetainOption = new StackWalkingThriftMessageClassFinder();

        final SecurityManager securityManager = System.getSecurityManager();
        System.setSecurityManager(new SecurityManager() {
            @Override
            public void checkPermission(Permission perm) {
                // `getStackWalkerWithClassReference` is called by RETAIN_CLASS_REFERENCE option.
                if (perm.getName().equals("getStackWalkerWithClassReference")) {
                    throw new SecurityException("Failing SecurityManage.checkPermission() for unit testing");
                }
            }

            @Override
            public void checkPermission(Permission perm, Object context) {
            }

            @Override
            public void checkExit(int status) {
            }
        });

        stackWalkingThriftMessageClassFinder =  new StackWalkingThriftMessageClassFinder();
        System.setSecurityManager(securityManager);
    }

    @Benchmark
    public void defaultStackTrace(Blackhole bh) throws Exception {
        bh.consume(defaultThriftMessageClassFinder.get());
    }

    @Benchmark
    public void usingStackWalker(Blackhole bh) throws Exception {
        bh.consume(stackWalkingThriftMessageClassFinder.get());
    }

    @Benchmark
    public void usingStackWalkerWithOption(Blackhole bh) throws Exception {
        bh.consume(stackWalkingFinderWithRetainOption.get());
    }
}
