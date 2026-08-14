package com.example.loracommunication.ui;

import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.loracommunication.data.Message;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.ViewHolder> {

    private List<Message> messages = new ArrayList<>();

    public void setMessages(List<Message> messages) {
        this.messages = messages;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_2, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Message message = messages.get(position);
        holder.text1.setText(message.getContent());
        
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(message.getTimestamp()));
        String subtitle = String.format("%s - %s", message.getSenderId(), time);
        
        if (message.getLatitude() != 0 || message.getLongitude() != 0) {
            subtitle += " (Tap to view map)";
            holder.itemView.setOnClickListener(v -> {
                Uri gmmIntentUri = Uri.parse("geo:" + message.getLatitude() + "," + message.getLongitude() + "?q=" + message.getLatitude() + "," + message.getLongitude() + "(Emergency Location)");
                Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
                mapIntent.setPackage("com.google.android.apps.maps");
                if (mapIntent.resolveActivity(v.getContext().getPackageManager()) != null) {
                    v.getContext().startActivity(mapIntent);
                } else {
                    // Fallback for when Google Maps is not installed
                    v.getContext().startActivity(new Intent(Intent.ACTION_VIEW, gmmIntentUri));
                }
            });
        } else {
            holder.itemView.setOnClickListener(null);
        }
        
        holder.text2.setText(subtitle);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView text1, text2;

        ViewHolder(View itemView) {
            super(itemView);
            text1 = itemView.findViewById(android.R.id.text1);
            text2 = itemView.findViewById(android.R.id.text2);
        }
    }
}
