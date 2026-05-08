/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds.validator;

import static java.util.Objects.requireNonNull;

import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A {@link RuntimeException} raised when an xDS resource fails validation.
 */
@UnstableApi
public final class XdsValidationException extends RuntimeException {

    private static final long serialVersionUID = -2825861127354315199L;

    /**
     * Returns a new {@link XdsValidationException} for the specified {@code resource} and {@code cause}.
     */
    public static XdsValidationException of(Message resource, Throwable cause) {
        requireNonNull(resource, "resource");
        requireNonNull(cause, "cause");
        return new XdsValidationException(
                resource.getDescriptorForType().getFullName() + ": " + cause.getMessage(), cause);
    }

    /**
     * Returns a new {@link XdsValidationException} for the specified {@code resource} with
     * the specified detail {@code message}.
     */
    public static XdsValidationException of(Message resource, String message) {
        requireNonNull(resource, "resource");
        requireNonNull(message, "message");
        return new XdsValidationException(
                resource.getDescriptorForType().getFullName() + ": " + message);
    }

    /**
     * Returns a new {@link XdsValidationException} with the specified detail {@code message}.
     */
    public static XdsValidationException of(String message) {
        requireNonNull(message, "message");
        return new XdsValidationException(message);
    }

    private XdsValidationException(String message) {
        super(message);
    }

    private XdsValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
