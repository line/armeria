/*
 * Copyright 2020 LINE Corporation
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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.armeria.common.auth.OAuth1aToken.OAUTH_CONSUMER_KEY;
import static com.linecorp.armeria.common.auth.OAuth1aToken.OAUTH_NONCE;
import static com.linecorp.armeria.common.auth.OAuth1aToken.OAUTH_SIGNATURE;
import static com.linecorp.armeria.common.auth.OAuth1aToken.OAUTH_SIGNATURE_METHOD;
import static com.linecorp.armeria.common.auth.OAuth1aToken.OAUTH_TIMESTAMP;
import static com.linecorp.armeria.common.auth.OAuth1aToken.OAUTH_TOKEN;
import static com.linecorp.armeria.common.auth.OAuth1aToken.OAUTH_VERSION;
import static com.linecorp.armeria.common.auth.OAuth1aToken.REALM;
import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Map.Entry;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Builds a new {@link OAuth1aToken}.
 */
public final class OAuth1aTokenBuilder {

    private static final String DEFAULT_OAUTH_VERSION = "1.0";

    @Nullable
    private String consumerKey;
    @Nullable
    private String token;
    @Nullable
    private String signatureMethod;
    @Nullable
    private String signature;
    @Nullable
    private String timestamp;
    @Nullable
    private String nonce;
    @Nullable
    private String realm;
    private String version = DEFAULT_OAUTH_VERSION;
    private final ImmutableMap.Builder<String, String> additionalsBuilder = ImmutableMap.builder();

    /**
     * Creates a new instance.
     */
    OAuth1aTokenBuilder() {}

    /**
     * Sets the value of the realm property.
     */
    public OAuth1aTokenBuilder realm(String realm) {
        this.realm = requireNonNull(realm, "realm");
        return this;
    }

    /**
     * Sets the value of the oath_consumer_key property.
     */
    public OAuth1aTokenBuilder consumerKey(String consumerKey) {
        this.consumerKey = requireNonNull(consumerKey, "consumerKey");
        return this;
    }

    /**
     * Sets the value of the oauth_token property.
     */
    public OAuth1aTokenBuilder token(String token) {
        this.token = requireNonNull(token, "token");
        return this;
    }

    /**
     * Sets the value of oauth_signature_method property.
     */
    public OAuth1aTokenBuilder signatureMethod(String signatureMethod) {
        this.signatureMethod = requireNonNull(signatureMethod, "signatureMethod");
        return this;
    }

    /**
     * Sets the value of oauth_signature property.
     */
    public OAuth1aTokenBuilder signature(String signature) {
        this.signature = requireNonNull(signature, "signature");
        return this;
    }

    /**
     * Sets the value of oauth_timestamp property.
     */
    public OAuth1aTokenBuilder timestamp(String timestamp) {
        try {
            Long.parseLong(requireNonNull(timestamp, "timestamp"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("timestamp: " + timestamp +
                                               " (expected: a string containing a long representation)");
        }
        this.timestamp = timestamp;
        return this;
    }

    /**
     * Sets the value of oauth_nonce property.
     */
    public OAuth1aTokenBuilder nonce(String nonce) {
        this.nonce = requireNonNull(nonce, "nonce");
        return this;
    }

    /**
     * Sets the value of oauth_version property.
     * If not set, {@value DEFAULT_OAUTH_VERSION} is used by default.
     */
    public OAuth1aTokenBuilder version(String version) {
        this.version = requireNonNull(version, "version");
        return this;
    }

    /**
     * Sets the specified {@code key}-{@code value} parameter. If the {@code key} is not one of the
     * pre-defined parameters, then the {@code key}-{@code value} pair is set as an additional parameter.
     * If the {@code key} is one of the pre-defined parameters, then the corresponding property is
     * automatically set.
     *
     * <pre>{@code
     * OAuth1aToken.builder().put("oauth_signature_method", "foo");
     * // is equivalent to
     * OAuth1aToken.builder().signatureMethod("foo");
     *
     * // This is just an additional parameter.
     * OAuth1aToken.builder().put("just_an_additional_parameter", "bar");
     * }</pre>
     */
    public OAuth1aTokenBuilder put(String key, String value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        final String lowerCased = Ascii.toLowerCase(key);
        switch (lowerCased) {
            case REALM:
                realm(value);
                break;
            case OAUTH_CONSUMER_KEY:
                consumerKey(value);
                break;
            case OAUTH_TOKEN:
                token(value);
                break;
            case OAUTH_SIGNATURE_METHOD:
                signatureMethod(value);
                break;
            case OAUTH_SIGNATURE:
                signature(value);
                break;
            case OAUTH_TIMESTAMP:
                timestamp(value);
                break;
            case OAUTH_NONCE:
                nonce(value);
                break;
            case OAUTH_VERSION:
                version(value);
                break;
            default:
                additionalsBuilder.put(key, value);
        }
        return this;
    }

    /**
     * Sets the specified {@link Map}. If the key in the {@link Map} is not one of the
     * pre-defined parameters, then the key-value pair is set as an additional parameter.
     * If the key is one of the pre-defined parameters, then the corresponding property is
     * automatically set.
     *
     * <pre>{@code
     * OAuth1aToken.builder().putAll(Map.of("oauth_signature_method", "foo"
     *                                      "oauth_signature", "bar"));
     * // is equivalent to
     * OAuth1aToken.builder().signatureMethod("foo")
     *                       .signature("bar");
     * }</pre>
     */
    public OAuth1aTokenBuilder putAll(Map<String, String> params) {
        requireNonNull(params, "params");
        for (Entry<String, String> param : params.entrySet()) {
            final String key = param.getKey();
            final String value = param.getValue();

            // Empty values are ignored.
            if (!isNullOrEmpty(key) && !isNullOrEmpty(value)) {
                put(key, value);
            }
        }
        return this;
    }

    /**
     * Returns a newly-created {@link OAuth1aToken} based on the properties set so far.
     */
    public OAuth1aToken build() {
        checkState(consumerKey != null, "consumerKey is not set.");
        checkState(token != null, "token is not set.");
        checkState(signatureMethod != null, "signatureMethod is not set.");
        checkState(signature != null, "signature is not set.");
        checkState(timestamp != null, "timestamp is not set.");
        checkState(nonce != null, "nonce is not set.");
        return new OAuth1aToken(consumerKey, token, signatureMethod, signature, timestamp, nonce,
                                version, additionalsBuilder.build(), realm);
    }
}
