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

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpStatus;

final class HttpStatusPredicate implements Predicate<HttpStatus> {
    private static final ImmutableMap<HttpStatus, HttpStatusPredicate> httpStatusPredicateMap;

    static {
        final ImmutableMap.Builder<HttpStatus, HttpStatusPredicate> builder =
                ImmutableMap.builderWithExpectedSize(1000);
        for (int i = 0; i < 1000; i++) {
            final HttpStatus status = HttpStatus.valueOf(i);
            builder.put(status, new HttpStatusPredicate(HttpStatus.valueOf(i)));
        }
        httpStatusPredicateMap = builder.build();
    }

    static HttpStatusPredicate of(HttpStatus httpStatus) {
        if (httpStatus.code() < 0 || httpStatus.code() >= 1000) {
            return new HttpStatusPredicate(httpStatus);
        } else {
            final HttpStatusPredicate httpStatusPredicate = httpStatusPredicateMap.get(httpStatus);
            return requireNonNull(httpStatusPredicate, "httpStatusPredicate");
        }
    }

    private final HttpStatus status;

    private HttpStatusPredicate(HttpStatus status) {
        this.status = requireNonNull(status, "status");
    }

    @Override
    public boolean test(HttpStatus status) {
        return this.status.equals(status);
    }

    HttpStatus status() {
        return status;
    }
}
