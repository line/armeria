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

import java.lang.annotation.Annotation;

import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.SimpleAutowireCandidateResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.Nullable;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.spring.LocalArmeriaPort;

public class LocalArmeriaPortAnnotationAutowireCandidateResolver extends SimpleAutowireCandidateResolver {

    private final Class<? extends Annotation> localArmeriaPortAnnotationType = LocalArmeriaPort.class;

    @Nullable
    private Server server;

    public void setServer(@Nullable Server server) {
        this.server = server;
    }

    /**
     * Determine whether the given dependency declares a value annotation.
     * @see LocalArmeriaPort
     */
    @Override
    @Nullable
    public Object getSuggestedValue(DependencyDescriptor descriptor) {
        Object value = findValue(descriptor.getAnnotations());
        if (value == null) {
            final MethodParameter methodParam = descriptor.getMethodParameter();
            if (methodParam != null) {
                value = findValue(methodParam.getMethodAnnotations());
            }
        }
        return value;
    }

    /**
     * Determine a suggested value from any of the given candidate annotations.
     */
    @Nullable
    protected Object findValue(Annotation[] annotationsToSearch) {
        if (annotationsToSearch.length > 0) {   // qualifier annotations have to be local
            final AnnotationAttributes attr = AnnotatedElementUtils.getMergedAnnotationAttributes(
                    AnnotatedElementUtils.forAnnotations(annotationsToSearch), localArmeriaPortAnnotationType);
            if (attr != null) {
                return extractValue(attr);
            }
        }
        return null;
    }

    /**
     * Extract the value attribute from the given annotation.
     * @since 4.3
     */
    @Nullable
    protected Object extractValue(AnnotationAttributes attr) {
        assert server != null;
        final SessionProtocol value = (SessionProtocol) attr.get(AnnotationUtils.VALUE);
        if (value == null) {
            throw new IllegalStateException("LocalArmeriaPort annotation must have a value attribute");
        }
        if (value == SessionProtocol.NONE) {
            return server.activeLocalPort();
        }

        try {
            return server.activeLocalPort(value);
        } catch (IllegalStateException e) {
            // ignored
            return null;
        }
    }
}
