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
import java.io.OutputStream;

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
        final EditText districtCityField = dialogView.findViewById(R.id.report_district_city);
        final EditText categoryField = dialogView.findViewById(R.id.report_category);
        final EditText amountField = dialogView.findViewById(R.id.report_amount);
        final EditText descriptionField = dialogView.findViewById(R.id.report_description);
        reportApiUrlField.setText(getInitialReportApiUrl());

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getActivity())
                .setTitle(R.string.create_report_title)
                .setView(dialogView)
                .setPositiveButton(R.string.report_submit, (dialog, which) -> {
                    String reportApiUrl = reportApiUrlField.getText().toString().trim();
                    String districtCity = districtCityField.getText().toString().trim();
                    String category = categoryField.getText().toString().trim();
                    String amountText = amountField.getText().toString().trim();
                    String description = descriptionField.getText().toString().trim();
                    submitReport(reportApiUrl, districtCity, category, amountText, description);
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

    private void submitReport(String reportApiUrl, String districtCity, String category, String amountText, String description) {
        if (reportApiUrl.isEmpty()) {
            Toast.makeText(getActivity(), R.string.report_missing_dashboard_url, Toast.LENGTH_LONG).show();
            return;
        }
        if (districtCity.isEmpty() || category.isEmpty() || amountText.isEmpty() || description.isEmpty()) {
            Toast.makeText(getActivity(), R.string.report_invalid_fields, Toast.LENGTH_LONG).show();
            return;
        }

        final double amount;
        try {
            amount = Double.parseDouble(amountText);
        } catch (NumberFormatException e) {
            Toast.makeText(getActivity(), R.string.report_invalid_fields, Toast.LENGTH_LONG).show();
            return;
        }
        if (amount < 0) {
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
        final String json = buildReportJson(timestamp, districtCity, category, amount, description);

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

    private static String buildReportJson(long timestamp, String districtCity, String category, double amount, String description) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"timestamp\":").append(timestamp).append(",");
        sb.append("\"districtCity\":\"").append(escapeJson(districtCity)).append("\",");
        sb.append("\"category\":\"").append(escapeJson(category)).append("\",");
        sb.append("\"amount\":").append(String.format(Locale.US, "%.3f", amount)).append(",");
        sb.append("\"description\":\"").append(escapeJson(description)).append("\"");
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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
