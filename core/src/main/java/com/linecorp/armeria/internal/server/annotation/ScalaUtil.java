/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.internal.server.annotation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.linecorp.armeria.common.annotation.Nullable;

import scala.concurrent.ExecutionContext;
import scala.util.Failure;

final class ScalaUtil {

    @Nullable
    private static final Class<?> SCALA_FUTURE;
    @Nullable
    private static final Class<?> SCALA_EXECUTION_CONTEXT;

    static {
        Class<?> futureClass;
        try {
            futureClass = Class.forName("scala.concurrent.Future");
        } catch (ClassNotFoundException e) {
            futureClass = null;
        }
        SCALA_FUTURE = futureClass;

        Class<?> executionContextClass;
        try {
            executionContextClass = Class.forName("scala.concurrent.ExecutionContext");
        } catch (ClassNotFoundException e) {
            executionContextClass = null;
        }
        SCALA_EXECUTION_CONTEXT = executionContextClass;
    }

    static boolean isScalaFuture(Class<?> clazz) {
        return SCALA_FUTURE != null && SCALA_FUTURE.isAssignableFrom(clazz);
    }

    static boolean isExecutionContext(Class<?> clazz) {
        return SCALA_EXECUTION_CONTEXT != null && SCALA_EXECUTION_CONTEXT.isAssignableFrom(clazz);
    }

    /**
     * A converter that converts {@link scala.concurrent.Future} to {@link CompletableFuture}.
     * This nested class is lazily initialized only when scala-library is in the classpath.
     */
    static final class FutureConverter {

        static <T> CompletableFuture<T> toCompletableFuture(scala.concurrent.Future<T> scalaFuture,
                                                            ExecutorService executor) {
            final CompletableFuture<T> completableFuture = new CompletableFuture<>();

            scalaFuture.onComplete(value -> {
                if (value.isSuccess()) {
                    completableFuture.complete(value.get());
                } else {
                    final Failure<T> failure = (Failure<T>) value;
                    completableFuture.completeExceptionally(failure.exception());
                }
                return null;
            }, ExecutionContext.fromExecutorService(executor));

            return completableFuture;
        }

        private FutureConverter() {}
    }

    private ScalaUtil() {}
}
