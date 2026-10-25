package com.example.loracommunication.bluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.loracommunication.R;
import com.example.loracommunication.data.AppDatabase;
import com.example.loracommunication.data.ConnectionState;
import com.example.loracommunication.data.FallStateModel;
import com.example.loracommunication.data.Message;
import com.example.loracommunication.data.NodeRepository;
import com.example.loracommunication.data.NodeStatus;
import com.example.loracommunication.protocol.LoraPacket;
import com.example.loracommunication.protocol.LoraProtocol;
import com.example.loracommunication.protocol.PacketDeduplicator;
import com.example.loracommunication.protocol.ParsedLine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BluetoothService extends Service {
    private static final String TAG = "BluetoothService";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // Standard SPP UUID
    private static final int FOREGROUND_NOTIFICATION_ID = 1;

    public static final String ACTION_DISCONNECT = "com.example.loracommunication.ACTION_DISCONNECT";

    // Timing constants
    private static final long INITIAL_RECONNECT_BACKOFF_MS = 2000L; // 2s
    private static final long MAX_RECONNECT_BACKOFF_MS = 30000L;    // 30s
    private static final long PING_INTERVAL_MS = 15000L;             // 15s heartbeat
    private static final long DEAD_LINK_TIMEOUT_MS = 45000L;         // 45s dead link detection

    public enum State {
        NONE, CONNECTING, CONNECTED
    }

    private State currentState = State.NONE;
    private String connectedDeviceName = null;
    private String reportedNodeName = null;
    private BluetoothDevice lastTargetDevice = null;
    private boolean isUserInitiatedDisconnect = false;
    private long reconnectBackoffMs = INITIAL_RECONNECT_BACKOFF_MS;
    private volatile long lastLineReceivedTimestamp = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());

    private final IBinder binder = new LocalBinder();
    private BluetoothAdapter bluetoothAdapter;
    private ConnectThread connectThread;
    private ConnectedThread connectedThread;
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();

    private final Runnable reconnectRunnable = this::attemptAutoReconnect;
    private final Runnable watchdogRunnable = this::runWatchdogCheck;

    private final BroadcastReceiver btAdapterReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                    Log.w(TAG, "Bluetooth turned off mid-session");
                    handleBluetoothDisabled();
                } else if (state == BluetoothAdapter.STATE_ON) {
                    Log.i(TAG, "Bluetooth turned on");
                    if (lastTargetDevice != null && !isUserInitiatedDisconnect) {
                        scheduleReconnect();
                    }
                }
            }
        }
    };

    public class LocalBinder extends Binder {
        public BluetoothService getService() {
            return BluetoothService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NodeRepository repo = NodeRepository.getInstance();
        repo.init(this);
        repo.registerService(this);
        final com.example.loracommunication.ui.EmergencyAlertManager alertManager =
                com.example.loracommunication.ui.EmergencyAlertManager.getInstance(this);
        repo.setAlertCallback(new NodeRepository.AlertCallback() {
            @Override
            public void onEmergencyAlert(com.example.loracommunication.data.Incident incident, String title, String body) {
                alertManager.triggerEmergencyAlert(incident);
            }

            @Override
            public void onNormalMessage(com.example.loracommunication.data.Message message, String title, String body) {
                alertManager.showNormalMessageNotification(message, title, body);
            }
        });

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }
        createNotificationChannel();

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(btAdapterReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            disconnect(true);
            return START_NOT_STICKY;
        }

        Notification notification = buildForegroundNotification(getNotificationContent(), currentState == State.CONNECTED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    serviceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                }
            }
            startForeground(FOREGROUND_NOTIFICATION_ID, notification, serviceType);
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification);
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public State getState() {
        return currentState;
    }

    public String getConnectedDeviceName() {
        return reportedNodeName != null ? reportedNodeName : (connectedDeviceName != null ? connectedDeviceName : "Node");
    }

    private synchronized void setState(State state, String deviceName) {
        currentState = state;
        connectedDeviceName = deviceName;

        ConnectionState repoState;
        switch (state) {
            case CONNECTED:
                repoState = ConnectionState.CONNECTED;
                break;
            case CONNECTING:
                repoState = ConnectionState.CONNECTING;
                break;
            case NONE:
            default:
                repoState = ConnectionState.NONE;
                break;
        }

        NodeRepository repo = NodeRepository.getInstance();
        repo.setConnectionState(repoState);
        repo.setDeviceName(getConnectedDeviceName());

        if (state != State.CONNECTED) {
            reportedNodeName = null;
            stopWatchdog();
        }

        updateForegroundNotification(getNotificationContent(), state == State.CONNECTED);
    }

    private String getNotificationContent() {
        switch (currentState) {
            case CONNECTED:
                return "Connected to " + getConnectedDeviceName();
            case CONNECTING:
                return "Connecting to " + (connectedDeviceName != null ? connectedDeviceName : "device") + "...";
            case NONE:
            default:
                return "Disconnected";
        }
    }

    public synchronized void connect(BluetoothDevice device) {
        isUserInitiatedDisconnect = false;
        reconnectBackoffMs = INITIAL_RECONNECT_BACKOFF_MS;
        reconnectHandler.removeCallbacks(reconnectRunnable);
        lastTargetDevice = device;
        connectInternal(device, false);
    }

    private synchronized void connectInternal(BluetoothDevice device, boolean isReconnectAttempt) {
        if (device == null) {
            return;
        }

        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        String displayName = getDeviceDisplayName(device);
        setState(State.CONNECTING, displayName);

        connectThread = new ConnectThread(device);
        connectThread.start();
    }

    @SuppressLint("MissingPermission")
    private synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
        if (connectThread != null) {
            connectThread = null;
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        connectedThread = new ConnectedThread(socket);
        connectedThread.start();

        // Connection successful: reset backoff and user disconnect flag
        isUserInitiatedDisconnect = false;
        reconnectBackoffMs = INITIAL_RECONNECT_BACKOFF_MS;
        reconnectHandler.removeCallbacks(reconnectRunnable);

        String displayName = getDeviceDisplayName(device);
        setState(State.CONNECTED, displayName);

        // Reset watchdog & heartbeat
        lastLineReceivedTimestamp = System.currentTimeMillis();
        startWatchdog();
    }

    public synchronized void disconnect(boolean userInitiated) {
        isUserInitiatedDisconnect = userInitiated;
        reconnectHandler.removeCallbacks(reconnectRunnable);
        stopWatchdog();
        commandTimeoutHandler.removeCallbacks(commandTimeoutRunnable);
        readRetryHandler.removeCallbacksAndMessages(null);
        sosRetryHandler.removeCallbacksAndMessages(null);
        isMsgOrSosPending = false;
        isReadPending = false;
        isSosInFlight = false;
        readRetryCount = 0;
        sosRetryCount = 0;
        pendingCommandQueue.clear();

        if (userInitiated) {
            reconnectBackoffMs = INITIAL_RECONNECT_BACKOFF_MS;
            lastTargetDevice = null;
        }

        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        setState(State.NONE, null);
    }

    private synchronized void connectionFailed() {
        commandTimeoutHandler.removeCallbacks(commandTimeoutRunnable);
        readRetryHandler.removeCallbacksAndMessages(null);
        sosRetryHandler.removeCallbacksAndMessages(null);
        isMsgOrSosPending = false;
        isReadPending = false;
        isSosInFlight = false;
        readRetryCount = 0;
        sosRetryCount = 0;
        pendingCommandQueue.clear();
        setState(State.NONE, null);
        if (!isUserInitiatedDisconnect) {
            scheduleReconnect();
        }
    }

    private synchronized void connectionLost() {
        commandTimeoutHandler.removeCallbacks(commandTimeoutRunnable);
        readRetryHandler.removeCallbacksAndMessages(null);
        sosRetryHandler.removeCallbacksAndMessages(null);
        isMsgOrSosPending = false;
        isReadPending = false;
        isSosInFlight = false;
        readRetryCount = 0;
        sosRetryCount = 0;
        pendingCommandQueue.clear();
        setState(State.NONE, null);
        if (!isUserInitiatedDisconnect) {
            scheduleReconnect();
        }
    }

    private synchronized void scheduleReconnect() {
        if (isUserInitiatedDisconnect || lastTargetDevice == null) {
            return;
        }
        reconnectHandler.removeCallbacks(reconnectRunnable);
        long delay = reconnectBackoffMs;
        Log.d(TAG, "Scheduling auto-reconnect to " + lastTargetDevice.getAddress() + " in " + delay + " ms");
        reconnectBackoffMs = Math.min(reconnectBackoffMs * 2, MAX_RECONNECT_BACKOFF_MS);
        reconnectHandler.postDelayed(reconnectRunnable, delay);
    }

    private synchronized void attemptAutoReconnect() {
        if (isUserInitiatedDisconnect || lastTargetDevice == null) {
            return;
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            scheduleReconnect();
            return;
        }
        if (currentState == State.CONNECTED || currentState == State.CONNECTING) {
            return;
        }
        Log.i(TAG, "Attempting auto-reconnect to " + lastTargetDevice.getAddress());
        connectInternal(lastTargetDevice, true);
    }

    private void handleBluetoothDisabled() {
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
        stopWatchdog();
        setState(State.NONE, null);
    }

    private void startWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable);
        watchdogHandler.postDelayed(watchdogRunnable, PING_INTERVAL_MS);
    }

    private void stopWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable);
    }

    private static class QueuedCommand {
        final String cmd;
        final NodeRepository.WriteCallback callback;

        QueuedCommand(String cmd, NodeRepository.WriteCallback callback) {
            this.cmd = cmd;
            this.callback = callback;
        }
    }

    private static final long COMMAND_TIMEOUT_MS = 2500L;
    private final java.util.Queue<QueuedCommand> pendingCommandQueue = new java.util.LinkedList<>();
    private volatile boolean isMsgOrSosPending = false;
    private volatile boolean isReadPending = false;
    private volatile boolean isSosInFlight = false;
    private int readRetryCount = 0;
    private int sosRetryCount = 0;
    private final Handler readRetryHandler = new Handler(Looper.getMainLooper());
    private final Handler sosRetryHandler = new Handler(Looper.getMainLooper());
    private final Handler commandTimeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable commandTimeoutRunnable = () -> {
        synchronized (BluetoothService.this) {
            if (isMsgOrSosPending || isReadPending || isSosInFlight) {
                Log.w(TAG, "Pending command timed out waiting for node response");
                isReadPending = false;
                isSosInFlight = false;
                onCommandCompletedInternal();
            }
        }
    };

    public boolean isCommandPending() {
        return isMsgOrSosPending;
    }

    public boolean isReadPending() {
        return isReadPending;
    }

    public synchronized void onCommandCompleted() {
        commandTimeoutHandler.removeCallbacks(commandTimeoutRunnable);
        onCommandCompletedInternal();
    }

    private synchronized void onCommandCompletedInternal() {
        isMsgOrSosPending = false;
        isSosInFlight = false;
        if (!pendingCommandQueue.isEmpty()) {
            QueuedCommand next = pendingCommandQueue.poll();
            if (next != null) {
                if ("READ".equals(next.cmd)) {
                    sendReadInternal(next.callback);
                } else {
                    isMsgOrSosPending = true;
                    write((next.cmd + "\n").getBytes(StandardCharsets.UTF_8), next.callback);
                    commandTimeoutHandler.removeCallbacks(commandTimeoutRunnable);
                    commandTimeoutHandler.postDelayed(commandTimeoutRunnable, COMMAND_TIMEOUT_MS);
                }
            }
        }
    }

    private synchronized void enqueueOrSendCommand(String cmd, NodeRepository.WriteCallback callback) {
        if (!isMsgOrSosPending) {
            isMsgOrSosPending = true;
            write((cmd + "\n").getBytes(StandardCharsets.UTF_8), callback);
            commandTimeoutHandler.removeCallbacks(commandTimeoutRunnable);
            commandTimeoutHandler.postDelayed(commandTimeoutRunnable, COMMAND_TIMEOUT_MS);
        } else {
            pendingCommandQueue.add(new QueuedCommand(cmd, callback));
        }
    }

    private void runWatchdogCheck() {
        if (currentState != State.CONNECTED) {
            return;
        }

        long now = System.currentTimeMillis();
        long elapsed = now - lastLineReceivedTimestamp;

        if (elapsed >= DEAD_LINK_TIMEOUT_MS) {
            Log.w(TAG, "No line received within " + elapsed + " ms. Dead link detected, reconnecting.");
            if (connectedThread != null) {
                connectedThread.cancel();
                connectedThread = null;
            }
            connectionLost();
            return;
        }

        // The node pushes STATUS every 5 seconds. Only send PING if quiet for >= 15 seconds,
        // and do NOT send PING while a MSG or SOS is waiting for its MSG_SENT / SOS_SENT result.
        if (elapsed >= PING_INTERVAL_MS && !isMsgOrSosPending) {
            sendPing(null);
        }
        watchdogHandler.postDelayed(watchdogRunnable, PING_INTERVAL_MS);
    }

    // --- Write Path ---

    public void sendMsg(String text, NodeRepository.WriteCallback callback) {
        try {
            String cmd = LoraProtocol.buildMsgCommand(text);
            enqueueOrSendCommand(cmd, callback);
        } catch (IllegalArgumentException e) {
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(false, e.getMessage()));
            }
        }
    }

    public synchronized void sendSos(NodeRepository.WriteCallback callback) {
        if (currentState != State.CONNECTED || connectedThread == null) {
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(false, "Not connected to LoRa node"));
            }
            return;
        }

        // Emergency SOS takes immediate priority over non-critical queues and pending retries.
        readRetryHandler.removeCallbacksAndMessages(null);
        commandTimeoutHandler.removeCallbacks(commandTimeoutRunnable);

        isReadPending = false;
        isMsgOrSosPending = true;
        isSosInFlight = true;

        String cmd = LoraProtocol.buildSosCommand();
        write((cmd + "\n").getBytes(StandardCharsets.UTF_8), callback);

        commandTimeoutHandler.postDelayed(commandTimeoutRunnable, COMMAND_TIMEOUT_MS);
    }

    public void sendStatus(NodeRepository.WriteCallback callback) {
        // Node pushes STATUS by itself every 5 s; stop sending STATUS.
        if (callback != null) {
            mainHandler.post(() -> callback.onResult(true, null));
        }
    }

    public void sendPing(NodeRepository.WriteCallback callback) {
        if (isMsgOrSosPending) {
            Log.d(TAG, "Skipping PING while MSG or SOS is pending");
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(false, "Command in progress"));
            }
            return;
        }
        String cmd = LoraProtocol.buildPingCommand();
        write((cmd + "\n").getBytes(StandardCharsets.UTF_8), callback);
    }

    public synchronized void sendRead(NodeRepository.WriteCallback callback) {
        if (currentState != State.CONNECTED || connectedThread == null) {
            // Disconnected: silently skip transmission without error
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(true, null));
            }
            return;
        }

        // If a MSG or SOS transmission is currently awaiting its result line over Bluetooth,
        // queue or defer the READ command until that pending result is received.
        if (isMsgOrSosPending) {
            pendingCommandQueue.add(new QueuedCommand("READ", callback));
            return;
        }

        sendReadInternal(callback);
    }

    private synchronized void sendReadInternal(NodeRepository.WriteCallback callback) {
        if (currentState != State.CONNECTED || connectedThread == null) {
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(true, null));
            }
            return;
        }
        isReadPending = true;
        write("READ\n".getBytes(StandardCharsets.UTF_8), callback);
        commandTimeoutHandler.removeCallbacks(commandTimeoutRunnable);
        commandTimeoutHandler.postDelayed(commandTimeoutRunnable, COMMAND_TIMEOUT_MS);
    }

    public void sendLoc(double lat, double lon, NodeRepository.WriteCallback callback) {
        try {
            String cmd = LoraProtocol.buildLocCommand(lat, lon);
            // GPS lines are handled immediately by the node and are exempt.
            write((cmd + "\n").getBytes(StandardCharsets.UTF_8), callback);
        } catch (IllegalArgumentException e) {
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(false, e.getMessage()));
            }
        }
    }

    public void write(byte[] out, NodeRepository.WriteCallback callback) {
        writeExecutor.execute(() -> {
            ConnectedThread r;
            synchronized (this) {
                if (currentState != State.CONNECTED || connectedThread == null) {
                    isMsgOrSosPending = false;
                    isReadPending = false;
                    isSosInFlight = false;
                    commandTimeoutHandler.removeCallbacks(commandTimeoutRunnable);
                    if (callback != null) {
                        mainHandler.post(() -> callback.onResult(false, "Not connected to LoRa node"));
                    }
                    return;
                }
                r = connectedThread;
            }

            try {
                String messageStr = new String(out, StandardCharsets.UTF_8);
                String finalMessage = messageStr.endsWith("\n") ? messageStr : messageStr + "\n";
                boolean success = r.write(finalMessage.getBytes(StandardCharsets.UTF_8));
                if (!success) {
                    synchronized (this) {
                        isMsgOrSosPending = false;
                        isReadPending = false;
                        isSosInFlight = false;
                        commandTimeoutHandler.removeCallbacks(commandTimeoutRunnable);
                    }
                }
                if (callback != null) {
                    mainHandler.post(() -> {
                        if (success) {
                            callback.onResult(true, null);
                        } else {
                            callback.onResult(false, "Failed to write to Bluetooth socket");
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Write error", e);
                synchronized (this) {
                    isMsgOrSosPending = false;
                    isReadPending = false;
                    isSosInFlight = false;
                    commandTimeoutHandler.removeCallbacks(commandTimeoutRunnable);
                }
                if (callback != null) {
                    mainHandler.post(() -> callback.onResult(false, e.getMessage()));
                }
            }
        });
    }

    // --- Sockets Threads ---

    private class ConnectThread extends Thread {
        private final BluetoothSocket socket;
        private final BluetoothDevice device;

        public ConnectThread(BluetoothDevice device) {
            this.device = device;
            BluetoothSocket tmp = null;
            try {
                if (hasBluetoothConnectPermission()) {
                    tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
                } else {
                    Log.e(TAG, "Missing BLUETOOTH_CONNECT permission for socket creation");
                }
            } catch (IOException | SecurityException e) {
                Log.e(TAG, "Socket create() failed", e);
                tmp = null;
            }
            socket = tmp;
        }

        public void run() {
            if (socket == null) {
                Log.e(TAG, "Cannot connect: BluetoothSocket is null");
                connectionFailed();
                return;
            }

            if (bluetoothAdapter != null && hasBluetoothConnectPermission()) {
                try {
                    bluetoothAdapter.cancelDiscovery();
                } catch (SecurityException ignored) {
                }
            }

            try {
                socket.connect();
            } catch (IOException | SecurityException e) {
                Log.e(TAG, "Socket connection failed", e);
                try {
                    socket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "unable to close() socket during connection failure", e2);
                }
                connectionFailed();
                return;
            }

            connected(socket, device);
        }

        public void cancel() {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    Log.e(TAG, "close() of connect socket failed", e);
                }
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket socket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            this.socket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }
            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;
            StringBuilder readMessage = new StringBuilder();

            while (true) {
                try {
                    if (mmInStream == null) {
                        break;
                    }
                    bytes = mmInStream.read(buffer);
                    if (bytes == -1) {
                        Log.i(TAG, "End of Bluetooth input stream reached");
                        connectionLost();
                        break;
                    }

                    String chunk = new String(buffer, 0, bytes, StandardCharsets.UTF_8);
                    readMessage.append(chunk);

                    int endOfLineIndex = readMessage.indexOf("\n");
                    while (endOfLineIndex > -1) {
                        String incomingLine = readMessage.substring(0, endOfLineIndex).trim();
                        readMessage.delete(0, endOfLineIndex + 1);

                        if (!incomingLine.isEmpty()) {
                            processIncomingLine(incomingLine);
                        }

                        endOfLineIndex = readMessage.indexOf("\n");
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Bluetooth connection lost during read", e);
                    connectionLost();
                    break;
                }
            }
        }

        public boolean write(byte[] buffer) {
            try {
                if (mmOutStream == null) return false;
                mmOutStream.write(buffer);
                mmOutStream.flush();
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Exception during socket write", e);
                return false;
            }
        }

        public void cancel() {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "close() of connected socket failed", e);
            }
        }
    }

    private void processIncomingLine(String rawLine) {
        lastLineReceivedTimestamp = System.currentTimeMillis();
        ParsedLine parsed = LoraProtocol.parseLine(rawLine);
        if (parsed.getNode() != null && !parsed.getNode().isEmpty()) {
            reportedNodeName = parsed.getNode();
        }
        NodeRepository repo = NodeRepository.getInstance();
        repo.setDeviceName(getConnectedDeviceName());
        repo.processParsedLine(parsed);

        switch (parsed.getLineType()) {
            case READY:
            case STATUS:
                updateForegroundNotification(getNotificationContent(), true);
                break;
            case FALL_MONITORING:
                int sec = parsed.getFallDurationSec() > 0 ? parsed.getFallDurationSec() : 120;
                showNotification("BT_CHANNEL", "Fall Monitoring Started",
                        "Impact detected on " + (parsed.getNode().isEmpty() ? getConnectedDeviceName() : parsed.getNode()) + ". Silent " + (sec / 60) + "-min inactivity monitoring active.",
                        NotificationCompat.PRIORITY_HIGH);
                break;
            case FALL_CANCELLED:
                showNotification("BT_CHANNEL", "Fall Monitoring Cancelled",
                        "Movement detected on " + (parsed.getNode().isEmpty() ? getConnectedDeviceName() : parsed.getNode()) + ". Monitoring cancelled.",
                        NotificationCompat.PRIORITY_DEFAULT);
                break;
            case MSG_SENT:
                onCommandCompleted();
                break;
            case SOS_SENT:
                isSosInFlight = false;
                sosRetryCount = 0;
                onCommandCompleted();
                break;
            case READ_SENT:
                isReadPending = false;
                readRetryCount = 0;
                onCommandCompleted();
                break;
            case ERROR:
                if (isSosInFlight) {
                    isSosInFlight = false;
                    onCommandCompleted();
                    if ("BUSY".equalsIgnoreCase(parsed.getErrorReason())) {
                        if (sosRetryCount == 0) {
                            sosRetryCount = 1;
                            sosRetryHandler.postDelayed(() -> {
                                synchronized (BluetoothService.this) {
                                    if (currentState == State.CONNECTED) {
                                        sendSos(null);
                                    }
                                }
                            }, 1500L);
                        }
                    }
                } else if (isReadPending) {
                    isReadPending = false;
                    onCommandCompleted();
                    if ("BUSY".equalsIgnoreCase(parsed.getErrorReason())) {
                        if (readRetryCount == 0) {
                            readRetryCount = 1;
                            readRetryHandler.postDelayed(() -> {
                                synchronized (BluetoothService.this) {
                                    if (currentState == State.CONNECTED) {
                                        sendRead(null);
                                    }
                                }
                            }, 1500L);
                        }
                    }
                } else {
                    onCommandCompleted();
                }
                break;
            default:
                break;
        }
    }

    // --- Notifications & Alerts ---

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel("BT_CHANNEL", "Bluetooth Service", NotificationManager.IMPORTANCE_LOW);
                manager.createNotificationChannel(channel);

                NotificationChannel sosChannel = new NotificationChannel("SOS_CHANNEL", "Emergency SOS", NotificationManager.IMPORTANCE_HIGH);
                sosChannel.setDescription("Notifications for SOS messages");
                sosChannel.enableLights(true);
                sosChannel.setLightColor(android.graphics.Color.RED);
                sosChannel.enableVibration(true);
                manager.createNotificationChannel(sosChannel);
            }
        }
    }

    private Notification buildForegroundNotification(String content, boolean showDisconnectAction) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "BT_CHANNEL")
                .setContentTitle("LoRa Emergency System")
                .setContentText(content)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true);

        if (showDisconnectAction) {
            Intent disconnectIntent = new Intent(this, BluetoothService.class);
            disconnectIntent.setAction(ACTION_DISCONNECT);
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent disconnectPendingIntent = PendingIntent.getService(this, 0, disconnectIntent, flags);
            builder.addAction(new NotificationCompat.Action.Builder(
                    R.drawable.ic_bluetooth, "Disconnect", disconnectPendingIntent).build());
        }

        return builder.build();
    }

    @SuppressLint("NotificationPermission")
    private void updateForegroundNotification(String content, boolean showDisconnectAction) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification(content, showDisconnectAction));
        }
    }

    @SuppressWarnings("deprecation")
    private void triggerEmergencyAlert(String message) {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                long[] pattern = {0, 500, 200, 500, 200, 500};
                v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                v.vibrate(1000);
            }
        }
        showNotification("SOS_CHANNEL", "EMERGENCY SOS", message, NotificationCompat.PRIORITY_MAX);
    }

    @SuppressWarnings("deprecation")
    private void triggerNormalAlert(String message) {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(200);
            }
        }
        showNotification("BT_CHANNEL", "New Message", message, NotificationCompat.PRIORITY_DEFAULT);
    }

    @SuppressLint("NotificationPermission")
    private void showNotification(String channelId, String title, String content, int priority) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }
        Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(priority)
                .setSound(alarmSound)
                .setAutoCancel(true)
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify((int) System.currentTimeMillis(), notification);
        }
    }

    private boolean hasBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public String getDeviceDisplayName(BluetoothDevice device) {
        if (device == null) return "Unknown Device";
        if (hasBluetoothConnectPermission()) {
            try {
                String name = device.getName();
                if (name != null && !name.trim().isEmpty()) {
                    return name;
                }
            } catch (SecurityException ignored) {
            }
        }
        return device.getAddress();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(btAdapterReceiver);
        } catch (Exception ignored) {
        }
        stopWatchdog();
        reconnectHandler.removeCallbacks(reconnectRunnable);
        readRetryHandler.removeCallbacksAndMessages(null);
        sosRetryHandler.removeCallbacksAndMessages(null);
        commandTimeoutHandler.removeCallbacks(commandTimeoutRunnable);
        disconnect(false);
        NodeRepository.getInstance().unregisterService(this);
        writeExecutor.shutdown();
    }
}
