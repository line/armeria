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
import static java.util.Objects.requireNonNull;

import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;

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
    private Map<String, String> additionals = ImmutableMap.of();

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
     * Sets additional (or user-defined) parameters.
     */
    public OAuth1aTokenBuilder additionals(Map<String, String> additionals) {
        this.additionals = ImmutableMap.copyOf(requireNonNull(additionals, "additionals"));
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
                                version, additionals, realm);
    }
}
