/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.grpc.shared;

import java.io.IOException;

import com.google.common.io.Resources;
import com.google.protobuf.Empty;

import com.linecorp.armeria.grpc.GithubApi.SearchResponse;
import com.linecorp.armeria.grpc.GithubServiceGrpc.GithubServiceImplBase;

import io.grpc.stub.StreamObserver;

/**
 * The {@link GithubApiService} mocks the GitHub API by always sending the same response.
 */
public class GithubApiService extends GithubServiceImplBase {

    public static final SearchResponse SEARCH_RESPONSE;

    static {
        try {
            SEARCH_RESPONSE = SearchResponse.parseFrom(
                    Resources.toByteArray(Resources.getResource("github_search_response.binarypb")));
        } catch (IOException e) {
            throw new Error("Could not read proto.", e);
        }
    }

    @Override
    public void simple(SearchResponse request, StreamObserver<SearchResponse> responseObserver) {
        responseObserver.onNext(SEARCH_RESPONSE);
        responseObserver.onCompleted();
    }

    @Override
    public void empty(Empty request, StreamObserver<Empty> responseObserver) {
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }
}
