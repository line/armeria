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
package com.linecorp.armeria.dropwizard.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.cert.CertificateException;

import javax.net.ssl.SSLException;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;

import io.dropwizard.validation.PortRange;

class ArmeriaServerDecoratorTest {

    @Test
    void decorate() throws CertificateException, SSLException {
        final ArmeriaServerDecorator decorator = new TestDecorator();
        final ServerBuilder sb = Server.builder().service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        decorator.decorate(sb);
        final Server server = sb.build();
        assertThat(server.config().ports())
                .contains(new ServerPort(0, SessionProtocol.HTTP));
    }

    class TestDecorator implements ArmeriaServerDecorator {
        @Override
        public @PortRange int getPort() {
            return 0;
        }

        @Override
        public String getType() {
            return "armeria-http";
        }
    }
}
