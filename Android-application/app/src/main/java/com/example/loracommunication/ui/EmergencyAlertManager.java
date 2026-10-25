package com.example.loracommunication.ui;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.loracommunication.MainActivity;
import com.example.loracommunication.R;
import com.example.loracommunication.data.AppDatabase;
import com.example.loracommunication.data.Incident;
import com.example.loracommunication.data.Message;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EmergencyAlertManager {
    private static final String TAG = "EmergencyAlertManager";

    public static final String EMERGENCY_CHANNEL_ID = "EMERGENCY_ALERTS_CHANNEL_V2";
    public static final String MESSAGES_CHANNEL_ID = "MESSAGES_CHANNEL";

    public static final String ACTION_ACKNOWLEDGE = "com.example.loracommunication.ACTION_ACKNOWLEDGE";
    public static final String EXTRA_INCIDENT_ID = "incident_id";
    public static final int EMERGENCY_NOTIFICATION_ID = 2001;

    private static volatile EmergencyAlertManager INSTANCE;

    private final Context appContext;
    private final NotificationManager notificationManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final java.util.Set<Long> acknowledgedIncidentIds = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private final java.util.concurrent.atomic.AtomicLong alertGeneration = new java.util.concurrent.atomic.AtomicLong(0);

    private MediaPlayer alarmPlayer;
    private Vibrator vibrator;
    private Runnable renotifyRunnable;
    private long currentAlertIncidentId = -1;

    @androidx.annotation.VisibleForTesting
    public EmergencyAlertManager(Context context) {
        this.appContext = context != null ? context.getApplicationContext() : null;
        this.notificationManager = appContext != null
                ? (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE)
                : null;
        if (notificationManager != null) {
            createChannels();
        }
    }

    public static EmergencyAlertManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (EmergencyAlertManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new EmergencyAlertManager(context);
                }
            }
        }
        return INSTANCE;
    }

    public void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager == null) return;

            // 1. Emergency Alerts Channel
            NotificationChannel emergencyChannel = new NotificationChannel(
                    EMERGENCY_CHANNEL_ID,
                    "Emergency alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            emergencyChannel.setDescription("High priority emergency alerts for SOS and Fall detection");
            emergencyChannel.enableLights(true);
            emergencyChannel.setLightColor(0xFFFF0000); // Red
            emergencyChannel.enableVibration(true);
            emergencyChannel.setVibrationPattern(new long[]{0, 500, 200, 500, 200, 500});
            emergencyChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

            // Audio attributes: notification itself is silent to avoid duplicate audio sources;
            // sound is produced by the looping MediaPlayer on USAGE_ALARM.
            emergencyChannel.setSound(null, null);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                emergencyChannel.setBypassDnd(true);
            }
            notificationManager.createNotificationChannel(emergencyChannel);

            // 2. Messages Channel
            NotificationChannel messagesChannel = new NotificationChannel(
                    MESSAGES_CHANNEL_ID,
                    "Messages",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            messagesChannel.setDescription("Standard incoming LoRa chat messages");
            messagesChannel.enableVibration(true);
            messagesChannel.setVibrationPattern(new long[]{0, 200});
            notificationManager.createNotificationChannel(messagesChannel);
        }
    }

    @SuppressLint("NotificationPermission")
    public synchronized void triggerEmergencyAlert(Incident incident) {
        if (incident == null || incident.getId() <= 0) {
            Log.w(TAG, "Cannot trigger emergency alert: invalid incident id " + (incident != null ? incident.getId() : "null"));
            return;
        }
        acknowledgedIncidentIds.remove(incident.getId());
        currentAlertIncidentId = incident.getId();

        // 1. Start looping alarm sound + vibration
        startAlarmLoop();

        // 2. Launch full-screen alert notification
        showEmergencyNotification(incident);

        // 3. Schedule 60-second re-notify while unacknowledged
        scheduleRenotify(incident);
    }

    @SuppressLint("NotificationPermission")
    public void showEmergencyNotification(Incident incident) {
        if (incident == null || incident.getId() <= 0) {
            Log.w(TAG, "Cannot show emergency notification: invalid incident id " + (incident != null ? incident.getId() : "null"));
            return;
        }

        Intent fullScreenIntent = new Intent(appContext, AlertActivity.class);
        fullScreenIntent.putExtra("incident_id", incident.getId());
        fullScreenIntent.putExtra("kind", incident.getKind());
        fullScreenIntent.putExtra("node", incident.getNode());
        fullScreenIntent.putExtra("lat", incident.getLat());
        fullScreenIntent.putExtra("lon", incident.getLon());
        fullScreenIntent.putExtra("receivedAt", incident.getReceivedAt());
        fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                appContext,
                (int) incident.getId(),
                fullScreenIntent,
                flags
        );

        // Acknowledge Action Intent
        Intent ackIntent = new Intent(appContext, AlertActionReceiver.class);
        ackIntent.setAction(ACTION_ACKNOWLEDGE);
        ackIntent.putExtra(EXTRA_INCIDENT_ID, incident.getId());
        PendingIntent ackPendingIntent = PendingIntent.getBroadcast(
                appContext,
                (int) incident.getId(),
                ackIntent,
                flags
        );

        String title = "FALL".equalsIgnoreCase(incident.getKind())
                ? "FALL DETECTED from " + incident.getNode() + ": no movement for 3 minutes"
                : "SOS from " + incident.getNode();

        String content = incident.hasGpsFix()
                ? String.format(Locale.US, "Location: %.5f, %.5f", incident.getLat(), incident.getLon())
                : "No GPS fix";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, EMERGENCY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sos)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(false)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setContentIntent(fullScreenPendingIntent)
                .addAction(R.drawable.ic_history, "Acknowledge", ackPendingIntent);

        if (incident.hasGpsFix()) {
            Uri gmmIntentUri = Uri.parse("geo:" + incident.getLat() + "," + incident.getLon()
                    + "?q=" + incident.getLat() + "," + incident.getLon() + "(Emergency Location)");
            Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
            mapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent mapPendingIntent = PendingIntent.getActivity(
                    appContext,
                    (int) incident.getId() + 100,
                    mapIntent,
                    flags
            );
            builder.addAction(R.drawable.ic_location, "Open in Maps", mapPendingIntent);
        }

        if (notificationManager != null) {
            notificationManager.notify(EMERGENCY_NOTIFICATION_ID, builder.build());
        }
    }

    public synchronized void startAlarmLoop() {
        stopAlarmLoop();
        if (appContext == null) return;

        try {
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            alarmPlayer = new MediaPlayer();
            alarmPlayer.setDataSource(appContext, alarmUri);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                alarmPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
            } else {
                alarmPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
            }
            alarmPlayer.setLooping(true);
            alarmPlayer.prepare();
            alarmPlayer.start();
        } catch (Exception e) {
            Log.e(TAG, "Error playing alarm sound", e);
        }

        try {
            vibrator = (Vibrator) appContext.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0, 600, 300, 600, 300, 800};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0)); // 0 = loop from start
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting vibration", e);
        }
    }

    public synchronized void stopAlarmLoop() {
        if (alarmPlayer != null) {
            try {
                if (alarmPlayer.isPlaying()) {
                    alarmPlayer.stop();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error stopping alarm player", e);
            }
            try {
                alarmPlayer.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing alarm player", e);
            }
            alarmPlayer = null;
        }

        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception ignored) {}
            vibrator = null;
        }
    }

    public void acknowledgeIncident(long incidentId) {
        acknowledgeIncident(incidentId, true);
    }

    public void acknowledgeIncident(long incidentId, boolean sendReadToNode) {
        // Strict execution order:
        // 1. stopAlarmLoop()
        stopAlarmLoop();

        // 2. stopRenotifyTimer()
        stopRenotifyTimer();

        // 3. Cancel the active emergency notification
        if (notificationManager != null) {
            notificationManager.cancel(EMERGENCY_NOTIFICATION_ID);
        }
        if (currentAlertIncidentId == incidentId) {
            currentAlertIncidentId = -1;
        }

        if (incidentId <= 0) {
            return;
        }

        // In-memory pure-Java idempotency guard
        if (!acknowledgedIncidentIds.add(incidentId)) {
            return;
        }

        // 4. Mark the incident acknowledged in the database
        if (appContext != null) {
            executor.execute(() -> {
                AppDatabase db = AppDatabase.getDatabase(appContext);
                db.incidentDao().acknowledge(incidentId);
            });
        }

        // 5. Dispatch the read confirmation to the node
        if (sendReadToNode) {
            dispatchReadToNode();
        }
    }

    public void acknowledgeAll() {
        acknowledgeAll(true);
    }

    public void acknowledgeAll(boolean sendReadToNode) {
        // Strict execution order:
        // 1. stopAlarmLoop()
        stopAlarmLoop();

        // 2. stopRenotifyTimer()
        stopRenotifyTimer();

        // 3. Cancel the active emergency notification
        if (notificationManager != null) {
            notificationManager.cancel(EMERGENCY_NOTIFICATION_ID);
        }
        currentAlertIncidentId = -1;

        // 4. Mark all active incident rows acknowledged in database
        if (appContext != null) {
            executor.execute(() -> {
                AppDatabase db = AppDatabase.getDatabase(appContext);
                java.util.List<Incident> all = db.incidentDao().getAllIncidentsSync();
                if (all != null) {
                    for (Incident inc : all) {
                        if (!inc.isAcknowledged()) {
                            acknowledgedIncidentIds.add(inc.getId());
                        }
                    }
                }
                db.incidentDao().acknowledgeAll();
            });
        }

        // 5. Dispatch a single READ command to the node
        if (sendReadToNode) {
            dispatchReadToNode();
        }
    }

    private void dispatchReadToNode() {
        com.example.loracommunication.data.NodeRepository repo =
                com.example.loracommunication.data.NodeRepository.getInstance();
        if (repo.getConnectionState().getValue() == com.example.loracommunication.data.ConnectionState.CONNECTED) {
            repo.sendRead(null);
        }
    }

    public boolean isIncidentAcknowledgedInMemory(long incidentId) {
        return acknowledgedIncidentIds.contains(incidentId);
    }

    private void scheduleRenotify(Incident incident) {
        stopRenotifyTimer();
        if (incident == null || incident.getId() <= 0) return;
        final long incId = incident.getId();
        final long gen = alertGeneration.incrementAndGet();
        renotifyRunnable = new Runnable() {
            @Override
            public void run() {
                executor.execute(() -> {
                    AppDatabase db = AppDatabase.getDatabase(appContext);
                    Incident latest = db.incidentDao().getById(incId);
                    if (latest != null && !latest.isAcknowledged()) {
                        mainHandler.post(() -> {
                            // Check on Android Main Thread immediately before restarting alarm
                            if (alertGeneration.get() != gen || acknowledgedIncidentIds.contains(incId)) {
                                Log.i(TAG, "Renotify suppressed: incident " + incId + " was acknowledged");
                                return;
                            }
                            showEmergencyNotification(latest);
                            startAlarmLoop();
                            if (renotifyRunnable == this) {
                                mainHandler.postDelayed(this, 60000); // Repeat every 60s
                            }
                        });
                    }
                });
            }
        };
        mainHandler.postDelayed(renotifyRunnable, 60000);
    }

    private void stopRenotifyTimer() {
        alertGeneration.incrementAndGet();
        if (renotifyRunnable != null) {
            mainHandler.removeCallbacks(renotifyRunnable);
            renotifyRunnable = null;
        }
    }

    @SuppressLint("NotificationPermission")
    public void showNormalMessageNotification(Message message, String title, String body) {
        try {
            Vibrator v = (Vibrator) appContext.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(200);
                }
            }
        } catch (Exception ignored) {}

        Intent openIntent = new Intent(appContext, MainActivity.class);
        openIntent.putExtra("navigate_to", "message");
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(appContext, 0, openIntent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, MESSAGES_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        if (notificationManager != null) {
            notificationManager.notify((int) System.currentTimeMillis(), builder.build());
        }
    }
}
