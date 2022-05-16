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

package com.linecorp.armeria.common.auth;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.armeria.internal.common.PercentEncoder.encodeComponent;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

/**
 * The bearer token of <a href="https://oauth.net/core/1.0a/#anchor12">OAuth 1.0a authentication</a>.
 */
public final class OAuth1aToken extends AuthToken {

    /**
     * The realm parameter. (optional)
     */
    static final String REALM = "realm";

    /**
     * The oauth_consumer_key parameter.
     */
    static final String OAUTH_CONSUMER_KEY = "oauth_consumer_key";

    /**
     * The oauth_token parameter.
     */
    static final String OAUTH_TOKEN = "oauth_token";

    /**
     * The oauth_signature_method parameter.
     */
    static final String OAUTH_SIGNATURE_METHOD = "oauth_signature_method";

    /**
     * The oauth_signature parameter.
     */
    static final String OAUTH_SIGNATURE = "oauth_signature";

    /**
     * The oauth_timestamp parameter.
     */
    static final String OAUTH_TIMESTAMP = "oauth_timestamp";

    /**
     * The oauth_nonce parameter.
     */
    static final String OAUTH_NONCE = "oauth_nonce";

    /**
     * The version parameter.
     */
    static final String OAUTH_VERSION = "version";

    /**
     * Returns a new {@link OAuth1aTokenBuilder}.
     *
     * @deprecated use {@link AuthToken#builderForOAuth1a()} instead.
     */
    @Deprecated
    public static OAuth1aTokenBuilder builder() {
        return new OAuth1aTokenBuilder();
    }

    private final String consumerKey;
    private final String token;
    private final String signatureMethod;
    private final String signature;
    private final String timestamp;
    private final String nonce;
    private final String version;
    private final Map<String, String> additionals;
    @Nullable
    private final String realm;

    @Nullable
    private String headerValue;

    OAuth1aToken(String consumerKey, String token, String signatureMethod, String signature,
                 String timestamp, String nonce, String version, Map<String, String> additionals,
                 @Nullable String realm) {
        this.consumerKey = consumerKey;
        this.token = token;
        this.signatureMethod = signatureMethod;
        this.signature = signature;
        this.timestamp = timestamp;
        this.nonce = nonce;
        this.version = version;
        this.additionals = additionals;
        this.realm = realm;
    }

    /**
     * Returns the value of the {@value #REALM} property.
     */
    @Nullable
    public String realm() {
        return realm;
    }

    /**
     * Returns the value of the {@value #OAUTH_CONSUMER_KEY} property.
     */
    public String consumerKey() {
        return consumerKey;
    }

    /**
     * Returns the value of the {@value #OAUTH_TOKEN} property.
     */
    public String token() {
        return token;
    }

    /**
     * Returns the value of {@value #OAUTH_SIGNATURE_METHOD} property.
     */
    public String signatureMethod() {
        return signatureMethod;
    }

    /**
     * Returns the value of {@value #OAUTH_SIGNATURE} property.
     */
    public String signature() {
        return signature;
    }

    /**
     * Returns the value of {@value #OAUTH_TIMESTAMP} property.
     */
    public String timestamp() {
        return timestamp;
    }

    /**
     * Returns the value of {@value #OAUTH_NONCE} property.
     */
    public String nonce() {
        return nonce;
    }

    /**
     * Returns the value of {@value #OAUTH_VERSION} property.
     * If not set, returns the default value of {@code "1.0"}.
     */
    public String version() {
        return version;
    }

    /**
     * Returns additional (or user-defined) parameters.
     */
    public Map<String, String> additionals() {
        return additionals;
    }

    /**
     * Returns the string that is sent as the value of the {@link HttpHeaderNames#AUTHORIZATION} header.
     */
    @Override
    public String asHeaderValue() {
        if (headerValue != null) {
            return headerValue;
        }
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder builder = tempThreadLocals.stringBuilder();
            builder.append("OAuth ");
            if (!isNullOrEmpty(realm)) {
                appendValue(builder, REALM, realm, true);
            }

            appendValue(builder, OAUTH_CONSUMER_KEY, consumerKey, true);
            appendValue(builder, OAUTH_TOKEN, token, true);
            appendValue(builder, OAUTH_SIGNATURE_METHOD, signatureMethod, true);
            appendValue(builder, OAUTH_SIGNATURE, signature, true);
            appendValue(builder, OAUTH_TIMESTAMP, timestamp, true);
            appendValue(builder, OAUTH_NONCE, nonce, true);
            appendValue(builder, OAUTH_VERSION, version, false);
            for (Entry<String, String> entry : additionals.entrySet()) {
                builder.append(',');
                appendValue(builder, entry.getKey(), entry.getValue(), false);
            }

            return headerValue = builder.toString();
        }
    }

    private static void appendValue(StringBuilder builder, String key, String value, boolean addComma) {
        builder.append(key);
        builder.append("=\"");
        encodeComponent(builder, value);
        if (addComma) {
            builder.append("\",");
        } else {
            builder.append('"');
        }
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OAuth1aToken)) {
            return false;
        }
        final OAuth1aToken that = (OAuth1aToken) o;

        // Do not short-circuit to make it hard to guess anything from timing.
        boolean equals = Objects.equals(realm, that.realm);
        equals &= consumerKey.equals(that.consumerKey);
        equals &= token.equals(that.token);
        equals &= signatureMethod.equals(that.signatureMethod);
        equals &= signature.equals(that.signature);
        equals &= timestamp.equals(that.timestamp);
        equals &= nonce.equals(that.nonce);
        equals &= version.equals(that.version);
        equals &= Objects.equals(additionals, that.additionals);
        return equals;
    }

    @Override
    public int hashCode() {
        return Objects.hash(realm, consumerKey, token, signatureMethod, signature, timestamp, nonce,
                            version, additionals);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("realm", realm)
                          .add("consumerKey", consumerKey)
                          .add("token", "****")
                          .add("signatureMethod", signatureMethod)
                          .add("signature", signature)
                          .add("timestamp", timestamp)
                          .add("nonce", nonce)
                          .add("version", version)
                          .add("additionals", additionals)
                          .toString();
    }
}
