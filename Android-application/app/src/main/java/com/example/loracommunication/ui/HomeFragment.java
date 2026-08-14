package com.example.loracommunication.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.example.loracommunication.R;
import com.example.loracommunication.bluetooth.BluetoothService;
import com.example.loracommunication.data.AppDatabase;
import com.example.loracommunication.data.Message;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.material.button.MaterialButton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomeFragment extends Fragment {

    private BluetoothService bluetoothService;
    private boolean isBound = false;
    private FusedLocationProviderClient fusedLocationClient;
    private AppDatabase db;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    private TextView tvStatus, tvDeviceInfo;

    private final BroadcastReceiver btStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothService.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int stateOrdinal = intent.getIntExtra(BluetoothService.EXTRA_STATE, 0);
                String deviceName = intent.getStringExtra(BluetoothService.EXTRA_DEVICE_NAME);
                updateUI(BluetoothService.State.values()[stateOrdinal], deviceName);
            }
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothService.LocalBinder binder = (BluetoothService.LocalBinder) service;
            bluetoothService = binder.getService();
            isBound = true;
            updateUI(bluetoothService.getState(), bluetoothService.getConnectedDeviceName());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());
        db = AppDatabase.getDatabase(requireContext());
        
        Intent intent = new Intent(getContext(), BluetoothService.class);
        requireContext().bindService(intent, connection, Context.BIND_AUTO_CREATE);
        
        IntentFilter filter = new IntentFilter(BluetoothService.ACTION_STATE_CHANGED);
        ContextCompat.registerReceiver(requireContext(), btStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        MaterialButton btnSos = view.findViewById(R.id.btn_sos);
        MaterialButton btnConnect = view.findViewById(R.id.btn_connect_bt);
        tvStatus = view.findViewById(R.id.tv_status);
        tvDeviceInfo = view.findViewById(R.id.tv_device_info);

        btnSos.setOnClickListener(v -> triggerSOS());

        btnConnect.setOnClickListener(v -> 
            Navigation.findNavController(view).navigate(R.id.action_homeFragment_to_bluetoothFragment)
        );

        return view;
    }

    private void updateUI(BluetoothService.State state, String deviceName) {
        if (tvStatus == null || tvDeviceInfo == null) return;

        switch (state) {
            case CONNECTED:
                tvStatus.setText(getString(R.string.connection_status, getString(R.string.connected)));
                tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_connected));
                tvDeviceInfo.setText("Module: " + (deviceName != null ? deviceName : "Unknown"));
                tvDeviceInfo.setTextColor(ContextCompat.getColor(requireContext(), R.color.black));
                break;
            case CONNECTING:
                tvStatus.setText(getString(R.string.connection_status, getString(R.string.connecting)));
                tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_connecting));
                tvDeviceInfo.setText("Searching for " + (deviceName != null ? deviceName : "device"));
                break;
            case NONE:
            default:
                tvStatus.setText(getString(R.string.connection_status, getString(R.string.disconnected)));
                tvStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_disconnected));
                tvDeviceInfo.setText("No module connected");
                break;
        }
    }

    private void triggerSOS() {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(getContext(), "Location permission required", Toast.LENGTH_SHORT).show();
            return;
        }

        fusedLocationClient.getLastLocation().addOnSuccessListener(requireActivity(), location -> {
            double lat = 0, lon = 0;
            if (location != null) {
                lat = location.getLatitude();
                lon = location.getLongitude();
            }
            sendSOSPayload(lat, lon);
        });
    }

    private void sendSOSPayload(double lat, double lon) {
        String payload = String.format("SOS! Lat:%.6f, Lon:%.6f", lat, lon);
        if (isBound) {
            bluetoothService.write(payload.getBytes());
            Message msg = new Message("Me", payload, System.currentTimeMillis(), lat, lon, true, true);
            executor.execute(() -> db.messageDao().insert(msg));
            Toast.makeText(getContext(), "SOS Sent!", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(getContext(), "Not connected to LoRa device", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (isBound) {
            requireContext().unbindService(connection);
            isBound = false;
        }
        requireContext().unregisterReceiver(btStateReceiver);
    }
}
