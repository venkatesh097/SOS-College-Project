package com.example.loracommunication.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.loracommunication.R;
import com.example.loracommunication.data.Message;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.ViewHolder> {

    public interface OnMessageRetryListener {
        void onRetry(Message message);
    }

    private List<Message> messages = new ArrayList<>();
    private OnMessageRetryListener retryListener;

    public MessageAdapter() {
        this(null);
    }

    public MessageAdapter(OnMessageRetryListener retryListener) {
        this.retryListener = retryListener;
    }

    public void setOnMessageRetryListener(OnMessageRetryListener retryListener) {
        this.retryListener = retryListener;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setMessages(List<Message> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Message message = messages.get(position);

        String sender = message.getSenderId();
        if (message.isSent()) {
            holder.tvSender.setText(sender.isEmpty() ? "Me" : sender + " (You)");
        } else {
            holder.tvSender.setText(sender.isEmpty() ? "Remote Node" : sender);
        }

        holder.tvContent.setText(message.getContent());

        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(message.getTimestamp()));
        holder.tvTimestamp.setText(time);

        if (message.isSos()) {
            holder.tvSosBadge.setVisibility(View.VISIBLE);
            if ("FALL".equalsIgnoreCase(message.getPacketType())) {
                holder.tvSosBadge.setText(R.string.fall_alert_badge);
            } else {
                holder.tvSosBadge.setText(R.string.sos_alert_badge);
            }
            holder.cardRoot.setStrokeColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.emergency_red));
        } else {
            holder.tvSosBadge.setVisibility(View.GONE);
            holder.cardRoot.setStrokeColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.divider_color));
        }

        // GPS Location Chip and info
        if (message.hasGpsFix()) {
            holder.chipLocation.setVisibility(View.VISIBLE);
            String label = String.format(Locale.US, "%.5f, %.5f (Map)", message.getLatitude(), message.getLongitude());
            holder.chipLocation.setText(label);
            holder.chipLocation.setOnClickListener(v -> {
                Uri gmmIntentUri = Uri.parse("geo:" + message.getLatitude() + "," + message.getLongitude()
                        + "?q=" + message.getLatitude() + "," + message.getLongitude() + "(Emergency Location)");
                Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
                mapIntent.setPackage("com.google.android.apps.maps");
                if (mapIntent.resolveActivity(v.getContext().getPackageManager()) != null) {
                    v.getContext().startActivity(mapIntent);
                } else {
                    v.getContext().startActivity(new Intent(Intent.ACTION_VIEW, gmmIntentUri));
                }
            });
            holder.tvGpsInfo.setVisibility(View.GONE);
        } else {
            holder.chipLocation.setVisibility(View.GONE);
            holder.chipLocation.setOnClickListener(null);
            if (message.isSos()) {
                holder.tvGpsInfo.setVisibility(View.VISIBLE);
                holder.tvGpsInfo.setText(R.string.no_gps_fix);
            } else {
                holder.tvGpsInfo.setVisibility(View.GONE);
            }
        }

        // Outgoing bubble states: Sending... -> Sent over LoRa -> Failed (tap to retry)
        StringBuilder statusBuilder = new StringBuilder();
        if (message.isSent()) {
            if (Message.STATE_SENDING.equalsIgnoreCase(message.getSendState())) {
                statusBuilder.append("Sending...");
                holder.itemView.setOnClickListener(null);
            } else if (Message.STATE_FAILED.equalsIgnoreCase(message.getSendState())) {
                statusBuilder.append("Failed (tap to retry)");
                holder.itemView.setOnClickListener(v -> {
                    if (retryListener != null) {
                        retryListener.onRetry(message);
                    }
                });
            } else {
                statusBuilder.append("Sent over LoRa");
                holder.itemView.setOnClickListener(null);
            }
        } else {
            statusBuilder.append("Received over LoRa");
            holder.itemView.setOnClickListener(null);
        }

        if (message.getPacketId() != null) {
            statusBuilder.append(" • #").append(message.getPacketId());
        }
        holder.tvPacketStatus.setText(statusBuilder.toString());
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardRoot;
        TextView tvSender, tvTimestamp, tvContent, tvSosBadge, tvGpsInfo, tvPacketStatus;
        Chip chipLocation;

        ViewHolder(View itemView) {
            super(itemView);
            cardRoot = itemView.findViewById(R.id.card_message_root);
            tvSender = itemView.findViewById(R.id.tv_sender);
            tvTimestamp = itemView.findViewById(R.id.tv_timestamp);
            tvContent = itemView.findViewById(R.id.tv_content);
            tvSosBadge = itemView.findViewById(R.id.tv_sos_badge);
            chipLocation = itemView.findViewById(R.id.chip_location);
            tvGpsInfo = itemView.findViewById(R.id.tv_gps_info);
            tvPacketStatus = itemView.findViewById(R.id.tv_packet_status);
        }
    }
}
