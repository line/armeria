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

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.google.common.base.Ascii;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

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
    private static final String OAUTH_CONSUMER_KEY = "oauth_consumer_key";

    /**
     * oauth_token parameter.
     */
    private static final String OAUTH_TOKEN = "oauth_token";

    /**
     * oauth_signature_method parameter.
     */
    private static final String OAUTH_SIGNATURE_METHOD = "oauth_signature_method";

    /**
     * oauth_signature parameter.
     */
    private static final String OAUTH_SIGNATURE = "oauth_signature";

    /**
     * oauth_timestamp parameter.
     */
    private static final String OAUTH_TIMESTAMP = "oauth_timestamp";

    /**
     * oauth_nonce parameter.
     */
    private static final String OAUTH_NONCE = "oauth_nonce";

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

    private OAuth1aToken(Map<String, String> params) {
        // Map builder with default version value.
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();

        for (Entry<String, String> param : params.entrySet()) {
            String key = param.getKey();
            String value = param.getValue();

            // Empty values are ignored.
            if (!Strings.isNullOrEmpty(value)) {
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
            Set<String> missing = Sets.difference(REQUIRED_PARAM_KEYS, this.params.keySet());
            throw new IllegalArgumentException("Missing OAuth1a parameter exists: " + missing);
        }

        try {
            Long.parseLong(this.params.get(OAUTH_TIMESTAMP));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Illegal " + OAUTH_TIMESTAMP + " value: " + this.params.get(OAUTH_TIMESTAMP));
        }
    }

    public String realm() {
        return params.get(REALM);
    }

    public String consumerKey() {
        return params.get(OAUTH_CONSUMER_KEY);
    }

    public String token() {
        return params.get(OAUTH_TOKEN);
    }

    public String signatureMethod() {
        return params.get(OAUTH_SIGNATURE_METHOD);
    }

    public String signature() {
        return params.get(OAUTH_SIGNATURE);
    }

    public String timestamp() {
        return params.get(OAUTH_TIMESTAMP);
    }

    public String nonce() {
        return params.get(OAUTH_NONCE);
    }

    /**
     * Returns version. If not set, returns default value (1.0).
     */
    public String version() {
        return params.getOrDefault(OAUTH_VERSION, "1.0");
    }

    /**
     * Returns additional (or, user-defined) parameters.
     */
    public Map<String, String> additionals() {
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        for (String key : params.keySet()) {
            if (!DEFINED_PARAM_KEYS.contains(key)) {
                builder.put(key, params.get(key));
            }
        }
        return builder.build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        OAuth1aToken that = (OAuth1aToken) o;
        return params.equals(that.params);
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
