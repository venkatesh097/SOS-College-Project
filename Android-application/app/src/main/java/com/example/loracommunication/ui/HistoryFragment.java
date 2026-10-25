package com.example.loracommunication.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.loracommunication.R;
import com.example.loracommunication.data.AppDatabase;
import com.example.loracommunication.data.Message;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HistoryFragment extends Fragment {

    private MessageAdapter adapter;
    private AppDatabase db;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView tvEmptyHistory;
    private RecyclerView rvHistory;
    private ChipGroup chipGroupFilters;

    private LiveData<List<Message>> currentLiveData;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_history, container, false);

        db = AppDatabase.getDatabase(requireContext());
        rvHistory = view.findViewById(R.id.rv_history);
        tvEmptyHistory = view.findViewById(R.id.tv_empty_history);
        MaterialButton btnClear = view.findViewById(R.id.btn_clear_history);
        MaterialButton btnExport = view.findViewById(R.id.btn_export_history);
        chipGroupFilters = view.findViewById(R.id.chip_group_filters);

        adapter = new MessageAdapter();
        rvHistory.setLayoutManager(new LinearLayoutManager(getContext()));
        rvHistory.setAdapter(adapter);

        setupFilters();

        btnClear.setOnClickListener(v -> confirmClearHistory());
        btnExport.setOnClickListener(v -> exportHistoryToCsv());

        return view;
    }

    private void setupFilters() {
        // Default to "All"
        loadHistory(null);

        chipGroupFilters.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int checkedId = checkedIds.get(0);
            if (checkedId == R.id.chip_filter_msg) {
                loadHistory("MSG");
            } else if (checkedId == R.id.chip_filter_sos) {
                loadHistory("SOS");
            } else if (checkedId == R.id.chip_filter_fall) {
                loadHistory("FALL");
            } else {
                loadHistory(null); // All
            }
        });
    }

    private void loadHistory(@Nullable String kind) {
        if (currentLiveData != null) {
            currentLiveData.removeObservers(getViewLifecycleOwner());
        }

        if (kind == null) {
            currentLiveData = db.messageDao().getAllMessages();
        } else {
            currentLiveData = db.messageDao().getMessagesByKind(kind);
        }

        currentLiveData.observe(getViewLifecycleOwner(), messages -> {
            adapter.setMessages(messages);
            if (messages == null || messages.isEmpty()) {
                tvEmptyHistory.setVisibility(View.VISIBLE);
                rvHistory.setVisibility(View.GONE);
            } else {
                tvEmptyHistory.setVisibility(View.GONE);
                rvHistory.setVisibility(View.VISIBLE);
            }
        });
    }

    private void confirmClearHistory() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.clear_all)
                .setMessage("Are you sure you want to clear all message history?")
                .setPositiveButton("Clear", (dialog, which) -> executor.execute(() -> db.messageDao().deleteAll()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void exportHistoryToCsv() {
        executor.execute(() -> {
            try {
                List<Message> messages = db.messageDao().getAllMessagesSync();
                if (messages == null || messages.isEmpty()) {
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(getContext(), "No messages to export", Toast.LENGTH_SHORT).show());
                    return;
                }

                StringBuilder csv = new StringBuilder();
                csv.append("ID,Timestamp,Date,Direction,Node,PacketId,Kind,Latitude,Longitude,SendState,Message\n");
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

                for (Message msg : messages) {
                    csv.append(msg.getId()).append(",");
                    csv.append(msg.getTimestamp()).append(",");
                    csv.append(sdf.format(new Date(msg.getTimestamp()))).append(",");
                    csv.append(escapeCsv(msg.getDirection())).append(",");
                    csv.append(escapeCsv(msg.getNode())).append(",");
                    csv.append(msg.getPacketId() != null ? msg.getPacketId() : "").append(",");
                    csv.append(escapeCsv(msg.getKind())).append(",");
                    csv.append(msg.getLatitude() != null ? msg.getLatitude() : "").append(",");
                    csv.append(msg.getLongitude() != null ? msg.getLongitude() : "").append(",");
                    csv.append(escapeCsv(msg.getSendState())).append(",");
                    csv.append(escapeCsv(msg.getMessage())).append("\n");
                }

                File exportDir = new File(requireContext().getCacheDir(), "exports");
                if (!exportDir.exists()) {
                    exportDir.mkdirs();
                }
                File csvFile = new File(exportDir, "lora_history_" + System.currentTimeMillis() + ".csv");
                try (FileOutputStream fos = new FileOutputStream(csvFile)) {
                    fos.write(csv.toString().getBytes(StandardCharsets.UTF_8));
                }

                Uri uri = FileProvider.getUriForFile(
                        requireContext(),
                        requireContext().getPackageName() + ".fileprovider",
                        csvFile);

                requireActivity().runOnUiThread(() -> {
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/csv");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, "LoRa Emergency Messenger History Export");
                    shareIntent.putExtra(Intent.EXTRA_TEXT, "Exported " + messages.size() + " LoRa message records.");
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(shareIntent, "Share CSV History"));
                });

            } catch (Exception e) {
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(getContext(), "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
