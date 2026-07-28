/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.xds.filter;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.util.Durations;

import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.xds.internal.XdsHeaderMatcher;

import io.envoyproxy.envoy.extensions.filters.common.fault.v3.FaultDelay;
import io.envoyproxy.envoy.extensions.filters.http.fault.v3.FaultAbort;
import io.envoyproxy.envoy.extensions.filters.http.fault.v3.HTTPFault;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.netty.util.concurrent.EventExecutor;

/**
 * An {@link HttpFilterFactory} for the {@code envoy.filters.http.fault} filter.
 *
 * <p>Supports:
 * <ul>
 *   <li>{@code abort} — returns an HTTP error status for a percentage of requests</li>
 *   <li>{@code delay} — adds fixed latency for a percentage of requests</li>
 * </ul>
 */
@UnstableApi
public final class FaultInjectionFilterFactory implements HttpFilterFactory {

    private static final String NAME = "envoy.filters.http.fault";
    private static final String TYPE_URL =
            "type.googleapis.com/envoy.extensions.filters.http.fault.v3.HTTPFault";
    private static final List<String> TYPE_URLS = ImmutableList.of(TYPE_URL);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> typeUrls() {
        return TYPE_URLS;
    }

    @Override
    @Nullable
    public XdsHttpFilter create(HttpFilter httpFilter, Any config, FactoryContext context) {
        final HTTPFault httpFault = context.validator().unpack(config, HTTPFault.class);

        final boolean hasAbort = httpFault.hasAbort() &&
                                 httpFault.getAbort().getErrorTypeCase() ==
                                 FaultAbort.ErrorTypeCase.HTTP_STATUS;
        final boolean hasDelay = httpFault.hasDelay() &&
                                 httpFault.getDelay().getFaultDelaySecifierCase() ==
                                 FaultDelay.FaultDelaySecifierCase.FIXED_DELAY;

        if (!hasAbort && !hasDelay) {
            return null;
        }

        final int abortStatus;
        final int abortNumerator;
        final int abortDenominator;
        if (hasAbort) {
            final FaultAbort abort = httpFault.getAbort();
            abortStatus = abort.getHttpStatus();
            abortNumerator = abort.getPercentage().getNumerator();
            abortDenominator = denominatorValue(abort.getPercentage());
        } else {
            abortStatus = 0;
            abortNumerator = 0;
            abortDenominator = 100;
        }

        final long delayMillis;
        final int delayNumerator;
        final int delayDenominator;
        if (hasDelay) {
            final FaultDelay delay = httpFault.getDelay();
            delayMillis = Durations.toMillis(delay.getFixedDelay());
            delayNumerator = delay.getPercentage().getNumerator();
            delayDenominator = denominatorValue(delay.getPercentage());
        } else {
            delayMillis = 0;
            delayNumerator = 0;
            delayDenominator = 100;
        }

        final List<XdsHeaderMatcher> headerMatchers = XdsHeaderMatcher.of(httpFault.getHeadersList());

        return new FaultInjectionXdsHttpFilter(
                abortStatus, abortNumerator, abortDenominator,
                delayMillis, delayNumerator, delayDenominator,
                headerMatchers);
    }

    private static int denominatorValue(FractionalPercent percent) {
        switch (percent.getDenominator()) {
            case TEN_THOUSAND:
                return 10_000;
            case MILLION:
                return 1_000_000;
            default:
                return 100;
        }
    }

    private static boolean shouldApply(int numerator, int denominator) {
        if (numerator <= 0) {
            return false;
        }
        if (numerator >= denominator) {
            return true;
        }
        return ThreadLocalRandom.current().nextInt(denominator) < numerator;
    }

    private static final class FaultInjectionXdsHttpFilter implements XdsHttpFilter {

        private final int abortStatus;
        private final int abortNumerator;
        private final int abortDenominator;
        private final long delayMillis;
        private final int delayNumerator;
        private final int delayDenominator;
        private final List<XdsHeaderMatcher> headerMatchers;

        FaultInjectionXdsHttpFilter(int abortStatus, int abortNumerator, int abortDenominator,
                                    long delayMillis, int delayNumerator, int delayDenominator,
                                    List<XdsHeaderMatcher> headerMatchers) {
            this.abortStatus = abortStatus;
            this.abortNumerator = abortNumerator;
            this.abortDenominator = abortDenominator;
            this.delayMillis = delayMillis;
            this.delayNumerator = delayNumerator;
            this.delayDenominator = delayDenominator;
            this.headerMatchers = headerMatchers;
        }

        @Override
        public DecoratingHttpClientFunction httpDecorator() {
            return (delegate, ctx, req) -> {
                if (!headersMatch(req.headers())) {
                    return delegate.execute(ctx, req);
                }
                final HttpResponse faultResponse = maybeFault();
                if (faultResponse != null) {
                    return maybeDelay(faultResponse, ctx.eventLoop());
                }
                return maybeDelay(delegate.execute(ctx, req), ctx.eventLoop());
            };
        }

        @Override
        public DecoratingHttpServiceFunction serviceDecorator() {
            return (delegate, ctx, req) -> {
                if (!headersMatch(req.headers())) {
                    return delegate.serve(ctx, req);
                }
                final HttpResponse faultResponse = maybeFault();
                if (faultResponse != null) {
                    return maybeDelay(faultResponse, ctx.eventLoop());
                }
                return maybeDelay(delegate.serve(ctx, req), ctx.eventLoop());
            };
        }

        private boolean headersMatch(HttpHeaders headers) {
            return XdsHeaderMatcher.matchAll(headerMatchers, headers);
        }

        @Nullable
        private HttpResponse maybeFault() {
            if (shouldApply(abortNumerator, abortDenominator)) {
                return HttpResponse.of(HttpStatus.valueOf(abortStatus));
            }
            return null;
        }

        private HttpResponse maybeDelay(HttpResponse response, EventExecutor executor) {
            if (shouldApply(delayNumerator, delayDenominator) && delayMillis > 0) {
                return HttpResponse.delayed(response, Duration.ofMillis(delayMillis), executor);
            }
            return response;
        }
    }
}
