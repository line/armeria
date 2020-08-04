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
package com.linecorp.armeria.common.grpc;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.rpc.Code;

import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.metric.DefaultMeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.server.metric.MetricCollectingService;

import io.micrometer.core.instrument.Tag;

/**
 * Creates a {@link MeterIdPrefix} from a {@link RequestLog}.
 * This adds the {@code grpc.status} tag upon the tags that {@link DefaultMeterIdPrefixFunction} appends.
 *
 * @see MetricCollectingClient
 * @see MetricCollectingService
 */
public final class GrpcMeterIdPrefixFunction extends DefaultMeterIdPrefixFunction {

    /**
     * Returns a newly created {@link GrpcMeterIdPrefixFunction} with the specified {@code name}.
     */
    public static GrpcMeterIdPrefixFunction of(String name) {
        return new GrpcMeterIdPrefixFunction(name);
    }

    GrpcMeterIdPrefixFunction(String name) {
        super(name,
              3, /* hostname.pattern, method, service */
              5  /* grpc.status, hostname.pattern, http.status, method, service */);
    }

    @Override
    protected void addCompleteRequestPrefixTags(Builder<Tag> tagListBuilder, RequestLog log) {
        appendGrpcStatus(tagListBuilder, log);
        super.addCompleteRequestPrefixTags(tagListBuilder, log);
    }

    private static void appendGrpcStatus(ImmutableList.Builder<Tag> tagListBuilder, RequestLog log) {
        String status = log.responseHeaders().get(GrpcHeaderNames.GRPC_STATUS);
        if (status != null) {
            tagListBuilder.add(Tag.of("grpc.status", status));
            return;
        }

        status = log.responseTrailers().get(GrpcHeaderNames.GRPC_STATUS);
        if (status != null) {
            tagListBuilder.add(Tag.of("grpc.status", status));
            return;
        }

        final HttpHeaders trailers = GrpcWebUtil.trailers(log.context());
        if (trailers != null) {
            status = trailers.get(GrpcHeaderNames.GRPC_STATUS);
            if (status != null) {
                tagListBuilder.add(Tag.of("grpc.status", status));
                return;
            }
        }

        tagListBuilder.add(Tag.of("grpc.status", String.valueOf(Code.UNKNOWN_VALUE)));
    }
}
