package p008io.fabric.sdk.android;

import android.content.Context;
import java.io.File;
import java.util.Collection;
import p008io.fabric.sdk.android.services.common.IdManager;
import p008io.fabric.sdk.android.services.concurrency.DependsOn;
import p008io.fabric.sdk.android.services.concurrency.Task;

/* renamed from: io.fabric.sdk.android.Kit */
public abstract class Kit<Result> implements Comparable<Kit> {
    Context context;
    Fabric fabric;
    IdManager idManager;
    InitializationCallback<Result> initializationCallback;
    InitializationTask<Result> initializationTask = new InitializationTask<>(this);

    /* access modifiers changed from: protected */
    public abstract Result doInBackground();

    public abstract String getIdentifier();

    public abstract String getVersion();

    /* access modifiers changed from: protected */
    public void onCancelled(Result result) {
    }

    /* access modifiers changed from: protected */
    public void onPostExecute(Result result) {
    }

    /* access modifiers changed from: protected */
    public boolean onPreExecute() {
        return true;
    }

    /* access modifiers changed from: 0000 */
    public void injectParameters(Context context2, Fabric fabric2, InitializationCallback<Result> initializationCallback2, IdManager idManager2) {
        this.fabric = fabric2;
        this.context = new FabricContext(context2, getIdentifier(), getPath());
        this.initializationCallback = initializationCallback2;
        this.idManager = idManager2;
    }

    /* access modifiers changed from: 0000 */
    public final void initialize() {
        this.initializationTask.executeOnExecutor(this.fabric.getExecutorService(), null);
    }

    /* access modifiers changed from: protected */
    public IdManager getIdManager() {
        return this.idManager;
    }

    public Context getContext() {
        return this.context;
    }

    public Fabric getFabric() {
        return this.fabric;
    }

    public String getPath() {
        StringBuilder sb = new StringBuilder();
        sb.append(".Fabric");
        sb.append(File.separator);
        sb.append(getIdentifier());
        return sb.toString();
    }

    public int compareTo(Kit kit) {
        if (containsAnnotatedDependency(kit)) {
            return 1;
        }
        if (kit.containsAnnotatedDependency(this)) {
            return -1;
        }
        if (hasAnnotatedDependency() && !kit.hasAnnotatedDependency()) {
            return 1;
        }
        if (hasAnnotatedDependency() || !kit.hasAnnotatedDependency()) {
            return 0;
        }
        return -1;
    }

    /* access modifiers changed from: 0000 */
    public boolean containsAnnotatedDependency(Kit kit) {
        DependsOn dependsOn = (DependsOn) getClass().getAnnotation(DependsOn.class);
        if (dependsOn != null) {
            for (Class equals : dependsOn.value()) {
                if (equals.equals(kit.getClass())) {
                    return true;
                }
            }
        }
        return false;
    }

    /* access modifiers changed from: 0000 */
    public boolean hasAnnotatedDependency() {
        return ((DependsOn) getClass().getAnnotation(DependsOn.class)) != null;
    }

    /* access modifiers changed from: protected */
    public Collection<Task> getDependencies() {
        return this.initializationTask.getDependencies();
    }
}
