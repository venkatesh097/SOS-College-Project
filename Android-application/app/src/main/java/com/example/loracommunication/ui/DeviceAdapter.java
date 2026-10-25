package com.example.loracommunication.ui;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.loracommunication.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {

    private final List<BluetoothDevice> allDevices = new ArrayList<>();
    private final List<BluetoothDevice> displayedDevices = new ArrayList<>();
    private final OnDeviceClickListener listener;
    private boolean filterNodeOnly = true;

    public interface OnDeviceClickListener {
        void onDeviceClick(BluetoothDevice device);
    }

    public DeviceAdapter(OnDeviceClickListener listener) {
        this.listener = listener;
    }

    @SuppressLint({"MissingPermission", "NotifyDataSetChanged"})
    public void setFilterNodeOnly(boolean filterNodeOnly) {
        this.filterNodeOnly = filterNodeOnly;
        applyFilter();
    }

    public boolean isFilterNodeOnly() {
        return filterNodeOnly;
    }

    @SuppressLint({"MissingPermission", "NotifyDataSetChanged"})
    public void clearDevices() {
        allDevices.clear();
        displayedDevices.clear();
        notifyDataSetChanged();
    }

    @SuppressLint({"MissingPermission", "NotifyDataSetChanged"})
    public void addDevice(BluetoothDevice device) {
        if (!allDevices.contains(device)) {
            String name = device.getName();
            // Prioritize NODE devices
            if (name != null && name.toUpperCase(Locale.ROOT).startsWith("NODE")) {
                allDevices.add(0, device);
            } else {
                allDevices.add(device);
            }
            applyFilter();
        }
    }

    @SuppressLint({"MissingPermission", "NotifyDataSetChanged"})
    private void applyFilter() {
        displayedDevices.clear();
        for (BluetoothDevice device : allDevices) {
            String name = device.getName();
            if (!filterNodeOnly) {
                displayedDevices.add(device);
            } else if (name != null && name.toUpperCase(Locale.ROOT).startsWith("NODE")) {
                displayedDevices.add(device);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_device, parent, false);
        return new ViewHolder(view);
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BluetoothDevice device = displayedDevices.get(position);
        String name = device.getName();
        if (name != null && !name.trim().isEmpty()) {
            holder.tvName.setText(name);
            holder.tvAddress.setText(device.getAddress());
            if (name.equalsIgnoreCase("NODE A") || name.equalsIgnoreCase("NODE B")) {
                holder.tvName.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.emergency_red));
            } else {
                holder.tvName.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.text_primary));
            }
        } else {
            holder.tvName.setText(device.getAddress());
            holder.tvName.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.text_primary));
            holder.tvAddress.setText(R.string.bluetooth_devices);
        }
        holder.itemView.setOnClickListener(v -> listener.onDeviceClick(device));
    }

    @Override
    public int getItemCount() {
        return displayedDevices.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvAddress;

        ViewHolder(View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_device_name);
            tvAddress = itemView.findViewById(R.id.tv_device_address);
        }
    }
}
