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
package com.linecorp.armeria.common.util;

import static java.lang.invoke.MethodType.methodType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;

import com.linecorp.armeria.common.annotation.Nullable;

final class GrpcExceptions {

    @Nullable
    private static final Class<?> STATUS_RUNTIME_EXCEPTION_CLASS;
    @Nullable
    private static final Class<?> STATUS_EXCEPTION_CLASS;
    @Nullable
    private static final MethodHandle GET_STATUS_FROM_STATUS_RUNTIME_EXCEPTION_MH;
    @Nullable
    private static final MethodHandle GET_STATUS_FROM_STATUS_EXCEPTION_MH;
    @Nullable
    private static final MethodHandle GET_CODE_MH;
    @Nullable
    private static final MethodHandle GET_VALUE_MH;

    static {
        Class<?> statusRuntimeException = null;
        Class<?> statusException = null;
        MethodHandle getStatusFromStatusRuntimeExceptionMH = null;
        MethodHandle getStatusFromStatusExceptionMH = null;
        MethodHandle getCodeMH = null;
        MethodHandle getValueMH = null;
        try {
            final Class<?> status = Class.forName(
                    "io.grpc.Status", false, GrpcExceptions.class.getClassLoader());
            final Class<?> code = Class.forName(
                    "io.grpc.Status$Code", false, GrpcExceptions.class.getClassLoader());
            statusRuntimeException = Class.forName(
                    "io.grpc.StatusRuntimeException", false, GrpcExceptions.class.getClassLoader());
            statusException = Class.forName(
                    "io.grpc.StatusException", false, GrpcExceptions.class.getClassLoader());
            final Lookup lookup = MethodHandles.publicLookup();
            getStatusFromStatusRuntimeExceptionMH =
                    lookup.findVirtual(statusRuntimeException, "getStatus", methodType(status));
            getStatusFromStatusExceptionMH =
                    lookup.findVirtual(statusException, "getStatus", methodType(status));
            getCodeMH = lookup.findVirtual(status, "getCode", methodType(code));
            getValueMH = lookup.findVirtual(code, "value", methodType(int.class));
        } catch (Throwable t) {
            // gRPC is not used.
        }

        STATUS_RUNTIME_EXCEPTION_CLASS = statusRuntimeException;
        STATUS_EXCEPTION_CLASS = statusException;
        GET_STATUS_FROM_STATUS_RUNTIME_EXCEPTION_MH = getStatusFromStatusRuntimeExceptionMH;
        GET_STATUS_FROM_STATUS_EXCEPTION_MH = getStatusFromStatusExceptionMH;
        GET_CODE_MH = getCodeMH;
        GET_VALUE_MH = getValueMH;
    }

    static boolean isGrpcCancel(Throwable cause) {
        if (GET_VALUE_MH == null) {
            // gRPC is not used.
            return false;
        }

        if (cause.getClass() == STATUS_RUNTIME_EXCEPTION_CLASS) {
            try {
                final Object status = GET_STATUS_FROM_STATUS_RUNTIME_EXCEPTION_MH.invoke(cause);
                return isGrpcCancel(status);
            } catch (Throwable e) {
                return false;
            }
        }

        if (cause.getClass() == STATUS_EXCEPTION_CLASS) {
            try {
                final Object status = GET_STATUS_FROM_STATUS_EXCEPTION_MH.invoke(cause);
                return isGrpcCancel(status);
            } catch (Throwable e) {
                return false;
            }
        }

        return false;
    }

    private static boolean isGrpcCancel(Object status) throws Throwable {
        final Object code = GET_CODE_MH.invoke(status);
        final int value = (int) GET_VALUE_MH.invoke(code);
        return value == 1;
    }

    private GrpcExceptions() {}
}
