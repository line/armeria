package com.linecorp.armeria.server.thrifty;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.server.DecoratingService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ThriftyHttpService extends DecoratingService<RpcRequest, RpcResponse, HttpRequest, HttpResponse> implements HttpService {

    private static final Logger logger = LoggerFactory.getLogger(ThriftyHttpService.class);

    public static ThriftyHttpServiceBuilder builder() {
        return new ThriftyHttpServiceBuilder();
    }

    public static ThriftyHttpService of(Object implementation) {
        return of(implementation)
    }

    /**
     * Creates a new instance that decorates the specified {@link Service}.
     *
     * @param delegate
     */
    protected ThriftyHttpService(Service<RpcRequest, RpcResponse> delegate) {
        super(delegate);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return null;
    }
}
