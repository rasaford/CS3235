package android.support.p000v4.p002os;

import android.support.annotation.RestrictTo;
import android.support.annotation.RestrictTo.Scope;
import java.util.Locale;
import p008io.fabric.sdk.android.services.events.EventsFilesManager;

@RestrictTo({Scope.LIBRARY_GROUP})
/* renamed from: android.support.v4.os.LocaleHelper */
final class LocaleHelper {
    static Locale forLanguageTag(String str) {
        if (str.contains("-")) {
            String[] split = str.split("-", -1);
            if (split.length > 2) {
                return new Locale(split[0], split[1], split[2]);
            }
            if (split.length > 1) {
                return new Locale(split[0], split[1]);
            }
            if (split.length == 1) {
                return new Locale(split[0]);
            }
        } else if (!str.contains(EventsFilesManager.ROLL_OVER_FILE_NAME_SEPARATOR)) {
            return new Locale(str);
        } else {
            String[] split2 = str.split(EventsFilesManager.ROLL_OVER_FILE_NAME_SEPARATOR, -1);
            if (split2.length > 2) {
                return new Locale(split2[0], split2[1], split2[2]);
            }
            if (split2.length > 1) {
                return new Locale(split2[0], split2[1]);
            }
            if (split2.length == 1) {
                return new Locale(split2[0]);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Can not parse language tag: [");
        sb.append(str);
        sb.append("]");
        throw new IllegalArgumentException(sb.toString());
    }

    static String toLanguageTag(Locale locale) {
        StringBuilder sb = new StringBuilder();
        sb.append(locale.getLanguage());
        String country = locale.getCountry();
        if (country != null && !country.isEmpty()) {
            sb.append("-");
            sb.append(locale.getCountry());
        }
        return sb.toString();
    }

    private LocaleHelper() {
    }
}
