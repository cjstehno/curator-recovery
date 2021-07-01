package demo;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.retry.RetryNTimes;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.curator.framework.CuratorFrameworkFactory.newClient;
import static org.apache.curator.framework.state.ConnectionState.CONNECTED;
import static org.apache.curator.framework.state.ConnectionState.RECONNECTED;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CuratorRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(CuratorRecoveryTest.class);
    private TestingServer server;
    private CuratorFramework client;

    @BeforeEach void beforeEach() throws Exception {
        server = new TestingServer();

        client = newClient(server.getConnectString(), 60000, 15000, new RetryNTimes(1, 250));
        client.start();
        client.blockUntilConnected();
    }

    @AfterEach void afterEach() throws Exception {
        client.close();

        stopServer();
    }

    @Test @DisplayName("when the zk server is stopped, the client should reconnect on restart")
    void reconnection() throws Exception {
        // add the listener
        final var stateListener = new StateListener();
        stateListener.stateChanged(client, CONNECTED);

        // register the listener
        client.getConnectionStateListenable().addListener(stateListener);

        // verify connection
        assertTrue(client.getZookeeperClient().isConnected());

        // let things settle
        nap(3, "initial settling");

        // stop zk
        stopServer();
        log.info(">>>>>>>>>> STOPPED ZK SERVER");

        // let it bake
        nap(3, "letting things bake");

        // ensure disconnected
        assertFalse(client.getZookeeperClient().isConnected());

        nap(3, "disconnecting");

        // start zk
        server.start();
        log.info(">>>>>>>>>> STARTED ZK SERVER");

        await().atMost(5, MINUTES).until(() -> stateListener.getCurrentState() == CONNECTED || stateListener.getCurrentState() == RECONNECTED);

        // NOTE: it never gets here - no state changes after LOST

        assertTrue(client.getZookeeperClient().isConnected());
    }

    private void stopServer() throws IOException {
        server.stop();
        server.close();
    }

    private static void nap(final long seconds, final String label) throws InterruptedException {
        log.debug("Taking a {}s nap for {}...", seconds, label);
        SECONDS.sleep(seconds);
        log.debug("Done napping for {}...", label);
    }

    private static class StateListener implements ConnectionStateListener {

        private final AtomicReference<ConnectionState> currentState = new AtomicReference<>(null);
        private final Map<ConnectionState, Runnable> stateHandlers = new EnumMap<>(ConnectionState.class);
        private final Map<ConnectionState, AtomicInteger> stateCounters = new EnumMap<>(ConnectionState.class);

        void onState(final ConnectionState state, final Runnable handler) {
            stateHandlers.put(state, handler);
        }

        @Override
        public void stateChanged(final CuratorFramework curator, final ConnectionState state) {
            log.warn("CONNECTION-STATE-CHANGE: {} --> {}", currentState.get(), state);

            currentState.set(state);
            stateCounters.computeIfAbsent(state, key -> new AtomicInteger(0)).incrementAndGet();

            if (stateHandlers.containsKey(state)) {
                stateHandlers.get(state).run();
            }
        }

        ConnectionState getCurrentState() {
            return currentState.get();
        }

        int getStateCount(final ConnectionState state) {
            return stateCounters.get(state).get();
        }
    }
}
