package com.example.loracommunication.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.loracommunication.R;
import com.example.loracommunication.bluetooth.BluetoothService;
import com.example.loracommunication.data.AppDatabase;
import com.example.loracommunication.data.Message;
import com.google.android.material.button.MaterialButton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MessageFragment extends Fragment {

    private MessageAdapter adapter;
    private BluetoothService bluetoothService;
    private boolean isBound = false;
    private AppDatabase db;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothService.LocalBinder binder = (BluetoothService.LocalBinder) service;
            bluetoothService = binder.getService();
            isBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = AppDatabase.getDatabase(requireContext());
        Intent intent = new Intent(getContext(), BluetoothService.class);
        requireContext().bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_message, container, false);

        RecyclerView rvMessages = view.findViewById(R.id.rv_messages);
        EditText etMessage = view.findViewById(R.id.et_message);
        MaterialButton btnSend = view.findViewById(R.id.btn_send);

        adapter = new MessageAdapter();
        rvMessages.setLayoutManager(new LinearLayoutManager(getContext()));
        rvMessages.setAdapter(adapter);

        db.messageDao().getAllMessages().observe(getViewLifecycleOwner(), messages -> {
            adapter.setMessages(messages);
            rvMessages.scrollToPosition(0);
        });

        btnSend.setOnClickListener(v -> {
            String text = etMessage.getText().toString().trim();
            if (!text.isEmpty() && isBound) {
                sendMessage(text);
                etMessage.setText("");
            }
        });

        return view;
    }

    private void sendMessage(String text) {
        Message message = new Message("Me", text, System.currentTimeMillis(), 0, 0, false, true);
        executor.execute(() -> db.messageDao().insert(message));
        bluetoothService.write(text.getBytes());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (isBound) {
            requireContext().unbindService(connection);
            isBound = false;
        }
    }
}
