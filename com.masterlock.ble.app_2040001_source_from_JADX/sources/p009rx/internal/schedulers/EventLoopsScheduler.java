package p009rx.internal.schedulers;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import p009rx.Scheduler;
import p009rx.Scheduler.Worker;
import p009rx.Subscription;
import p009rx.functions.Action0;
import p009rx.internal.util.RxThreadFactory;
import p009rx.internal.util.SubscriptionList;
import p009rx.subscriptions.CompositeSubscription;
import p009rx.subscriptions.Subscriptions;

/* renamed from: rx.internal.schedulers.EventLoopsScheduler */
public final class EventLoopsScheduler extends Scheduler implements SchedulerLifecycle {
    static final String KEY_MAX_THREADS = "rx.scheduler.max-computation-threads";
    static final int MAX_THREADS;
    static final FixedSchedulerPool NONE = new FixedSchedulerPool(null, 0);
    static final PoolWorker SHUTDOWN_WORKER = new PoolWorker(RxThreadFactory.NONE);
    final AtomicReference<FixedSchedulerPool> pool = new AtomicReference<>(NONE);
    final ThreadFactory threadFactory;

    /* renamed from: rx.internal.schedulers.EventLoopsScheduler$EventLoopWorker */
    static final class EventLoopWorker extends Worker {
        private final SubscriptionList both = new SubscriptionList(this.serial, this.timed);
        private final PoolWorker poolWorker;
        private final SubscriptionList serial = new SubscriptionList();
        private final CompositeSubscription timed = new CompositeSubscription();

        EventLoopWorker(PoolWorker poolWorker2) {
            this.poolWorker = poolWorker2;
        }

        public void unsubscribe() {
            this.both.unsubscribe();
        }

        public boolean isUnsubscribed() {
            return this.both.isUnsubscribed();
        }

        public Subscription schedule(final Action0 action0) {
            if (isUnsubscribed()) {
                return Subscriptions.unsubscribed();
            }
            return this.poolWorker.scheduleActual((Action0) new Action0() {
                public void call() {
                    if (!EventLoopWorker.this.isUnsubscribed()) {
                        action0.call();
                    }
                }
            }, 0, (TimeUnit) null, this.serial);
        }

        public Subscription schedule(final Action0 action0, long j, TimeUnit timeUnit) {
            if (isUnsubscribed()) {
                return Subscriptions.unsubscribed();
            }
            return this.poolWorker.scheduleActual((Action0) new Action0() {
                public void call() {
                    if (!EventLoopWorker.this.isUnsubscribed()) {
                        action0.call();
                    }
                }
            }, j, timeUnit, this.timed);
        }
    }

    /* renamed from: rx.internal.schedulers.EventLoopsScheduler$FixedSchedulerPool */
    static final class FixedSchedulerPool {
        final int cores;
        final PoolWorker[] eventLoops;

        /* renamed from: n */
        long f256n;

        FixedSchedulerPool(ThreadFactory threadFactory, int i) {
            this.cores = i;
            this.eventLoops = new PoolWorker[i];
            for (int i2 = 0; i2 < i; i2++) {
                this.eventLoops[i2] = new PoolWorker(threadFactory);
            }
        }

        public PoolWorker getEventLoop() {
            int i = this.cores;
            if (i == 0) {
                return EventLoopsScheduler.SHUTDOWN_WORKER;
            }
            PoolWorker[] poolWorkerArr = this.eventLoops;
            long j = this.f256n;
            this.f256n = 1 + j;
            return poolWorkerArr[(int) (j % ((long) i))];
        }

        public void shutdown() {
            for (PoolWorker unsubscribe : this.eventLoops) {
                unsubscribe.unsubscribe();
            }
        }
    }

    /* renamed from: rx.internal.schedulers.EventLoopsScheduler$PoolWorker */
    static final class PoolWorker extends NewThreadWorker {
        PoolWorker(ThreadFactory threadFactory) {
            super(threadFactory);
        }
    }

    static {
        int intValue = Integer.getInteger(KEY_MAX_THREADS, 0).intValue();
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        if (intValue <= 0 || intValue > availableProcessors) {
            intValue = availableProcessors;
        }
        MAX_THREADS = intValue;
        SHUTDOWN_WORKER.unsubscribe();
    }

    public EventLoopsScheduler(ThreadFactory threadFactory2) {
        this.threadFactory = threadFactory2;
        start();
    }

    public Worker createWorker() {
        return new EventLoopWorker(((FixedSchedulerPool) this.pool.get()).getEventLoop());
    }

    public void start() {
        FixedSchedulerPool fixedSchedulerPool = new FixedSchedulerPool(this.threadFactory, MAX_THREADS);
        if (!this.pool.compareAndSet(NONE, fixedSchedulerPool)) {
            fixedSchedulerPool.shutdown();
        }
    }

    public void shutdown() {
        FixedSchedulerPool fixedSchedulerPool;
        FixedSchedulerPool fixedSchedulerPool2;
        do {
            fixedSchedulerPool = (FixedSchedulerPool) this.pool.get();
            fixedSchedulerPool2 = NONE;
            if (fixedSchedulerPool == fixedSchedulerPool2) {
                return;
            }
        } while (!this.pool.compareAndSet(fixedSchedulerPool, fixedSchedulerPool2));
        fixedSchedulerPool.shutdown();
    }

    public Subscription scheduleDirect(Action0 action0) {
        return ((FixedSchedulerPool) this.pool.get()).getEventLoop().scheduleActual(action0, -1, TimeUnit.NANOSECONDS);
    }
}
