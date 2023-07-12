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
public final class HttpStatusAssert extends AbstractResponseAssert<HttpStatus> {

    HttpStatusAssert(HttpStatus actual, TestHttpResponse response) {
        super(actual, response);
    }

    public TestHttpResponse isEqualTo(HttpStatus expected) {
        requireNonNull(expected, "expected");
        assertEquals(expected, actual());
        return response();
    }

    public TestHttpResponse isContinue() {
        assertEquals(HttpStatus.CONTINUE, actual());
        return response();
    }

    public TestHttpResponse isSwitchingProtocols() {
        assertEquals(HttpStatus.SWITCHING_PROTOCOLS, actual());
        return response();
    }

    public TestHttpResponse isProcessing() {
        assertEquals(HttpStatus.PROCESSING, actual());
        return response();
    }

    public TestHttpResponse isOk() {
        assertEquals(HttpStatus.OK, actual());
        return response();
    }

    public TestHttpResponse isCreated() {
        assertEquals(HttpStatus.CREATED, actual());
        return response();
    }

    public TestHttpResponse isAccepted() {
        assertEquals(HttpStatus.ACCEPTED, actual());
        return response();
    }

    public TestHttpResponse isNonAuthoritativeInformation() {
        assertEquals(HttpStatus.NON_AUTHORITATIVE_INFORMATION, actual());
        return response();
    }

    public TestHttpResponse isNoContent() {
        assertEquals(HttpStatus.NO_CONTENT, actual());
        return response();
    }

    public TestHttpResponse isResetContent() {
        assertEquals(HttpStatus.RESET_CONTENT, actual());
        return response();
    }

    public TestHttpResponse isPartialContent() {
        assertEquals(HttpStatus.PARTIAL_CONTENT, actual());
        return response();
    }

    public TestHttpResponse isMultiStatus() {
        assertEquals(HttpStatus.MULTI_STATUS, actual());
        return response();
    }

    public TestHttpResponse isMultipleChoices() {
        assertEquals(HttpStatus.MULTIPLE_CHOICES, actual());
        return response();
    }

    public TestHttpResponse isMovedPermanently() {
        assertEquals(HttpStatus.MOVED_PERMANENTLY, actual());
        return response();
    }

    public TestHttpResponse isFound() {
        assertEquals(HttpStatus.FOUND, actual());
        return response();
    }

    public TestHttpResponse isSeeOther() {
        assertEquals(HttpStatus.SEE_OTHER, actual());
        return response();
    }

    public TestHttpResponse isNotModified() {
        assertEquals(HttpStatus.NOT_MODIFIED, actual());
        return response();
    }

    public TestHttpResponse isUseProxy() {
        assertEquals(HttpStatus.USE_PROXY, actual());
        return response();
    }

    public TestHttpResponse isTemporaryRedirect() {
        assertEquals(HttpStatus.TEMPORARY_REDIRECT, actual());
        return response();
    }

    public TestHttpResponse isBadRequest() {
        assertEquals(HttpStatus.BAD_REQUEST, actual());
        return response();
    }

    public TestHttpResponse isUnauthorized() {
        assertEquals(HttpStatus.UNAUTHORIZED, actual());
        return response();
    }

    public TestHttpResponse isPaymentRequired() {
        assertEquals(HttpStatus.PAYMENT_REQUIRED, actual());
        return response();
    }

    public TestHttpResponse isForbidden() {
        assertEquals(HttpStatus.FORBIDDEN, actual());
        return response();
    }

    public TestHttpResponse isNotFound() {
        assertEquals(HttpStatus.NOT_FOUND, actual());
        return response();
    }

