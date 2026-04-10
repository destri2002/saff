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

package se.lublin.mumla.channel;

import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.preference.PreferenceManager;
import androidx.viewpager.widget.PagerTabStrip;
import androidx.viewpager.widget.ViewPager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

import org.json.JSONArray;
import org.json.JSONObject;

import se.lublin.humla.HumlaService;
import se.lublin.humla.IHumlaService;
import se.lublin.humla.IHumlaSession;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.WhisperTarget;
import se.lublin.humla.util.HumlaDisconnectedException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.humla.util.IHumlaObserver;
import se.lublin.humla.util.VoiceTargetMode;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.util.HumlaServiceFragment;

/**
 * Class to encapsulate both a ChannelListFragment and ChannelChatFragment.
 * Created by andrew on 02/08/13.
 */
public class ChannelFragment extends HumlaServiceFragment implements SharedPreferences.OnSharedPreferenceChangeListener, ChatTargetProvider {
    private static final String TAG = ChannelFragment.class.getName();
    private static final ExecutorService REPORT_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String PREF_LOCAL_REPORT_API_URL = "channel_report_api_url";
    private static final String API_WILAYAH_BASE_URL = "https://emsifa.github.io/api-wilayah-indonesia";
    private static final String API_WILAYAH_PROVINCES_URL = API_WILAYAH_BASE_URL + "/api/provinces.json";
    private static final String API_WILAYAH_REGENCIES_URL_TEMPLATE = API_WILAYAH_BASE_URL + "/api/regencies/%s.json";
    private static final String API_WILAYAH_DISTRICTS_URL_TEMPLATE = API_WILAYAH_BASE_URL + "/api/districts/%s.json";
    private static final String API_WILAYAH_VILLAGES_URL_TEMPLATE = API_WILAYAH_BASE_URL + "/api/villages/%s.json";

    private ViewPager mViewPager;
    private PagerTabStrip mTabStrip;
    private Button mTalkButton;
    private View mTalkView;

    private View mTargetPanel;
    private ImageView mTargetPanelCancel;
    private TextView mTargetPanelText;

    private ChatTarget mChatTarget;
    /** Chat target listeners, notified when the chat target is changed. */
    private List<OnChatTargetSelectedListener> mChatTargetListeners = new ArrayList<OnChatTargetSelectedListener>();

    /** True iff the talk button has been hidden (e.g. when muted) */
    private boolean mTalkButtonHidden;

    private HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onUserTalkStateUpdated(IUser user) {
            if (getService() == null || !getService().isConnected()) {
                return;
            }
            int selfSession;
            try {
                selfSession = getService().HumlaSession().getSessionId();
            } catch (HumlaDisconnectedException|IllegalStateException e) {
                Log.d(TAG, "exception in onUserTalkStateUpdated: " + e);
                return;
            }
            if (user != null && user.getSession() == selfSession) {
                // Manually set button selection colour when we receive a talk state update.
                // This allows representation of talk state when using hot corners and PTT toggle.
                switch (user.getTalkState()) {
                case TALKING:
                case SHOUTING:
                case WHISPERING:
                    mTalkButton.setPressed(true);
                    break;
                case PASSIVE:
                    mTalkButton.setPressed(false);
                    break;
                }
            }
        }

        @Override
        public void onUserStateUpdated(IUser user) {
            if (getService() == null || !getService().isConnected()) {
                return;
            }
            int selfSession;
            try {
                selfSession = getService().HumlaSession().getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserStateUpdated: " + e);
                return;
            }
            if (user != null && user.getSession() == selfSession) {
                configureInput();
            }
        }

        @Override
        public void onVoiceTargetChanged(VoiceTargetMode mode) {
            configureTargetPanel();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_channel, container, false);
        mViewPager = (ViewPager) view.findViewById(R.id.channel_view_pager);
        mTabStrip = (PagerTabStrip) view.findViewById(R.id.channel_tab_strip);
        if(mTabStrip != null) {
            int[] attrs = new int[] { android.R.attr.colorPrimary, android.R.attr.textColorPrimaryInverse };
            TypedArray a = getActivity().obtainStyledAttributes(attrs);
            int titleStripBackground = a.getColor(0, -1);
            int titleStripColor = a.getColor(1, -1);
            a.recycle();

            mTabStrip.setTextColor(titleStripColor);
            mTabStrip.setTabIndicatorColor(titleStripColor);
            mTabStrip.setBackgroundColor(titleStripBackground);
            mTabStrip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        }

