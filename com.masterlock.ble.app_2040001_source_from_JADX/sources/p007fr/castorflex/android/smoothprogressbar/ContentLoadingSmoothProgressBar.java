package p007fr.castorflex.android.smoothprogressbar;

import android.content.Context;
import android.util.AttributeSet;

/* renamed from: fr.castorflex.android.smoothprogressbar.ContentLoadingSmoothProgressBar */
public class ContentLoadingSmoothProgressBar extends SmoothProgressBar {
    private static final int MIN_DELAY = 500;
    private static final int MIN_SHOW_TIME = 500;
    private final Runnable mDelayedHide;
    private final Runnable mDelayedShow;
    /* access modifiers changed from: private */
    public boolean mDismissed;
    /* access modifiers changed from: private */
    public boolean mPostedHide;
    /* access modifiers changed from: private */
    public boolean mPostedShow;
    /* access modifiers changed from: private */
    public long mStartTime;

    public ContentLoadingSmoothProgressBar(Context context) {
        this(context, null);
    }

    public ContentLoadingSmoothProgressBar(Context context, AttributeSet attributeSet) {
        super(context, attributeSet, 0);
        this.mStartTime = -1;
        this.mPostedHide = false;
        this.mPostedShow = false;
        this.mDismissed = false;
        this.mDelayedHide = new Runnable() {
            public void run() {
                ContentLoadingSmoothProgressBar.this.mPostedHide = false;
                ContentLoadingSmoothProgressBar.this.mStartTime = -1;
                ContentLoadingSmoothProgressBar.this.setVisibility(8);
            }
        };
        this.mDelayedShow = new Runnable() {
            public void run() {
                ContentLoadingSmoothProgressBar.this.mPostedShow = false;
                if (!ContentLoadingSmoothProgressBar.this.mDismissed) {
                    ContentLoadingSmoothProgressBar.this.mStartTime = System.currentTimeMillis();
                    ContentLoadingSmoothProgressBar.this.setVisibility(0);
                }
            }
        };
    }

    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks();
    }

    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks();
    }

    private void removeCallbacks() {
        removeCallbacks(this.mDelayedHide);
        removeCallbacks(this.mDelayedShow);
    }

    public void hide() {
        this.mDismissed = true;
        removeCallbacks(this.mDelayedShow);
        long currentTimeMillis = System.currentTimeMillis();
        long j = this.mStartTime;
        long j2 = currentTimeMillis - j;
        if (j2 >= 500 || j == -1) {
            setVisibility(8);
        } else if (!this.mPostedHide) {
            postDelayed(this.mDelayedHide, 500 - j2);
            this.mPostedHide = true;
        }
    }

    public void show() {
        this.mStartTime = -1;
        this.mDismissed = false;
        removeCallbacks(this.mDelayedHide);
        if (!this.mPostedShow) {
            postDelayed(this.mDelayedShow, 500);
            this.mPostedShow = true;
        }
    }
}
