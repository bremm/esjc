package lt.msemys.esjc;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.timeout.IdleStateHandler;
import lt.msemys.esjc.node.NodeEndPoints;
import lt.msemys.esjc.operation.UserCredentials;
import lt.msemys.esjc.operation.manager.OperationManager;
import lt.msemys.esjc.subscription.VolatileSubscription;
import lt.msemys.esjc.subscription.manager.SubscriptionManager;
import lt.msemys.esjc.tcp.TcpPackage;
import lt.msemys.esjc.tcp.TcpPackageDecoder;
import lt.msemys.esjc.tcp.TcpPackageEncoder;
import lt.msemys.esjc.tcp.handler.AuthenticationHandler;
import lt.msemys.esjc.tcp.handler.AuthenticationHandler.AuthenticationStatus;
import lt.msemys.esjc.tcp.handler.HeartbeatHandler;
import lt.msemys.esjc.tcp.handler.OperationHandler;

import java.util.concurrent.CompletableFuture;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static lt.msemys.esjc.util.Preconditions.checkNotNull;

public abstract class AbstractEventStore {
    private static final int MAX_FRAME_LENGTH = 64 * 1024 * 1024;

    protected enum ConnectionState {INIT, CONNECTING, CONNECTED, CLOSED}

    protected final EventLoopGroup group;
    protected final Bootstrap bootstrap;
    protected final OperationManager operationManager;
    protected final SubscriptionManager subscriptionManager;
    protected final Settings settings;

    protected volatile Channel connection;

