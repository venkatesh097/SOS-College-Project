package com.example.loracommunication.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.loracommunication.R;
import com.example.loracommunication.data.ConnectionState;
import com.example.loracommunication.data.Incident;
import com.example.loracommunication.data.NodeRepository;
import com.example.loracommunication.data.NodeStatus;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

public class SettingsFragment extends Fragment {

    private TextInputEditText etUsername, etDeviceId;
    private RadioGroup rgTheme;
    private TextView tvSettingsNodeStatus;
    private TextView tvModeName;
    private MaterialSwitch switchRescueMode;
    private SharedPreferences prefs;

    // Permission status views
    private TextView tvPermBluetoothStatus;
    private TextView tvPermNotificationStatus;
    private TextView tvPermFullscreenStatus;
    private TextView tvPermBatteryStatus;

    private NodeRepository repository;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = NodeRepository.getInstance();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        etUsername = view.findViewById(R.id.et_username);
        etDeviceId = view.findViewById(R.id.et_device_id);
        tvModeName = view.findViewById(R.id.tv_mode_name);
        switchRescueMode = view.findViewById(R.id.switch_rescue_mode);
        rgTheme = view.findViewById(R.id.rg_theme);
        RadioButton rbThemeSystem = view.findViewById(R.id.rb_theme_system);
        RadioButton rbThemeLight = view.findViewById(R.id.rb_theme_light);
        RadioButton rbThemeDark = view.findViewById(R.id.rb_theme_dark);
        tvSettingsNodeStatus = view.findViewById(R.id.tv_settings_node_status);
        MaterialButton btnStatus = view.findViewById(R.id.btn_settings_status);
        MaterialButton btnPing = view.findViewById(R.id.btn_settings_ping);
        MaterialButton btnTestAlarm = view.findViewById(R.id.btn_test_alarm);
        MaterialButton btnBatteryOpt = view.findViewById(R.id.btn_battery_optimizations);
        MaterialButton btnSave = view.findViewById(R.id.btn_save);

        // Permission status rows
        tvPermBluetoothStatus = view.findViewById(R.id.tv_perm_bluetooth_status);
        tvPermNotificationStatus = view.findViewById(R.id.tv_perm_notification_status);
        tvPermFullscreenStatus = view.findViewById(R.id.tv_perm_fullscreen_status);
        tvPermBatteryStatus = view.findViewById(R.id.tv_perm_battery_status);

        View rowBluetooth = view.findViewById(R.id.row_perm_bluetooth);
        View rowNotification = view.findViewById(R.id.row_perm_notification);
        View rowFullscreen = view.findViewById(R.id.row_perm_fullscreen);
        View rowBattery = view.findViewById(R.id.row_perm_battery);

        prefs = requireActivity().getSharedPreferences("lora_prefs", Context.MODE_PRIVATE);

