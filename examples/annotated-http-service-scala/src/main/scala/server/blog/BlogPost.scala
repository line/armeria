package server.blog

import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}

object BlogPost:
  def apply(id: Int, title: String, content: String): BlogPost =
    apply(id, title, content, System.currentTimeMillis())

  def apply(id: Int, title: String, content: String, createdAt: Long): BlogPost =
    BlogPost(id, title, content, createdAt, createdAt)

case class BlogPost(id: Int, title: String, content: String, createAt: Long, modifiedAt: Long):
  @JsonCreator
  def this(@JsonProperty("id") id: Int, @JsonProperty("title") title: String,
            @JsonProperty("content") content: String) =
    this(id, title, content, System.currentTimeMillis(), System.currentTimeMillis())

