package com.example.loracommunication.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.example.loracommunication.R;
import com.example.loracommunication.data.ConnectionState;
import com.example.loracommunication.data.FallStateModel;
import com.example.loracommunication.data.NodeRepository;
import com.example.loracommunication.data.NodeStatus;
import com.example.loracommunication.location.PhoneLocationProvider;
import com.example.loracommunication.protocol.LoraPacket;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.util.Locale;

public class HomeFragment extends Fragment {

    private static final long SOS_HOLD_DURATION_MS = 2000;
    private static final long SOS_COOLDOWN_DURATION_MS = 10000;
    private static final long FALL_TOTAL_DURATION_SEC = 120;

    private NodeRepository repository;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvStatus, tvDeviceInfo, tvLastHeard;
    private ImageView ivStatusIcon;
    private LinearLayout layoutDiagnostics, layoutNodeActions;
    private TextView tvMpuStatus, tvGpsStatus, tvLoraStatus, tvPingLatency;
    private LinearLayout layoutLocationSource;
    private TextView tvLocationSource;
    private MaterialCardView cardNoGpsWarning;
    private ActivityResultLauncher<String[]> locationPermissionLauncher;
    private MaterialCardView cardFallMonitoring;
    private TextView tvFallTitle, tvFallCountdown, tvFallDesc;
    private MaterialButton btnSos;
    private CircularProgressIndicator progressSosHold;
    private TextView tvSosHint, tvSosStatus;

    private Vibrator vibrator;
    private long lastHeardTimestamp = 0;
    private long pingSentTimestamp = 0;
    private boolean isSosHolding = false;
    private long sosHoldStartTime = 0;
    private boolean isSosCooldown = false;

