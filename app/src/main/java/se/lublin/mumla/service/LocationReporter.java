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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
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
    private static final String REPORT_CATEGORY_LOCATION_UPDATE = "Location Update";
    private static final String REPORT_API_SOURCE = "https://emsifa.github.io/api-wilayah-indonesia";
    private static final String API_WILAYAH_PROVINCES_URL = REPORT_API_SOURCE + "/api/provinces.json";
    private static final String API_WILAYAH_REGENCIES_URL_TEMPLATE = REPORT_API_SOURCE + "/api/regencies/%s.json";
    // Cache for 10 minutes to reduce API requests while keeping region names reasonably fresh.
    private static final long API_REGION_CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final String REPORT_WILAYAH_UNKNOWN = "Unknown";

    private final Context mContext;
    private final Settings mSettings;
    private final LocationManager mLocationManager;

    private ScheduledExecutorService mScheduler;
    private ScheduledFuture<?> mScheduledTask;

    /** Most recently received location — updated by the LocationListener. */
    private volatile Location mLastLocation;
    private volatile String mLastApiProvince;
    private volatile String mLastApiCity;
    private volatile String mLastApiKabKota;
    private volatile long mLastApiKabKotaResolvedAt;

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
        Address address = reverseGeocode(location);
        String kabKotaFallback = resolveDistrictCity(location, address);
        String kabKota = resolveKabKotaFromApi(location, kabKotaFallback);
        String kecamatan = resolveKecamatan(address);
        String kelurahan = resolveKelurahan(address);
        String namaPelapor = resolveNamaPelapor(username);
        String category = REPORT_CATEGORY_LOCATION_UPDATE;

        StringBuilder description = new StringBuilder();
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
        sb.append("\"namaPelapor\":\"").append(escapeJson(namaPelapor)).append("\",");
        sb.append("\"api\":\"").append(escapeJson(REPORT_API_SOURCE)).append("\",");
        sb.append("\"kabKota\":\"").append(escapeJson(kabKota)).append("\",");
        sb.append("\"kecamatan\":\"").append(escapeJson(kecamatan)).append("\",");
        sb.append("\"kelurahan\":\"").append(escapeJson(kelurahan)).append("\",");
        sb.append("\"kategori\":\"").append(escapeJson(category)).append("\",");
        sb.append("\"deskripsi\":\"").append(escapeJson(description.toString())).append("\"");
        sb.append("}");
        return sb.toString();
    }

    private String resolveKabKotaFromApi(Location location, String fallbackDistrictCity) {
        Address address = reverseGeocode(location);
        if (address == null) {
            return fallbackDistrictCity;
        }

        String province = firstNonEmpty(address.getAdminArea(), address.getSubAdminArea());
        String city = firstNonEmpty(address.getSubAdminArea(), address.getLocality(), address.getAdminArea());
        if (province == null || city == null) {
            return fallbackDistrictCity;
        }

        long now = System.currentTimeMillis();
        if (province.equalsIgnoreCase(mLastApiProvince)
                && city.equalsIgnoreCase(mLastApiCity)
                && mLastApiKabKota != null
                && now - mLastApiKabKotaResolvedAt < API_REGION_CACHE_TTL_MS) {
            return mLastApiKabKota;
        }

        try {
            String provinceId = findProvinceId(province);
            if (provinceId == null) {
                return fallbackDistrictCity;
            }

            String kabKotaFromApi = findRegencyName(provinceId, city);
            if (kabKotaFromApi == null) {
                return fallbackDistrictCity;
            }

            mLastApiProvince = province;
            mLastApiCity = city;
            mLastApiKabKota = kabKotaFromApi;
            mLastApiKabKotaResolvedAt = now;
            return kabKotaFromApi;
        } catch (Exception e) {
            Log.d(TAG, "Could not resolve kab/kota from API wilayah: " + e.getMessage());
            return fallbackDistrictCity;
        }
    }

    private String resolveDistrictCity(Location location, Address address) {
        if (address != null) {
            String district = firstNonEmpty(address.getSubAdminArea(), address.getLocality(), address.getAdminArea());
            String city = firstNonEmpty(address.getLocality(), address.getSubAdminArea(), address.getAdminArea(), address.getCountryName());
            if (district != null && city != null && !district.equalsIgnoreCase(city)) {
                return (district + "/" + city).trim();
            }
            if (city != null) {
                return city.trim();
            }
        }

        String provider = location.getProvider();
        if (provider != null && !provider.trim().isEmpty()) {
            return provider.trim();
        }
        return REPORT_WILAYAH_UNKNOWN;
    }

    private String resolveKecamatan(Address address) {
        if (address != null) {
            String kecamatan = firstNonEmpty(
                    address.getSubLocality(),
                    address.getLocality(),
                    address.getSubAdminArea(),
                    address.getAdminArea());
            if (kecamatan != null) {
                return kecamatan;
            }
        }
        return REPORT_WILAYAH_UNKNOWN;
    }

    private String resolveKelurahan(Address address) {
        if (address != null) {
            String kelurahan = firstNonEmpty(
                    address.getFeatureName(),
                    address.getSubThoroughfare(),
                    address.getThoroughfare(),
                    address.getSubLocality(),
                    address.getLocality());
            if (kelurahan != null) {
                return kelurahan;
            }
        }
        return REPORT_WILAYAH_UNKNOWN;
    }

    private String resolveNamaPelapor(String username) {
        if (username == null) {
            return REPORT_WILAYAH_UNKNOWN;
        }
        String trimmed = username.trim();
        if (trimmed.isEmpty()) {
            return REPORT_WILAYAH_UNKNOWN;
        }
        return trimmed;
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

    private Address reverseGeocode(Location location) {
        try {
            if (!Geocoder.isPresent()) {
                return null;
            }
            Geocoder geocoder = new Geocoder(mContext, Locale.getDefault());
            List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
            if (addresses != null && !addresses.isEmpty()) {
                return addresses.get(0);
            }
        } catch (Exception e) {
            Log.d(TAG, "Could not reverse geocode district/city: " + e.getMessage());
        }
        return null;
    }

    private String findProvinceId(String provinceName) throws Exception {
        JSONArray provinces = fetchJsonArray(API_WILAYAH_PROVINCES_URL);
        for (int i = 0; i < provinces.length(); i++) {
            JSONObject province = provinces.optJSONObject(i);
            if (province == null) continue;
            String apiProvinceName = province.optString("name", "");
            if (namesLikelyMatch(apiProvinceName, provinceName)) {
                return province.optString("id", null);
            }
        }
        return null;
    }

    private String findRegencyName(String provinceId, String cityName) throws Exception {
        if (provinceId == null || !provinceId.matches("\\d{1,4}")) {
            return null;
        }
        String endpointUrl = String.format(Locale.US, API_WILAYAH_REGENCIES_URL_TEMPLATE, provinceId);
        JSONArray regencies = fetchJsonArray(endpointUrl);
        for (int i = 0; i < regencies.length(); i++) {
            JSONObject regency = regencies.optJSONObject(i);
            if (regency == null) continue;
            String apiRegencyName = regency.optString("name", "");
            if (namesLikelyMatch(apiRegencyName, cityName)) {
                return apiRegencyName;
            }
        }
        return null;
    }

    private static JSONArray fetchJsonArray(String endpointUrl) throws Exception {
        URL url = new URL(endpointUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IllegalStateException("API wilayah responded with HTTP " + responseCode);
            }

            StringBuilder response = new StringBuilder();
            try (InputStream inputStream = connection.getInputStream();
                 InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                 BufferedReader bufferedReader = new BufferedReader(reader)) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    response.append(line);
                }
            }
            return new JSONArray(response.toString());
        } finally {
            connection.disconnect();
        }
    }

    private static boolean namesLikelyMatch(String left, String right) {
        String normalizedLeft = normalizeWilayahName(left);
        String normalizedRight = normalizeWilayahName(right);
        if (normalizedLeft.isEmpty() || normalizedRight.isEmpty()) {
            return false;
        }
        return normalizedLeft.equals(normalizedRight)
                || normalizedLeft.contains(normalizedRight)
                || normalizedRight.contains(normalizedLeft);
    }

    private static String normalizeWilayahName(String value) {
        if (value == null) return "";
        return value.toUpperCase(Locale.US)
                .replace("DAERAH KHUSUS IBUKOTA", "DKI")
                .replace("DAERAH ISTIMEWA", "DI")
                .replaceAll("\\bPROVINSI\\b", " ")
                .replaceAll("\\bKABUPATEN\\b", " ")
                .replaceAll("\\bKOTA\\b", " ")
                .replaceAll("[^A-Z0-9]", " ")
                .replaceAll("\\s+", " ")
                .trim();
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
