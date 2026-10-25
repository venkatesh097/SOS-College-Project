package com.example.loracommunication.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.loracommunication.R;
import com.example.loracommunication.data.NodeRepository;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class SimulatorBottomSheet extends BottomSheetDialogFragment {

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_simulator, container, false);

        NodeRepository repo = NodeRepository.getInstance();

        TextInputEditText etCustomLine = view.findViewById(R.id.et_custom_line);
        MaterialButton btnInjectCustom = view.findViewById(R.id.btn_inject_custom);

        // Lifecycle & Ping
        MaterialButton btnReady = view.findViewById(R.id.btn_sim_ready);
        MaterialButton btnPong = view.findViewById(R.id.btn_sim_pong);

        // Status
        MaterialButton btnStatusAllOk = view.findViewById(R.id.btn_sim_status_all_ok);
        MaterialButton btnStatusNoGps = view.findViewById(R.id.btn_sim_status_no_gps);
        MaterialButton btnStatusFallOff = view.findViewById(R.id.btn_sim_status_fall_off);

        // Messages
        MaterialButton btnRxMsg = view.findViewById(R.id.btn_sim_rx_msg);
        MaterialButton btnRxMsgNoGps = view.findViewById(R.id.btn_sim_rx_msg_invalid_gps);
        MaterialButton btnTxMsg = view.findViewById(R.id.btn_sim_tx_msg);

        // Emergencies
        MaterialButton btnRxSos = view.findViewById(R.id.btn_sim_rx_sos);
        MaterialButton btnRxSosNoGps = view.findViewById(R.id.btn_sim_rx_sos_no_gps);
        MaterialButton btnRxFall = view.findViewById(R.id.btn_sim_rx_fall);

        // Fall Sequences
        MaterialButton btnFallSeqCancel = view.findViewById(R.id.btn_sim_fall_monitoring_cancel);
        MaterialButton btnFallSeqTrigger = view.findViewById(R.id.btn_sim_fall_monitoring_trigger);

        // Phone GPS Fallback & LOC
        MaterialButton btnStatusSrcGps = view.findViewById(R.id.btn_sim_status_src_gps);
        MaterialButton btnStatusSrcPhone = view.findViewById(R.id.btn_sim_status_src_phone);
        MaterialButton btnStatusSrcNone = view.findViewById(R.id.btn_sim_status_src_none);
        MaterialButton btnLocOk = view.findViewById(R.id.btn_sim_loc_ok);
        MaterialButton btnErrorBadLoc = view.findViewById(R.id.btn_sim_error_bad_loc);

        // Errors & Duplicates
        MaterialButton btnErrorUnknown = view.findViewById(R.id.btn_sim_error_unknown);
        MaterialButton btnErrorEmpty = view.findViewById(R.id.btn_sim_error_empty);
        MaterialButton btnDuplicate = view.findViewById(R.id.btn_sim_duplicate_packet);

        // Custom Injection
        btnInjectCustom.setOnClickListener(v -> {
            String line = etCustomLine.getText() != null ? etCustomLine.getText().toString().trim() : "";
            if (!line.isEmpty()) {
                repo.simulateLine(line);
                showToast("Injected: " + line);
            }
        });

        // Lifecycle
        btnReady.setOnClickListener(v -> {
            repo.simulateLine("READY|NODE B");
            showToast("Injected: READY|NODE B");
        });

        btnPong.setOnClickListener(v -> {
            repo.simulateLine("PONG|NODE B");
            showToast("Injected: PONG|NODE B");
        });

        // Status Combos
        btnStatusAllOk.setOnClickListener(v -> {
            repo.simulateLine("STATUS|NODE B|MPU=READY|GPS=FIX|LORA=READY");
            showToast("Injected: STATUS All OK");
        });

        btnStatusNoGps.setOnClickListener(v -> {
            repo.simulateLine("STATUS|NODE B|MPU=READY|GPS=NO_FIX|LORA=READY");
            showToast("Injected: STATUS No GPS");
        });

        btnStatusFallOff.setOnClickListener(v -> {
            repo.simulateLine("STATUS|NODE B|MPU=NOT_FOUND|GPS=FIX|LORA=READY");
            showToast("Injected: STATUS Fall Detection Off");
        });

        // Chat Messages
        btnRxMsg.setOnClickListener(v -> {
            long pid = System.currentTimeMillis() % 10000;
            repo.simulateLine("RX|MSG|NODE B|" + pid + "|13.0827|80.2707|Hello from simulated Node B!");
            showToast("Injected: RX MSG with GPS");
        });

        btnRxMsgNoGps.setOnClickListener(v -> {
            long pid = System.currentTimeMillis() % 10000;
            repo.simulateLine("RX|MSG|NODE B|" + pid + "|INVALID|INVALID|No GPS fix on Node B");
            showToast("Injected: RX MSG without GPS");
        });

        btnTxMsg.setOnClickListener(v -> {
            long pid = System.currentTimeMillis() % 10000;
            repo.simulateLine("TX|MSG|NODE A|" + pid + "|13.0827|80.2707|Locally transmitted message");
            showToast("Injected: TX MSG (Own echo)");
        });

        // Emergencies
        btnRxSos.setOnClickListener(v -> {
            long pid = System.currentTimeMillis() % 10000;
            repo.simulateLine("RX|SOS|NODE B|" + pid + "|13.0827|80.2707");
            showToast("Injected: RX SOS with GPS");
        });

        btnRxSosNoGps.setOnClickListener(v -> {
            long pid = System.currentTimeMillis() % 10000;
            repo.simulateLine("RX|SOS|NODE B|" + pid + "|INVALID|INVALID");
            showToast("Injected: RX SOS without GPS");
        });

        btnRxFall.setOnClickListener(v -> {
            long pid = System.currentTimeMillis() % 10000;
            repo.simulateLine("RX|FALL|NODE B|" + pid + "|13.0827|80.2707");
            showToast("Injected: RX FALL Alarm");
        });

        // Fall Sequences
        btnFallSeqCancel.setOnClickListener(v -> {
            repo.simulateLine("FALL_MONITORING|NODE B");
            showToast("Sequence started: FALL_MONITORING (cancels in 3s)");
            handler.postDelayed(() -> {
                repo.simulateLine("FALL_CANCELLED|NODE B");
                showToast("Sequence step: FALL_CANCELLED");
            }, 3000);
        });

        btnFallSeqTrigger.setOnClickListener(v -> {
            repo.simulateLine("FALL_MONITORING|NODE B");
            showToast("Sequence started: FALL_MONITORING (SOS in 3s)");
            handler.postDelayed(() -> {
                long pid = System.currentTimeMillis() % 10000;
                repo.simulateLine("RX|FALL|NODE B|" + pid + "|13.0827|80.2707");
                showToast("Sequence step: RX FALL Triggered");
            }, 3000);
        });

        // Phone GPS Fallback & LOC
        btnStatusSrcGps.setOnClickListener(v -> {
            repo.simulateLine("STATUS|NODE B|MPU=READY|GPS=FIX|LORA=READY|SRC=GPS");
            showToast("Injected: STATUS with SRC=GPS");
        });

        btnStatusSrcPhone.setOnClickListener(v -> {
            repo.simulateLine("STATUS|NODE B|MPU=READY|GPS=NO_FIX|LORA=READY|SRC=PHONE");
            showToast("Injected: STATUS with SRC=PHONE");
        });

        btnStatusSrcNone.setOnClickListener(v -> {
            repo.simulateLine("STATUS|NODE B|MPU=READY|GPS=NO_FIX|LORA=READY|SRC=NONE");
            showToast("Injected: STATUS with SRC=NONE");
        });

        btnLocOk.setOnClickListener(v -> {
            repo.simulateLine("LOC_OK|NODE B");
            showToast("Injected: LOC_OK|NODE B");
        });

        btnErrorBadLoc.setOnClickListener(v -> {
            repo.simulateLine("ERROR|BAD_LOC");
            showToast("Injected: ERROR|BAD_LOC");
        });

        // Errors & Duplicates
        btnErrorUnknown.setOnClickListener(v -> {
            repo.simulateLine("ERROR|UNKNOWN_COMMAND");
            showToast("Injected: ERROR|UNKNOWN_COMMAND");
        });

        btnErrorEmpty.setOnClickListener(v -> {
            repo.simulateLine("ERROR|EMPTY_MESSAGE");
            showToast("Injected: ERROR|EMPTY_MESSAGE");
        });

        btnDuplicate.setOnClickListener(v -> {
            long fixedPid = 9999;
            repo.simulateLine("RX|MSG|NODE B|" + fixedPid + "|13.0827|80.2707|Duplicate packet test");
            repo.simulateLine("RX|MSG|NODE B|" + fixedPid + "|13.0827|80.2707|Duplicate packet test");
            repo.simulateLine("RX|SOS|NODE B|" + fixedPid + "|13.0827|80.2707");
            repo.simulateLine("RX|SOS|NODE B|" + fixedPid + "|13.0827|80.2707");
            showToast("Injected: Duplicate MSG & SOS (PID " + fixedPid + ") twice");
        });

        return view;
    }

    private void showToast(String message) {
        if (getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }
}
