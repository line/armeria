/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.it.client;

import java.util.HashMap;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.remoting.caucho.HessianServiceExporter;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;

import com.linecorp.armeria.hessian.service.HelloService;
import com.linecorp.armeria.hessian.service.HelloServiceImp;

/**
 * config.
 *
 * @author eisig
 */
@Configuration(proxyBeanMethods = false)
public class HessianConfiguration {

    @Bean
    HelloServiceImp helloServiceImp() {
        return new HelloServiceImp();
    }

    @Bean
    @SuppressWarnings("deprecation")
    SimpleUrlHandlerMapping hessianUrlHandlerMapping(HelloServiceImp helloServiceImp) {
        final SimpleUrlHandlerMapping handlerMapping = new SimpleUrlHandlerMapping();
        handlerMapping.setOrder(-100);
        final HessianServiceExporter hessianServiceExporter = new HessianServiceExporter();
        hessianServiceExporter.setService(helloServiceImp);
        hessianServiceExporter.setServiceInterface(HelloService.class);
        hessianServiceExporter.afterPropertiesSet();
        final Map<String, Object> mapping = new HashMap<>();
        mapping.put("/services/helloService.hs", hessianServiceExporter);
        handlerMapping.setUrlMap(mapping);
        return handlerMapping;
    }
}
