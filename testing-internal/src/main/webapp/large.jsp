<%@ page contentType="text/plain; charset=UTF-8" %>
<%
  final StringBuilder buf = new StringBuilder();
  for (int i = 0; i < 1024; i++) {
    buf.append("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n");
  }
%>
<%= buf %>
