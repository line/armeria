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

package com.linecorp.armeria.internal.grpc;

import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

public final class StatusExceptionConverter {

    public static StatusRuntimeException toGrpc(ArmeriaStatusException armeria) {
        StatusRuntimeException converted = Status.fromCodeValue(armeria.getCode())
                                                 .withDescription(armeria.getMessage())
                                                 .withCause(armeria.getCause())
                                                 .asRuntimeException();
        converted.setStackTrace(armeria.getStackTrace());
        return converted;
    }

    private StatusExceptionConverter() {}
}
