/*
 * Copyright 2018 LINE Corporation
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
/*
 * Copyright 2014, gRPC Authors All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.internal.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.LinkedList;
import java.util.Map.Entry;

import org.junit.jupiter.api.Test;

import com.google.api.gax.grpc.GrpcStatusCode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.internal.common.grpc.GrpcStatusMappingTest.A1Exception;
import com.linecorp.armeria.internal.common.grpc.GrpcStatusMappingTest.A2Exception;
import com.linecorp.armeria.internal.common.grpc.GrpcStatusMappingTest.A3Exception;
import com.linecorp.armeria.internal.common.grpc.GrpcStatusMappingTest.B1Exception;
import com.linecorp.armeria.internal.common.grpc.GrpcStatusMappingTest.B2Exception;

import io.grpc.Status;

class GrpcStatusTest {
    @Test
    void grpcCodeToHttpStatus() {
        for (Status.Code code : Status.Code.values()) {
            assertThat(GrpcStatus.grpcCodeToHttpStatus(code).code())
                    .as("gRPC code: {}", code)
                    .isEqualTo(GrpcStatusCode.of(code).getCode().getHttpStatusCode());
        }
    }

    @Test
    void duplicatedExceptionMappings() {
        final LinkedList<Entry<Class<? extends Throwable>, Status>> exceptionMappings = new LinkedList<>();
        GrpcStatus.addExceptionMapping(exceptionMappings, A1Exception.class, Status.RESOURCE_EXHAUSTED);

        assertThatThrownBy(() -> {
            GrpcStatus.addExceptionMapping(exceptionMappings, A1Exception.class, Status.UNIMPLEMENTED);
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("is already added with");
    }

    @Test
    void sortExceptionMappings() {
        final LinkedList<Entry<Class<? extends Throwable>, Status>> exceptionMappings = new LinkedList<>();
        GrpcStatus.addExceptionMapping(exceptionMappings, A1Exception.class, Status.RESOURCE_EXHAUSTED);
        GrpcStatus.addExceptionMapping(exceptionMappings, A2Exception.class, Status.UNIMPLEMENTED);

        assertThat(exceptionMappings)
                .containsExactly(new SimpleImmutableEntry<>(A2Exception.class, Status.UNIMPLEMENTED),
                                 new SimpleImmutableEntry<>(A1Exception.class, Status.RESOURCE_EXHAUSTED));

        GrpcStatus.addExceptionMapping(exceptionMappings, B1Exception.class, Status.UNAUTHENTICATED);
        assertThat(exceptionMappings)
                .containsExactly(new SimpleImmutableEntry<>(A2Exception.class, Status.UNIMPLEMENTED),
                                 new SimpleImmutableEntry<>(A1Exception.class, Status.RESOURCE_EXHAUSTED),
                                 new SimpleImmutableEntry<>(B1Exception.class, Status.UNAUTHENTICATED));

        GrpcStatus.addExceptionMapping(exceptionMappings, A3Exception.class, Status.UNAUTHENTICATED);
        assertThat(exceptionMappings)
                .containsExactly(new SimpleImmutableEntry<>(A3Exception.class, Status.UNAUTHENTICATED),
                                 new SimpleImmutableEntry<>(A2Exception.class, Status.UNIMPLEMENTED),
                                 new SimpleImmutableEntry<>(A1Exception.class, Status.RESOURCE_EXHAUSTED),
                                 new SimpleImmutableEntry<>(B1Exception.class, Status.UNAUTHENTICATED));

        GrpcStatus.addExceptionMapping(exceptionMappings, B2Exception.class, Status.NOT_FOUND);
        assertThat(exceptionMappings)
                .containsExactly(new SimpleImmutableEntry<>(A3Exception.class, Status.UNAUTHENTICATED),
                                 new SimpleImmutableEntry<>(A2Exception.class, Status.UNIMPLEMENTED),
                                 new SimpleImmutableEntry<>(A1Exception.class, Status.RESOURCE_EXHAUSTED),
                                 new SimpleImmutableEntry<>(B2Exception.class, Status.NOT_FOUND),
                                 new SimpleImmutableEntry<>(B1Exception.class, Status.UNAUTHENTICATED));

        Status status = GrpcStatus.fromThrowable(exceptionMappings, new A3Exception());
        assertThat(status.getCode()).isEqualTo(Status.UNAUTHENTICATED.getCode());

        status = GrpcStatus.fromThrowable(exceptionMappings, new A2Exception());
        assertThat(status.getCode()).isEqualTo(Status.UNIMPLEMENTED.getCode());

        status = GrpcStatus.fromThrowable(exceptionMappings, new A1Exception());
        assertThat(status.getCode()).isEqualTo(Status.RESOURCE_EXHAUSTED.getCode());

        status = GrpcStatus.fromThrowable(exceptionMappings, new B2Exception());
        assertThat(status.getCode()).isEqualTo(Status.NOT_FOUND.getCode());

        status = GrpcStatus.fromThrowable(exceptionMappings, new B1Exception());
        assertThat(status.getCode()).isEqualTo(Status.UNAUTHENTICATED.getCode());
    }

    @Test
    void mapStatus() {
        final LinkedList<Entry<Class<? extends Throwable>, Status>> exceptionMappings = new LinkedList<>();
        GrpcStatus.addExceptionMapping(exceptionMappings, A2Exception.class, Status.PERMISSION_DENIED);

        for (Throwable ex: ImmutableList.of(new A2Exception(), new A3Exception())) {
            final Status status = Status.UNKNOWN.withCause(ex);
            final Status newStatus = GrpcStatus.fromMappingRule(exceptionMappings, status);
            assertThat(newStatus.getCode()).isEqualTo(Status.PERMISSION_DENIED.getCode());
            assertThat(newStatus.getCause()).isEqualTo(ex);
        }

        final Status status = Status.DEADLINE_EXCEEDED.withCause(new A1Exception());
        final Status newStatus = GrpcStatus.fromMappingRule(exceptionMappings, status);
        assertThat(newStatus).isSameAs(status);

        // Should return the same instance if the code and cause are equal.
        final Status status1 = Status.PERMISSION_DENIED.withCause(new A3Exception());
        final Status newStatus1 = GrpcStatus.fromMappingRule(exceptionMappings, status1);
        assertThat(newStatus1).isSameAs(status1);
    }
}
