package transport.udp.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import transport.Address;
import transport.Configs;
import transport.ErrorResolver;
import transport.IoProcessor;
import transport.MessageHelper;
import transport.MessageListener;
import transport.ZLive;

/**
 *
 * @author thuannv
 */
public final class UDPManager {

    private static final int UDP_HANDSHAKE = 300;
    private static final int UDP_PING = 301;
    private static final int UDP_PONG = 302;
    private static final int UDP_CLIEN_CLOSED = 303;

    private static final int SUB_UDP_HANDSHAKE_SUCCESS = 300;
    private static final int SUB_UDP_HANDSHAKE_FAILURE = 301;

    private static volatile UDPManager sInstance = null;

    private volatile boolean mIsInitializing = false;

    private Configs mConfigs;

    private UDPClient mClient;

    private UDPConnector mConnector;

    private PingScheduler mPingScheduler;

    private Handshaker mHandshaker;

    private MessageListener mListener;

    private int mServerIndex = 0;

    private volatile boolean mIsReady = false;

    public static UDPManager getsInstance() {
        UDPManager localInstance = sInstance;
        if (localInstance == null) {
            synchronized (UDPManager.class) {
                localInstance = sInstance;
                if (localInstance == null) {
                    localInstance = sInstance = new UDPManager();
                }
            }
        }
        return localInstance;
    }

    public void init(Configs configs) {
        if (configs == null) {
            throw new IllegalArgumentException("configs must NOT be null.");
        }
        System.out.println("Initializing UDPManager...");

        mConfigs = configs;
        mServerIndex = 0;
        mHandshaker = new Handshaker();
        mPingScheduler = new PingScheduler();
    }

    public void startClient() {
        if (mIsInitializing) {
            return;
        }
        connect();
    }

    public void stopClient() {
        if (mIsReady || mIsInitializing) {
            mIsReady = false;
            mIsInitializing = false;
            mServerIndex = 0;
            mHandshaker.stopChecking();
            mPingScheduler.stop();
            
            resetConnector();
            resetClient();
            
            mPingScheduler = null;
            mHandshaker = null;
        }
    }

    private synchronized void connect() {
        mIsInitializing = true;
        final Address server = mConfigs.getServer(mServerIndex);
        System.out.format("Connect to: %s\n", server.toString());

        final UDPConfigs udpConfigs = new UDPConfigs(server.getHost(), server.getPort(), 64 * 1024, 15000);
        mConnector = new UDPConnector(udpConfigs);
        mConnector.start();
    }

    private void resetConnector() {
        if (mConnector != null) {
            if (mConnector.isAlive() && !mConnector.isInterrupted()) {
                mConnector.interrupt();
            }
            mConnector = null;
        }
    }

    private void tryConnectToNextServer() {
        System.out.println("Try connecting to next server...");

        mIsReady = false;

        resetConnector();

        final int serverCount = mConfigs.getServerCount();
        if (++mServerIndex == serverCount) {
            System.out.println("No more servers to try.");
            mServerIndex = 0;
            mPingScheduler.stop();
            resetClient();
        } else {
            connect();
        }

    }

    public void setListener(MessageListener listener) {
        mListener = listener;
    }

    private void notifyListener(ZLive.ZAPIMessage message) {
        if (mListener != null) {
            mListener.onMessage(message);
        }
    }

    public synchronized void send(byte[] data) {
        if (mIsReady && mClient != null) {
            mClient.send(data);
        }
    }

    private void sendPing() {
        byte[] data = ZLive.ZAPIMessage.newBuilder()
                .setCmd(UDP_PING)
                .build()
                .toByteArray();
        send(data);
        System.out.println("Sent ping.");
    }

    private synchronized void resetClient() {
        if (mClient != null) {
            System.out.println("Stopping client...");
            mClient.setProcessor(null);
            mClient.stop();
            mClient = null;
        }
    }

    private synchronized void createClient(UDPConfigs configs) throws IOException {
        mClient = new UDPClient(configs);
        mClient.setProcessor(new InternalIoProcessor());
        mClient.start();
    }

    /**
     *
     */
    private final class InternalIoProcessor implements IoProcessor {

