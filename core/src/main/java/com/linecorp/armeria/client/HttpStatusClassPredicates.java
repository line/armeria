/*
 * Copyright 2022 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.function.Predicate;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;

final class HttpStatusClassPredicates implements Predicate<HttpStatus> {

    private static final HttpStatusClassPredicates INFORMATIONAL_PREDICATE =
            new HttpStatusClassPredicates(HttpStatusClass.INFORMATIONAL);

    private static final HttpStatusClassPredicates SUCCESS_PREDICATE =
            new HttpStatusClassPredicates(HttpStatusClass.SUCCESS);

    private static final HttpStatusClassPredicates REDIRECTION_PREDICATE =
            new HttpStatusClassPredicates(HttpStatusClass.REDIRECTION);

    private static final HttpStatusClassPredicates CLIENT_ERROR_PREDICATE =
            new HttpStatusClassPredicates(HttpStatusClass.CLIENT_ERROR);

    private static final HttpStatusClassPredicates SERVER_ERROR_PREDICATE =
            new HttpStatusClassPredicates(HttpStatusClass.SERVER_ERROR);

    private static final HttpStatusClassPredicates UNKNOWN_PREDICATE =
            new HttpStatusClassPredicates(HttpStatusClass.UNKNOWN);

    static HttpStatusClassPredicates of(HttpStatusClass httpStatusClass) {
        switch (httpStatusClass) {
            case INFORMATIONAL:
                return INFORMATIONAL_PREDICATE;
            case SUCCESS:
                return SUCCESS_PREDICATE;
            case REDIRECTION:
                return REDIRECTION_PREDICATE;
            case CLIENT_ERROR:
                return CLIENT_ERROR_PREDICATE;
            case SERVER_ERROR:
                return SERVER_ERROR_PREDICATE;
            default:
                return UNKNOWN_PREDICATE;
        }
    }

    private final HttpStatusClass httpStatusClass;

    private HttpStatusClassPredicates(HttpStatusClass httpStatusClass) {
        this.httpStatusClass = requireNonNull(httpStatusClass, "httpStatusClass");
    }

    @Override
    public boolean test(HttpStatus status) {
        return httpStatusClass.contains(status);
    }

    HttpStatusClass statusClass() {
        return httpStatusClass;
    }
}
