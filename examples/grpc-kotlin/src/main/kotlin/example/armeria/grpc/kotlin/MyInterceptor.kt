package example.armeria.grpc.kotlin

import io.grpc.ForwardingServerCall
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status

class MyInterceptor : ServerInterceptor {
    override fun <ReqT : Any?, RespT : Any?> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        println("MyInterceptor: interceptCall")
        return next.startCall(MyHandler(call), headers)
    }

    class MyHandler<ReqT : Any?, RespT : Any?>(
        call: ServerCall<ReqT, RespT>,
    ) : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
        override fun close(status: Status?, trailers: Metadata?) {
            println("MyInterceptor: close")
            super.close(status, trailers)
        }
    }
}