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

package com.linecorp.armeria.common.grpc;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Strings;

/**
 * A {@link RuntimeException} reconstructed from debug information in a failed gRPC
 * response, containing information about the cause of the exception at the server
 * side.
 */
public final class StatusCauseException extends RuntimeException {

    private static final long serialVersionUID = -1792808536714102039L;

    private final String originalClassName;
    private final String originalMessage;

    /**
     * Constructs a {@link StatusCauseException} from the information in the {@link ThrowableProto}.
     */
    public StatusCauseException(ThrowableProto proto) {
        super(requireNonNull(proto, "proto").getOriginalClassName() + ": " + proto.getOriginalMessage());

        this.originalClassName = proto.getOriginalClassName();
        this.originalMessage = proto.getOriginalMessage();

        if (proto.getStackTraceCount() > 0) {
            setStackTrace(proto.getStackTraceList().stream()
                               .map(StatusCauseException::deserializeStackTraceElement)
                               .toArray(StackTraceElement[]::new));
        }

        if (proto.hasCause()) {
            initCause(new StatusCauseException(proto.getCause()));
        }
    }

    /**
     * Returns the class name of the original exception in the server.
     */
    public String getOriginalClassName() {
        return originalClassName;
    }

    /**
     * Returns the message attached to the original exception in the server.
     */
    public String getOriginalMessage() {
        return originalMessage;
    }

    private static StackTraceElement deserializeStackTraceElement(StackTraceElementProto proto) {
        return new StackTraceElement(
                proto.getClassName(),
                proto.getMethodName(),
                Strings.emptyToNull(proto.getFileName()),
                proto.getLineNumber());
    }

    @Override
    public Throwable fillInStackTrace() {
        // We set the stack trace based on ThrowableProto so don't need to fill it automatically.
        return this;
    }
}
