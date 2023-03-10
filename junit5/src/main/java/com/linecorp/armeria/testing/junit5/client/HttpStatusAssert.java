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

import static com.google.common.base.Preconditions.checkState;

import com.linecorp.armeria.common.HttpStatus;

/**
 * Assertion methods for HttpStatus
 */
public final class HttpStatusAssert extends AssertThat<HttpStatus, TestHttpResponse> {

    HttpStatusAssert(HttpStatus actual, TestHttpResponse back) {
        super(actual, back);
    }

    public TestHttpResponse isEqualTo(HttpStatus expected) {
        checkState(actual().equals(expected), "\nexpected: %s\n but was: %s", expected, actual());
        return back();
    }


    public TestHttpResponse isContinue() {
        checkState(actual().equals(HttpStatus.CONTINUE), "\nexpected: %s\n but was: %s", HttpStatus.CONTINUE, actual());
        return back();
    }

    public TestHttpResponse isSwitchingProtocols() {
        checkState(actual().equals(HttpStatus.SWITCHING_PROTOCOLS), "\nexpected: %s\n but was: %s", HttpStatus.SWITCHING_PROTOCOLS, actual());
        return back();
    }

    public TestHttpResponse isProcessing() {
        checkState(actual().equals(HttpStatus.PROCESSING), "\nexpected: %s\n but was: %s", HttpStatus.PROCESSING, actual());
        return back();
    }

    public TestHttpResponse isOk() {
        checkState(actual().equals(HttpStatus.OK), "\nexpected: %s\n but was: %s", HttpStatus.OK, actual());
        return back();
    }

    public TestHttpResponse isCreated() {
        checkState(actual().equals(HttpStatus.CREATED), "\nexpected: %s\n but was: %s", HttpStatus.CREATED, actual());
        return back();
    }

    public TestHttpResponse isAccepted() {
        checkState(actual().equals(HttpStatus.ACCEPTED), "\nexpected: %s\n but was: %s", HttpStatus.ACCEPTED, actual());
        return back();
    }

    public TestHttpResponse isNonAuthoritativeInformation() {
        checkState(actual().equals(HttpStatus.NON_AUTHORITATIVE_INFORMATION), "\nexpected: %s\n but was: %s", HttpStatus.NON_AUTHORITATIVE_INFORMATION, actual());
        return back();
    }

    public TestHttpResponse isNoContent() {
        checkState(actual().equals(HttpStatus.NO_CONTENT), "\nexpected: %s\n but was: %s", HttpStatus.NO_CONTENT, actual());
        return back();
    }

    public TestHttpResponse isResetContent() {
        checkState(actual().equals(HttpStatus.RESET_CONTENT), "\nexpected: %s\n but was: %s", HttpStatus.RESET_CONTENT, actual());
        return back();
    }

    public TestHttpResponse isPartialContent() {
        checkState(actual().equals(HttpStatus.PARTIAL_CONTENT), "\nexpected: %s\n but was: %s", HttpStatus.PARTIAL_CONTENT, actual());
        return back();
    }

    public TestHttpResponse isMultiStatus() {
        checkState(actual().equals(HttpStatus.MULTI_STATUS), "\nexpected: %s\n but was: %s", HttpStatus.MULTI_STATUS, actual());
        return back();
    }

    public TestHttpResponse isMultipleChoices() {
        checkState(actual().equals(HttpStatus.MULTIPLE_CHOICES), "\nexpected: %s\n but was: %s", HttpStatus.MULTIPLE_CHOICES, actual());
        return back();
    }

    public TestHttpResponse isMovedPermanently() {
        checkState(actual().equals(HttpStatus.MOVED_PERMANENTLY), "\nexpected: %s\n but was: %s", HttpStatus.MOVED_PERMANENTLY, actual());
        return back();
    }

    public TestHttpResponse isFound() {
        checkState(actual().equals(HttpStatus.FOUND), "\nexpected: %s\n but was: %s", HttpStatus.FOUND, actual());
        return back();
    }

    public TestHttpResponse isSeeOther() {
        checkState(actual().equals(HttpStatus.SEE_OTHER), "\nexpected: %s\n but was: %s", HttpStatus.SEE_OTHER, actual());
        return back();
    }

    public TestHttpResponse isNotModified() {
        checkState(actual().equals(HttpStatus.NOT_MODIFIED), "\nexpected: %s\n but was: %s", HttpStatus.NOT_MODIFIED, actual());
        return back();
    }

    public TestHttpResponse isUseProxy() {
        checkState(actual().equals(HttpStatus.USE_PROXY), "\nexpected: %s\n but was: %s", HttpStatus.USE_PROXY, actual());
        return back();
    }

    public TestHttpResponse isTemporaryRedirect() {
        checkState(actual().equals(HttpStatus.TEMPORARY_REDIRECT), "\nexpected: %s\n but was: %s", HttpStatus.TEMPORARY_REDIRECT, actual());
        return back();
    }

