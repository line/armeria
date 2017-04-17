/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.internal.grpc;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.client.ResponseTimeoutException;

import io.grpc.Status;

/**
 * Utilities for handling {@link Status} in Armeria.
 */
public final class GrpcStatus {

    /**
     * Converts the {@link Throwable} to a {@link Status}, taking into account exceptions specific to Armeria as
     * well.
     */
    public static Status fromThrowable(Throwable t) {
        requireNonNull(t, "t");
        if (t instanceof ResponseTimeoutException) {
            return Status.DEADLINE_EXCEEDED.withCause(t);
        }
        return Status.fromThrowable(t);
    }

    private GrpcStatus() {}
}