        mTalkView = view.findViewById(R.id.pushtotalk_view);
        mTalkButton = (Button) view.findViewById(R.id.pushtotalk);
        mTalkButton.setOnTouchListener(new View.OnTouchListener() {

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        if (getService() != null) {
                            getService().onTalkKeyDown();
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (getService() != null) {
                            getService().onTalkKeyUp();
                        }
                        break;
                }
                return true;
            }
        });
        mTargetPanel = view.findViewById(R.id.target_panel);
        mTargetPanelCancel = (ImageView) view.findViewById(R.id.target_panel_cancel);
        mTargetPanelCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getService() == null || !getService().isConnected())
                    return;

                IHumlaSession session = getService().HumlaSession();
                if (session.getVoiceTargetMode() == VoiceTargetMode.WHISPER) {
                    byte target = session.getVoiceTargetId();
                    session.setVoiceTargetId((byte) 0);
                    session.unregisterWhisperTarget(target);
                }
            }
        });
        mTargetPanelText = (TextView) view.findViewById(R.id.target_panel_warning);
        configureInput();
        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        preferences.registerOnSharedPreferenceChangeListener(this);

        if(mViewPager != null) { // Phone
            ChannelFragmentPagerAdapter pagerAdapter = new ChannelFragmentPagerAdapter(getChildFragmentManager());
            mViewPager.setAdapter(pagerAdapter);
        } else { // Tablet
            ChannelListFragment listFragment = new ChannelListFragment();
            Bundle listArgs = new Bundle();
            listArgs.putBoolean("pinned", isShowingPinnedChannels());
            listFragment.setArguments(listArgs);
            ChannelChatFragment chatFragment = new ChannelChatFragment();

            getChildFragmentManager().beginTransaction()
                    .replace(R.id.list_fragment, listFragment)
                    .replace(R.id.chat_fragment, chatFragment)
                    .commit();
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.channel_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Settings settings = Settings.getInstance(getActivity());
        int itemId = item.getItemId();
        if (itemId == R.id.menu_input_voice) {
            settings.setInputMethod(Settings.ARRAY_INPUT_METHOD_VOICE);
            return true;
        } else if (itemId == R.id.menu_input_ptt) {
            settings.setInputMethod(Settings.ARRAY_INPUT_METHOD_PTT);
            return true;
        } else if (itemId == R.id.menu_input_continuous) {
            settings.setInputMethod(Settings.ARRAY_INPUT_METHOD_CONTINUOUS);
            return true;
        } else if (itemId == R.id.menu_create_report) {
            showCreateReportDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showCreateReportDialog() {
        if (getActivity() == null) return;

        View dialogView = LayoutInflater.from(getActivity()).inflate(R.layout.dialog_create_report, null);
        final EditText reportApiUrlField = dialogView.findViewById(R.id.report_api_url);
        final EditText namaPelaporField = dialogView.findViewById(R.id.report_nama_pelapor);
        final Spinner kabKotaField = dialogView.findViewById(R.id.report_kab_kota);
        final Spinner kecamatanField = dialogView.findViewById(R.id.report_kecamatan);
        final Spinner kelurahanField = dialogView.findViewById(R.id.report_kelurahan);
        final EditText kategoriField = dialogView.findViewById(R.id.report_kategori);
        final EditText deskripsiField = dialogView.findViewById(R.id.report_deskripsi);
        reportApiUrlField.setText(getInitialReportApiUrl());
        final WilayahSelectionState wilayahState = setupWilayahSearch(kabKotaField, kecamatanField, kelurahanField);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getActivity())
                .setTitle(R.string.create_report_title)
                .setView(dialogView)
                .setPositiveButton(R.string.report_submit, (dialog, which) -> {
                    String reportApiUrl = reportApiUrlField.getText().toString().trim();
                    String namaPelapor = namaPelaporField.getText().toString().trim();
                    String kabKota = wilayahState.getKabKota();
                    String kecamatan = wilayahState.getKecamatan();
                    String kelurahan = wilayahState.getKelurahan();
                    String kategori = kategoriField.getText().toString().trim();
                    String deskripsi = deskripsiField.getText().toString().trim();
                    if (!wilayahState.isSelectionValid()) {
                        Toast.makeText(getActivity(), R.string.report_invalid_wilayah_selection, Toast.LENGTH_LONG).show();
                        return;
                    }
                    submitReport(reportApiUrl, namaPelapor, kabKota, kecamatan, kelurahan, kategori, deskripsi);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String getInitialReportApiUrl() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        String localReportUrl = preferences.getString(PREF_LOCAL_REPORT_API_URL, "");
        if (localReportUrl != null && !localReportUrl.trim().isEmpty()) {
            return localReportUrl.trim();
        }
        String dashboardUrl = Settings.getInstance(getActivity()).getDashboardUrl();
        return deriveReportUrl(dashboardUrl);
    }

    private WilayahSelectionState setupWilayahSearch(Spinner kabKotaField, Spinner kecamatanField, Spinner kelurahanField) {
        WilayahSelectionState state = new WilayahSelectionState();

        final String kabPlaceholder = getString(R.string.report_select_kab_kota);
        final String kecPlaceholder = getString(R.string.report_select_kecamatan);
        final String kelPlaceholder = getString(R.string.report_select_kelurahan);

        ArrayAdapter<WilayahOption> kabKotaAdapter = createWilayahSpinnerAdapter(kabPlaceholder);
        ArrayAdapter<WilayahOption> kecamatanAdapter = createWilayahSpinnerAdapter(kecPlaceholder);
        ArrayAdapter<WilayahOption> kelurahanAdapter = createWilayahSpinnerAdapter(kelPlaceholder);
        kabKotaField.setAdapter(kabKotaAdapter);
        kecamatanField.setAdapter(kecamatanAdapter);
        kelurahanField.setAdapter(kelurahanAdapter);
        kecamatanField.setEnabled(false);
        kelurahanField.setEnabled(false);

        kabKotaField.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                WilayahOption selected = kabKotaAdapter.getItem(position);
                state.regency = selected != null && selected.hasValidId() ? selected : null;
                state.district = null;
                state.village = null;
                resetSpinner(kecamatanField, kecamatanAdapter, kecPlaceholder);
                resetSpinner(kelurahanField, kelurahanAdapter, kelPlaceholder);
                kelurahanField.setEnabled(false);
                if (state.regency == null) {
                    kecamatanField.setEnabled(false);
                    return;
                }
                kecamatanField.setEnabled(true);
                loadDistrictsByRegency(state.regency.id, kecamatanAdapter, kecPlaceholder);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                state.regency = null;
            }
        });

        kecamatanField.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                WilayahOption selected = kecamatanAdapter.getItem(position);
                state.district = selected != null && selected.hasValidId() ? selected : null;
                state.village = null;
                resetSpinner(kelurahanField, kelurahanAdapter, kelPlaceholder);
                if (state.district == null) {
                    kelurahanField.setEnabled(false);
                    return;
                }
                kelurahanField.setEnabled(true);
                loadVillagesByDistrict(state.district.id, kelurahanAdapter, kelPlaceholder);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                state.district = null;
            }
        });

        kelurahanField.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                WilayahOption selected = kelurahanAdapter.getItem(position);
                state.village = selected != null && selected.hasValidId() ? selected : null;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                state.village = null;
            }
        });

        loadRegencies(kabKotaAdapter, kabPlaceholder);
        return state;
    }

    private ArrayAdapter<WilayahOption> createWilayahSpinnerAdapter(String placeholderLabel) {
        ArrayAdapter<WilayahOption> adapter = new ArrayAdapter<>(getActivity(), android.R.layout.simple_spinner_item, new ArrayList<>());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        adapter.add(WilayahOption.placeholder(placeholderLabel));
        return adapter;
    }

    private void resetSpinner(Spinner spinner, ArrayAdapter<WilayahOption> adapter, String placeholderLabel) {
        adapter.clear();
        adapter.add(WilayahOption.placeholder(placeholderLabel));
        adapter.notifyDataSetChanged();
        spinner.setSelection(0, false);
    }

    private void loadRegencies(ArrayAdapter<WilayahOption> kabKotaAdapter, String placeholderLabel) {
        REPORT_EXECUTOR.execute(() -> {
            try {
                List<WilayahOption> regencies = new ArrayList<>();
                JSONArray provinces = fetchJsonArray(API_WILAYAH_PROVINCES_URL);
                for (int i = 0; i < provinces.length(); i++) {
                    JSONObject province = provinces.optJSONObject(i);
                    if (province == null) continue;
                    String provinceId = province.optString("id", "");
                    if (!provinceId.matches("\\d{1,4}")) continue;
                    String endpointUrl = String.format(Locale.US, API_WILAYAH_REGENCIES_URL_TEMPLATE, provinceId);
                    JSONArray regencyJson = fetchJsonArray(endpointUrl);
                    regencies.addAll(parseWilayahOptions(regencyJson));
                }
                Handler main = new Handler(Looper.getMainLooper());
                main.post(() -> {
                    if (getActivity() == null) return;
                    kabKotaAdapter.clear();
                    kabKotaAdapter.add(WilayahOption.placeholder(placeholderLabel));
                    kabKotaAdapter.addAll(regencies);
                    kabKotaAdapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                showWilayahLoadFailed();
            }
        });
    }

    private void loadDistrictsByRegency(String regencyId, ArrayAdapter<WilayahOption> kecamatanAdapter, String placeholderLabel) {
        if (regencyId == null || !regencyId.matches("\\d{1,8}")) return;
        REPORT_EXECUTOR.execute(() -> {
            try {
                String endpointUrl = String.format(Locale.US, API_WILAYAH_DISTRICTS_URL_TEMPLATE, regencyId);
                List<WilayahOption> districts = parseWilayahOptions(fetchJsonArray(endpointUrl));
                Handler main = new Handler(Looper.getMainLooper());
                main.post(() -> {
                    if (getActivity() == null) return;
                    kecamatanAdapter.clear();
                    kecamatanAdapter.add(WilayahOption.placeholder(placeholderLabel));
                    kecamatanAdapter.addAll(districts);
                    kecamatanAdapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                showWilayahLoadFailed();
            }
        });
    }

    private void loadVillagesByDistrict(String districtId, ArrayAdapter<WilayahOption> kelurahanAdapter, String placeholderLabel) {
        if (districtId == null || !districtId.matches("\\d{1,12}")) return;
        REPORT_EXECUTOR.execute(() -> {
            try {
                String endpointUrl = String.format(Locale.US, API_WILAYAH_VILLAGES_URL_TEMPLATE, districtId);
                List<WilayahOption> villages = parseWilayahOptions(fetchJsonArray(endpointUrl));
                Handler main = new Handler(Looper.getMainLooper());
                main.post(() -> {
                    if (getActivity() == null) return;
                    kelurahanAdapter.clear();
                    kelurahanAdapter.add(WilayahOption.placeholder(placeholderLabel));
                    kelurahanAdapter.addAll(villages);
                    kelurahanAdapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                showWilayahLoadFailed();
            }
        });
    }

    private void showWilayahLoadFailed() {
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            if (getActivity() == null) return;
            Toast.makeText(getActivity(), R.string.report_wilayah_load_failed, Toast.LENGTH_LONG).show();
        });
    }

    private void submitReport(String reportApiUrl, String namaPelapor, String kabKota, String kecamatan, String kelurahan, String kategori, String deskripsi) {
        if (reportApiUrl.isEmpty()) {
            Toast.makeText(getActivity(), R.string.report_missing_dashboard_url, Toast.LENGTH_LONG).show();
            return;
        }
        if (namaPelapor.isEmpty() || kabKota.isEmpty() || kecamatan.isEmpty() || kelurahan.isEmpty() || kategori.isEmpty() || deskripsi.isEmpty()) {
            Toast.makeText(getActivity(), R.string.report_invalid_fields, Toast.LENGTH_LONG).show();
            return;
        }

        final String reportUrl = normalizeReportUrl(reportApiUrl);
        if (reportUrl.isEmpty()) {
            Toast.makeText(getActivity(), R.string.report_missing_dashboard_url, Toast.LENGTH_LONG).show();
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(getActivity())
                .edit()
                .putString(PREF_LOCAL_REPORT_API_URL, reportUrl)
                .apply();
        final long timestamp = System.currentTimeMillis();
        final String json = buildReportJson(timestamp, namaPelapor, kabKota, kecamatan, kelurahan, kategori, deskripsi);

        REPORT_EXECUTOR.execute(() -> {
            boolean success = postReport(reportUrl, json);
            Handler main = new Handler(Looper.getMainLooper());
            main.post(() -> {
                if (getActivity() == null) return;
                Toast.makeText(getActivity(), success ? R.string.report_sent_success : R.string.report_sent_failed, Toast.LENGTH_LONG).show();
            });
        });
    }

    private boolean postReport(String reportUrl, String json) {
        try {
            URL url = new URL(reportUrl);
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
                int response = connection.getResponseCode();
                return response >= 200 && response < 300;
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to send report: " + e.getMessage());
            return false;
        }
    }

    private static String deriveReportUrl(String dashboardUrl) {
        String trimmed = dashboardUrl == null ? "" : dashboardUrl.trim();
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

    private static String normalizeReportUrl(String reportApiUrl) {
        String trimmed = reportApiUrl == null ? "" : reportApiUrl.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.endsWith("/report")) {
            return trimmed;
        }
        if (trimmed.endsWith("/location") || trimmed.endsWith("/location/")) {
            return deriveReportUrl(trimmed);
        }
        if (trimmed.endsWith("/")) {
            return trimmed + "report";
        }
        return trimmed + "/report";
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

    private static List<WilayahOption> parseWilayahOptions(JSONArray array) {
        List<WilayahOption> options = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "");
            String name = item.optString("name", "").trim();
            if (id.isEmpty() || name.isEmpty()) continue;
            options.add(new WilayahOption(id, name));
        }
        return options;
    }

    private static String buildReportJson(long timestamp, String namaPelapor, String kabKota, String kecamatan, String kelurahan, String kategori, String deskripsi) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"timestamp\":").append(timestamp).append(",");
        sb.append("\"namaPelapor\":\"").append(escapeJson(namaPelapor)).append("\",");
        sb.append("\"kabKota\":\"").append(escapeJson(kabKota)).append("\",");
        sb.append("\"kecamatan\":\"").append(escapeJson(kecamatan)).append("\",");
        sb.append("\"kelurahan\":\"").append(escapeJson(kelurahan)).append("\",");
        sb.append("\"kategori\":\"").append(escapeJson(kategori)).append("\",");
        sb.append("\"deskripsi\":\"").append(escapeJson(deskripsi)).append("\"");
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static class WilayahOption {
        final String id;
        final String name;

        WilayahOption(String id, String name) {
            this.id = id;
            this.name = name;
        }

        static WilayahOption placeholder(String name) {
            return new WilayahOption("", name);
        }

        boolean hasValidId() {
            return id != null && !id.trim().isEmpty();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static class WilayahSelectionState {
        WilayahOption regency;
        WilayahOption district;
        WilayahOption village;

        boolean isSelectionValid() {
            return regency != null && regency.hasValidId()
                    && district != null && district.hasValidId()
                    && village != null && village.hasValidId();
        }

        String getKabKota() {
            return regency != null ? regency.name : "";
        }

        String getKecamatan() {
            return district != null ? district.name : "";
        }

        String getKelurahan() {
            return village != null ? village.name : "";
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getService() != null && getService().isConnected() &&
            !Settings.getInstance(getActivity()).isPushToTalkToggle()) {
            // XXX: This ensures that push to talk is disabled when we pause.
            // We don't want to leave the talk state active if the fragment is paused while pressed.
            getService().HumlaSession().setTalkingState(false);
        }
    }

    @Override
    public void onDestroy() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        super.onDestroy();
    }

    @Override
    public IHumlaObserver getServiceObserver() {
        return mObserver;
    }

    @Override
    public void onServiceBound(IHumlaService service) {
        super.onServiceBound(service);
        if (service.getConnectionState() == HumlaService.ConnectionState.CONNECTED) {
            configureTargetPanel();
            configureInput();
        }
    }

    private void configureTargetPanel() {
        if (getService() == null || !getService().isConnected()) {
            return;
        }

        IHumlaSession session = getService().HumlaSession();
        VoiceTargetMode mode = session.getVoiceTargetMode();
        if (mode == VoiceTargetMode.WHISPER) {
            WhisperTarget target = session.getWhisperTarget();
            mTargetPanel.setVisibility(View.VISIBLE);
            mTargetPanelText.setText(getString(R.string.shout_target, target.getName()));
        } else {
            mTargetPanel.setVisibility(View.GONE);
        }
    }

    /**
     * @return true if the channel fragment is set to display only the user's pinned channels.
     */
    private boolean isShowingPinnedChannels() {
        return getArguments() != null &&
               getArguments().getBoolean("pinned");
    }

    /**
     * Configures the fragment in accordance with the user's interface preferences.
     */
    private void configureInput() {
        Settings settings = Settings.getInstance(getActivity());

        ViewGroup.LayoutParams params = mTalkView.getLayoutParams();
        params.height = settings.getPTTButtonHeight();
        mTalkButton.setLayoutParams(params);

        boolean muted = false;
        if (getService() != null && getService().isConnected()) {
            IUser self = null;
            try {
                self = getService().HumlaSession().getSessionUser();
            } catch (HumlaDisconnectedException|IllegalStateException e) {
                Log.d(TAG, "exception in configureInput: " + e);
            }
            muted = self == null || self.isMuted() || self.isSuppressed() || self.isSelfMuted();
        }
        boolean showPttButton =
                !muted &&
                settings.isPushToTalkButtonShown() &&
                settings.getInputMethod().equals(Settings.ARRAY_INPUT_METHOD_PTT);
        setTalkButtonHidden(!showPttButton);
    }

    private void setTalkButtonHidden(final boolean hidden) {
        mTalkView.setVisibility(hidden ? View.GONE : View.VISIBLE);
        mTalkButtonHidden = hidden;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if(Settings.PREF_INPUT_METHOD.equals(key)
            || Settings.PREF_PUSH_BUTTON_HIDE_KEY.equals(key)
            || Settings.PREF_PTT_BUTTON_HEIGHT.equals(key))
            configureInput();
    }

    @Override
    public ChatTarget getChatTarget() {
        return mChatTarget;
    }

    @Override
    public void setChatTarget(ChatTarget target) {
        mChatTarget = target;
        for(OnChatTargetSelectedListener listener : mChatTargetListeners)
            listener.onChatTargetSelected(target);
    }

    @Override
    public void registerChatTargetListener(OnChatTargetSelectedListener listener) {
        mChatTargetListeners.add(listener);
    }

    @Override
    public void unregisterChatTargetListener(OnChatTargetSelectedListener listener) {
        mChatTargetListeners.remove(listener);
    }

    private class ChannelFragmentPagerAdapter extends FragmentPagerAdapter {

        public ChannelFragmentPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int i) {
            Fragment fragment = null;
            Bundle args = new Bundle();
            switch (i) {
                case 0:
                    fragment = new ChannelListFragment();
                    args.putBoolean("pinned", isShowingPinnedChannels());
                    break;
                case 1:
                    fragment = new ChannelChatFragment();
                    break;
            }
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            switch (position) {
                case 0:
                    return getString(R.string.channel).toUpperCase();
                case 1:
                    return getString(R.string.chat).toUpperCase();
                default:
                    return null;
            }
        }

        @Override
        public int getCount() {
            return 2;
        }
    }
}