    public TestHttpResponse isMethodNotAllowed() {
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, actual());
        return response();
    }

    public TestHttpResponse isNotAcceptable() {
        assertEquals(HttpStatus.NOT_ACCEPTABLE, actual());
        return response();
    }

    public TestHttpResponse isProxyAuthenticationRequired() {
        assertEquals(HttpStatus.PROXY_AUTHENTICATION_REQUIRED, actual());
        return response();
    }

    public TestHttpResponse isRequestTimeout() {
        assertEquals(HttpStatus.REQUEST_TIMEOUT, actual());
        return response();
    }

    public TestHttpResponse isConflict() {
        assertEquals(HttpStatus.CONFLICT, actual());
        return response();
    }

    public TestHttpResponse isGone() {
        assertEquals(HttpStatus.GONE, actual());
        return response();
    }

    public TestHttpResponse isLengthRequired() {
        assertEquals(HttpStatus.LENGTH_REQUIRED, actual());
        return response();
    }

    public TestHttpResponse isPreconditionFailed() {
        assertEquals(HttpStatus.PRECONDITION_FAILED, actual());
        return response();
    }

    public TestHttpResponse isRequestEntityTooLarge() {
        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, actual());
        return response();
    }

    public TestHttpResponse isRequestUriTooLong() {
        assertEquals(HttpStatus.REQUEST_URI_TOO_LONG, actual());
        return response();
    }

    public TestHttpResponse isUnsupportedMediaType() {
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, actual());
        return response();
    }

    public TestHttpResponse isRequestedRangeNotSatisfiable() {
        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, actual());
        return response();
    }

    public TestHttpResponse isExpectationFailed() {
        assertEquals(HttpStatus.EXPECTATION_FAILED, actual());
        return response();
    }

    public TestHttpResponse isMisdirectedRequest() {
        assertEquals(HttpStatus.MISDIRECTED_REQUEST, actual());
        return response();
    }

    public TestHttpResponse isUnprocessableEntity() {
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, actual());
        return response();
    }

    public TestHttpResponse isLocked() {
        assertEquals(HttpStatus.LOCKED, actual());
        return response();
    }

    public TestHttpResponse isFailedDependency() {
        assertEquals(HttpStatus.FAILED_DEPENDENCY, actual());
        return response();
    }

    public TestHttpResponse isUnorderedCollection() {
        assertEquals(HttpStatus.UNORDERED_COLLECTION, actual());
        return response();
    }

    public TestHttpResponse isUpgradeRequired() {
        assertEquals(HttpStatus.UPGRADE_REQUIRED, actual());
        return response();
    }

    public TestHttpResponse isPreconditionRequired() {
        assertEquals(HttpStatus.PRECONDITION_REQUIRED, actual());
        return response();
    }

    public TestHttpResponse isTooManyRequests() {
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, actual());
        return response();
    }

    public TestHttpResponse isRequestHeaderFieldsTooLarge() {
        assertEquals(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE, actual());
        return response();
    }

    public TestHttpResponse isClientClosedRequest() {
        assertEquals(HttpStatus.CLIENT_CLOSED_REQUEST, actual());
        return response();
    }

    public TestHttpResponse isInternalServerError() {
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, actual());
        return response();
    }

    public TestHttpResponse isNotImplemented() {
        assertEquals(HttpStatus.NOT_IMPLEMENTED, actual());
        return response();
    }

    public TestHttpResponse isBadGateway() {
        assertEquals(HttpStatus.BAD_GATEWAY, actual());
        return response();
    }

    public TestHttpResponse isServiceUnavailable() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, actual());
        return response();
    }

    public TestHttpResponse isGatewayTimeout() {
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, actual());
        return response();
    }

    public TestHttpResponse isHttpVersionNotSupported() {
        assertEquals(HttpStatus.HTTP_VERSION_NOT_SUPPORTED, actual());
        return response();
    }

    public TestHttpResponse isVariantAlsoNegotiates() {
        assertEquals(HttpStatus.VARIANT_ALSO_NEGOTIATES, actual());
        return response();
    }

    public TestHttpResponse isInsufficientStorage() {
        assertEquals(HttpStatus.INSUFFICIENT_STORAGE, actual());
        return response();
    }

    public TestHttpResponse isNotExtended() {
        assertEquals(HttpStatus.NOT_EXTENDED, actual());
        return response();
    }

    public TestHttpResponse isNetworkAuthenticationRequired() {
        assertEquals(HttpStatus.NETWORK_AUTHENTICATION_REQUIRED, actual());
        return response();
    }

    public TestHttpResponse isUnknown() {
        assertEquals(HttpStatus.UNKNOWN, actual());
        return response();
    }
}
