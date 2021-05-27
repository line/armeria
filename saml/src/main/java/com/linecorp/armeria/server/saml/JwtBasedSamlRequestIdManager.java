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

import static com.google.common.base.Preconditions.checkArgument;
import static io.netty.util.internal.MacAddressUtil.defaultMachineId;
import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

import io.netty.util.internal.ThreadLocalRandom;

/**
 * A {@link SamlRequestIdManager} implementation based on JSON Web Tokens specification.
 *
 * @see <a href="https://jwt.io/">JSON Web Tokens</a>
 */
final class JwtBasedSamlRequestIdManager implements SamlRequestIdManager {
    private static final Logger logger = LoggerFactory.getLogger(JwtBasedSamlRequestIdManager.class);

    private static final String CLAIM_NAME_UNIQUIFIER1 = "un1";
    private static final String CLAIM_NAME_UNIQUIFIER2 = "un2";

    private final String issuer;
    private final Algorithm algorithm;
    private final int validSeconds;
    private final int leewaySeconds;

    private final String un1;
    private final JWTVerifier verifier;

    JwtBasedSamlRequestIdManager(String issuer, Algorithm algorithm,
                                 int validSeconds, int leewaySeconds) {
        this.issuer = requireNonNull(issuer, "issuer");
        this.algorithm = requireNonNull(algorithm, "algorithm");
        this.validSeconds = validSeconds;
        this.leewaySeconds = leewaySeconds;

        checkArgument(validSeconds > 0,
                      "invalid valid duration: " + validSeconds + " (expected: > 0)");
        checkArgument(leewaySeconds >= 0,
                      "invalid leeway duration:" + leewaySeconds + " (expected: >= 0)");

        un1 = getUniquifierPrefix();
        verifier = JWT.require(algorithm)
                      .withIssuer(issuer)
                      .acceptLeeway(leewaySeconds)
                      .build();
    }

    @Override
    public String newId() {
        final Instant now = Instant.now();
        final int un2 = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE) & 0x7fffffff;
        return JWT.create()
                  .withIssuer(issuer)
                  .withIssuedAt(Date.from(now))
                  .withExpiresAt(Date.from(now.plus(validSeconds, ChronoUnit.SECONDS)))
                  // To make multiple tokens issued in the same second unique, we add uniquifiers.
                  .withClaim(CLAIM_NAME_UNIQUIFIER1, un1)
                  .withClaim(CLAIM_NAME_UNIQUIFIER2, un2)
                  .sign(algorithm);
    }

    @Override
    public boolean validateId(String id) {
        requireNonNull(id, "id");
        try {
            // Verifier will check whether its issuer is me and also check whether it has been expired or not.
            verifier.verify(id);
            return true;
        } catch (Throwable cause) {
            logger.trace("JWT token validation failed", cause);
            return false;
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("issuer", issuer)
                          .add("algorithm", algorithm)
                          .add("validSeconds", validSeconds)
                          .add("leewaySeconds", leewaySeconds)
                          .toString();
    }

    private static String getUniquifierPrefix() {
        // To make a request ID globally unique, we will add MAC-based machine ID and a random number.
        // The random number tries to make this instance unique in the same machine and process.
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final byte[] r = tempThreadLocals.byteArray(6);
            ThreadLocalRandom.current().nextBytes(r);
            final Encoder encoder = Base64.getEncoder();
            return new StringBuilder().append(encoder.encodeToString(defaultMachineId()))
                                      .append(encoder.encodeToString(r))
                                      .toString();
        }
    }
}
