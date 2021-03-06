package com.google.common.util.concurrent;

import com.google.common.annotations.Beta;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.Service.Listener;
import com.google.common.util.concurrent.Service.State;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import javax.annotation.concurrent.GuardedBy;

@Beta
public abstract class AbstractScheduledService implements Service {
    /* access modifiers changed from: private */
    public static final Logger logger = Logger.getLogger(AbstractScheduledService.class.getName());
    /* access modifiers changed from: private */
    public final AbstractService delegate = new AbstractService() {
        /* access modifiers changed from: private */
        public volatile ScheduledExecutorService executorService;
        /* access modifiers changed from: private */
        public final ReentrantLock lock = new ReentrantLock();
        /* access modifiers changed from: private */
        public volatile Future<?> runningTask;
        /* access modifiers changed from: private */
        public final Runnable task = new Runnable() {
            public void run() {
                C09081.this.lock.lock();
                try {
                    AbstractScheduledService.this.runOneIteration();
                    C09081.this.lock.unlock();
                } catch (Throwable th) {
                    C09081.this.lock.unlock();
                    throw th;
                }
            }
        };

        /* access modifiers changed from: protected */
        public final void doStart() {
            this.executorService = MoreExecutors.renamingDecorator(AbstractScheduledService.this.executor(), (Supplier<String>) new Supplier<String>() {
                public String get() {
                    StringBuilder sb = new StringBuilder();
                    sb.append(AbstractScheduledService.this.serviceName());
                    sb.append(" ");
                    sb.append(C09081.this.state());
                    return sb.toString();
                }
            });
            this.executorService.execute(new Runnable() {
                public void run() {
                    C09081.this.lock.lock();
                    try {
                        AbstractScheduledService.this.startUp();
                        C09081.this.runningTask = AbstractScheduledService.this.scheduler().schedule(AbstractScheduledService.this.delegate, C09081.this.executorService, C09081.this.task);
                        C09081.this.notifyStarted();
                        C09081.this.lock.unlock();
                    } catch (Throwable th) {
                        C09081.this.lock.unlock();
                        throw th;
                    }
                }
            });
        }

        /* access modifiers changed from: protected */
        public final void doStop() {
            this.runningTask.cancel(false);
            this.executorService.execute(new Runnable() {
                public void run() {
                    try {
                        C09081.this.lock.lock();
                        if (C09081.this.state() != State.STOPPING) {
                            C09081.this.lock.unlock();
                            return;
                        }
                        AbstractScheduledService.this.shutDown();
                        C09081.this.lock.unlock();
                        C09081.this.notifyStopped();
                    } catch (Throwable th) {
                        C09081.this.notifyFailed(th);
                        throw Throwables.propagate(th);
                    }
                }
            });
        }
    };

    @Beta
    public static abstract class CustomScheduler extends Scheduler {

        private class ReschedulableCallable extends ForwardingFuture<Void> implements Callable<Void> {
            @GuardedBy("lock")
            private Future<Void> currentFuture;
            private final ScheduledExecutorService executor;
            private final ReentrantLock lock = new ReentrantLock();
            private final AbstractService service;
            private final Runnable wrappedRunnable;

            ReschedulableCallable(AbstractService abstractService, ScheduledExecutorService scheduledExecutorService, Runnable runnable) {
                this.wrappedRunnable = runnable;
                this.executor = scheduledExecutorService;
                this.service = abstractService;
            }

            public Void call() throws Exception {
                this.wrappedRunnable.run();
                reschedule();
                return null;
            }

            public void reschedule() {
                this.lock.lock();
                try {
                    if (this.currentFuture == null || !this.currentFuture.isCancelled()) {
                        Schedule nextSchedule = CustomScheduler.this.getNextSchedule();
                        this.currentFuture = this.executor.schedule(this, nextSchedule.delay, nextSchedule.unit);
                    }
                } catch (Throwable th) {
                    this.lock.unlock();
                    throw th;
                }
                this.lock.unlock();
            }

            public boolean cancel(boolean z) {
                this.lock.lock();
                try {
                    return this.currentFuture.cancel(z);
                } finally {
                    this.lock.unlock();
                }
            }

            /* access modifiers changed from: protected */
            public Future<Void> delegate() {
                throw new UnsupportedOperationException("Only cancel is supported by this future");
            }
        }

        @Beta
        protected static final class Schedule {
            /* access modifiers changed from: private */
            public final long delay;
            /* access modifiers changed from: private */
            public final TimeUnit unit;

