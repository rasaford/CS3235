package p009rx.internal.operators;

import p009rx.Observable.Operator;
import p009rx.Scheduler;
import p009rx.Subscriber;
import p009rx.schedulers.Timestamped;

/* renamed from: rx.internal.operators.OperatorTimestamp */
public final class OperatorTimestamp<T> implements Operator<Timestamped<T>, T> {
    final Scheduler scheduler;

    public OperatorTimestamp(Scheduler scheduler2) {
        this.scheduler = scheduler2;
    }

    public Subscriber<? super T> call(final Subscriber<? super Timestamped<T>> subscriber) {
        return new Subscriber<T>(subscriber) {
            public void onCompleted() {
                subscriber.onCompleted();
            }

            public void onError(Throwable th) {
                subscriber.onError(th);
            }

            public void onNext(T t) {
                subscriber.onNext(new Timestamped(OperatorTimestamp.this.scheduler.now(), t));
            }
        };
    }
}
