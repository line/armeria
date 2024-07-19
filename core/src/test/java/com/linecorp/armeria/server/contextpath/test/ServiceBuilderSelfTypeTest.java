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

package com.linecorp.armeria.server.contextpath.test;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.server.ContextPathAnnotatedServiceConfigSetters;
import com.linecorp.armeria.server.ContextPathServiceBindingBuilder;
import com.linecorp.armeria.server.ContextPathServicesBuilder;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceBindingBuilder;
import com.linecorp.armeria.server.VirtualHostBuilder;
import com.linecorp.armeria.server.VirtualHostContextPathAnnotatedServiceConfigSetters;
import com.linecorp.armeria.server.VirtualHostContextPathServiceBindingBuilder;
import com.linecorp.armeria.server.VirtualHostContextPathServicesBuilder;

class ServiceBuilderSelfTypeTest {

    // A non-existent package is used to check if the API is exposed publicly.

    @Test
    void contextPathAnnotatedServiceConfigSetters() {
        final ContextPathAnnotatedServiceConfigSetters setters =
                Server.builder()
                      .contextPath("/foo")
                      .annotatedService()
                      .addHeader("X-foo", "bar");
        final ContextPathServicesBuilder contextPathServicesBuilder = setters.build(new Object());
        final ServerBuilder serverBuilder = contextPathServicesBuilder.and();
    }

    @Test
    void virtualHostContextPathAnnotatedServiceConfigSetters() {
        final VirtualHostContextPathAnnotatedServiceConfigSetters setters =
                Server.builder()
                      .virtualHost("foo.com")
                      .contextPath("/foo")
                      .annotatedService()
                      .addHeader("X-foo", "bar");
        final VirtualHostContextPathServicesBuilder contextPathServicesBuilder = setters.build(new Object());
        final VirtualHostBuilder serverBuilder = contextPathServicesBuilder.and();
    }

    @Test
    void serviceBindingBuilder() {
        final ServiceBindingBuilder serviceBindingBuilder =
                Server.builder()
                      .route()
                      .path("/");
        final ServiceBindingBuilder serviceBindingBuilder1 = serviceBindingBuilder.decorator(
                (delegate, ctx, req) -> null);
        serviceBindingBuilder1.build((ctx, req) -> null);
    }

    @Test
    void contextPathServiceBindingBuilder() {
        final ContextPathServiceBindingBuilder builder =
                Server.builder()
                      .contextPath("/foo")
                      .route()
                      .path("/")
                      .addHeader("X-foo", "bar");
        builder.build((ctx, req) -> null);
    }

    @Test
    void virtualHostContextPathServiceBindingBuilder() {
        final VirtualHostContextPathServiceBindingBuilder builder =
                Server.builder()
                      .virtualHost("foo.com")
                      .contextPath("/foo")
                      .route()
                      .path("/")
                      .addHeader("X-foo", "bar");
        builder.build((ctx, req) -> null);
    }
}
