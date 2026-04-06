/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.service;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import se.lublin.mumla.Settings;

/**
 * Sends the device's GPS location to a Node.js dashboard endpoint at a regular interval
 * (default {@link Settings#LOCATION_SEND_INTERVAL_MS} milliseconds = 5 seconds).
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>Call {@link #start()} when the Mumble connection is established.</li>
 *   <li>Call {@link #stop()} when the connection drops or the service is destroyed.</li>
 * </ul>
 *
 * <p>To change the sending interval, update the constant
 * {@link Settings#LOCATION_SEND_INTERVAL_MS} in {@link Settings}.
 * To change the destination URL, set the "dashboardUrl" preference in General Settings.
 */
public class LocationReporter {
    private static final String TAG = LocationReporter.class.getSimpleName();

    private final Context mContext;
    private final Settings mSettings;
    private final LocationManager mLocationManager;

    private ScheduledExecutorService mScheduler;
    private ScheduledFuture<?> mScheduledTask;

    /** Most recently received location — updated by the LocationListener. */
    private volatile Location mLastLocation;

    private final LocationListener mLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            mLastLocation = location;
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {}
    };

    public LocationReporter(Context context, Settings settings) {
        mContext = context.getApplicationContext();
        mSettings = settings;
        mLocationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
    }

    /**
     * Starts periodic location reporting.  Safe to call multiple times — a second call is a
     * no-op when already running, preventing duplicate schedulers after reconnect.
     */
    public synchronized void start() {
        if (mScheduler != null && !mScheduler.isShutdown()) {
            // Already running — avoid duplicate schedulers on reconnect.
            return;
        }

        // Seed with the best cached fix before the first scheduled tick.
        seedLastLocation();

        // Subscribe to live updates so the cached location stays fresh.
        requestLocationUpdates();

        long intervalMs = Settings.LOCATION_SEND_INTERVAL_MS;
        mScheduler = Executors.newSingleThreadScheduledExecutor();
        // scheduleWithFixedDelay: the next send starts intervalMs *after* the current one finishes,
        // preventing queued tasks if a network request takes longer than the interval.
        mScheduledTask = mScheduler.scheduleWithFixedDelay(
                this::sendLocation, 0, intervalMs, TimeUnit.MILLISECONDS);
        Log.d(TAG, "Location reporting started, interval=" + intervalMs + "ms");
    }

    /**
     * Stops periodic location reporting and unregisters the location listener.
     * Safe to call when already stopped.
     */
    public synchronized void stop() {
        if (mScheduledTask != null) {
            mScheduledTask.cancel(false);
            mScheduledTask = null;
        }
        if (mScheduler != null) {
            mScheduler.shutdown();
            mScheduler = null;
        }
        removeLocationUpdates();
        Log.d(TAG, "Location reporting stopped");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void seedLastLocation() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted, cannot seed last location");
            return;
        }
        try {
            Location gps = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            mLastLocation = bestLocation(gps, network);
        } catch (SecurityException e) {
            Log.w(TAG, "SecurityException seeding last location: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "Could not seed last location: " + e.getMessage());
        }
    }

    private void requestLocationUpdates() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted, cannot request location updates");
            return;
        }
        try {
            if (mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, Settings.LOCATION_SEND_INTERVAL_MS, 0f,
                        mLocationListener, Looper.getMainLooper());
            }
            if (mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, Settings.LOCATION_SEND_INTERVAL_MS, 0f,
                        mLocationListener, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            Log.w(TAG, "SecurityException requesting location updates: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "Could not request location updates: " + e.getMessage());
        }
    }

    private void removeLocationUpdates() {
        try {
            mLocationManager.removeUpdates(mLocationListener);
        } catch (Exception e) {
            Log.w(TAG, "Could not remove location updates: " + e.getMessage());
        }
    }

    private void sendLocation() {
        String dashboardUrl = mSettings.getDashboardUrl();
        if (dashboardUrl == null || dashboardUrl.isEmpty()) {
            Log.d(TAG, "Dashboard URL not configured, skipping location send");
            return;
        }

        Location location = mLastLocation;
        if (location == null) {
            Log.d(TAG, "No location available yet, skipping");
            return;
        }

        String json = buildJson(location);
        try {
            URL url = new URL(dashboardUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(body);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    Log.w(TAG, "Dashboard responded with HTTP " + responseCode);
                }
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to send location to dashboard: " + e.getMessage());
        }
    }

    private static String buildJson(Location location) {
        return "{" +
                "\"latitude\":" + location.getLatitude() + "," +
                "\"longitude\":" + location.getLongitude() + "," +
                "\"accuracy\":" + location.getAccuracy() + "," +
                "\"timestamp\":" + location.getTime() +
                "}";
    }

    private static Location bestLocation(Location a, Location b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.getTime() >= b.getTime() ? a : b;
    }
}
