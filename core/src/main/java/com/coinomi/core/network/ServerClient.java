package com.coinomi.core.network;

import com.coinomi.core.network.interfaces.BlockchainConnection;
import com.coinomi.core.coins.CoinType;
import com.coinomi.core.network.interfaces.ConnectionEventListener;
import com.coinomi.core.network.interfaces.TransactionEventListener;
import com.coinomi.stratumj.ServerAddress;
import com.coinomi.stratumj.StratumClient;
import com.coinomi.stratumj.messages.CallMessage;
import com.coinomi.stratumj.messages.ResultMessage;
import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.AddressFormatException;
import com.google.bitcoin.core.Sha256Hash;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.TransactionOutPoint;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.utils.ListenerRegistration;
import com.google.bitcoin.utils.Threading;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Service;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import static com.coinomi.core.Preconditions.checkNotNull;
import static com.coinomi.core.Preconditions.checkState;
import static com.google.common.util.concurrent.Service.State.NEW;

/**
 * @author Giannis Dzegoutanis
 */
public class ServerClient implements BlockchainConnection {
    private static final Logger log = LoggerFactory.getLogger(ServerClient.class);

    private static final ScheduledThreadPoolExecutor connectionExec;
    static {
        connectionExec = new ScheduledThreadPoolExecutor(1);
        connectionExec.setRemoveOnCancelPolicy(true);
    }
    private static final Random RANDOM = new Random();

    private static final long MAX_WAIT = 16;

    private CoinType type;
    private final ImmutableList<ServerAddress> addresses;
    private final HashSet<ServerAddress> failedAddresses;
    private ServerAddress lastServerAddress;
    private StratumClient stratumClient;
    private long retrySeconds = 1;
    private boolean stopped = false;

    private transient CopyOnWriteArrayList<ListenerRegistration<ConnectionEventListener>> eventListeners;

    private Runnable reconnectTask = new Runnable() {
        @Override
        public void run() {
            if (!stopped) {
                createStratumClient().startAsync();
            } else { log.info("{} client stopped, aborting reconnect.", type.getName()); }
        }
    };

    private Service.Listener serviceListener = new Service.Listener() {
        @Override
        public void running() {
            // Check if connection is up as this event is fired even if there is no connection
            if (stratumClient != null && stratumClient.isConnected()) {
                log.info("{} client connected to {}", type.getName(), lastServerAddress);
                broadcastOnConnection();
                retrySeconds = 1;
            }
        }

        @Override
        public void terminated(Service.State from) {
            log.info("{} client stopped", type.getName());
            broadcastOnDisconnect();
            failedAddresses.add(lastServerAddress);
            lastServerAddress = null;
            stratumClient = null;
            // Try to restart
            if (!stopped) {
                retrySeconds = Math.min(retrySeconds * 2, MAX_WAIT);
                log.info("Reconnecting {} in {} seconds", type.getName(), retrySeconds);
                connectionExec.remove(reconnectTask);
                connectionExec.schedule(reconnectTask, retrySeconds, TimeUnit.SECONDS);
            }
        }
    };

