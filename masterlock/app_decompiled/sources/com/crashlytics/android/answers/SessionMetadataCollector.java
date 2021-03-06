package com.crashlytics.android.answers;

import android.content.Context;
import java.util.Map;
import java.util.UUID;
import p008io.fabric.sdk.android.services.common.CommonUtils;
import p008io.fabric.sdk.android.services.common.IdManager;
import p008io.fabric.sdk.android.services.common.IdManager.DeviceIdentifierType;

class SessionMetadataCollector {
    private final Context context;
    private final IdManager idManager;
    private final String versionCode;
    private final String versionName;

    public SessionMetadataCollector(Context context2, IdManager idManager2, String str, String str2) {
        this.context = context2;
        this.idManager = idManager2;
        this.versionCode = str;
        this.versionName = str2;
    }

    public SessionEventMetadata getMetadata() {
        Map deviceIdentifiers = this.idManager.getDeviceIdentifiers();
        SessionEventMetadata sessionEventMetadata = new SessionEventMetadata(this.idManager.getAppIdentifier(), UUID.randomUUID().toString(), this.idManager.getAppInstallIdentifier(), (String) deviceIdentifiers.get(DeviceIdentifierType.ANDROID_ID), (String) deviceIdentifiers.get(DeviceIdentifierType.ANDROID_ADVERTISING_ID), this.idManager.isLimitAdTrackingEnabled(), (String) deviceIdentifiers.get(DeviceIdentifierType.FONT_TOKEN), CommonUtils.resolveBuildId(this.context), this.idManager.getOsVersionString(), this.idManager.getModelName(), this.versionCode, this.versionName);
        return sessionEventMetadata;
    }
}
