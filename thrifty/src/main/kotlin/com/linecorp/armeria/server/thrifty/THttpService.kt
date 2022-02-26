package com.linecorp.armeria.server.thrifty

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.RpcRequest
import com.linecorp.armeria.common.RpcResponse
import com.linecorp.armeria.server.DecoratingService
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.RpcService
import com.linecorp.armeria.server.Service
import com.linecorp.armeria.server.ServiceRequestContext

class THttpService(delegate: RpcService) :
    DecoratingService<RpcRequest, RpcResponse, HttpRequest, HttpResponse>(delegate), HttpService {

    override fun unwrap(): Service<RpcRequest, RpcResponse> {
        TODO("Not yet implemented")
    }

    override fun serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse {
        TODO("Not yet implemented")
    }

}