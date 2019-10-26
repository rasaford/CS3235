package com.google.common.cache;

import com.google.common.annotations.GwtCompatible;
import com.google.common.annotations.GwtIncompatible;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Equivalence;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.cache.AbstractCache.SimpleStatsCounter;
import com.google.common.cache.AbstractCache.StatsCounter;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.collect.AbstractSequentialIterator;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.common.util.concurrent.Uninterruptibles;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.AbstractMap;
import java.util.AbstractQueue;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

@GwtCompatible(emulated = true)
class LocalCache<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {
    static final int CONTAINS_VALUE_RETRIES = 3;
    static final Queue<? extends Object> DISCARDING_QUEUE = new AbstractQueue<Object>() {
        public boolean offer(Object obj) {
            return true;
        }

        public Object peek() {
            return null;
        }

        public Object poll() {
            return null;
        }

        public int size() {
            return 0;
        }

        public Iterator<Object> iterator() {
            return Iterators.emptyIterator();
        }
    };
    static final int DRAIN_MAX = 16;
    static final int DRAIN_THRESHOLD = 63;
    static final int MAXIMUM_CAPACITY = 1073741824;
    static final int MAX_SEGMENTS = 65536;
    static final ValueReference<Object, Object> UNSET = new ValueReference<Object, Object>() {
        public ValueReference<Object, Object> copyFor(ReferenceQueue<Object> referenceQueue, @Nullable Object obj, ReferenceEntry<Object, Object> referenceEntry) {
            return this;
        }

        public Object get() {
            return null;
        }

        public ReferenceEntry<Object, Object> getEntry() {
            return null;
        }

        public int getWeight() {
            return 0;
        }

        public boolean isActive() {
            return false;
        }

        public boolean isLoading() {
            return false;
        }

        public void notifyNewValue(Object obj) {
        }

        public Object waitForValue() {
            return null;
        }
    };
    static final Logger logger = Logger.getLogger(LocalCache.class.getName());
    static final ListeningExecutorService sameThreadExecutor = MoreExecutors.sameThreadExecutor();
    final int concurrencyLevel;
    @Nullable
    final CacheLoader<? super K, V> defaultLoader;
    final EntryFactory entryFactory;
    Set<Entry<K, V>> entrySet;
    final long expireAfterAccessNanos;
    final long expireAfterWriteNanos;
    final StatsCounter globalStatsCounter;
    final Equivalence<Object> keyEquivalence;
    Set<K> keySet;
    final Strength keyStrength;
    final long maxWeight;
    final long refreshNanos;
    final RemovalListener<K, V> removalListener;
    final Queue<RemovalNotification<K, V>> removalNotificationQueue;
    final int segmentMask;
    final int segmentShift;
    final Segment<K, V>[] segments;
    final Ticker ticker;
    final Equivalence<Object> valueEquivalence;
    final Strength valueStrength;
    Collection<V> values;
    final Weigher<K, V> weigher;

    abstract class AbstractCacheSet<T> extends AbstractSet<T> {
        final ConcurrentMap<?, ?> map;

        AbstractCacheSet(ConcurrentMap<?, ?> concurrentMap) {
            this.map = concurrentMap;
        }

        public int size() {
            return this.map.size();
        }

        public boolean isEmpty() {
            return this.map.isEmpty();
        }

        public void clear() {
            this.map.clear();
        }
    }

    static abstract class AbstractReferenceEntry<K, V> implements ReferenceEntry<K, V> {
        AbstractReferenceEntry() {
        }

        public ValueReference<K, V> getValueReference() {
            throw new UnsupportedOperationException();
        }

        public void setValueReference(ValueReference<K, V> valueReference) {
            throw new UnsupportedOperationException();
        }

        public ReferenceEntry<K, V> getNext() {
            throw new UnsupportedOperationException();
        }

        public int getHash() {
            throw new UnsupportedOperationException();
        }

        public K getKey() {
            throw new UnsupportedOperationException();
        }

        public long getAccessTime() {
            throw new UnsupportedOperationException();
        }

        public void setAccessTime(long j) {
            throw new UnsupportedOperationException();
        }

        public ReferenceEntry<K, V> getNextInAccessQueue() {
            throw new UnsupportedOperationException();
        }

        public void setNextInAccessQueue(ReferenceEntry<K, V> referenceEntry) {
            throw new UnsupportedOperationException();
        }

        public ReferenceEntry<K, V> getPreviousInAccessQueue() {
            throw new UnsupportedOperationException();
        }

        public void setPreviousInAccessQueue(ReferenceEntry<K, V> referenceEntry) {
            throw new UnsupportedOperationException();
        }

        public long getWriteTime() {
            throw new UnsupportedOperationException();
        }

        public void setWriteTime(long j) {
            throw new UnsupportedOperationException();
        }

        public ReferenceEntry<K, V> getNextInWriteQueue() {
            throw new UnsupportedOperationException();
        }

        public void setNextInWriteQueue(ReferenceEntry<K, V> referenceEntry) {
            throw new UnsupportedOperationException();
        }

        public ReferenceEntry<K, V> getPreviousInWriteQueue() {
            throw new UnsupportedOperationException();
        }

        public void setPreviousInWriteQueue(ReferenceEntry<K, V> referenceEntry) {
            throw new UnsupportedOperationException();
        }
    }

    static final class AccessQueue<K, V> extends AbstractQueue<ReferenceEntry<K, V>> {
        final ReferenceEntry<K, V> head = new AbstractReferenceEntry<K, V>() {
            ReferenceEntry<K, V> nextAccess = this;
            ReferenceEntry<K, V> previousAccess = this;

            public long getAccessTime() {
                return Long.MAX_VALUE;
            }

            public void setAccessTime(long j) {
            }

            public ReferenceEntry<K, V> getNextInAccessQueue() {
                return this.nextAccess;
            }

            public void setNextInAccessQueue(ReferenceEntry<K, V> referenceEntry) {
                this.nextAccess = referenceEntry;
            }

            public ReferenceEntry<K, V> getPreviousInAccessQueue() {
                return this.previousAccess;
            }

            public void setPreviousInAccessQueue(ReferenceEntry<K, V> referenceEntry) {
                this.previousAccess = referenceEntry;
            }
        };

        AccessQueue() {
        }

        public boolean offer(ReferenceEntry<K, V> referenceEntry) {
            LocalCache.connectAccessOrder(referenceEntry.getPreviousInAccessQueue(), referenceEntry.getNextInAccessQueue());
            LocalCache.connectAccessOrder(this.head.getPreviousInAccessQueue(), referenceEntry);
            LocalCache.connectAccessOrder(referenceEntry, this.head);
            return true;
        }

        public ReferenceEntry<K, V> peek() {
            ReferenceEntry<K, V> nextInAccessQueue = this.head.getNextInAccessQueue();
            if (nextInAccessQueue == this.head) {
                return null;
            }
            return nextInAccessQueue;
        }

        public ReferenceEntry<K, V> poll() {
            ReferenceEntry<K, V> nextInAccessQueue = this.head.getNextInAccessQueue();
            if (nextInAccessQueue == this.head) {
                return null;
            }
            remove(nextInAccessQueue);
            return nextInAccessQueue;
        }

        public boolean remove(Object obj) {
            ReferenceEntry referenceEntry = (ReferenceEntry) obj;
            ReferenceEntry previousInAccessQueue = referenceEntry.getPreviousInAccessQueue();
            ReferenceEntry nextInAccessQueue = referenceEntry.getNextInAccessQueue();
            LocalCache.connectAccessOrder(previousInAccessQueue, nextInAccessQueue);
            LocalCache.nullifyAccessOrder(referenceEntry);
            return nextInAccessQueue != NullEntry.INSTANCE;
        }

        public boolean contains(Object obj) {
            return ((ReferenceEntry) obj).getNextInAccessQueue() != NullEntry.INSTANCE;
        }

        public boolean isEmpty() {
            return this.head.getNextInAccessQueue() == this.head;
        }

        public int size() {
            int i = 0;
            for (ReferenceEntry<K, V> nextInAccessQueue = this.head.getNextInAccessQueue(); nextInAccessQueue != this.head; nextInAccessQueue = nextInAccessQueue.getNextInAccessQueue()) {
                i++;
            }
            return i;
        }

        public void clear() {
            ReferenceEntry<K, V> nextInAccessQueue = this.head.getNextInAccessQueue();
            while (true) {
                ReferenceEntry<K, V> referenceEntry = this.head;
                if (nextInAccessQueue != referenceEntry) {
                    ReferenceEntry nextInAccessQueue2 = nextInAccessQueue.getNextInAccessQueue();
                    LocalCache.nullifyAccessOrder(nextInAccessQueue);
                    nextInAccessQueue = nextInAccessQueue2;
                } else {
                    referenceEntry.setNextInAccessQueue(referenceEntry);
                    ReferenceEntry<K, V> referenceEntry2 = this.head;
                    referenceEntry2.setPreviousInAccessQueue(referenceEntry2);
                    return;
                }
            }
        }

        public Iterator<ReferenceEntry<K, V>> iterator() {
            return new AbstractSequentialIterator<ReferenceEntry<K, V>>(peek()) {
                /* access modifiers changed from: protected */
                public ReferenceEntry<K, V> computeNext(ReferenceEntry<K, V> referenceEntry) {
                    ReferenceEntry<K, V> nextInAccessQueue = referenceEntry.getNextInAccessQueue();
                    if (nextInAccessQueue == AccessQueue.this.head) {
                        return null;
                    }
                    return nextInAccessQueue;
                }
            };
        }
    }

    enum EntryFactory {
        STRONG {
            /* access modifiers changed from: 0000 */
            public <K, V> ReferenceEntry<K, V> newEntry(Segment<K, V> segment, K k, int i, @Nullable ReferenceEntry<K, V> referenceEntry) {
                return new StrongEntry(k, i, referenceEntry);
            }
        },
        STRONG_ACCESS {
            /* access modifiers changed from: 0000 */
            public <K, V> ReferenceEntry<K, V> newEntry(Segment<K, V> segment, K k, int i, @Nullable ReferenceEntry<K, V> referenceEntry) {
                return new StrongAccessEntry(k, i, referenceEntry);
            }

            /* access modifiers changed from: 0000 */
            public <K, V> ReferenceEntry<K, V> copyEntry(Segment<K, V> segment, ReferenceEntry<K, V> referenceEntry, ReferenceEntry<K, V> referenceEntry2) {
                ReferenceEntry<K, V> copyEntry = super.copyEntry(segment, referenceEntry, referenceEntry2);
                copyAccessEntry(referenceEntry, copyEntry);
                return copyEntry;
            }
        },
        STRONG_WRITE {
            /* access modifiers changed from: 0000 */
            public <K, V> ReferenceEntry<K, V> newEntry(Segment<K, V> segment, K k, int i, @Nullable ReferenceEntry<K, V> referenceEntry) {
                return new StrongWriteEntry(k, i, referenceEntry);
            }

            /* access modifiers changed from: 0000 */
            public <K, V> ReferenceEntry<K, V> copyEntry(Segment<K, V> segment, ReferenceEntry<K, V> referenceEntry, ReferenceEntry<K, V> referenceEntry2) {
                ReferenceEntry<K, V> copyEntry = super.copyEntry(segment, referenceEntry, referenceEntry2);
                copyWriteEntry(referenceEntry, copyEntry);
                return copyEntry;
            }
        },
        STRONG_ACCESS_WRITE {
            /* access modifiers changed from: 0000 */
            public <K, V> ReferenceEntry<K, V> newEntry(Segment<K, V> segment, K k, int i, @Nullable ReferenceEntry<K, V> referenceEntry) {
                return new StrongAccessWriteEntry(k, i, referenceEntry);
            }

            /* access modifiers changed from: 0000 */
            public <K, V> ReferenceEntry<K, V> copyEntry(Segment<K, V> segment, ReferenceEntry<K, V> referenceEntry, ReferenceEntry<K, V> referenceEntry2) {
                ReferenceEntry<K, V> copyEntry = super.copyEntry(segment, referenceEntry, referenceEntry2);
                copyAccessEntry(referenceEntry, copyEntry);
                copyWriteEntry(referenceEntry, copyEntry);
                return copyEntry;
            }
        },
        WEAK {
            /* access modifiers changed from: 0000 */
            public <K, V> ReferenceEntry<K, V> newEntry(Segment<K, V> segment, K k, int i, @Nullable ReferenceEntry<K, V> referenceEntry) {
                return new WeakEntry(segment.keyReferenceQueue, k, i, referenceEntry);
            }
        },
        WEAK_ACCESS {
            /* access modifiers changed from: 0000 */
            public <K, V> ReferenceEntry<K, V> newEntry(Segment<K, V> segment, K k, int i, @Nullable ReferenceEntry<K, V> referenceEntry) {
                return new WeakAccessEntry(segment.keyReferenceQueue, k, i, referenceEntry);
            }

            /* access modifiers changed from: 0000 */
            public <K, V> ReferenceEntry<K, V> copyEntry(Segment<K, V> segment, ReferenceEntry<K, V> referenceEntry, ReferenceEntry<K, V> referenceEntry2) {
                ReferenceEntry<K, V> copyEntry = super.copyEntry(segment, referenceEntry, referenceEntry2);
                copyAccessEntry(referenceEntry, copyEntry);
                return copyEntry;
            }
        },
        WEAK_WRITE {
            /* access modifiers changed from: 0000 */
            public <K, V> ReferenceEntry<K, V> newEntry(Segment<K, V> segment, K k, int i, @Nullable ReferenceEntry<K, V> referenceEntry) {
                return new WeakWriteEntry(segment.keyReferenceQueue, k, i, referenceEntry);
            }

            /* access modifiers changed from: 0000 */
            public <K, V> ReferenceEntry<K, V> copyEntry(Segment<K, V> segment, ReferenceEntry<K, V> referenceEntry, ReferenceEntry<K, V> referenceEntry2) {
                ReferenceEntry<K, V> copyEntry = super.copyEntry(segment, referenceEntry, referenceEntry2);
                copyWriteEntry(referenceEntry, copyEntry);
                return copyEntry;
            }
        },
        WEAK_ACCESS_WRITE {
            /* access modifiers changed from: 0000 */
            public <K, V> ReferenceEntry<K, V> newEntry(Segment<K, V> segment, K k, int i, @Nullable ReferenceEntry<K, V> referenceEntry) {
                return new WeakAccessWriteEntry(segment.keyReferenceQueue, k, i, referenceEntry);
            }

            /* access modifiers changed from: 0000 */
            public <K, V> ReferenceEntry<K, V> copyEntry(Segment<K, V> segment, ReferenceEntry<K, V> referenceEntry, ReferenceEntry<K, V> referenceEntry2) {
                ReferenceEntry<K, V> copyEntry = super.copyEntry(segment, referenceEntry, referenceEntry2);
                copyAccessEntry(referenceEntry, copyEntry);
                copyWriteEntry(referenceEntry, copyEntry);
                return copyEntry;
            }
        };
        
        static final int ACCESS_MASK = 1;
        static final int WEAK_MASK = 4;
        static final int WRITE_MASK = 2;
        static final EntryFactory[] factories = null;

        /* access modifiers changed from: 0000 */
        public abstract <K, V> ReferenceEntry<K, V> newEntry(Segment<K, V> segment, K k, int i, @Nullable ReferenceEntry<K, V> referenceEntry);

        static {
            EntryFactory entryFactory;
            EntryFactory entryFactory2;
            EntryFactory entryFactory3;
            EntryFactory entryFactory4;
            EntryFactory entryFactory5;
            EntryFactory entryFactory6;
            EntryFactory entryFactory7;
            EntryFactory entryFactory8;
            factories = new EntryFactory[]{entryFactory, entryFactory2, entryFactory3, entryFactory4, entryFactory5, entryFactory6, entryFactory7, entryFactory8};
        }