    // Recurring tasks
    private final Runnable lastHeardTicker = new Runnable() {
        @Override
        public void run() {
            updateLastHeardDisplay();
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable fallCountdownTicker = new Runnable() {
        @Override
        public void run() {
            updateFallCountdown();
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable sosHoldProgressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isSosHolding) return;
            long elapsed = System.currentTimeMillis() - sosHoldStartTime;
            if (elapsed >= SOS_HOLD_DURATION_MS) {
                // Trigger SOS
                progressSosHold.setProgress((int) SOS_HOLD_DURATION_MS);
                isSosHolding = false;
                triggerSos();
            } else {
                progressSosHold.setProgress((int) elapsed);
                // Gentle haptic feedback every 250ms
                if (elapsed % 250 < 50) {
                    performShortVibration(30);
                }
                handler.postDelayed(this, 40);
            }
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = NodeRepository.getInstance();
        vibrator = (Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);
        locationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    if (repository.getPhoneLocationProvider() != null && repository.getPhoneLocationProvider().hasLocationPermission()) {
                        Toast.makeText(getContext(), "Location permission granted. Sending phone coordinates to node...", Toast.LENGTH_SHORT).show();
                        repository.pushLocationNow(null);
                    } else {
                        Toast.makeText(getContext(), "Location permission denied", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        initViews(view);
        setupSosButtonTouch();
        setupQuickActions(view);
        setupObservers();

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.post(lastHeardTicker);
        handler.post(fallCountdownTicker);
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(lastHeardTicker);
        handler.removeCallbacks(fallCountdownTicker);
        handler.removeCallbacks(sosHoldProgressRunnable);
    }

    private void initViews(View view) {
        tvStatus = view.findViewById(R.id.tv_status);
        tvDeviceInfo = view.findViewById(R.id.tv_device_info);
        tvLastHeard = view.findViewById(R.id.tv_last_heard);
        ivStatusIcon = view.findViewById(R.id.iv_status_icon);
        layoutDiagnostics = view.findViewById(R.id.layout_diagnostics);
        layoutNodeActions = view.findViewById(R.id.layout_node_actions);
        tvMpuStatus = view.findViewById(R.id.tv_mpu_status);
        tvGpsStatus = view.findViewById(R.id.tv_gps_status);
        tvLoraStatus = view.findViewById(R.id.tv_lora_status);
        tvPingLatency = view.findViewById(R.id.tv_ping_latency);

        cardFallMonitoring = view.findViewById(R.id.card_fall_monitoring);
        tvFallTitle = view.findViewById(R.id.tv_fall_title);
        tvFallCountdown = view.findViewById(R.id.tv_fall_countdown);
        tvFallDesc = view.findViewById(R.id.tv_fall_desc);

        layoutLocationSource = view.findViewById(R.id.layout_location_source);
        tvLocationSource = view.findViewById(R.id.tv_location_source);
        cardNoGpsWarning = view.findViewById(R.id.card_no_gps_warning);
        if (cardNoGpsWarning != null) {
            cardNoGpsWarning.setOnClickListener(v -> handleEnablePhoneGpsClick());
        }

        btnSos = view.findViewById(R.id.btn_sos);
        progressSosHold = view.findViewById(R.id.progress_sos_hold);
        tvSosHint = view.findViewById(R.id.tv_sos_hint);
        tvSosStatus = view.findViewById(R.id.tv_sos_status);
    }

    private void handleEnablePhoneGpsClick() {
        PhoneLocationProvider provider = repository.getPhoneLocationProvider();
        if (provider != null && !provider.hasLocationPermission()) {
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
            return;
        }

        if (provider != null && !provider.isLocationServicesEnabled()) {
            Toast.makeText(getContext(), "Please enable Location services in device settings", Toast.LENGTH_LONG).show();
            startActivity(PhoneLocationProvider.getLocationSettingsIntent());
            return;
        }

        if (!repository.isPhoneGpsFallbackEnabled()) {
            repository.setPhoneGpsFallbackEnabled(true);
            Toast.makeText(getContext(), "Phone GPS fallback enabled", Toast.LENGTH_SHORT).show();
            repository.pushLocationNow(null);
        } else {
            Toast.makeText(getContext(), "Requesting fresh location...", Toast.LENGTH_SHORT).show();
            repository.pushLocationNow(null);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupSosButtonTouch() {
        btnSos.setOnTouchListener((v, event) -> {
            if (isSosCooldown) {
                return false;
            }

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isSosHolding = true;
                    sosHoldStartTime = System.currentTimeMillis();
                    progressSosHold.setProgress(0);
                    performShortVibration(50);
                    handler.post(sosHoldProgressRunnable);
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isSosHolding) {
                        isSosHolding = false;
                        handler.removeCallbacks(sosHoldProgressRunnable);
                        progressSosHold.setProgress(0);
                    }
                    return true;
            }
            return false;
        });
    }

    private void setupQuickActions(View view) {
        MaterialButton btnConnect = view.findViewById(R.id.btn_connect_bt);
        MaterialButton btnStatus = view.findViewById(R.id.btn_status);
        MaterialButton btnPing = view.findViewById(R.id.btn_ping);

        btnConnect.setOnClickListener(v ->
                Navigation.findNavController(view).navigate(R.id.action_homeFragment_to_bluetoothFragment)
        );

        btnStatus.setOnClickListener(v -> {
            if (repository.getConnectionState().getValue() == ConnectionState.CONNECTED) {
                NodeStatus current = repository.getNodeStatus().getValue();
                if (current != null) {
                    updateDiagnostics(current);
                }
                Toast.makeText(getContext(), "Showing latest status", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), getString(R.string.not_connected_hint), Toast.LENGTH_SHORT).show();
            }
        });

        btnPing.setOnClickListener(v -> {
            if (repository.getConnectionState().getValue() == ConnectionState.CONNECTED) {
                pingSentTimestamp = System.currentTimeMillis();
                tvPingLatency.setVisibility(View.GONE);
                repository.sendPing((success, error) -> {
                    if (!success && error != null) {
                        Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show();
                    }
                });
                Toast.makeText(getContext(), getString(R.string.ping_sent), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), getString(R.string.not_connected_hint), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupObservers() {
        repository.getConnectionState().observe(getViewLifecycleOwner(), state -> {
            String deviceName = repository.getDeviceName().getValue();
            updateUI(state, deviceName);
        });

        repository.getDeviceName().observe(getViewLifecycleOwner(), name -> {
            ConnectionState state = repository.getConnectionState().getValue();
            updateUI(state, name);
        });

        repository.getNodeStatus().observe(getViewLifecycleOwner(), status -> {
            lastHeardTimestamp = System.currentTimeMillis();
            updateDiagnostics(status);
        });

        repository.getLastHeardTime().observe(getViewLifecycleOwner(), ts -> {
            if (ts != null && ts > 0) {
                lastHeardTimestamp = ts;
                updateLastHeardDisplay();
            }
        });

        repository.getFallState().observe(getViewLifecycleOwner(), this::updateFallMonitoring);

        repository.getLastError().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                tvSosStatus.setText("Error: " + error + " (Tap to retry)");
                tvSosStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.emergency_red));
                tvSosStatus.setVisibility(View.VISIBLE);
                tvSosStatus.setOnClickListener(v -> triggerSos());
            }
        });

        repository.getPongEvents().observe(getViewLifecycleOwner(), event -> {
            String node = event != null ? event.getContentIfNotHandled() : null;
            if (node != null) {
                lastHeardTimestamp = System.currentTimeMillis();
                long latency = System.currentTimeMillis() - pingSentTimestamp;
                if (tvPingLatency != null) {
                    tvPingLatency.setText(String.format(Locale.US, "PONG from %s (latency: %dms)", node, latency));
                    tvPingLatency.setVisibility(View.VISIBLE);
                }
            }
        });

        repository.getIncomingPackets().observe(getViewLifecycleOwner(), event -> {
            LoraPacket packet = event != null ? event.peekContent() : null;
            if (packet != null) {
                lastHeardTimestamp = System.currentTimeMillis();
                if (packet.getType() == LoraPacket.Type.SOS) {
                    tvSosStatus.setText("SOS sent over LoRa");
                    tvSosStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_connected));
                    tvSosStatus.setVisibility(View.VISIBLE);
                    tvSosStatus.setOnClickListener(null);
                }
            }
        });
    }

