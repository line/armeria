/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server.athenz;

import static com.linecorp.armeria.server.athenz.AthenzDocker.ADMIN_POLICY;
import static com.linecorp.armeria.server.athenz.AthenzDocker.ADMIN_ROLE;
import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_DOMAIN_NAME;
import static com.linecorp.armeria.server.athenz.AthenzDocker.TEST_SERVICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;

import com.yahoo.athenz.zpe.pkey.PublicKeyStore;
import com.yahoo.rdl.Struct;

import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@EnabledIfDockerAvailable
class AthenzPolicyLoaderTest {

    @RegisterExtension
    static AthenzExtension athenzExtension = new AthenzExtension();

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void loadPolicyFiles(boolean jwsPolicySupport) throws Exception {
        try (ZtsBaseClient baseClient = athenzExtension.newZtsBaseClient(TEST_SERVICE, builder -> {
            builder.configureWebClient(client -> {
                client.decorator(LoggingClient.newDecorator());
            });
        })) {
            final PublicKeyStore publicKeyStore = new AthenzPublicKeyProvider(baseClient,
                                                                              Duration.ofSeconds(10),
                                                                              "/oauth2/keys?rfc=true");
            final AthenzPolicyConfig policyConfig = new AthenzPolicyConfig(ImmutableList.of(TEST_DOMAIN_NAME),
                                                                           ImmutableMap.of(), jwsPolicySupport,
                                                                           Duration.ofSeconds(10));

            final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            final MeterIdPrefix meterIdPrefix = new MeterIdPrefix("athenz.test");
            final AthenzPolicyLoader policyLoader = new AthenzPolicyLoader(baseClient, TEST_DOMAIN_NAME,
                                                                           policyConfig, publicKeyStore,
                                                                           meterRegistry, meterIdPrefix);

            assertThatThrownBy(policyLoader::getNow)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Policy data is not initialized yet");
            policyLoader.init();
            final AthenzAssertions assertions = policyLoader.getNow();
            final List<Struct> adminPolicy = assertions.roleStandardAllowMap().get(ADMIN_ROLE);
            assertThat(adminPolicy).satisfiesOnlyOnce(struct -> {
                assertThat(struct.get("polname")).isEqualTo(TEST_DOMAIN_NAME + ":policy." + ADMIN_POLICY);
            });

            // Verify that the policy load success counter was incremented.
            final String dataType = jwsPolicySupport ? "jws" : "signed";
            assertThat(meterRegistry.counter("athenz.test.policy.loads",
                                             "domain", TEST_DOMAIN_NAME,
                                             "result", "success",
                                             "type", dataType).count()).isEqualTo(1);
            assertThat(meterRegistry.counter("athenz.test.policy.loads",
                                             "domain", TEST_DOMAIN_NAME,
                                             "result", "failure",
                                             "type", dataType).count()).isZero();
        }
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void shouldReuseAssertionsWhenNotModified(boolean jwsPolicySupport) throws Exception {
        final List<String> ifNoneMatchHeaders = new CopyOnWriteArrayList<>();
        final String policyPath =
                jwsPolicySupport ? "/zts/v1/domain/" + TEST_DOMAIN_NAME + "/policy/signed"
                                 : "/zts/v1/domain/" + TEST_DOMAIN_NAME + "/signed_policy_data";

        try (ZtsBaseClient baseClient = athenzExtension.newZtsBaseClient(TEST_SERVICE, builder -> {
            builder.configureWebClient(client -> {
                client.decorator(LoggingClient.newDecorator());
                client.decorator((delegate, ctx, req) -> {
                    if (policyPath.equals(ctx.path())) {
                        ifNoneMatchHeaders.add(req.headers().get(HttpHeaderNames.IF_NONE_MATCH));
                    }
                    return delegate.execute(ctx, req);
                });
            });
        })) {
            final PublicKeyStore publicKeyStore = new AthenzPublicKeyProvider(baseClient,
                                                                              Duration.ofSeconds(10),
                                                                              "/oauth2/keys?rfc=true");
            // Use a short refresh interval so that the next getNow() triggers a background refresh.
            final AthenzPolicyConfig policyConfig = new AthenzPolicyConfig(ImmutableList.of(TEST_DOMAIN_NAME),
                                                                           ImmutableMap.of(), jwsPolicySupport,
                                                                           Duration.ofMillis(100));

            final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            final MeterIdPrefix meterIdPrefix = new MeterIdPrefix("athenz.test");
            final AthenzPolicyLoader policyLoader = new AthenzPolicyLoader(baseClient, TEST_DOMAIN_NAME,
                                                                           policyConfig, publicKeyStore,
                                                                           meterRegistry, meterIdPrefix);
            policyLoader.init();
            final AthenzAssertions initial = policyLoader.getNow();

            final String dataType = jwsPolicySupport ? "jws" : "signed";
            final Counter successCounter = meterRegistry.counter("athenz.test.policy.loads",
                                                                 "domain", TEST_DOMAIN_NAME,
                                                                 "result", "success",
                                                                 "type", dataType);
            final Counter notModifiedCounter = meterRegistry.counter("athenz.test.policy.loads",
                                                                     "domain", TEST_DOMAIN_NAME,
                                                                     "result", "not_modified",
                                                                     "type", dataType);
            final Counter failureCounter = meterRegistry.counter("athenz.test.policy.loads",
                                                                 "domain", TEST_DOMAIN_NAME,
                                                                 "result", "failure",
                                                                 "type", dataType);

            // The initial load should be counted as a single successful load.
            assertThat(successCounter.count()).isEqualTo(1);

            // Wait until ZTS has returned at least three 304 Not Modified responses while no
            // additional 200 OK responses have occurred. Each poll calls getNow() to trigger a
            // background refresh once the refreshInterval has elapsed.
            await().untilAsserted(() -> {
                policyLoader.getNow();
                assertThat(successCounter.count()).isEqualTo(1);
                assertThat(notModifiedCounter.count()).isGreaterThanOrEqualTo(3);
            });

            // The cached AthenzAssertions instance should be reused when ZTS returned 304.
            assertThat(policyLoader.getNow()).isSameAs(initial);

            // No failures should have occurred and only the initial load is counted as a success.
            assertThat(successCounter.count()).isEqualTo(1);
            assertThat(failureCounter.count()).isZero();

            // The initial request should not include the If-None-Match header.
            assertThat(ifNoneMatchHeaders).isNotEmpty();
            assertThat(ifNoneMatchHeaders.get(0))
                    .as("Initial request should not send the If-None-Match header")
                    .isNull();
            // Subsequent requests should send the If-None-Match header with the ETag from the previous
            // response so that ZTS can respond with 304 Not Modified.
            assertThat(ifNoneMatchHeaders.subList(1, ifNoneMatchHeaders.size()))
                    .as("Subsequent requests should send the If-None-Match header")
                    .isNotEmpty()
                    .allSatisfy(etag -> assertThat(etag).isNotNull().isNotEmpty());
        }
    }
}
