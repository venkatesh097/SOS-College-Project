package com.example.loracommunication.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.loracommunication.R;
import com.example.loracommunication.data.AppDatabase;
import com.example.loracommunication.data.ConnectionState;
import com.example.loracommunication.data.NodeRepository;
import com.example.loracommunication.protocol.LoraProtocol;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputLayout;

public class MessageFragment extends Fragment {

    private MessageAdapter adapter;
    private NodeRepository repository;
    private AppDatabase db;

    private TextView tvConnectedStatus;
    private TextInputLayout tilMessage;
    private EditText etMessage;
    private MaterialButton btnSend;
    private ChipGroup chipGroupQuickReplies;

    private static final String[] TREKKER_QUICK_REPLIES = {
            "All OK", "Need help", "Injured", "Need water", "Lost, returning", "Resting"
    };

    private static final String[] RESCUE_QUICK_REPLIES = {
            "Help is on the way", "Stay where you are", "Send status", "Share your condition"
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = NodeRepository.getInstance();
        db = AppDatabase.getDatabase(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_message, container, false);

        RecyclerView rvMessages = view.findViewById(R.id.rv_messages);
        tilMessage = view.findViewById(R.id.til_message);
        etMessage = view.findViewById(R.id.et_message);
        btnSend = view.findViewById(R.id.btn_send);
        tvConnectedStatus = view.findViewById(R.id.tv_connected_status);
        chipGroupQuickReplies = view.findViewById(R.id.chip_group_quick_replies);

        adapter = new MessageAdapter(message -> {
            repository.retryMessage(message, (success, error) -> {
                if (!success && error != null) {
                    Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show();
                }
            });
        });
        rvMessages.setLayoutManager(new LinearLayoutManager(getContext()));
        rvMessages.setAdapter(adapter);

        db.messageDao().getChatMessages().observe(getViewLifecycleOwner(), messages -> {
            adapter.setMessages(messages);
            if (messages != null && !messages.isEmpty()) {
                rvMessages.scrollToPosition(messages.size() - 1);
            }
        });

        btnSend.setOnClickListener(v -> submitMessage());

        etMessage.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitMessage();
                return true;
            }
            return false;
        });

        setupQuickReplies();
        setupObservers();

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh quick replies in case Rescue/Trekker mode changed in Settings
        setupQuickReplies();
    }

    private void setupQuickReplies() {
        if (chipGroupQuickReplies == null) return;
        chipGroupQuickReplies.removeAllViews();

        SharedPreferences prefs = requireContext().getSharedPreferences("lora_prefs", Context.MODE_PRIVATE);
        int mode = prefs.getInt("mode", 0); // 0 = Trekker, 1 = Rescue
        String[] replies = (mode == 1) ? RESCUE_QUICK_REPLIES : TREKKER_QUICK_REPLIES;

        boolean isConnected = repository.getConnectionState().getValue() == ConnectionState.CONNECTED;

        for (String text : replies) {
            Chip chip = new Chip(requireContext());
            chip.setText(text);
            chip.setClickable(true);
            chip.setCheckable(false);
            chip.setEnabled(isConnected);
            chip.setOnClickListener(v -> sendMessage(text));
            chipGroupQuickReplies.addView(chip);
        }
    }

    private void setupObservers() {
        repository.getConnectionState().observe(getViewLifecycleOwner(), state -> {
            String name = repository.getDeviceName().getValue();
            updateStatusHeader(state, name);
            updateInputState(state == ConnectionState.CONNECTED);
        });

        repository.getDeviceName().observe(getViewLifecycleOwner(), name -> {
            ConnectionState state = repository.getConnectionState().getValue();
            updateStatusHeader(state, name);
        });

        repository.getLastError().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                Toast.makeText(getContext(), getString(R.string.command_error, error), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateInputState(boolean isConnected) {
        if (tilMessage != null) tilMessage.setEnabled(isConnected);
        if (etMessage != null) etMessage.setEnabled(isConnected);
        if (btnSend != null) btnSend.setEnabled(isConnected);

        if (chipGroupQuickReplies != null) {
            for (int i = 0; i < chipGroupQuickReplies.getChildCount(); i++) {
                chipGroupQuickReplies.getChildAt(i).setEnabled(isConnected);
            }
        }
    }

    private void updateStatusHeader(ConnectionState state, String deviceName) {
        if (tvConnectedStatus == null) return;

        if (state == ConnectionState.CONNECTED) {
            String name = (deviceName != null && !deviceName.isEmpty()) ? deviceName : "Node";
            tvConnectedStatus.setText(getString(R.string.connection_status, name));
            tvConnectedStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_connected));
        } else if (state == ConnectionState.CONNECTING) {
            tvConnectedStatus.setText(getString(R.string.connection_status, getString(R.string.connecting)));
            tvConnectedStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_connecting));
        } else {
            tvConnectedStatus.setText(getString(R.string.no_module_connected));
            tvConnectedStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_disconnected));
        }
    }

    private void submitMessage() {
        if (etMessage == null) return;
        String rawText = etMessage.getText() != null ? etMessage.getText().toString().trim() : "";
        if (rawText.isEmpty()) {
            return;
        }

        sendMessage(rawText);
        etMessage.setText("");
    }

    private void sendMessage(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) return;

        // Protocol validation: text non-empty, no newline, total command <= 200 chars ("MSG|" is 4 chars)
        String text = rawText.replace("\n", " ").replace("\r", " ").trim();
        if (text.isEmpty()) {
            return;
        }

        if (text.length() > LoraProtocol.MAX_MSG_TEXT_LENGTH) {
            text = text.substring(0, LoraProtocol.MAX_MSG_TEXT_LENGTH);
        }

        if (repository.getConnectionState().getValue() != ConnectionState.CONNECTED) {
            Toast.makeText(getContext(), getString(R.string.not_connected_hint), Toast.LENGTH_SHORT).show();
            return;
        }

        repository.sendMsg(text, (success, error) -> {
            if (!success && error != null) {
                Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
