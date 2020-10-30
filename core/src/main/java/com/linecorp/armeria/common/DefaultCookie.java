/*
 * Copyright 2019 LINE Corporation
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
/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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
package com.linecorp.armeria.common;

import java.util.Objects;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

/**
 * The default {@link Cookie} implementation.
 */
final class DefaultCookie implements Cookie {

    // Forked from netty-4.1.43
    // https://github.com/netty/netty/blob/9d45f514a47ee8ad5259ee782fcca240017fc3a3/codec-http/src/main/java/io/netty/handler/codec/http/cookie/DefaultCookie.java

    private final String name;
    private final String value;
    private final boolean valueQuoted;
    @Nullable
    private final String domain;
    @Nullable
    private final String path;
    private final long maxAge;
    private final boolean secure;
    private final boolean httpOnly;
    private final boolean hostOnly;
    @Nullable
    private final String sameSite;
    private final long createdMillis;

    DefaultCookie(String name, String value, boolean valueQuoted, @Nullable String domain,
                  @Nullable String path, long maxAge, boolean secure, boolean httpOnly, boolean hostOnly,
                  @Nullable String sameSite) {
        this.name = name;
        this.value = value;
        this.valueQuoted = valueQuoted;
        this.domain = domain;
        this.path = path;
        this.maxAge = maxAge;
        this.secure = secure;
        this.httpOnly = httpOnly;
        this.hostOnly = hostOnly;
        this.sameSite = sameSite;
        createdMillis = System.currentTimeMillis();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String value() {
        return value;
    }

    @Override
    public boolean isValueQuoted() {
        return valueQuoted;
    }

    @Override
    public String domain() {
        return domain;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public long maxAge() {
        return maxAge;
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public boolean isHttpOnly() {
        return httpOnly;
    }

    @Override
    public String sameSite() {
        return sameSite;
    }

    @Override
    public boolean isHostOnly() {
        return hostOnly;
    }

    @Override
    public boolean isExpired() {
        if (maxAge == UNDEFINED_MAX_AGE) {
            return false;
        }
        if (maxAge <= 0) {
            return true;
        }
        final double timePassed = (System.currentTimeMillis() - createdMillis) / 1000.0;
        return timePassed > maxAge;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof Cookie)) {
            return false;
        }

        final Cookie that = (Cookie) o;
        if (!name.equals(that.name()) ||
            !value.equals(that.value()) ||
            !Objects.equals(path, that.path())) {
            return false;
        }

        if (domain() == null) {
            return that.domain() == null;
        } else {
            return domain().equalsIgnoreCase(that.domain());
        }
    }

    @Override
    public String toString() {
        final ToStringHelper helper = MoreObjects.toStringHelper(this).omitNullValues()
                                                 .add("name", name)
                                                 .add("value", !value.isEmpty() ? value : "<EMPTY>")
                                                 .add("valueQuoted", valueQuoted)
                                                 .add("domain", domain)
                                                 .add("path", path);

        if (maxAge != Cookie.UNDEFINED_MAX_AGE) {
            helper.add("maxAge", maxAge);
        }

        if (secure) {
            helper.addValue("secure");
        }

        if (httpOnly) {
            helper.addValue("httpOnly");
        }

        helper.add("sameSite", sameSite);
        return helper.toString();
    }
}
