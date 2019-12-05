/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.auth;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

/**
 * The bearer token of <a href="https://en.wikipedia.org/wiki/Basic_access_authentication">HTTP basic access authentication</a>.
 */
public final class BasicToken {

    /**
     * Creates a new {@link BasicToken} from the given {@code username} and {@code password}.
     */
    public static BasicToken of(String username, String password) {
        return new BasicToken(username, password);
    }

    private final String username;
    private final String password;

    private BasicToken(String username, String password) {
        this.username = requireNonNull(username, "username");
        this.password = requireNonNull(password, "password");
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BasicToken)) {
            return false;
        }
        final BasicToken that = (BasicToken) o;
        // Note that we used '&' intentionally to make it hard to guess anything from timing.
        return secureEquals(username, that.username) &
               secureEquals(password, that.password);
    }

    static boolean secureEquals(@Nullable String a, @Nullable String b) {
        final int aLength = a != null ? a.length() : 0;
        final int bLength = b != null ? b.length() : 0;
        final int length = Math.min(aLength, bLength);
        int result = 0;
        for (int i = 0; i < length; i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0 && aLength == bLength;
    }

    @Override
    public int hashCode() {
        return Objects.hash(username, password);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("username", username)
                          .add("password", "****")
                          .toString();
    }
}
