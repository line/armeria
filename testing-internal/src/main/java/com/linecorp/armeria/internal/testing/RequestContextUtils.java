/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.internal.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.fail;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLog;

public final class RequestContextUtils {
    private RequestContextUtils() {}

    @FunctionalInterface
    public interface RequestLogVerifier {
        void verifyChildLog(RequestLog childLog) throws Exception;
    }

    public static final RequestLogVerifier VERIFY_NOTHING = childLog -> {
        // No verification is performed.
    };

    public static RequestLogVerifier verifyAllValid(RequestLogVerifier... childLogVerifiers) {
        return childLog -> {
            for (RequestLogVerifier childLogVerifier : childLogVerifiers) {
                childLogVerifier.verifyChildLog(childLog);
            }
        };
    }

    public static RequestLogVerifier verifyExactlyOneValid(RequestLogVerifier... childLogVerifiers) {
        return childLog -> {
            final Throwable[] verifierCauses = new Throwable[childLogVerifiers.length];

            for (int i = 0; i < childLogVerifiers.length; i++) {
                final int index = i;
                verifierCauses[i] = catchThrowable(() -> childLogVerifiers[index].verifyChildLog(childLog));
            }

            final List<Throwable> nonNullVerifierCauses = Arrays.stream(verifierCauses)
                                                                .filter(Objects::nonNull)
                                                                .collect(Collectors.toList());

            if (nonNullVerifierCauses.size() != childLogVerifiers.length - 1) {
                final Throwable allCauses = nonNullVerifierCauses.get(0);

                for (int i = 1; i < nonNullVerifierCauses.size(); i++) {
                    allCauses.addSuppressed(nonNullVerifierCauses.get(i));
                }

                fail(allCauses);
            }
        };
    }

    public static RequestLogVerifier verifyStatusCode(HttpStatus expectedStatus) {
        return childLog -> assertThat(childLog.responseHeaders().status()).isEqualTo(expectedStatus);
    }

    public static RequestLogVerifier verifyUnprocessedRequestException() {
        return verifyAllValid(
                verifyStatusCode(HttpStatus.UNKNOWN),
                verifyResponseCause(UnprocessedRequestException.class)
        );
    }

    public static RequestLogVerifier verifyResponseCause(Class<?> expectedResponseCauseClass) {
        return childLog -> {
            assertThat(childLog.responseCause()).isExactlyInstanceOf(expectedResponseCauseClass);
        };
    }

    public static RequestLogVerifier verifyResponseCause(Throwable expectedCause) {
        return childLog -> {
            assertThat(childLog.responseCause()).isSameAs(expectedCause);
        };
    }

    public static RequestLogVerifier verifyResponseHeader(String headerName,
                                                           String expectedHeaderValue) {
        return childLog -> {
            final ResponseHeaders headers = childLog.responseHeaders();
            assertThat(headers.get(headerName)).isEqualTo(expectedHeaderValue);
        };
    }

    public static RequestLogVerifier verifyResponseTrailer(String headerName,
                                                           String expectedHeaderValue) {
        return childLog -> {
            assertThat(childLog.responseTrailers().get(headerName)).isEqualTo(expectedHeaderValue);
        };
    }

    public static void assertValidClientRequestContext(ClientRequestContext ctx,
                                                       RequestLogVerifier... childLogVerifiers) {
        assertValidClientRequestContextWithVerifier(ctx, childLogVerifiers);
    }

    public static void assertValidClientRequestContextWithParentLogVerifier(
            ClientRequestContext ctx,
            RequestLogVerifier parentLogVerifier,
            RequestLogVerifier... childLogVerifiers) {
        assertValidClientRequestContextWithVerifier(ctx, parentLogVerifier, childLogVerifiers);
    }

    private static void assertValidClientRequestContextWithVerifier(
            ClientRequestContext ctx,
            RequestLogVerifier[] childLogVerifiers) {
        if (childLogVerifiers.length == 0) {
            childLogVerifiers = new RequestLogVerifier[ctx.log().children().size()];
            Arrays.fill(childLogVerifiers, VERIFY_NOTHING);
        }

        assertValidClientRequestContextWithVerifier(
                ctx,
                childLogVerifiers.length == 0 ?
                VERIFY_NOTHING : childLogVerifiers[childLogVerifiers.length - 1], childLogVerifiers
        );
    }

    private static void assertValidClientRequestContextWithVerifier(
            ClientRequestContext ctx,
            RequestLogVerifier parentLogVerifier,
            RequestLogVerifier[] childLogVerifiers) {
        final int expectedNumRequests = childLogVerifiers.length;

            assertThat(ctx.log().isComplete()).isTrue();
            assertThat(ctx.log().children()).hasSize(expectedNumRequests);

            if (expectedNumRequests == 0) {
                return;
            }

            for (int childLogIndex = 0; childLogIndex < expectedNumRequests; childLogIndex++) {
                final RequestLog childLog = ctx.log().children().get(childLogIndex).whenComplete().join();
                assertThat(childLog).isNotNull();
                assertThat(childLog.isComplete()).isTrue();
                assertThat(childLog.children()).isEmpty();
                assertThat(childLog.requestContent()).isNull();
                assertThat(childLog.responseContent()).isNull();
                assertThat(childLog.rawResponseContent()).isNull();
                assertThat(childLog.responseContentPreview()).isNull();
                try {
                    childLogVerifiers[childLogIndex].verifyChildLog(childLog);
                } catch (Throwable e) {
                    fail("Failed to verify child log (" + (childLogIndex + 1) +
                         '/' + expectedNumRequests + ')', e);
                }
            }
            try {
                parentLogVerifier.verifyChildLog(ctx.log().partial());
            } catch (Throwable e) {
                fail("Failed to verify parent log", e);
            }
    }
}
