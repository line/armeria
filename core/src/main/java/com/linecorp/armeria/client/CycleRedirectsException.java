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
package com.linecorp.armeria.client;

import com.google.common.base.Joiner;

import com.linecorp.armeria.common.Flags;

/**
 * An exception indicating that a client detected cyclical redirections.
 * See <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-6.4">cyclical redirections</a>
 * for more information.
 */
public final class CycleRedirectsException extends RuntimeException {
    private static final long serialVersionUID = -2969770339558298361L;

    private static final Joiner joiner = Joiner.on(';');

    CycleRedirectsException(String originalPath) {
        super("The request path: " + originalPath);
    }

    CycleRedirectsException(String originalPath, Iterable<String> paths) {
        super("The initial request path: " + originalPath + ", redirect paths: " + joiner.join(paths));
    }

    @Override
    public Throwable fillInStackTrace() {
        if (Flags.verboseExceptionSampler().isSampled(getClass())) {
            return super.fillInStackTrace();
        }
        return this;
    }
}
