/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server.saml;

import static java.util.Objects.requireNonNull;

import java.io.UnsupportedEncodingException;

import com.auth0.jwt.algorithms.Algorithm;

/**
 * An interface which generates and validates a request ID when transferring a SAML message between
 * a service provider and an identity provider.
 */
public interface SamlRequestIdManager {
    /**
     * Returns a {@link SamlRequestIdManager} implementation based on JSON Web Tokens specification.
     *
     * @param issuer the ID of the entity who issues a token
     * @param algorithm the algorithm instance which is used to create a signature
     * @param validSeconds the valid period of a token in seconds
     * @param leewaySeconds the leeway when there is a clock skew times between the signer and the verifier,
     *                      in seconds.
     */
    static SamlRequestIdManager ofJwt(String issuer, Algorithm algorithm,
                                      int validSeconds, int leewaySeconds) {
        return new JwtBasedSamlRequestIdManager(issuer, algorithm, validSeconds, leewaySeconds);
    }

    /**
     * Returns a {@link SamlRequestIdManager} implementation based on JSON Web Tokens specification with
     * the {@link Algorithm} instance using {@code HmacSHA384}.
     *
     * @param issuer the ID of the entity who issues a token
     * @param secret the secret which is used to generate a signature
     * @param validSeconds the valid period of a token in seconds
     * @param leewaySeconds the leeway when there is a clock skew times between the signer and the verifier,
     *                      in seconds.
     */
    static SamlRequestIdManager ofJwt(String issuer, String secret,
                                      int validSeconds, int leewaySeconds) throws UnsupportedEncodingException {
        final Algorithm algorithm = Algorithm.HMAC384(requireNonNull(secret, "secret"));
        return ofJwt(issuer, algorithm, validSeconds, leewaySeconds);
    }

    /**
     * Returns a newly-generated request ID.
     */
    String newId();

    /**
     * Returns whether the specified ID is valid or not.
     */
    boolean validateId(String id);
}
