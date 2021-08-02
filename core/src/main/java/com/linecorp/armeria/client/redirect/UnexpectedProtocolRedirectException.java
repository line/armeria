/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.client.redirect;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.Set;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.SessionProtocol;

/**
 * An exception indicating that the {@linkplain URI#getScheme()} session protocol} of the redirection URI
 * is not allowed to redirect.
 *
 * @see RedirectConfigBuilder#allowProtocols(Iterable)
 */
public final class UnexpectedProtocolRedirectException extends RuntimeException {

    private static final long serialVersionUID = -3766002689876499315L;

    /**
     * Returns a new {@link UnexpectedProtocolRedirectException}.
     */
    public static UnexpectedProtocolRedirectException of(SessionProtocol redirectProtocol,
                                                         Set<SessionProtocol> expectedProtocols) {
        requireNonNull(redirectProtocol, "redirectProtocol");
        requireNonNull(expectedProtocols, "expectedProtocols");
        return new UnexpectedProtocolRedirectException(redirectProtocol, expectedProtocols);
    }

    private UnexpectedProtocolRedirectException(SessionProtocol redirectProtocol,
                                                Set<SessionProtocol> expectedProtocols) {
        super("redirectProtocol: " + redirectProtocol + " (expected: " + expectedProtocols + ')');
    }

    @Override
    public Throwable fillInStackTrace() {
        if (Flags.verboseExceptionSampler().isSampled(getClass())) {
            return super.fillInStackTrace();
        }
        return this;
    }
}
