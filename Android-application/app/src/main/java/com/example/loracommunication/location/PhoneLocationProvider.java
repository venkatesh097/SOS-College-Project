package com.example.loracommunication.location;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

public class PhoneLocationProvider {

    private static final String TAG = "PhoneLocationProvider";

    public static final long STALE_THRESHOLD_MS = 120000L; // 2 minutes
    public static final long EXPIRED_THRESHOLD_MS = 300000L; // 5 minutes

    private final Context context;
    private final FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    private Location lastLocation;
    private long lastLocationTimestamp = 0;
    private boolean isRunning = false;
    private boolean highAccuracy = false;

    public interface OnLocationUpdatedListener {
        void onLocationUpdated(Location location);
    }

    private OnLocationUpdatedListener listener;

    public PhoneLocationProvider(Context context) {
        this.context = context.getApplicationContext();
        this.fusedLocationClient = LocationServices.getFusedLocationProviderClient(this.context);
    }

    public void setListener(OnLocationUpdatedListener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        if (isRunning) return;
        if (!hasLocationPermission()) {
            Log.w(TAG, "Cannot start location provider: permission not granted");
            return;
        }

        isRunning = true;
        requestLocationUpdates();
    }

    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
    }

    public synchronized boolean isRunning() {
        return isRunning;
    }

    public synchronized void setHighAccuracy(boolean highAccuracy) {
        if (this.highAccuracy == highAccuracy) return;
        this.highAccuracy = highAccuracy;
        if (isRunning) {
            // Re-apply request with new priority
            requestLocationUpdates();
        }
    }

    public boolean isHighAccuracy() {
        return highAccuracy;
    }

    @SuppressLint("MissingPermission")
    private void requestLocationUpdates() {
        if (!hasLocationPermission()) return;

        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }

        int priority = highAccuracy ? Priority.PRIORITY_HIGH_ACCURACY : Priority.PRIORITY_BALANCED_POWER_ACCURACY;

        LocationRequest request = new LocationRequest.Builder(priority, 15000L)
                .setMinUpdateIntervalMillis(10000L)
                .setMinUpdateDistanceMeters(5.0f)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                Location loc = locationResult.getLastLocation();
                if (loc != null) {
                    updateLocation(loc);
                }
            }
        };

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException requesting location updates", e);
        }

        // Also retrieve last known location initially
        fusedLocationClient.getLastLocation().addOnSuccessListener(loc -> {
            if (loc != null && (lastLocation == null || loc.getTime() > lastLocation.getTime())) {
                updateLocation(loc);
            }
        });
    }

    private synchronized void updateLocation(Location loc) {
        if (loc == null) return;
        if (loc.hasAccuracy() && loc.getAccuracy() > 50.0f) {
            Log.d(TAG, "Discarding location with accuracy > 50m: " + loc.getAccuracy() + "m");
            return;
        }
        this.lastLocation = loc;
        this.lastLocationTimestamp = System.currentTimeMillis();
        if (listener != null) {
            listener.onLocationUpdated(loc);
        }
    }

    public synchronized Location getLastLocation() {
        return lastLocation;
    }

    public synchronized long getLastLocationTimestamp() {
        return lastLocationTimestamp;
    }

    public synchronized boolean hasValidLocation() {
        if (lastLocation == null) return false;
        long age = System.currentTimeMillis() - lastLocationTimestamp;
        return age < EXPIRED_THRESHOLD_MS;
    }

    public synchronized boolean isLocationStale() {
        if (lastLocation == null) return true;
        long age = System.currentTimeMillis() - lastLocationTimestamp;
        return age >= STALE_THRESHOLD_MS;
    }

    @SuppressLint("MissingPermission")
    public void getFreshLocation(OnLocationUpdatedListener callback) {
        if (!hasLocationPermission()) {
            if (callback != null) callback.onLocationUpdated(hasValidLocation() ? lastLocation : null);
            return;
        }

        // If we have a very fresh location (< 15 seconds), return it immediately
        if (lastLocation != null && (System.currentTimeMillis() - lastLocationTimestamp < 15000L)) {
            if (callback != null) callback.onLocationUpdated(lastLocation);
            return;
        }

        CancellationTokenSource cts = new CancellationTokenSource();
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                .addOnSuccessListener(loc -> {
                    if (loc != null && (!loc.hasAccuracy() || loc.getAccuracy() <= 50.0f)) {
                        updateLocation(loc);
                        if (callback != null) callback.onLocationUpdated(loc);
                    } else {
                        // Fallback to last known location if within 5 minutes
                        if (callback != null) callback.onLocationUpdated(hasValidLocation() ? lastLocation : null);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Failed to get current location", e);
                    if (callback != null) callback.onLocationUpdated(hasValidLocation() ? lastLocation : null);
                });
    }

    public boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isLocationServicesEnabled() {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        try {
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    public static Intent getLocationSettingsIntent() {
        return new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
    }
}
