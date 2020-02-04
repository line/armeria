/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.server.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.ClassRule;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.sse.ServerSentEvent;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import reactor.core.publisher.Flux;

public class ServerSentEventsTest {

    @ClassRule
    public static ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/sse/publisher", (ctx, req) -> ServerSentEvents.fromPublisher(
                    Flux.just(ServerSentEvent.ofData("foo"), ServerSentEvent.ofData("bar"))));
            sb.service("/sse/stream", (ctx, req) -> ServerSentEvents.fromStream(
                    Stream.of(ServerSentEvent.ofData("foo"), ServerSentEvent.ofData("bar")),
                    MoreExecutors.directExecutor()));

            sb.service("/converter/publisher", (ctx, req) -> ServerSentEvents.fromPublisher(
                    Flux.just("foo", "bar"), ServerSentEvent::ofComment));
            sb.service("/converter/stream", (ctx, req) -> ServerSentEvents.fromStream(
                    Stream.of("foo", "bar"), MoreExecutors.directExecutor(), ServerSentEvent::ofComment));

            sb.service("/single/sse", (ctx, req) -> ServerSentEvents.fromEvent(
                    ServerSentEvent.ofEvent("add")));
        }
    };

    @Test
    public void fromPublisherOrStream() {
        final WebClient client = WebClient.of(rule.httpUri() + "/sse");
        for (final String path : ImmutableList.of("/publisher", "/stream")) {
            final AggregatedHttpResponse response = client.get(path).aggregate().join();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.headers().contentType()).isEqualTo(MediaType.EVENT_STREAM);
            assertThat(response.content().toStringUtf8()).isEqualTo("data:foo\n\ndata:bar\n\n");
        }
    }

    @Test
    public void withConverter() {
        final WebClient client = WebClient.of(rule.httpUri() + "/converter");
        for (final String path : ImmutableList.of("/publisher", "/stream")) {
            final AggregatedHttpResponse response = client.get(path).aggregate().join();
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.headers().contentType()).isEqualTo(MediaType.EVENT_STREAM);
            assertThat(response.content().toStringUtf8()).isEqualTo(":foo\n\n:bar\n\n");
        }
    }

    @Test
    public void singleEvent() {
        final AggregatedHttpResponse response =
                WebClient.of(rule.httpUri() + "/single").get("/sse").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().contentType()).isEqualTo(MediaType.EVENT_STREAM);
        assertThat(response.content().toStringUtf8()).isEqualTo("event:add\n\n");
    }
}
