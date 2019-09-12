package butterknife;

import android.view.View;
import java.util.AbstractList;
import java.util.RandomAccess;

final class ImmutableViewList<T extends View> extends AbstractList<T> implements RandomAccess {
    private final T[] views;

    ImmutableViewList(T[] tArr) {
        this.views = tArr;
    }

    public T get(int i) {
        return this.views[i];
    }

    public int size() {
        return this.views.length;
    }

    public boolean contains(Object obj) {
        for (T t : this.views) {
            if (t == obj) {
                return true;
            }
        }
        return false;
    }
}