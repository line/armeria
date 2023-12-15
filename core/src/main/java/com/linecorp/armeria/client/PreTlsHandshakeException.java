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

package com.linecorp.armeria.client;

import javax.net.ssl.SSLException;

import com.linecorp.armeria.common.Flags;

/**
 * An {@link SSLException} raised before starting a TLS handshake.
 */
public final class PreTlsHandshakeException extends SSLException {

    private static final long serialVersionUID = -4425286273254997423L;

    /**
     * Creates a new instance.
     */
    public PreTlsHandshakeException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public Throwable fillInStackTrace() {
        if (Flags.verboseExceptionSampler().isSampled(getClass())) {
            super.fillInStackTrace();
        }
        return this;
    }
}
