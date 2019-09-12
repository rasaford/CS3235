package com.google.android.gms.internal.location;

import android.app.PendingIntent;
import android.util.Log;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.common.api.internal.BaseImplementation.ResultHolder;
import com.google.android.gms.location.LocationStatusCodes;

final class zzbb extends zzan {
    private ResultHolder<Status> zzdf;

    public zzbb(ResultHolder<Status> resultHolder) {
        this.zzdf = resultHolder;
    }

    private final void zze(int i) {
        if (this.zzdf == null) {
            Log.wtf("LocationClientImpl", "onRemoveGeofencesResult called multiple times");
            return;
        }
        this.zzdf.setResult(LocationStatusCodes.zzd(LocationStatusCodes.zzc(i)));
        this.zzdf = null;
    }

    public final void zza(int i, PendingIntent pendingIntent) {
        zze(i);
    }

    public final void zza(int i, String[] strArr) {
        Log.wtf("LocationClientImpl", "Unexpected call to onAddGeofencesResult");
    }

    public final void zzb(int i, String[] strArr) {
        zze(i);
    }
}
