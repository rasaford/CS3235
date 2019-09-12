package com.google.common.reflect;

import com.google.common.annotations.Beta;
import com.google.common.collect.ForwardingMap;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

@Beta
public final class ImmutableTypeToInstanceMap<B> extends ForwardingMap<TypeToken<? extends B>, B> implements TypeToInstanceMap<B> {
    private final ImmutableMap<TypeToken<? extends B>, B> delegate;

    @Beta
    public static final class Builder<B> {
        private final com.google.common.collect.ImmutableMap.Builder<TypeToken<? extends B>, B> mapBuilder;

        private Builder() {
            this.mapBuilder = ImmutableMap.builder();
        }

        public <T extends B> Builder<B> put(Class<T> cls, T t) {
            this.mapBuilder.put(TypeToken.m162of(cls), t);
            return this;
        }

        public <T extends B> Builder<B> put(TypeToken<T> typeToken, T t) {
            this.mapBuilder.put(typeToken.rejectTypeVariables(), t);
            return this;
        }

        public ImmutableTypeToInstanceMap<B> build() {
            return new ImmutableTypeToInstanceMap<>(this.mapBuilder.build());
        }
    }

    /* renamed from: of */
    public static <B> ImmutableTypeToInstanceMap<B> m161of() {
        return new ImmutableTypeToInstanceMap<>(ImmutableMap.m84of());
    }

    public static <B> Builder<B> builder() {
        return new Builder<>();
    }

    private ImmutableTypeToInstanceMap(ImmutableMap<TypeToken<? extends B>, B> immutableMap) {
        this.delegate = immutableMap;
    }

    public <T extends B> T getInstance(TypeToken<T> typeToken) {
        return trustedGet(typeToken.rejectTypeVariables());
    }

    public <T extends B> T putInstance(TypeToken<T> typeToken, T t) {
        throw new UnsupportedOperationException();
    }

    public <T extends B> T getInstance(Class<T> cls) {
        return trustedGet(TypeToken.m162of(cls));
    }

    public <T extends B> T putInstance(Class<T> cls, T t) {
        throw new UnsupportedOperationException();
    }

    /* access modifiers changed from: protected */
    public Map<TypeToken<? extends B>, B> delegate() {
        return this.delegate;
    }

    private <T extends B> T trustedGet(TypeToken<T> typeToken) {
        return this.delegate.get(typeToken);
    }
}
