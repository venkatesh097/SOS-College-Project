package com.example.loracommunication.bluetooth;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.loracommunication.R;
import com.example.loracommunication.data.AppDatabase;
import com.example.loracommunication.data.Message;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BluetoothService extends Service {
    private static final String TAG = "BluetoothService";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // Standard SPP UUID

    public static final String ACTION_STATE_CHANGED = "com.example.loracommunication.BT_STATE_CHANGED";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_DEVICE_NAME = "device_name";

    public enum State {
        NONE, CONNECTING, CONNECTED
    }

    private State currentState = State.NONE;
    private String connectedDeviceName = null;
    
    private final IBinder binder = new LocalBinder();
    private BluetoothAdapter bluetoothAdapter;
    private ConnectThread connectThread;
    private ConnectedThread connectedThread;
    private AppDatabase db;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public class LocalBinder extends Binder {
        public BluetoothService getService() {
            return BluetoothService.this;
        }
    }

    public State getState() {
        return currentState;
    }

    public String getConnectedDeviceName() {
        return connectedDeviceName;
    }

    private synchronized void setState(State state, String deviceName) {
        currentState = state;
        connectedDeviceName = deviceName;
        
        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.putExtra(EXTRA_STATE, state.ordinal());
        intent.putExtra(EXTRA_DEVICE_NAME, deviceName);
        sendBroadcast(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        db = AppDatabase.getDatabase(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, getNotification("Service Started"));
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @SuppressLint("MissingPermission")
    public synchronized void connect(BluetoothDevice device) {
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        setState(State.CONNECTING, device.getName());
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
        
        setState(State.CONNECTED, device.getName());
        updateNotification("Connected to " + device.getName());
    }

    public void write(byte[] out) {
        ConnectedThread r;
        synchronized (this) {
            if (connectedThread == null) return;
            r = connectedThread;
        }
        r.write(out);
    }

    private class ConnectThread extends Thread {
        private final BluetoothSocket socket;
        private final BluetoothDevice device;

        @SuppressLint("MissingPermission")
        public ConnectThread(BluetoothDevice device) {
            this.device = device;
            BluetoothSocket tmp = null;
            try {
                tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {
                Log.e(TAG, "Socket create() failed", e);
            }
            socket = tmp;
        }

        @SuppressLint("MissingPermission")
        public void run() {
            bluetoothAdapter.cancelDiscovery();
            try {
                socket.connect();
            } catch (IOException e) {
                setState(BluetoothService.State.NONE, null);
                try {
                    socket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "unable to close() socket during connection failure", e2);
                }
                return;
            }
            connected(socket, device);
        }

        public void cancel() {
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
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
            while (true) {
                try {
                    bytes = mmInStream.read(buffer);
                    String incomingMessage = new String(buffer, 0, bytes);
                    
                    // Save to database
                    boolean isSos = incomingMessage.contains("SOS");
                    Message msg = new Message("Remote", incomingMessage, System.currentTimeMillis(), 0, 0, isSos, false);
                    executor.execute(() -> db.messageDao().insert(msg));

                    if (isSos) {
                        triggerEmergencyAlert(incomingMessage);
                    } else {
                        triggerNormalAlert(incomingMessage);
                    }

                    Intent intent = new Intent("com.example.loracommunication.BLUETOOTH_DATA");
                    intent.putExtra("data", incomingMessage);
                    sendBroadcast(intent);
                } catch (IOException e) {
                    Log.e(TAG, "disconnected", e);
                    setState(BluetoothService.State.NONE, null);
                    break;
                }
            }
        }

        public void write(byte[] buffer) {
            try {
                mmOutStream.write(buffer);
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }

        public void cancel() {
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                // Default Channel
                NotificationChannel channel = new NotificationChannel("BT_CHANNEL", "Bluetooth Service", NotificationManager.IMPORTANCE_LOW);
                manager.createNotificationChannel(channel);

                // SOS Channel (High Importance)
                NotificationChannel sosChannel = new NotificationChannel("SOS_CHANNEL", "Emergency SOS", NotificationManager.IMPORTANCE_HIGH);
                sosChannel.setDescription("Notifications for SOS messages");
                sosChannel.enableLights(true);
                sosChannel.setLightColor(android.graphics.Color.RED);
                sosChannel.enableVibration(true);
                manager.createNotificationChannel(sosChannel);
            }
        }
    }

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

    private void showNotification(String channelId, String title, String content, int priority) {
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

    private Notification getNotification(String content) {
        return new NotificationCompat.Builder(this, "BT_CHANNEL")
                .setContentTitle("LoRa System")
                .setContentText(content)
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
    }

    private void updateNotification(String content) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(1, getNotification(content));
    }
}
