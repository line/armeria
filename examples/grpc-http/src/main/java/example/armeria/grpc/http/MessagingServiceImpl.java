package example.armeria.grpc.http;

import example.armeria.grpc.http.messaging.v2.MessagingGrpc.MessagingImplBase;
import example.armeria.grpc.http.messaging.v2.MessagingOuterClass.GetMessageRequestV1;
import example.armeria.grpc.http.messaging.v2.MessagingOuterClass.GetMessageRequestV2;
import example.armeria.grpc.http.messaging.v2.MessagingOuterClass.GetMessageRequestV3;
import example.armeria.grpc.http.messaging.v2.MessagingOuterClass.MessageV1;
import example.armeria.grpc.http.messaging.v2.MessagingOuterClass.MessageV2;
import example.armeria.grpc.http.messaging.v2.MessagingOuterClass.UpdateMessageRequestV1;
import io.grpc.stub.StreamObserver;

/**
 * Sends the result of {@code request.toString()} via {@code text} field of {@link MessageV1} or {@link MessageV2}.
 */
public class MessagingServiceImpl extends MessagingImplBase {
    @Override
    public void getMessageV1(GetMessageRequestV1 request, StreamObserver<MessageV1> responseObserver) {
        responseObserver.onNext(MessageV1.newBuilder().setText(request.toString()).build());
        responseObserver.onCompleted();
    }

    @Override
    public void getMessageV2(GetMessageRequestV2 request, StreamObserver<MessageV1> responseObserver) {
        responseObserver.onNext(MessageV1.newBuilder().setText(request.toString()).build());
        responseObserver.onCompleted();
    }

    @Override
    public void updateMessageV1(UpdateMessageRequestV1 request, StreamObserver<MessageV1> responseObserver) {
        responseObserver.onNext(MessageV1.newBuilder().setText(request.toString()).build());
        responseObserver.onCompleted();
    }

    @Override
    public void updateMessageV2(MessageV2 request, StreamObserver<MessageV2> responseObserver) {
        responseObserver.onNext(MessageV2.newBuilder()
                                         .setMessageId(request.getMessageId())
                                         .setText(request.toString())
                                         .build());
        responseObserver.onCompleted();
    }

    @Override
    public void getMessageV3(GetMessageRequestV3 request, StreamObserver<MessageV2> responseObserver) {
        responseObserver.onNext(MessageV2.newBuilder()
                                         .setMessageId(request.getMessageId())
                                         .setText(request.toString())
                                         .build());
        responseObserver.onCompleted();
    }
}
