package example.armeria.server.blog.grpc;

final class BlogNotFoundException extends IllegalStateException {
    private static final long serialVersionUID = -2914549282978136686L;

    BlogNotFoundException(String s) {
        super(s);
    }
}
