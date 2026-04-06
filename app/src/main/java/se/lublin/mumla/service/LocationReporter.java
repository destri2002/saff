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
import android.location.Address;
import android.location.Geocoder;
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
import java.util.List;
import java.util.Locale;
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

        String username = mSettings.getDefaultUsername();
        String locationJson = buildLocationJson(location, username);
        String reportJson = buildReportJson(location, username);
        String reportUrl = deriveReportUrl(dashboardUrl);
        try {
            postJson(dashboardUrl, locationJson, "location");
            postJson(reportUrl, reportJson, "report");
        } catch (Exception e) {
            Log.w(TAG, "Failed to send location to dashboard: " + e.getMessage());
        }
    }

    private void postJson(String endpointUrl, String json, String kind) throws Exception {
        URL url = new URL(endpointUrl);
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
                Log.w(TAG, "Dashboard " + kind + " endpoint responded with HTTP " + responseCode);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String buildLocationJson(Location location, String username) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"latitude\":").append(location.getLatitude()).append(",");
        sb.append("\"longitude\":").append(location.getLongitude()).append(",");
        sb.append("\"accuracy\":").append(location.getAccuracy()).append(",");
        sb.append("\"timestamp\":").append(location.getTime());
        if (username != null && !username.isEmpty()) {
            sb.append(",\"username\":\"").append(escapeJson(username)).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private String buildReportJson(Location location, String username) {
        String districtCity = resolveDistrictCity(location);
        String category = "Location Update";
        // Keep amount numeric and useful in report table: we use GPS accuracy in meters.
        double amount = Math.max(0d, location.getAccuracy());

        StringBuilder description = new StringBuilder();
        if (username != null && !username.isEmpty()) {
            description.append(username.trim()).append(" ");
        }
        description.append("at ")
                .append(String.format(Locale.US, "%.6f", location.getLatitude()))
                .append(", ")
                .append(String.format(Locale.US, "%.6f", location.getLongitude()))
                .append(" ±")
                .append(String.format(Locale.US, "%.1f", location.getAccuracy()))
                .append("m");

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"timestamp\":").append(location.getTime()).append(",");
        sb.append("\"districtCity\":\"").append(escapeJson(districtCity)).append("\",");
        sb.append("\"category\":\"").append(escapeJson(category)).append("\",");
        sb.append("\"amount\":").append(amount).append(",");
        sb.append("\"description\":\"").append(escapeJson(description.toString())).append("\"");
        sb.append("}");
        return sb.toString();
    }

    private String resolveDistrictCity(Location location) {
        try {
            if (Geocoder.isPresent()) {
                Geocoder geocoder = new Geocoder(mContext, Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address a = addresses.get(0);
                    String district = firstNonEmpty(a.getSubAdminArea(), a.getLocality(), a.getAdminArea());
                    String city = firstNonEmpty(a.getLocality(), a.getSubAdminArea(), a.getAdminArea(), a.getCountryName());
                    if (district != null && city != null && !district.equalsIgnoreCase(city)) {
                        return (district + "/" + city).trim();
                    }
                    if (city != null) {
                        return city.trim();
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Could not reverse geocode district/city: " + e.getMessage());
        }

        String provider = location.getProvider();
        if (provider != null && !provider.trim().isEmpty()) {
            return provider.trim();
        }
        return "Unknown";
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String deriveReportUrl(String dashboardUrl) {
        String trimmed = dashboardUrl.trim();
        if (trimmed.endsWith("/location")) {
            return trimmed.substring(0, trimmed.length() - "/location".length()) + "/report";
        }
        if (trimmed.endsWith("/location/")) {
            return trimmed.substring(0, trimmed.length() - "/location/".length()) + "/report";
        }
        if (trimmed.endsWith("/")) {
            return trimmed + "report";
        }
        return trimmed + "/report";
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Location bestLocation(Location a, Location b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.getTime() >= b.getTime() ? a : b;
    }
}
