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

package com.linecorp.armeria.internal.common.grpc;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

public final class StatusExceptionConverter {

    public static StatusRuntimeException toGrpc(ArmeriaStatusException armeria) {
        requireNonNull(armeria, "armeria");
        final StatusRuntimeException converted = Status.fromCodeValue(armeria.getCode())
                                                       .withDescription(armeria.getMessage())
                                                       .withCause(armeria.getCause())
                                                       .asRuntimeException();
        // NB(anuraaga): We end up filling the stacktrace of the exception twice, which is unfortunate since
        // generating a stacktrace is expensive. Ideally, we could call the StatusRuntimeException with the
        // toggle for filling in the stacktrace, but it is not a public API. Other approaches could be to
        // subclass StatusRuntimeException (which will probably just cause upstream to declare the class final
        // :P) or include ArmeriaStatusException in the cause chain of Status that we return from Armeria. Both
        // diverge from upstream behavior slightly and for now we assume the performance hit isn't something to
        // worry about since it only affects exceptions.
        converted.setStackTrace(armeria.getStackTrace());
        return converted;
    }

    private StatusExceptionConverter() {}
}
