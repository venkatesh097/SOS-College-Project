package com.example.loracommunication.ui;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.loracommunication.R;
import com.example.loracommunication.data.Incident;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class IncidentAdapter extends RecyclerView.Adapter<IncidentAdapter.ViewHolder> {

    public interface OnIncidentActionListener {
        void onAcknowledge(Incident incident);
    }

    private List<Incident> incidents = new ArrayList<>();
    private final OnIncidentActionListener listener;

    public IncidentAdapter(OnIncidentActionListener listener) {
        this.listener = listener;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setIncidents(List<Incident> incidents) {
        this.incidents = incidents != null ? incidents : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_incident, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Incident incident = incidents.get(position);
        Context context = holder.itemView.getContext();

        // 1. Badge (SOS / FALL)
        boolean isFall = "FALL".equalsIgnoreCase(incident.getKind());
        holder.tvBadge.setText(isFall ? "FALL" : "SOS");
        int badgeColor = isFall ? 0xFFE65100 : 0xFFD32F2F;
        holder.tvBadge.setBackgroundTintList(ColorStateList.valueOf(badgeColor));

        // 2. Node & Time
        holder.tvNode.setText(incident.getNode().isEmpty() ? "Unknown Node" : incident.getNode());
        CharSequence relativeTime = DateUtils.getRelativeTimeSpanString(
                incident.getReceivedAt(),
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
        );
        holder.tvTime.setText(relativeTime);

        // 3. Acknowledged state styling
        if (incident.isAcknowledged()) {
            holder.cardRoot.setStrokeColor(ContextCompat.getColor(context, R.color.divider_color));
            holder.tvAckStatus.setText("ACKNOWLEDGED");
            holder.tvAckStatus.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
            holder.btnAcknowledge.setVisibility(View.GONE);
        } else {
            holder.cardRoot.setStrokeColor(ContextCompat.getColor(context, R.color.emergency_red));
            holder.tvAckStatus.setText("UNACKNOWLEDGED");
            holder.tvAckStatus.setTextColor(ContextCompat.getColor(context, R.color.emergency_red));
            holder.btnAcknowledge.setVisibility(View.VISIBLE);
            holder.btnAcknowledge.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onAcknowledge(incident);
                }
            });
        }

        // 4. Coordinates
        if (incident.hasGpsFix()) {
            String coordsStr = String.format(Locale.US, "%.5f, %.5f", incident.getLat(), incident.getLon());
            holder.tvCoords.setText(coordsStr);
            holder.btnCopy.setEnabled(true);
            holder.btnMaps.setEnabled(true);

            holder.btnCopy.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Coordinates", coordsStr);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(context, "Coordinates copied to clipboard", Toast.LENGTH_SHORT).show();
                }
            });

            holder.btnMaps.setOnClickListener(v -> {
                Uri gmmIntentUri = Uri.parse("geo:" + incident.getLat() + "," + incident.getLon()
                        + "?q=" + incident.getLat() + "," + incident.getLon() + "(Incident Location)");
                Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
                mapIntent.setPackage("com.google.android.apps.maps");
                if (mapIntent.resolveActivity(context.getPackageManager()) != null) {
                    context.startActivity(mapIntent);
                } else {
                    context.startActivity(new Intent(Intent.ACTION_VIEW, gmmIntentUri));
                }
            });
        } else {
            holder.tvCoords.setText("No GPS fix");
            holder.btnCopy.setEnabled(false);
            holder.btnMaps.setEnabled(false);
            holder.btnCopy.setOnClickListener(null);
            holder.btnMaps.setOnClickListener(null);
        }
    }

    @Override
    public int getItemCount() {
        return incidents.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardRoot;
        TextView tvBadge, tvNode, tvTime, tvAckStatus, tvCoords;
        MaterialButton btnCopy, btnMaps, btnAcknowledge;

        ViewHolder(View itemView) {
            super(itemView);
            cardRoot = itemView.findViewById(R.id.card_incident_root);
            tvBadge = itemView.findViewById(R.id.tv_incident_badge);
            tvNode = itemView.findViewById(R.id.tv_incident_node);
            tvTime = itemView.findViewById(R.id.tv_incident_time);
            tvAckStatus = itemView.findViewById(R.id.tv_incident_ack_status);
            tvCoords = itemView.findViewById(R.id.tv_incident_coords);
            btnCopy = itemView.findViewById(R.id.btn_copy_coords);
            btnMaps = itemView.findViewById(R.id.btn_open_maps);
            btnAcknowledge = itemView.findViewById(R.id.btn_ack_incident);
        }
    }
}
