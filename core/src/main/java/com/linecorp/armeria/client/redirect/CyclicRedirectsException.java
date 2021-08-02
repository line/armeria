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
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

/**
 * An exception indicating that a client detected cyclical redirections.
 * See <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-6.4">cyclical redirections</a>
 * for more information.
 */
public final class CyclicRedirectsException extends RuntimeException {

    private static final long serialVersionUID = -2969770339558298361L;

    /**
     * Returns a new {@link CyclicRedirectsException}.
     */
    public static CyclicRedirectsException of(String originalUri, String... redirectUris) {
        return of(originalUri, ImmutableList.copyOf(requireNonNull(redirectUris, "redirectUris")));
    }

    /**
     * Returns a new {@link CyclicRedirectsException}.
     */
    public static CyclicRedirectsException of(String originalUri, Iterable<String> redirectUris) {
        requireNonNull(redirectUris, "redirectUris");
        checkArgument(!Iterables.isEmpty(redirectUris), "redirectUris can't be empty.");
        return new CyclicRedirectsException(requireNonNull(originalUri, "originalUri"), redirectUris);
    }

    private CyclicRedirectsException(String originalUri, Iterable<String> paths) {
        super(createMessage(originalUri, paths));
    }

    private static String createMessage(String originalUri, Iterable<String> redirectUris) {
        try (TemporaryThreadLocals threadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder sb = threadLocals.stringBuilder();
            sb.append("The original URI: ");
            sb.append(originalUri);
            sb.append(System.lineSeparator());
            addRedirectUris(sb, redirectUris);
            return sb.toString();
        }
    }

    static void addRedirectUris(StringBuilder sb, Iterable<String> redirectUris) {
        sb.append("redirect URIs:");
        sb.append(System.lineSeparator());
        for (String path : redirectUris) {
            sb.append('\t');
            sb.append(path);
            sb.append(System.lineSeparator());
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
