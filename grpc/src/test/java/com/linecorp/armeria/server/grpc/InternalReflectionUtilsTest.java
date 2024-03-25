/*
 * Copyright 2022 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.reflections.ReflectionUtils.withModifier;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.grpc.stub.StreamObserver;
import testing.grpc.EmptyProtos.Empty;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class InternalReflectionUtilsTest {

    @Test
    void getAllSortedMethods() {
        final Map<String, Method> methods = new HashMap<>();
        for (Method method : InternalReflectionUtils.getAllSortedMethods(TestServiceImpl.class,
                                                                         withModifier(Modifier.PUBLIC))) {
            final String methodName = method.getName();
            if (!methods.containsKey(methodName)) {
                methods.put(methodName, method);
            }
        }
        assertThat(methods.get("unaryCall").getDeclaringClass()).isEqualTo(AbstractTestServiceImpl.class);
        assertThat(methods.get("emptyCall").getDeclaringClass()).isEqualTo(TestServiceImpl.class);
    }

    private abstract static class AbstractTestServiceImpl extends TestServiceImplBase {

        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("test user")
                                                  .build());
            responseObserver.onCompleted();
        }
    }

    private static class TestServiceImpl extends AbstractTestServiceImpl {

        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }
}
