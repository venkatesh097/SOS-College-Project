package com.example.loracommunication.data;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.loracommunication.bluetooth.BluetoothService;
import com.example.loracommunication.location.PhoneLocationProvider;
import com.example.loracommunication.protocol.LoraPacket;
import com.example.loracommunication.protocol.LoraProtocol;
import com.example.loracommunication.protocol.PacketDeduplicator;
import com.example.loracommunication.protocol.ParsedLine;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Singleton repository managing node connection state, diagnostics,
 * fall detection state, packet event streams, and Room persistence.
 */
public class NodeRepository {

    public static final String PREFS_NAME = "lora_prefs";
    public static final String KEY_PHONE_GPS_FALLBACK = "pref_phone_gps_fallback";

    public interface WriteCallback {
        void onResult(boolean success, String error);
    }

    public interface AlertCallback {
        void onEmergencyAlert(Incident incident, String title, String body);
        void onNormalMessage(Message message, String title, String body);
    }

    private static volatile NodeRepository INSTANCE;

    private final MutableLiveData<ConnectionState> connectionState = new MutableLiveData<>(ConnectionState.NONE);
    private final MutableLiveData<String> deviceName = new MutableLiveData<>(null);
    private final MutableLiveData<NodeStatus> nodeStatus = new MutableLiveData<>(new NodeStatus("--", "--", "--"));
    private final MutableLiveData<FallStateModel> fallState = new MutableLiveData<>(FallStateModel.normal());
    private final MutableLiveData<String> lastError = new MutableLiveData<>(null);
    private final MutableLiveData<Event<LoraPacket>> incomingPackets = new MutableLiveData<>();
    private final MutableLiveData<Event<String>> pongEvents = new MutableLiveData<>();
    private final MutableLiveData<Event<Boolean>> dismissAlertEvent = new MutableLiveData<>();
    private final MutableLiveData<Long> lastHeardTime = new MutableLiveData<>(0L);

    private BluetoothService bluetoothService;
    private Context appContext;
    private MessageDao messageDao;
    private IncidentDao incidentDao;
    private PacketDeduplicator deduplicator = new PacketDeduplicator();
    private Executor dbExecutor = Executors.newSingleThreadExecutor();
    private final Executor fallbackExecutor = Executors.newSingleThreadExecutor();
    private Handler mainHandler;
    private AlertCallback alertCallback;

    private PhoneLocationProvider phoneLocationProvider;
    private long lastLocPushTimestamp = 0;
    private long gpsFixFirstSeenTimestamp = 0;
    private int consecutiveInvalidRxCount = 0;
    private volatile boolean nodeHasValidPhoneLoc = false;
    private volatile boolean locPushInProgress = false;
    private final Object locLatchLock = new Object();
    private CountDownLatch pendingLocLatch = null;

    private NodeRepository() {
        this.mainHandler = createMainHandler();
    }

    @VisibleForTesting
    public NodeRepository(MessageDao messageDao, IncidentDao incidentDao, PacketDeduplicator deduplicator, Executor dbExecutor) {
        this.messageDao = messageDao;
        this.incidentDao = incidentDao;
        this.deduplicator = deduplicator != null ? deduplicator : new PacketDeduplicator();
        this.dbExecutor = dbExecutor != null ? dbExecutor : (r -> r.run());
        this.mainHandler = createMainHandler();
    }

