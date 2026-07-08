/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.client.Preprocessor;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.RouteCluster;
import com.linecorp.armeria.xds.internal.XdsCommonUtil;

final class RouterFilter<I extends Request, O extends Response> implements Preprocessor<I, O> {

    private final boolean isRpc;

    RouterFilter(boolean isRpc) {
        this.isRpc = isRpc;
    }

    @Override
    public O execute(PreClient<I, O> delegate, PreClientRequestContext ctx, I req) throws Exception {
        final RouteCluster routeCluster = ctx.attr(XdsCommonUtil.ROUTE_CLUSTER);
        if (routeCluster == null) {
            final UnprocessedRequestException e = UnprocessedRequestException.of(
                    new IllegalArgumentException(
                            "ROUTE_CLUSTER is not set for the ctx. If a new ctx has been used, " +
                            "please make sure to use ctx.newDerivedContext()."));
            ctx.cancel(e);
            throw e;
        }
        final ClusterSnapshot clusterSnapshot = routeCluster.clusterSnapshot();
        ctx.setAttr(XdsAttributeKeys.ROUTE_METADATA_MATCH, routeCluster.metadataMatch());

        @SuppressWarnings("unchecked")
        final Preprocessor<I, O> preprocessor = (Preprocessor<I, O>) (isRpc ?
                clusterSnapshot.rpcPreprocessor() : clusterSnapshot.preprocessor());
        return preprocessor.execute(delegate, ctx, req);
    }
}