        // 1. Mode Setting (0 = Trekker, 1 = Rescue)
        int mode = prefs.getInt("mode", 0);
        switchRescueMode.setChecked(mode == 1);
        updateModeLabel(mode == 1);
        switchRescueMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putInt("mode", isChecked ? 1 : 0).apply();
            updateModeLabel(isChecked);
            Toast.makeText(getContext(), isChecked ? "Switched to Rescue Mode" : "Switched to Trekker Mode", Toast.LENGTH_SHORT).show();
        });

        // 2. Profile
        etUsername.setText(prefs.getString("username", "User1"));
        etDeviceId.setText(prefs.getString("device_id", "NODE A"));

        // 3. Theme
        int savedThemeMode = prefs.getInt("theme_mode", 0);
        switch (savedThemeMode) {
            case 1:
                rbThemeLight.setChecked(true);
                break;
            case 2:
                rbThemeDark.setChecked(true);
                break;
            default:
                rbThemeSystem.setChecked(true);
                break;
        }

        // 4. Node Action Buttons
        btnStatus.setOnClickListener(v -> {
            if (repository.getConnectionState().getValue() == ConnectionState.CONNECTED) {
                Toast.makeText(getContext(), "Showing latest status", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), getString(R.string.not_connected_hint), Toast.LENGTH_SHORT).show();
            }
        });

        btnPing.setOnClickListener(v -> {
            if (repository.getConnectionState().getValue() == ConnectionState.CONNECTED) {
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

        // 5. Test Alarm Button
        btnTestAlarm.setOnClickListener(v -> {
            Incident testIncident = new Incident(999, "SOS", "TEST NODE", 1L, 13.0827, 80.2707, System.currentTimeMillis(), false);
            EmergencyAlertManager.getInstance(requireContext()).triggerEmergencyAlert(testIncident);
        });

        // 6. Battery Optimization Shortcut
        btnBatteryOpt.setOnClickListener(v -> openBatteryOptimizationSettings());

        // 7. Phone GPS Fallback Controls
        MaterialSwitch switchPhoneGpsFallback = view.findViewById(R.id.switch_phone_gps_fallback);
        if (switchPhoneGpsFallback != null) {
            switchPhoneGpsFallback.setChecked(repository.isPhoneGpsFallbackEnabled());
            switchPhoneGpsFallback.setOnCheckedChangeListener((btn, isChecked) -> {
                repository.setPhoneGpsFallbackEnabled(isChecked);
                Toast.makeText(getContext(), isChecked ? "Phone GPS fallback enabled" : "Phone GPS fallback disabled", Toast.LENGTH_SHORT).show();
            });
        }

        MaterialButton btnSendLocationNow = view.findViewById(R.id.btn_send_location_now);
        if (btnSendLocationNow != null) {
            btnSendLocationNow.setOnClickListener(v -> {
                btnSendLocationNow.setEnabled(false);
                Toast.makeText(getContext(), "Acquiring location and pushing to node...", Toast.LENGTH_SHORT).show();
                repository.pushLocationNow((success, error) -> {
                    btnSendLocationNow.setEnabled(true);
                    if (success) {
                        Toast.makeText(getContext(), "Location sent successfully: LOC_OK", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(getContext(), "Failed to send location: " + (error != null ? error : "Unknown error"), Toast.LENGTH_LONG).show();
                    }
                });
            });
        }

        // Debug-only Protocol Simulator
        MaterialButton btnLaunchSimulator = view.findViewById(R.id.btn_launch_simulator);
        if (btnLaunchSimulator != null) {
            if (com.example.loracommunication.BuildConfig.DEBUG) {
                btnLaunchSimulator.setVisibility(View.VISIBLE);
                btnLaunchSimulator.setOnClickListener(v -> {
                    new SimulatorBottomSheet().show(getParentFragmentManager(), "SimulatorBottomSheet");
                });
            } else {
                btnLaunchSimulator.setVisibility(View.GONE);
            }
        }

        // Permission row clicks
        if (rowBluetooth != null) {
            rowBluetooth.setOnClickListener(v -> openAppSettings());
        }
        if (rowNotification != null) {
            rowNotification.setOnClickListener(v -> openAppNotificationSettings());
        }
        if (rowFullscreen != null) {
            rowFullscreen.setOnClickListener(v -> openFullScreenIntentSettings());
        }
        if (rowBattery != null) {
            rowBattery.setOnClickListener(v -> openBatteryOptimizationSettings());
        }

        // 7. Save Settings
        btnSave.setOnClickListener(v -> {
            String user = etUsername.getText() != null ? etUsername.getText().toString().trim() : "";
            String device = etDeviceId.getText() != null ? etDeviceId.getText().toString().trim() : "";

            int checkedId = rgTheme.getCheckedRadioButtonId();
            int selectedThemeMode;
            if (checkedId == R.id.rb_theme_light) {
                selectedThemeMode = 1;
            } else if (checkedId == R.id.rb_theme_dark) {
                selectedThemeMode = 2;
            } else {
                selectedThemeMode = 0;
            }

            prefs.edit()
                    .putString("username", user)
                    .putString("device_id", device)
                    .putString("last_device_name", device)
                    .putInt("theme_mode", selectedThemeMode)
                    .apply();

            applyTheme(selectedThemeMode);
            Toast.makeText(getContext(), getString(R.string.save_settings), Toast.LENGTH_SHORT).show();
        });

        setupObservers();

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePermissionStatuses();
    }

    private void updatePermissionStatuses() {
        Context context = getContext();
        if (context == null) return;

        // Bluetooth
        boolean btGranted;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            btGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED;
        }
        setPermissionBadge(tvPermBluetoothStatus, btGranted, "Granted", "Missing");

        // Notifications
        boolean notifGranted;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        } else {
            notifGranted = NotificationManagerCompat.from(context).areNotificationsEnabled();
        }
        setPermissionBadge(tvPermNotificationStatus, notifGranted, "Granted", "Disabled");

        // Full Screen Intent (Android 14+)
        boolean fsGranted = true;
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            fsGranted = (nm != null && nm.canUseFullScreenIntent());
        }
        setPermissionBadge(tvPermFullscreenStatus, fsGranted, "Granted", "Restricted");

        // Battery optimization
        boolean batteryExempt = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            batteryExempt = (pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName()));
        }
        setPermissionBadge(tvPermBatteryStatus, batteryExempt, "Exempted", "Optimized (Tap)");
    }

    private void setPermissionBadge(TextView tv, boolean isGood, String goodText, String badText) {
        if (tv == null) return;
        if (isGood) {
            tv.setText(goodText);
            tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_connected));
        } else {
            tv.setText(badText);
            tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.emergency_red));
        }
    }

    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", requireContext().getPackageName(), null));
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private void openAppNotificationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName());
                startActivity(intent);
            } else {
                openAppSettings();
            }
        } catch (Exception e) {
            openAppSettings();
        }
    }

    private void openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
                intent.setData(Uri.fromParts("package", requireContext().getPackageName(), null));
                startActivity(intent);
                return;
            } catch (Exception ignored) {
            }
        }
        openAppSettings();
    }

    private void updateModeLabel(boolean isRescue) {
        if (tvModeName != null) {
            tvModeName.setText(isRescue ? "Rescue Mode Active" : "Trekker Mode Active");
        }
    }

    @SuppressLint("BatteryLife")
    private void openBatteryOptimizationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                startActivity(intent);
            } else {
                Toast.makeText(getContext(), "Battery optimization not restricted on this Android version", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            openAppSettings();
        }
    }

    private void setupObservers() {
        repository.getConnectionState().observe(getViewLifecycleOwner(), state -> updateNodeStatusDisplay());
        repository.getDeviceName().observe(getViewLifecycleOwner(), name -> updateNodeStatusDisplay());
        repository.getNodeStatus().observe(getViewLifecycleOwner(), status -> updateNodeStatusDisplay());
    }

    private void updateNodeStatusDisplay() {
        if (tvSettingsNodeStatus == null) return;

        ConnectionState state = repository.getConnectionState().getValue();
        if (state == ConnectionState.CONNECTED) {
            String nodeName = repository.getDeviceName().getValue();
            if (nodeName == null || nodeName.isEmpty()) nodeName = "Node";

            NodeStatus status = repository.getNodeStatus().getValue();
            String mpu = (status != null && status.getMpu() != null) ? status.getMpu() : "--";
            String gps = (status != null && status.getGps() != null) ? status.getGps() : "--";
            String lora = (status != null && status.getLora() != null) ? status.getLora() : "--";

            String statusText = "Connected: " + nodeName +
                    "\nDiagnostics: MPU=" + mpu + " | GPS=" + gps + " | LoRa=" + lora;
            tvSettingsNodeStatus.setText(statusText);
            tvSettingsNodeStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary));
        } else {
            tvSettingsNodeStatus.setText(getString(R.string.no_module_connected));
            tvSettingsNodeStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
        }
    }

    private void applyTheme(int themeMode) {
        switch (themeMode) {
            case 1:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case 2:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }
}
