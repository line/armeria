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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;

/**
 * Assertion methods for HttpStatus.
 */
public final class HttpStatusAssert extends AbstractResponseAssert<HttpStatus> {
    HttpStatusAssert(HttpStatus actual, TestHttpResponse response) {
        super(actual, response);
    }

    /**
     * Asserts that the class of actual {@link HttpStatus} is equal to the
     * {@link HttpStatusClass#INFORMATIONAL}.
     */
    public TestHttpResponse is1xxInformational() {
        assertEquals(HttpStatusClass.INFORMATIONAL, actual().codeClass());
        return response();
    }

    /**
     * Asserts that the class of actual {@link HttpStatus} is equal to the {@link HttpStatusClass#SUCCESS}.
     */
    public TestHttpResponse is2xxSuccessful() {
        assertEquals(HttpStatusClass.SUCCESS, actual().codeClass());
        return response();
    }

    /**
     * Asserts that the class of actual {@link HttpStatus} is equal to the {@link HttpStatusClass#REDIRECTION}.
     */
    public TestHttpResponse is3xxRedirection() {
        assertEquals(HttpStatusClass.REDIRECTION, actual().codeClass());
        return response();
    }

    /**
     * Asserts that the class of actual {@link HttpStatus} is equal to the {@link HttpStatusClass#CLIENT_ERROR}.
     */
    public TestHttpResponse is4xxClientError() {
        assertEquals(HttpStatusClass.CLIENT_ERROR, actual().codeClass());
        return response();
    }

    /**
     * Asserts that the class of actual {@link HttpStatus} is equal to the {@link HttpStatusClass#SERVER_ERROR}.
     */
    public TestHttpResponse is5xxServerError() {
        assertEquals(HttpStatusClass.SERVER_ERROR, actual().codeClass());
        return response();
    }

    /**
     * Asserts that the actual {@link HttpStatus} is equal to the {@link HttpStatus#OK}.
     */
    public TestHttpResponse isOk() {
        assertEquals(HttpStatus.OK, actual());
        return response();
    }

    /**
     * Asserts that the actual {@link HttpStatus} is equal to the {@link HttpStatus#CREATED}.
     */
    public TestHttpResponse isCreated() {
        assertEquals(HttpStatus.CREATED, actual());
        return response();
    }

    /**
     * Asserts that the actual {@link HttpStatus} is equal to the {@link HttpStatus#BAD_REQUEST}.
     */
    public TestHttpResponse isBadRequest() {
        assertEquals(HttpStatus.BAD_REQUEST, actual());
        return response();
    }

    /**
     * Asserts that the actual {@link HttpStatus} is equal to the {@link HttpStatus#NOT_FOUND}.
     */
    public TestHttpResponse isNotFound() {
        assertEquals(HttpStatus.NOT_FOUND, actual());
        return response();
    }

    /**
     * Asserts that the actual {@link HttpStatus} is equal to the {@link HttpStatus#INTERNAL_SERVER_ERROR}.
     */
    public TestHttpResponse isInternalServerError() {
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, actual());
        return response();
    }

    /**
     * Asserts that the actual {@link HttpStatus} is equal to the {@link HttpStatus#SERVICE_UNAVAILABLE}.
     */
    public TestHttpResponse isServiceUnavailable() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, actual());
        return response();
    }
}
