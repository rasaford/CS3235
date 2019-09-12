package p008io.fabric.sdk.android.services.common;

/* renamed from: io.fabric.sdk.android.services.common.ResponseParser */
public class ResponseParser {
    public static final int ResponseActionDiscard = 0;
    public static final int ResponseActionRetry = 1;

    public static int parse(int i) {
        if (i >= 200 && i <= 299) {
            return 0;
        }
        if (i >= 300 && i <= 399) {
            return 1;
        }
        if (i < 400 || i > 499) {
            return i >= 500 ? 1 : 1;
        }
        return 0;
    }
}
