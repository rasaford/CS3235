package p009rx.internal.operators;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import p009rx.Observable;
import p009rx.Observable.Operator;
import p009rx.Producer;
import p009rx.Subscriber;
import p009rx.Subscription;
import p009rx.functions.Action0;
import p009rx.internal.util.atomic.SpscLinkedArrayQueue;
import p009rx.subjects.Subject;
import p009rx.subjects.UnicastSubject;
import p009rx.subscriptions.Subscriptions;

/* renamed from: rx.internal.operators.OperatorWindowWithSize */
public final class OperatorWindowWithSize<T> implements Operator<Observable<T>, T> {
    final int size;
    final int skip;

    /* renamed from: rx.internal.operators.OperatorWindowWithSize$WindowExact */
    static final class WindowExact<T> extends Subscriber<T> implements Action0 {
        final Subscriber<? super Observable<T>> actual;
        final Subscription cancel = Subscriptions.create(this);
        int index;
        final int size;
        Subject<T, T> window;
        final AtomicInteger wip = new AtomicInteger(1);

        public WindowExact(Subscriber<? super Observable<T>> subscriber, int i) {
            this.actual = subscriber;
            this.size = i;
            add(this.cancel);
            request(0);
        }

        public void onNext(T t) {
            int i = this.index;
            Subject subject = this.window;
            if (i == 0) {
                this.wip.getAndIncrement();
                subject = UnicastSubject.create(this.size, this);
                this.window = subject;
                this.actual.onNext(subject);
            }
            int i2 = i + 1;
            subject.onNext(t);
            if (i2 == this.size) {
                this.index = 0;
                this.window = null;
                subject.onCompleted();
                return;
            }
            this.index = i2;
        }

        public void onError(Throwable th) {
            Subject<T, T> subject = this.window;
            if (subject != null) {
                this.window = null;
                subject.onError(th);
            }
            this.actual.onError(th);
        }

        public void onCompleted() {
            Subject<T, T> subject = this.window;
            if (subject != null) {
                this.window = null;
                subject.onCompleted();
            }
            this.actual.onCompleted();
        }

        /* access modifiers changed from: 0000 */
        public Producer createProducer() {
            return new Producer() {
                public void request(long j) {
                    if (j < 0) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("n >= 0 required but it was ");
                        sb.append(j);
                        throw new IllegalArgumentException(sb.toString());
                    } else if (j != 0) {
                        WindowExact.this.request(BackpressureUtils.multiplyCap((long) WindowExact.this.size, j));
                    }
                }
            };
        }

