# Why isn't curator recovering when zookeeper is back online?

I have a `CuratorFramework` client (v5.1.0) running against a Zookeeper server (v3.7.0). If the 
Zookeeper server is shutdown while the client is connected to it I can see connection states
(with a `ConnectionStateListener`) of `SUSPENDED` and then `LOST` and then nothing more even 
when the server comes back online.

This feels like a very standard use case and that I must be missing something silly, but I can never
get the client to connect again once the server is online.

I have done some google searching and found nothing useful about how to handle recovery after a LOST state.

I have a [self-contained example](https://github.com/cjstehno/curator-recovery) of what I am doing with 
the example code in the 
[CuratorRecoveryTest](https://github.com/cjstehno/curator-recovery/blob/master/src/test/java/demo/CuratorRecoveryTest.java) class 
(run in IDE rather than maven). The meat of it is (extracted from the test class):

```java
// setup the server and client
server = new TestingServer();

client = newClient(server.getConnectString(), 60000, 15000, new RetryNTimes(1, 250));
client.start();
client.blockUntilConnected();
            
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
```

When this is run I get the following output:

```
[Thread-0] INFO org.apache.curator.test.TestingZooKeeperMain - Starting server
[Thread-0] WARN org.apache.zookeeper.server.ServerCnxnFactory - maxCnxns is not configured, using default value 0.
[main] INFO org.apache.curator.framework.imps.CuratorFrameworkImpl - Starting
[main] INFO org.apache.curator.framework.imps.CuratorFrameworkImpl - Default schema
[main] WARN demo.CuratorRecoveryTest - CONNECTION-STATE-CHANGE: null --> CONNECTED
[main] DEBUG demo.CuratorRecoveryTest - Taking a 3s nap for initial settling...
[main] DEBUG demo.CuratorRecoveryTest - Done napping for initial settling...
[Curator-ConnectionStateManager-0] WARN demo.CuratorRecoveryTest - CONNECTION-STATE-CHANGE: CONNECTED --> SUSPENDED
[main] INFO demo.CuratorRecoveryTest - >>>>>>>>>> STOPPED ZK SERVER
[main] DEBUG demo.CuratorRecoveryTest - Taking a 3s nap for letting things bake...
[main] DEBUG demo.CuratorRecoveryTest - Done napping for letting things bake...
[main] DEBUG demo.CuratorRecoveryTest - Taking a 3s nap for disconnecting...
[main] DEBUG demo.CuratorRecoveryTest - Done napping for disconnecting...
[main] INFO demo.CuratorRecoveryTest - >>>>>>>>>> STARTED ZK SERVER
[Curator-ConnectionStateManager-0] WARN org.apache.curator.framework.state.ConnectionStateManager - Session timeout has elapsed while SUSPENDED. Injecting a session expiration. Elapsed ms: 20009. Adjusted session timeout ms: 20000
[main-EventThread] WARN org.apache.curator.ConnectionState - Session expired event received
[Curator-ConnectionStateManager-0] WARN demo.CuratorRecoveryTest - CONNECTION-STATE-CHANGE: SUSPENDED --> LOST
```

which then fails when the waiting condition never happens.

> NOTE: This happens on an older version combination of Curator and Zookeeper as well, so this is not a "bleeding edge" issue.

What am I missing?