    public TestHttpResponse isBadRequest() {
        checkState(actual().equals(HttpStatus.BAD_REQUEST), "\nexpected: %s\n but was: %s", HttpStatus.BAD_REQUEST, actual());
        return back();
    }

    public TestHttpResponse isUnauthorized() {
        checkState(actual().equals(HttpStatus.UNAUTHORIZED), "\nexpected: %s\n but was: %s", HttpStatus.UNAUTHORIZED, actual());
        return back();
    }

    public TestHttpResponse isPaymentRequired() {
        checkState(actual().equals(HttpStatus.PAYMENT_REQUIRED), "\nexpected: %s\n but was: %s", HttpStatus.PAYMENT_REQUIRED, actual());
        return back();
    }

    public TestHttpResponse isForbidden() {
        checkState(actual().equals(HttpStatus.FORBIDDEN), "\nexpected: %s\n but was: %s", HttpStatus.FORBIDDEN, actual());
        return back();
    }

    public TestHttpResponse isNotFound() {
        checkState(actual().equals(HttpStatus.NOT_FOUND), "\nexpected: %s\n but was: %s", HttpStatus.NOT_FOUND, actual());
        return back();
    }

    public TestHttpResponse isMethodNotAllowed() {
        checkState(actual().equals(HttpStatus.METHOD_NOT_ALLOWED), "\nexpected: %s\n but was: %s", HttpStatus.METHOD_NOT_ALLOWED, actual());
        return back();
    }

    public TestHttpResponse isNotAcceptable() {
        checkState(actual().equals(HttpStatus.NOT_ACCEPTABLE), "\nexpected: %s\n but was: %s", HttpStatus.NOT_ACCEPTABLE, actual());
        return back();
    }

    public TestHttpResponse isProxyAuthenticationRequired() {
        checkState(actual().equals(HttpStatus.PROXY_AUTHENTICATION_REQUIRED), "\nexpected: %s\n but was: %s", HttpStatus.PROXY_AUTHENTICATION_REQUIRED, actual());
        return back();
    }

    public TestHttpResponse isRequestTimeout() {
        checkState(actual().equals(HttpStatus.REQUEST_TIMEOUT), "\nexpected: %s\n but was: %s", HttpStatus.REQUEST_TIMEOUT, actual());
        return back();
    }

    public TestHttpResponse isConflict() {
        checkState(actual().equals(HttpStatus.CONFLICT), "\nexpected: %s\n but was: %s", HttpStatus.CONFLICT, actual());
        return back();
    }

    public TestHttpResponse isGone() {
        checkState(actual().equals(HttpStatus.GONE), "\nexpected: %s\n but was: %s", HttpStatus.GONE, actual());
        return back();
    }

    public TestHttpResponse isLengthRequired() {
        checkState(actual().equals(HttpStatus.LENGTH_REQUIRED), "\nexpected: %s\n but was: %s", HttpStatus.LENGTH_REQUIRED, actual());
        return back();
    }

    public TestHttpResponse isPreconditionFailed() {
        checkState(actual().equals(HttpStatus.PRECONDITION_FAILED), "\nexpected: %s\n but was: %s", HttpStatus.PRECONDITION_FAILED, actual());
        return back();
    }

    public TestHttpResponse isRequestEntityTooLarge() {
        checkState(actual().equals(HttpStatus.REQUEST_ENTITY_TOO_LARGE), "\nexpected: %s\n but was: %s", HttpStatus.REQUEST_ENTITY_TOO_LARGE, actual());
        return back();
    }

    public TestHttpResponse isRequestUriTooLong() {
        checkState(actual().equals(HttpStatus.REQUEST_URI_TOO_LONG), "\nexpected: %s\n but was: %s", HttpStatus.REQUEST_URI_TOO_LONG, actual());
        return back();
    }

    public TestHttpResponse isUnsupportedMediaType() {
        checkState(actual().equals(HttpStatus.UNSUPPORTED_MEDIA_TYPE), "\nexpected: %s\n but was: %s", HttpStatus.UNSUPPORTED_MEDIA_TYPE, actual());
        return back();
    }

