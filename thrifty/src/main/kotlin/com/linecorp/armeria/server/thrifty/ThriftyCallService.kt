package com.linecorp.armeria.server.thrifty

import com.linecorp.armeria.common.RpcRequest
import com.linecorp.armeria.common.RpcResponse
import com.linecorp.armeria.server.RpcService
import com.linecorp.armeria.server.ServiceRequestContext

class ThriftyCallService : RpcService {
    override fun serve(ctx: ServiceRequestContext, req: RpcRequest): RpcResponse {
        TODO("Not yet implemented")
    }
}