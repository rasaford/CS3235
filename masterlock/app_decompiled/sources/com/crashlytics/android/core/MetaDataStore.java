package com.crashlytics.android.core;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;
import p008io.fabric.sdk.android.Fabric;
import p008io.fabric.sdk.android.services.common.CommonUtils;
import p008io.fabric.sdk.android.services.network.HttpRequest;

class MetaDataStore {
    private static final String KEYDATA_SUFFIX = "keys";
    private static final String KEY_USER_EMAIL = "userEmail";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_USER_NAME = "userName";
    private static final String METADATA_EXT = ".meta";
    private static final String USERDATA_SUFFIX = "user";
    private static final Charset UTF_8 = Charset.forName(HttpRequest.CHARSET_UTF8);
    private final File filesDir;

    public MetaDataStore(File file) {
        this.filesDir = file;
    }

    public void writeUserData(String str, UserMetaData userMetaData) {
        File userDataFileForSession = getUserDataFileForSession(str);
        BufferedWriter bufferedWriter = null;
        try {
            String userDataToJson = userDataToJson(userMetaData);
            BufferedWriter bufferedWriter2 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(userDataFileForSession), UTF_8));
            try {
                bufferedWriter2.write(userDataToJson);
                bufferedWriter2.flush();
                CommonUtils.closeOrLog(bufferedWriter2, "Failed to close user metadata file.");
            } catch (Exception e) {
                e = e;
                bufferedWriter = bufferedWriter2;
                try {
                    Fabric.getLogger().mo21796e(CrashlyticsCore.TAG, "Error serializing user metadata.", e);
                    CommonUtils.closeOrLog(bufferedWriter, "Failed to close user metadata file.");
                } catch (Throwable th) {
                    th = th;
                    CommonUtils.closeOrLog(bufferedWriter, "Failed to close user metadata file.");
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
                bufferedWriter = bufferedWriter2;
                CommonUtils.closeOrLog(bufferedWriter, "Failed to close user metadata file.");
                throw th;
            }
        } catch (Exception e2) {
            e = e2;
            Fabric.getLogger().mo21796e(CrashlyticsCore.TAG, "Error serializing user metadata.", e);
            CommonUtils.closeOrLog(bufferedWriter, "Failed to close user metadata file.");
        }
    }

    public UserMetaData readUserData(String str) {
        File userDataFileForSession = getUserDataFileForSession(str);
        if (!userDataFileForSession.exists()) {
            return UserMetaData.EMPTY;
        }
        FileInputStream fileInputStream = null;
        try {
            FileInputStream fileInputStream2 = new FileInputStream(userDataFileForSession);
            try {
                UserMetaData jsonToUserData = jsonToUserData(CommonUtils.streamToString(fileInputStream2));
                CommonUtils.closeOrLog(fileInputStream2, "Failed to close user metadata file.");
                return jsonToUserData;
            } catch (Exception e) {
                e = e;
                fileInputStream = fileInputStream2;
                try {
                    Fabric.getLogger().mo21796e(CrashlyticsCore.TAG, "Error deserializing user metadata.", e);
                    CommonUtils.closeOrLog(fileInputStream, "Failed to close user metadata file.");
                    return UserMetaData.EMPTY;
                } catch (Throwable th) {
                    th = th;
                    CommonUtils.closeOrLog(fileInputStream, "Failed to close user metadata file.");
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
                fileInputStream = fileInputStream2;
                CommonUtils.closeOrLog(fileInputStream, "Failed to close user metadata file.");
                throw th;
            }
        } catch (Exception e2) {
            e = e2;
            Fabric.getLogger().mo21796e(CrashlyticsCore.TAG, "Error deserializing user metadata.", e);
            CommonUtils.closeOrLog(fileInputStream, "Failed to close user metadata file.");
            return UserMetaData.EMPTY;
        }
    }

    public void writeKeyData(String str, Map<String, String> map) {
        File keysFileForSession = getKeysFileForSession(str);
        BufferedWriter bufferedWriter = null;
        try {
            String keysDataToJson = keysDataToJson(map);
            BufferedWriter bufferedWriter2 = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(keysFileForSession), UTF_8));
            try {
                bufferedWriter2.write(keysDataToJson);
                bufferedWriter2.flush();
                CommonUtils.closeOrLog(bufferedWriter2, "Failed to close key/value metadata file.");
            } catch (Exception e) {
                e = e;
                bufferedWriter = bufferedWriter2;
                try {
                    Fabric.getLogger().mo21796e(CrashlyticsCore.TAG, "Error serializing key/value metadata.", e);
                    CommonUtils.closeOrLog(bufferedWriter, "Failed to close key/value metadata file.");
                } catch (Throwable th) {
                    th = th;
                    CommonUtils.closeOrLog(bufferedWriter, "Failed to close key/value metadata file.");
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
                bufferedWriter = bufferedWriter2;
                CommonUtils.closeOrLog(bufferedWriter, "Failed to close key/value metadata file.");
                throw th;
            }
        } catch (Exception e2) {
            e = e2;
            Fabric.getLogger().mo21796e(CrashlyticsCore.TAG, "Error serializing key/value metadata.", e);
            CommonUtils.closeOrLog(bufferedWriter, "Failed to close key/value metadata file.");
        }
    }

    public Map<String, String> readKeyData(String str) {
        File keysFileForSession = getKeysFileForSession(str);
        if (!keysFileForSession.exists()) {
            return Collections.emptyMap();
        }
        FileInputStream fileInputStream = null;
        try {
            FileInputStream fileInputStream2 = new FileInputStream(keysFileForSession);
            try {
                Map<String, String> jsonToKeysData = jsonToKeysData(CommonUtils.streamToString(fileInputStream2));
                CommonUtils.closeOrLog(fileInputStream2, "Failed to close user metadata file.");
                return jsonToKeysData;
            } catch (Exception e) {
                e = e;
                fileInputStream = fileInputStream2;
                try {
                    Fabric.getLogger().mo21796e(CrashlyticsCore.TAG, "Error deserializing user metadata.", e);
                    CommonUtils.closeOrLog(fileInputStream, "Failed to close user metadata file.");
                    return Collections.emptyMap();
                } catch (Throwable th) {
                    th = th;
                    CommonUtils.closeOrLog(fileInputStream, "Failed to close user metadata file.");
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
                fileInputStream = fileInputStream2;
                CommonUtils.closeOrLog(fileInputStream, "Failed to close user metadata file.");
                throw th;
            }
        } catch (Exception e2) {
            e = e2;
            Fabric.getLogger().mo21796e(CrashlyticsCore.TAG, "Error deserializing user metadata.", e);
            CommonUtils.closeOrLog(fileInputStream, "Failed to close user metadata file.");
            return Collections.emptyMap();
        }
    }

    private File getUserDataFileForSession(String str) {
        File file = this.filesDir;
        StringBuilder sb = new StringBuilder();
        sb.append(str);
        sb.append(USERDATA_SUFFIX);
        sb.append(METADATA_EXT);
        return new File(file, sb.toString());
    }

    private File getKeysFileForSession(String str) {
        File file = this.filesDir;
        StringBuilder sb = new StringBuilder();
        sb.append(str);
        sb.append("keys");
        sb.append(METADATA_EXT);
        return new File(file, sb.toString());
    }

    private static UserMetaData jsonToUserData(String str) throws JSONException {
        JSONObject jSONObject = new JSONObject(str);
        return new UserMetaData(valueOrNull(jSONObject, KEY_USER_ID), valueOrNull(jSONObject, "userName"), valueOrNull(jSONObject, "userEmail"));
    }

    private static String userDataToJson(final UserMetaData userMetaData) throws JSONException {
        return new JSONObject() {
            {
                put(MetaDataStore.KEY_USER_ID, userMetaData.f32id);
                put("userName", userMetaData.name);
                put("userEmail", userMetaData.email);
            }
        }.toString();
    }

    private static Map<String, String> jsonToKeysData(String str) throws JSONException {
        JSONObject jSONObject = new JSONObject(str);
        HashMap hashMap = new HashMap();
        Iterator keys = jSONObject.keys();
        while (keys.hasNext()) {
            String str2 = (String) keys.next();
            hashMap.put(str2, valueOrNull(jSONObject, str2));
        }
        return hashMap;
    }

    private static String keysDataToJson(Map<String, String> map) throws JSONException {
        return new JSONObject(map).toString();
    }

    private static String valueOrNull(JSONObject jSONObject, String str) {
        if (!jSONObject.isNull(str)) {
            return jSONObject.optString(str, null);
        }
        return null;
    }
}
