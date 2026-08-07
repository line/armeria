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

package com.linecorp.armeria.spring.xds;

import static java.util.Objects.requireNonNull;

import java.util.Objects;
import java.util.Properties;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertySourceFactory;

import com.linecorp.armeria.common.annotation.Nullable;

final class YamlFilePropertySourceFactory implements PropertySourceFactory {

    @Override
    public PropertySource<?> createPropertySource(@Nullable String name, EncodedResource resource) {
        final YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(resource.getResource());
        final Properties properties = requireNonNull(factory.getObject(), "properties");
        final String sourceName = Objects.requireNonNullElseGet(
                name, () -> requireNonNull(resource.getResource().getFilename(),
                                           "resource must be file-backed"));
        return new PropertiesPropertySource(sourceName, properties);
    }
}
