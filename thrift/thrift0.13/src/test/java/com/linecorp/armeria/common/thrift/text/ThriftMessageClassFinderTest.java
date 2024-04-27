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

import static org.assertj.core.api.Assertions.assertThat;

import java.security.Permission;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.common.util.SystemInfo;

import testing.thrift.debug.RpcDebugService.doDebug_args;

class ThriftMessageClassFinderTest {
    @ParameterizedTest(name = "testThriftMessageClassFinder {index}: finder={0}")
    @MethodSource("testThriftMessageClassParameters")
    void testThriftMessageClassFinder(Supplier<Class<?>> thriftMessageClassFinder) {
        assertThat(thriftMessageClassFinder.get()).isNull();
        assertThat(new MockArgs().proxy(thriftMessageClassFinder)).isNotNull();
    }

    private static Stream<Arguments> testThriftMessageClassParameters() throws Throwable {
        Stream<Arguments> parameters = Stream.of(Arguments.of(new DefaultThriftMessageClassFinder()));

        if (SystemInfo.javaVersion() >= 9) {
            parameters = Stream.concat(
                    parameters,
                    Stream.of(
                            Arguments.of(new StackWalkingThriftMessageClassFinder()),
                            Arguments.of(getNoOptionStackWalkerInstance())));
        }
        return parameters;
    }

    private static Supplier<Class<?>> getNoOptionStackWalkerInstance() throws Throwable {
        final SecurityManager SM = System.getSecurityManager();
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

        final StackWalkingThriftMessageClassFinder noOptionStackWalkingThriftMessageClassFinder =
                new StackWalkingThriftMessageClassFinder();
        System.setSecurityManager(SM);

        return noOptionStackWalkingThriftMessageClassFinder;
    }

    public static class MockArgs extends doDebug_args {
        public Class<?> proxy(Supplier<Class<?>> currentThriftMessageClassFinder) {
            return currentThriftMessageClassFinder.get();
        }
    }
}
