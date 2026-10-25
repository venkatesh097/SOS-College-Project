package com.example.loracommunication.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.loracommunication.R;
import com.example.loracommunication.data.AppDatabase;
import com.example.loracommunication.data.Incident;
import com.google.android.material.button.MaterialButton;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IncidentsFragment extends Fragment {

    private IncidentAdapter adapter;
    private AppDatabase db;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private RecyclerView rvIncidents;
    private View layoutEmptyIncidents;
    private LinearLayout containerNodePositions;
    private TextView tvNoPositions;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = AppDatabase.getDatabase(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_incidents, container, false);

        rvIncidents = view.findViewById(R.id.rv_incidents);
        layoutEmptyIncidents = view.findViewById(R.id.layout_empty_incidents);
        containerNodePositions = view.findViewById(R.id.container_node_positions);
        tvNoPositions = view.findViewById(R.id.tv_no_positions);
        MaterialButton btnAckAll = view.findViewById(R.id.btn_ack_all);

        adapter = new IncidentAdapter(incident -> {
            EmergencyAlertManager.getInstance(requireContext()).acknowledgeIncident(incident.getId());
            Toast.makeText(getContext(), "Incident Acknowledged", Toast.LENGTH_SHORT).show();
        });

        rvIncidents.setLayoutManager(new LinearLayoutManager(getContext()));
        rvIncidents.setAdapter(adapter);

        // 1. Observe all incidents (unacknowledged first, then newest)
        db.incidentDao().getAllIncidents().observe(getViewLifecycleOwner(), incidents -> {
            adapter.setIncidents(incidents);
            if (incidents == null || incidents.isEmpty()) {
                layoutEmptyIncidents.setVisibility(View.VISIBLE);
                rvIncidents.setVisibility(View.GONE);
            } else {
                layoutEmptyIncidents.setVisibility(View.GONE);
                rvIncidents.setVisibility(View.VISIBLE);
            }
        });

        // 2. Observe incidents with location to show "Last known positions" per node
        db.incidentDao().getIncidentsWithLocation().observe(getViewLifecycleOwner(), this::updateLastKnownPositions);

        // 3. Acknowledge All
        btnAckAll.setOnClickListener(v -> {
            EmergencyAlertManager.getInstance(requireContext()).acknowledgeAll();
            Toast.makeText(getContext(), "All Incidents Acknowledged", Toast.LENGTH_SHORT).show();
        });

        return view;
    }

    private void updateLastKnownPositions(List<Incident> locationIncidents) {
        if (containerNodePositions == null) return;
        containerNodePositions.removeAllViews();

        if (locationIncidents == null || locationIncidents.isEmpty()) {
            if (tvNoPositions != null) {
                containerNodePositions.addView(tvNoPositions);
            }
            return;
        }

        // Group by node name, picking the newest one per node
        Map<String, Incident> latestPerNode = new HashMap<>();
        for (Incident inc : locationIncidents) {
            String node = inc.getNode();
            if (node == null || node.trim().isEmpty()) node = "Unknown Node";
            if (!latestPerNode.containsKey(node)) {
                latestPerNode.put(node, inc);
            }
        }

        for (Map.Entry<String, Incident> entry : latestPerNode.entrySet()) {
            Incident inc = entry.getValue();
            View rowView = LayoutInflater.from(getContext()).inflate(R.layout.item_device, containerNodePositions, false);

            TextView tvName = rowView.findViewById(R.id.tv_device_name);
            TextView tvAddress = rowView.findViewById(R.id.tv_device_address);

            CharSequence timeAgo = DateUtils.getRelativeTimeSpanString(
                    inc.getReceivedAt(),
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
            );

            tvName.setText(String.format(Locale.US, "%s: %.5f, %.5f", entry.getKey(), inc.getLat(), inc.getLon()));
            tvAddress.setText(String.format(Locale.US, "Last heard %s • Tap to open in Maps", timeAgo));

            rowView.setOnClickListener(v -> {
                Uri gmmIntentUri = Uri.parse("geo:" + inc.getLat() + "," + inc.getLon()
                        + "?q=" + inc.getLat() + "," + inc.getLon() + "(" + entry.getKey() + " Last Known Position)");
                Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
                mapIntent.setPackage("com.google.android.apps.maps");
                if (mapIntent.resolveActivity(requireContext().getPackageManager()) != null) {
                    startActivity(mapIntent);
                } else {
                    startActivity(new Intent(Intent.ACTION_VIEW, gmmIntentUri));
                }
            });

            containerNodePositions.addView(rowView);
        }
    }
}