    private void updateUI(ConnectionState state, String deviceName) {
        if (tvStatus == null || tvDeviceInfo == null) return;

        if (state == ConnectionState.CONNECTED) {
            tvStatus.setText(getString(R.string.connection_status, getString(R.string.connected)));
            tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_connected));
            String name = (deviceName != null && !deviceName.isEmpty()) ? deviceName : "Node";
            tvDeviceInfo.setText("Module: " + name);
            tvDeviceInfo.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary));
            if (ivStatusIcon != null) {
                ivStatusIcon.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_connected_bg)));
                ivStatusIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_connected)));
            }
            if (layoutDiagnostics != null) layoutDiagnostics.setVisibility(View.VISIBLE);
            if (layoutNodeActions != null) layoutNodeActions.setVisibility(View.VISIBLE);
        } else if (state == ConnectionState.CONNECTING) {
            tvStatus.setText(getString(R.string.connection_status, getString(R.string.connecting)));
            tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_connecting));
            String name = (deviceName != null && !deviceName.isEmpty()) ? deviceName : "device";
            tvDeviceInfo.setText("Searching for " + name);
            if (ivStatusIcon != null) {
                ivStatusIcon.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_connecting_bg)));
                ivStatusIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_connecting)));
            }
            if (layoutDiagnostics != null) layoutDiagnostics.setVisibility(View.GONE);
            if (layoutNodeActions != null) layoutNodeActions.setVisibility(View.GONE);
            if (layoutLocationSource != null) layoutLocationSource.setVisibility(View.GONE);
            if (cardNoGpsWarning != null) cardNoGpsWarning.setVisibility(View.GONE);
        } else {
            tvStatus.setText(getString(R.string.connection_status, getString(R.string.disconnected)));
            tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_disconnected));
            tvDeviceInfo.setText(getString(R.string.no_module_connected));
            if (ivStatusIcon != null) {
                ivStatusIcon.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_disconnected_bg)));
                ivStatusIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_disconnected)));
            }
            if (layoutDiagnostics != null) layoutDiagnostics.setVisibility(View.GONE);
            if (layoutNodeActions != null) layoutNodeActions.setVisibility(View.GONE);
            if (layoutLocationSource != null) layoutLocationSource.setVisibility(View.GONE);
            if (cardNoGpsWarning != null) cardNoGpsWarning.setVisibility(View.GONE);
            if (cardFallMonitoring != null) cardFallMonitoring.setVisibility(View.GONE);
        }
    }

    private void updateDiagnostics(NodeStatus status) {
        if (tvMpuStatus == null || tvGpsStatus == null || tvLoraStatus == null || status == null) return;

        // MPU badge: NOT_FOUND = "Fall detection OFF"
        if ("NOT_FOUND".equalsIgnoreCase(status.getMpu())) {
            tvMpuStatus.setText("Fall detection OFF");
            tvMpuStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.emergency_red));
        } else {
            tvMpuStatus.setText("MPU: " + status.getMpu());
            int mpuColor = status.isMpuReady() ? R.color.status_connected : R.color.emergency_red;
            tvMpuStatus.setTextColor(ContextCompat.getColor(requireContext(), mpuColor));
        }

        // GPS badge: FIX / NO_FIX
        tvGpsStatus.setText("GPS: " + status.getGps());
        int gpsColor = status.hasGpsFix() ? R.color.status_connected : R.color.status_connecting;
        tvGpsStatus.setTextColor(ContextCompat.getColor(requireContext(), gpsColor));

        // LoRa badge
        tvLoraStatus.setText("LoRa: " + status.getLora());
        int loraColor = status.isLoraReady() ? R.color.status_connected : R.color.status_disconnected;
        tvLoraStatus.setTextColor(ContextCompat.getColor(requireContext(), loraColor));

        // Location Source badge & Warning banner
        String source = status.getSource();
        String displaySource = "None";
        if ("GPS".equalsIgnoreCase(source) || status.hasGpsFix()) {
            displaySource = "Node GPS";
        } else if ("PHONE".equalsIgnoreCase(source) || repository.nodeHasValidPhoneLoc()) {
            displaySource = "Phone GPS";
        }

        if (layoutLocationSource != null) {
            layoutLocationSource.setVisibility(View.VISIBLE);
        }
        if (tvLocationSource != null) {
            tvLocationSource.setText(displaySource);
            int srcColor = "None".equals(displaySource) ? R.color.emergency_red : R.color.status_connected;
            tvLocationSource.setTextColor(ContextCompat.getColor(requireContext(), srcColor));
        }

        if (cardNoGpsWarning != null) {
            if ("None".equals(displaySource) && repository.getConnectionState().getValue() == ConnectionState.CONNECTED) {
                cardNoGpsWarning.setVisibility(View.VISIBLE);
            } else {
                cardNoGpsWarning.setVisibility(View.GONE);
            }
        }
    }

    private void updateLastHeardDisplay() {
        if (tvLastHeard == null) return;
        if (repository.getConnectionState().getValue() != ConnectionState.CONNECTED || lastHeardTimestamp == 0) {
            tvLastHeard.setText("Last heard: --");
            return;
        }

        long secondsAgo = (System.currentTimeMillis() - lastHeardTimestamp) / 1000;
        if (secondsAgo < 0) secondsAgo = 0;
        tvLastHeard.setText(String.format(Locale.US, "Last heard: %ds ago", secondsAgo));
    }

    private void updateFallMonitoring(FallStateModel fallState) {
        if (cardFallMonitoring == null || tvFallDesc == null) return;

        if (fallState != null && fallState.isMonitoring()) {
            cardFallMonitoring.setVisibility(View.VISIBLE);
            cardFallMonitoring.setCardBackgroundColor(Color.parseColor("#FFF3E0"));
            cardFallMonitoring.setStrokeColor(Color.parseColor("#FFB74D"));
            tvFallTitle.setText("High impact detected: monitoring");
            tvFallTitle.setTextColor(Color.parseColor("#E65100"));
            tvFallCountdown.setVisibility(View.VISIBLE);
            tvFallDesc.setVisibility(View.VISIBLE);
            tvFallDesc.setText("Automatic FALL SOS follows if countdown reaches 00:00 without movement.");
            updateFallCountdown();
        } else if (fallState != null && "CANCELLED".equals(fallState.getState())) {
            cardFallMonitoring.setVisibility(View.VISIBLE);
            cardFallMonitoring.setCardBackgroundColor(Color.parseColor("#E8F5E9"));
            cardFallMonitoring.setStrokeColor(Color.parseColor("#81C784"));
            tvFallTitle.setText("Movement detected, fall cancelled");
            tvFallTitle.setTextColor(Color.parseColor("#2E7D32"));
            tvFallCountdown.setVisibility(View.GONE);
            tvFallDesc.setVisibility(View.GONE);

            // Auto-hide after ~4 seconds
            handler.postDelayed(() -> {
                FallStateModel current = repository.getFallState().getValue();
                if (current != null && "CANCELLED".equals(current.getState()) && cardFallMonitoring != null) {
                    cardFallMonitoring.setVisibility(View.GONE);
                }
            }, 4000);
        } else {
            cardFallMonitoring.setVisibility(View.GONE);
        }
    }

    private void updateFallCountdown() {
        if (tvFallCountdown == null || cardFallMonitoring.getVisibility() != View.VISIBLE) return;
        FallStateModel fallState = repository.getFallState().getValue();
        if (fallState == null || !fallState.isMonitoring()) return;

        long totalDuration = fallState.getDurationSec() > 0 ? fallState.getDurationSec() : FALL_TOTAL_DURATION_SEC;
        long elapsedSec = (System.currentTimeMillis() - fallState.getStartedAt()) / 1000;
        long remainingSec = totalDuration - elapsedSec;
        if (remainingSec < 0) remainingSec = 0;

        long mm = remainingSec / 60;
        long ss = remainingSec % 60;
        tvFallCountdown.setText(String.format(Locale.US, "Time remaining: %02d:%02d", mm, ss));
    }

    private void triggerSos() {
        performShortVibration(300);

        if (repository.getConnectionState().getValue() != ConnectionState.CONNECTED) {
            tvSosStatus.setText("Not connected to node (Tap to retry)");
            tvSosStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.emergency_red));
            tvSosStatus.setVisibility(View.VISIBLE);
            tvSosStatus.setOnClickListener(v -> triggerSos());
            Toast.makeText(getContext(), getString(R.string.not_connected_hint), Toast.LENGTH_SHORT).show();
            return;
        }

        tvSosStatus.setText("Transmitting SOS over LoRa...");
        tvSosStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary));
        tvSosStatus.setVisibility(View.VISIBLE);
        tvSosStatus.setOnClickListener(null);

        repository.sendSos((success, error) -> {
            if (!success) {
                isSosCooldown = false;
                btnSos.setEnabled(true);
                tvSosHint.setText("HOLD 2 SECONDS TO TRIGGER SOS");
                progressSosHold.setProgress(0);
                tvSosStatus.setText("Failed to send SOS (Tap to retry)");
                tvSosStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.emergency_red));
                tvSosStatus.setOnClickListener(v -> triggerSos());
                if (error != null) {
                    Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show();
                }
            }
        });

        startSosCooldown();
    }

    private void startSosCooldown() {
        isSosCooldown = true;
        btnSos.setEnabled(false);
        final long startTime = System.currentTimeMillis();

        final Runnable cooldownRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsed = System.currentTimeMillis() - startTime;
                long remainingSec = (SOS_COOLDOWN_DURATION_MS - elapsed) / 1000;
                if (remainingSec > 0) {
                    tvSosHint.setText(String.format(Locale.US, "COOLDOWN: %ds", remainingSec));
                    handler.postDelayed(this, 1000);
                } else {
                    isSosCooldown = false;
                    btnSos.setEnabled(true);
                    tvSosHint.setText("HOLD 2 SECONDS TO TRIGGER SOS");
                    progressSosHold.setProgress(0);
                }
            }
        };
        handler.post(cooldownRunnable);
    }

    @SuppressWarnings("deprecation")
    private void performShortVibration(long ms) {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(ms);
            }
        }
    }
}
