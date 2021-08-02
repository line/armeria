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

import com.linecorp.armeria.common.Flags;

/**
 * An exception indicating that the {@linkplain URI#getHost() host component} of the redirection URI
 * is not allowed to redirect.
 *
 * @see RedirectConfigBuilder#allowDomains(Iterable)
 */
public final class UnexpectedDomainRedirectException extends RuntimeException {

    private static final long serialVersionUID = 3127736510630287566L;

    /**
     * Returns a new {@link UnexpectedDomainRedirectException}.
     */
    public static UnexpectedDomainRedirectException of(String domain) {
        requireNonNull(domain, "domain");
        return new UnexpectedDomainRedirectException(domain);
    }

    private UnexpectedDomainRedirectException(String domain) {
        super(domain + " is not allowed to redirect.");
    }

    @Override
    public Throwable fillInStackTrace() {
        if (Flags.verboseExceptionSampler().isSampled(getClass())) {
            return super.fillInStackTrace();
        }
        return this;
    }
}