    public TestHttpResponse isRequestedRangeNotSatisfiable() {
        checkState(actual().equals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE), "\nexpected: %s\n but was: %s", HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, actual());
        return back();
    }

    public TestHttpResponse isExpectationFailed() {
        checkState(actual().equals(HttpStatus.EXPECTATION_FAILED), "\nexpected: %s\n but was: %s", HttpStatus.EXPECTATION_FAILED, actual());
        return back();
    }

    public TestHttpResponse isMisdirectedRequest() {
        checkState(actual().equals(HttpStatus.MISDIRECTED_REQUEST), "\nexpected: %s\n but was: %s", HttpStatus.MISDIRECTED_REQUEST, actual());
        return back();
    }

    public TestHttpResponse isUnprocessableEntity() {
        checkState(actual().equals(HttpStatus.UNPROCESSABLE_ENTITY), "\nexpected: %s\n but was: %s", HttpStatus.UNPROCESSABLE_ENTITY, actual());
        return back();
    }

    public TestHttpResponse isLocked() {
        checkState(actual().equals(HttpStatus.LOCKED), "\nexpected: %s\n but was: %s", HttpStatus.LOCKED, actual());
        return back();
    }

    public TestHttpResponse isFailedDependency() {
        checkState(actual().equals(HttpStatus.FAILED_DEPENDENCY), "\nexpected: %s\n but was: %s", HttpStatus.FAILED_DEPENDENCY, actual());
        return back();
    }

    public TestHttpResponse isUnorderedCollection() {
        checkState(actual().equals(HttpStatus.UNORDERED_COLLECTION), "\nexpected: %s\n but was: %s", HttpStatus.UNORDERED_COLLECTION, actual());
        return back();
    }

    public TestHttpResponse isUpgradeRequired() {
        checkState(actual().equals(HttpStatus.UPGRADE_REQUIRED), "\nexpected: %s\n but was: %s", HttpStatus.UPGRADE_REQUIRED, actual());
        return back();
    }

    public TestHttpResponse isPreconditionRequired() {
        checkState(actual().equals(HttpStatus.PRECONDITION_REQUIRED), "\nexpected: %s\n but was: %s", HttpStatus.PRECONDITION_REQUIRED, actual());
        return back();
    }

    public TestHttpResponse isTooManyRequests() {
        checkState(actual().equals(HttpStatus.TOO_MANY_REQUESTS), "\nexpected: %s\n but was: %s", HttpStatus.TOO_MANY_REQUESTS, actual());
        return back();
    }

    public TestHttpResponse isRequestHeaderFieldsTooLarge() {
        checkState(actual().equals(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE), "\nexpected: %s\n but was: %s", HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE, actual());
        return back();
    }

    public TestHttpResponse isClientClosedRequest() {
        checkState(actual().equals(HttpStatus.CLIENT_CLOSED_REQUEST), "\nexpected: %s\n but was: %s", HttpStatus.CLIENT_CLOSED_REQUEST, actual());
        return back();
    }

    public TestHttpResponse isInternalServerError() {
        checkState(actual().equals(HttpStatus.INTERNAL_SERVER_ERROR), "\nexpected: %s\n but was: %s", HttpStatus.INTERNAL_SERVER_ERROR, actual());
        return back();
    }

    public TestHttpResponse isNotImplemented() {
        checkState(actual().equals(HttpStatus.NOT_IMPLEMENTED), "\nexpected: %s\n but was: %s", HttpStatus.NOT_IMPLEMENTED, actual());
        return back();
    }

    public TestHttpResponse isBadGateway() {
        checkState(actual().equals(HttpStatus.BAD_GATEWAY), "\nexpected: %s\n but was: %s", HttpStatus.BAD_GATEWAY, actual());
        return back();
    }

    public TestHttpResponse isServiceUnavailable() {
        checkState(actual().equals(HttpStatus.SERVICE_UNAVAILABLE), "\nexpected: %s\n but was: %s", HttpStatus.SERVICE_UNAVAILABLE, actual());
        return back();
    }

    public TestHttpResponse isGatewayTimeout() {
        checkState(actual().equals(HttpStatus.GATEWAY_TIMEOUT), "\nexpected: %s\n but was: %s", HttpStatus.GATEWAY_TIMEOUT, actual());
        return back();
    }

    public TestHttpResponse isHttpVersionNotSupported() {
        checkState(actual().equals(HttpStatus.HTTP_VERSION_NOT_SUPPORTED), "\nexpected: %s\n but was: %s", HttpStatus.HTTP_VERSION_NOT_SUPPORTED, actual());
        return back();
    }

    public TestHttpResponse isVariantAlsoNegotiates() {
        checkState(actual().equals(HttpStatus.VARIANT_ALSO_NEGOTIATES), "\nexpected: %s\n but was: %s", HttpStatus.VARIANT_ALSO_NEGOTIATES, actual());
        return back();
    }

    public TestHttpResponse isInsufficientStorage() {
        checkState(actual().equals(HttpStatus.INSUFFICIENT_STORAGE), "\nexpected: %s\n but was: %s", HttpStatus.INSUFFICIENT_STORAGE, actual());
        return back();
    }

    public TestHttpResponse isNotExtended() {
        checkState(actual().equals(HttpStatus.NOT_EXTENDED), "\nexpected: %s\n but was: %s", HttpStatus.NOT_EXTENDED, actual());
        return back();
    }

    public TestHttpResponse isNetworkAuthenticationRequired() {
        checkState(actual().equals(HttpStatus.NETWORK_AUTHENTICATION_REQUIRED), "\nexpected: %s\n but was: %s", HttpStatus.NETWORK_AUTHENTICATION_REQUIRED, actual());
        return back();
    }

    public TestHttpResponse isUnknown() {
        checkState(actual().equals(HttpStatus.UNKNOWN), "\nexpected: %s\n but was: %s", HttpStatus.UNKNOWN, actual());
        return back();
    }
}
