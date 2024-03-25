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

package com.linecorp.armeria.server.grpc;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.collect.ImmutableList;

import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class HandlerRegistryTest {

    @CsvSource({
            "/foo/, /bar/, foo/, bar/",
            "/foo, /bar, foo, bar",
            "foo, bar, foo, bar",
            "foo/, bar/, foo/, bar/",
    })
    @ParameterizedTest
    void normalizePath(String path1, String path2, String expected1, String expected2) {
        final HandlerRegistry.Builder builder = new HandlerRegistry.Builder();
        final TestServiceImplBase testService = new TestServiceImplBase() {};
        final HandlerRegistry handlerRegistry = builder.addService(path1, testService.bindService(),
                                                                   null, null, ImmutableList.of())
                                                       .addService(path2, testService.bindService(),
                                                                   null, null, ImmutableList.of())
                                                       .build();

        handlerRegistry.lookupMethod(expected1 + "EmptyCall");
        handlerRegistry.lookupMethod(expected2 + "EmptyCall");
    }
}
