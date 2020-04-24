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
import static com.linecorp.armeria.internal.common.util.AuthUtil.secureEquals;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

/**
 * The bearer token of <a href="https://oauth.net/core/1.0a/#anchor12">OAuth 1.0a authentication</a>.
 */
public final class OAuth1aToken {

    /**
     * realm parameter. (optional)
     */
    private static final String REALM = "realm";

    /**
     * oauth_consumer_key parameter.
     */
    @VisibleForTesting
    static final String OAUTH_CONSUMER_KEY = "oauth_consumer_key";

    /**
     * oauth_token parameter.
     */
    @VisibleForTesting
    static final String OAUTH_TOKEN = "oauth_token";

    /**
     * oauth_signature_method parameter.
     */
    @VisibleForTesting
    static final String OAUTH_SIGNATURE_METHOD = "oauth_signature_method";

    /**
     * oauth_signature parameter.
     */
    @VisibleForTesting
    static final String OAUTH_SIGNATURE = "oauth_signature";

    /**
     * oauth_timestamp parameter.
     */
    @VisibleForTesting
    static final String OAUTH_TIMESTAMP = "oauth_timestamp";

    /**
     * oauth_nonce parameter.
     */
    @VisibleForTesting
    static final String OAUTH_NONCE = "oauth_nonce";

    /**
     * version parameter. (optional)
     * If not set, the default value is 1.0.
     */
    private static final String OAUTH_VERSION = "version";

    /**
     * Set of required parameters.
     */
    private static final Set<String> REQUIRED_PARAM_KEYS = ImmutableSet.of(OAUTH_CONSUMER_KEY, OAUTH_TOKEN,
                                                                           OAUTH_SIGNATURE_METHOD,
                                                                           OAUTH_SIGNATURE, OAUTH_TIMESTAMP,
                                                                           OAUTH_NONCE);

    /**
     * Set of optional parameters.
     */
    private static final Set<String> OPTIONAL_PARAM_KEYS = ImmutableSet.of(REALM, OAUTH_VERSION);

    /**
     * Set of defined parameters, regardless of it is required or not.
     */
    private static final Set<String> DEFINED_PARAM_KEYS = Sets.union(REQUIRED_PARAM_KEYS, OPTIONAL_PARAM_KEYS);

    /**
     * Creates a new {@link OAuth1aToken} from the given arguments.
     */
    public static OAuth1aToken of(Map<String, String> params) {
        return new OAuth1aToken(params);
    }

    private final Map<String, String> params;

    @Nullable
    private String headerValueStr;

    private OAuth1aToken(Map<String, String> params) {
        // Map builder with default version value.
        final ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();

        for (Entry<String, String> param : params.entrySet()) {
            final String key = param.getKey();
            final String value = param.getValue();

            // Empty values are ignored.
            if (!isNullOrEmpty(value)) {
                final String lowerCased = Ascii.toLowerCase(key);
                if (DEFINED_PARAM_KEYS.contains(lowerCased)) {
                    // If given parameter is defined by Oauth1a protocol, add with lower-cased key.
                    builder.put(lowerCased, value);
                } else {
                    // Otherwise, just add.
                    builder.put(key, value);
                }
            }
        }

        this.params = builder.build();

        if (!this.params.keySet().containsAll(REQUIRED_PARAM_KEYS)) {
            final Set<String> missing = Sets.difference(REQUIRED_PARAM_KEYS, this.params.keySet());
            throw new IllegalArgumentException("Missing OAuth1a parameters: " + missing);
        }

        try {
            Long.parseLong(this.params.get(OAUTH_TIMESTAMP));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Illegal " + OAUTH_TIMESTAMP + " value: " + this.params.get(OAUTH_TIMESTAMP));
        }
    }

    /**
     * Returns the value of the {@value #REALM} property.
     */
    public String realm() {
        return params.get(REALM);
    }

    /**
     * Returns the value of the {@value #OAUTH_CONSUMER_KEY} property.
     */
    public String consumerKey() {
        return params.get(OAUTH_CONSUMER_KEY);
    }

    /**
     * Returns the value of the {@value #OAUTH_TOKEN} property.
     */
    public String token() {
        return params.get(OAUTH_TOKEN);
    }

    /**
     * Returns the value of {@value #OAUTH_SIGNATURE_METHOD} property.
     */
    public String signatureMethod() {
        return params.get(OAUTH_SIGNATURE_METHOD);
    }

    /**
     * Returns the value of {@value #OAUTH_SIGNATURE} property.
     */
    public String signature() {
        return params.get(OAUTH_SIGNATURE);
    }

    /**
     * Returns the value of {@value #OAUTH_TIMESTAMP} property.
     */
    public String timestamp() {
        return params.get(OAUTH_TIMESTAMP);
    }

    /**
     * Returns the value of {@value #OAUTH_NONCE} property.
     */
    public String nonce() {
        return params.get(OAUTH_NONCE);
    }

    /**
     * Returns the value of {@value #OAUTH_VERSION} property.
     * If not set, returns the default value of {@code "1.0"}.
     */
    public String version() {
        return params.getOrDefault(OAUTH_VERSION, "1.0");
    }

    /**
     * Returns additional (or user-defined) parameters.
     */
    public Map<String, String> additionals() {
        final ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        for (Entry<String, String> e : params.entrySet()) {
            if (!DEFINED_PARAM_KEYS.contains(e.getKey())) {
                builder.put(e.getKey(), e.getValue());
            }
        }
        return builder.build();
    }

    /**
     * Returns the string that is sent as the value of the authorization header.
     */
    public String toHeaderValueString() {
        if (headerValueStr != null) {
            return headerValueStr;
        }
        final StringBuilder builder = TemporaryThreadLocals.get().stringBuilder();
        builder.append("OAuth ");
        final String realm = realm();
        if (!isNullOrEmpty(realm())) {
            builder.append("realm=\"");
            encodeComponent(builder, realm);
            builder.append("\",");
        }
        builder.append("oauth_consumer_key=\"");
        encodeComponent(builder, consumerKey());
        builder.append("\",oauth_token=\"");
        encodeComponent(builder, token());
        builder.append("\",oauth_signature_method=\"");
        encodeComponent(builder, signatureMethod());
        builder.append("\",oauth_signature=\"");
        encodeComponent(builder, signature());
        builder.append("\",oauth_timestamp=\"");
        encodeComponent(builder, timestamp());
        builder.append("\",oauth_nonce=\"");
        encodeComponent(builder, nonce());
        builder.append("\",version=\"");
        // Do not have to encode the version.
        builder.append(version());
        builder.append('"');
        for (Entry<String, String> entry : additionals().entrySet()) {
            builder.append("\",");
            encodeComponent(builder, entry.getKey());
            builder.append("=\"");
            encodeComponent(builder, entry.getValue());
            builder.append('"');
        }
        return builder.toString();
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
        boolean equals = true;
        for (Entry<String, String> e : params.entrySet()) {
            equals &= secureEquals(that.params.get(e.getKey()), e.getValue());
        }

        return equals && params.size() == that.params.size();
    }

    @Override
    public int hashCode() {
        return params.hashCode();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("realm", realm())
                          .add("consumerKey", consumerKey())
                          .add("token", "****")
                          .add("signatureMethod", signatureMethod())
                          .add("signature", signature())
                          .add("timestamp", timestamp())
                          .add("nonce", nonce())
                          .add("version", version())
                          .add("additionals", additionals())
                          .toString();
    }
}
