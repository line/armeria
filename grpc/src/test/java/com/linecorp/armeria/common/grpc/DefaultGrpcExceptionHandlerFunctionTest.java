/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.FailFastException;

import io.grpc.Status;

class DefaultGrpcExceptionHandlerFunctionTest {

    @Test
    void failFastExceptionToUnavailableCode() {
        assertThat(GrpcExceptionHandlerFunction
                           .of()
                           .apply(null, new FailFastException(CircuitBreaker.ofDefaultName()), null)
                           .getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    void invalidProtocolBufferExceptionToInvalidArgumentCode() {
        assertThat(GrpcExceptionHandlerFunction
                           .of()
                           .apply(null, new InvalidProtocolBufferException("Failed to parse message"), null)
                           .getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
}
