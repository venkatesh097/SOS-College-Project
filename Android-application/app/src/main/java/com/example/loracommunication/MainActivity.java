package com.example.loracommunication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.NavGraph;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.example.loracommunication.bluetooth.BluetoothService;
import com.example.loracommunication.data.AppDatabase;
import com.example.loracommunication.data.ConnectionState;
import com.example.loracommunication.data.NodeRepository;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private boolean isBound = false;
    private BluetoothAdapter bluetoothAdapter;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            isBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applySavedTheme();
        super.onCreate(savedInstanceState);

        NodeRepository.getInstance().init(this);

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        // Enable Edge-to-Edge
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_main);

        View rootView = findViewById(R.id.nav_host_fragment).getRootView();
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(insets.left, insets.top, insets.right, 0);

            BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
            bottomNav.setPadding(0, 0, 0, insets.bottom);

            return WindowInsetsCompat.CONSUMED;
        });

        setupNavigation();
        checkAndRequestPermissions();

        // Start and bind BluetoothService
        Intent serviceIntent = new Intent(this, BluetoothService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        checkBluetoothEnabled();
        attemptAutoConnect();
    }

    private void applySavedTheme() {
        SharedPreferences prefs = getSharedPreferences("lora_prefs", MODE_PRIVATE);
        int themeMode = prefs.getInt("theme_mode", 0);
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

    private void setupNavigation() {
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment != null) {
            NavController navController = navHostFragment.getNavController();
            BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);

            // Phase 4: Configure start destination based on Mode (Trekker vs Rescue)
            SharedPreferences prefs = getSharedPreferences("lora_prefs", MODE_PRIVATE);
            int mode = prefs.getInt("mode", 0); // 0 = Trekker, 1 = Rescue
            NavGraph navGraph = navController.getNavInflater().inflate(R.navigation.nav_graph);
            if (mode == 1) {
                navGraph.setStartDestination(R.id.incidentsFragment);
            } else {
                navGraph.setStartDestination(R.id.homeFragment);
            }
            navController.setGraph(navGraph);

            NavigationUI.setupWithNavController(bottomNav, navController);

            // Badge for unacknowledged incidents on bottom navigation
            AppDatabase.getDatabase(this)
                    .incidentDao().getUnacknowledgedCount()
                    .observe(this, count -> {
                        com.google.android.material.badge.BadgeDrawable badge =
                                bottomNav.getOrCreateBadge(R.id.incidentsFragment);
                        if (count != null && count > 0) {
                            badge.setVisible(true);
                            badge.setNumber(count);
                            badge.setBackgroundColor(ContextCompat.getColor(this, R.color.emergency_red));
                        } else {
                            badge.setVisible(false);
                        }
                    });
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNavigationIntent(intent);
    }

    private void handleNavigationIntent(Intent intent) {
        if (intent != null && "message".equals(intent.getStringExtra("navigate_to"))) {
            BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
            if (bottomNav != null) {
                bottomNav.setSelectedItemId(R.id.messageFragment);
            }
        }
    }

    private void checkAndRequestPermissions() {
        String[] permissions = getRequiredPermissions();
        if (!hasPermissions(permissions)) {
            SharedPreferences prefs = getSharedPreferences("lora_prefs", MODE_PRIVATE);
            boolean explanationShown = prefs.getBoolean("perm_explanation_shown", false);

            if (!explanationShown) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Permissions Required")
                        .setMessage("This emergency application requires:\n\n"
                                + "• Bluetooth: To communicate with the ESP32 LoRa radio hardware.\n"
                                + "• Notifications: To trigger critical alarms for incoming SOS and Fall alerts even when the screen is off.\n"
                                + "• Location: Used only when the node has no GPS fix, to include your position in SOS alerts.\n\n"
                                + "The app remains operational even if location permission is declined.")
                        .setPositiveButton("Continue", (dialog, which) -> {
                            prefs.edit().putBoolean("perm_explanation_shown", true).apply();
                            ActivityCompat.requestPermissions(MainActivity.this, permissions, PERMISSION_REQUEST_CODE);
                        })
                        .setNegativeButton("Later", null)
                        .show();
            } else {
                ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
            }
        }
    }

    private String[] getRequiredPermissions() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            perms.add(Manifest.permission.BLUETOOTH);
            perms.add(Manifest.permission.BLUETOOTH_ADMIN);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        return perms.toArray(new String[0]);
    }

    private boolean hasPermissions(String[] permissions) {
        for (String permission : permissions) {
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void checkBluetoothEnabled() {
        if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Bluetooth is disabled. Enable it to connect to LoRa nodes.", Toast.LENGTH_LONG).show();
        }
    }

    @SuppressLint("MissingPermission")
    private void attemptAutoConnect() {
        SharedPreferences prefs = getSharedPreferences("lora_prefs", MODE_PRIVATE);
        boolean autoConnect = prefs.getBoolean("auto_connect_on_launch", false);
        String lastAddress = prefs.getString("last_device_address", null);

        if (autoConnect && lastAddress != null && bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            NodeRepository repo = NodeRepository.getInstance();
            if (repo.getConnectionState().getValue() == ConnectionState.NONE) {
                try {
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(lastAddress);
                    repo.connect(device);
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            attemptAutoConnect();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            try {
                unbindService(serviceConnection);
            } catch (Exception ignored) {
            }
            isBound = false;
        }
    }
}
