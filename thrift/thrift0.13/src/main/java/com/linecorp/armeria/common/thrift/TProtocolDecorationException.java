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

package com.linecorp.armeria.common.thrift;

import org.apache.thrift.protocol.TProtocol;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An exception that indicates a {@link TProtocol} was not successfully decorated.
 */
@UnstableApi
public final class TProtocolDecorationException extends RuntimeException {

    private static final long serialVersionUID = -5734593879633758514L;

    /**
     * Creates a new {@link TProtocolDecorationException} with the specified cause.
     *
     * @param isRequest whether the decoration failed for a request or a response protocol.
     */
    public TProtocolDecorationException(boolean isRequest, Throwable cause) {
        super("Failed to decorate the " + (isRequest ? "request" : "response") + " protocol", cause);
    }
}
