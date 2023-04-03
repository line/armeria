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

package com.linecorp.armeria.testing.junit5.client;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.linecorp.armeria.common.HttpStatus;

/**
 * Assertion methods for HttpStatus
 */
public final class HttpStatusAssert extends AssertThat<HttpStatus, TestHttpResponse> {

    HttpStatusAssert(HttpStatus actual, TestHttpResponse back) {
        super(actual, back);
    }

    public TestHttpResponse isEqualTo(HttpStatus expected) {
        requireNonNull(expected, "expected");
        assertEquals(expected, actual());
        return back();
    }

    public TestHttpResponse isContinue() {
        assertEquals(HttpStatus.CONTINUE, actual());
        return back();
    }

    public TestHttpResponse isSwitchingProtocols() {
        assertEquals(HttpStatus.SWITCHING_PROTOCOLS, actual());
        return back();
    }

    public TestHttpResponse isProcessing() {
        assertEquals(HttpStatus.PROCESSING, actual());
        return back();
    }

    public TestHttpResponse isOk() {
        assertEquals(HttpStatus.OK, actual());
        return back();
    }

    public TestHttpResponse isCreated() {
        assertEquals(HttpStatus.CREATED, actual());
        return back();
    }

    public TestHttpResponse isAccepted() {
        assertEquals(HttpStatus.ACCEPTED, actual());
        return back();
    }

    public TestHttpResponse isNonAuthoritativeInformation() {
        assertEquals(HttpStatus.NON_AUTHORITATIVE_INFORMATION, actual());
        return back();
    }

    public TestHttpResponse isNoContent() {
        assertEquals(HttpStatus.NO_CONTENT, actual());
        return back();
    }

    public TestHttpResponse isResetContent() {
        assertEquals(HttpStatus.RESET_CONTENT, actual());
        return back();
    }

    public TestHttpResponse isPartialContent() {
        assertEquals(HttpStatus.PARTIAL_CONTENT, actual());
        return back();
    }

    public TestHttpResponse isMultiStatus() {
        assertEquals(HttpStatus.MULTI_STATUS, actual());
        return back();
    }

    public TestHttpResponse isMultipleChoices() {
        assertEquals(HttpStatus.MULTIPLE_CHOICES, actual());
        return back();
    }

    public TestHttpResponse isMovedPermanently() {
        assertEquals(HttpStatus.MOVED_PERMANENTLY, actual());
        return back();
    }

    public TestHttpResponse isFound() {
        assertEquals(HttpStatus.FOUND, actual());
        return back();
    }

    public TestHttpResponse isSeeOther() {
        assertEquals(HttpStatus.SEE_OTHER, actual());
        return back();
    }

    public TestHttpResponse isNotModified() {
        assertEquals(HttpStatus.NOT_MODIFIED, actual());
        return back();
    }

    public TestHttpResponse isUseProxy() {
        assertEquals(HttpStatus.USE_PROXY, actual());
        return back();
    }

    public TestHttpResponse isTemporaryRedirect() {
        assertEquals(HttpStatus.TEMPORARY_REDIRECT, actual());
        return back();
    }

    public TestHttpResponse isBadRequest() {
        assertEquals(HttpStatus.BAD_REQUEST, actual());
        return back();
    }

    public TestHttpResponse isUnauthorized() {
        assertEquals(HttpStatus.UNAUTHORIZED, actual());
        return back();
    }

    public TestHttpResponse isPaymentRequired() {
        assertEquals(HttpStatus.PAYMENT_REQUIRED, actual());
        return back();
    }

    public TestHttpResponse isForbidden() {
        assertEquals(HttpStatus.FORBIDDEN, actual());
        return back();
    }

    public TestHttpResponse isNotFound() {
        assertEquals(HttpStatus.NOT_FOUND, actual());
        return back();
    }

