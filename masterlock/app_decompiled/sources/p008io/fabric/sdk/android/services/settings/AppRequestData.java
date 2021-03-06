package p008io.fabric.sdk.android.services.settings;

import java.util.Collection;
import p008io.fabric.sdk.android.KitInfo;

/* renamed from: io.fabric.sdk.android.services.settings.AppRequestData */
public class AppRequestData {
    public final String apiKey;
    public final String appId;
    public final String buildVersion;
    public final String builtSdkVersion;
    public final String displayVersion;
    public final IconRequest icon;
    public final String instanceIdentifier;
    public final String minSdkVersion;
    public final String name;
    public final Collection<KitInfo> sdkKits;
    public final int source;

    public AppRequestData(String str, String str2, String str3, String str4, String str5, String str6, int i, String str7, String str8, IconRequest iconRequest, Collection<KitInfo> collection) {
        this.apiKey = str;
        this.appId = str2;
        this.displayVersion = str3;
        this.buildVersion = str4;
        this.instanceIdentifier = str5;
        this.name = str6;
        this.source = i;
        this.minSdkVersion = str7;
        this.builtSdkVersion = str8;
        this.icon = iconRequest;
        this.sdkKits = collection;
    }
}
