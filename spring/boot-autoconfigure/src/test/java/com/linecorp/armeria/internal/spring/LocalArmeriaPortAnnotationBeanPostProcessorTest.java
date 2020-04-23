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
package com.linecorp.armeria.internal.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.spring.LocalArmeriaPort;

class LocalArmeriaPortAnnotationBeanPostProcessorTest {

    private DefaultListableBeanFactory bf;

    private LocalArmeriaPortAnnotationBeanPostProcessor bpp;

    @BeforeEach
    void setup() {
        bf = new DefaultListableBeanFactory();
        bf.registerResolvableDependency(BeanFactory.class, bf);
        bpp = new LocalArmeriaPortAnnotationBeanPostProcessor();
        bpp.setBeanFactory(bf);
        bf.addBeanPostProcessor(bpp);
        bf.setDependencyComparator(AnnotationAwareOrderComparator.INSTANCE);
    }

    @AfterEach
    public void close() {
        bf.destroySingletons();
    }

    @Test
    public void testCustomAnnotationRequiredFieldResourceInjection() {
        final ServerBuilder builder = Server.builder();
        builder.service("/hello", (ctx, req) -> HttpResponse.of("hello"));

        final Server server = builder.build();
        server.start().handle((result, t) -> {
            if (t != null) {
                throw new IllegalStateException("Armeria server failed to start", t);
            }
            return result;
        }).join();

        bf.registerBeanDefinition("customBean", new RootBeanDefinition(
                CustomAnnotationFieldResourceInjectionBean.class));
        bf.registerSingleton("server", server);

        final LocalArmeriaPortAnnotationAutowireCandidateResolver resolver =
                new LocalArmeriaPortAnnotationAutowireCandidateResolver();
        resolver.setServer(server);
        bf.setAutowireCandidateResolver(resolver);

        final CustomAnnotationFieldResourceInjectionBean bean =
                (CustomAnnotationFieldResourceInjectionBean) bf.getBean("customBean");
        assertThat(bean.defaultPort()).isEqualTo(server.activeLocalPort());
        assertThat(bean.httpPort()).isEqualTo(server.activeLocalPort(SessionProtocol.HTTP));
    }

    static class CustomAnnotationFieldResourceInjectionBean {

        @LocalArmeriaPort
        private Integer defaultPort;

        @LocalArmeriaPort(SessionProtocol.HTTP)
        private Integer httpPort;

        public Integer defaultPort() {
            return defaultPort;
        }

        public Integer httpPort() {
            return httpPort;
        }
    }
}
