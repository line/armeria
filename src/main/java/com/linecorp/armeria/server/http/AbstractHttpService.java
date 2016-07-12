/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http;

import com.linecorp.armeria.common.http.DefaultHttpResponse;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;

public abstract class AbstractHttpService implements HttpService {

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final DefaultHttpResponse res = new DefaultHttpResponse();
        switch (req.method()) {
            case OPTIONS:
                doOptions(ctx, req, res);
                break;
            case GET:
                doGet(ctx, req, res);
                break;
            case HEAD:
                doHead(ctx, req, res);
                break;
            case POST:
                doPost(ctx, req, res);
                break;
            case PUT:
                doPut(ctx, req, res);
                break;
            case PATCH:
                doPatch(ctx, req, res);
                break;
            case DELETE:
                doDelete(ctx, req, res);
                break;
            case TRACE:
                doTrace(ctx, req, res);
                break;
            default:
                res.respond(HttpStatus.METHOD_NOT_ALLOWED);
        }

        return res;
    }

    protected void doOptions(ServiceRequestContext ctx,
                             HttpRequest req, HttpResponseWriter res) throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    protected void doGet(ServiceRequestContext ctx,
                         HttpRequest req, HttpResponseWriter res) throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    protected void doHead(ServiceRequestContext ctx,
                          HttpRequest req, HttpResponseWriter res) throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    protected void doPost(ServiceRequestContext ctx,
                          HttpRequest req, HttpResponseWriter res) throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    protected void doPut(ServiceRequestContext ctx,
                         HttpRequest req, HttpResponseWriter res) throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    protected void doPatch(ServiceRequestContext ctx,
                           HttpRequest req, HttpResponseWriter res) throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    protected void doDelete(ServiceRequestContext ctx,
                            HttpRequest req, HttpResponseWriter res) throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    protected void doTrace(ServiceRequestContext ctx,
                           HttpRequest req, HttpResponseWriter res) throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }
}
