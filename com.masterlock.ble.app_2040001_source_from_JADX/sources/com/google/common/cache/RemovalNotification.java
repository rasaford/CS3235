package com.google.common.cache;

import com.google.common.annotations.Beta;
import com.google.common.annotations.GwtCompatible;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import java.util.Map.Entry;
import javax.annotation.Nullable;

@GwtCompatible
@Beta
public final class RemovalNotification<K, V> implements Entry<K, V> {
    private static final long serialVersionUID = 0;
    private final RemovalCause cause;
    @Nullable
    private final K key;
    @Nullable
    private final V value;

    RemovalNotification(@Nullable K k, @Nullable V v, RemovalCause removalCause) {
        this.key = k;
        this.value = v;
        this.cause = (RemovalCause) Preconditions.checkNotNull(removalCause);
    }

    public RemovalCause getCause() {
        return this.cause;
    }

    public boolean wasEvicted() {
        return this.cause.wasEvicted();
    }

    @Nullable
    public K getKey() {
        return this.key;
    }

    @Nullable
    public V getValue() {
        return this.value;
    }

    public final V setValue(V v) {
        throw new UnsupportedOperationException();
    }

    public boolean equals(@Nullable Object obj) {
        boolean z = false;
        if (!(obj instanceof Entry)) {
            return false;
        }
        Entry entry = (Entry) obj;
        if (Objects.equal(getKey(), entry.getKey()) && Objects.equal(getValue(), entry.getValue())) {
            z = true;
        }
        return z;
    }

    public int hashCode() {
        int i;
        Object key2 = getKey();
        Object value2 = getValue();
        int i2 = 0;
        if (key2 == null) {
            i = 0;
        } else {
            i = key2.hashCode();
        }
        if (value2 != null) {
            i2 = value2.hashCode();
        }
        return i ^ i2;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getKey());
        sb.append("=");
        sb.append(getValue());
        return sb.toString();
    }
}
