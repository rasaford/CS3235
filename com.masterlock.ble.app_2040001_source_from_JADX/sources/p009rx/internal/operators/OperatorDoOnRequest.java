package p009rx.internal.operators;

import p009rx.Observable.Operator;
import p009rx.Producer;
import p009rx.Subscriber;
import p009rx.functions.Action1;

/* renamed from: rx.internal.operators.OperatorDoOnRequest */
public class OperatorDoOnRequest<T> implements Operator<T, T> {
    final Action1<? super Long> request;

    /* renamed from: rx.internal.operators.OperatorDoOnRequest$ParentSubscriber */
    static final class ParentSubscriber<T> extends Subscriber<T> {
        private final Subscriber<? super T> child;

        ParentSubscriber(Subscriber<? super T> subscriber) {
            this.child = subscriber;
            request(0);
        }

        /* access modifiers changed from: private */
        public void requestMore(long j) {
            request(j);
        }

        public void onCompleted() {
            this.child.onCompleted();
        }

        public void onError(Throwable th) {
            this.child.onError(th);
        }

        public void onNext(T t) {
            this.child.onNext(t);
        }
    }

    public OperatorDoOnRequest(Action1<? super Long> action1) {
        this.request = action1;
    }

    public Subscriber<? super T> call(Subscriber<? super T> subscriber) {
        final ParentSubscriber parentSubscriber = new ParentSubscriber(subscriber);
        subscriber.setProducer(new Producer() {
            public void request(long j) {
                OperatorDoOnRequest.this.request.call(Long.valueOf(j));
                parentSubscriber.requestMore(j);
            }
        });
        subscriber.add(parentSubscriber);
        return parentSubscriber;
    }
}
