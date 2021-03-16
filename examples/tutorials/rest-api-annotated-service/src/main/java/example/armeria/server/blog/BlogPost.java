package example.armeria.server.blog;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

final class BlogPost {
    private final int id;
    private final String title;
    private final String content;
    private final long createdAt;
    private final long modifiedAt;

    @JsonCreator
    BlogPost(@JsonProperty("id") int id, @JsonProperty("title") String title,
             @JsonProperty("content") String content) {
        this(id, title, content, System.currentTimeMillis());
    }

    BlogPost(int id, String title, String content, long createdAt) {
        this(id, title, content, createdAt, createdAt);
    }

    BlogPost(int id, String title, String content, long createdAt, long modifiedAt) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.createdAt = createdAt;
        this.modifiedAt = modifiedAt;
    }

    @JsonProperty("id")
    int id() {
        return id;
    }

    @JsonProperty("title")
    String title() {
        return title;
    }

    @JsonProperty("content")
    String content() {
        return content;
    }

    @JsonProperty("createdAt")
    long createdAt() {
        return createdAt;
    }

    @JsonProperty("modifiedAt")
    long modifiedAt() {
        return modifiedAt;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("id", id)
                          .add("title", title)
                          .add("content", content)
                          .add("createdAt", createdAt)
                          .add("modifiedAt", modifiedAt)
                          .toString();
    }
}
