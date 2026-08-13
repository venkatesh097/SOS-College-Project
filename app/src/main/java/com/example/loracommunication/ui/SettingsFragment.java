package com.example.loracommunication.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.loracommunication.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class SettingsFragment extends Fragment {

    private TextInputEditText etUsername, etDeviceId;
    private SharedPreferences prefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        etUsername = view.findViewById(R.id.et_username);
        etDeviceId = view.findViewById(R.id.et_device_id);
        MaterialButton btnSave = view.findViewById(R.id.btn_save);

        prefs = requireActivity().getSharedPreferences("lora_prefs", Context.MODE_PRIVATE);

        // Load saved settings
        etUsername.setText(prefs.getString("username", "User1"));
        etDeviceId.setText(prefs.getString("device_id", "LORA-01"));

        btnSave.setOnClickListener(v -> {
            String user = etUsername.getText().toString();
            String device = etDeviceId.getText().toString();

            prefs.edit()
                    .putString("username", user)
                    .putString("device_id", device)
                    .apply();

            Toast.makeText(getContext(), "Settings Saved", Toast.LENGTH_SHORT).show();
        });

        return view;
    }
}
