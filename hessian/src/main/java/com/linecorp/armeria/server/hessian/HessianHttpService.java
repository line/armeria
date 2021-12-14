package cn.swiftpass.feynman.hessian.server;

import cn.swiftpass.feynman.hessian.internal.server.HessianHttpServiceImpl;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.server.HttpServiceWithRoutes;

/**
 * hessian http service.
 *
 * @author eisig
 */
public interface HessianHttpService extends HttpServiceWithRoutes {

    /**
     * Creates a new instance of {@link HessianHttpServiceBuilder} which can build an
     * instance of {@link HessianHttpServiceImpl} fluently.
     *
     * <p>
     * Currently, the only way to specify a serialization format is by using the HTTP
     * session protocol and setting the {@code "Content-Type"} header to the appropriate
     * {@link SerializationFormat#mediaType()}.
     */
    static HessianHttpServiceBuilder builder() {
        return new HessianHttpServiceBuilder();
    }

}
