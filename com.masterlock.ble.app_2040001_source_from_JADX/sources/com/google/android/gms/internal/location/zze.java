package com.google.android.gms.internal.location;

import android.app.PendingIntent;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.ActivityRecognitionApi;
import com.google.android.gms.location.ActivityTransitionRequest;

public final class zze implements ActivityRecognitionApi {
    public final PendingResult<Status> removeActivityUpdates(GoogleApiClient googleApiClient, PendingIntent pendingIntent) {
        return googleApiClient.execute(new zzg(this, googleApiClient, pendingIntent));
    }

    public final PendingResult<Status> requestActivityUpdates(GoogleApiClient googleApiClient, long j, PendingIntent pendingIntent) {
        zzf zzf = new zzf(this, googleApiClient, j, pendingIntent);
        return googleApiClient.execute(zzf);
    }

    public final PendingResult<Status> zza(GoogleApiClient googleApiClient, PendingIntent pendingIntent) {
        return googleApiClient.execute(new zzi(this, googleApiClient, pendingIntent));
    }

    public final PendingResult<Status> zza(GoogleApiClient googleApiClient, ActivityTransitionRequest activityTransitionRequest, PendingIntent pendingIntent) {
        return googleApiClient.execute(new zzh(this, googleApiClient, activityTransitionRequest, pendingIntent));
    }
}