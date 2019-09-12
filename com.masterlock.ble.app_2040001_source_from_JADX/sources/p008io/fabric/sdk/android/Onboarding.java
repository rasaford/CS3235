package p008io.fabric.sdk.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import p008io.fabric.sdk.android.services.common.ApiKey;
import p008io.fabric.sdk.android.services.common.CommonUtils;
import p008io.fabric.sdk.android.services.common.DeliveryMechanism;
import p008io.fabric.sdk.android.services.common.IdManager;
import p008io.fabric.sdk.android.services.network.DefaultHttpRequestFactory;
import p008io.fabric.sdk.android.services.network.HttpRequestFactory;
import p008io.fabric.sdk.android.services.settings.AppRequestData;
import p008io.fabric.sdk.android.services.settings.AppSettingsData;
import p008io.fabric.sdk.android.services.settings.CreateAppSpiCall;
import p008io.fabric.sdk.android.services.settings.IconRequest;
import p008io.fabric.sdk.android.services.settings.Settings;
import p008io.fabric.sdk.android.services.settings.SettingsData;
import p008io.fabric.sdk.android.services.settings.UpdateAppSpiCall;

/* renamed from: io.fabric.sdk.android.Onboarding */
class Onboarding extends Kit<Boolean> {
    private static final String BINARY_BUILD_TYPE = "binary";
    static final String CRASHLYTICS_API_ENDPOINT = "com.crashlytics.ApiEndpoint";
    private String applicationLabel;
    private String installerPackageName;
    private final Future<Map<String, KitInfo>> kitsFinder;
    private PackageInfo packageInfo;
    private PackageManager packageManager;
    private String packageName;
    private final Collection<Kit> providedKits;
    private final HttpRequestFactory requestFactory = new DefaultHttpRequestFactory();
    private String targetAndroidSdkVersion;
    private String versionCode;
    private String versionName;

    public String getIdentifier() {
        return "io.fabric.sdk.android:fabric";
    }

    public String getVersion() {
        return "1.3.10.97";
    }

    public Onboarding(Future<Map<String, KitInfo>> future, Collection<Kit> collection) {
        this.kitsFinder = future;
        this.providedKits = collection;
    }

    /* access modifiers changed from: protected */
    public boolean onPreExecute() {
        try {
            this.installerPackageName = getIdManager().getInstallerPackageName();
            this.packageManager = getContext().getPackageManager();
            this.packageName = getContext().getPackageName();
            this.packageInfo = this.packageManager.getPackageInfo(this.packageName, 0);
            this.versionCode = Integer.toString(this.packageInfo.versionCode);
            this.versionName = this.packageInfo.versionName == null ? IdManager.DEFAULT_VERSION_NAME : this.packageInfo.versionName;
            this.applicationLabel = this.packageManager.getApplicationLabel(getContext().getApplicationInfo()).toString();
            this.targetAndroidSdkVersion = Integer.toString(getContext().getApplicationInfo().targetSdkVersion);
            return true;
        } catch (NameNotFoundException e) {
            Fabric.getLogger().mo21796e(Fabric.TAG, "Failed init", e);
            return false;
        }
    }

    /* access modifiers changed from: protected */
    public Boolean doInBackground() {
        boolean z;
        Map map;
        String appIconHashOrNull = CommonUtils.getAppIconHashOrNull(getContext());
        SettingsData retrieveSettingsData = retrieveSettingsData();
        if (retrieveSettingsData != null) {
            try {
                if (this.kitsFinder != null) {
                    map = (Map) this.kitsFinder.get();
                } else {
                    map = new HashMap();
                }
                z = performAutoConfigure(appIconHashOrNull, retrieveSettingsData.appData, mergeKits(map, this.providedKits).values());
            } catch (Exception e) {
                Fabric.getLogger().mo21796e(Fabric.TAG, "Error performing auto configuration.", e);
            }
            return Boolean.valueOf(z);
        }
        z = false;
        return Boolean.valueOf(z);
    }

    private SettingsData retrieveSettingsData() {
        try {
            Settings.getInstance().initialize(this, this.idManager, this.requestFactory, this.versionCode, this.versionName, getOverridenSpiEndpoint()).loadSettingsData();
            return Settings.getInstance().awaitSettingsData();
        } catch (Exception e) {
            Fabric.getLogger().mo21796e(Fabric.TAG, "Error dealing with settings", e);
            return null;
        }
    }

    /* access modifiers changed from: 0000 */
    public Map<String, KitInfo> mergeKits(Map<String, KitInfo> map, Collection<Kit> collection) {
        for (Kit kit : collection) {
            if (!map.containsKey(kit.getIdentifier())) {
                map.put(kit.getIdentifier(), new KitInfo(kit.getIdentifier(), kit.getVersion(), "binary"));
            }
        }
        return map;
    }

    private boolean performAutoConfigure(String str, AppSettingsData appSettingsData, Collection<KitInfo> collection) {
        if (AppSettingsData.STATUS_NEW.equals(appSettingsData.status)) {
            if (performCreateApp(str, appSettingsData, collection)) {
                return Settings.getInstance().loadSettingsSkippingCache();
            }
            Fabric.getLogger().mo21796e(Fabric.TAG, "Failed to create app with Crashlytics service.", null);
            return false;
        } else if (AppSettingsData.STATUS_CONFIGURED.equals(appSettingsData.status)) {
            return Settings.getInstance().loadSettingsSkippingCache();
        } else {
            if (appSettingsData.updateRequired) {
                Fabric.getLogger().mo21793d(Fabric.TAG, "Server says an update is required - forcing a full App update.");
                performUpdateApp(str, appSettingsData, collection);
            }
            return true;
        }
    }

    private boolean performCreateApp(String str, AppSettingsData appSettingsData, Collection<KitInfo> collection) {
        return new CreateAppSpiCall(this, getOverridenSpiEndpoint(), appSettingsData.url, this.requestFactory).invoke(buildAppRequest(IconRequest.build(getContext(), str), collection));
    }

    private boolean performUpdateApp(String str, AppSettingsData appSettingsData, Collection<KitInfo> collection) {
        return performUpdateApp(appSettingsData, IconRequest.build(getContext(), str), collection);
    }

    private boolean performUpdateApp(AppSettingsData appSettingsData, IconRequest iconRequest, Collection<KitInfo> collection) {
        return new UpdateAppSpiCall(this, getOverridenSpiEndpoint(), appSettingsData.url, this.requestFactory).invoke(buildAppRequest(iconRequest, collection));
    }

    private AppRequestData buildAppRequest(IconRequest iconRequest, Collection<KitInfo> collection) {
        Context context = getContext();
        AppRequestData appRequestData = new AppRequestData(new ApiKey().getValue(context), getIdManager().getAppIdentifier(), this.versionName, this.versionCode, CommonUtils.createInstanceIdFrom(CommonUtils.resolveBuildId(context)), this.applicationLabel, DeliveryMechanism.determineFrom(this.installerPackageName).getId(), this.targetAndroidSdkVersion, "0", iconRequest, collection);
        return appRequestData;
    }

    /* access modifiers changed from: 0000 */
    public String getOverridenSpiEndpoint() {
        return CommonUtils.getStringsFileValue(getContext(), CRASHLYTICS_API_ENDPOINT);
    }
}