    protected AbstractEventStore(Settings settings) {
        checkNotNull(settings, "settings");

        group = new NioEventLoopGroup();

        bootstrap = new Bootstrap()
            .option(ChannelOption.SO_KEEPALIVE, settings.tcpSettings.keepAlive)
            .option(ChannelOption.TCP_NODELAY, settings.tcpSettings.tcpNoDelay)
            .option(ChannelOption.SO_SNDBUF, settings.tcpSettings.sendBufferSize)
            .option(ChannelOption.SO_RCVBUF, settings.tcpSettings.receiveBufferSize)
            .option(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, settings.tcpSettings.writeBufferLowWaterMark)
            .option(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, settings.tcpSettings.writeBufferHighWaterMark)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) settings.tcpSettings.connectTimeout.toMillis())
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) throws Exception {
                    ChannelPipeline pipeline = ch.pipeline();

                    // decoder
                    pipeline.addLast("frame-decoder", new LengthFieldBasedFrameDecoder(LITTLE_ENDIAN, MAX_FRAME_LENGTH, 0, 4, 0, 4, true));
                    pipeline.addLast("package-decoder", new TcpPackageDecoder());

                    // encoder
                    pipeline.addLast("frame-encoder", new LengthFieldPrepender(LITTLE_ENDIAN, 4, 0, false));
                    pipeline.addLast("package-encoder", new TcpPackageEncoder());

                    // logic
                    pipeline.addLast("idle-state-handler", new IdleStateHandler(0, settings.heartbeatInterval.toMillis(), 0, MILLISECONDS));
                    pipeline.addLast("heartbeat-handler", new HeartbeatHandler(settings.heartbeatTimeout));
                    pipeline.addLast("authentication-handler", new AuthenticationHandler(settings.userCredentials, settings.operationTimeout)
                        .whenComplete(AbstractEventStore.this::onAuthenticationCompleted));
                    pipeline.addLast("operation-handler", new OperationHandler(operationManager, subscriptionManager)
                        .whenBadRequest(AbstractEventStore.this::onBadRequest)
                        .whenReconnect(AbstractEventStore.this::onReconnect));
                }
            });

        operationManager = new OperationManager(settings);
        subscriptionManager = new SubscriptionManager(settings);

        this.settings = settings;
    }

    public CompletableFuture<DeleteResult> deleteStream(String stream,
                                                        ExpectedVersion expectedVersion) {
        return deleteStream(stream, expectedVersion, false, null);
    }

    public CompletableFuture<DeleteResult> deleteStream(String stream,
                                                        ExpectedVersion expectedVersion,
                                                        UserCredentials userCredentials) {
        return deleteStream(stream, expectedVersion, false, userCredentials);
    }

    public CompletableFuture<DeleteResult> deleteStream(String stream,
                                                        ExpectedVersion expectedVersion,
                                                        boolean hardDelete) {
        return deleteStream(stream, expectedVersion, hardDelete, null);
    }

    public abstract CompletableFuture<DeleteResult> deleteStream(String stream,
                                                                 ExpectedVersion expectedVersion,
                                                                 boolean hardDelete,
                                                                 UserCredentials userCredentials);

    public CompletableFuture<WriteResult> appendToStream(String stream,
                                                         ExpectedVersion expectedVersion,
                                                         Iterable<EventData> events) {
        return appendToStream(stream, expectedVersion, events, null);
    }

    public abstract CompletableFuture<WriteResult> appendToStream(String stream,
                                                                  ExpectedVersion expectedVersion,
                                                                  Iterable<EventData> events,
                                                                  UserCredentials userCredentials);

    public CompletableFuture<Transaction> startTransaction(String stream,
                                                           ExpectedVersion expectedVersion) {
        return startTransaction(stream, expectedVersion, null);
    }

    public abstract CompletableFuture<Transaction> startTransaction(String stream,
                                                                    ExpectedVersion expectedVersion,
                                                                    UserCredentials userCredentials);

    public Transaction continueTransaction(long transactionId) {
        return continueTransaction(transactionId, null);
    }

    public abstract Transaction continueTransaction(long transactionId,
                                                    UserCredentials userCredentials);

    public CompletableFuture<EventReadResult> readEvent(String stream,
                                                        int eventNumber,
                                                        boolean resolveLinkTos) {
        return readEvent(stream, eventNumber, resolveLinkTos, null);
    }

    public abstract CompletableFuture<EventReadResult> readEvent(String stream,
                                                                 int eventNumber,
                                                                 boolean resolveLinkTos,
                                                                 UserCredentials userCredentials);

    public CompletableFuture<StreamEventsSlice> readStreamEventsForward(String stream,
                                                                        int start,
                                                                        int count,
                                                                        boolean resolveLinkTos) {
        return readStreamEventsForward(stream, start, count, resolveLinkTos, null);
    }

    public abstract CompletableFuture<StreamEventsSlice> readStreamEventsForward(String stream,
                                                                                 int start,
                                                                                 int count,
                                                                                 boolean resolveLinkTos,
                                                                                 UserCredentials userCredentials);

    public CompletableFuture<StreamEventsSlice> readStreamEventsBackward(String stream,
                                                                         int start,
                                                                         int count,
                                                                         boolean resolveLinkTos) {
        return readStreamEventsBackward(stream, start, count, resolveLinkTos, null);
    }

    public abstract CompletableFuture<StreamEventsSlice> readStreamEventsBackward(String stream,
                                                                                  int start,
                                                                                  int count,
                                                                                  boolean resolveLinkTos,
                                                                                  UserCredentials userCredentials);

    public CompletableFuture<AllEventsSlice> readAllEventsForward(Position position,
                                                                  int maxCount,
                                                                  boolean resolveLinkTos) {
        return readAllEventsForward(position, maxCount, resolveLinkTos, null);
    }

    public abstract CompletableFuture<AllEventsSlice> readAllEventsForward(Position position,
                                                                           int maxCount,
                                                                           boolean resolveLinkTos,
                                                                           UserCredentials userCredentials);

    public CompletableFuture<AllEventsSlice> readAllEventsBackward(Position position,
                                                                   int maxCount,
                                                                   boolean resolveLinkTos) {
        return readAllEventsBackward(position, maxCount, resolveLinkTos, null);
    }

    public abstract CompletableFuture<AllEventsSlice> readAllEventsBackward(Position position,
                                                                            int maxCount,
                                                                            boolean resolveLinkTos,
                                                                            UserCredentials userCredentials);

    public CompletableFuture<VolatileSubscription> subscribeToStream(String stream,
                                                                     boolean resolveLinkTos,
                                                                     SubscriptionListener listener) {
        return subscribeToStream(stream, resolveLinkTos, listener, null);
    }

    public abstract CompletableFuture<VolatileSubscription> subscribeToStream(String stream,
                                                                              boolean resolveLinkTos,
                                                                              SubscriptionListener listener,
                                                                              UserCredentials userCredentials);

    public CompletableFuture<VolatileSubscription> subscribeToAll(boolean resolveLinkTos,
                                                                  SubscriptionListener listener) {
        return subscribeToAll(resolveLinkTos, listener, null);
    }

    public abstract CompletableFuture<VolatileSubscription> subscribeToAll(boolean resolveLinkTos,
                                                                           SubscriptionListener listener,
                                                                           UserCredentials userCredentials);

    protected abstract void onAuthenticationCompleted(AuthenticationStatus status);

    protected abstract void onBadRequest(TcpPackage tcpPackage);

    protected abstract void onReconnect(NodeEndPoints nodeEndPoints);

    protected ConnectionState connectionState() {
        if (connection == null) {
            return ConnectionState.INIT;
        } else if (connection.isOpen()) {
            return connection.isActive() ? ConnectionState.CONNECTED : ConnectionState.CONNECTING;
        } else {
            return ConnectionState.CLOSED;
        }
    }

}