            public Schedule(long j, TimeUnit timeUnit) {
                this.delay = j;
                this.unit = (TimeUnit) Preconditions.checkNotNull(timeUnit);
            }
        }

        /* access modifiers changed from: protected */
        public abstract Schedule getNextSchedule() throws Exception;

        public CustomScheduler() {
            super();
        }

        /* access modifiers changed from: 0000 */
        public final Future<?> schedule(AbstractService abstractService, ScheduledExecutorService scheduledExecutorService, Runnable runnable) {
            ReschedulableCallable reschedulableCallable = new ReschedulableCallable(abstractService, scheduledExecutorService, runnable);
            reschedulableCallable.reschedule();
            return reschedulableCallable;
        }
    }

    public static abstract class Scheduler {
        /* access modifiers changed from: 0000 */
        public abstract Future<?> schedule(AbstractService abstractService, ScheduledExecutorService scheduledExecutorService, Runnable runnable);

        public static Scheduler newFixedDelaySchedule(long j, long j2, TimeUnit timeUnit) {
            final long j3 = j;
            final long j4 = j2;
            final TimeUnit timeUnit2 = timeUnit;
            C09151 r0 = new Scheduler() {
                public Future<?> schedule(AbstractService abstractService, ScheduledExecutorService scheduledExecutorService, Runnable runnable) {
                    return scheduledExecutorService.scheduleWithFixedDelay(runnable, j3, j4, timeUnit2);
                }
            };
            return r0;
        }

        public static Scheduler newFixedRateSchedule(long j, long j2, TimeUnit timeUnit) {
            final long j3 = j;
            final long j4 = j2;
            final TimeUnit timeUnit2 = timeUnit;
            C09162 r0 = new Scheduler() {
                public Future<?> schedule(AbstractService abstractService, ScheduledExecutorService scheduledExecutorService, Runnable runnable) {
                    return scheduledExecutorService.scheduleAtFixedRate(runnable, j3, j4, timeUnit2);
                }
            };
            return r0;
        }

        private Scheduler() {
        }
    }

    /* access modifiers changed from: protected */
    public abstract void runOneIteration() throws Exception;

    /* access modifiers changed from: protected */
    public abstract Scheduler scheduler();

    /* access modifiers changed from: protected */
    public void shutDown() throws Exception {
    }

    /* access modifiers changed from: protected */
    public void startUp() throws Exception {
    }

    protected AbstractScheduledService() {
    }

    /* access modifiers changed from: protected */
    public ScheduledExecutorService executor() {
        final ScheduledExecutorService newSingleThreadScheduledExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                return MoreExecutors.newThread(AbstractScheduledService.this.serviceName(), runnable);
            }
        });
        addListener(new Listener() {
            public void terminated(State state) {
                newSingleThreadScheduledExecutor.shutdown();
            }

            public void failed(State state, Throwable th) {
                newSingleThreadScheduledExecutor.shutdown();
            }
        }, MoreExecutors.sameThreadExecutor());
        return newSingleThreadScheduledExecutor;
    }

    /* access modifiers changed from: protected */
    public String serviceName() {
        return getClass().getSimpleName();
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(serviceName());
        sb.append(" [");
        sb.append(state());
        sb.append("]");
        return sb.toString();
    }

    @Deprecated
    public final ListenableFuture<State> start() {
        return this.delegate.start();
    }

    @Deprecated
    public final State startAndWait() {
        return this.delegate.startAndWait();
    }

    public final boolean isRunning() {
        return this.delegate.isRunning();
    }

    public final State state() {
        return this.delegate.state();
    }

    @Deprecated
    public final ListenableFuture<State> stop() {
        return this.delegate.stop();
    }

    @Deprecated
    public final State stopAndWait() {
        return this.delegate.stopAndWait();
    }

    public final void addListener(Listener listener, Executor executor) {
        this.delegate.addListener(listener, executor);
    }

    public final Throwable failureCause() {
        return this.delegate.failureCause();
    }

    public final Service startAsync() {
        this.delegate.startAsync();
        return this;
    }

    public final Service stopAsync() {
        this.delegate.stopAsync();
        return this;
    }

    public final void awaitRunning() {
        this.delegate.awaitRunning();
    }

    public final void awaitRunning(long j, TimeUnit timeUnit) throws TimeoutException {
        this.delegate.awaitRunning(j, timeUnit);
    }

    public final void awaitTerminated() {
        this.delegate.awaitTerminated();
    }

    public final void awaitTerminated(long j, TimeUnit timeUnit) throws TimeoutException {
        this.delegate.awaitTerminated(j, timeUnit);
    }
}
