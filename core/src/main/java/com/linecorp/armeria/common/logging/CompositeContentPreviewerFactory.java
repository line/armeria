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
package com.linecorp.armeria.common.logging;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;

final class CompositeContentPreviewerFactory implements ContentPreviewerFactory {

    final List<ContentPreviewerFactory> factoryList;

    CompositeContentPreviewerFactory(List<ContentPreviewerFactory> factories) {
        factoryList = ImmutableList.copyOf(requireNonNull(factories, "factories"));
    }

    @Override
    public ContentPreviewer get(RequestContext ctx, HttpHeaders headers) {
        for (ContentPreviewerFactory factory : factoryList) {
            final ContentPreviewer previewer = factory.get(ctx, headers);
            if (previewer == null) {
                throw new IllegalStateException(
                        String.format("%s returned null.", factory.getClass().getName()));
            }
            if (previewer != ContentPreviewer.disabled()) {
                return previewer;
            }
        }
        return ContentPreviewer.disabled();
    }
}