        @Override
        public void process(byte[] data) {
            ZLive.ZAPIMessage message = null;
            try {
                message = ZLive.ZAPIMessage.parseFrom(data);
                if (mHandshaker.handleHanshake(message)) {
                    return;
                }
                if (mPingScheduler.handlePong(message)) {
                    return;
                }
                notifyListener(message);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     *
     */
    private final class UDPConnector extends Thread {

        private final UDPConfigs mUDPConfigs;

        public UDPConnector(UDPConfigs configs) {
            mUDPConfigs = configs;
        }

        private void onError(Throwable t) {
            mIsInitializing = false;
            System.out.format("onError()/Error=%s\n", t.toString());
        }

        private void onStarted() {
            System.out.println("Starting handshake...");

            mIsInitializing = false;

            mHandshaker.handshake();
        }

        @Override
        public void run() {
            resetClient();
            try {
                createClient(mUDPConfigs);
                onStarted();
            } catch (IOException ex) {
                onError(ex);
            }
        }
    }

    /**
     *
     */
    private final class Handshaker {

        private final long HANDSHAKE_TIMEOUT_MILLIS = 5000; // 5 seconds

        private volatile Timer mTimer;

        private volatile boolean mHandshakeSuccess = false;

        public void handshake() {

            mIsReady = false;

            if (mClient != null) {
                mHandshakeSuccess = false;

                //send handshake data to server
                mClient.send(MessageHelper.createProtoMessage(UDP_HANDSHAKE));

                // start checking for timeout
                startChecking();
            }
        }

        public synchronized void startChecking() {
            if (mTimer == null) {
                mTimer = new Timer();
                mTimer.schedule(new TimerTask() {

                    @Override
                    public void run() {
                        stopChecking();
                        onHandshakeTimeout();
                    }
                }, HANDSHAKE_TIMEOUT_MILLIS);
            }
        }

        public synchronized void stopChecking() {
            if (mTimer != null) {
                mTimer.cancel();
                mTimer = null;
            }
        }

        public boolean handleHanshake(ZLive.ZAPIMessage message) {
            if (mHandshakeSuccess) {
                return false;
            }

            if (message.getCmd() == UDP_HANDSHAKE) {
                stopChecking();
                int subCommand = message.getSubCmd();
                if (subCommand == SUB_UDP_HANDSHAKE_SUCCESS) {
                    onHandshakeSuccess();
                } else if (subCommand == SUB_UDP_HANDSHAKE_FAILURE) {
                    int errorCode = -1;
                    try {
                        errorCode = ByteBuffer.wrap(message.getData().toByteArray()).getInt();
                    } catch (Exception e) {
                    }
                    onHandshakeFailed(errorCode, ErrorResolver.resolve(errorCode));
                }
                return true;
            }

            return false;
        }

        private void onHandshakeTimeout() {
            System.out.println("Hanshake timeout.");
            mHandshakeSuccess = false;
            tryConnectToNextServer();
        }

        private void onHandshakeFailed(int code, String reason) {
            mHandshakeSuccess = false;
            System.out.format("Hanshake failed code=%d, reason=%s\n", code, reason);
            if (ErrorResolver.isServerError(code)) {
                tryConnectToNextServer();
            }
        }

        private void onHandshakeSuccess() {
            mIsReady = true;

            mHandshakeSuccess = true;

            mPingScheduler.start();

            System.out.println("Hanshake success");

        }
    }

    /**
     *
     */
    private final class PingScheduler {

        private long mLastPong = 0;

        private int mRetryCount = 0;

        private volatile Timer mTimer;

        private void ping() {
            final long time = System.currentTimeMillis() - mLastPong;
            final int pingTime = mConfigs.getPingTime();
            final int maxRetry = mConfigs.getRetryCount();
            if (time > pingTime) {
                ++mRetryCount;
            }
            if (mRetryCount <= maxRetry) {
                System.out.format("Ping retry count: %d\n", mRetryCount);
                sendPing();
                scheduleNext();
            } else {
                mRetryCount = 0;
                mLastPong = 0;
                tryConnectToNextServer();
            }
        }

        public void onPong() {
            mLastPong = System.currentTimeMillis();
            mRetryCount = 0;
        }

        public boolean handlePong(ZLive.ZAPIMessage message) {
            if (message != null && message.getCmd() == UDP_PONG) {
                onPong();
                return true;
            }
            return false;
        }

        public void start() {
            if (mTimer == null) {
                mTimer = new Timer();
                scheduleNext();
            }
        }

        private void scheduleNext() {
            if (mTimer != null) {
                mTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        ping();
                    }
                }, mConfigs.getPingTime());
            }
        }

        public void stop() {
            if (mTimer != null) {
                mTimer.cancel();
                mTimer = null;
            }
        }
    }

    private static final boolean LOCAL = true;

    public static void main(String[] args) throws InterruptedException {
        int pingTime;
        final List<Address> servers = new ArrayList<>();
        if (LOCAL) {
            pingTime = 5000;
            servers.add(new Address("127.0.0.1", 3333));
            servers.add(new Address("localhost", 4444));
        } else {
            pingTime = 15000;
            servers.add(new Address("49.213.118.166", 11114));
        }
        final Configs configs = new Configs(servers, pingTime, 10);
       
        UDPManager.getsInstance().init(configs);
        UDPManager.getsInstance().startClient();
        UDPManager.getsInstance().setListener(new MessageListener() {
            @Override
            public void onMessage(ZLive.ZAPIMessage message) {
                System.out.println(message.getData().toString(Charset.defaultCharset()));
            }
        });
        
        final CountDownLatch restartLatch = new CountDownLatch(1);
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 10; i++) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ex) {
                    }
                    UDPManager.getsInstance().send(MessageHelper.createProtoMessage(10000));
                }
                UDPManager.getsInstance().stopClient();
                restartLatch.countDown();
            }
        }).start();
        
        
        
        restartLatch.await();
        
        UDPManager.getsInstance().init(configs);
        UDPManager.getsInstance().startClient();
        UDPManager.getsInstance().setListener(new MessageListener() {
            @Override
            public void onMessage(ZLive.ZAPIMessage message) {
                System.out.println(message.getData().toString(Charset.defaultCharset()));
            }
        });

        
        final CountDownLatch restartLatch1 = new CountDownLatch(1);
        
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 10; i++) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ex) {
                    }
                    UDPManager.getsInstance().send(MessageHelper.createProtoMessage(10000));
                }
                UDPManager.getsInstance().stopClient();
                restartLatch1.countDown();
            }
        }).start();
        
        
        
        restartLatch1.await();
        
       
        UDPManager.getsInstance().init(configs);
        UDPManager.getsInstance().startClient();
        UDPManager.getsInstance().setListener(new MessageListener() {
            @Override
            public void onMessage(ZLive.ZAPIMessage message) {
                System.out.println(message.getData().toString(Charset.defaultCharset()));
            }
        });

        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 10; i++) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ex) {
                    }
                    UDPManager.getsInstance().send(MessageHelper.createProtoMessage(10000));
                }
                UDPManager.getsInstance().stopClient();
            }
        }).start();
    }
}
