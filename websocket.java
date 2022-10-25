// Annotation Based
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

@ServerEndpoint("/endpoint")
public class MyWebSocketServer {

   @OnOpen
   public void open(Session session) { }

   @OnClose
   public void close(Session session) { }

   @OnError
   public void error(Throwable error) { }

   @OnMessage
   public void message(String message,
      Session session) { }
}



// Interface Based
 
import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;

public class MyWebsocketServer extends Endpoint{

   @Override
   public void onOpen(Session session,
      EndpointConfig config) {
   }

   public void onClose(Session session,
      CloseReason closeReason){
   }
   public void onError(Session session,
      Throwable thr){
   }
}
