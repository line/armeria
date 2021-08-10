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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.client.redirect.CyclicRedirectsException.addUris;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

/**
 * An exception indicating that
 * <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-6.4">automatic redirection</a> exceeds the
 * maximum limit number.
 *
 * @see RedirectConfigBuilder#maxRedirects(int)
 */
public final class TooManyRedirectsException extends RuntimeException {

    private static final long serialVersionUID = 3741211991690338730L;

    /**
     * Returns a new {@link TooManyRedirectsException}.
     */
    public static TooManyRedirectsException of(int maxRedirects, String originalUri, String... redirectUris) {
        return of(maxRedirects, originalUri,
                  ImmutableList.copyOf(requireNonNull(redirectUris, "redirectUris")));
    }

    /**
     * Returns a new {@link TooManyRedirectsException}.
     */
    public static TooManyRedirectsException of(int maxRedirects, String originalUri,
                                               Iterable<String> redirectUris) {
        checkArgument(maxRedirects > 0, "maxRedirects: %s (expected: > 0)", maxRedirects);
        requireNonNull(originalUri, "originalUri");
        requireNonNull(redirectUris, "redirectUris");
        checkArgument(!Iterables.isEmpty(redirectUris), "redirectUris can't be empty.");
        return new TooManyRedirectsException(maxRedirects, originalUri, redirectUris);
    }

    private TooManyRedirectsException(int maxRedirects, String originalUri, Iterable<String> redirectUris) {
        super(createMessage(maxRedirects, originalUri, redirectUris));
    }

    private static String createMessage(int maxRedirects, String originalUri, Iterable<String> redirectUris) {
        try (TemporaryThreadLocals threadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder sb = threadLocals.stringBuilder();
            sb.append("maxRedirects: ");
            sb.append(maxRedirects);
            sb.append(System.lineSeparator());
            addUris(sb, originalUri, redirectUris);
            return sb.toString();
        }
    }

    @Override
    public Throwable fillInStackTrace() {
        if (Flags.verboseExceptionSampler().isSampled(getClass())) {
            return super.fillInStackTrace();
        }
        return this;
    }
}
