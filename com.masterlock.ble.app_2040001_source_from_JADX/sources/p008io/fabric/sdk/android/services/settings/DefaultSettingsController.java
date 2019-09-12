package p008io.fabric.sdk.android.services.settings;

import android.annotation.SuppressLint;
import android.content.SharedPreferences.Editor;
import org.json.JSONException;
import org.json.JSONObject;
import p008io.fabric.sdk.android.Fabric;
import p008io.fabric.sdk.android.Kit;
import p008io.fabric.sdk.android.Logger;
import p008io.fabric.sdk.android.services.common.CommonUtils;
import p008io.fabric.sdk.android.services.common.CurrentTimeProvider;
import p008io.fabric.sdk.android.services.persistence.PreferenceStore;
import p008io.fabric.sdk.android.services.persistence.PreferenceStoreImpl;

/* renamed from: io.fabric.sdk.android.services.settings.DefaultSettingsController */
class DefaultSettingsController implements SettingsController {
    private static final String LOAD_ERROR_MESSAGE = "Unknown error while loading Crashlytics settings. Crashes will be cached until settings can be retrieved.";
    private static final String PREFS_BUILD_INSTANCE_IDENTIFIER = "existing_instance_identifier";
    private final CachedSettingsIo cachedSettingsIo;
    private final CurrentTimeProvider currentTimeProvider;
    private final Kit kit;
    private final PreferenceStore preferenceStore = new PreferenceStoreImpl(this.kit);
    private final SettingsJsonTransform settingsJsonTransform;
    private final SettingsRequest settingsRequest;
    private final SettingsSpiCall settingsSpiCall;

    public DefaultSettingsController(Kit kit2, SettingsRequest settingsRequest2, CurrentTimeProvider currentTimeProvider2, SettingsJsonTransform settingsJsonTransform2, CachedSettingsIo cachedSettingsIo2, SettingsSpiCall settingsSpiCall2) {
        this.kit = kit2;
        this.settingsRequest = settingsRequest2;
        this.currentTimeProvider = currentTimeProvider2;
        this.settingsJsonTransform = settingsJsonTransform2;
        this.cachedSettingsIo = cachedSettingsIo2;
        this.settingsSpiCall = settingsSpiCall2;
    }

    public SettingsData loadSettingsData() {
        return loadSettingsData(SettingsCacheBehavior.USE_CACHE);
    }

    public SettingsData loadSettingsData(SettingsCacheBehavior settingsCacheBehavior) {
        SettingsData settingsData = null;
        try {
            if (!Fabric.isDebuggable() && !buildInstanceIdentifierChanged()) {
                settingsData = getCachedSettingsData(settingsCacheBehavior);
            }
            if (settingsData == null) {
                JSONObject invoke = this.settingsSpiCall.invoke(this.settingsRequest);
                if (invoke != null) {
                    settingsData = this.settingsJsonTransform.buildFromJson(this.currentTimeProvider, invoke);
                    this.cachedSettingsIo.writeCachedSettings(settingsData.expiresAtMillis, invoke);
                    logSettings(invoke, "Loaded settings: ");
                    setStoredBuildInstanceIdentifier(getBuildInstanceIdentifierFromContext());
                }
            }
            if (settingsData == null) {
                return getCachedSettingsData(SettingsCacheBehavior.IGNORE_CACHE_EXPIRATION);
            }
            return settingsData;
        } catch (Exception e) {
            Fabric.getLogger().mo21796e(Fabric.TAG, LOAD_ERROR_MESSAGE, e);
            return null;
        }
    }

    private SettingsData getCachedSettingsData(SettingsCacheBehavior settingsCacheBehavior) {
        SettingsData settingsData = null;
        try {
            if (SettingsCacheBehavior.SKIP_CACHE_LOOKUP.equals(settingsCacheBehavior)) {
                return null;
            }
            JSONObject readCachedSettings = this.cachedSettingsIo.readCachedSettings();
            if (readCachedSettings != null) {
                SettingsData buildFromJson = this.settingsJsonTransform.buildFromJson(this.currentTimeProvider, readCachedSettings);
                if (buildFromJson != null) {
                    logSettings(readCachedSettings, "Loaded cached settings: ");
                    long currentTimeMillis = this.currentTimeProvider.getCurrentTimeMillis();
                    if (!SettingsCacheBehavior.IGNORE_CACHE_EXPIRATION.equals(settingsCacheBehavior)) {
                        if (buildFromJson.isExpired(currentTimeMillis)) {
                            Fabric.getLogger().mo21793d(Fabric.TAG, "Cached settings have expired.");
                            return null;
                        }
                    }
                    try {
                        Fabric.getLogger().mo21793d(Fabric.TAG, "Returning cached settings.");
                        return buildFromJson;
                    } catch (Exception e) {
                        e = e;
                        settingsData = buildFromJson;
                        Fabric.getLogger().mo21796e(Fabric.TAG, "Failed to get cached settings", e);
                        return settingsData;
                    }
                } else {
                    Fabric.getLogger().mo21796e(Fabric.TAG, "Failed to transform cached settings data.", null);
                    return null;
                }
            } else {
                Fabric.getLogger().mo21793d(Fabric.TAG, "No cached settings data found.");
                return null;
            }
        } catch (Exception e2) {
            e = e2;
            Fabric.getLogger().mo21796e(Fabric.TAG, "Failed to get cached settings", e);
            return settingsData;
        }
    }

    private void logSettings(JSONObject jSONObject, String str) throws JSONException {
        Logger logger = Fabric.getLogger();
        String str2 = Fabric.TAG;
        StringBuilder sb = new StringBuilder();
        sb.append(str);
        sb.append(jSONObject.toString());
        logger.mo21793d(str2, sb.toString());
    }

    /* access modifiers changed from: 0000 */
    public String getBuildInstanceIdentifierFromContext() {
        return CommonUtils.createInstanceIdFrom(CommonUtils.resolveBuildId(this.kit.getContext()));
    }

    /* access modifiers changed from: 0000 */
    public String getStoredBuildInstanceIdentifier() {
        return this.preferenceStore.get().getString(PREFS_BUILD_INSTANCE_IDENTIFIER, "");
    }

    /* access modifiers changed from: 0000 */
    @SuppressLint({"CommitPrefEdits"})
    public boolean setStoredBuildInstanceIdentifier(String str) {
        Editor edit = this.preferenceStore.edit();
        edit.putString(PREFS_BUILD_INSTANCE_IDENTIFIER, str);
        return this.preferenceStore.save(edit);
    }

    /* access modifiers changed from: 0000 */
    public boolean buildInstanceIdentifierChanged() {
        return !getStoredBuildInstanceIdentifier().equals(getBuildInstanceIdentifierFromContext());
    }
}
