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

import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.RequestLog;

public final class RequestContextUtils {
    private RequestContextUtils() {}

    @FunctionalInterface
    public interface RequestLogVerifier {
        void verifyLog(RequestLog requestLog) throws Exception;
    }

    public static final RequestLogVerifier VERIFY_NOTHING = childLog -> {
        // No verification is performed.
    };

    public static RequestLogVerifier verifyAllVerifierValid(RequestLogVerifier... childLogVerifiers) {
        return childLog -> {
            for (RequestLogVerifier childLogVerifier : childLogVerifiers) {
                childLogVerifier.verifyLog(childLog);
            }
        };
    }

    public static RequestLogVerifier verifyExactlyOneVerifierValid(RequestLogVerifier... childLogVerifiers) {
        return childLog -> {
            final Throwable[] verifierCauses = new Throwable[childLogVerifiers.length];

            for (int i = 0; i < childLogVerifiers.length; i++) {
                final int index = i;
                verifierCauses[i] = catchThrowable(() -> childLogVerifiers[index].verifyLog(childLog));
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
        return verifyAllVerifierValid(
                verifyStatusCode(HttpStatus.UNKNOWN),
                verifyResponseCause(UnprocessedRequestException.class)
        );
    }

    public static RequestLogVerifier verifyRequestCause(Class<?> expectedCauseClass) {
        return childLog -> {
            assertThat(childLog.requestCause()).isExactlyInstanceOf(expectedCauseClass);
        };
    }

    public static RequestLogVerifier verifyResponseCause(Class<?> expectedCauseClass) {
        return childLog -> {
            assertThat(childLog.responseCause()).isExactlyInstanceOf(expectedCauseClass);
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

    public static RequestLogVerifier verifyResponseContent(String expectedResponseContent) {
        return childLog -> {
            assertThat(childLog.responseContent()).isExactlyInstanceOf(String.class);
            assertThat(childLog.responseContent()).isEqualTo(expectedResponseContent);
        };
    }

    public static void assertValidRequestContext(RequestContext ctx,
                                                 RequestLogVerifier... childLogVerifiers) {
        assertValidRequestContextWithVerifier(ctx, childLogVerifiers);
    }

    public static void assertValidRequestContextWithParentLogVerifier(
            RequestContext ctx,
            RequestLogVerifier parentLogVerifier,
            RequestLogVerifier... childLogVerifiers) {
        assertValidRequestContextWithVerifier(ctx, parentLogVerifier, childLogVerifiers);
    }

    private static void assertValidRequestContextWithVerifier(
            RequestContext ctx,
            RequestLogVerifier[] childLogVerifiers) {
        if (childLogVerifiers.length == 0) {
            childLogVerifiers = new RequestLogVerifier[ctx.log().children().size()];
            Arrays.fill(childLogVerifiers, VERIFY_NOTHING);
        }

        assertValidRequestContextWithVerifier(
                ctx,
                childLogVerifiers.length == 0 ?
                VERIFY_NOTHING
                        : verifyAllVerifierValid(
                        childLog -> {
                            // Default parent log verifier.
                            final HttpRequest req = ctx.request();
                            assertThat(req).isNotNull();
                            assert req != null;
                            assertThat(req.isComplete()).isTrue();

                            if (ctx.rpcRequest() != null) {
                                final HttpRequest lastHttpReq =
                                        ctx.log().children()
                                           .get(ctx.log().children().size() - 1).context().request();

                                if (lastHttpReq != null) {
                                    assertThat(lastHttpReq).isSameAs(ctx.log().context().request());
                                }
                            }
                        },
                        childLogVerifiers[childLogVerifiers.length - 1]
                ), childLogVerifiers
        );
    }

    private static void assertValidRequestContextWithVerifier(
            RequestContext ctx,
            RequestLogVerifier parentLogVerifier,
            RequestLogVerifier[] childLogVerifiers) {
        final int expectedNumRequests = childLogVerifiers.length;
        assertThat(ctx.log().isComplete()).isTrue();
        assertThat(ctx.log().children()).hasSize(expectedNumRequests);

        for (int childLogIndex = 0; childLogIndex < expectedNumRequests; childLogIndex++) {
            final RequestLog childLog = ctx.log().children().get(childLogIndex).whenComplete().join();
            assertThat(childLog).isNotNull();
            assertThat(childLog.isComplete()).isTrue();
            assertThat(childLog.children()).isEmpty();
            if (ctx.rpcRequest() != null) {
                assertThat(childLog.requestContent()).isInstanceOf(RpcRequest.class);
                assertThat(childLog.responseContent()).isInstanceOf(RpcResponse.class);
            }

            try {
                childLogVerifiers[childLogIndex].verifyLog(childLog);
            } catch (Throwable e) {
                fail("Failed to verify child log (" + (childLogIndex + 1) +
                     '/' + expectedNumRequests + ')', e);
            }
        }

        try {
            parentLogVerifier.verifyLog(ctx.log().partial());
        } catch (Throwable e) {
            fail("Failed to verify parent log", e);
        }
    }
}
