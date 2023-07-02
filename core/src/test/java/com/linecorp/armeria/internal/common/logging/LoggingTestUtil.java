/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.internal.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import java.util.concurrent.atomic.AtomicReference;

import org.mockito.invocation.DescribedInvocation;
import org.mockito.invocation.InvocationOnMock;
import org.slf4j.Logger;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.Exceptions;

public final class LoggingTestUtil {

    public static Logger newMockLogger(RequestContext ctx, AtomicReference<Throwable> capturedCause) {
        return mock(Logger.class, withSettings().invocationListeners(report -> {
            final DescribedInvocation describedInvocation = report.getInvocation();
            if (!(describedInvocation instanceof InvocationOnMock)) {
                return;
            }

            final InvocationOnMock invocation = (InvocationOnMock) describedInvocation;
            final Object[] arguments = invocation.getArguments();
            if (arguments.length == 0) {
                return;
            }
            if (arguments[0] == null || "".equals(arguments[0])) {
                // Invoked at verification phase
                return;
            }

            switch (invocation.getMethod().getName()) {
                case "trace":
                case "debug":
                case "info":
                case "warn":
                case "error":
                    try {
                        assertThat((RequestContext) RequestContext.current()).isSameAs(ctx);
                    } catch (Throwable cause) {
                        capturedCause.set(cause);
                    }
            }
        }));
    }

    public static void throwIfCaptured(AtomicReference<Throwable> capturedCause) {
        final Throwable cause = capturedCause.get();
        if (cause != null) {
            capturedCause.set(null);
            Exceptions.throwUnsafely(cause);
        }
    }

    private LoggingTestUtil() {}
}