    public ServerClient(CoinAddress coinAddress) {
        eventListeners = new CopyOnWriteArrayList<ListenerRegistration<ConnectionEventListener>>();
        failedAddresses = new HashSet<ServerAddress>();
        type = coinAddress.getType();
        addresses = ImmutableList.copyOf(coinAddress.getAddresses());

        createStratumClient();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                stopAsync();
            }
        });
    }

    private StratumClient createStratumClient() {
        checkState(stratumClient == null);
        lastServerAddress = getServerAddress();
        stratumClient = new StratumClient(lastServerAddress);
        stratumClient.addListener(serviceListener, Threading.USER_THREAD);
        return stratumClient;
    }

    private ServerAddress getServerAddress() {
        // If we blacklisted all servers, try to reset
        if (failedAddresses.size() == addresses.size()) failedAddresses.clear();

        ServerAddress address;
        // Not the most efficient, but does the job
        while (true) {
            address = addresses.get(RANDOM.nextInt(addresses.size()));
            if (!failedAddresses.contains(address)) break;
        }
        return address;
    }

    public void startAsync() {
        Service.State state = stratumClient.state();
        checkState(state == NEW, "Service %s is %s, cannot start it.", type.getName(), state);
        checkState(!stopped, "Service %s is stopped.", type.getName());
        try {
            stratumClient.startAsync();
        } catch (IllegalStateException e) {
            // This can happen if the service has already been started or stopped (e.g. by another
            // service or listener). Our contract says it is safe to call this method if
            // all services were NEW when it was called, and this has already been verified above, so we
            // don't propagate the exception.
            log.warn("Unable to start Service " + type.getName(), e);
        }
    }

    public void stopAsync() {
        stopped = true;
        connectionExec.remove(reconnectTask);
        if (stratumClient != null) {
            stratumClient.stopAsync();
            stratumClient = null;
        }
    }

    public boolean isConnected() {
        return stratumClient != null && stratumClient.isConnected();
    }


    /**
     * Adds an event listener object. Methods on this object are called when something interesting happens,
     * like new connection to a server. The listener is executed by the given executor.
     */
    public void addEventListener(ConnectionEventListener listener) {
        addEventListener(listener, Threading.USER_THREAD);
    }

    /**
     * Adds an event listener object. Methods on this object are called when something interesting happens,
     * like new connection to a server. The listener is executed by the given executor.
     */
    public void addEventListener(ConnectionEventListener listener, Executor executor) {
        eventListeners.add(new ListenerRegistration<ConnectionEventListener>(listener, executor));
    }

    /**
     * Removes the given event listener object. Returns true if the listener was removed, false if that listener
     * was never added.
     */
    public boolean removeEventListener(ConnectionEventListener listener) {
        return ListenerRegistration.removeFromList(listener, eventListeners);
    }

    private void broadcastOnConnection() {
        for (final ListenerRegistration<ConnectionEventListener> registration : eventListeners) {
            registration.executor.execute(new Runnable() {
                @Override
                public void run() {
                    registration.listener.onConnection(ServerClient.this);
                }
            });
        }
    }

    private void broadcastOnDisconnect() {
        for (final ListenerRegistration<ConnectionEventListener> registration : eventListeners) {
            registration.executor.execute(new Runnable() {
                @Override
                public void run() {
                    registration.listener.onDisconnect();
                }
            });
        }
    }

    @Override
    public void subscribeToAddresses(List<Address> addresses, final TransactionEventListener listener) {
        checkNotNull(stratumClient);

        CallMessage callMessage = new CallMessage("blockchain.address.subscribe", (List)null);

        // TODO use TransactionEventListener directly because the current solution leaks memory
        StratumClient.SubscribeResult addressHandler = new StratumClient.SubscribeResult() {
            @Override
            public void handle(CallMessage message) {
                try {
                    Address address = new Address(type, message.getParams().getString(0));
                    AddressStatus status;
                    if (message.getParams().isNull(1)) {
                        status = new AddressStatus(address, null);
                    }
                    else {
                        status = new AddressStatus(address, message.getParams().getString(1));
                    }
                    listener.onAddressStatusUpdate(status);
                } catch (AddressFormatException e) {
                    log.error("Address subscribe sent a malformed address", e);
                } catch (JSONException e) {
                    log.error("Unexpected JSON format", e);
                }
            }
        };

        for (final Address address : addresses) {
            log.info("Going to subscribe to {}", address);
            callMessage.setParam(address.toString());

            ListenableFuture<ResultMessage> reply = stratumClient.subscribe(callMessage, addressHandler);

            Futures.addCallback(reply, new FutureCallback<ResultMessage>() {

                @Override
                public void onSuccess(ResultMessage result) {
                    AddressStatus status = null;
                    try {
                        if (result.getResult().isNull(0)) {
                            status = new AddressStatus(address, null);
                        }
                        else {
                            status = new AddressStatus(address, result.getResult().getString(0));
                        }
                        listener.onAddressStatusUpdate(status);
                    } catch (JSONException e) {
                        log.error("Unexpected JSON format", e);
                    }
                }

                @Override
                public void onFailure(Throwable t) {
                    log.error("Could not get reply for address subscribe", t);
                }
            }, Threading.USER_THREAD);
        }
    }

    @Override
    public void getUnspentTx(final AddressStatus status, final TransactionEventListener listener) {
        checkNotNull(stratumClient);

        CallMessage message = new CallMessage("blockchain.address.listunspent",
                Arrays.asList(status.getAddress().toString()));
        final ListenableFuture<ResultMessage> result = stratumClient.call(message);

        Futures.addCallback(result, new FutureCallback<ResultMessage>() {

            @Override
            public void onSuccess(ResultMessage result) {
                JSONArray resTxs = result.getResult();
                ImmutableList.Builder<UnspentTx> utxes = ImmutableList.builder();
                try {
                    for (int i = 0; i < resTxs.length(); i++) {
                        utxes.add(new UnspentTx(resTxs.getJSONObject(i)));
                    }
                } catch (JSONException e) {
                    onFailure(e);
                    return;
                }
                listener.onUnspentTransactionUpdate(status, utxes.build());
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Could not get reply for blockchain.address.listunspent", t);
            }
        }, Threading.USER_THREAD);
    }

    @Override
    public void getHistoryTx(final AddressStatus status, final TransactionEventListener listener) {
        checkNotNull(stratumClient);

        CallMessage message = new CallMessage("blockchain.address.get_history",
                Arrays.asList(status.getAddress().toString()));
        final ListenableFuture<ResultMessage> result = stratumClient.call(message);

        Futures.addCallback(result, new FutureCallback<ResultMessage>() {

            @Override
            public void onSuccess(ResultMessage result) {
                JSONArray resTxs = result.getResult();
                ImmutableList.Builder<HistoryTx> historyTxs = ImmutableList.builder();
                try {
                    for (int i = 0; i < resTxs.length(); i++) {
                        historyTxs.add(new HistoryTx(resTxs.getJSONObject(i)));
                    }
                } catch (JSONException e) {
                    onFailure(e);
                    return;
                }
                listener.onTransactionHistory(status, historyTxs.build());
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Could not get reply for blockchain.address.get_history", t);
            }
        }, Threading.USER_THREAD);
    }

    @Override
    public void getTransaction(final Sha256Hash txHash, final TransactionEventListener listener) {
        checkNotNull(stratumClient);

        CallMessage message = new CallMessage("blockchain.transaction.get",
                Arrays.asList(txHash.toString()));
        final ListenableFuture<ResultMessage> result = stratumClient.call(message);

        Futures.addCallback(result, new FutureCallback<ResultMessage>() {

            @Override
            public void onSuccess(ResultMessage result) {
                try {
                    String rawTx = result.getResult().getString(0);
                    Transaction tx = new Transaction(type, Utils.HEX.decode(rawTx));
                    listener.onTransactionUpdate(tx);
                } catch (Exception e) {
                    onFailure(e);
                    return;
                }
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Could not get reply for blockchain.transaction.get", t);
            }
        }, Threading.USER_THREAD);
    }

    @Override
    public void broadcastTx(final Transaction tx, @Nullable final TransactionEventListener listener) {
        checkNotNull(stratumClient);

        CallMessage message = new CallMessage("blockchain.transaction.broadcast",
                Arrays.asList(Utils.HEX.encode(tx.bitcoinSerialize())));
        final ListenableFuture<ResultMessage> result = stratumClient.call(message);

        Futures.addCallback(result, new FutureCallback<ResultMessage>() {

            @Override
            public void onSuccess(ResultMessage result) {
                try {
                    String txId = result.getResult().getString(0);

                    // FIXME could crash due to transaction malleability
                    log.info("got tx {} =?= {}", txId, tx.getHash());
                    checkState(tx.getHash().toString().equals(txId));

                    if (listener != null) listener.onTransactionBroadcast(tx);
                } catch (JSONException e) {
                    onFailure(e);
                    return;
                }
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Could not get reply for blockchain.transaction.broadcast", t);
                if (listener != null) listener.onTransactionBroadcastError(tx, t);
            }
        }, Threading.USER_THREAD);
    }

    @Override
    public void ping() {
        checkNotNull(stratumClient);
        if (!stratumClient.isConnected()) {
            log.warn("There is no connection with {} server, skipping ping.", type.getName());
            return;
        }
        CallMessage pingMsg = new CallMessage("server.version", ImmutableList.of());
        ListenableFuture<ResultMessage> pong = stratumClient.call(pingMsg);
        Futures.addCallback(pong, new FutureCallback<ResultMessage>() {
            @Override
            public void onSuccess(@Nullable ResultMessage result) {
                try {
                    log.info("Server {} version {} OK", type.getName(),
                            result.getResult().get(0));
                } catch (JSONException ignore) { }
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Server {} ping failed", type.getName());
            }
        }, Threading.USER_THREAD);
    }

    public static class HistoryTx {
        protected Sha256Hash txHash;
        protected int height;

        public HistoryTx(JSONObject json) throws JSONException {
            txHash = new Sha256Hash(json.getString("tx_hash"));
            height = json.getInt("height");
        }

        public HistoryTx(TransactionOutPoint txop, int height) {
            this.txHash = txop.getHash();
            this.height = height;
        }

        public static List<? extends HistoryTx> fromArray(JSONArray jsonArray) throws JSONException {
            ImmutableList.Builder<HistoryTx> list = ImmutableList.builder();
            for (int i = 0; i < jsonArray.length(); i++) {
                list.add(new HistoryTx(jsonArray.getJSONObject(i)));
            }
            return list.build();
        }

        public Sha256Hash getTxHash() {
            return txHash;
        }

        public int getHeight() {
            return height;
        }
    }

    public static class UnspentTx extends HistoryTx {
        private int txPos;
        private long value;

        public UnspentTx(JSONObject json) throws JSONException {
            super(json);
            txPos = json.getInt("tx_pos");
            value = json.getLong("value");
        }

        public UnspentTx(TransactionOutPoint txop, long value, int height) {
            super(txop, height);
            this.txPos = (int) txop.getIndex();
            this.value = value;
        }

        public static List<? extends HistoryTx> fromArray(JSONArray jsonArray) throws JSONException {
            ImmutableList.Builder<UnspentTx> list = ImmutableList.builder();
            for (int i = 0; i < jsonArray.length(); i++) {
                list.add(new UnspentTx(jsonArray.getJSONObject(i)));
            }
            return list.build();
        }

        public Sha256Hash getTxHash() {
            return txHash;
        }

        public int getTxPos() {
            return txPos;
        }

        public long getValue() {
            return value;
        }

        public int getHeight() {
            return height;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            UnspentTx unspentTx = (UnspentTx) o;

            if (txPos != unspentTx.txPos) return false;
            if (value != unspentTx.value) return false;
            if (!txHash.equals(unspentTx.txHash)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = txHash.hashCode();
            result = 31 * result + txPos;
            result = 31 * result + (int) (value ^ (value >>> 32));
            return result;
        }
    }
}
