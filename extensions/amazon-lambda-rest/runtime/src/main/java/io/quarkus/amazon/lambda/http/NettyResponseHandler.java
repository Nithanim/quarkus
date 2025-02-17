package io.quarkus.amazon.lambda.http;

import java.io.ByteArrayOutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import com.amazonaws.serverless.proxy.internal.LambdaContainerHandler;
import com.amazonaws.serverless.proxy.model.ContainerConfig;

import io.netty.channel.FileRegion;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.quarkus.amazon.lambda.http.model.AwsProxyRequest;
import io.quarkus.amazon.lambda.http.model.AwsProxyResponse;
import io.quarkus.amazon.lambda.http.model.Headers;
import io.quarkus.netty.runtime.virtual.VirtualResponseHandler;

class NettyResponseHandler implements VirtualResponseHandler {
    private static final int BUFFER_SIZE = 8096;

    private final AwsProxyResponse responseBuilder = new AwsProxyResponse();
    private final CompletableFuture<AwsProxyResponse> future = new CompletableFuture<>();
    private final AwsProxyRequest request;
    private ByteArrayOutputStream baos;
    private WritableByteChannel byteChannel;

    public NettyResponseHandler(AwsProxyRequest request) {
        this.request = request;
    }

    public CompletableFuture<AwsProxyResponse> getFuture() {
        return future;
    }

    @Override
    public void handleMessage(Object msg) {
        try {
            if (msg instanceof HttpResponse) {
                HttpResponse res = (HttpResponse) msg;
                responseBuilder.setStatusCode(res.status().code());

                if (request.getRequestSource() == AwsProxyRequest.RequestSource.ALB) {
                    responseBuilder.setStatusDescription(res.status().reasonPhrase());
                }
                responseBuilder.setMultiValueHeaders(new Headers());
                for (String name : res.headers().names()) {
                    for (String v : res.headers().getAll(name)) {
                        responseBuilder.getMultiValueHeaders().add(name, v);
                    }
                }
            }
            if (msg instanceof HttpContent) {
                HttpContent content = (HttpContent) msg;
                int readable = content.content().readableBytes();
                if (baos == null && readable > 0) {
                    baos = new ByteArrayOutputStream(BUFFER_SIZE);
                }
                for (int i = 0; i < readable; i++) {
                    baos.write(content.content().readByte());
                }
            }
            if (msg instanceof FileRegion) {
                FileRegion file = (FileRegion) msg;
                if (file.count() > 0 && file.transferred() < file.count()) {
                    if (baos == null) {
                        baos = new ByteArrayOutputStream(BUFFER_SIZE);
                    }
                    if (byteChannel == null) {
                        byteChannel = Channels.newChannel(baos);
                    }
                    file.transferTo(byteChannel, file.transferred());
                }
            }
            if (msg instanceof LastHttpContent) {
                if (baos != null) {
                    if (isBinary(responseBuilder.getMultiValueHeaders().getFirst("Content-Type"))) {
                        responseBuilder.setBase64Encoded(true);
                        responseBuilder.setBody(Base64.getMimeEncoder().encodeToString(baos.toByteArray()));
                    } else {
                        responseBuilder.setBody(new String(baos.toByteArray(), StandardCharsets.UTF_8));
                    }
                }
                future.complete(responseBuilder);
            }
        } catch (Throwable ex) {
            future.completeExceptionally(ex);
        } finally {
            if (msg != null) {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    private boolean isBinary(String contentType) {
        if (contentType != null) {
            ContainerConfig containerConfig = LambdaContainerHandler.getContainerConfig();

            int index = contentType.indexOf(';');
            if (index >= 0) {
                return containerConfig.isBinaryContentType(contentType.substring(0, index));
            } else {
                return containerConfig.isBinaryContentType(contentType);
            }
        } else {
            return false;
        }
    }

    @Override
    public void close() {
        if (!future.isDone()) {
            future.completeExceptionally(new RuntimeException("Connection closed"));
        }
    }
}
