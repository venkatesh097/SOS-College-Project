package com.example.loracommunication.ui;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.loracommunication.R;
import com.example.loracommunication.data.ConnectionState;
import com.example.loracommunication.data.NodeRepository;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.Set;

public class BluetoothFragment extends Fragment {

    private BluetoothAdapter bluetoothAdapter;
    private DeviceAdapter adapter;
    private NodeRepository repository;
    private SharedPreferences prefs;

    private MaterialCardView cardConnectedDevice;
    private MaterialCardView cardBluetoothDisabled;
    private TextView tvConnectedDeviceName;
    private MaterialButton btnScan;
    private MaterialButton btnEnableBluetooth;
    private MaterialButton btnSystemBtSettings;
    private MaterialSwitch switchFilterNodes;
    private MaterialSwitch switchAutoConnect;

    private boolean isReceiverRegistered = false;

    private final ActivityResultLauncher<Intent> enableBtLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                updateBluetoothStateUI();
                refreshBondedDevices();
            });

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                } else {
                    @SuppressWarnings("deprecation")
                    BluetoothDevice legacyDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    device = legacyDevice;
                }
                if (device != null) {
                    adapter.addDevice(device);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                if (btnScan != null) {
                    btnScan.setText(R.string.stop_scan);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                if (btnScan != null) {
                    btnScan.setText(R.string.scan_devices);
                }
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                updateBluetoothStateUI();
                refreshBondedDevices();
            }
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = NodeRepository.getInstance();
        prefs = requireActivity().getSharedPreferences("lora_prefs", Context.MODE_PRIVATE);

        BluetoothManager bluetoothManager = (BluetoothManager) requireContext().getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }
    }

    @SuppressLint("MissingPermission")
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_bluetooth, container, false);

        RecyclerView rvDevices = view.findViewById(R.id.rv_devices);
        btnScan = view.findViewById(R.id.btn_scan);
        cardConnectedDevice = view.findViewById(R.id.card_connected_device);
        cardBluetoothDisabled = view.findViewById(R.id.card_bluetooth_disabled);
        tvConnectedDeviceName = view.findViewById(R.id.tv_connected_device_name);
        MaterialButton btnDisconnect = view.findViewById(R.id.btn_disconnect);
        btnEnableBluetooth = view.findViewById(R.id.btn_enable_bluetooth);
        btnSystemBtSettings = view.findViewById(R.id.btn_system_bt_settings);
        switchFilterNodes = view.findViewById(R.id.switch_filter_nodes);
        switchAutoConnect = view.findViewById(R.id.switch_auto_connect);

        adapter = new DeviceAdapter(device -> {
            String name = device.getName();
            String address = device.getAddress();
            prefs.edit()
                    .putString("last_device_address", address)
                    .putString("last_device_name", name != null ? name : "Node")
                    .apply();
            repository.connect(device);
        });

        rvDevices.setLayoutManager(new LinearLayoutManager(getContext()));
        rvDevices.setAdapter(adapter);

        // Filter switch
        boolean filterNodes = prefs.getBoolean("filter_nodes_only", true);
        switchFilterNodes.setChecked(filterNodes);
        adapter.setFilterNodeOnly(filterNodes);
        switchFilterNodes.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("filter_nodes_only", isChecked).apply();
            adapter.setFilterNodeOnly(isChecked);
        });

        // Auto-connect switch
        boolean autoConnect = prefs.getBoolean("auto_connect_on_launch", false);
        switchAutoConnect.setChecked(autoConnect);
        switchAutoConnect.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("auto_connect_on_launch", isChecked).apply();
        });

        btnScan.setOnClickListener(v -> toggleDiscovery());
        btnDisconnect.setOnClickListener(v -> repository.disconnect());

        btnEnableBluetooth.setOnClickListener(v -> promptEnableBluetooth());

        btnSystemBtSettings.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(getContext(), "Cannot open Bluetooth settings", Toast.LENGTH_SHORT).show();
            }
        });

        setupObservers();

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        registerBluetoothReceiver();
        updateBluetoothStateUI();
        refreshBondedDevices();
    }

    @Override
    public void onStop() {
        super.onStop();
        unregisterBluetoothReceiver();
        cancelDiscovery();
    }

    private void registerBluetoothReceiver() {
        if (!isReceiverRegistered && getContext() != null) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            requireContext().registerReceiver(bluetoothReceiver, filter);
            isReceiverRegistered = true;
        }
    }

    private void unregisterBluetoothReceiver() {
        if (isReceiverRegistered && getContext() != null) {
            try {
                requireContext().unregisterReceiver(bluetoothReceiver);
            } catch (Exception ignored) {
            }
            isReceiverRegistered = false;
        }
    }

    private void updateBluetoothStateUI() {
        boolean isEnabled = (bluetoothAdapter != null && bluetoothAdapter.isEnabled());
        if (cardBluetoothDisabled != null) {
            cardBluetoothDisabled.setVisibility(isEnabled ? View.GONE : View.VISIBLE);
        }
    }

    @SuppressLint("MissingPermission")
    private void refreshBondedDevices() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            adapter.clearDevices();
            return;
        }

        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices != null) {
            for (BluetoothDevice device : pairedDevices) {
                adapter.addDevice(device);
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void promptEnableBluetooth() {
        if (bluetoothAdapter == null) {
            Toast.makeText(getContext(), "Bluetooth not supported on this device", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBtLauncher.launch(enableBtIntent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    private void setupObservers() {
        repository.getConnectionState().observe(getViewLifecycleOwner(), state -> {
            String name = repository.getDeviceName().getValue();
            updateConnectedDeviceUI(state, name);
        });

        repository.getDeviceName().observe(getViewLifecycleOwner(), name -> {
            ConnectionState state = repository.getConnectionState().getValue();
            updateConnectedDeviceUI(state, name);
        });
    }

    private void updateConnectedDeviceUI(ConnectionState state, String deviceName) {
        if (cardConnectedDevice == null || tvConnectedDeviceName == null) return;

        if (state == ConnectionState.CONNECTED && deviceName != null && !deviceName.isEmpty()) {
            cardConnectedDevice.setVisibility(View.VISIBLE);
            tvConnectedDeviceName.setText(deviceName);
        } else {
            cardConnectedDevice.setVisibility(View.GONE);
        }
    }

    @SuppressLint("MissingPermission")
    private void toggleDiscovery() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            promptEnableBluetooth();
            return;
        }

        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
            if (btnScan != null) btnScan.setText(R.string.scan_devices);
        } else {
            adapter.clearDevices();
            refreshBondedDevices();
            bluetoothAdapter.startDiscovery();
            if (btnScan != null) btnScan.setText(R.string.stop_scan);
        }
    }

    @SuppressLint("MissingPermission")
    private void cancelDiscovery() {
        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
    }
}
