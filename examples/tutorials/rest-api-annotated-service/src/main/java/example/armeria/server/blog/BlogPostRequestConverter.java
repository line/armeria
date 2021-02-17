package example.armeria.server.blog;

import static example.armeria.server.blog.BlogService.intValue;
import static example.armeria.server.blog.BlogService.stringValue;

import java.lang.reflect.ParameterizedType;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;

/**
 * Converts an {@link HttpRequest} to a {@link BlogPost}.
 */
final class BlogPostRequestConverter implements RequestConverterFunction {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpRequest request,
                                 Class<?> expectedResultType,
                                 @Nullable ParameterizedType expectedParameterizedResultType)
            throws Exception {
        if (expectedResultType == BlogPost.class) {
            final JsonNode jsonNode = mapper.readTree(request.contentUtf8());
            final int id = intValue(jsonNode, "id");
            final String title = stringValue(jsonNode, "title");
            final String content = stringValue(jsonNode, "content");
            return new BlogPost(id, title, content);
        }
        return RequestConverterFunction.fallthrough();
    }
}
