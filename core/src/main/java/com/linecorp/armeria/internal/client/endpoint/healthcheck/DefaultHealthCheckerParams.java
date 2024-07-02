/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.internal.client.endpoint.healthcheck;

import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.annotation.Nullable;

public final class DefaultHealthCheckerParams {

    private final String path;
    private final HttpMethod httpMethod;
    @Nullable
    private final String host;

    DefaultHealthCheckerParams(String path, HttpMethod httpMethod, @Nullable String host) {
        this.path = path;
        this.httpMethod = httpMethod;
        this.host = host;
    }

    String path() {
        return path;
    }

    HttpMethod httpMethod() {
        return httpMethod;
    }

    @Nullable
    String host() {
        return host;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("path", path)
                          .add("httpMethod", httpMethod)
                          .add("host", host)
                          .toString();
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final DefaultHealthCheckerParams that = (DefaultHealthCheckerParams) object;
        return Objects.equals(path, that.path) && httpMethod == that.httpMethod &&
               Objects.equals(host, that.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, httpMethod, host);
    }
}
