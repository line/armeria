package example.armeria.server.blog.grpc;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;

import io.grpc.Metadata;
import io.grpc.Status;

class GrpcExceptionHandler implements GrpcExceptionHandlerFunction {

    @Nullable
    @Override
    public Status apply(RequestContext ctx, Throwable cause, Metadata metadata) {
        if (cause instanceof IllegalArgumentException) {
            return Status.INVALID_ARGUMENT.withCause(cause);
        }
        if (cause instanceof BlogNotFoundException) {
            return Status.NOT_FOUND.withCause(cause).withDescription(cause.getMessage());
        }

        // The default mapping function will be applied.
        return null;
    }
}