    public TestHttpResponse isMethodNotAllowed() {
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, actual());
        return back();
    }

    public TestHttpResponse isNotAcceptable() {
        assertEquals(HttpStatus.NOT_ACCEPTABLE, actual());
        return back();
    }

    public TestHttpResponse isProxyAuthenticationRequired() {
        assertEquals(HttpStatus.PROXY_AUTHENTICATION_REQUIRED, actual());
        return back();
    }

    public TestHttpResponse isRequestTimeout() {
        assertEquals(HttpStatus.REQUEST_TIMEOUT, actual());
        return back();
    }

    public TestHttpResponse isConflict() {
        assertEquals(HttpStatus.CONFLICT, actual());
        return back();
    }

    public TestHttpResponse isGone() {
        assertEquals(HttpStatus.GONE, actual());
        return back();
    }

    public TestHttpResponse isLengthRequired() {
        assertEquals(HttpStatus.LENGTH_REQUIRED, actual());
        return back();
    }

    public TestHttpResponse isPreconditionFailed() {
        assertEquals(HttpStatus.PRECONDITION_FAILED, actual());
        return back();
    }

    public TestHttpResponse isRequestEntityTooLarge() {
        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, actual());
        return back();
    }

    public TestHttpResponse isRequestUriTooLong() {
        assertEquals(HttpStatus.REQUEST_URI_TOO_LONG, actual());
        return back();
    }

    public TestHttpResponse isUnsupportedMediaType() {
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, actual());
        return back();
    }

    public TestHttpResponse isRequestedRangeNotSatisfiable() {
        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, actual());
        return back();
    }

    public TestHttpResponse isExpectationFailed() {
        assertEquals(HttpStatus.EXPECTATION_FAILED, actual());
        return back();
    }

    public TestHttpResponse isMisdirectedRequest() {
        assertEquals(HttpStatus.MISDIRECTED_REQUEST, actual());
        return back();
    }

    public TestHttpResponse isUnprocessableEntity() {
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, actual());
        return back();
    }

    public TestHttpResponse isLocked() {
        assertEquals(HttpStatus.LOCKED, actual());
        return back();
    }

    public TestHttpResponse isFailedDependency() {
        assertEquals(HttpStatus.FAILED_DEPENDENCY, actual());
        return back();
    }

    public TestHttpResponse isUnorderedCollection() {
        assertEquals(HttpStatus.UNORDERED_COLLECTION, actual());
        return back();
    }

    public TestHttpResponse isUpgradeRequired() {
        assertEquals(HttpStatus.UPGRADE_REQUIRED, actual());
        return back();
    }

    public TestHttpResponse isPreconditionRequired() {
        assertEquals(HttpStatus.PRECONDITION_REQUIRED, actual());
        return back();
    }

    public TestHttpResponse isTooManyRequests() {
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, actual());
        return back();
    }

    public TestHttpResponse isRequestHeaderFieldsTooLarge() {
        assertEquals(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE, actual());
        return back();
    }

    public TestHttpResponse isClientClosedRequest() {
        assertEquals(HttpStatus.CLIENT_CLOSED_REQUEST, actual());
        return back();
    }

    public TestHttpResponse isInternalServerError() {
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, actual());
        return back();
    }

    public TestHttpResponse isNotImplemented() {
        assertEquals(HttpStatus.NOT_IMPLEMENTED, actual());
        return back();
    }

    public TestHttpResponse isBadGateway() {
        assertEquals(HttpStatus.BAD_GATEWAY, actual());
        return back();
    }

    public TestHttpResponse isServiceUnavailable() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, actual());
        return back();
    }

    public TestHttpResponse isGatewayTimeout() {
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, actual());
        return back();
    }

    public TestHttpResponse isHttpVersionNotSupported() {
        assertEquals(HttpStatus.HTTP_VERSION_NOT_SUPPORTED, actual());
        return back();
    }

    public TestHttpResponse isVariantAlsoNegotiates() {
        assertEquals(HttpStatus.VARIANT_ALSO_NEGOTIATES, actual());
        return back();
    }

    public TestHttpResponse isInsufficientStorage() {
        assertEquals(HttpStatus.INSUFFICIENT_STORAGE, actual());
        return back();
    }

    public TestHttpResponse isNotExtended() {
        assertEquals(HttpStatus.NOT_EXTENDED, actual());
        return back();
    }

    public TestHttpResponse isNetworkAuthenticationRequired() {
        assertEquals(HttpStatus.NETWORK_AUTHENTICATION_REQUIRED, actual());
        return back();
    }

    public TestHttpResponse isUnknown() {
        assertEquals(HttpStatus.UNKNOWN, actual());
        return back();
    }
}
