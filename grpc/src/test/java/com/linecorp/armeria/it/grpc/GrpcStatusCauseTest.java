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

package com.linecorp.armeria.it.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assume.assumeTrue;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.grpc.StatusCauseException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

public class GrpcStatusCauseTest {

    private static class TestServiceImpl extends TestServiceImplBase {
        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            final IllegalStateException e1 = new IllegalStateException("Exception 1");
            final IllegalArgumentException e2 = new IllegalArgumentException();
            final AssertionError e3 = new AssertionError("Exception 3");
            Exceptions.clearTrace(e3);
            final RuntimeException e4 = new RuntimeException("Exception 4");
            Exceptions.clearTrace(e4);

            e1.initCause(e2);
            e2.initCause(e3);
            e3.initCause(e4);

            final Status status = Status.ABORTED.withCause(e1);
            responseObserver.onError(status.asRuntimeException());
        }
    }

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.serviceUnder("/",
                            GrpcService.builder()
                                       .addService(new TestServiceImpl())
                                       .build());
        }
    };

    private TestServiceBlockingStub stub;

    @Before
    public void setUp() {
        stub = GrpcClients.newClient(server.httpUri(), TestServiceBlockingStub.class);
    }

    @Test
    public void normal() {
        // These two properties are set in build.gradle.
        assumeTrue("always".equals(Flags.verboseExceptionSamplerSpec()));
        assumeTrue(Flags.verboseResponses());

        assertThatThrownBy(() -> stub.unaryCall(SimpleRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, t -> {
                    assertThat(t.getCause()).isInstanceOfSatisfying(
                            StatusCauseException.class,
                            cause -> {
                                assertThat(cause.getOriginalClassName())
                                        .isEqualTo("java.lang.IllegalStateException");
                                assertThat(cause.getOriginalMessage()).isEqualTo("Exception 1");
                                assertThat(cause.getMessage())
                                        .isEqualTo("java.lang.IllegalStateException: Exception 1");
                                assertThat(cause.getStackTrace()).isNotEmpty();
                                assertThat(cause.getStackTrace()[0].getClassName()).contains("TestServiceImpl");
                                assertThat(cause.getStackTrace()[0].getMethodName()).isEqualTo("unaryCall");
                                assertThat(cause.getStackTrace()[0].getFileName())
                                        .isEqualTo("GrpcStatusCauseTest.java");
                                assertThat(cause.getStackTrace()[0].getLineNumber()).isPositive();
                            }
                    );
                    assertThat(t.getCause().getCause()).isInstanceOfSatisfying(
                            StatusCauseException.class,
                            cause -> {
                                assertThat(cause.getMessage())
                                        .isEqualTo("java.lang.IllegalArgumentException: ");
                                assertThat(cause.getOriginalMessage()).isEmpty();
                            }
                    );
                    assertThat(t.getCause().getCause().getCause()).isInstanceOfSatisfying(
                            StatusCauseException.class,
                            cause -> {
                                assertThat(cause.getMessage())
                                        .isEqualTo("java.lang.AssertionError: Exception 3");
                                assertThat(cause.getStackTrace()).isEmpty();
                            }
                    );
                    assertThat(t.getCause().getCause().getCause().getCause()).isInstanceOfSatisfying(
                            StatusCauseException.class,
                            cause -> {
                                assertThat(cause.getMessage())
                                        .isEqualTo("java.lang.RuntimeException: Exception 4");
                                assertThat(cause.getCause()).isNull();
                            }
                    );
                });
    }
}
