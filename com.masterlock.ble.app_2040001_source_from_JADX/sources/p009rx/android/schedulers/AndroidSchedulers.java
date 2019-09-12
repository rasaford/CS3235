package p009rx.android.schedulers;

import android.os.Looper;
import java.util.concurrent.atomic.AtomicReference;
import p009rx.Scheduler;
import p009rx.android.plugins.RxAndroidPlugins;
import p009rx.annotations.Experimental;

/* renamed from: rx.android.schedulers.AndroidSchedulers */
public final class AndroidSchedulers {
    private static final AtomicReference<AndroidSchedulers> INSTANCE = new AtomicReference<>();
    private final Scheduler mainThreadScheduler;

    private static AndroidSchedulers getInstance() {
        AndroidSchedulers androidSchedulers;
        do {
            AndroidSchedulers androidSchedulers2 = (AndroidSchedulers) INSTANCE.get();
            if (androidSchedulers2 != null) {
                return androidSchedulers2;
            }
            androidSchedulers = new AndroidSchedulers();
        } while (!INSTANCE.compareAndSet(null, androidSchedulers));
        return androidSchedulers;
    }

    private AndroidSchedulers() {
        Scheduler mainThreadScheduler2 = RxAndroidPlugins.getInstance().getSchedulersHook().getMainThreadScheduler();
        if (mainThreadScheduler2 != null) {
            this.mainThreadScheduler = mainThreadScheduler2;
        } else {
            this.mainThreadScheduler = new LooperScheduler(Looper.getMainLooper());
        }
    }

    public static Scheduler mainThread() {
        return getInstance().mainThreadScheduler;
    }

    public static Scheduler from(Looper looper) {
        if (looper != null) {
            return new LooperScheduler(looper);
        }
        throw new NullPointerException("looper == null");
    }

    @Experimental
    public static void reset() {
        INSTANCE.set(null);
    }
}
