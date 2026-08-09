package com.example.loracommunication.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.loracommunication.R;
import com.example.loracommunication.data.AppDatabase;
import com.google.android.material.button.MaterialButton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HistoryFragment extends Fragment {

    private MessageAdapter adapter;
    private AppDatabase db;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_history, container, false);

        db = AppDatabase.getDatabase(requireContext());
        RecyclerView rvHistory = view.findViewById(R.id.rv_history);
        MaterialButton btnClear = view.findViewById(R.id.btn_clear_history);

        adapter = new MessageAdapter();
        rvHistory.setLayoutManager(new LinearLayoutManager(getContext()));
        rvHistory.setAdapter(adapter);

        db.messageDao().getAllMessages().observe(getViewLifecycleOwner(), messages -> adapter.setMessages(messages));

        btnClear.setOnClickListener(v -> executor.execute(() -> db.messageDao().deleteAll()));

        return view;
    }
}
