package com.example.loracommunication.ui;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.loracommunication.MainActivity;
import com.example.loracommunication.R;
import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AlertActivity extends AppCompatActivity {

    private long incidentId = -1;
    private String kind = "SOS";
    private String node = "Remote Node";
    private Double lat = null;
    private Double lon = null;
    private long receivedAt = 0;

    private TextView tvTitle, tvSubtitle, tvSender, tvTime, tvCoordinates;
    private MaterialButton btnOpenMaps, btnAcknowledge, btnReply;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureLockScreenFlags();

        setContentView(R.layout.activity_alert);

        initViews();
        parseIntent(getIntent());
        bindData();

        com.example.loracommunication.data.NodeRepository.getInstance().getDismissAlertEvent().observe(this, event -> {
            if (event != null && Boolean.TRUE.equals(event.getContentIfNotHandled())) {
                finish();
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        parseIntent(intent);
        bindData();
    }

    private void configureLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguardManager != null) {
                keyguardManager.requestDismissKeyguard(this, null);
            }
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
        }
    }

    private void initViews() {
        tvTitle = findViewById(R.id.tv_alert_title);
        tvSubtitle = findViewById(R.id.tv_alert_subtitle);
        tvSender = findViewById(R.id.tv_alert_sender);
        tvTime = findViewById(R.id.tv_alert_time);
        tvCoordinates = findViewById(R.id.tv_alert_coordinates);
        btnOpenMaps = findViewById(R.id.btn_alert_maps);
        btnAcknowledge = findViewById(R.id.btn_alert_acknowledge);
        btnReply = findViewById(R.id.btn_alert_reply);

        btnAcknowledge.setOnClickListener(v -> handleAcknowledge());
        btnReply.setOnClickListener(v -> handleReply());
        btnOpenMaps.setOnClickListener(v -> handleOpenMaps());
    }

    private void parseIntent(Intent intent) {
        if (intent == null) return;
        incidentId = intent.getLongExtra("incident_id", -1);
        kind = intent.getStringExtra("kind");
        if (kind == null) kind = "SOS";
        node = intent.getStringExtra("node");
        if (node == null || node.isEmpty()) node = "Remote Node";
        if (intent.hasExtra("lat")) {
            lat = intent.getDoubleExtra("lat", 0.0);
            if (lat == 0.0 && !intent.getBooleanExtra("has_fix", true)) {
                lat = null;
            }
        }
        if (intent.hasExtra("lon")) {
            lon = intent.getDoubleExtra("lon", 0.0);
            if (lon == 0.0 && !intent.getBooleanExtra("has_fix", true)) {
                lon = null;
            }
        }
        receivedAt = intent.getLongExtra("receivedAt", System.currentTimeMillis());
    }

    private void bindData() {
        if ("FALL".equalsIgnoreCase(kind)) {
            tvTitle.setText(getString(R.string.fall_alert_badge) + " from " + node);
            tvSubtitle.setText("FALL DETECTED from " + node + ": no movement for 3 minutes");
        } else {
            tvTitle.setText("SOS from " + node);
            tvSubtitle.setText("EMERGENCY SOS ALERT RECEIVED VIA LORA");
        }

        tvSender.setText(node);

        String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(receivedAt));
        tvTime.setText(timeStr);

        boolean hasFix = lat != null && lon != null;
        if (hasFix) {
            tvCoordinates.setText(String.format(Locale.US, "%.5f, %.5f", lat, lon));
            btnOpenMaps.setVisibility(View.VISIBLE);
        } else {
            tvCoordinates.setText("No GPS fix");
            btnOpenMaps.setVisibility(View.GONE);
        }
    }

    private void handleAcknowledge() {
        if (incidentId > 0) {
            EmergencyAlertManager.getInstance(this).acknowledgeIncident(incidentId);
        } else {
            EmergencyAlertManager.getInstance(this).acknowledgeAll();
        }
        Toast.makeText(this, "Alert Acknowledged", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void handleReply() {
        if (incidentId > 0) {
            EmergencyAlertManager.getInstance(this).acknowledgeIncident(incidentId);
        } else {
            EmergencyAlertManager.getInstance(this).acknowledgeAll();
        }
        Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.putExtra("navigate_to", "message");
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(mainIntent);
        finish();
    }

    private void handleOpenMaps() {
        if (lat != null && lon != null) {
            Uri gmmIntentUri = Uri.parse("geo:" + lat + "," + lon + "?q=" + lat + "," + lon + "(Emergency Location)");
            Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
            mapIntent.setPackage("com.google.android.apps.maps");
            if (mapIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(mapIntent);
            } else {
                startActivity(new Intent(Intent.ACTION_VIEW, gmmIntentUri));
            }
        }
    }
}
