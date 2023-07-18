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
 * Assertion methods for HttpStatus.
 */
public final class HttpStatusAssert extends AbstractResponseAssert<HttpStatus> {

    HttpStatusAssert(HttpStatus actual, TestHttpResponse response) {
        super(actual, response);
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the given one.
     * The {@code expected} cannot be null.
     */
    public TestHttpResponse isEqualTo(HttpStatus expected) {
        requireNonNull(expected, "expected");
        assertEquals(expected, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#CONTINUE}.
     */
    public TestHttpResponse isContinue() {
        assertEquals(HttpStatus.CONTINUE, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#SWITCHING_PROTOCOLS}.
     */
    public TestHttpResponse isSwitchingProtocols() {
        assertEquals(HttpStatus.SWITCHING_PROTOCOLS, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#PROCESSING}.
     */
    public TestHttpResponse isProcessing() {
        assertEquals(HttpStatus.PROCESSING, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#OK}.
     */
    public TestHttpResponse isOk() {
        assertEquals(HttpStatus.OK, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#CREATED}.
     */
    public TestHttpResponse isCreated() {
        assertEquals(HttpStatus.CREATED, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#ACCEPTED}.
     */
    public TestHttpResponse isAccepted() {
        assertEquals(HttpStatus.ACCEPTED, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the
     * {@link HttpStatus#NON_AUTHORITATIVE_INFORMATION}.
     */
    public TestHttpResponse isNonAuthoritativeInformation() {
        assertEquals(HttpStatus.NON_AUTHORITATIVE_INFORMATION, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#NO_CONTENT}.
     */
    public TestHttpResponse isNoContent() {
        assertEquals(HttpStatus.NO_CONTENT, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#RESET_CONTENT}.
     */
    public TestHttpResponse isResetContent() {
        assertEquals(HttpStatus.RESET_CONTENT, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#PARTIAL_CONTENT}.
     */
    public TestHttpResponse isPartialContent() {
        assertEquals(HttpStatus.PARTIAL_CONTENT, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#MULTI_STATUS}.
     */
    public TestHttpResponse isMultiStatus() {
        assertEquals(HttpStatus.MULTI_STATUS, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#MULTIPLE_CHOICES}.
     */
    public TestHttpResponse isMultipleChoices() {
        assertEquals(HttpStatus.MULTIPLE_CHOICES, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#MOVED_PERMANENTLY}.
     */
    public TestHttpResponse isMovedPermanently() {
        assertEquals(HttpStatus.MOVED_PERMANENTLY, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#FOUND}.
     */
    public TestHttpResponse isFound() {
        assertEquals(HttpStatus.FOUND, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#SEE_OTHER}.
     */
    public TestHttpResponse isSeeOther() {
        assertEquals(HttpStatus.SEE_OTHER, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#NOT_MODIFIED}.
     */
    public TestHttpResponse isNotModified() {
        assertEquals(HttpStatus.NOT_MODIFIED, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#USE_PROXY}.
     */
    public TestHttpResponse isUseProxy() {
        assertEquals(HttpStatus.USE_PROXY, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#TEMPORARY_REDIRECT}.
     */
    public TestHttpResponse isTemporaryRedirect() {
        assertEquals(HttpStatus.TEMPORARY_REDIRECT, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#BAD_REQUEST}.
     */
    public TestHttpResponse isBadRequest() {
        assertEquals(HttpStatus.BAD_REQUEST, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#UNAUTHORIZED}.
     */
    public TestHttpResponse isUnauthorized() {
        assertEquals(HttpStatus.UNAUTHORIZED, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#PAYMENT_REQUIRED}.
     */
    public TestHttpResponse isPaymentRequired() {
        assertEquals(HttpStatus.PAYMENT_REQUIRED, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#FORBIDDEN}.
     */
    public TestHttpResponse isForbidden() {
        assertEquals(HttpStatus.FORBIDDEN, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#NOT_FOUND}.
     */
    public TestHttpResponse isNotFound() {
        assertEquals(HttpStatus.NOT_FOUND, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#METHOD_NOT_ALLOWED}.
     */
    public TestHttpResponse isMethodNotAllowed() {
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#NOT_ACCEPTABLE}.
     */
    public TestHttpResponse isNotAcceptable() {
        assertEquals(HttpStatus.NOT_ACCEPTABLE, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the
     * {@link HttpStatus#PROXY_AUTHENTICATION_REQUIRED}.
     */
    public TestHttpResponse isProxyAuthenticationRequired() {
        assertEquals(HttpStatus.PROXY_AUTHENTICATION_REQUIRED, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#REQUEST_TIMEOUT}.
     */
    public TestHttpResponse isRequestTimeout() {
        assertEquals(HttpStatus.REQUEST_TIMEOUT, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#CONFLICT}.
     */
    public TestHttpResponse isConflict() {
        assertEquals(HttpStatus.CONFLICT, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#GONE}.
     */
    public TestHttpResponse isGone() {
        assertEquals(HttpStatus.GONE, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#LENGTH_REQUIRED}.
     */
    public TestHttpResponse isLengthRequired() {
        assertEquals(HttpStatus.LENGTH_REQUIRED, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#PRECONDITION_FAILED}.
     */
    public TestHttpResponse isPreconditionFailed() {
        assertEquals(HttpStatus.PRECONDITION_FAILED, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#REQUEST_ENTITY_TOO_LARGE}.
     */
    public TestHttpResponse isRequestEntityTooLarge() {
        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#REQUEST_URI_TOO_LONG}.
     */
    public TestHttpResponse isRequestUriTooLong() {
        assertEquals(HttpStatus.REQUEST_URI_TOO_LONG, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#UNSUPPORTED_MEDIA_TYPE}.
     */
    public TestHttpResponse isUnsupportedMediaType() {
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the
     * {@link HttpStatus#REQUESTED_RANGE_NOT_SATISFIABLE}.
     */
    public TestHttpResponse isRequestedRangeNotSatisfiable() {
        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#EXPECTATION_FAILED}.
     */
    public TestHttpResponse isExpectationFailed() {
        assertEquals(HttpStatus.EXPECTATION_FAILED, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#MISDIRECTED_REQUEST}.
     */
    public TestHttpResponse isMisdirectedRequest() {
        assertEquals(HttpStatus.MISDIRECTED_REQUEST, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#UNPROCESSABLE_ENTITY}.
     */
    public TestHttpResponse isUnprocessableEntity() {
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#LOCKED}.
     */
    public TestHttpResponse isLocked() {
        assertEquals(HttpStatus.LOCKED, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#FAILED_DEPENDENCY}.
     */
    public TestHttpResponse isFailedDependency() {
        assertEquals(HttpStatus.FAILED_DEPENDENCY, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#UNORDERED_COLLECTION}.
     */
    public TestHttpResponse isUnorderedCollection() {
        assertEquals(HttpStatus.UNORDERED_COLLECTION, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#UPGRADE_REQUIRED}.
     */
    public TestHttpResponse isUpgradeRequired() {
        assertEquals(HttpStatus.UPGRADE_REQUIRED, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#PRECONDITION_REQUIRED}.
     */
    public TestHttpResponse isPreconditionRequired() {
        assertEquals(HttpStatus.PRECONDITION_REQUIRED, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#TOO_MANY_REQUESTS}.
     */
    public TestHttpResponse isTooManyRequests() {
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the
     * {@link HttpStatus#REQUEST_HEADER_FIELDS_TOO_LARGE}.
     */
    public TestHttpResponse isRequestHeaderFieldsTooLarge() {
        assertEquals(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#CLIENT_CLOSED_REQUEST}.
     */
    public TestHttpResponse isClientClosedRequest() {
        assertEquals(HttpStatus.CLIENT_CLOSED_REQUEST, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#INTERNAL_SERVER_ERROR}.
     */
    public TestHttpResponse isInternalServerError() {
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#NOT_IMPLEMENTED}.
     */
    public TestHttpResponse isNotImplemented() {
        assertEquals(HttpStatus.NOT_IMPLEMENTED, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#BAD_GATEWAY}.
     */
    public TestHttpResponse isBadGateway() {
        assertEquals(HttpStatus.BAD_GATEWAY, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#SERVICE_UNAVAILABLE}.
     */
    public TestHttpResponse isServiceUnavailable() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#GATEWAY_TIMEOUT}.
     */
    public TestHttpResponse isGatewayTimeout() {
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the
     * {@link HttpStatus#HTTP_VERSION_NOT_SUPPORTED}.
     */
    public TestHttpResponse isHttpVersionNotSupported() {
        assertEquals(HttpStatus.HTTP_VERSION_NOT_SUPPORTED, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#VARIANT_ALSO_NEGOTIATES}.
     */
    public TestHttpResponse isVariantAlsoNegotiates() {
        assertEquals(HttpStatus.VARIANT_ALSO_NEGOTIATES, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#INSUFFICIENT_STORAGE}.
     */
    public TestHttpResponse isInsufficientStorage() {
        assertEquals(HttpStatus.INSUFFICIENT_STORAGE, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#NOT_EXTENDED}.
     */
    public TestHttpResponse isNotExtended() {
        assertEquals(HttpStatus.NOT_EXTENDED, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the
     * {@link HttpStatus#NETWORK_AUTHENTICATION_REQUIRED}.
     */
    public TestHttpResponse isNetworkAuthenticationRequired() {
        assertEquals(HttpStatus.NETWORK_AUTHENTICATION_REQUIRED, actual());
        return response();
    }

    /**
     * Verifies that the actual {@link HttpStatus} is equal to the {@link HttpStatus#UNKNOWN}.
     */
    public TestHttpResponse isUnknown() {
        assertEquals(HttpStatus.UNKNOWN, actual());
        return response();
    }
}
