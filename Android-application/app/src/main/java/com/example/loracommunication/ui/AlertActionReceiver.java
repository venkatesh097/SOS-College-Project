package com.example.loracommunication.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlertActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (EmergencyAlertManager.ACTION_ACKNOWLEDGE.equals(intent.getAction())) {
            long incidentId = intent.getLongExtra(EmergencyAlertManager.EXTRA_INCIDENT_ID, -1);
            if (incidentId != -1) {
                EmergencyAlertManager.getInstance(context).acknowledgeIncident(incidentId);
            }
        }
    }
}
