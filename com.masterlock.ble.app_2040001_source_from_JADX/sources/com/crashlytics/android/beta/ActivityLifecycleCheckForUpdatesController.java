package com.crashlytics.android.beta;

import android.annotation.TargetApi;
import android.app.Activity;
import java.util.concurrent.ExecutorService;
import p008io.fabric.sdk.android.ActivityLifecycleManager;
import p008io.fabric.sdk.android.ActivityLifecycleManager.Callbacks;

@TargetApi(14)
class ActivityLifecycleCheckForUpdatesController extends AbstractCheckForUpdatesController {
    private final Callbacks callbacks = new Callbacks() {
        public void onActivityStarted(Activity activity) {
            if (ActivityLifecycleCheckForUpdatesController.this.signalExternallyReady()) {
                ActivityLifecycleCheckForUpdatesController.this.executorService.submit(new Runnable() {
                    public void run() {
                        ActivityLifecycleCheckForUpdatesController.this.checkForUpdates();
                    }
                });
            }
        }
    };
    /* access modifiers changed from: private */
    public final ExecutorService executorService;

    public boolean isActivityLifecycleTriggered() {
        return true;
    }

    public ActivityLifecycleCheckForUpdatesController(ActivityLifecycleManager activityLifecycleManager, ExecutorService executorService2) {
        this.executorService = executorService2;
        activityLifecycleManager.registerCallbacks(this.callbacks);
    }
}