        static EntryFactory getFactory(Strength strength, boolean z, boolean z2) {
            char c = 0;
            boolean z3 = (strength == Strength.WEAK ? (char) 4 : 0) | z;
            if (z2) {
                c = 2;
            }
            return factories[z3 | c];
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public <K, V> ReferenceEntry<K, V> copyEntry(Segment<K, V> segment, ReferenceEntry<K, V> referenceEntry, ReferenceEntry<K, V> referenceEntry2) {
            return newEntry(segment, referenceEntry.getKey(), referenceEntry.getHash(), referenceEntry2);
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public <K, V> void copyAccessEntry(ReferenceEntry<K, V> referenceEntry, ReferenceEntry<K, V> referenceEntry2) {
            referenceEntry2.setAccessTime(referenceEntry.getAccessTime());
            LocalCache.connectAccessOrder(referenceEntry.getPreviousInAccessQueue(), referenceEntry2);
            LocalCache.connectAccessOrder(referenceEntry2, referenceEntry.getNextInAccessQueue());
            LocalCache.nullifyAccessOrder(referenceEntry);
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public <K, V> void copyWriteEntry(ReferenceEntry<K, V> referenceEntry, ReferenceEntry<K, V> referenceEntry2) {
            referenceEntry2.setWriteTime(referenceEntry.getWriteTime());
            LocalCache.connectWriteOrder(referenceEntry.getPreviousInWriteQueue(), referenceEntry2);
            LocalCache.connectWriteOrder(referenceEntry2, referenceEntry.getNextInWriteQueue());
            LocalCache.nullifyWriteOrder(referenceEntry);
        }
    }

    final class EntryIterator extends HashIterator<Entry<K, V>> {
        EntryIterator() {
            super();
        }

        public Entry<K, V> next() {
            return nextEntry();
        }
    }

    final class EntrySet extends AbstractCacheSet<Entry<K, V>> {
        EntrySet(ConcurrentMap<?, ?> concurrentMap) {
            super(concurrentMap);
        }

        public Iterator<Entry<K, V>> iterator() {
            return new EntryIterator();
        }

        public boolean contains(Object obj) {
            boolean z = false;
            if (!(obj instanceof Entry)) {
                return false;
            }
            Entry entry = (Entry) obj;
            Object key = entry.getKey();
            if (key == null) {
                return false;
            }
            Object obj2 = LocalCache.this.get(key);
            if (obj2 != null && LocalCache.this.valueEquivalence.equivalent(entry.getValue(), obj2)) {
                z = true;
            }
            return z;
        }

        public boolean remove(Object obj) {
            boolean z = false;
            if (!(obj instanceof Entry)) {
                return false;
            }
            Entry entry = (Entry) obj;
            Object key = entry.getKey();
            if (key != null && LocalCache.this.remove(key, entry.getValue())) {
                z = true;
            }
            return z;
        }
    }

    abstract class HashIterator<T> implements Iterator<T> {
        Segment<K, V> currentSegment;
        AtomicReferenceArray<ReferenceEntry<K, V>> currentTable;
        WriteThroughEntry lastReturned;
        ReferenceEntry<K, V> nextEntry;
        WriteThroughEntry nextExternal;
        int nextSegmentIndex;
        int nextTableIndex = -1;

        public abstract T next();

        HashIterator() {
            this.nextSegmentIndex = LocalCache.this.segments.length - 1;
            advance();
        }

        /* access modifiers changed from: 0000 */
        public final void advance() {
            this.nextExternal = null;
            if (!nextInChain() && !nextInTable()) {
                while (this.nextSegmentIndex >= 0) {
                    Segment<K, V>[] segmentArr = LocalCache.this.segments;
                    int i = this.nextSegmentIndex;
                    this.nextSegmentIndex = i - 1;
                    this.currentSegment = segmentArr[i];
                    if (this.currentSegment.count != 0) {
                        this.currentTable = this.currentSegment.table;
                        this.nextTableIndex = this.currentTable.length() - 1;
                        if (nextInTable()) {
                            return;
                        }
                    }
                }
            }
        }

        /* access modifiers changed from: 0000 */
        public boolean nextInChain() {
            ReferenceEntry<K, V> referenceEntry = this.nextEntry;
            if (referenceEntry != null) {
                while (true) {
                    this.nextEntry = referenceEntry.getNext();
                    ReferenceEntry<K, V> referenceEntry2 = this.nextEntry;
                    if (referenceEntry2 == null) {
                        break;
                    } else if (advanceTo(referenceEntry2)) {
                        return true;
                    } else {
                        referenceEntry = this.nextEntry;
                    }
                }
            }
            return false;
        }

        /* access modifiers changed from: 0000 */
        public boolean nextInTable() {
            while (true) {
                int i = this.nextTableIndex;
                if (i < 0) {
                    return false;
                }
                AtomicReferenceArray<ReferenceEntry<K, V>> atomicReferenceArray = this.currentTable;
                this.nextTableIndex = i - 1;
                ReferenceEntry<K, V> referenceEntry = (ReferenceEntry) atomicReferenceArray.get(i);
                this.nextEntry = referenceEntry;
                if (referenceEntry == null || (!advanceTo(this.nextEntry) && !nextInChain())) {
                }
            }
            return true;
        }

        /* access modifiers changed from: 0000 */
        public boolean advanceTo(ReferenceEntry<K, V> referenceEntry) {
            boolean z;
            try {
                long read = LocalCache.this.ticker.read();
                Object key = referenceEntry.getKey();
                Object liveValue = LocalCache.this.getLiveValue(referenceEntry, read);
                if (liveValue != null) {
                    this.nextExternal = new WriteThroughEntry<>(key, liveValue);
                    z = true;
                } else {
                    z = false;
                }
                return z;
            } finally {
                this.currentSegment.postReadCleanup();
            }
        }

        public boolean hasNext() {
            return this.nextExternal != null;
        }

        /* access modifiers changed from: 0000 */
        public WriteThroughEntry nextEntry() {
            WriteThroughEntry writeThroughEntry = this.nextExternal;
            if (writeThroughEntry != null) {
                this.lastReturned = writeThroughEntry;
                advance();
                return this.lastReturned;
            }
            throw new NoSuchElementException();
        }

        public void remove() {
            Preconditions.checkState(this.lastReturned != null);
            LocalCache.this.remove(this.lastReturned.getKey());
            this.lastReturned = null;
        }
    }

    final class KeyIterator extends HashIterator<K> {
        KeyIterator() {
            super();
        }

        public K next() {
            return nextEntry().getKey();
        }
    }

    final class KeySet extends AbstractCacheSet<K> {
        KeySet(ConcurrentMap<?, ?> concurrentMap) {
            super(concurrentMap);
        }

        public Iterator<K> iterator() {
            return new KeyIterator();
        }

        public boolean contains(Object obj) {
            return this.map.containsKey(obj);
        }

        public boolean remove(Object obj) {
            return this.map.remove(obj) != null;
        }
    }

    static final class LoadingSerializationProxy<K, V> extends ManualSerializationProxy<K, V> implements LoadingCache<K, V>, Serializable {
        private static final long serialVersionUID = 1;
        transient LoadingCache<K, V> autoDelegate;

        LoadingSerializationProxy(LocalCache<K, V> localCache) {
            super(localCache);
        }

        private void readObject(ObjectInputStream objectInputStream) throws IOException, ClassNotFoundException {
            objectInputStream.defaultReadObject();
            this.autoDelegate = recreateCacheBuilder().build(this.loader);
        }

        public V get(K k) throws ExecutionException {
            return this.autoDelegate.get(k);
        }

        public V getUnchecked(K k) {
            return this.autoDelegate.getUnchecked(k);
        }

        public ImmutableMap<K, V> getAll(Iterable<? extends K> iterable) throws ExecutionException {
            return this.autoDelegate.getAll(iterable);
        }

        public final V apply(K k) {
            return this.autoDelegate.apply(k);
        }

        public void refresh(K k) {
            this.autoDelegate.refresh(k);
        }

        private Object readResolve() {
            return this.autoDelegate;
        }
    }

    static class LoadingValueReference<K, V> implements ValueReference<K, V> {
        final SettableFuture<V> futureValue;
        volatile ValueReference<K, V> oldValue;
        final Stopwatch stopwatch;

        public ValueReference<K, V> copyFor(ReferenceQueue<V> referenceQueue, @Nullable V v, ReferenceEntry<K, V> referenceEntry) {
            return this;
        }

        public ReferenceEntry<K, V> getEntry() {
            return null;
        }

        public boolean isLoading() {
            return true;
        }

        public LoadingValueReference() {
            this(LocalCache.unset());
        }

        public LoadingValueReference(ValueReference<K, V> valueReference) {
            this.futureValue = SettableFuture.create();
            this.stopwatch = Stopwatch.createUnstarted();
            this.oldValue = valueReference;
        }

        public boolean isActive() {
            return this.oldValue.isActive();
        }

        public int getWeight() {
            return this.oldValue.getWeight();
        }

        public boolean set(@Nullable V v) {
            return this.futureValue.set(v);
        }

        public boolean setException(Throwable th) {
            return this.futureValue.setException(th);
        }

        private ListenableFuture<V> fullyFailedFuture(Throwable th) {
            return Futures.immediateFailedFuture(th);
        }

        public void notifyNewValue(@Nullable V v) {
            if (v != null) {
                set(v);
            } else {
                this.oldValue = LocalCache.unset();
            }
        }

        public ListenableFuture<V> loadFuture(K k, CacheLoader<? super K, V> cacheLoader) {
            this.stopwatch.start();
            Object obj = this.oldValue.get();
            if (obj == null) {
                try {
                    Object load = cacheLoader.load(k);
                    return set(load) ? this.futureValue : Futures.immediateFuture(load);
                } catch (Throwable th) {
                    if (th instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    return setException(th) ? this.futureValue : fullyFailedFuture(th);
                }
            } else {
                ListenableFuture reload = cacheLoader.reload(k, obj);
                if (reload == null) {
                    return Futures.immediateFuture(null);
                }
                return Futures.transform(reload, (Function<? super I, ? extends O>) new Function<V, V>() {
                    public V apply(V v) {
                        LoadingValueReference.this.set(v);
                        return v;
                    }
                });
            }
        }

        public long elapsedNanos() {
            return this.stopwatch.elapsed(TimeUnit.NANOSECONDS);
        }

        public V waitForValue() throws ExecutionException {
            return Uninterruptibles.getUninterruptibly(this.futureValue);
        }

        public V get() {
            return this.oldValue.get();
        }

        public ValueReference<K, V> getOldValue() {
            return this.oldValue;
        }
    }

    static class LocalLoadingCache<K, V> extends LocalManualCache<K, V> implements LoadingCache<K, V> {
        private static final long serialVersionUID = 1;

        LocalLoadingCache(CacheBuilder<? super K, ? super V> cacheBuilder, CacheLoader<? super K, V> cacheLoader) {
            super();
        }

        public V get(K k) throws ExecutionException {
            return this.localCache.getOrLoad(k);
        }

        public V getUnchecked(K k) {
            try {
                return get(k);
            } catch (ExecutionException e) {
                throw new UncheckedExecutionException(e.getCause());
            }
        }

        public ImmutableMap<K, V> getAll(Iterable<? extends K> iterable) throws ExecutionException {
            return this.localCache.getAll(iterable);
        }

        public void refresh(K k) {
            this.localCache.refresh(k);
        }

        public final V apply(K k) {
            return getUnchecked(k);
        }

        /* access modifiers changed from: 0000 */
        public Object writeReplace() {
            return new LoadingSerializationProxy(this.localCache);
        }
    }

    static class LocalManualCache<K, V> implements Cache<K, V>, Serializable {
        private static final long serialVersionUID = 1;
        final LocalCache<K, V> localCache;

        LocalManualCache(CacheBuilder<? super K, ? super V> cacheBuilder) {
            this(new LocalCache<>(cacheBuilder, null));
        }

        private LocalManualCache(LocalCache<K, V> localCache2) {
            this.localCache = localCache2;
        }

        @Nullable
        public V getIfPresent(Object obj) {
            return this.localCache.getIfPresent(obj);
        }

        public V get(K k, final Callable<? extends V> callable) throws ExecutionException {
            Preconditions.checkNotNull(callable);
            return this.localCache.get(k, new CacheLoader<Object, V>() {
                public V load(Object obj) throws Exception {
                    return callable.call();
                }
            });
        }

        public ImmutableMap<K, V> getAllPresent(Iterable<?> iterable) {
            return this.localCache.getAllPresent(iterable);
        }

        public void put(K k, V v) {
            this.localCache.put(k, v);
        }

        public void putAll(Map<? extends K, ? extends V> map) {
            this.localCache.putAll(map);
        }

        public void invalidate(Object obj) {
            Preconditions.checkNotNull(obj);
            this.localCache.remove(obj);
        }

        public void invalidateAll(Iterable<?> iterable) {
            this.localCache.invalidateAll(iterable);
        }

        public void invalidateAll() {
            this.localCache.clear();
        }

        public long size() {
            return this.localCache.longSize();
        }

        public ConcurrentMap<K, V> asMap() {
            return this.localCache;
        }

        public CacheStats stats() {
            SimpleStatsCounter simpleStatsCounter = new SimpleStatsCounter();
            simpleStatsCounter.incrementBy(this.localCache.globalStatsCounter);
            for (Segment<K, V> segment : this.localCache.segments) {
                simpleStatsCounter.incrementBy(segment.statsCounter);
            }
            return simpleStatsCounter.snapshot();
        }

        public void cleanUp() {
            this.localCache.cleanUp();
        }

        /* access modifiers changed from: 0000 */
        public Object writeReplace() {
            return new ManualSerializationProxy(this.localCache);
        }
    }

    static class ManualSerializationProxy<K, V> extends ForwardingCache<K, V> implements Serializable {
        private static final long serialVersionUID = 1;
        final int concurrencyLevel;
        transient Cache<K, V> delegate;
        final long expireAfterAccessNanos;
        final long expireAfterWriteNanos;
        final Equivalence<Object> keyEquivalence;
        final Strength keyStrength;
        final CacheLoader<? super K, V> loader;
        final long maxWeight;
        final RemovalListener<? super K, ? super V> removalListener;
        final Ticker ticker;
        final Equivalence<Object> valueEquivalence;
        final Strength valueStrength;
        final Weigher<K, V> weigher;

        ManualSerializationProxy(LocalCache<K, V> localCache) {
            LocalCache<K, V> localCache2 = localCache;
            Strength strength = localCache2.keyStrength;
            Strength strength2 = localCache2.valueStrength;
            Equivalence<Object> equivalence = localCache2.keyEquivalence;
            Equivalence<Object> equivalence2 = localCache2.valueEquivalence;
            long j = localCache2.expireAfterWriteNanos;
            long j2 = localCache2.expireAfterAccessNanos;
            long j3 = localCache2.maxWeight;
            Weigher<K, V> weigher2 = localCache2.weigher;
            int i = localCache2.concurrencyLevel;
            RemovalListener<K, V> removalListener2 = localCache2.removalListener;
            Ticker ticker2 = localCache2.ticker;
            CacheLoader<? super K, V> cacheLoader = localCache2.defaultLoader;
            this(strength, strength2, equivalence, equivalence2, j, j2, j3, weigher2, i, removalListener2, ticker2, cacheLoader);
        }

        private ManualSerializationProxy(Strength strength, Strength strength2, Equivalence<Object> equivalence, Equivalence<Object> equivalence2, long j, long j2, long j3, Weigher<K, V> weigher2, int i, RemovalListener<? super K, ? super V> removalListener2, Ticker ticker2, CacheLoader<? super K, V> cacheLoader) {
            this.keyStrength = strength;
            this.valueStrength = strength2;
            this.keyEquivalence = equivalence;
            this.valueEquivalence = equivalence2;
            this.expireAfterWriteNanos = j;
            this.expireAfterAccessNanos = j2;
            this.maxWeight = j3;
            this.weigher = weigher2;
            this.concurrencyLevel = i;
            this.removalListener = removalListener2;
            if (ticker2 == Ticker.systemTicker() || ticker2 == CacheBuilder.NULL_TICKER) {
                ticker2 = null;
            }
            this.ticker = ticker2;
            this.loader = cacheLoader;
        }

        /* access modifiers changed from: 0000 */
        public CacheBuilder<K, V> recreateCacheBuilder() {
            CacheBuilder<K, V> removalListener2 = CacheBuilder.newBuilder().setKeyStrength(this.keyStrength).setValueStrength(this.valueStrength).keyEquivalence(this.keyEquivalence).valueEquivalence(this.valueEquivalence).concurrencyLevel(this.concurrencyLevel).removalListener(this.removalListener);
            removalListener2.strictParsing = false;
            long j = this.expireAfterWriteNanos;
            if (j > 0) {
                removalListener2.expireAfterWrite(j, TimeUnit.NANOSECONDS);
            }
            long j2 = this.expireAfterAccessNanos;
            if (j2 > 0) {
                removalListener2.expireAfterAccess(j2, TimeUnit.NANOSECONDS);
            }
            if (this.weigher != OneWeigher.INSTANCE) {
                removalListener2.weigher(this.weigher);
                long j3 = this.maxWeight;
                if (j3 != -1) {
                    removalListener2.maximumWeight(j3);
                }
            } else {
                long j4 = this.maxWeight;
                if (j4 != -1) {
                    removalListener2.maximumSize(j4);
                }
            }
            Ticker ticker2 = this.ticker;
            if (ticker2 != null) {
                removalListener2.ticker(ticker2);
            }
            return removalListener2;
        }

        private void readObject(ObjectInputStream objectInputStream) throws IOException, ClassNotFoundException {
            objectInputStream.defaultReadObject();
            this.delegate = recreateCacheBuilder().build();
        }

        private Object readResolve() {
            return this.delegate;
        }

        /* access modifiers changed from: protected */
        public Cache<K, V> delegate() {
            return this.delegate;
        }
    }

    private enum NullEntry implements ReferenceEntry<Object, Object> {
        INSTANCE;

        public long getAccessTime() {
            return 0;
        }

        public int getHash() {
            return 0;
        }

        public Object getKey() {
            return null;
        }

        public ReferenceEntry<Object, Object> getNext() {
            return null;
        }

        public ReferenceEntry<Object, Object> getNextInAccessQueue() {
            return this;
        }

        public ReferenceEntry<Object, Object> getNextInWriteQueue() {
            return this;
        }

        public ReferenceEntry<Object, Object> getPreviousInAccessQueue() {
            return this;
        }

        public ReferenceEntry<Object, Object> getPreviousInWriteQueue() {
            return this;
        }

        public ValueReference<Object, Object> getValueReference() {
            return null;
        }

        public long getWriteTime() {
            return 0;
        }

        public void setAccessTime(long j) {
        }

        public void setNextInAccessQueue(ReferenceEntry<Object, Object> referenceEntry) {
        }

        public void setNextInWriteQueue(ReferenceEntry<Object, Object> referenceEntry) {
        }

        public void setPreviousInAccessQueue(ReferenceEntry<Object, Object> referenceEntry) {
        }

        public void setPreviousInWriteQueue(ReferenceEntry<Object, Object> referenceEntry) {
        }

        public void setValueReference(ValueReference<Object, Object> valueReference) {
        }

        public void setWriteTime(long j) {
        }
    }

    interface ReferenceEntry<K, V> {
        long getAccessTime();

        int getHash();

        @Nullable
        K getKey();

        @Nullable
        ReferenceEntry<K, V> getNext();

        ReferenceEntry<K, V> getNextInAccessQueue();

        ReferenceEntry<K, V> getNextInWriteQueue();

        ReferenceEntry<K, V> getPreviousInAccessQueue();

        ReferenceEntry<K, V> getPreviousInWriteQueue();

        ValueReference<K, V> getValueReference();

        long getWriteTime();

        void setAccessTime(long j);

        void setNextInAccessQueue(ReferenceEntry<K, V> referenceEntry);

        void setNextInWriteQueue(ReferenceEntry<K, V> referenceEntry);

        void setPreviousInAccessQueue(ReferenceEntry<K, V> referenceEntry);

        void setPreviousInWriteQueue(ReferenceEntry<K, V> referenceEntry);

        void setValueReference(ValueReference<K, V> valueReference);

        void setWriteTime(long j);
    }

    static class Segment<K, V> extends ReentrantLock {
        @GuardedBy("Segment.this")
        final Queue<ReferenceEntry<K, V>> accessQueue;
        volatile int count;
        final ReferenceQueue<K> keyReferenceQueue;
        final LocalCache<K, V> map;
        final long maxSegmentWeight;
        int modCount;
        final AtomicInteger readCount = new AtomicInteger();
        final Queue<ReferenceEntry<K, V>> recencyQueue;
        final StatsCounter statsCounter;
        volatile AtomicReferenceArray<ReferenceEntry<K, V>> table;
        int threshold;
        @GuardedBy("Segment.this")
        int totalWeight;
        final ReferenceQueue<V> valueReferenceQueue;
        @GuardedBy("Segment.this")
        final Queue<ReferenceEntry<K, V>> writeQueue;

        Segment(LocalCache<K, V> localCache, int i, long j, StatsCounter statsCounter2) {
            this.map = localCache;
            this.maxSegmentWeight = j;
            this.statsCounter = (StatsCounter) Preconditions.checkNotNull(statsCounter2);
            initTable(newEntryArray(i));
            ReferenceQueue<V> referenceQueue = null;
            this.keyReferenceQueue = localCache.usesKeyReferences() ? new ReferenceQueue<>() : null;
            if (localCache.usesValueReferences()) {
                referenceQueue = new ReferenceQueue<>();
            }
            this.valueReferenceQueue = referenceQueue;
            this.recencyQueue = localCache.usesAccessQueue() ? new ConcurrentLinkedQueue<>() : LocalCache.discardingQueue();
            this.writeQueue = localCache.usesWriteQueue() ? new WriteQueue<>() : LocalCache.discardingQueue();
            this.accessQueue = localCache.usesAccessQueue() ? new AccessQueue<>() : LocalCache.discardingQueue();
        }

        /* access modifiers changed from: 0000 */
        public AtomicReferenceArray<ReferenceEntry<K, V>> newEntryArray(int i) {
            return new AtomicReferenceArray<>(i);
        }

        /* access modifiers changed from: 0000 */
        public void initTable(AtomicReferenceArray<ReferenceEntry<K, V>> atomicReferenceArray) {
            this.threshold = (atomicReferenceArray.length() * 3) / 4;
            if (!this.map.customWeigher()) {
                int i = this.threshold;
                if (((long) i) == this.maxSegmentWeight) {
                    this.threshold = i + 1;
                }
            }
            this.table = atomicReferenceArray;
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public ReferenceEntry<K, V> newEntry(K k, int i, @Nullable ReferenceEntry<K, V> referenceEntry) {
            return this.map.entryFactory.newEntry(this, Preconditions.checkNotNull(k), i, referenceEntry);
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public ReferenceEntry<K, V> copyEntry(ReferenceEntry<K, V> referenceEntry, ReferenceEntry<K, V> referenceEntry2) {
            if (referenceEntry.getKey() == null) {
                return null;
            }
            ValueReference valueReference = referenceEntry.getValueReference();
            Object obj = valueReference.get();
            if (obj == null && valueReference.isActive()) {
                return null;
            }
            ReferenceEntry<K, V> copyEntry = this.map.entryFactory.copyEntry(this, referenceEntry, referenceEntry2);
            copyEntry.setValueReference(valueReference.copyFor(this.valueReferenceQueue, obj, copyEntry));
            return copyEntry;
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public void setValue(ReferenceEntry<K, V> referenceEntry, K k, V v, long j) {
            ValueReference valueReference = referenceEntry.getValueReference();
            int weigh = this.map.weigher.weigh(k, v);
            Preconditions.checkState(weigh >= 0, "Weights must be non-negative");
            referenceEntry.setValueReference(this.map.valueStrength.referenceValue(this, referenceEntry, v, weigh));
            recordWrite(referenceEntry, weigh, j);
            valueReference.notifyNewValue(v);
        }

        /* access modifiers changed from: 0000 */
        public V get(K k, int i, CacheLoader<? super K, V> cacheLoader) throws ExecutionException {
            Preconditions.checkNotNull(k);
            Preconditions.checkNotNull(cacheLoader);
            try {
                if (this.count != 0) {
                    ReferenceEntry entry = getEntry(k, i);
                    if (entry != null) {
                        long read = this.map.ticker.read();
                        Object liveValue = getLiveValue(entry, read);
                        if (liveValue != null) {
                            recordRead(entry, read);
                            this.statsCounter.recordHits(1);
                            V scheduleRefresh = scheduleRefresh(entry, k, i, liveValue, read, cacheLoader);
                            postReadCleanup();
                            return scheduleRefresh;
                        }
                        ValueReference valueReference = entry.getValueReference();
                        if (valueReference.isLoading()) {
                            V waitForLoadingValue = waitForLoadingValue(entry, k, valueReference);
                            postReadCleanup();
                            return waitForLoadingValue;
                        }
                    }
                }
                V lockedGetOrLoad = lockedGetOrLoad(k, i, cacheLoader);
                postReadCleanup();
                return lockedGetOrLoad;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Error) {
                    throw new ExecutionError((Error) cause);
                } else if (cause instanceof RuntimeException) {
                    throw new UncheckedExecutionException(cause);
                } else {
                    throw e;
                }
            } catch (Throwable th) {
                postReadCleanup();
                throw th;
            }
        }

        /* access modifiers changed from: 0000 */
        public V lockedGetOrLoad(K k, int i, CacheLoader<? super K, V> cacheLoader) throws ExecutionException {
            LoadingValueReference loadingValueReference;
            ValueReference valueReference;
            boolean z;
            V loadSync;
            K k2 = k;
            int i2 = i;
            lock();
            try {
                long read = this.map.ticker.read();
                preWriteCleanup(read);
                int i3 = this.count - 1;
                AtomicReferenceArray<ReferenceEntry<K, V>> atomicReferenceArray = this.table;
                int length = (atomicReferenceArray.length() - 1) & i2;
                ReferenceEntry referenceEntry = (ReferenceEntry) atomicReferenceArray.get(length);
                ReferenceEntry referenceEntry2 = referenceEntry;
                while (true) {
                    loadingValueReference = null;
                    if (referenceEntry2 == null) {
                        valueReference = null;
                        z = true;
                        break;
                    }
                    Object key = referenceEntry2.getKey();
                    if (referenceEntry2.getHash() != i2 || key == null || !this.map.keyEquivalence.equivalent(k2, key)) {
                        referenceEntry2 = referenceEntry2.getNext();
                    } else {
                        valueReference = referenceEntry2.getValueReference();
                        if (valueReference.isLoading()) {
                            z = false;
                        } else {
                            V v = valueReference.get();
                            if (v == null) {
                                enqueueNotification(key, i2, valueReference, RemovalCause.COLLECTED);
                            } else if (this.map.isExpired(referenceEntry2, read)) {
                                enqueueNotification(key, i2, valueReference, RemovalCause.EXPIRED);
                            } else {
                                recordLockedRead(referenceEntry2, read);
                                this.statsCounter.recordHits(1);
                                unlock();
                                postWriteCleanup();
                                return v;
                            }
                            this.writeQueue.remove(referenceEntry2);
                            this.accessQueue.remove(referenceEntry2);
                            this.count = i3;
                            z = true;
                        }
                    }
                }
                if (z) {
                    loadingValueReference = new LoadingValueReference();
                    if (referenceEntry2 == null) {
                        referenceEntry2 = newEntry(k2, i2, referenceEntry);
                        referenceEntry2.setValueReference(loadingValueReference);
                        atomicReferenceArray.set(length, referenceEntry2);
                    } else {
                        referenceEntry2.setValueReference(loadingValueReference);
                    }
                }
                if (!z) {
                    return waitForLoadingValue(referenceEntry2, k2, valueReference);
                }
                try {
                    synchronized (referenceEntry2) {
                        loadSync = loadSync(k2, i2, loadingValueReference, cacheLoader);
                    }
                    this.statsCounter.recordMisses(1);
                    return loadSync;
                } catch (Throwable th) {
                    this.statsCounter.recordMisses(1);
                    throw th;
                }
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        /* access modifiers changed from: 0000 */
        public V waitForLoadingValue(ReferenceEntry<K, V> referenceEntry, K k, ValueReference<K, V> valueReference) throws ExecutionException {
            if (valueReference.isLoading()) {
                Preconditions.checkState(!Thread.holdsLock(referenceEntry), "Recursive load");
                try {
                    V waitForValue = valueReference.waitForValue();
                    if (waitForValue != null) {
                        recordRead(referenceEntry, this.map.ticker.read());
                        return waitForValue;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("CacheLoader returned null for key ");
                    sb.append(k);
                    sb.append(".");
                    throw new InvalidCacheLoadException(sb.toString());
                } finally {
                    this.statsCounter.recordMisses(1);
                }
            } else {
                throw new AssertionError();
            }
        }

        /* access modifiers changed from: 0000 */
        public V loadSync(K k, int i, LoadingValueReference<K, V> loadingValueReference, CacheLoader<? super K, V> cacheLoader) throws ExecutionException {
            return getAndRecordStats(k, i, loadingValueReference, loadingValueReference.loadFuture(k, cacheLoader));
        }

        /* access modifiers changed from: 0000 */
        public ListenableFuture<V> loadAsync(K k, int i, LoadingValueReference<K, V> loadingValueReference, CacheLoader<? super K, V> cacheLoader) {
            ListenableFuture<V> loadFuture = loadingValueReference.loadFuture(k, cacheLoader);
            final K k2 = k;
            final int i2 = i;
            final LoadingValueReference<K, V> loadingValueReference2 = loadingValueReference;
            final ListenableFuture<V> listenableFuture = loadFuture;
            C05421 r0 = new Runnable() {
                public void run() {
                    try {
                        Segment.this.getAndRecordStats(k2, i2, loadingValueReference2, listenableFuture);
                    } catch (Throwable th) {
                        LocalCache.logger.log(Level.WARNING, "Exception thrown during refresh", th);
                        loadingValueReference2.setException(th);
                    }
                }
            };
            loadFuture.addListener(r0, LocalCache.sameThreadExecutor);
            return loadFuture;
        }

        /* access modifiers changed from: 0000 */
        /* JADX WARNING: Removed duplicated region for block: B:15:0x0043  */
        /* Code decompiled incorrectly, please refer to instructions dump. */
        public V getAndRecordStats(K r4, int r5, com.google.common.cache.LocalCache.LoadingValueReference<K, V> r6, com.google.common.util.concurrent.ListenableFuture<V> r7) throws java.util.concurrent.ExecutionException {
            /*
                r3 = this;
                java.lang.Object r7 = com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly(r7)     // Catch:{ all -> 0x003f }
                if (r7 == 0) goto L_0x0023
                com.google.common.cache.AbstractCache$StatsCounter r0 = r3.statsCounter     // Catch:{ all -> 0x0021 }
                long r1 = r6.elapsedNanos()     // Catch:{ all -> 0x0021 }
                r0.recordLoadSuccess(r1)     // Catch:{ all -> 0x0021 }
                r3.storeLoadedValue(r4, r5, r6, r7)     // Catch:{ all -> 0x0021 }
                if (r7 != 0) goto L_0x0020
                com.google.common.cache.AbstractCache$StatsCounter r0 = r3.statsCounter
                long r1 = r6.elapsedNanos()
                r0.recordLoadException(r1)
                r3.removeLoadingValue(r4, r5, r6)
            L_0x0020:
                return r7
            L_0x0021:
                r0 = move-exception
                goto L_0x0041
            L_0x0023:
                com.google.common.cache.CacheLoader$InvalidCacheLoadException r0 = new com.google.common.cache.CacheLoader$InvalidCacheLoadException     // Catch:{ all -> 0x0021 }
                java.lang.StringBuilder r1 = new java.lang.StringBuilder     // Catch:{ all -> 0x0021 }
                r1.<init>()     // Catch:{ all -> 0x0021 }
                java.lang.String r2 = "CacheLoader returned null for key "
                r1.append(r2)     // Catch:{ all -> 0x0021 }
                r1.append(r4)     // Catch:{ all -> 0x0021 }
                java.lang.String r2 = "."
                r1.append(r2)     // Catch:{ all -> 0x0021 }
                java.lang.String r1 = r1.toString()     // Catch:{ all -> 0x0021 }
                r0.<init>(r1)     // Catch:{ all -> 0x0021 }
                throw r0     // Catch:{ all -> 0x0021 }
            L_0x003f:
                r0 = move-exception
                r7 = 0
            L_0x0041:
                if (r7 != 0) goto L_0x004f
                com.google.common.cache.AbstractCache$StatsCounter r7 = r3.statsCounter
                long r1 = r6.elapsedNanos()
                r7.recordLoadException(r1)
                r3.removeLoadingValue(r4, r5, r6)
            L_0x004f:
                throw r0
            */
            throw new UnsupportedOperationException("Method not decompiled: com.google.common.cache.LocalCache.Segment.getAndRecordStats(java.lang.Object, int, com.google.common.cache.LocalCache$LoadingValueReference, com.google.common.util.concurrent.ListenableFuture):java.lang.Object");
        }

        /* access modifiers changed from: 0000 */
        public V scheduleRefresh(ReferenceEntry<K, V> referenceEntry, K k, int i, V v, long j, CacheLoader<? super K, V> cacheLoader) {
            if (this.map.refreshes() && j - referenceEntry.getWriteTime() > this.map.refreshNanos && !referenceEntry.getValueReference().isLoading()) {
                V refresh = refresh(k, i, cacheLoader, true);
                if (refresh != null) {
                    return refresh;
                }
            }
            return v;
        }

        /* access modifiers changed from: 0000 */
        @Nullable
        public V refresh(K k, int i, CacheLoader<? super K, V> cacheLoader, boolean z) {
            LoadingValueReference insertLoadingValueReference = insertLoadingValueReference(k, i, z);
            if (insertLoadingValueReference == null) {
                return null;
            }
            ListenableFuture loadAsync = loadAsync(k, i, insertLoadingValueReference, cacheLoader);
            if (loadAsync.isDone()) {
                try {
                    return Uninterruptibles.getUninterruptibly(loadAsync);
                } catch (Throwable unused) {
                }
            }
            return null;
        }

        /* access modifiers changed from: 0000 */
        @Nullable
        public LoadingValueReference<K, V> insertLoadingValueReference(K k, int i, boolean z) {
            lock();
            try {
                long read = this.map.ticker.read();
                preWriteCleanup(read);
                AtomicReferenceArray<ReferenceEntry<K, V>> atomicReferenceArray = this.table;
                int length = (atomicReferenceArray.length() - 1) & i;
                ReferenceEntry referenceEntry = (ReferenceEntry) atomicReferenceArray.get(length);
                ReferenceEntry referenceEntry2 = referenceEntry;
                while (referenceEntry2 != null) {
                    Object key = referenceEntry2.getKey();
                    if (referenceEntry2.getHash() != i || key == null || !this.map.keyEquivalence.equivalent(k, key)) {
                        referenceEntry2 = referenceEntry2.getNext();
                    } else {
                        ValueReference valueReference = referenceEntry2.getValueReference();
                        if (!valueReference.isLoading()) {
                            if (!z || read - referenceEntry2.getWriteTime() >= this.map.refreshNanos) {
                                this.modCount++;
                                LoadingValueReference<K, V> loadingValueReference = new LoadingValueReference<>(valueReference);
                                referenceEntry2.setValueReference(loadingValueReference);
                                unlock();
                                postWriteCleanup();
                                return loadingValueReference;
                            }
                        }
                        return null;
                    }
                }
                this.modCount++;
                LoadingValueReference<K, V> loadingValueReference2 = new LoadingValueReference<>();
                ReferenceEntry newEntry = newEntry(k, i, referenceEntry);
                newEntry.setValueReference(loadingValueReference2);
                atomicReferenceArray.set(length, newEntry);
                unlock();
                postWriteCleanup();
                return loadingValueReference2;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        /* access modifiers changed from: 0000 */
        public void tryDrainReferenceQueues() {
            if (tryLock()) {
                try {
                    drainReferenceQueues();
                } finally {
                    unlock();
                }
            }
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public void drainReferenceQueues() {
            if (this.map.usesKeyReferences()) {
                drainKeyReferenceQueue();
            }
            if (this.map.usesValueReferences()) {
                drainValueReferenceQueue();
            }
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public void drainKeyReferenceQueue() {
            int i = 0;
            do {
                Reference poll = this.keyReferenceQueue.poll();
                if (poll != null) {
                    this.map.reclaimKey((ReferenceEntry) poll);
                    i++;
                } else {
                    return;
                }
            } while (i != 16);
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public void drainValueReferenceQueue() {
            int i = 0;
            do {
                Reference poll = this.valueReferenceQueue.poll();
                if (poll != null) {
                    this.map.reclaimValue((ValueReference) poll);
                    i++;
                } else {
                    return;
                }
            } while (i != 16);
        }

        /* access modifiers changed from: 0000 */
        public void clearReferenceQueues() {
            if (this.map.usesKeyReferences()) {
                clearKeyReferenceQueue();
            }
            if (this.map.usesValueReferences()) {
                clearValueReferenceQueue();
            }
        }

        /* access modifiers changed from: 0000 */
        public void clearKeyReferenceQueue() {
            do {
            } while (this.keyReferenceQueue.poll() != null);
        }

        /* access modifiers changed from: 0000 */
        public void clearValueReferenceQueue() {
            do {
            } while (this.valueReferenceQueue.poll() != null);
        }

        /* access modifiers changed from: 0000 */
        public void recordRead(ReferenceEntry<K, V> referenceEntry, long j) {
            if (this.map.recordsAccess()) {
                referenceEntry.setAccessTime(j);
            }
            this.recencyQueue.add(referenceEntry);
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public void recordLockedRead(ReferenceEntry<K, V> referenceEntry, long j) {
            if (this.map.recordsAccess()) {
                referenceEntry.setAccessTime(j);
            }
            this.accessQueue.add(referenceEntry);
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public void recordWrite(ReferenceEntry<K, V> referenceEntry, int i, long j) {
            drainRecencyQueue();
            this.totalWeight += i;
            if (this.map.recordsAccess()) {
                referenceEntry.setAccessTime(j);
            }
            if (this.map.recordsWrite()) {
                referenceEntry.setWriteTime(j);
            }
            this.accessQueue.add(referenceEntry);
            this.writeQueue.add(referenceEntry);
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public void drainRecencyQueue() {
            while (true) {
                ReferenceEntry referenceEntry = (ReferenceEntry) this.recencyQueue.poll();
                if (referenceEntry == null) {
                    return;
                }
                if (this.accessQueue.contains(referenceEntry)) {
                    this.accessQueue.add(referenceEntry);
                }
            }
        }

        /* access modifiers changed from: 0000 */
        public void tryExpireEntries(long j) {
            if (tryLock()) {
                try {
                    expireEntries(j);
                } finally {
                    unlock();
                }
            }
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public void expireEntries(long j) {
            ReferenceEntry referenceEntry;
            ReferenceEntry referenceEntry2;
            drainRecencyQueue();
            do {
                referenceEntry = (ReferenceEntry) this.writeQueue.peek();
                if (referenceEntry == null || !this.map.isExpired(referenceEntry, j)) {
                    do {
                        referenceEntry2 = (ReferenceEntry) this.accessQueue.peek();
                        if (referenceEntry2 == null || !this.map.isExpired(referenceEntry2, j)) {
                            return;
                        }
                    } while (removeEntry(referenceEntry2, referenceEntry2.getHash(), RemovalCause.EXPIRED));
                    throw new AssertionError();
                }
            } while (removeEntry(referenceEntry, referenceEntry.getHash(), RemovalCause.EXPIRED));
            throw new AssertionError();
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public void enqueueNotification(ReferenceEntry<K, V> referenceEntry, RemovalCause removalCause) {
            enqueueNotification(referenceEntry.getKey(), referenceEntry.getHash(), referenceEntry.getValueReference(), removalCause);
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public void enqueueNotification(@Nullable K k, int i, ValueReference<K, V> valueReference, RemovalCause removalCause) {
            this.totalWeight -= valueReference.getWeight();
            if (removalCause.wasEvicted()) {
                this.statsCounter.recordEviction();
            }
            if (this.map.removalNotificationQueue != LocalCache.DISCARDING_QUEUE) {
                this.map.removalNotificationQueue.offer(new RemovalNotification(k, valueReference.get(), removalCause));
            }
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public void evictEntries() {
            if (this.map.evictsBySize()) {
                drainRecencyQueue();
                while (((long) this.totalWeight) > this.maxSegmentWeight) {
                    ReferenceEntry nextEvictable = getNextEvictable();
                    if (!removeEntry(nextEvictable, nextEvictable.getHash(), RemovalCause.SIZE)) {
                        throw new AssertionError();
                    }
                }
            }
        }

        /* access modifiers changed from: 0000 */
        public ReferenceEntry<K, V> getNextEvictable() {
            for (ReferenceEntry<K, V> referenceEntry : this.accessQueue) {
                if (referenceEntry.getValueReference().getWeight() > 0) {
                    return referenceEntry;
                }
            }
            throw new AssertionError();
        }

        /* access modifiers changed from: 0000 */
        public ReferenceEntry<K, V> getFirst(int i) {
            AtomicReferenceArray<ReferenceEntry<K, V>> atomicReferenceArray = this.table;
            return (ReferenceEntry) atomicReferenceArray.get(i & (atomicReferenceArray.length() - 1));
        }

        /* access modifiers changed from: 0000 */
        @Nullable
        public ReferenceEntry<K, V> getEntry(Object obj, int i) {
            for (ReferenceEntry<K, V> first = getFirst(i); first != null; first = first.getNext()) {
                if (first.getHash() == i) {
                    Object key = first.getKey();
                    if (key == null) {
                        tryDrainReferenceQueues();
                    } else if (this.map.keyEquivalence.equivalent(obj, key)) {
                        return first;
                    }
                }
            }
            return null;
        }

        /* access modifiers changed from: 0000 */
        @Nullable
        public ReferenceEntry<K, V> getLiveEntry(Object obj, int i, long j) {
            ReferenceEntry<K, V> entry = getEntry(obj, i);
            if (entry == null) {
                return null;
            }
            if (!this.map.isExpired(entry, j)) {
                return entry;
            }
            tryExpireEntries(j);
            return null;
        }

        /* access modifiers changed from: 0000 */
        public V getLiveValue(ReferenceEntry<K, V> referenceEntry, long j) {
            if (referenceEntry.getKey() == null) {
                tryDrainReferenceQueues();
                return null;
            }
            V v = referenceEntry.getValueReference().get();
            if (v == null) {
                tryDrainReferenceQueues();
                return null;
            } else if (!this.map.isExpired(referenceEntry, j)) {
                return v;
            } else {
                tryExpireEntries(j);
                return null;
            }
        }

        /* access modifiers changed from: 0000 */
        @Nullable
        public V get(Object obj, int i) {
            try {
                if (this.count != 0) {
                    long read = this.map.ticker.read();
                    ReferenceEntry liveEntry = getLiveEntry(obj, i, read);
                    if (liveEntry == null) {
                        return null;
                    }
                    Object obj2 = liveEntry.getValueReference().get();
                    if (obj2 != null) {
                        recordRead(liveEntry, read);
                        V scheduleRefresh = scheduleRefresh(liveEntry, liveEntry.getKey(), i, obj2, read, this.map.defaultLoader);
                        postReadCleanup();
                        return scheduleRefresh;
                    }
                    tryDrainReferenceQueues();
                }
                postReadCleanup();
                return null;
            } finally {
                postReadCleanup();
            }
        }

        /* access modifiers changed from: 0000 */
        public boolean containsKey(Object obj, int i) {
            try {
                boolean z = false;
                if (this.count != 0) {
                    ReferenceEntry liveEntry = getLiveEntry(obj, i, this.map.ticker.read());
                    if (liveEntry == null) {
                        return false;
                    }
                    if (liveEntry.getValueReference().get() != null) {
                        z = true;
                    }
                    postReadCleanup();
                    return z;
                }
                postReadCleanup();
                return false;
            } finally {
                postReadCleanup();
            }
        }

        /* JADX INFO: finally extract failed */
        /* access modifiers changed from: 0000 */
        @VisibleForTesting
        public boolean containsValue(Object obj) {
            try {
                if (this.count != 0) {
                    long read = this.map.ticker.read();
                    AtomicReferenceArray<ReferenceEntry<K, V>> atomicReferenceArray = this.table;
                    int length = atomicReferenceArray.length();
                    for (int i = 0; i < length; i++) {
                        for (ReferenceEntry referenceEntry = (ReferenceEntry) atomicReferenceArray.get(i); referenceEntry != null; referenceEntry = referenceEntry.getNext()) {
                            Object liveValue = getLiveValue(referenceEntry, read);
                            if (liveValue != null) {
                                if (this.map.valueEquivalence.equivalent(obj, liveValue)) {
                                    postReadCleanup();
                                    return true;
                                }
                            }
                        }
                    }
                }
                postReadCleanup();
                return false;
            } catch (Throwable th) {
                postReadCleanup();
                throw th;
            }
        }

        /* access modifiers changed from: 0000 */
        /* JADX WARNING: Code restructure failed: missing block: B:13:0x0045, code lost:
            r0 = r2.getValueReference();
            r7 = r0.get();
         */
        /* JADX WARNING: Code restructure failed: missing block: B:14:0x004d, code lost:
            if (r7 != null) goto L_0x007f;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:15:0x004f, code lost:
            r9.modCount++;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:16:0x0059, code lost:
            if (r0.isActive() == false) goto L_0x0069;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:17:0x005b, code lost:
            enqueueNotification(r10, r11, r0, com.google.common.cache.RemovalCause.COLLECTED);
            setValue(r2, r10, r12, r5);
            r10 = r9.count;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:18:0x0069, code lost:
            setValue(r2, r10, r12, r5);
            r10 = r9.count + 1;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:19:0x0073, code lost:
            r9.count = r10;
            evictEntries();
         */
        /* JADX WARNING: Code restructure failed: missing block: B:22:0x007f, code lost:
            if (r13 == false) goto L_0x008b;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:24:?, code lost:
            recordLockedRead(r2, r5);
         */
        /* JADX WARNING: Code restructure failed: missing block: B:25:0x0084, code lost:
            unlock();
            postWriteCleanup();
         */
        /* JADX WARNING: Code restructure failed: missing block: B:26:0x008a, code lost:
            return r7;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:28:?, code lost:
            r9.modCount++;
            enqueueNotification(r10, r11, r0, com.google.common.cache.RemovalCause.REPLACED);
            setValue(r2, r10, r12, r5);
            evictEntries();
         */
        @javax.annotation.Nullable
        /* Code decompiled incorrectly, please refer to instructions dump. */
        public V put(K r10, int r11, V r12, boolean r13) {
            /*
                r9 = this;
                r9.lock()
                com.google.common.cache.LocalCache<K, V> r0 = r9.map     // Catch:{ all -> 0x00c3 }
                com.google.common.base.Ticker r0 = r0.ticker     // Catch:{ all -> 0x00c3 }
                long r5 = r0.read()     // Catch:{ all -> 0x00c3 }
                r9.preWriteCleanup(r5)     // Catch:{ all -> 0x00c3 }
                int r0 = r9.count     // Catch:{ all -> 0x00c3 }
                int r0 = r0 + 1
                int r1 = r9.threshold     // Catch:{ all -> 0x00c3 }
                if (r0 <= r1) goto L_0x001b
                r9.expand()     // Catch:{ all -> 0x00c3 }
                int r0 = r9.count     // Catch:{ all -> 0x00c3 }
            L_0x001b:
                java.util.concurrent.atomic.AtomicReferenceArray<com.google.common.cache.LocalCache$ReferenceEntry<K, V>> r0 = r9.table     // Catch:{ all -> 0x00c3 }
                int r1 = r0.length()     // Catch:{ all -> 0x00c3 }
                int r1 = r1 + -1
                r7 = r11 & r1
                java.lang.Object r1 = r0.get(r7)     // Catch:{ all -> 0x00c3 }
                com.google.common.cache.LocalCache$ReferenceEntry r1 = (com.google.common.cache.LocalCache.ReferenceEntry) r1     // Catch:{ all -> 0x00c3 }
                r2 = r1
            L_0x002c:
                r8 = 0
                if (r2 == 0) goto L_0x00a5
                java.lang.Object r3 = r2.getKey()     // Catch:{ all -> 0x00c3 }
                int r4 = r2.getHash()     // Catch:{ all -> 0x00c3 }
                if (r4 != r11) goto L_0x00a0
                if (r3 == 0) goto L_0x00a0
                com.google.common.cache.LocalCache<K, V> r4 = r9.map     // Catch:{ all -> 0x00c3 }
                com.google.common.base.Equivalence<java.lang.Object> r4 = r4.keyEquivalence     // Catch:{ all -> 0x00c3 }
                boolean r3 = r4.equivalent(r10, r3)     // Catch:{ all -> 0x00c3 }
                if (r3 == 0) goto L_0x00a0
                com.google.common.cache.LocalCache$ValueReference r0 = r2.getValueReference()     // Catch:{ all -> 0x00c3 }
                java.lang.Object r7 = r0.get()     // Catch:{ all -> 0x00c3 }
                if (r7 != 0) goto L_0x007f
                int r13 = r9.modCount     // Catch:{ all -> 0x00c3 }
                int r13 = r13 + 1
                r9.modCount = r13     // Catch:{ all -> 0x00c3 }
                boolean r13 = r0.isActive()     // Catch:{ all -> 0x00c3 }
                if (r13 == 0) goto L_0x0069
                com.google.common.cache.RemovalCause r13 = com.google.common.cache.RemovalCause.COLLECTED     // Catch:{ all -> 0x00c3 }
                r9.enqueueNotification(r10, r11, r0, r13)     // Catch:{ all -> 0x00c3 }
                r1 = r9
                r3 = r10
                r4 = r12
                r1.setValue(r2, r3, r4, r5)     // Catch:{ all -> 0x00c3 }
                int r10 = r9.count     // Catch:{ all -> 0x00c3 }
                goto L_0x0073
            L_0x0069:
                r1 = r9
                r3 = r10
                r4 = r12
                r1.setValue(r2, r3, r4, r5)     // Catch:{ all -> 0x00c3 }
                int r10 = r9.count     // Catch:{ all -> 0x00c3 }
                int r10 = r10 + 1
            L_0x0073:
                r9.count = r10     // Catch:{ all -> 0x00c3 }
                r9.evictEntries()     // Catch:{ all -> 0x00c3 }
            L_0x0078:
                r9.unlock()
                r9.postWriteCleanup()
                return r8
            L_0x007f:
                if (r13 == 0) goto L_0x008b
                r9.recordLockedRead(r2, r5)     // Catch:{ all -> 0x00c3 }
            L_0x0084:
                r9.unlock()
                r9.postWriteCleanup()
                return r7
            L_0x008b:
                int r13 = r9.modCount     // Catch:{ all -> 0x00c3 }
                int r13 = r13 + 1
                r9.modCount = r13     // Catch:{ all -> 0x00c3 }
                com.google.common.cache.RemovalCause r13 = com.google.common.cache.RemovalCause.REPLACED     // Catch:{ all -> 0x00c3 }
                r9.enqueueNotification(r10, r11, r0, r13)     // Catch:{ all -> 0x00c3 }
                r1 = r9
                r3 = r10
                r4 = r12
                r1.setValue(r2, r3, r4, r5)     // Catch:{ all -> 0x00c3 }
                r9.evictEntries()     // Catch:{ all -> 0x00c3 }
                goto L_0x0084
            L_0x00a0:
                com.google.common.cache.LocalCache$ReferenceEntry r2 = r2.getNext()     // Catch:{ all -> 0x00c3 }
                goto L_0x002c
            L_0x00a5:
                int r13 = r9.modCount     // Catch:{ all -> 0x00c3 }
                int r13 = r13 + 1
                r9.modCount = r13     // Catch:{ all -> 0x00c3 }
                com.google.common.cache.LocalCache$ReferenceEntry r11 = r9.newEntry(r10, r11, r1)     // Catch:{ all -> 0x00c3 }
                r1 = r9
                r2 = r11
                r3 = r10
                r4 = r12
                r1.setValue(r2, r3, r4, r5)     // Catch:{ all -> 0x00c3 }
                r0.set(r7, r11)     // Catch:{ all -> 0x00c3 }
                int r10 = r9.count     // Catch:{ all -> 0x00c3 }
                int r10 = r10 + 1
                r9.count = r10     // Catch:{ all -> 0x00c3 }
                r9.evictEntries()     // Catch:{ all -> 0x00c3 }
                goto L_0x0078
            L_0x00c3:
                r10 = move-exception
                r9.unlock()
                r9.postWriteCleanup()
                throw r10
                return
            */
            throw new UnsupportedOperationException("Method not decompiled: com.google.common.cache.LocalCache.Segment.put(java.lang.Object, int, java.lang.Object, boolean):java.lang.Object");
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public void expand() {
            AtomicReferenceArray<ReferenceEntry<K, V>> atomicReferenceArray = this.table;
            int length = atomicReferenceArray.length();
            if (length < 1073741824) {
                int i = this.count;
                AtomicReferenceArray<ReferenceEntry<K, V>> newEntryArray = newEntryArray(length << 1);
                this.threshold = (newEntryArray.length() * 3) / 4;
                int length2 = newEntryArray.length() - 1;
                for (int i2 = 0; i2 < length; i2++) {
                    ReferenceEntry referenceEntry = (ReferenceEntry) atomicReferenceArray.get(i2);
                    if (referenceEntry != null) {
                        ReferenceEntry next = referenceEntry.getNext();
                        int hash = referenceEntry.getHash() & length2;
                        if (next == null) {
                            newEntryArray.set(hash, referenceEntry);
                        } else {
                            ReferenceEntry referenceEntry2 = referenceEntry;
                            while (next != null) {
                                int hash2 = next.getHash() & length2;
                                if (hash2 != hash) {
                                    referenceEntry2 = next;
                                    hash = hash2;
                                }
                                next = next.getNext();
                            }
                            newEntryArray.set(hash, referenceEntry2);
                            while (referenceEntry != referenceEntry2) {
                                int hash3 = referenceEntry.getHash() & length2;
                                ReferenceEntry copyEntry = copyEntry(referenceEntry, (ReferenceEntry) newEntryArray.get(hash3));
                                if (copyEntry != null) {
                                    newEntryArray.set(hash3, copyEntry);
                                } else {
                                    removeCollectedEntry(referenceEntry);
                                    i--;
                                }
                                referenceEntry = referenceEntry.getNext();
                            }
                        }
                    }
                }
                this.table = newEntryArray;
                this.count = i;
            }
        }

        /* access modifiers changed from: 0000 */
        /* JADX WARNING: Code restructure failed: missing block: B:10:0x003d, code lost:
            r13 = r3.getValueReference();
            r1 = r13.get();
         */
        /* JADX WARNING: Code restructure failed: missing block: B:11:0x0045, code lost:
            if (r1 != null) goto L_0x006d;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:13:0x004b, code lost:
            if (r13.isActive() == false) goto L_0x0066;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:14:0x004d, code lost:
            r0 = r8.count;
            r8.modCount++;
            r0 = removeValueFromChain(r2, r3, r4, r16, r13, com.google.common.cache.RemovalCause.COLLECTED);
            r1 = r8.count - 1;
            r9.set(r11, r0);
            r8.count = r1;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:19:0x0077, code lost:
            if (r8.map.valueEquivalence.equivalent(r17, r1) == false) goto L_0x0096;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:20:0x0079, code lost:
            r8.modCount++;
            enqueueNotification(r15, r5, r13, com.google.common.cache.RemovalCause.REPLACED);
            setValue(r3, r15, r18, r6);
            evictEntries();
         */
        /* JADX WARNING: Code restructure failed: missing block: B:21:0x008f, code lost:
            unlock();
            postWriteCleanup();
         */
        /* JADX WARNING: Code restructure failed: missing block: B:22:0x0095, code lost:
            return true;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:24:?, code lost:
            recordLockedRead(r3, r6);
         */
        /* Code decompiled incorrectly, please refer to instructions dump. */
        public boolean replace(K r15, int r16, V r17, V r18) {
            /*
                r14 = this;
                r8 = r14
                r0 = r15
                r5 = r16
                r14.lock()
                com.google.common.cache.LocalCache<K, V> r1 = r8.map     // Catch:{ all -> 0x00a1 }
                com.google.common.base.Ticker r1 = r1.ticker     // Catch:{ all -> 0x00a1 }
                long r6 = r1.read()     // Catch:{ all -> 0x00a1 }
                r14.preWriteCleanup(r6)     // Catch:{ all -> 0x00a1 }
                java.util.concurrent.atomic.AtomicReferenceArray<com.google.common.cache.LocalCache$ReferenceEntry<K, V>> r9 = r8.table     // Catch:{ all -> 0x00a1 }
                int r1 = r9.length()     // Catch:{ all -> 0x00a1 }
                r10 = 1
                int r1 = r1 - r10
                r11 = r5 & r1
                java.lang.Object r1 = r9.get(r11)     // Catch:{ all -> 0x00a1 }
                r2 = r1
                com.google.common.cache.LocalCache$ReferenceEntry r2 = (com.google.common.cache.LocalCache.ReferenceEntry) r2     // Catch:{ all -> 0x00a1 }
                r3 = r2
            L_0x0024:
                r12 = 0
                if (r3 == 0) goto L_0x0066
                java.lang.Object r4 = r3.getKey()     // Catch:{ all -> 0x00a1 }
                int r1 = r3.getHash()     // Catch:{ all -> 0x00a1 }
                if (r1 != r5) goto L_0x009a
                if (r4 == 0) goto L_0x009a
                com.google.common.cache.LocalCache<K, V> r1 = r8.map     // Catch:{ all -> 0x00a1 }
                com.google.common.base.Equivalence<java.lang.Object> r1 = r1.keyEquivalence     // Catch:{ all -> 0x00a1 }
                boolean r1 = r1.equivalent(r15, r4)     // Catch:{ all -> 0x00a1 }
                if (r1 == 0) goto L_0x009a
                com.google.common.cache.LocalCache$ValueReference r13 = r3.getValueReference()     // Catch:{ all -> 0x00a1 }
                java.lang.Object r1 = r13.get()     // Catch:{ all -> 0x00a1 }
                if (r1 != 0) goto L_0x006d
                boolean r0 = r13.isActive()     // Catch:{ all -> 0x00a1 }
                if (r0 == 0) goto L_0x0066
                int r0 = r8.count     // Catch:{ all -> 0x00a1 }
                int r0 = r8.modCount     // Catch:{ all -> 0x00a1 }
                int r0 = r0 + r10
                r8.modCount = r0     // Catch:{ all -> 0x00a1 }
                com.google.common.cache.RemovalCause r7 = com.google.common.cache.RemovalCause.COLLECTED     // Catch:{ all -> 0x00a1 }
                r1 = r14
                r5 = r16
                r6 = r13
                com.google.common.cache.LocalCache$ReferenceEntry r0 = r1.removeValueFromChain(r2, r3, r4, r5, r6, r7)     // Catch:{ all -> 0x00a1 }
                int r1 = r8.count     // Catch:{ all -> 0x00a1 }
                int r1 = r1 - r10
                r9.set(r11, r0)     // Catch:{ all -> 0x00a1 }
                r8.count = r1     // Catch:{ all -> 0x00a1 }
            L_0x0066:
                r14.unlock()
                r14.postWriteCleanup()
                return r12
            L_0x006d:
                com.google.common.cache.LocalCache<K, V> r2 = r8.map     // Catch:{ all -> 0x00a1 }
                com.google.common.base.Equivalence<java.lang.Object> r2 = r2.valueEquivalence     // Catch:{ all -> 0x00a1 }
                r4 = r17
                boolean r1 = r2.equivalent(r4, r1)     // Catch:{ all -> 0x00a1 }
                if (r1 == 0) goto L_0x0096
                int r1 = r8.modCount     // Catch:{ all -> 0x00a1 }
                int r1 = r1 + r10
                r8.modCount = r1     // Catch:{ all -> 0x00a1 }
                com.google.common.cache.RemovalCause r1 = com.google.common.cache.RemovalCause.REPLACED     // Catch:{ all -> 0x00a1 }
                r14.enqueueNotification(r15, r5, r13, r1)     // Catch:{ all -> 0x00a1 }
                r1 = r14
                r2 = r3
                r3 = r15
                r4 = r18
                r5 = r6
                r1.setValue(r2, r3, r4, r5)     // Catch:{ all -> 0x00a1 }
                r14.evictEntries()     // Catch:{ all -> 0x00a1 }
                r14.unlock()
                r14.postWriteCleanup()
                return r10
            L_0x0096:
                r14.recordLockedRead(r3, r6)     // Catch:{ all -> 0x00a1 }
                goto L_0x0066
            L_0x009a:
                r4 = r17
                com.google.common.cache.LocalCache$ReferenceEntry r3 = r3.getNext()     // Catch:{ all -> 0x00a1 }
                goto L_0x0024
            L_0x00a1:
                r0 = move-exception
                r14.unlock()
                r14.postWriteCleanup()
                throw r0
                return
            */
            throw new UnsupportedOperationException("Method not decompiled: com.google.common.cache.LocalCache.Segment.replace(java.lang.Object, int, java.lang.Object, java.lang.Object):boolean");
        }

        /* access modifiers changed from: 0000 */
        /* JADX WARNING: Code restructure failed: missing block: B:10:0x003d, code lost:
            r12 = r3.getValueReference();
            r13 = r12.get();
         */
        /* JADX WARNING: Code restructure failed: missing block: B:11:0x0045, code lost:
            if (r13 != null) goto L_0x006f;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:13:0x004b, code lost:
            if (r12.isActive() == false) goto L_0x0068;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:14:0x004d, code lost:
            r0 = r8.count;
            r8.modCount++;
            r0 = removeValueFromChain(r2, r3, r4, r16, r12, com.google.common.cache.RemovalCause.COLLECTED);
            r1 = r8.count - 1;
            r9.set(r10, r0);
            r8.count = r1;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:18:?, code lost:
            r8.modCount++;
            enqueueNotification(r15, r5, r12, com.google.common.cache.RemovalCause.REPLACED);
            setValue(r3, r15, r17, r6);
            evictEntries();
         */
        /* JADX WARNING: Code restructure failed: missing block: B:19:0x0086, code lost:
            unlock();
            postWriteCleanup();
         */
        /* JADX WARNING: Code restructure failed: missing block: B:20:0x008c, code lost:
            return r13;
         */
        @javax.annotation.Nullable
        /* Code decompiled incorrectly, please refer to instructions dump. */
        public V replace(K r15, int r16, V r17) {
            /*
                r14 = this;
                r8 = r14
                r0 = r15
                r5 = r16
                r14.lock()
                com.google.common.cache.LocalCache<K, V> r1 = r8.map     // Catch:{ all -> 0x0092 }
                com.google.common.base.Ticker r1 = r1.ticker     // Catch:{ all -> 0x0092 }
                long r6 = r1.read()     // Catch:{ all -> 0x0092 }
                r14.preWriteCleanup(r6)     // Catch:{ all -> 0x0092 }
                java.util.concurrent.atomic.AtomicReferenceArray<com.google.common.cache.LocalCache$ReferenceEntry<K, V>> r9 = r8.table     // Catch:{ all -> 0x0092 }
                int r1 = r9.length()     // Catch:{ all -> 0x0092 }
                int r1 = r1 + -1
                r10 = r5 & r1
                java.lang.Object r1 = r9.get(r10)     // Catch:{ all -> 0x0092 }
                r2 = r1
                com.google.common.cache.LocalCache$ReferenceEntry r2 = (com.google.common.cache.LocalCache.ReferenceEntry) r2     // Catch:{ all -> 0x0092 }
                r3 = r2
            L_0x0024:
                r11 = 0
                if (r3 == 0) goto L_0x0068
                java.lang.Object r4 = r3.getKey()     // Catch:{ all -> 0x0092 }
                int r1 = r3.getHash()     // Catch:{ all -> 0x0092 }
                if (r1 != r5) goto L_0x008d
                if (r4 == 0) goto L_0x008d
                com.google.common.cache.LocalCache<K, V> r1 = r8.map     // Catch:{ all -> 0x0092 }
                com.google.common.base.Equivalence<java.lang.Object> r1 = r1.keyEquivalence     // Catch:{ all -> 0x0092 }
                boolean r1 = r1.equivalent(r15, r4)     // Catch:{ all -> 0x0092 }
                if (r1 == 0) goto L_0x008d
                com.google.common.cache.LocalCache$ValueReference r12 = r3.getValueReference()     // Catch:{ all -> 0x0092 }
                java.lang.Object r13 = r12.get()     // Catch:{ all -> 0x0092 }
                if (r13 != 0) goto L_0x006f
                boolean r0 = r12.isActive()     // Catch:{ all -> 0x0092 }
                if (r0 == 0) goto L_0x0068
                int r0 = r8.count     // Catch:{ all -> 0x0092 }
                int r0 = r8.modCount     // Catch:{ all -> 0x0092 }
                int r0 = r0 + 1
                r8.modCount = r0     // Catch:{ all -> 0x0092 }
                com.google.common.cache.RemovalCause r7 = com.google.common.cache.RemovalCause.COLLECTED     // Catch:{ all -> 0x0092 }
                r1 = r14
                r5 = r16
                r6 = r12
                com.google.common.cache.LocalCache$ReferenceEntry r0 = r1.removeValueFromChain(r2, r3, r4, r5, r6, r7)     // Catch:{ all -> 0x0092 }
                int r1 = r8.count     // Catch:{ all -> 0x0092 }
                int r1 = r1 + -1
                r9.set(r10, r0)     // Catch:{ all -> 0x0092 }
                r8.count = r1     // Catch:{ all -> 0x0092 }
            L_0x0068:
                r14.unlock()
                r14.postWriteCleanup()
                return r11
            L_0x006f:
                int r1 = r8.modCount     // Catch:{ all -> 0x0092 }
                int r1 = r1 + 1
                r8.modCount = r1     // Catch:{ all -> 0x0092 }
                com.google.common.cache.RemovalCause r1 = com.google.common.cache.RemovalCause.REPLACED     // Catch:{ all -> 0x0092 }
                r14.enqueueNotification(r15, r5, r12, r1)     // Catch:{ all -> 0x0092 }
                r1 = r14
                r2 = r3
                r3 = r15
                r4 = r17
                r5 = r6
                r1.setValue(r2, r3, r4, r5)     // Catch:{ all -> 0x0092 }
                r14.evictEntries()     // Catch:{ all -> 0x0092 }
                r14.unlock()
                r14.postWriteCleanup()
                return r13
            L_0x008d:
                com.google.common.cache.LocalCache$ReferenceEntry r3 = r3.getNext()     // Catch:{ all -> 0x0092 }
                goto L_0x0024
            L_0x0092:
                r0 = move-exception
                r14.unlock()
                r14.postWriteCleanup()
                throw r0
                return
            */
            throw new UnsupportedOperationException("Method not decompiled: com.google.common.cache.LocalCache.Segment.replace(java.lang.Object, int, java.lang.Object):java.lang.Object");
        }

        /* access modifiers changed from: 0000 */
        @Nullable
        public V remove(Object obj, int i) {
            RemovalCause removalCause;
            lock();
            try {
                preWriteCleanup(this.map.ticker.read());
                int i2 = this.count;
                AtomicReferenceArray<ReferenceEntry<K, V>> atomicReferenceArray = this.table;
                int length = (atomicReferenceArray.length() - 1) & i;
                ReferenceEntry referenceEntry = (ReferenceEntry) atomicReferenceArray.get(length);
                ReferenceEntry referenceEntry2 = referenceEntry;
                while (true) {
                    if (referenceEntry2 == null) {
                        break;
                    }
                    Object key = referenceEntry2.getKey();
                    if (referenceEntry2.getHash() != i || key == null || !this.map.keyEquivalence.equivalent(obj, key)) {
                        referenceEntry2 = referenceEntry2.getNext();
                    } else {
                        ValueReference valueReference = referenceEntry2.getValueReference();
                        V v = valueReference.get();
                        if (v != null) {
                            removalCause = RemovalCause.EXPLICIT;
                        } else if (valueReference.isActive()) {
                            removalCause = RemovalCause.COLLECTED;
                        }
                        this.modCount++;
                        int i3 = this.count - 1;
                        atomicReferenceArray.set(length, removeValueFromChain(referenceEntry, referenceEntry2, key, i, valueReference, removalCause));
                        this.count = i3;
                        return v;
                    }
                }
                unlock();
                postWriteCleanup();
                return null;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        /* access modifiers changed from: 0000 */
        /* JADX WARNING: Code restructure failed: missing block: B:12:0x0044, code lost:
            r1 = r2.getValueReference();
            r3 = r1.get();
         */
        /* JADX WARNING: Code restructure failed: missing block: B:13:0x004c, code lost:
            if (r13 == r1) goto L_0x0067;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:14:0x004e, code lost:
            if (r3 != null) goto L_0x0055;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:16:0x0052, code lost:
            if (r1 == com.google.common.cache.LocalCache.UNSET) goto L_0x0055;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:18:0x0055, code lost:
            enqueueNotification(r11, r12, new com.google.common.cache.LocalCache.WeightedStrongValueReference(r14, 0), com.google.common.cache.RemovalCause.REPLACED);
         */
        /* JADX WARNING: Code restructure failed: missing block: B:19:0x0060, code lost:
            unlock();
            postWriteCleanup();
         */
        /* JADX WARNING: Code restructure failed: missing block: B:20:0x0066, code lost:
            return false;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:22:?, code lost:
            r10.modCount++;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:23:0x0070, code lost:
            if (r13.isActive() == false) goto L_0x007e;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:24:0x0072, code lost:
            if (r3 != null) goto L_0x0077;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:25:0x0074, code lost:
            r1 = com.google.common.cache.RemovalCause.COLLECTED;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:26:0x0077, code lost:
            r1 = com.google.common.cache.RemovalCause.REPLACED;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:27:0x0079, code lost:
            enqueueNotification(r11, r12, r13, r1);
            r0 = r0 - 1;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:28:0x007e, code lost:
            setValue(r2, r11, r14, r5);
            r10.count = r0;
            evictEntries();
         */
        /* Code decompiled incorrectly, please refer to instructions dump. */
        public boolean storeLoadedValue(K r11, int r12, com.google.common.cache.LocalCache.LoadingValueReference<K, V> r13, V r14) {
            /*
                r10 = this;
                r10.lock()
                com.google.common.cache.LocalCache<K, V> r0 = r10.map     // Catch:{ all -> 0x00ae }
                com.google.common.base.Ticker r0 = r0.ticker     // Catch:{ all -> 0x00ae }
                long r5 = r0.read()     // Catch:{ all -> 0x00ae }
                r10.preWriteCleanup(r5)     // Catch:{ all -> 0x00ae }
                int r0 = r10.count     // Catch:{ all -> 0x00ae }
                r7 = 1
                int r0 = r0 + r7
                int r1 = r10.threshold     // Catch:{ all -> 0x00ae }
                if (r0 <= r1) goto L_0x001c
                r10.expand()     // Catch:{ all -> 0x00ae }
                int r0 = r10.count     // Catch:{ all -> 0x00ae }
                int r0 = r0 + r7
            L_0x001c:
                java.util.concurrent.atomic.AtomicReferenceArray<com.google.common.cache.LocalCache$ReferenceEntry<K, V>> r8 = r10.table     // Catch:{ all -> 0x00ae }
                int r1 = r8.length()     // Catch:{ all -> 0x00ae }
                int r1 = r1 - r7
                r9 = r12 & r1
                java.lang.Object r1 = r8.get(r9)     // Catch:{ all -> 0x00ae }
                com.google.common.cache.LocalCache$ReferenceEntry r1 = (com.google.common.cache.LocalCache.ReferenceEntry) r1     // Catch:{ all -> 0x00ae }
                r2 = r1
            L_0x002c:
                if (r2 == 0) goto L_0x0095
                java.lang.Object r3 = r2.getKey()     // Catch:{ all -> 0x00ae }
                int r4 = r2.getHash()     // Catch:{ all -> 0x00ae }
                if (r4 != r12) goto L_0x0090
                if (r3 == 0) goto L_0x0090
                com.google.common.cache.LocalCache<K, V> r4 = r10.map     // Catch:{ all -> 0x00ae }
                com.google.common.base.Equivalence<java.lang.Object> r4 = r4.keyEquivalence     // Catch:{ all -> 0x00ae }
                boolean r3 = r4.equivalent(r11, r3)     // Catch:{ all -> 0x00ae }
                if (r3 == 0) goto L_0x0090
                com.google.common.cache.LocalCache$ValueReference r1 = r2.getValueReference()     // Catch:{ all -> 0x00ae }
                java.lang.Object r3 = r1.get()     // Catch:{ all -> 0x00ae }
                if (r13 == r1) goto L_0x0067
                if (r3 != 0) goto L_0x0055
                com.google.common.cache.LocalCache$ValueReference<java.lang.Object, java.lang.Object> r4 = com.google.common.cache.LocalCache.UNSET     // Catch:{ all -> 0x00ae }
                if (r1 == r4) goto L_0x0055
                goto L_0x0067
            L_0x0055:
                com.google.common.cache.LocalCache$WeightedStrongValueReference r13 = new com.google.common.cache.LocalCache$WeightedStrongValueReference     // Catch:{ all -> 0x00ae }
                r0 = 0
                r13.<init>(r14, r0)     // Catch:{ all -> 0x00ae }
                com.google.common.cache.RemovalCause r14 = com.google.common.cache.RemovalCause.REPLACED     // Catch:{ all -> 0x00ae }
                r10.enqueueNotification(r11, r12, r13, r14)     // Catch:{ all -> 0x00ae }
                r10.unlock()
                r10.postWriteCleanup()
                return r0
            L_0x0067:
                int r1 = r10.modCount     // Catch:{ all -> 0x00ae }
                int r1 = r1 + r7
                r10.modCount = r1     // Catch:{ all -> 0x00ae }
                boolean r1 = r13.isActive()     // Catch:{ all -> 0x00ae }
                if (r1 == 0) goto L_0x007e
                if (r3 != 0) goto L_0x0077
                com.google.common.cache.RemovalCause r1 = com.google.common.cache.RemovalCause.COLLECTED     // Catch:{ all -> 0x00ae }
                goto L_0x0079
            L_0x0077:
                com.google.common.cache.RemovalCause r1 = com.google.common.cache.RemovalCause.REPLACED     // Catch:{ all -> 0x00ae }
            L_0x0079:
                r10.enqueueNotification(r11, r12, r13, r1)     // Catch:{ all -> 0x00ae }
                int r0 = r0 + -1
            L_0x007e:
                r1 = r10
                r3 = r11
                r4 = r14
                r1.setValue(r2, r3, r4, r5)     // Catch:{ all -> 0x00ae }
                r10.count = r0     // Catch:{ all -> 0x00ae }
                r10.evictEntries()     // Catch:{ all -> 0x00ae }
            L_0x0089:
                r10.unlock()
                r10.postWriteCleanup()
                return r7
            L_0x0090:
                com.google.common.cache.LocalCache$ReferenceEntry r2 = r2.getNext()     // Catch:{ all -> 0x00ae }
                goto L_0x002c
            L_0x0095:
                int r13 = r10.modCount     // Catch:{ all -> 0x00ae }
                int r13 = r13 + r7
                r10.modCount = r13     // Catch:{ all -> 0x00ae }
                com.google.common.cache.LocalCache$ReferenceEntry r12 = r10.newEntry(r11, r12, r1)     // Catch:{ all -> 0x00ae }
                r1 = r10
                r2 = r12
                r3 = r11
                r4 = r14
                r1.setValue(r2, r3, r4, r5)     // Catch:{ all -> 0x00ae }
                r8.set(r9, r12)     // Catch:{ all -> 0x00ae }
                r10.count = r0     // Catch:{ all -> 0x00ae }
                r10.evictEntries()     // Catch:{ all -> 0x00ae }
                goto L_0x0089
            L_0x00ae:
                r11 = move-exception
                r10.unlock()
                r10.postWriteCleanup()
                throw r11
                return
            */
            throw new UnsupportedOperationException("Method not decompiled: com.google.common.cache.LocalCache.Segment.storeLoadedValue(java.lang.Object, int, com.google.common.cache.LocalCache$LoadingValueReference, java.lang.Object):boolean");
        }

        /* access modifiers changed from: 0000 */
        public boolean remove(Object obj, int i, Object obj2) {
            RemovalCause removalCause;
            lock();
            try {
                preWriteCleanup(this.map.ticker.read());
                int i2 = this.count;
                AtomicReferenceArray<ReferenceEntry<K, V>> atomicReferenceArray = this.table;
                boolean z = true;
                int length = (atomicReferenceArray.length() - 1) & i;
                ReferenceEntry referenceEntry = (ReferenceEntry) atomicReferenceArray.get(length);
                ReferenceEntry referenceEntry2 = referenceEntry;
                while (true) {
                    if (referenceEntry2 == null) {
                        break;
                    }
                    Object key = referenceEntry2.getKey();
                    if (referenceEntry2.getHash() != i || key == null || !this.map.keyEquivalence.equivalent(obj, key)) {
                        referenceEntry2 = referenceEntry2.getNext();
                    } else {
                        ValueReference valueReference = referenceEntry2.getValueReference();
                        Object obj3 = valueReference.get();
                        if (this.map.valueEquivalence.equivalent(obj2, obj3)) {
                            removalCause = RemovalCause.EXPLICIT;
                        } else if (obj3 == null && valueReference.isActive()) {
                            removalCause = RemovalCause.COLLECTED;
                        }
                        this.modCount++;
                        int i3 = this.count - 1;
                        atomicReferenceArray.set(length, removeValueFromChain(referenceEntry, referenceEntry2, key, i, valueReference, removalCause));
                        this.count = i3;
                        if (removalCause != RemovalCause.EXPLICIT) {
                            z = false;
                        }
                        return z;
                    }
                }
                unlock();
                postWriteCleanup();
                return false;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        /* access modifiers changed from: 0000 */
        public void clear() {
            if (this.count != 0) {
                lock();
                try {
                    AtomicReferenceArray<ReferenceEntry<K, V>> atomicReferenceArray = this.table;
                    for (int i = 0; i < atomicReferenceArray.length(); i++) {
                        for (ReferenceEntry referenceEntry = (ReferenceEntry) atomicReferenceArray.get(i); referenceEntry != null; referenceEntry = referenceEntry.getNext()) {
                            if (referenceEntry.getValueReference().isActive()) {
                                enqueueNotification(referenceEntry, RemovalCause.EXPLICIT);
                            }
                        }
                    }
                    for (int i2 = 0; i2 < atomicReferenceArray.length(); i2++) {
                        atomicReferenceArray.set(i2, null);
                    }
                    clearReferenceQueues();
                    this.writeQueue.clear();
                    this.accessQueue.clear();
                    this.readCount.set(0);
                    this.modCount++;
                    this.count = 0;
                } finally {
                    unlock();
                    postWriteCleanup();
                }
            }
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        @Nullable
        public ReferenceEntry<K, V> removeValueFromChain(ReferenceEntry<K, V> referenceEntry, ReferenceEntry<K, V> referenceEntry2, @Nullable K k, int i, ValueReference<K, V> valueReference, RemovalCause removalCause) {
            enqueueNotification(k, i, valueReference, removalCause);
            this.writeQueue.remove(referenceEntry2);
            this.accessQueue.remove(referenceEntry2);
            if (!valueReference.isLoading()) {
                return removeEntryFromChain(referenceEntry, referenceEntry2);
            }
            valueReference.notifyNewValue(null);
            return referenceEntry;
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        @Nullable
        public ReferenceEntry<K, V> removeEntryFromChain(ReferenceEntry<K, V> referenceEntry, ReferenceEntry<K, V> referenceEntry2) {
            int i = this.count;
            ReferenceEntry<K, V> next = referenceEntry2.getNext();
            while (referenceEntry != referenceEntry2) {
                ReferenceEntry<K, V> copyEntry = copyEntry(referenceEntry, next);
                if (copyEntry != null) {
                    next = copyEntry;
                } else {
                    removeCollectedEntry(referenceEntry);
                    i--;
                }
                referenceEntry = referenceEntry.getNext();
            }
            this.count = i;
            return next;
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public void removeCollectedEntry(ReferenceEntry<K, V> referenceEntry) {
            enqueueNotification(referenceEntry, RemovalCause.COLLECTED);
            this.writeQueue.remove(referenceEntry);
            this.accessQueue.remove(referenceEntry);
        }

        /* access modifiers changed from: 0000 */
        public boolean reclaimKey(ReferenceEntry<K, V> referenceEntry, int i) {
            lock();
            try {
                int i2 = this.count;
                AtomicReferenceArray<ReferenceEntry<K, V>> atomicReferenceArray = this.table;
                int length = (atomicReferenceArray.length() - 1) & i;
                ReferenceEntry<K, V> referenceEntry2 = (ReferenceEntry) atomicReferenceArray.get(length);
                for (ReferenceEntry<K, V> referenceEntry3 = referenceEntry2; referenceEntry3 != null; referenceEntry3 = referenceEntry3.getNext()) {
                    if (referenceEntry3 == referenceEntry) {
                        this.modCount++;
                        int i3 = this.count - 1;
                        atomicReferenceArray.set(length, removeValueFromChain(referenceEntry2, referenceEntry3, referenceEntry3.getKey(), i, referenceEntry3.getValueReference(), RemovalCause.COLLECTED));
                        this.count = i3;
                        return true;
                    }
                }
                unlock();
                postWriteCleanup();
                return false;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        /* access modifiers changed from: 0000 */
        public boolean reclaimValue(K k, int i, ValueReference<K, V> valueReference) {
            lock();
            try {
                int i2 = this.count;
                AtomicReferenceArray<ReferenceEntry<K, V>> atomicReferenceArray = this.table;
                int length = (atomicReferenceArray.length() - 1) & i;
                ReferenceEntry referenceEntry = (ReferenceEntry) atomicReferenceArray.get(length);
                ReferenceEntry referenceEntry2 = referenceEntry;
                while (referenceEntry2 != null) {
                    Object key = referenceEntry2.getKey();
                    if (referenceEntry2.getHash() != i || key == null || !this.map.keyEquivalence.equivalent(k, key)) {
                        referenceEntry2 = referenceEntry2.getNext();
                    } else if (referenceEntry2.getValueReference() == valueReference) {
                        this.modCount++;
                        int i3 = this.count - 1;
                        atomicReferenceArray.set(length, removeValueFromChain(referenceEntry, referenceEntry2, key, i, valueReference, RemovalCause.COLLECTED));
                        this.count = i3;
                        return true;
                    } else {
                        unlock();
                        if (!isHeldByCurrentThread()) {
                            postWriteCleanup();
                        }
                        return false;
                    }
                }
                unlock();
                if (!isHeldByCurrentThread()) {
                    postWriteCleanup();
                }
                return false;
            } finally {
                unlock();
                if (!isHeldByCurrentThread()) {
                    postWriteCleanup();
                }
            }
        }

        /* access modifiers changed from: 0000 */
        public boolean removeLoadingValue(K k, int i, LoadingValueReference<K, V> loadingValueReference) {
            lock();
            try {
                AtomicReferenceArray<ReferenceEntry<K, V>> atomicReferenceArray = this.table;
                int length = (atomicReferenceArray.length() - 1) & i;
                ReferenceEntry referenceEntry = (ReferenceEntry) atomicReferenceArray.get(length);
                ReferenceEntry referenceEntry2 = referenceEntry;
                while (true) {
                    if (referenceEntry2 == null) {
                        break;
                    }
                    Object key = referenceEntry2.getKey();
                    if (referenceEntry2.getHash() != i || key == null || !this.map.keyEquivalence.equivalent(k, key)) {
                        referenceEntry2 = referenceEntry2.getNext();
                    } else if (referenceEntry2.getValueReference() == loadingValueReference) {
                        if (loadingValueReference.isActive()) {
                            referenceEntry2.setValueReference(loadingValueReference.getOldValue());
                        } else {
                            atomicReferenceArray.set(length, removeEntryFromChain(referenceEntry, referenceEntry2));
                        }
                        return true;
                    }
                }
                unlock();
                postWriteCleanup();
                return false;
            } finally {
                unlock();
                postWriteCleanup();
            }
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public boolean removeEntry(ReferenceEntry<K, V> referenceEntry, int i, RemovalCause removalCause) {
            int i2 = this.count;
            AtomicReferenceArray<ReferenceEntry<K, V>> atomicReferenceArray = this.table;
            int length = (atomicReferenceArray.length() - 1) & i;
            ReferenceEntry<K, V> referenceEntry2 = (ReferenceEntry) atomicReferenceArray.get(length);
            for (ReferenceEntry<K, V> referenceEntry3 = referenceEntry2; referenceEntry3 != null; referenceEntry3 = referenceEntry3.getNext()) {
                if (referenceEntry3 == referenceEntry) {
                    this.modCount++;
                    int i3 = this.count - 1;
                    atomicReferenceArray.set(length, removeValueFromChain(referenceEntry2, referenceEntry3, referenceEntry3.getKey(), i, referenceEntry3.getValueReference(), removalCause));
                    this.count = i3;
                    return true;
                }
            }
            return false;
        }

        /* access modifiers changed from: 0000 */
        public void postReadCleanup() {
            if ((this.readCount.incrementAndGet() & 63) == 0) {
                cleanUp();
            }
        }

        /* access modifiers changed from: 0000 */
        @GuardedBy("Segment.this")
        public void preWriteCleanup(long j) {
            runLockedCleanup(j);
        }

        /* access modifiers changed from: 0000 */
        public void postWriteCleanup() {
            runUnlockedCleanup();
        }

        /* access modifiers changed from: 0000 */
        public void cleanUp() {
            runLockedCleanup(this.map.ticker.read());
            runUnlockedCleanup();
        }

        /* access modifiers changed from: 0000 */
        public void runLockedCleanup(long j) {
            if (tryLock()) {
                try {
                    drainReferenceQueues();
                    expireEntries(j);
                    this.readCount.set(0);
                } finally {
                    unlock();
                }
            }
        }

        /* access modifiers changed from: 0000 */
        public void runUnlockedCleanup() {
            if (!isHeldByCurrentThread()) {
                this.map.processPendingNotifications();
            }
        }
    }

    static class SoftValueReference<K, V> extends SoftReference<V> implements ValueReference<K, V> {
        final ReferenceEntry<K, V> entry;

        public int getWeight() {
            return 1;
        }

        public boolean isActive() {
            return true;
        }

        public boolean isLoading() {
            return false;
        }

        public void notifyNewValue(V v) {
        }

        SoftValueReference(ReferenceQueue<V> referenceQueue, V v, ReferenceEntry<K, V> referenceEntry) {
            super(v, referenceQueue);
            this.entry = referenceEntry;
        }

        public ReferenceEntry<K, V> getEntry() {
            return this.entry;
        }

        public ValueReference<K, V> copyFor(ReferenceQueue<V> referenceQueue, V v, ReferenceEntry<K, V> referenceEntry) {
            return new SoftValueReference(referenceQueue, v, referenceEntry);
        }

        public V waitForValue() {
            return get();
        }
    }

    enum Strength {
        STRONG {
            /* access modifiers changed from: 0000 */
            public <K, V> ValueReference<K, V> referenceValue(Segment<K, V> segment, ReferenceEntry<K, V> referenceEntry, V v, int i) {
                return i == 1 ? new StrongValueReference(v) : new WeightedStrongValueReference(v, i);
            }

            /* access modifiers changed from: 0000 */
            public Equivalence<Object> defaultEquivalence() {
                return Equivalence.equals();
            }
        },
        SOFT {
            /* access modifiers changed from: 0000 */
            public <K, V> ValueReference<K, V> referenceValue(Segment<K, V> segment, ReferenceEntry<K, V> referenceEntry, V v, int i) {
                return i == 1 ? new SoftValueReference(segment.valueReferenceQueue, v, referenceEntry) : new WeightedSoftValueReference(segment.valueReferenceQueue, v, referenceEntry, i);
            }

            /* access modifiers changed from: 0000 */
            public Equivalence<Object> defaultEquivalence() {
                return Equivalence.identity();
            }
        },
        WEAK {
            /* access modifiers changed from: 0000 */
            public <K, V> ValueReference<K, V> referenceValue(Segment<K, V> segment, ReferenceEntry<K, V> referenceEntry, V v, int i) {
                return i == 1 ? new WeakValueReference(segment.valueReferenceQueue, v, referenceEntry) : new WeightedWeakValueReference(segment.valueReferenceQueue, v, referenceEntry, i);
            }

            /* access modifiers changed from: 0000 */
            public Equivalence<Object> defaultEquivalence() {
                return Equivalence.identity();
            }
        };

        /* access modifiers changed from: 0000 */
        public abstract Equivalence<Object> defaultEquivalence();

        /* access modifiers changed from: 0000 */
        public abstract <K, V> ValueReference<K, V> referenceValue(Segment<K, V> segment, ReferenceEntry<K, V> referenceEntry, V v, int i);
    }

    static final class StrongAccessEntry<K, V> extends StrongEntry<K, V> {
        volatile long accessTime = Long.MAX_VALUE;
        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> nextAccess = LocalCache.nullEntry();
        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> previousAccess = LocalCache.nullEntry();

        StrongAccessEntry(K k, int i, @Nullable ReferenceEntry<K, V> referenceEntry) {
            super(k, i, referenceEntry);
        }

        public long getAccessTime() {
            return this.accessTime;
        }

        public void setAccessTime(long j) {
            this.accessTime = j;
        }

        public ReferenceEntry<K, V> getNextInAccessQueue() {
            return this.nextAccess;
        }

        public void setNextInAccessQueue(ReferenceEntry<K, V> referenceEntry) {
            this.nextAccess = referenceEntry;
        }

        public ReferenceEntry<K, V> getPreviousInAccessQueue() {
            return this.previousAccess;
        }

        public void setPreviousInAccessQueue(ReferenceEntry<K, V> referenceEntry) {
            this.previousAccess = referenceEntry;
        }
    }

    static final class StrongAccessWriteEntry<K, V> extends StrongEntry<K, V> {
        volatile long accessTime = Long.MAX_VALUE;
        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> nextAccess = LocalCache.nullEntry();
        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> nextWrite = LocalCache.nullEntry();
        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> previousAccess = LocalCache.nullEntry();
        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> previousWrite = LocalCache.nullEntry();
        volatile long writeTime = Long.MAX_VALUE;

        StrongAccessWriteEntry(K k, int i, @Nullable ReferenceEntry<K, V> referenceEntry) {
            super(k, i, referenceEntry);
        }

        public long getAccessTime() {
            return this.accessTime;
        }

        public void setAccessTime(long j) {
            this.accessTime = j;
        }

        public ReferenceEntry<K, V> getNextInAccessQueue() {
            return this.nextAccess;
        }

        public void setNextInAccessQueue(ReferenceEntry<K, V> referenceEntry) {
            this.nextAccess = referenceEntry;
        }

        public ReferenceEntry<K, V> getPreviousInAccessQueue() {
            return this.previousAccess;
        }

        public void setPreviousInAccessQueue(ReferenceEntry<K, V> referenceEntry) {
            this.previousAccess = referenceEntry;
        }

        public long getWriteTime() {
            return this.writeTime;
        }

        public void setWriteTime(long j) {
            this.writeTime = j;
        }

        public ReferenceEntry<K, V> getNextInWriteQueue() {
            return this.nextWrite;
        }

        public void setNextInWriteQueue(ReferenceEntry<K, V> referenceEntry) {
            this.nextWrite = referenceEntry;
        }

        public ReferenceEntry<K, V> getPreviousInWriteQueue() {
            return this.previousWrite;
        }

        public void setPreviousInWriteQueue(ReferenceEntry<K, V> referenceEntry) {
            this.previousWrite = referenceEntry;
        }
    }

    static class StrongEntry<K, V> extends AbstractReferenceEntry<K, V> {
        final int hash;
        final K key;
        final ReferenceEntry<K, V> next;
        volatile ValueReference<K, V> valueReference = LocalCache.unset();

        StrongEntry(K k, int i, @Nullable ReferenceEntry<K, V> referenceEntry) {
            this.key = k;
            this.hash = i;
            this.next = referenceEntry;
        }

        public K getKey() {
            return this.key;
        }

        public ValueReference<K, V> getValueReference() {
            return this.valueReference;
        }

        public void setValueReference(ValueReference<K, V> valueReference2) {
            this.valueReference = valueReference2;
        }

        public int getHash() {
            return this.hash;
        }

        public ReferenceEntry<K, V> getNext() {
            return this.next;
        }
    }

    static class StrongValueReference<K, V> implements ValueReference<K, V> {
        final V referent;

        public ValueReference<K, V> copyFor(ReferenceQueue<V> referenceQueue, V v, ReferenceEntry<K, V> referenceEntry) {
            return this;
        }

        public ReferenceEntry<K, V> getEntry() {
            return null;
        }

        public int getWeight() {
            return 1;
        }

        public boolean isActive() {
            return true;
        }

        public boolean isLoading() {
            return false;
        }

        public void notifyNewValue(V v) {
        }

        StrongValueReference(V v) {
            this.referent = v;
        }

        public V get() {
            return this.referent;
        }

        public V waitForValue() {
            return get();
        }
    }

    static final class StrongWriteEntry<K, V> extends StrongEntry<K, V> {
        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> nextWrite = LocalCache.nullEntry();
        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> previousWrite = LocalCache.nullEntry();
        volatile long writeTime = Long.MAX_VALUE;

        StrongWriteEntry(K k, int i, @Nullable ReferenceEntry<K, V> referenceEntry) {
            super(k, i, referenceEntry);
        }

        public long getWriteTime() {
            return this.writeTime;
        }

        public void setWriteTime(long j) {
            this.writeTime = j;
        }

        public ReferenceEntry<K, V> getNextInWriteQueue() {
            return this.nextWrite;
        }

        public void setNextInWriteQueue(ReferenceEntry<K, V> referenceEntry) {
            this.nextWrite = referenceEntry;
        }

        public ReferenceEntry<K, V> getPreviousInWriteQueue() {
            return this.previousWrite;
        }

        public void setPreviousInWriteQueue(ReferenceEntry<K, V> referenceEntry) {
            this.previousWrite = referenceEntry;
        }
    }

    final class ValueIterator extends HashIterator<V> {
        ValueIterator() {
            super();
        }

        public V next() {
            return nextEntry().getValue();
        }
    }

    interface ValueReference<K, V> {
        ValueReference<K, V> copyFor(ReferenceQueue<V> referenceQueue, @Nullable V v, ReferenceEntry<K, V> referenceEntry);

        @Nullable
        V get();

        @Nullable
        ReferenceEntry<K, V> getEntry();

        int getWeight();

        boolean isActive();

        boolean isLoading();

        void notifyNewValue(@Nullable V v);

        V waitForValue() throws ExecutionException;
    }

    final class Values extends AbstractCacheSet<V> {
        Values(ConcurrentMap<?, ?> concurrentMap) {
            super(concurrentMap);
        }

        public Iterator<V> iterator() {
            return new ValueIterator();
        }

        public boolean contains(Object obj) {
            return this.map.containsValue(obj);
        }
    }

    static final class WeakAccessEntry<K, V> extends WeakEntry<K, V> {
        volatile long accessTime = Long.MAX_VALUE;
        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> nextAccess = LocalCache.nullEntry();
        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> previousAccess = LocalCache.nullEntry();

        WeakAccessEntry(ReferenceQueue<K> referenceQueue, K k, int i, @Nullable ReferenceEntry<K, V> referenceEntry) {
            super(referenceQueue, k, i, referenceEntry);
        }

        public long getAccessTime() {
            return this.accessTime;
        }

        public void setAccessTime(long j) {
            this.accessTime = j;
        }

        public ReferenceEntry<K, V> getNextInAccessQueue() {
            return this.nextAccess;
        }

        public void setNextInAccessQueue(ReferenceEntry<K, V> referenceEntry) {
            this.nextAccess = referenceEntry;
        }

        public ReferenceEntry<K, V> getPreviousInAccessQueue() {
            return this.previousAccess;
        }

        public void setPreviousInAccessQueue(ReferenceEntry<K, V> referenceEntry) {
            this.previousAccess = referenceEntry;
        }
    }

    static final class WeakAccessWriteEntry<K, V> extends WeakEntry<K, V> {
        volatile long accessTime = Long.MAX_VALUE;
        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> nextAccess = LocalCache.nullEntry();
        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> nextWrite = LocalCache.nullEntry();
        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> previousAccess = LocalCache.nullEntry();
        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> previousWrite = LocalCache.nullEntry();
        volatile long writeTime = Long.MAX_VALUE;

        WeakAccessWriteEntry(ReferenceQueue<K> referenceQueue, K k, int i, @Nullable ReferenceEntry<K, V> referenceEntry) {
            super(referenceQueue, k, i, referenceEntry);
        }

        public long getAccessTime() {
            return this.accessTime;
        }

        public void setAccessTime(long j) {
            this.accessTime = j;
        }

        public ReferenceEntry<K, V> getNextInAccessQueue() {
            return this.nextAccess;
        }

        public void setNextInAccessQueue(ReferenceEntry<K, V> referenceEntry) {
            this.nextAccess = referenceEntry;
        }

        public ReferenceEntry<K, V> getPreviousInAccessQueue() {
            return this.previousAccess;
        }

        public void setPreviousInAccessQueue(ReferenceEntry<K, V> referenceEntry) {
            this.previousAccess = referenceEntry;
        }

        public long getWriteTime() {
            return this.writeTime;
        }

        public void setWriteTime(long j) {
            this.writeTime = j;
        }

        public ReferenceEntry<K, V> getNextInWriteQueue() {
            return this.nextWrite;
        }

        public void setNextInWriteQueue(ReferenceEntry<K, V> referenceEntry) {
            this.nextWrite = referenceEntry;
        }

        public ReferenceEntry<K, V> getPreviousInWriteQueue() {
            return this.previousWrite;
        }

        public void setPreviousInWriteQueue(ReferenceEntry<K, V> referenceEntry) {
            this.previousWrite = referenceEntry;
        }
    }

    static class WeakEntry<K, V> extends WeakReference<K> implements ReferenceEntry<K, V> {
        final int hash;
        final ReferenceEntry<K, V> next;
        volatile ValueReference<K, V> valueReference = LocalCache.unset();

        WeakEntry(ReferenceQueue<K> referenceQueue, K k, int i, @Nullable ReferenceEntry<K, V> referenceEntry) {
            super(k, referenceQueue);
            this.hash = i;
            this.next = referenceEntry;
        }

        public K getKey() {
            return get();
        }

        public long getAccessTime() {
            throw new UnsupportedOperationException();
        }

        public void setAccessTime(long j) {
            throw new UnsupportedOperationException();
        }

        public ReferenceEntry<K, V> getNextInAccessQueue() {
            throw new UnsupportedOperationException();
        }

        public void setNextInAccessQueue(ReferenceEntry<K, V> referenceEntry) {
            throw new UnsupportedOperationException();
        }

        public ReferenceEntry<K, V> getPreviousInAccessQueue() {
            throw new UnsupportedOperationException();
        }

        public void setPreviousInAccessQueue(ReferenceEntry<K, V> referenceEntry) {
            throw new UnsupportedOperationException();
        }

        public long getWriteTime() {
            throw new UnsupportedOperationException();
        }

        public void setWriteTime(long j) {
            throw new UnsupportedOperationException();
        }

        public ReferenceEntry<K, V> getNextInWriteQueue() {
            throw new UnsupportedOperationException();
        }

        public void setNextInWriteQueue(ReferenceEntry<K, V> referenceEntry) {
            throw new UnsupportedOperationException();
        }

        public ReferenceEntry<K, V> getPreviousInWriteQueue() {
            throw new UnsupportedOperationException();
        }

        public void setPreviousInWriteQueue(ReferenceEntry<K, V> referenceEntry) {
            throw new UnsupportedOperationException();
        }

        public ValueReference<K, V> getValueReference() {
            return this.valueReference;
        }

        public void setValueReference(ValueReference<K, V> valueReference2) {
            this.valueReference = valueReference2;
        }

        public int getHash() {
            return this.hash;
        }

        public ReferenceEntry<K, V> getNext() {
            return this.next;
        }
    }

    static class WeakValueReference<K, V> extends WeakReference<V> implements ValueReference<K, V> {
        final ReferenceEntry<K, V> entry;

        public int getWeight() {
            return 1;
        }

        public boolean isActive() {
            return true;
        }

        public boolean isLoading() {
            return false;
        }

        public void notifyNewValue(V v) {
        }

        WeakValueReference(ReferenceQueue<V> referenceQueue, V v, ReferenceEntry<K, V> referenceEntry) {
            super(v, referenceQueue);
            this.entry = referenceEntry;
        }

        public ReferenceEntry<K, V> getEntry() {
            return this.entry;
        }

        public ValueReference<K, V> copyFor(ReferenceQueue<V> referenceQueue, V v, ReferenceEntry<K, V> referenceEntry) {
            return new WeakValueReference(referenceQueue, v, referenceEntry);
        }

        public V waitForValue() {
            return get();
        }
    }

    static final class WeakWriteEntry<K, V> extends WeakEntry<K, V> {
        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> nextWrite = LocalCache.nullEntry();
        @GuardedBy("Segment.this")
        ReferenceEntry<K, V> previousWrite = LocalCache.nullEntry();
        volatile long writeTime = Long.MAX_VALUE;

        WeakWriteEntry(ReferenceQueue<K> referenceQueue, K k, int i, @Nullable ReferenceEntry<K, V> referenceEntry) {
            super(referenceQueue, k, i, referenceEntry);
        }

        public long getWriteTime() {
            return this.writeTime;
        }

        public void setWriteTime(long j) {
            this.writeTime = j;
        }

        public ReferenceEntry<K, V> getNextInWriteQueue() {
            return this.nextWrite;
        }

        public void setNextInWriteQueue(ReferenceEntry<K, V> referenceEntry) {
            this.nextWrite = referenceEntry;
        }

        public ReferenceEntry<K, V> getPreviousInWriteQueue() {
            return this.previousWrite;
        }

        public void setPreviousInWriteQueue(ReferenceEntry<K, V> referenceEntry) {
            this.previousWrite = referenceEntry;
        }
    }

    static final class WeightedSoftValueReference<K, V> extends SoftValueReference<K, V> {
        final int weight;

        WeightedSoftValueReference(ReferenceQueue<V> referenceQueue, V v, ReferenceEntry<K, V> referenceEntry, int i) {
            super(referenceQueue, v, referenceEntry);
            this.weight = i;
        }

        public int getWeight() {
            return this.weight;
        }

        public ValueReference<K, V> copyFor(ReferenceQueue<V> referenceQueue, V v, ReferenceEntry<K, V> referenceEntry) {
            return new WeightedSoftValueReference(referenceQueue, v, referenceEntry, this.weight);
        }
    }

    static final class WeightedStrongValueReference<K, V> extends StrongValueReference<K, V> {
        final int weight;

        WeightedStrongValueReference(V v, int i) {
            super(v);
            this.weight = i;
        }

        public int getWeight() {
            return this.weight;
        }
    }

    static final class WeightedWeakValueReference<K, V> extends WeakValueReference<K, V> {
        final int weight;

        WeightedWeakValueReference(ReferenceQueue<V> referenceQueue, V v, ReferenceEntry<K, V> referenceEntry, int i) {
            super(referenceQueue, v, referenceEntry);
            this.weight = i;
        }

        public int getWeight() {
            return this.weight;
        }

        public ValueReference<K, V> copyFor(ReferenceQueue<V> referenceQueue, V v, ReferenceEntry<K, V> referenceEntry) {
            return new WeightedWeakValueReference(referenceQueue, v, referenceEntry, this.weight);
        }
    }

    static final class WriteQueue<K, V> extends AbstractQueue<ReferenceEntry<K, V>> {
        final ReferenceEntry<K, V> head = new AbstractReferenceEntry<K, V>() {
            ReferenceEntry<K, V> nextWrite = this;
            ReferenceEntry<K, V> previousWrite = this;

            public long getWriteTime() {
                return Long.MAX_VALUE;
            }

            public void setWriteTime(long j) {
            }

            public ReferenceEntry<K, V> getNextInWriteQueue() {
                return this.nextWrite;
            }

            public void setNextInWriteQueue(ReferenceEntry<K, V> referenceEntry) {
                this.nextWrite = referenceEntry;
            }

            public ReferenceEntry<K, V> getPreviousInWriteQueue() {
                return this.previousWrite;
            }

            public void setPreviousInWriteQueue(ReferenceEntry<K, V> referenceEntry) {
                this.previousWrite = referenceEntry;
            }
        };

        WriteQueue() {
        }

        public boolean offer(ReferenceEntry<K, V> referenceEntry) {
            LocalCache.connectWriteOrder(referenceEntry.getPreviousInWriteQueue(), referenceEntry.getNextInWriteQueue());
            LocalCache.connectWriteOrder(this.head.getPreviousInWriteQueue(), referenceEntry);
            LocalCache.connectWriteOrder(referenceEntry, this.head);
            return true;
        }

        public ReferenceEntry<K, V> peek() {
            ReferenceEntry<K, V> nextInWriteQueue = this.head.getNextInWriteQueue();
            if (nextInWriteQueue == this.head) {
                return null;
            }
            return nextInWriteQueue;
        }

        public ReferenceEntry<K, V> poll() {
            ReferenceEntry<K, V> nextInWriteQueue = this.head.getNextInWriteQueue();
            if (nextInWriteQueue == this.head) {
                return null;
            }
            remove(nextInWriteQueue);
            return nextInWriteQueue;
        }

        public boolean remove(Object obj) {
            ReferenceEntry referenceEntry = (ReferenceEntry) obj;
            ReferenceEntry previousInWriteQueue = referenceEntry.getPreviousInWriteQueue();
            ReferenceEntry nextInWriteQueue = referenceEntry.getNextInWriteQueue();
            LocalCache.connectWriteOrder(previousInWriteQueue, nextInWriteQueue);
            LocalCache.nullifyWriteOrder(referenceEntry);
            return nextInWriteQueue != NullEntry.INSTANCE;
        }

        public boolean contains(Object obj) {
            return ((ReferenceEntry) obj).getNextInWriteQueue() != NullEntry.INSTANCE;
        }

        public boolean isEmpty() {
            return this.head.getNextInWriteQueue() == this.head;
        }

        public int size() {
            int i = 0;
            for (ReferenceEntry<K, V> nextInWriteQueue = this.head.getNextInWriteQueue(); nextInWriteQueue != this.head; nextInWriteQueue = nextInWriteQueue.getNextInWriteQueue()) {
                i++;
            }
            return i;
        }

        public void clear() {
            ReferenceEntry<K, V> nextInWriteQueue = this.head.getNextInWriteQueue();
            while (true) {
                ReferenceEntry<K, V> referenceEntry = this.head;
                if (nextInWriteQueue != referenceEntry) {
                    ReferenceEntry nextInWriteQueue2 = nextInWriteQueue.getNextInWriteQueue();
                    LocalCache.nullifyWriteOrder(nextInWriteQueue);
                    nextInWriteQueue = nextInWriteQueue2;
                } else {
                    referenceEntry.setNextInWriteQueue(referenceEntry);
                    ReferenceEntry<K, V> referenceEntry2 = this.head;
                    referenceEntry2.setPreviousInWriteQueue(referenceEntry2);
                    return;
                }
            }
        }

        public Iterator<ReferenceEntry<K, V>> iterator() {
            return new AbstractSequentialIterator<ReferenceEntry<K, V>>(peek()) {
                /* access modifiers changed from: protected */
                public ReferenceEntry<K, V> computeNext(ReferenceEntry<K, V> referenceEntry) {
                    ReferenceEntry<K, V> nextInWriteQueue = referenceEntry.getNextInWriteQueue();
                    if (nextInWriteQueue == WriteQueue.this.head) {
                        return null;
                    }
                    return nextInWriteQueue;
                }
            };
        }
    }

    final class WriteThroughEntry implements Entry<K, V> {
        final K key;
        V value;

        WriteThroughEntry(K k, V v) {
            this.key = k;
            this.value = v;
        }

        public K getKey() {
            return this.key;
        }

        public V getValue() {
            return this.value;
        }

        public boolean equals(@Nullable Object obj) {
            boolean z = false;
            if (!(obj instanceof Entry)) {
                return false;
            }
            Entry entry = (Entry) obj;
            if (this.key.equals(entry.getKey()) && this.value.equals(entry.getValue())) {
                z = true;
            }
            return z;
        }

        public int hashCode() {
            return this.key.hashCode() ^ this.value.hashCode();
        }

        public V setValue(V v) {
            throw new UnsupportedOperationException();
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(getKey());
            sb.append("=");
            sb.append(getValue());
            return sb.toString();
        }
    }

    static int rehash(int i) {
        int i2 = i + ((i << 15) ^ -12931);
        int i3 = i2 ^ (i2 >>> 10);
        int i4 = i3 + (i3 << 3);
        int i5 = i4 ^ (i4 >>> 6);
        int i6 = i5 + (i5 << 2) + (i5 << 14);
        return i6 ^ (i6 >>> 16);
    }

    LocalCache(CacheBuilder<? super K, ? super V> cacheBuilder, @Nullable CacheLoader<? super K, V> cacheLoader) {
        this.concurrencyLevel = Math.min(cacheBuilder.getConcurrencyLevel(), 65536);
        this.keyStrength = cacheBuilder.getKeyStrength();
        this.valueStrength = cacheBuilder.getValueStrength();
        this.keyEquivalence = cacheBuilder.getKeyEquivalence();
        this.valueEquivalence = cacheBuilder.getValueEquivalence();
        this.maxWeight = cacheBuilder.getMaximumWeight();
        this.weigher = cacheBuilder.getWeigher();
        this.expireAfterAccessNanos = cacheBuilder.getExpireAfterAccessNanos();
        this.expireAfterWriteNanos = cacheBuilder.getExpireAfterWriteNanos();
        this.refreshNanos = cacheBuilder.getRefreshNanos();
        this.removalListener = cacheBuilder.getRemovalListener();
        this.removalNotificationQueue = this.removalListener == NullListener.INSTANCE ? discardingQueue() : new ConcurrentLinkedQueue<>();
        this.ticker = cacheBuilder.getTicker(recordsTime());
        this.entryFactory = EntryFactory.getFactory(this.keyStrength, usesAccessEntries(), usesWriteEntries());
        this.globalStatsCounter = (StatsCounter) cacheBuilder.getStatsCounterSupplier().get();
        this.defaultLoader = cacheLoader;
        int min = Math.min(cacheBuilder.getInitialCapacity(), 1073741824);
        if (evictsBySize() && !customWeigher()) {
            min = Math.min(min, (int) this.maxWeight);
        }
        int i = 0;
        int i2 = 1;
        int i3 = 1;
        int i4 = 0;
        while (i3 < this.concurrencyLevel && (!evictsBySize() || ((long) (i3 * 20)) <= this.maxWeight)) {
            i4++;
            i3 <<= 1;
        }
        this.segmentShift = 32 - i4;
        this.segmentMask = i3 - 1;
        this.segments = newSegmentArray(i3);
        int i5 = min / i3;
        if (i5 * i3 < min) {
            i5++;
        }
        while (i2 < i5) {
            i2 <<= 1;
        }
        if (evictsBySize()) {
            long j = this.maxWeight;
            long j2 = (long) i3;
            long j3 = (j / j2) + 1;
            long j4 = j % j2;
            while (i < this.segments.length) {
                if (((long) i) == j4) {
                    j3--;
                }
                this.segments[i] = createSegment(i2, j3, (StatsCounter) cacheBuilder.getStatsCounterSupplier().get());
                i++;
            }
            return;
        }
        while (true) {
            Segment<K, V>[] segmentArr = this.segments;
            if (i < segmentArr.length) {
                segmentArr[i] = createSegment(i2, -1, (StatsCounter) cacheBuilder.getStatsCounterSupplier().get());
                i++;
            } else {
                return;
            }
        }
    }

    /* access modifiers changed from: 0000 */
    public boolean evictsBySize() {
        return this.maxWeight >= 0;
    }

    /* access modifiers changed from: 0000 */
    public boolean customWeigher() {
        return this.weigher != OneWeigher.INSTANCE;
    }

    /* access modifiers changed from: 0000 */
    public boolean expires() {
        return expiresAfterWrite() || expiresAfterAccess();
    }

    /* access modifiers changed from: 0000 */
    public boolean expiresAfterWrite() {
        return this.expireAfterWriteNanos > 0;
    }

    /* access modifiers changed from: 0000 */
    public boolean expiresAfterAccess() {
        return this.expireAfterAccessNanos > 0;
    }

    /* access modifiers changed from: 0000 */
    public boolean refreshes() {
        return this.refreshNanos > 0;
    }

    /* access modifiers changed from: 0000 */
    public boolean usesAccessQueue() {
        return expiresAfterAccess() || evictsBySize();
    }

    /* access modifiers changed from: 0000 */
    public boolean usesWriteQueue() {
        return expiresAfterWrite();
    }

    /* access modifiers changed from: 0000 */
    public boolean recordsWrite() {
        return expiresAfterWrite() || refreshes();
    }

    /* access modifiers changed from: 0000 */
    public boolean recordsAccess() {
        return expiresAfterAccess();
    }

    /* access modifiers changed from: 0000 */
    public boolean recordsTime() {
        return recordsWrite() || recordsAccess();
    }

    /* access modifiers changed from: 0000 */
    public boolean usesWriteEntries() {
        return usesWriteQueue() || recordsWrite();
    }

    /* access modifiers changed from: 0000 */
    public boolean usesAccessEntries() {
        return usesAccessQueue() || recordsAccess();
    }

    /* access modifiers changed from: 0000 */
    public boolean usesKeyReferences() {
        return this.keyStrength != Strength.STRONG;
    }

    /* access modifiers changed from: 0000 */
    public boolean usesValueReferences() {
        return this.valueStrength != Strength.STRONG;
    }

    static <K, V> ValueReference<K, V> unset() {
        return UNSET;
    }

    static <K, V> ReferenceEntry<K, V> nullEntry() {
        return NullEntry.INSTANCE;
    }

    static <E> Queue<E> discardingQueue() {
        return DISCARDING_QUEUE;
    }

    /* access modifiers changed from: 0000 */
    @GuardedBy("Segment.this")
    @VisibleForTesting
    public ReferenceEntry<K, V> newEntry(K k, int i, @Nullable ReferenceEntry<K, V> referenceEntry) {
        return segmentFor(i).newEntry(k, i, referenceEntry);
    }

    /* access modifiers changed from: 0000 */
    @GuardedBy("Segment.this")
    @VisibleForTesting
    public ReferenceEntry<K, V> copyEntry(ReferenceEntry<K, V> referenceEntry, ReferenceEntry<K, V> referenceEntry2) {
        return segmentFor(referenceEntry.getHash()).copyEntry(referenceEntry, referenceEntry2);
    }

    /* access modifiers changed from: 0000 */
    @GuardedBy("Segment.this")
    @VisibleForTesting
    public ValueReference<K, V> newValueReference(ReferenceEntry<K, V> referenceEntry, V v, int i) {
        return this.valueStrength.referenceValue(segmentFor(referenceEntry.getHash()), referenceEntry, Preconditions.checkNotNull(v), i);
    }

    /* access modifiers changed from: 0000 */
    public int hash(@Nullable Object obj) {
        return rehash(this.keyEquivalence.hash(obj));
    }

    /* access modifiers changed from: 0000 */
    public void reclaimValue(ValueReference<K, V> valueReference) {
        ReferenceEntry entry = valueReference.getEntry();
        int hash = entry.getHash();
        segmentFor(hash).reclaimValue(entry.getKey(), hash, valueReference);
    }

    /* access modifiers changed from: 0000 */
    public void reclaimKey(ReferenceEntry<K, V> referenceEntry) {
        int hash = referenceEntry.getHash();
        segmentFor(hash).reclaimKey(referenceEntry, hash);
    }

    /* access modifiers changed from: 0000 */
    @VisibleForTesting
    public boolean isLive(ReferenceEntry<K, V> referenceEntry, long j) {
        return segmentFor(referenceEntry.getHash()).getLiveValue(referenceEntry, j) != null;
    }

    /* access modifiers changed from: 0000 */
    public Segment<K, V> segmentFor(int i) {
        return this.segments[(i >>> this.segmentShift) & this.segmentMask];
    }

    /* access modifiers changed from: 0000 */
    public Segment<K, V> createSegment(int i, long j, StatsCounter statsCounter) {
        Segment segment = new Segment(this, i, j, statsCounter);
        return segment;
    }

    /* access modifiers changed from: 0000 */
    @Nullable
    public V getLiveValue(ReferenceEntry<K, V> referenceEntry, long j) {
        if (referenceEntry.getKey() == null) {
            return null;
        }
        V v = referenceEntry.getValueReference().get();
        if (v != null && !isExpired(referenceEntry, j)) {
            return v;
        }
        return null;
    }

    /* access modifiers changed from: 0000 */
    public boolean isExpired(ReferenceEntry<K, V> referenceEntry, long j) {
        Preconditions.checkNotNull(referenceEntry);
        if (expiresAfterAccess() && j - referenceEntry.getAccessTime() >= this.expireAfterAccessNanos) {
            return true;
        }
        if (!expiresAfterWrite() || j - referenceEntry.getWriteTime() < this.expireAfterWriteNanos) {
            return false;
        }
        return true;
    }

    @GuardedBy("Segment.this")
    static <K, V> void connectAccessOrder(ReferenceEntry<K, V> referenceEntry, ReferenceEntry<K, V> referenceEntry2) {
        referenceEntry.setNextInAccessQueue(referenceEntry2);
        referenceEntry2.setPreviousInAccessQueue(referenceEntry);
    }

    @GuardedBy("Segment.this")
    static <K, V> void nullifyAccessOrder(ReferenceEntry<K, V> referenceEntry) {
        ReferenceEntry nullEntry = nullEntry();
        referenceEntry.setNextInAccessQueue(nullEntry);
        referenceEntry.setPreviousInAccessQueue(nullEntry);
    }

    @GuardedBy("Segment.this")
    static <K, V> void connectWriteOrder(ReferenceEntry<K, V> referenceEntry, ReferenceEntry<K, V> referenceEntry2) {
        referenceEntry.setNextInWriteQueue(referenceEntry2);
        referenceEntry2.setPreviousInWriteQueue(referenceEntry);
    }

    @GuardedBy("Segment.this")
    static <K, V> void nullifyWriteOrder(ReferenceEntry<K, V> referenceEntry) {
        ReferenceEntry nullEntry = nullEntry();
        referenceEntry.setNextInWriteQueue(nullEntry);
        referenceEntry.setPreviousInWriteQueue(nullEntry);
    }

    /* access modifiers changed from: 0000 */
    public void processPendingNotifications() {
        while (true) {
            RemovalNotification removalNotification = (RemovalNotification) this.removalNotificationQueue.poll();
            if (removalNotification != null) {
                try {
                    this.removalListener.onRemoval(removalNotification);
                } catch (Throwable th) {
                    logger.log(Level.WARNING, "Exception thrown by removal listener", th);
                }
            } else {
                return;
            }
        }
    }

    /* access modifiers changed from: 0000 */
    public final Segment<K, V>[] newSegmentArray(int i) {
        return new Segment[i];
    }

    public void cleanUp() {
        for (Segment<K, V> cleanUp : this.segments) {
            cleanUp.cleanUp();
        }
    }

    public boolean isEmpty() {
        Segment<K, V>[] segmentArr = this.segments;
        long j = 0;
        for (int i = 0; i < segmentArr.length; i++) {
            if (segmentArr[i].count != 0) {
                return false;
            }
            j += (long) segmentArr[i].modCount;
        }
        if (j != 0) {
            for (int i2 = 0; i2 < segmentArr.length; i2++) {
                if (segmentArr[i2].count != 0) {
                    return false;
                }
                j -= (long) segmentArr[i2].modCount;
            }
            if (j != 0) {
                return false;
            }
        }
        return true;
    }

    /* access modifiers changed from: 0000 */
    public long longSize() {
        long j = 0;
        for (Segment<K, V> segment : this.segments) {
            j += (long) segment.count;
        }
        return j;
    }

    public int size() {
        return Ints.saturatedCast(longSize());
    }

    @Nullable
    public V get(@Nullable Object obj) {
        if (obj == null) {
            return null;
        }
        int hash = hash(obj);
        return segmentFor(hash).get(obj, hash);
    }

    @Nullable
    public V getIfPresent(Object obj) {
        int hash = hash(Preconditions.checkNotNull(obj));
        V v = segmentFor(hash).get(obj, hash);
        if (v == null) {
            this.globalStatsCounter.recordMisses(1);
        } else {
            this.globalStatsCounter.recordHits(1);
        }
        return v;
    }

    /* access modifiers changed from: 0000 */
    public V get(K k, CacheLoader<? super K, V> cacheLoader) throws ExecutionException {
        int hash = hash(Preconditions.checkNotNull(k));
        return segmentFor(hash).get(k, hash, cacheLoader);
    }

    /* access modifiers changed from: 0000 */
    public V getOrLoad(K k) throws ExecutionException {
        return get(k, this.defaultLoader);
    }

    /* access modifiers changed from: 0000 */
    public ImmutableMap<K, V> getAllPresent(Iterable<?> iterable) {
        LinkedHashMap newLinkedHashMap = Maps.newLinkedHashMap();
        int i = 0;
        int i2 = 0;
        for (Object next : iterable) {
            Object obj = get(next);
            if (obj == null) {
                i2++;
            } else {
                newLinkedHashMap.put(next, obj);
                i++;
            }
        }
        this.globalStatsCounter.recordHits(i);
        this.globalStatsCounter.recordMisses(i2);
        return ImmutableMap.copyOf(newLinkedHashMap);
    }

    /* access modifiers changed from: 0000 */
    /* JADX WARNING: Can't wrap try/catch for region: R(4:21|22|(2:25|23)|38) */
    /* JADX WARNING: Code restructure failed: missing block: B:22:?, code lost:
        r8 = r1.iterator();
     */
    /* JADX WARNING: Code restructure failed: missing block: B:24:0x0073, code lost:
        if (r8.hasNext() != false) goto L_0x0075;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:25:0x0075, code lost:
        r1 = r8.next();
        r3 = r3 - 1;
        r0.put(r1, get(r1, r7.defaultLoader));
     */
    /* JADX WARNING: Missing exception handler attribute for start block: B:21:0x006b */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    public com.google.common.collect.ImmutableMap<K, V> getAll(java.lang.Iterable<? extends K> r8) throws java.util.concurrent.ExecutionException {
        /*
            r7 = this;
            java.util.LinkedHashMap r0 = com.google.common.collect.Maps.newLinkedHashMap()
            java.util.LinkedHashSet r1 = com.google.common.collect.Sets.newLinkedHashSet()
            java.util.Iterator r8 = r8.iterator()
            r2 = 0
            r3 = 0
        L_0x000e:
            boolean r4 = r8.hasNext()
            if (r4 == 0) goto L_0x0030
            java.lang.Object r4 = r8.next()
            java.lang.Object r5 = r7.get(r4)
            boolean r6 = r0.containsKey(r4)
            if (r6 != 0) goto L_0x000e
            r0.put(r4, r5)
            if (r5 != 0) goto L_0x002d
            int r3 = r3 + 1
            r1.add(r4)
            goto L_0x000e
        L_0x002d:
            int r2 = r2 + 1
            goto L_0x000e
        L_0x0030:
            boolean r8 = r1.isEmpty()     // Catch:{ all -> 0x0094 }
            if (r8 != 0) goto L_0x0085
            com.google.common.cache.CacheLoader<? super K, V> r8 = r7.defaultLoader     // Catch:{ UnsupportedLoadingOperationException -> 0x006b }
            java.util.Map r8 = r7.loadAll(r1, r8)     // Catch:{ UnsupportedLoadingOperationException -> 0x006b }
            java.util.Iterator r4 = r1.iterator()     // Catch:{ UnsupportedLoadingOperationException -> 0x006b }
        L_0x0040:
            boolean r5 = r4.hasNext()     // Catch:{ UnsupportedLoadingOperationException -> 0x006b }
            if (r5 == 0) goto L_0x0085
            java.lang.Object r5 = r4.next()     // Catch:{ UnsupportedLoadingOperationException -> 0x006b }
            java.lang.Object r6 = r8.get(r5)     // Catch:{ UnsupportedLoadingOperationException -> 0x006b }
            if (r6 == 0) goto L_0x0054
            r0.put(r5, r6)     // Catch:{ UnsupportedLoadingOperationException -> 0x006b }
            goto L_0x0040
        L_0x0054:
            com.google.common.cache.CacheLoader$InvalidCacheLoadException r8 = new com.google.common.cache.CacheLoader$InvalidCacheLoadException     // Catch:{ UnsupportedLoadingOperationException -> 0x006b }
            java.lang.StringBuilder r4 = new java.lang.StringBuilder     // Catch:{ UnsupportedLoadingOperationException -> 0x006b }
            r4.<init>()     // Catch:{ UnsupportedLoadingOperationException -> 0x006b }
            java.lang.String r6 = "loadAll failed to return a value for "
            r4.append(r6)     // Catch:{ UnsupportedLoadingOperationException -> 0x006b }
            r4.append(r5)     // Catch:{ UnsupportedLoadingOperationException -> 0x006b }
            java.lang.String r4 = r4.toString()     // Catch:{ UnsupportedLoadingOperationException -> 0x006b }
            r8.<init>(r4)     // Catch:{ UnsupportedLoadingOperationException -> 0x006b }
            throw r8     // Catch:{ UnsupportedLoadingOperationException -> 0x006b }
        L_0x006b:
            java.util.Iterator r8 = r1.iterator()     // Catch:{ all -> 0x0094 }
        L_0x006f:
            boolean r1 = r8.hasNext()     // Catch:{ all -> 0x0094 }
            if (r1 == 0) goto L_0x0085
            java.lang.Object r1 = r8.next()     // Catch:{ all -> 0x0094 }
            int r3 = r3 + -1
            com.google.common.cache.CacheLoader<? super K, V> r4 = r7.defaultLoader     // Catch:{ all -> 0x0094 }
            java.lang.Object r4 = r7.get(r1, r4)     // Catch:{ all -> 0x0094 }
            r0.put(r1, r4)     // Catch:{ all -> 0x0094 }
            goto L_0x006f
        L_0x0085:
            com.google.common.collect.ImmutableMap r8 = com.google.common.collect.ImmutableMap.copyOf(r0)     // Catch:{ all -> 0x0094 }
            com.google.common.cache.AbstractCache$StatsCounter r0 = r7.globalStatsCounter
            r0.recordHits(r2)
            com.google.common.cache.AbstractCache$StatsCounter r0 = r7.globalStatsCounter
            r0.recordMisses(r3)
            return r8
        L_0x0094:
            r8 = move-exception
            com.google.common.cache.AbstractCache$StatsCounter r0 = r7.globalStatsCounter
            r0.recordHits(r2)
            com.google.common.cache.AbstractCache$StatsCounter r0 = r7.globalStatsCounter
            r0.recordMisses(r3)
            throw r8
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.google.common.cache.LocalCache.getAll(java.lang.Iterable):com.google.common.collect.ImmutableMap");
    }

    /* access modifiers changed from: 0000 */
    @Nullable
    public Map<K, V> loadAll(Set<? extends K> set, CacheLoader<? super K, V> cacheLoader) throws ExecutionException {
        Preconditions.checkNotNull(cacheLoader);
        Preconditions.checkNotNull(set);
        Stopwatch createStarted = Stopwatch.createStarted();
        boolean z = false;
        try {
            Map<K, V> loadAll = cacheLoader.loadAll(set);
            if (loadAll != null) {
                createStarted.stop();
                for (Entry entry : loadAll.entrySet()) {
                    Object key = entry.getKey();
                    Object value = entry.getValue();
                    if (key == null || value == null) {
                        z = true;
                    } else {
                        put(key, value);
                    }
                }
                if (!z) {
                    this.globalStatsCounter.recordLoadSuccess(createStarted.elapsed(TimeUnit.NANOSECONDS));
                    return loadAll;
                }
                this.globalStatsCounter.recordLoadException(createStarted.elapsed(TimeUnit.NANOSECONDS));
                StringBuilder sb = new StringBuilder();
                sb.append(cacheLoader);
                sb.append(" returned null keys or values from loadAll");
                throw new InvalidCacheLoadException(sb.toString());
            }
            this.globalStatsCounter.recordLoadException(createStarted.elapsed(TimeUnit.NANOSECONDS));
            StringBuilder sb2 = new StringBuilder();
            sb2.append(cacheLoader);
            sb2.append(" returned null map from loadAll");
            throw new InvalidCacheLoadException(sb2.toString());
        } catch (UnsupportedLoadingOperationException e) {
            throw e;
        } catch (InterruptedException e2) {
            Thread.currentThread().interrupt();
            throw new ExecutionException(e2);
        } catch (RuntimeException e3) {
            throw new UncheckedExecutionException((Throwable) e3);
        } catch (Exception e4) {
            throw new ExecutionException(e4);
        } catch (Error e5) {
            throw new ExecutionError(e5);
        } catch (Throwable th) {
            th = th;
            z = true;
        }
        if (!z) {
            this.globalStatsCounter.recordLoadException(createStarted.elapsed(TimeUnit.NANOSECONDS));
        }
        throw th;
    }

    /* access modifiers changed from: 0000 */
    public ReferenceEntry<K, V> getEntry(@Nullable Object obj) {
        if (obj == null) {
            return null;
        }
        int hash = hash(obj);
        return segmentFor(hash).getEntry(obj, hash);
    }

    /* access modifiers changed from: 0000 */
    public void refresh(K k) {
        int hash = hash(Preconditions.checkNotNull(k));
        segmentFor(hash).refresh(k, hash, this.defaultLoader, false);
    }

    public boolean containsKey(@Nullable Object obj) {
        if (obj == null) {
            return false;
        }
        int hash = hash(obj);
        return segmentFor(hash).containsKey(obj, hash);
    }

    public boolean containsValue(@Nullable Object obj) {
        boolean z;
        long j;
        Object obj2 = obj;
        if (obj2 == null) {
            return false;
        }
        long read = this.ticker.read();
        Segment<K, V>[] segmentArr = this.segments;
        long j2 = -1;
        int i = 0;
        while (true) {
            if (i >= 3) {
                z = false;
                break;
            }
            int length = segmentArr.length;
            long j3 = 0;
            int i2 = 0;
            while (i2 < length) {
                Segment<K, V> segment = segmentArr[i2];
                int i3 = segment.count;
                AtomicReferenceArray<ReferenceEntry<K, V>> atomicReferenceArray = segment.table;
                for (int i4 = 0; i4 < atomicReferenceArray.length(); i4++) {
                    ReferenceEntry referenceEntry = (ReferenceEntry) atomicReferenceArray.get(i4);
                    while (referenceEntry != null) {
                        Segment<K, V>[] segmentArr2 = segmentArr;
                        Object liveValue = segment.getLiveValue(referenceEntry, read);
                        if (liveValue != null) {
                            j = read;
                            if (this.valueEquivalence.equivalent(obj2, liveValue)) {
                                return true;
                            }
                        } else {
                            j = read;
                        }
                        referenceEntry = referenceEntry.getNext();
                        segmentArr = segmentArr2;
                        read = j;
                    }
                    long j4 = read;
                    Segment<K, V>[] segmentArr3 = segmentArr;
                }
                Segment<K, V>[] segmentArr4 = segmentArr;
                j3 += (long) segment.modCount;
                i2++;
                read = read;
            }
            long j5 = read;
            Segment<K, V>[] segmentArr5 = segmentArr;
            if (j3 == j2) {
                z = false;
                break;
            }
            i++;
            j2 = j3;
            segmentArr = segmentArr5;
            read = j5;
        }
        return z;
    }

    public V put(K k, V v) {
        Preconditions.checkNotNull(k);
        Preconditions.checkNotNull(v);
        int hash = hash(k);
        return segmentFor(hash).put(k, hash, v, false);
    }

    public V putIfAbsent(K k, V v) {
        Preconditions.checkNotNull(k);
        Preconditions.checkNotNull(v);
        int hash = hash(k);
        return segmentFor(hash).put(k, hash, v, true);
    }

    public void putAll(Map<? extends K, ? extends V> map) {
        for (Entry entry : map.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    public V remove(@Nullable Object obj) {
        if (obj == null) {
            return null;
        }
        int hash = hash(obj);
        return segmentFor(hash).remove(obj, hash);
    }

    public boolean remove(@Nullable Object obj, @Nullable Object obj2) {
        if (obj == null || obj2 == null) {
            return false;
        }
        int hash = hash(obj);
        return segmentFor(hash).remove(obj, hash, obj2);
    }

    public boolean replace(K k, @Nullable V v, V v2) {
        Preconditions.checkNotNull(k);
        Preconditions.checkNotNull(v2);
        if (v == null) {
            return false;
        }
        int hash = hash(k);
        return segmentFor(hash).replace(k, hash, v, v2);
    }

    public V replace(K k, V v) {
        Preconditions.checkNotNull(k);
        Preconditions.checkNotNull(v);
        int hash = hash(k);
        return segmentFor(hash).replace(k, hash, v);
    }

    public void clear() {
        for (Segment<K, V> clear : this.segments) {
            clear.clear();
        }
    }

    /* access modifiers changed from: 0000 */
    public void invalidateAll(Iterable<?> iterable) {
        for (Object remove : iterable) {
            remove(remove);
        }
    }

    public Set<K> keySet() {
        Set<K> set = this.keySet;
        if (set != null) {
            return set;
        }
        KeySet keySet2 = new KeySet(this);
        this.keySet = keySet2;
        return keySet2;
    }

    public Collection<V> values() {
        Collection<V> collection = this.values;
        if (collection != null) {
            return collection;
        }
        Values values2 = new Values(this);
        this.values = values2;
        return values2;
    }

    @GwtIncompatible("Not supported.")
    public Set<Entry<K, V>> entrySet() {
        Set<Entry<K, V>> set = this.entrySet;
        if (set != null) {
            return set;
        }
        EntrySet entrySet2 = new EntrySet(this);
        this.entrySet = entrySet2;
        return entrySet2;
    }
}