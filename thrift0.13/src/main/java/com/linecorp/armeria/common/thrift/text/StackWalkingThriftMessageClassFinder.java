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
// =================================================================================================
// Copyright 2011 Twitter, Inc.
// -------------------------------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this work except in compliance with the License.
// You may obtain a copy of the License in the LICENSE file, or at:
//
//  https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// =================================================================================================
package com.linecorp.armeria.common.thrift.text;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StackWalkingThriftMessageClassFinder extends AbstractThriftMessageClassFinder {

    private static final Logger logger =
            LoggerFactory.getLogger(StackWalkingThriftMessageClassFinder.class);

    private static final String INVOKING_FAIL_MSG =
            "Failed to invoke StackWalker.StackFrame.getDeclaringClass():";

    @Nullable
    private final Function<Stream<Object>, Class<?>> walkHandler;
    private final MethodHandle walkMH;
    private final Object stackWalker;

    StackWalkingThriftMessageClassFinder() throws Throwable {
        final ClassLoader classLoader = ClassLoader.getSystemClassLoader();
        final Lookup lookup = MethodHandles.lookup();

        final Class<?> stackWalkerClass = classLoader.loadClass("java.lang.StackWalker");
        walkMH = lookup.findVirtual(stackWalkerClass,
                                    "walk",
                                    MethodType.methodType(Object.class, Function.class));

        final Class<?> stackFrameClass = classLoader.loadClass("java.lang.StackWalker$StackFrame");
        Function<Object, Class<?>> getClassByStackFrameTemp;
        Object instance;

        try {
            // StackWalker instantiate with RETAIN_CLASS_REFERENCE option
            final Class<?> Option = classLoader.loadClass("java.lang.StackWalker$Option");
            final MethodHandle getInstanceMH =
                    lookup.findStatic(stackWalkerClass,
                                      "getInstance",
                                      MethodType.methodType(stackWalkerClass, Option));
            final Enum<?> RETAIN_CLASS_REFERENCE =
                    Arrays.stream((Enum<?>[]) Option.getEnumConstants())
                          .filter(op -> "RETAIN_CLASS_REFERENCE".equals(op.name()))
                          .findFirst().orElse(null);

            if (RETAIN_CLASS_REFERENCE == null) {
                throw new IllegalStateException("Failed to get RETAIN_CLASS_REFERENCE option");
            }
            instance = getInstanceMH.invoke(RETAIN_CLASS_REFERENCE);
            final MethodHandle getDeclaringClassMH = lookup.findVirtual(stackFrameClass,
                                                                        "getDeclaringClass",
                                                                        MethodType.methodType(Class.class));

            getClassByStackFrameTemp = stackFrame -> {
                try {
                    return getMatchedClass((Class<?>) getDeclaringClassMH.invoke(stackFrame));
                } catch (Throwable t) {
                    logger.warn(INVOKING_FAIL_MSG, t);
                }
                return null;
            };
        } catch (Throwable throwable) {
            // StackWalker instantiate without option
            logger.warn("Falling back to StackWalker without option:", throwable);
            final MethodHandle getInstanceMH =
                    lookup.findStatic(stackWalkerClass,
                                      "getInstance",
                                      MethodType.methodType(stackWalkerClass));
            final MethodHandle getClassNameMH =
                    lookup.findVirtual(stackFrameClass, "getClassName", MethodType.methodType(String.class));

            instance = getInstanceMH.invoke();
            getClassByStackFrameTemp = stackFrame -> {
                try {
                    return getMatchedClass(getClassByName(getClassNameMH.invoke(stackFrame).toString()));
                } catch (Throwable t) {
                    logger.warn(INVOKING_FAIL_MSG, t);
                }
                return null;
            };
        }

        stackWalker = instance;

        final Function<Object, Class<?>> getClassByStackFrame = getClassByStackFrameTemp;
        walkHandler = stackFrameStream ->
                stackFrameStream.map(getClassByStackFrame)
                                .filter(Objects::nonNull)
                                .findFirst()
                                .orElse(null);
    }

    @Nullable
    @Override
    public Class<?> get() {
        try {
            return (Class<?>) walkMH.invoke(stackWalker, walkHandler);
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to invoke StackWalker.walk():", t);
        }
    }
}

