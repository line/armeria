/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http.auth;

import static java.util.Objects.requireNonNull;

import java.util.function.Predicate;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.server.Service;

/**
 * A default implementation of {@link HttpAuthService}.
 */
final class HttpAuthServiceImpl extends HttpAuthService {

    private final Predicate<? super HttpHeaders>[] predicates;

    HttpAuthServiceImpl(Service<? super HttpRequest, ? extends HttpResponse> delegate,
                        Predicate<? super HttpHeaders>... predicates) {
        super(delegate);
        for (Predicate<? super HttpHeaders> predicate : predicates) {
            requireNonNull(predicate);
        }
        this.predicates = predicates;
    }

    @Override
    public boolean authorize(HttpHeaders headers) {
        for (Predicate<? super HttpHeaders> predicate : predicates) {
            if (predicate.test(headers)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).addValue(predicates).toString();
    }
}
