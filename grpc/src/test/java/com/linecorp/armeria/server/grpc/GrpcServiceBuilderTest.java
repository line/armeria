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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.LinkedList;
import java.util.Map.Entry;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.grpc.GrpcStatusFunction;
import com.linecorp.armeria.internal.common.grpc.GrpcStatus;
import com.linecorp.armeria.server.grpc.GrpcStatusMappingTest.A1Exception;
import com.linecorp.armeria.server.grpc.GrpcStatusMappingTest.A2Exception;
import com.linecorp.armeria.server.grpc.GrpcStatusMappingTest.A3Exception;
import com.linecorp.armeria.server.grpc.GrpcStatusMappingTest.B1Exception;
import com.linecorp.armeria.server.grpc.GrpcStatusMappingTest.B2Exception;

import io.grpc.Status;

class GrpcServiceBuilderTest {

    @Test
    void mixExceptionMappingAndGrpcStatusFunction() {
        assertThatThrownBy(() -> GrpcService.builder()
                                            .addExceptionMapping(A1Exception.class, Status.RESOURCE_EXHAUSTED)
                                            .exceptionMapping(cause -> Status.PERMISSION_DENIED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceptionMapping() and addExceptionMapping() are mutually exclusive.");

        assertThatThrownBy(() -> GrpcService.builder()
                                            .exceptionMapping(cause -> Status.PERMISSION_DENIED)
                                            .addExceptionMapping(A1Exception.class, Status.RESOURCE_EXHAUSTED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("addExceptionMapping() and exceptionMapping() are mutually exclusive.");
    }

    @Test
    void duplicatedExceptionMappings() {
        final LinkedList<Entry<Class<? extends Throwable>, Status>> exceptionMappings = new LinkedList<>();
        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A1Exception.class, Status.RESOURCE_EXHAUSTED);

        assertThatThrownBy(() -> {
            GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A1Exception.class, Status.UNIMPLEMENTED);
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("is already added with");
    }

    @Test
    void sortExceptionMappings() {
        final LinkedList<Entry<Class<? extends Throwable>, Status>> exceptionMappings = new LinkedList<>();
        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A1Exception.class, Status.RESOURCE_EXHAUSTED);
        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A2Exception.class, Status.UNIMPLEMENTED);

        assertThat(exceptionMappings)
                .containsExactly(new SimpleImmutableEntry<>(A2Exception.class, Status.UNIMPLEMENTED),
                                 new SimpleImmutableEntry<>(A1Exception.class, Status.RESOURCE_EXHAUSTED));

        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, B1Exception.class, Status.UNAUTHENTICATED);
        assertThat(exceptionMappings)
                .containsExactly(new SimpleImmutableEntry<>(A2Exception.class, Status.UNIMPLEMENTED),
                                 new SimpleImmutableEntry<>(A1Exception.class, Status.RESOURCE_EXHAUSTED),
                                 new SimpleImmutableEntry<>(B1Exception.class, Status.UNAUTHENTICATED));

        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A3Exception.class, Status.UNAUTHENTICATED);
        assertThat(exceptionMappings)
                .containsExactly(new SimpleImmutableEntry<>(A3Exception.class, Status.UNAUTHENTICATED),
                                 new SimpleImmutableEntry<>(A2Exception.class, Status.UNIMPLEMENTED),
                                 new SimpleImmutableEntry<>(A1Exception.class, Status.RESOURCE_EXHAUSTED),
                                 new SimpleImmutableEntry<>(B1Exception.class, Status.UNAUTHENTICATED));

        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, B2Exception.class, Status.NOT_FOUND);
        assertThat(exceptionMappings)
                .containsExactly(new SimpleImmutableEntry<>(A3Exception.class, Status.UNAUTHENTICATED),
                                 new SimpleImmutableEntry<>(A2Exception.class, Status.UNIMPLEMENTED),
                                 new SimpleImmutableEntry<>(A1Exception.class, Status.RESOURCE_EXHAUSTED),
                                 new SimpleImmutableEntry<>(B2Exception.class, Status.NOT_FOUND),
                                 new SimpleImmutableEntry<>(B1Exception.class, Status.UNAUTHENTICATED));

        final GrpcStatusFunction mappingFunction = GrpcStatus.toGrpcStatusFunction(exceptionMappings);

        Status status = GrpcStatus.fromThrowable(mappingFunction, new A3Exception());
        assertThat(status.getCode()).isEqualTo(Status.UNAUTHENTICATED.getCode());

        status = GrpcStatus.fromThrowable(mappingFunction, new A2Exception());
        assertThat(status.getCode()).isEqualTo(Status.UNIMPLEMENTED.getCode());

        status = GrpcStatus.fromThrowable(mappingFunction, new A1Exception());
        assertThat(status.getCode()).isEqualTo(Status.RESOURCE_EXHAUSTED.getCode());

        status = GrpcStatus.fromThrowable(mappingFunction, new B2Exception());
        assertThat(status.getCode()).isEqualTo(Status.NOT_FOUND.getCode());

        status = GrpcStatus.fromThrowable(mappingFunction, new B1Exception());
        assertThat(status.getCode()).isEqualTo(Status.UNAUTHENTICATED.getCode());
    }

    @Test
    void mapStatus() {
        final LinkedList<Entry<Class<? extends Throwable>, Status>> exceptionMappings = new LinkedList<>();
        GrpcServiceBuilder.addExceptionMapping(exceptionMappings, A2Exception.class, Status.PERMISSION_DENIED);

        final GrpcStatusFunction mappingFunction = GrpcStatus.toGrpcStatusFunction(exceptionMappings);

        for (Throwable ex : ImmutableList.of(new A2Exception(), new A3Exception())) {
            final Status status = Status.UNKNOWN.withCause(ex);
            final Status newStatus = GrpcStatus.fromMappingFunction(mappingFunction, status);
            assertThat(newStatus.getCode()).isEqualTo(Status.PERMISSION_DENIED.getCode());
            assertThat(newStatus.getCause()).isEqualTo(ex);
        }

        final Status status = Status.DEADLINE_EXCEEDED.withCause(new A1Exception());
        final Status newStatus = GrpcStatus.fromMappingFunction(mappingFunction, status);
        assertThat(newStatus).isSameAs(status);
    }
}