    private static Handler createMainHandler() {
        try {
            Looper looper = Looper.getMainLooper();
            if (looper != null) {
                return new Handler(looper);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void postToMain(Runnable r) {
        if (r == null) return;
        if (mainHandler != null) {
            mainHandler.post(r);
        } else {
            r.run();
        }
    }

    private <T> void postValueSafe(MutableLiveData<T> liveData, T value) {
        if (liveData == null) return;
        try {
            liveData.postValue(value);
        } catch (Throwable t) {
            try {
                liveData.setValue(value);
            } catch (Throwable ignored) {}
        }
    }

    public static NodeRepository getInstance() {
        if (INSTANCE == null) {
            synchronized (NodeRepository.class) {
                if (INSTANCE == null) {
                    INSTANCE = new NodeRepository();
                }
            }
        }
        return INSTANCE;
    }

    public void init(Context context) {
        if (context != null) {
            this.appContext = context.getApplicationContext();
            AppDatabase db = AppDatabase.getDatabase(this.appContext);
            this.messageDao = db.messageDao();
            this.incidentDao = db.incidentDao();
            if (this.phoneLocationProvider == null) {
                this.phoneLocationProvider = new PhoneLocationProvider(this.appContext);
            }
        }
    }

    public synchronized void registerService(BluetoothService service) {
        this.bluetoothService = service;
    }

    public synchronized void unregisterService(BluetoothService service) {
        if (this.bluetoothService == service) {
            this.bluetoothService = null;
        }
    }

    public synchronized BluetoothService getService() {
        return bluetoothService;
    }

    public void setAlertCallback(AlertCallback callback) {
        this.alertCallback = callback;
    }

    public boolean isPhoneGpsFallbackEnabled() {
        if (appContext == null) return true;
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_PHONE_GPS_FALLBACK, true);
    }

    public void setPhoneGpsFallbackEnabled(boolean enabled) {
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_PHONE_GPS_FALLBACK, enabled).apply();
        }
        if (!enabled && phoneLocationProvider != null) {
            phoneLocationProvider.stop();
        }
    }

    public PhoneLocationProvider getPhoneLocationProvider() {
        return phoneLocationProvider;
    }

    public boolean nodeHasValidPhoneLoc() {
        return nodeHasValidPhoneLoc;
    }

    // --- Observable LiveData ---

    public LiveData<ConnectionState> getConnectionState() {
        return connectionState;
    }

    public LiveData<String> getDeviceName() {
        return deviceName;
    }

    public LiveData<NodeStatus> getNodeStatus() {
        return nodeStatus;
    }

    public LiveData<FallStateModel> getFallState() {
        return fallState;
    }

    public LiveData<String> getLastError() {
        return lastError;
    }

    public LiveData<Event<LoraPacket>> getIncomingPackets() {
        return incomingPackets;
    }

    public LiveData<Event<String>> getPongEvents() {
        return pongEvents;
    }

    public LiveData<Event<Boolean>> getDismissAlertEvent() {
        return dismissAlertEvent;
    }

    public void postDismissAlert() {
        postValueSafe(dismissAlertEvent, new Event<>(true));
    }

    public LiveData<Long> getLastHeardTime() {
        return lastHeardTime;
    }

    // --- State Setters ---

    public void setConnectionState(ConnectionState state) {
        postValueSafe(connectionState, state);
        if (state != ConnectionState.CONNECTED) {
            if (phoneLocationProvider != null) {
                phoneLocationProvider.stop();
            }
            gpsFixFirstSeenTimestamp = 0;
            consecutiveInvalidRxCount = 0;
            nodeHasValidPhoneLoc = false;
        }
    }

    public void setDeviceName(String name) {
        postValueSafe(deviceName, name);
    }

    public void setNodeStatus(NodeStatus status) {
        postValueSafe(nodeStatus, status);
    }

    public void setFallState(FallStateModel state) {
        postValueSafe(fallState, state);
    }

    public void setLastError(String error) {
        postValueSafe(lastError, error);
    }

    public void postIncomingPacket(LoraPacket packet) {
        postValueSafe(incomingPackets, new Event<>(packet));
    }

    public void postPong(String node) {
        postValueSafe(pongEvents, new Event<>(node));
    }

    public void simulateLine(String rawLine) {
        if (rawLine == null) return;
        ParsedLine parsed = LoraProtocol.parseLine(rawLine);
        processParsedLine(parsed);
    }

    // --- Protocol Line Processing (Repository Handling) ---