        public void call() {
            if (this.wip.decrementAndGet() == 0) {
                unsubscribe();
            }
        }
    }

    /* renamed from: rx.internal.operators.OperatorWindowWithSize$WindowOverlap */
    static final class WindowOverlap<T> extends Subscriber<T> implements Action0 {
        final Subscriber<? super Observable<T>> actual;
        final Subscription cancel = Subscriptions.create(this);
        volatile boolean done;
        final AtomicInteger drainWip = new AtomicInteger();
        Throwable error;
        int index;
        int produced;
        final Queue<Subject<T, T>> queue;
        final AtomicLong requested = new AtomicLong();
        final int size;
        final int skip;
        final ArrayDeque<Subject<T, T>> windows = new ArrayDeque<>();
        final AtomicInteger wip = new AtomicInteger(1);

        /* renamed from: rx.internal.operators.OperatorWindowWithSize$WindowOverlap$WindowOverlapProducer */
        final class WindowOverlapProducer extends AtomicBoolean implements Producer {
            private static final long serialVersionUID = 4625807964358024108L;

            WindowOverlapProducer() {
            }

            public void request(long j) {
                if (j < 0) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("n >= 0 required but it was ");
                    sb.append(j);
                    throw new IllegalArgumentException(sb.toString());
                } else if (j != 0) {
                    WindowOverlap windowOverlap = WindowOverlap.this;
                    if (get() || !compareAndSet(false, true)) {
                        WindowOverlap.this.request(BackpressureUtils.multiplyCap((long) windowOverlap.skip, j));
                    } else {
                        windowOverlap.request(BackpressureUtils.addCap(BackpressureUtils.multiplyCap((long) windowOverlap.skip, j - 1), (long) windowOverlap.size));
                    }
                    BackpressureUtils.getAndAddRequest(windowOverlap.requested, j);
                    windowOverlap.drain();
                }
            }
        }

        public WindowOverlap(Subscriber<? super Observable<T>> subscriber, int i, int i2) {
            this.actual = subscriber;
            this.size = i;
            this.skip = i2;
            add(this.cancel);
            request(0);
            this.queue = new SpscLinkedArrayQueue((i + (i2 - 1)) / i2);
        }

        public void onNext(T t) {
            int i = this.index;
            ArrayDeque<Subject<T, T>> arrayDeque = this.windows;
            if (i == 0 && !this.actual.isUnsubscribed()) {
                this.wip.getAndIncrement();
                UnicastSubject create = UnicastSubject.create(16, this);
                arrayDeque.offer(create);
                this.queue.offer(create);
                drain();
            }
            Iterator it = this.windows.iterator();
            while (it.hasNext()) {
                ((Subject) it.next()).onNext(t);
            }
            int i2 = this.produced + 1;
            if (i2 == this.size) {
                this.produced = i2 - this.skip;
                Subject subject = (Subject) arrayDeque.poll();
                if (subject != null) {
                    subject.onCompleted();
                }
            } else {
                this.produced = i2;
            }
            int i3 = i + 1;
            if (i3 == this.skip) {
                this.index = 0;
            } else {
                this.index = i3;
            }
        }

        public void onError(Throwable th) {
            Iterator it = this.windows.iterator();
            while (it.hasNext()) {
                ((Subject) it.next()).onError(th);
            }
            this.windows.clear();
            this.error = th;
            this.done = true;
            drain();
        }

        public void onCompleted() {
            Iterator it = this.windows.iterator();
            while (it.hasNext()) {
                ((Subject) it.next()).onCompleted();
            }
            this.windows.clear();
            this.done = true;
            drain();
        }

        /* access modifiers changed from: 0000 */
        public Producer createProducer() {
            return new WindowOverlapProducer();
        }

        public void call() {
            if (this.wip.decrementAndGet() == 0) {
                unsubscribe();
            }
        }

        /* access modifiers changed from: 0000 */
        public void drain() {
            AtomicInteger atomicInteger = this.drainWip;
            if (atomicInteger.getAndIncrement() == 0) {
                Subscriber<? super Observable<T>> subscriber = this.actual;
                Queue<Subject<T, T>> queue2 = this.queue;
                int i = 1;
                do {
                    long j = this.requested.get();
                    long j2 = 0;
                    while (j2 != j) {
                        boolean z = this.done;
                        Subject subject = (Subject) queue2.poll();
                        boolean z2 = subject == null;
                        if (!checkTerminated(z, z2, subscriber, queue2)) {
                            if (z2) {
                                break;
                            }
                            subscriber.onNext(subject);
                            j2++;
                        } else {
                            return;
                        }
                    }
                    if (j2 != j || !checkTerminated(this.done, queue2.isEmpty(), subscriber, queue2)) {
                        if (!(j2 == 0 || j == Long.MAX_VALUE)) {
                            this.requested.addAndGet(-j2);
                        }
                        i = atomicInteger.addAndGet(-i);
                    } else {
                        return;
                    }
                } while (i != 0);
            }
        }

        /* access modifiers changed from: 0000 */
        public boolean checkTerminated(boolean z, boolean z2, Subscriber<? super Subject<T, T>> subscriber, Queue<Subject<T, T>> queue2) {
            if (subscriber.isUnsubscribed()) {
                queue2.clear();
                return true;
            }
            if (z) {
                Throwable th = this.error;
                if (th != null) {
                    queue2.clear();
                    subscriber.onError(th);
                    return true;
                } else if (z2) {
                    subscriber.onCompleted();
                    return true;
                }
            }
            return false;
        }
    }

    /* renamed from: rx.internal.operators.OperatorWindowWithSize$WindowSkip */
    static final class WindowSkip<T> extends Subscriber<T> implements Action0 {
        final Subscriber<? super Observable<T>> actual;
        final Subscription cancel = Subscriptions.create(this);
        int index;
        final int size;
        final int skip;
        Subject<T, T> window;
        final AtomicInteger wip = new AtomicInteger(1);

        /* renamed from: rx.internal.operators.OperatorWindowWithSize$WindowSkip$WindowSkipProducer */
        final class WindowSkipProducer extends AtomicBoolean implements Producer {
            private static final long serialVersionUID = 4625807964358024108L;

            WindowSkipProducer() {
            }

            public void request(long j) {
                if (j < 0) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("n >= 0 required but it was ");
                    sb.append(j);
                    throw new IllegalArgumentException(sb.toString());
                } else if (j != 0) {
                    WindowSkip windowSkip = WindowSkip.this;
                    if (get() || !compareAndSet(false, true)) {
                        windowSkip.request(BackpressureUtils.multiplyCap(j, (long) windowSkip.skip));
                    } else {
                        windowSkip.request(BackpressureUtils.addCap(BackpressureUtils.multiplyCap(j, (long) windowSkip.size), BackpressureUtils.multiplyCap((long) (windowSkip.skip - windowSkip.size), j - 1)));
                    }
                }
            }
        }

        public WindowSkip(Subscriber<? super Observable<T>> subscriber, int i, int i2) {
            this.actual = subscriber;
            this.size = i;
            this.skip = i2;
            add(this.cancel);
            request(0);
        }

        public void onNext(T t) {
            int i = this.index;
            Subject subject = this.window;
            if (i == 0) {
                this.wip.getAndIncrement();
                subject = UnicastSubject.create(this.size, this);
                this.window = subject;
                this.actual.onNext(subject);
            }
            int i2 = i + 1;
            if (subject != null) {
                subject.onNext(t);
            }
            if (i2 == this.size) {
                this.index = i2;
                this.window = null;
                subject.onCompleted();
            } else if (i2 == this.skip) {
                this.index = 0;
            } else {
                this.index = i2;
            }
        }

        public void onError(Throwable th) {
            Subject<T, T> subject = this.window;
            if (subject != null) {
                this.window = null;
                subject.onError(th);
            }
            this.actual.onError(th);
        }

        public void onCompleted() {
            Subject<T, T> subject = this.window;
            if (subject != null) {
                this.window = null;
                subject.onCompleted();
            }
            this.actual.onCompleted();
        }

        /* access modifiers changed from: 0000 */
        public Producer createProducer() {
            return new WindowSkipProducer();
        }

        public void call() {
            if (this.wip.decrementAndGet() == 0) {
                unsubscribe();
            }
        }
    }

    public OperatorWindowWithSize(int i, int i2) {
        this.size = i;
        this.skip = i2;
    }

    public Subscriber<? super T> call(Subscriber<? super Observable<T>> subscriber) {
        int i = this.skip;
        int i2 = this.size;
        if (i == i2) {
            WindowExact windowExact = new WindowExact(subscriber, i2);
            subscriber.add(windowExact.cancel);
            subscriber.setProducer(windowExact.createProducer());
            return windowExact;
        } else if (i > i2) {
            WindowSkip windowSkip = new WindowSkip(subscriber, i2, i);
            subscriber.add(windowSkip.cancel);
            subscriber.setProducer(windowSkip.createProducer());
            return windowSkip;
        } else {
            WindowOverlap windowOverlap = new WindowOverlap(subscriber, i2, i);
            subscriber.add(windowOverlap.cancel);
            subscriber.setProducer(windowOverlap.createProducer());
            return windowOverlap;
        }
    }
}
