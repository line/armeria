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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.encoding.StreamDecoderFactory;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.server.logging.ContentPreview;

class ServerOptionsTest {
    @Test
    void defaultsAndSnapshot() {
        assertThat(ServerOptions.of().get(ServerOptions.CONTENT_PREVIEW).factory()).isNull();
        assertThat(ServerOptions.of().get(ServerOptions.COMPRESSION).encoderFactories()).isEmpty();
        assertThat(ServerOptions.of().get(ServerOptions.DECOMPRESSION)).isEmpty();
        final List<StreamDecoderFactory> factories = new ArrayList<>();
        factories.add(StreamDecoderFactory.gzip());
        final ServerOptions options = ServerOptions.of(ServerOptions.DECOMPRESSION.newValue(factories));
        factories.clear();
        assertThat(options.get(ServerOptions.DECOMPRESSION)).containsExactly(StreamDecoderFactory.gzip());
        assertThatThrownBy(() -> options.get(ServerOptions.DECOMPRESSION).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ServerOptions.DECOMPRESSION.newValue(
                ImmutableList.of(StreamDecoderFactory.gzip(), StreamDecoderFactory.gzip())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inheritanceAndExplicitDisable() {
        final ContentPreview serverPreview = ContentPreview.of(ContentPreviewerFactory.text(10));
        final ContentPreview hostPreview = ContentPreview.of(ContentPreviewerFactory.text(20));
        final ContentPreview servicePreview = ContentPreview.of(ContentPreviewerFactory.text(30));
        final ServerBuilder sb = Server.builder()
                .option(ServerOptions.CONTENT_PREVIEW, serverPreview)
                .option(ServerOptions.DECOMPRESSION, StreamDecoderFactory.all());
        sb.service("/", (ctx, req) -> HttpResponse.of("default"));
        sb.virtualHost("example.com")
          .option(ServerOptions.CONTENT_PREVIEW, hostPreview)
          .service("/", (ctx, req) -> HttpResponse.of("host"))
          .route().get("/service")
          .option(ServerOptions.CONTENT_PREVIEW, ContentPreview.disabled())
          .option(ServerOptions.CONTENT_PREVIEW, servicePreview)
          .option(ServerOptions.DECOMPRESSION, ImmutableList.of())
          .build((ctx, req) -> HttpResponse.of("service"))
          .route().get("/disabled")
          .option(ServerOptions.CONTENT_PREVIEW, ContentPreview.disabled())
          .build((ctx, req) -> HttpResponse.of("disabled"));
        final Server server = sb.build();
        try {
            assertThat(server.config().defaultVirtualHost().options().get(ServerOptions.CONTENT_PREVIEW))
                    .isSameAs(serverPreview);
            assertThat(server.config().defaultVirtualHost().serviceConfigs().get(0)
                             .options().get(ServerOptions.CONTENT_PREVIEW)).isSameAs(serverPreview);
            final VirtualHost host = server.config().findVirtualHost("example.com", -1);
            assertThat(host.options().get(ServerOptions.CONTENT_PREVIEW)).isSameAs(hostPreview);
            assertThat(host.options().get(ServerOptions.DECOMPRESSION))
                    .containsExactlyElementsOf(StreamDecoderFactory.all());
            for (ServiceConfig config : host.serviceConfigs()) {
                if (config.route().patternString().equals("/service")) {
                    assertThat(config.options().get(ServerOptions.CONTENT_PREVIEW)).isSameAs(servicePreview);
                    assertThat(config.options().get(ServerOptions.DECOMPRESSION)).isEmpty();
                } else if (config.route().patternString().equals("/disabled")) {
                    assertThat(config.options().get(ServerOptions.CONTENT_PREVIEW))
                            .isSameAs(ContentPreview.disabled());
                } else {
                    assertThat(config.options().get(ServerOptions.CONTENT_PREVIEW)).isSameAs(hostPreview);
                    assertThat(config.options().get(ServerOptions.DECOMPRESSION)).isNotEmpty();
                }
            }
        } finally {
            server.close();
        }
    }
}
