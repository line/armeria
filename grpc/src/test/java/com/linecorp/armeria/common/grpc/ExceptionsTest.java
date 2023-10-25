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
package com.linecorp.armeria.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.util.Exceptions;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

class ExceptionsTest {

    @Test
    void cancelledStatus() {
        assertThat(Exceptions.isStreamCancelling(new StatusRuntimeException(Status.CANCELLED))).isTrue();
        assertThat(Exceptions.isStreamCancelling(new StatusRuntimeException(Status.INTERNAL))).isFalse();
        assertThat(Exceptions.isStreamCancelling(new StatusException(Status.CANCELLED))).isTrue();
        assertThat(Exceptions.isStreamCancelling(new StatusException(Status.INTERNAL))).isFalse();
        assertThat(Exceptions.isStreamCancelling(
                new ArmeriaStatusException(Status.CANCELLED.getCode().value(), null))).isTrue();
        assertThat(Exceptions.isStreamCancelling(
                new ArmeriaStatusException(Status.INTERNAL.getCode().value(), null))).isFalse();
    }
}
