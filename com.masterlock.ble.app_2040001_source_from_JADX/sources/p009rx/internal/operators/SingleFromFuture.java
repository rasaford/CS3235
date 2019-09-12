package p009rx.internal.operators;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import p009rx.Single.OnSubscribe;
import p009rx.SingleSubscriber;
import p009rx.exceptions.Exceptions;
import p009rx.subscriptions.Subscriptions;

/* renamed from: rx.internal.operators.SingleFromFuture */
public final class SingleFromFuture<T> implements OnSubscribe<T> {
    final Future<? extends T> future;
    final long timeout;
    final TimeUnit unit;

    public SingleFromFuture(Future<? extends T> future2, long j, TimeUnit timeUnit) {
        this.future = future2;
        this.timeout = j;
        this.unit = timeUnit;
    }

    public void call(SingleSubscriber<? super T> singleSubscriber) {
        Object obj;
        Future<? extends T> future2 = this.future;
        singleSubscriber.add(Subscriptions.from(future2));
        try {
            if (this.timeout == 0) {
                obj = future2.get();
            } else {
                obj = future2.get(this.timeout, this.unit);
            }
            singleSubscriber.onSuccess(obj);
        } catch (Throwable th) {
            Exceptions.throwIfFatal(th);
            singleSubscriber.onError(th);
        }
    }
}
