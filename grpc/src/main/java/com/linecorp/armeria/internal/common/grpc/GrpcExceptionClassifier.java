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
package com.linecorp.armeria.internal.common.grpc;

import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.internal.common.util.ExceptionClassifier;

import io.grpc.Status.Code;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

public final class GrpcExceptionClassifier implements ExceptionClassifier {

    @Override
    public boolean isStreamCancelling(Throwable cause) {
        if (cause instanceof StatusRuntimeException) {
            return ((StatusRuntimeException) cause).getStatus().getCode() == Code.CANCELLED;
        }

        if (cause instanceof StatusException) {
            return ((StatusException) cause).getStatus().getCode() == Code.CANCELLED;
        }

        if (cause instanceof ArmeriaStatusException) {
            return ((ArmeriaStatusException) cause).getCode() == Code.CANCELLED.value();
        }
        return false;
    }
}