    public void processParsedLine(ParsedLine parsed) {
        if (parsed == null) return;
        postValueSafe(lastHeardTime, System.currentTimeMillis());

        switch (parsed.getLineType()) {
            case READY:
                if (parsed.getNode() != null && !parsed.getNode().isEmpty()) {
                    setDeviceName(parsed.getNode());
                }
                break;

            case PONG:
                String pongNode = parsed.getNode();
                if (pongNode != null && !pongNode.isEmpty()) {
                    setDeviceName(pongNode);
                } else {
                    pongNode = deviceName.getValue();
                    if (pongNode == null || pongNode.isEmpty()) {
                        pongNode = "Node";
                    }
                }
                postPong(pongNode);
                break;

            case STATUS:
                if (parsed.getNode() != null && !parsed.getNode().isEmpty()) {
                    setDeviceName(parsed.getNode());
                }
                String locSrc = parsed.getLocSource();
                if (locSrc == null || locSrc.isEmpty()) {
                    locSrc = parsed.hasGpsFix() ? "GPS" : (nodeHasValidPhoneLoc ? "PHONE" : "NONE");
                }
                setNodeStatus(new NodeStatus(parsed.getMpuStatus(), parsed.getGpsStatus(), parsed.getLoraStatus(), locSrc));
                handleStatusForLocationFallback(parsed);
                break;

            case LOC_OK:
                if (parsed.getNode() != null && !parsed.getNode().isEmpty()) {
                    setDeviceName(parsed.getNode());
                }
                onLocOkReceived();
                break;

            case NEED_GPS:
                postToMain(() -> pushPhoneLocationToNode(true, null));
                break;

            case MSG_SENT:
                if (bluetoothService != null) {
                    bluetoothService.onCommandCompleted();
                }
                if (messageDao != null) {
                    dbExecutor.execute(() -> {
                        Message sending = messageDao.getLatestSendingMessage(Message.KIND_MSG);
                        if (sending != null) {
                            if (parsed.isSuccess()) {
                                messageDao.updateSendState(sending.getId(), Message.STATE_SENT_OVER_LORA);
                            } else {
                                messageDao.updateSendState(sending.getId(), Message.STATE_FAILED);
                            }
                        }
                    });
                }
                break;

            case SOS_SENDING:
                handleSosSending(parsed);
                break;

            case SOS_SENT:
                if (bluetoothService != null) {
                    bluetoothService.onCommandCompleted();
                }
                if (messageDao != null) {
                    dbExecutor.execute(() -> {
                        Message sending = messageDao.getLatestSendingMessage(Message.KIND_SOS);
                        if (sending == null) {
                            sending = messageDao.getLatestSendingMessage(Message.KIND_FALL);
                        }
                        if (sending != null) {
                            if (parsed.isSuccess()) {
                                messageDao.updateSendState(sending.getId(), Message.STATE_SENT_OVER_LORA);
                            } else {
                                messageDao.updateSendState(sending.getId(), Message.STATE_FAILED);
                            }
                        } else if (!parsed.isSuccess()) {
                            messageDao.markLatestSendingFailed();
                        }
                    });
                }
                break;

            case SOS_READ:
                final String readFrom = parsed.getNode();
                android.util.Log.i("NodeRepository", "SOS read by " + readFrom);
                postToMain(() -> {
                    if (appContext != null) {
                        android.widget.Toast.makeText(appContext, "SOS read by " + readFrom, android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
                break;

            case READ_SENT:
                android.util.Log.i("NodeRepository", "READ_SENT: " + parsed.getErrorReason());
                if (parsed.isSuccess()) {
                    if (incidentDao != null) {
                        dbExecutor.execute(() -> incidentDao.acknowledgeAll());
                    }
                    if (appContext != null) {
                        com.example.loracommunication.ui.EmergencyAlertManager.getInstance(appContext).acknowledgeAll(false);
                    }
                    postDismissAlert();
                } else {
                    postToMain(() -> {
                        if (appContext != null) {
                            android.widget.Toast.makeText(appContext, "Read confirmation not delivered, node alarm still active", android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                }
                break;

            case FALL_MONITORING:
                String fmNode = parsed.getNode();
                if (fmNode != null && !fmNode.isEmpty()) {
                    setDeviceName(fmNode);
                } else {
                    fmNode = deviceName.getValue();
                    if (fmNode == null) fmNode = "";
                }
                int dur = parsed.getFallDurationSec() > 0 ? parsed.getFallDurationSec() : 120;
                setFallState(FallStateModel.monitoring(System.currentTimeMillis(), fmNode, dur));
                break;

            case FALL_CANCELLED:
                String fcNode = parsed.getNode();
                if (fcNode != null && !fcNode.isEmpty()) {
                    setDeviceName(fcNode);
                } else {
                    fcNode = deviceName.getValue();
                    if (fcNode == null) fcNode = "";
                }
                setFallState(FallStateModel.cancelled(fcNode));
                break;

            case FALL_SOS:
                setFallState(FallStateModel.normal());
                break;

            case ERROR:
                boolean isReadBusy = (bluetoothService != null && bluetoothService.isReadPending());
                if (bluetoothService != null) {
                    bluetoothService.onCommandCompleted();
                }
                setLastError(parsed.getErrorReason());
                if (LoraProtocol.ERROR_BAD_LOC.equalsIgnoreCase(parsed.getErrorReason())) {
                    nodeHasValidPhoneLoc = false;
                    releaseLocLatch();
                }
                if (!isReadBusy && messageDao != null) {
                    dbExecutor.execute(() -> messageDao.markLatestSendingFailed());
                }
                break;

            case TX:
                handleTxLine(parsed);
                break;

            case RX:
                handleRxLine(parsed);
                break;

            case SIG:
            case UNKNOWN:
            default:
                break;
        }
    }

    private void handleSosSending(ParsedLine parsed) {
        final String reason = parsed.getErrorReason();
        final String kind = Message.KIND_FALL.equalsIgnoreCase(reason) ? Message.KIND_FALL : Message.KIND_SOS;
        final Double lat = parsed.hasGpsFix() ? parsed.getPacketLatitude() : null;
        final Double lon = parsed.hasGpsFix() ? parsed.getPacketLongitude() : null;
        final String myNode = (deviceName.getValue() != null && !deviceName.getValue().isEmpty()) ? deviceName.getValue() : "Me";

        if (messageDao != null) {
            dbExecutor.execute(() -> {
                Message sending = messageDao.getLatestSendingMessage(kind);
                if (sending == null && Message.KIND_FALL.equalsIgnoreCase(kind)) {
                    sending = messageDao.getLatestSendingMessage(Message.KIND_SOS);
                } else if (sending == null && Message.KIND_SOS.equalsIgnoreCase(kind)) {
                    sending = messageDao.getLatestSendingMessage(Message.KIND_FALL);
                }

                if (sending != null) {
                    messageDao.updateSendStateAndDetails(sending.getId(), Message.STATE_SENDING, sending.getPacketId(), lat, lon);
                } else {
                    String content = Message.KIND_FALL.equalsIgnoreCase(kind)
                            ? "Automatic Fall SOS (Inactivity)"
                            : "Emergency SOS";
                    Message outgoing = new Message(0, myNode, null, Message.DIRECTION_SENT,
                            kind, content, System.currentTimeMillis(), lat, lon, Message.STATE_SENDING);
                    messageDao.insert(outgoing);
                }
            });
        }
    }

    private void handleTxLine(ParsedLine parsed) {
        LoraPacket packet = parsed.getPacket();
        if (packet == null) return;

        if (packet.getNode() != null && !packet.getNode().isEmpty()) {
            setDeviceName(packet.getNode());
        }

        final Long pid = parsePacketId(packet.getId());
        final String kind = packet.getType().name();
        final Double lat = packet.hasGpsFix() ? packet.getLatitude() : null;
        final Double lon = packet.hasGpsFix() ? packet.getLongitude() : null;

        if (messageDao != null) {
            dbExecutor.execute(() -> {
                Message sending = messageDao.getLatestSendingMessage(kind);
                if (sending != null) {
                    messageDao.updateSendStateAndDetails(sending.getId(), Message.STATE_SENT_OVER_LORA, pid, lat, lon);
                } else {
                    // Own packet transmitted (e.g. from physical hardware button)
                    String content = packet.getType() == LoraPacket.Type.MSG
                            ? packet.getText()
                            : (packet.getType() == LoraPacket.Type.FALL ? "Automatic Fall SOS (Inactivity)" : "Emergency SOS");
                    Message sentMsg = new Message(0, packet.getNode(), pid, Message.DIRECTION_SENT,
                            kind, content, System.currentTimeMillis(), lat, lon, Message.STATE_SENT_OVER_LORA);
                    messageDao.insert(sentMsg);
                }
            });
        }

        // Post packet event
        postIncomingPacket(packet);
        // CRITICAL: TX lines are the node's echo of its OWN transmission and must NEVER trigger an alarm or incident.
    }

    private void handleRxLine(ParsedLine parsed) {
        LoraPacket packet = parsed.getPacket();
        if (packet == null) return;

        // Fallback strategy condition (b): track consecutive INVALID RX packets
        if (!packet.hasGpsFix()) {
            consecutiveInvalidRxCount++;
            if (consecutiveInvalidRxCount >= 2 && isPhoneGpsFallbackEnabled() && connectionState.getValue() == ConnectionState.CONNECTED) {
                if (phoneLocationProvider != null && !phoneLocationProvider.isRunning()) {
                    phoneLocationProvider.start();
                }
                if (System.currentTimeMillis() - lastLocPushTimestamp >= 30000L) {
                    pushPhoneLocationToNode(false, null);
                }
            }
        } else {
            consecutiveInvalidRxCount = 0;
        }

        // De-duplicate on (node, id, type)
        if (!deduplicator.trackIfNew(packet)) {
            return;
        }

        final Long pid = parsePacketId(packet.getId());
        final Double lat = packet.hasGpsFix() ? packet.getLatitude() : null;
        final Double lon = packet.hasGpsFix() ? packet.getLongitude() : null;
        final long now = System.currentTimeMillis();

        if (packet.getType() == LoraPacket.Type.MSG) {
            final Message rxMsg = new Message(0, packet.getNode(), pid, Message.DIRECTION_RECEIVED,
                    Message.KIND_MSG, packet.getText(), now, lat, lon, Message.STATE_SENT_OVER_LORA);

            if (messageDao != null) {
                dbExecutor.execute(() -> messageDao.insert(rxMsg));
            }

            if (alertCallback != null) {
                alertCallback.onNormalMessage(rxMsg, packet.getNode(), packet.getText());
            }
        } else if (packet.getType() == LoraPacket.Type.SOS || packet.getType() == LoraPacket.Type.FALL) {
            final String kind = packet.getType().name();
            final String content = packet.getType() == LoraPacket.Type.FALL
                    ? "Automatic Fall SOS (Inactivity)"
                    : "Emergency SOS";

            final Incident incident = new Incident(0, kind, packet.getNode(), pid, lat, lon, now, false);
            final Message histMsg = new Message(0, packet.getNode(), pid, Message.DIRECTION_RECEIVED,
                    kind, content, now, lat, lon, Message.STATE_SENT_OVER_LORA);

            if (incidentDao != null) {
                dbExecutor.execute(() -> {
                    long id = incidentDao.insert(incident);
                    if (id > 0) {
                        incident.setId(id);
                    }
                    if (messageDao != null) {
                        messageDao.insert(histMsg);
                    }
                    // Post call to alertCallback on main thread ONLY after database insert completed with valid ID
                    if (incident.getId() > 0 && alertCallback != null) {
                        final String title = packet.getType() == LoraPacket.Type.FALL ? "FALL DETECTED" : "EMERGENCY SOS";
                        final String body = packet.getNode() + ": " + content;
                        postToMain(() -> {
                            if (alertCallback != null) {
                                alertCallback.onEmergencyAlert(incident, title, body);
                            }
                        });
                    }
                });
            }
        }

        postIncomingPacket(packet);
    }

    private Long parsePacketId(String idStr) {
        if (idStr == null || idStr.trim().isEmpty()) return null;
        try {
            return Long.parseLong(idStr.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- Action Methods ---

    public void connect(BluetoothDevice device) {
        ensureServiceStarted();
        if (bluetoothService != null) {
            bluetoothService.connect(device);
        }
    }

    public void disconnect() {
        if (bluetoothService != null) {
            bluetoothService.disconnect(true);
        }
    }

    public void sendMsg(String text, WriteCallback callback) {
        prepareLocationFallbackBeforeSending(() -> sendMsgInternal(text, callback));
    }

    private void sendMsgInternal(String text, WriteCallback callback) {
        long timestamp = System.currentTimeMillis();
        String node = deviceName.getValue();
        if (node == null || node.trim().isEmpty()) node = "Me";
        final Message sendingMsg = new Message(0, node, null, Message.DIRECTION_SENT,
                Message.KIND_MSG, text, timestamp, null, null, Message.STATE_SENDING);

        if (messageDao != null) {
            dbExecutor.execute(() -> {
                long id = messageDao.insert(sendingMsg);
                mainHandler.post(() -> {
                    if (bluetoothService != null) {
                        bluetoothService.sendMsg(text, (success, error) -> {
                            if (!success && messageDao != null) {
                                dbExecutor.execute(() -> messageDao.updateSendState(id, Message.STATE_FAILED));
                            }
                            if (callback != null) callback.onResult(success, error);
                        });
                    } else {
                        if (messageDao != null) {
                            dbExecutor.execute(() -> messageDao.updateSendState(id, Message.STATE_FAILED));
                        }
                        if (callback != null) callback.onResult(false, "Bluetooth service not available");
                    }
                });
            });
        } else if (bluetoothService != null) {
            bluetoothService.sendMsg(text, callback);
        } else if (callback != null) {
            callback.onResult(false, "Bluetooth service not available");
        }
    }

    public void sendSos(WriteCallback callback) {
        // Trigger asynchronous location refresh if needed, but NEVER block emergency SOS
        if (isPhoneGpsFallbackEnabled() &&
                connectionState.getValue() == ConnectionState.CONNECTED &&
                phoneLocationProvider != null &&
                phoneLocationProvider.hasLocationPermission() &&
                phoneLocationProvider.isLocationServicesEnabled()) {
            NodeStatus status = nodeStatus.getValue();
            if (status == null || !status.hasGpsFix()) {
                phoneLocationProvider.setHighAccuracy(true);
                pushPhoneLocationToNode(true, null);
            }
        }
        sendSosInternal(callback);
    }

    private void sendSosInternal(WriteCallback callback) {
        long timestamp = System.currentTimeMillis();
        String node = deviceName.getValue();
        if (node == null || node.trim().isEmpty()) node = "Me";
        final Message sendingMsg = new Message(0, node, null, Message.DIRECTION_SENT,
                Message.KIND_SOS, "Emergency SOS", timestamp, null, null, Message.STATE_SENDING);

        if (bluetoothService != null) {
            bluetoothService.sendSos((success, error) -> {
                if (messageDao != null) {
                    dbExecutor.execute(() -> {
                        if (success) {
                            messageDao.insert(sendingMsg);
                        } else {
                            sendingMsg.setSendState(Message.STATE_FAILED);
                            messageDao.insert(sendingMsg);
                        }
                    });
                }
                if (callback != null) callback.onResult(success, error);
            });
        } else {
            if (messageDao != null) {
                dbExecutor.execute(() -> {
                    sendingMsg.setSendState(Message.STATE_FAILED);
                    messageDao.insert(sendingMsg);
                });
            }
            if (callback != null) {
                callback.onResult(false, "Bluetooth service not available");
            }
        }
    }

    // --- Location Fallback Controller ---

    private void handleStatusForLocationFallback(ParsedLine parsed) {
        if (parsed == null) return;
        if (connectionState.getValue() != ConnectionState.CONNECTED) return;
        if (!isPhoneGpsFallbackEnabled()) {
            if (phoneLocationProvider != null) {
                phoneLocationProvider.stop();
            }
            return;
        }

        if (parsed.hasGpsFix()) {
            if (gpsFixFirstSeenTimestamp == 0) {
                gpsFixFirstSeenTimestamp = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - gpsFixFirstSeenTimestamp >= 10000L) {
                // Sustained GPS fix for >= 10s: stop updates to save battery
                if (phoneLocationProvider != null) {
                    phoneLocationProvider.stop();
                }
                nodeHasValidPhoneLoc = false;
            }
        } else {
            // GPS is NO_FIX
            gpsFixFirstSeenTimestamp = 0;
            if (phoneLocationProvider != null) {
                if (!phoneLocationProvider.isRunning()) {
                    phoneLocationProvider.start();
                }
                phoneLocationProvider.setHighAccuracy(phoneLocationProvider.isLocationStale());
            }

            // Push at most once every 30s
            if (System.currentTimeMillis() - lastLocPushTimestamp >= 30000L) {
                pushPhoneLocationToNode(false, null);
            }
        }
    }

    private void onLocOkReceived() {
        nodeHasValidPhoneLoc = true;
        releaseLocLatch();
        NodeStatus current = nodeStatus.getValue();
        if (current != null && !current.hasGpsFix()) {
            setNodeStatus(new NodeStatus(current.getMpu(), current.getGps(), current.getLora(), "PHONE"));
        }
    }

    private void releaseLocLatch() {
        synchronized (locLatchLock) {
            if (pendingLocLatch != null) {
                pendingLocLatch.countDown();
            }
        }
    }

    public void pushLocationNow(WriteCallback callback) {
        pushPhoneLocationToNode(true, callback);
    }

    public void pushPhoneLocationToNode(boolean immediate, WriteCallback callback) {
        if (connectionState.getValue() != ConnectionState.CONNECTED || bluetoothService == null) {
            releaseLocLatch();
            if (callback != null) callback.onResult(false, "Not connected to LoRa node");
            return;
        }

        if (!isPhoneGpsFallbackEnabled()) {
            releaseLocLatch();
            if (callback != null) callback.onResult(false, "Phone GPS fallback disabled in settings");
            return;
        }

        if (phoneLocationProvider == null || !phoneLocationProvider.hasLocationPermission()) {
            releaseLocLatch();
            if (callback != null) callback.onResult(false, "Location permission not granted");
            return;
        }

        if (!phoneLocationProvider.isLocationServicesEnabled()) {
            releaseLocLatch();
            if (callback != null) callback.onResult(false, "Location services disabled on device");
            return;
        }

        long now = System.currentTimeMillis();
        if (!immediate && (now - lastLocPushTimestamp < 30000L)) {
            releaseLocLatch();
            if (callback != null) callback.onResult(true, "Throttled (recently pushed)");
            return;
        }

        if (locPushInProgress) {
            releaseLocLatch();
            if (callback != null) callback.onResult(false, "Location push already in progress");
            return;
        }

        locPushInProgress = true;
        phoneLocationProvider.getFreshLocation(location -> {
            if (location == null) {
                locPushInProgress = false;
                releaseLocLatch();
                if (callback != null) callback.onResult(false, "No valid location acquired");
                return;
            }

            long age = System.currentTimeMillis() - location.getTime();
            if (age > PhoneLocationProvider.EXPIRED_THRESHOLD_MS) {
                locPushInProgress = false;
                releaseLocLatch();
                if (callback != null) callback.onResult(false, "Location coordinates older than 5 minutes");
                return;
            }

            double lat = location.getLatitude();
            double lon = location.getLongitude();
            if (Double.isNaN(lat) || Double.isNaN(lon) || (Math.abs(lat) < 0.000001 && Math.abs(lon) < 0.000001)) {
                locPushInProgress = false;
                releaseLocLatch();
                if (callback != null) callback.onResult(false, "Invalid coordinates (0.0, 0.0)");
                return;
            }

            lastLocPushTimestamp = System.currentTimeMillis();
            if (bluetoothService != null) {
                bluetoothService.sendLoc(lat, lon, (success, error) -> {
                    locPushInProgress = false;
                    if (success) {
                        onLocOkReceived();
                    } else {
                        releaseLocLatch();
                    }
                    if (callback != null) callback.onResult(success, error);
                });
            } else {
                locPushInProgress = false;
                releaseLocLatch();
                if (callback != null) callback.onResult(false, "Bluetooth service unavailable");
            }
        });
    }

    private void prepareLocationFallbackBeforeSending(Runnable onReadyToSend) {
        NodeStatus status = nodeStatus.getValue();
        boolean needsFallback = (status == null || !status.hasGpsFix());
        boolean canFallback = isPhoneGpsFallbackEnabled() &&
                connectionState.getValue() == ConnectionState.CONNECTED &&
                phoneLocationProvider != null &&
                phoneLocationProvider.hasLocationPermission() &&
                phoneLocationProvider.isLocationServicesEnabled();

        if (!needsFallback || !canFallback) {
            onReadyToSend.run();
            return;
        }

        fallbackExecutor.execute(() -> {
            try {
                CountDownLatch latch;
                synchronized (locLatchLock) {
                    latch = new CountDownLatch(1);
                    pendingLocLatch = latch;
                }

                postToMain(() -> {
                    if (phoneLocationProvider != null) {
                        phoneLocationProvider.setHighAccuracy(true);
                    }
                    pushPhoneLocationToNode(true, null);
                });

                // Wait up to 1 second for LOC_OK
                try {
                    latch.await(1000L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                }
            } finally {
                synchronized (locLatchLock) {
                    pendingLocLatch = null;
                }
                postToMain(onReadyToSend);
            }
        });
    }

    public void retryMessage(Message message, WriteCallback callback) {
        if (message == null) return;
        if (messageDao != null) {
            dbExecutor.execute(() -> {
                messageDao.updateSendState(message.getId(), Message.STATE_SENDING);
                mainHandler.post(() -> {
                    if (Message.KIND_SOS.equalsIgnoreCase(message.getKind())) {
                        if (bluetoothService != null) {
                            bluetoothService.sendSos((success, error) -> {
                                if (!success && messageDao != null) {
                                    dbExecutor.execute(() -> messageDao.updateSendState(message.getId(), Message.STATE_FAILED));
                                }
                                if (callback != null) callback.onResult(success, error);
                            });
                        } else if (callback != null) {
                            if (messageDao != null) {
                                dbExecutor.execute(() -> messageDao.updateSendState(message.getId(), Message.STATE_FAILED));
                            }
                            callback.onResult(false, "Bluetooth service not available");
                        }
                    } else {
                        if (bluetoothService != null) {
                            bluetoothService.sendMsg(message.getContent(), (success, error) -> {
                                if (!success && messageDao != null) {
                                    dbExecutor.execute(() -> messageDao.updateSendState(message.getId(), Message.STATE_FAILED));
                                }
                                if (callback != null) callback.onResult(success, error);
                            });
                        } else if (callback != null) {
                            if (messageDao != null) {
                                dbExecutor.execute(() -> messageDao.updateSendState(message.getId(), Message.STATE_FAILED));
                            }
                            callback.onResult(false, "Bluetooth service not available");
                        }
                    }
                });
            });
        }
    }

    public void sendStatus(WriteCallback callback) {
        if (callback != null) {
            callback.onResult(true, null);
        }
    }

    public void sendPing(WriteCallback callback) {
        if (bluetoothService != null) {
            bluetoothService.sendPing(callback);
        } else if (callback != null) {
            callback.onResult(false, "Bluetooth service not available");
        }
    }

    public void sendRead(WriteCallback callback) {
        if (connectionState.getValue() != ConnectionState.CONNECTED || bluetoothService == null) {
            if (callback != null) {
                callback.onResult(true, null);
            }
            return;
        }
        bluetoothService.sendRead(callback);
    }

    public void ensureServiceStarted() {
        if (appContext != null) {
            Intent serviceIntent = new Intent(appContext, BluetoothService.class);
            ContextCompat.startForegroundService(appContext, serviceIntent);
        }
    }
}
