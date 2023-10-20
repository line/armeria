/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.internal.server.thrift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import net.bytebuddy.dynamic.ClassFileLocator.ForClassLoader;

import testing.thrift.main.FooStruct;

class ThriftClassLoadingTest {

    // see https://issues.apache.org/jira/browse/THRIFT-5430
    @Test
    void testDeadlock() throws Exception {
        for (int i = 0; i < 50; i++) {
            final ExecutorService e1 = Executors.newSingleThreadExecutor();
            final ExecutorService e2 = Executors.newSingleThreadExecutor();
            final ClassLoader classLoader = new SimpleClassLoader(FooStruct.class);
            @SuppressWarnings("unchecked")
            final Class<FooStruct> aClass =
                    (Class<FooStruct>) Class.forName(FooStruct.class.getName(), false, classLoader);
            e1.submit(() -> ThriftDescriptiveTypeInfoProvider.newStructInfo(aClass));
            e2.submit(() -> Class.forName(FooStruct.class.getName(), true, classLoader));
            e1.shutdown();
            e2.shutdown();
            // unfortunately if a deadlock did occur the threads are in an uninterruptible state
            // which means the threads cannot be cleaned up
            assertThat(e1.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            assertThat(e2.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * A simple class loader used to re-initialize a class.
     */
    private static class SimpleClassLoader extends ClassLoader {

        private final Class<?> targetClass;
        private final Map<String, Class<?>> memo = new HashMap<>();

        SimpleClassLoader(Class<?> targetClass) {
            this.targetClass = targetClass;
        }

        @Override
        public synchronized Class<?> loadClass(String name) throws ClassNotFoundException {
            if (!name.startsWith(targetClass.getName())) {
                return super.loadClass(name);
            }
            if (memo.containsKey(name)) {
                return memo.get(name);
            }
            final byte[] bytes = ForClassLoader.read(Class.forName(name));
            final Class<?> clazz = defineClass(name, bytes, 0, bytes.length, null);
            Arrays.stream(clazz.getConstructors()).forEach(c -> c.setAccessible(true));
            memo.put(name, clazz);
            return clazz;
        }
    }
}
