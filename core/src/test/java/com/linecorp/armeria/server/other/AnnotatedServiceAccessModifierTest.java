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

package com.linecorp.armeria.server.other;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.TestConverters.UnformattedStringConverterFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

public class AnnotatedServiceAccessModifierTest {

    @ClassRule
    public static final ServerRule rule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/anonymous", new Object() {
                @Get("/public")
                @LoggingDecorator
                @ResponseConverter(UnformattedStringConverterFunction.class)
                public String public0() {
                    return "hello";
                }

                @Get("/package")
                String package0() {
                    return "hello";
                }

                @Get("/private")
                private String private0() {
                    return "hello";
                }
            });

            sb.annotatedService("/named", new AccessModifierTest());
        }
    };

    @LoggingDecorator
    @ResponseConverter(UnformattedStringConverterFunction.class)
    private static class AccessModifierTest {
        @Get("/public")
        public String public0() {
            return "hello";
        }

        @Get("/public/static")
        public static String publicStatic0() {
            return "hello";
        }

        @Get("/package")
        String package0() {
            return "hello";
        }

        @Get("/package/static")
        static String packageStatic0() {
            return "hello";
        }

        @Get("/private")
        private String private0() {
            return "hello";
        }

        @Get("/private/static")
        private static String privateStatic0() {
            return "hello";
        }
    }

    @Test
    public void testAccessModifier() throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(rule.httpUri());

        assertThat(client.get("/anonymous/public").contentUtf8())
                .isEqualTo("hello");
        assertThat(client.get("/anonymous/package").status())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(client.get("/anonymous/private").status())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(client.get("/named/public").contentUtf8())
                .isEqualTo("hello");
        assertThat(client.get("/named/public/static").contentUtf8())
                .isEqualTo("hello");
        assertThat(client.get("/named/package").status())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(client.get("/named/package/static").status())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(client.get("/named/private").status())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(client.get("/named/private/static").status())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
