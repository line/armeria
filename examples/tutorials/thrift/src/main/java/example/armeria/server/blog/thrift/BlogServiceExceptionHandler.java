package example.armeria.server.blog.thrift;

import java.util.function.BiFunction;

import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.ServiceRequestContext;

import example.armeria.blog.thrift.BlogNotFoundException;

public class BlogServiceExceptionHandler implements BiFunction<ServiceRequestContext, Throwable, RpcResponse> {

    @Override
    public RpcResponse apply(ServiceRequestContext serviceRequestContext, Throwable cause) {
        if (cause instanceof IllegalArgumentException) {
            return RpcResponse.ofFailure(new BlogNotFoundException(cause.getMessage()));
        }
        return RpcResponse.ofFailure(cause);
    }
}
