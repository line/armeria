/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.common.brave;

import static java.util.Objects.requireNonNull;

import java.util.regex.Pattern;

import com.google.common.collect.ImmutableList;

import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.Builder;
import brave.propagation.CurrentTraceContext.ScopeDecorator;

/**
 * A builder of {@link RequestContextCurrentTraceContext} to enable tracing of an Armeria-based application.
 */
public final class RequestContextCurrentTraceContextBuilder extends CurrentTraceContext.Builder {

    private final ImmutableList.Builder<Pattern> nonRequestThreadPatterns = ImmutableList.builder();

    private boolean scopeDecoratorAdded;

    RequestContextCurrentTraceContextBuilder() {}

    /**
     * Sets a regular expression that matches names of threads that should be considered non-request
     * threads, meaning they may have spans created for clients outside of the context of an Armeria
     * request. For example, this can be set to {@code "RMI TCP Connection"} if you use RMI to serve
     * monitoring requests.
     *
     * @see RequestContextCurrentTraceContext#setCurrentThreadNotRequestThread(boolean)
     */
    public RequestContextCurrentTraceContextBuilder nonRequestThread(String pattern) {
        requireNonNull(pattern, "pattern");
        final Pattern compiled  = Pattern.compile(pattern);
        return nonRequestThread(compiled);
    }

    /**
     * Sets a regular expression that matches names of threads that should be considered non-request
     * threads, meaning they may have spans created for clients outside of the context of an Armeria
     * request. For example, this can be set to {@code Pattern.compile("RMI TCP Connection")} if you use
     * RMI to serve monitoring requests.
     *
     * @see RequestContextCurrentTraceContext#setCurrentThreadNotRequestThread(boolean)
     */
    public RequestContextCurrentTraceContextBuilder nonRequestThread(Pattern pattern) {
        nonRequestThreadPatterns.add(requireNonNull(pattern, "pattern"));
        return this;
    }

    @Override
    public Builder addScopeDecorator(ScopeDecorator scopeDecorator) {
        // a null ScopeDecorator will be checked by the super class.
        if (scopeDecorator != null && scopeDecorator != ScopeDecorator.NOOP) {
            scopeDecoratorAdded = true;
        }
        return super.addScopeDecorator(scopeDecorator);
    }

    /**
     * Returns a newly-created {@link RequestContextCurrentTraceContext} based on the configuration properties
     * set so far.
     */
    @Override
    public RequestContextCurrentTraceContext build() {
        return new RequestContextCurrentTraceContext(this, nonRequestThreadPatterns.build(),
                                                     scopeDecoratorAdded);
    }
}
