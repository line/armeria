/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.common.outlier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.WriteTimeoutException;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseCompleteException;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.RequestTimeoutException;

class OutlierRuleTest {

    @Test
    void onException() {
        final OutlierRule rule =
                OutlierRule.builder()
                           .onException(AnticipatedException.class, OutlierDetectionDecision.IGNORE)
                           .onException(WriteTimeoutException.class, OutlierDetectionDecision.FATAL)
                           .onException(RequestTimeoutException.class,
                                                 OutlierDetectionDecision.FAILURE)
                           .build();
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertThat(rule.decide(ctx, null, new AnticipatedException()))
                .isEqualTo(OutlierDetectionDecision.IGNORE);
        assertThat(rule.decide(ctx, null, WriteTimeoutException.get()))
                .isEqualTo(OutlierDetectionDecision.FATAL);
        assertThat(rule.decide(ctx, null, RequestTimeoutException.get()))
                .isEqualTo(OutlierDetectionDecision.FAILURE);
        assertThat(rule.decide(ctx, null, ResponseCompleteException.get()))
                .isEqualTo(OutlierDetectionDecision.NEXT);
        assertThat(rule.decide(ctx, ResponseHeaders.of(200), null))
                .isEqualTo(OutlierDetectionDecision.NEXT);
    }

    @Test
    void onStatus() {
        final OutlierRule rule =
                OutlierRule.builder()
                           .onStatus(HttpStatus::isClientError, OutlierDetectionDecision.IGNORE)
                           .onStatus(status -> status == HttpStatus.SERVICE_UNAVAILABLE,
                                              OutlierDetectionDecision.FATAL)
                           .onStatus(HttpStatus::isServerError, OutlierDetectionDecision.FAILURE)
                           .build();
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertThat(rule.decide(ctx, ResponseHeaders.of(200), null))
                .isEqualTo(OutlierDetectionDecision.NEXT);
        assertThat(rule.decide(ctx, ResponseHeaders.of(500), null))
                .isEqualTo(OutlierDetectionDecision.FAILURE);
        assertThat(rule.decide(ctx, ResponseHeaders.of(503), null))
                .isEqualTo(OutlierDetectionDecision.FATAL);
    }

    @Test
    void onResponseHeaders() {
        final OutlierRule rule =
                OutlierRule.builder()
                           .onResponseHeaders((ctx, headers) -> {
                                        if (headers.status().isServerError()) {
                                            return OutlierDetectionDecision.FAILURE;
                                        }
                                        return OutlierDetectionDecision.SUCCESS;
                                    })
                           .build();
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertThat(rule.decide(ctx, ResponseHeaders.of(200), null))
                .isEqualTo(OutlierDetectionDecision.SUCCESS);
        assertThat(rule.decide(ctx, ResponseHeaders.of(500), null))
                .isEqualTo(OutlierDetectionDecision.FAILURE);
    }

    @Test
    void shouldRaiseExceptionIfNoRuleSet() {
        assertThatThrownBy(() -> OutlierRule.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No rule has been added.");
    }
}
