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

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.server.athenz.AthenzPolicyHandler.toAssertions;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.yahoo.athenz.zpe.pkey.PublicKeyStore;
import com.yahoo.athenz.zts.DomainSignedPolicyData;
import com.yahoo.athenz.zts.JWSPolicyData;

import com.linecorp.armeria.client.FutureResponseAs;
import com.linecorp.armeria.client.InvalidHttpResponseException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientRequestPreparation;
import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.util.AsyncLoader;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.JacksonUtil;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

final class AthenzPolicyLoader {

    private final WebClient client;
    private final String targetDomain;
    private final boolean jwsPolicySupport;
    @Nullable
    private final Map<String, Object> jwsPolicyParams;
    private final AthenzPolicyHandler policyHandler;
    private final AsyncLoader<Etagged<AthenzAssertions>> policyLoader;
    private final CompletableFuture<Etagged<AthenzAssertions>> initialPolicyData;
    private final Counter successCounter;
    private final Counter notModifiedCounter;
    private final Counter failureCounter;
    private final AsAthenzAssertions asAthenzAssertions;

    AthenzPolicyLoader(ZtsBaseClient baseClient, String targetDomain,
                       AthenzPolicyConfig updaterConfig, PublicKeyStore publicKeyStore,
                       MeterRegistry meterRegistry, MeterIdPrefix meterIdPrefix) {
        client = baseClient.webClient();
        this.targetDomain = targetDomain;
        jwsPolicySupport = updaterConfig.jwsPolicySupport();
        if (jwsPolicySupport) {
            jwsPolicyParams = ImmutableMap.of(
                    "policyVersions", updaterConfig.policyVersions(),
                    "signatureP1363Format", true);
        } else {
            jwsPolicyParams = null;
        }
        asAthenzAssertions = new AsAthenzAssertions();

        final String dataType = updaterConfig.jwsPolicySupport() ? "jws" : "signed";
        successCounter = meterRegistry.counter(meterIdPrefix.name("policy.loads"),
                                               meterIdPrefix.tags("domain", targetDomain, "result", "success",
                                                                  "type", dataType));
        notModifiedCounter = meterRegistry.counter(meterIdPrefix.name("policy.loads"),
                                                   meterIdPrefix.tags("domain", targetDomain,
                                                                      "result", "not_modified",
                                                                      "type", dataType));
        failureCounter = meterRegistry.counter(meterIdPrefix.name("policy.loads"),
                                               meterIdPrefix.tags("domain", targetDomain, "result", "failure",
                                                                  "type", dataType));

        policyHandler = new AthenzPolicyHandler(publicKeyStore);
        policyLoader = AsyncLoader.builder(this::loadPolicyData)
                                  .name("athenz-policy-loader/" + targetDomain)
                                  .refreshAfterLoad(updaterConfig.refreshInterval())
                                  .build();
        initialPolicyData = policyLoader.load();
    }

    void init() throws Exception {
        initialPolicyData.get(20, TimeUnit.SECONDS);
    }

    AthenzAssertions getNow() {
        checkState(initialPolicyData.isDone(), "Policy data is not initialized yet");
        return policyLoader.load().join().value;
    }

    private CompletableFuture<Etagged<AthenzAssertions>> loadPolicyData(
            @Nullable Etagged<AthenzAssertions> old) {
        return loadPolicyData0(old == null ? null : old.etag).handle((newValue, cause) -> {
            if (cause != null) {
                failureCounter.increment();
                return Exceptions.throwUnsafely(cause);
            } else {
                if (newValue == Etagged.<AthenzAssertions>notModified()) {
                    assert old != null;
                    notModifiedCounter.increment();
                    return old;
                } else {
                    successCounter.increment();
                    return newValue;
                }
            }
        });
    }

    private CompletableFuture<Etagged<AthenzAssertions>> loadPolicyData0(@Nullable String etag) {
        if (jwsPolicySupport) {
            return loadJwsPolicyData(etag);
        } else {
            return loadSignedPolicyData(etag);
        }
    }

    private CompletableFuture<Etagged<AthenzAssertions>> loadJwsPolicyData(@Nullable String etag) {
        assert jwsPolicyParams != null;
        final WebClientRequestPreparation prepare = client.prepare()
                                                          .post("/domain/{domain}/policy/signed");
        if (etag != null) {
            prepare.header(HttpHeaderNames.IF_NONE_MATCH, etag);
        }
        return prepare
                .pathParam("domain", targetDomain)
                .contentJson(jwsPolicyParams)
                .as(asAthenzAssertions)
                .execute();
    }

    private CompletableFuture<Etagged<AthenzAssertions>> loadSignedPolicyData(@Nullable String etag) {
        final WebClientRequestPreparation prepare = client.prepare()
                                                          .get("/domain/{domain}/signed_policy_data");
        if (etag != null) {
            prepare.header(HttpHeaderNames.IF_NONE_MATCH, etag);
        }
        return prepare
                .pathParam("domain", targetDomain)
                .as(asAthenzAssertions)
                .execute();
    }

    private final class AsAthenzAssertions implements FutureResponseAs<Etagged<AthenzAssertions>> {

        @Override
        public CompletableFuture<Etagged<AthenzAssertions>> as(HttpResponse response) {
            return response.aggregate().thenApplyAsync(res -> {
                if (res.status() == HttpStatus.NOT_MODIFIED) {
                    return Etagged.notModified();
                }
                if (res.status() == HttpStatus.OK) {
                    final AthenzAssertions assertions;
                    try {
                        if (jwsPolicySupport) {
                            final JWSPolicyData policyData =
                                    JacksonUtil.readValue(res.content().array(), JWSPolicyData.class);
                            assertions = toAssertions(policyHandler.getJWSPolicyData(policyData));
                        } else {
                            final DomainSignedPolicyData policyData =
                                    JacksonUtil.readValue(res.content().array(), DomainSignedPolicyData.class);
                            assertions = toAssertions(policyHandler.getSignedPolicyData(policyData));
                        }
                    } catch (IOException e) {
                        throw new InvalidHttpResponseException(res, "Failed to parse policy data", e);
                    }
                    return new Etagged<>(res.headers().get(HttpHeaderNames.ETAG), assertions);
                }

                throw new InvalidHttpResponseException(
                        res, "status: " + res.status() +
                             " (expected: 200 OK or 304 Not Modified), response: " + response);
            }, CommonPools.blockingTaskExecutor());
        }

        @Override
        public boolean requiresAggregation() {
            return true;
        }
    }

    private static final class Etagged<T> {

        private static final Etagged<?> NOT_MODIFIED = new Etagged<>(null, "");

        @SuppressWarnings("unchecked")
        static <T> Etagged<T> notModified() {
            return (Etagged<T>) NOT_MODIFIED;
        }

        @Nullable
        final String etag;
        final T value;

        private Etagged(@Nullable String etag, T value) {
            this.etag = etag;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Etagged)) {
                return false;
            }
            final Etagged<?> etagged = (Etagged<?>) o;
            return Objects.equals(etag, etagged.etag) && value.equals(etagged.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(etag, value);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .omitNullValues()
                              .add("etag", etag)
                              .add("value", value)
                              .toString();
        }
    }
}
