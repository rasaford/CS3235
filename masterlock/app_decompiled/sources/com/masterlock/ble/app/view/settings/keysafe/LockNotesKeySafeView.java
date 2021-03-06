package com.masterlock.ble.app.view.settings.keysafe;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import butterknife.ButterKnife;
import butterknife.InjectView;
import butterknife.OnClick;
import com.akta.android.p004ui.floatinglabeledittext.FloatingLabelEditText;
import com.masterlock.api.util.ApiError;
import com.masterlock.ble.app.C1075R;
import com.masterlock.ble.app.presenter.settings.keysafe.LockNotesKeySafePresenter;
import com.masterlock.ble.app.util.ViewUtil;
import com.masterlock.ble.app.view.IAuthenticatedView;

public class LockNotesKeySafeView extends LinearLayout implements IAuthenticatedView {
    @InjectView(2131296597)
    TextView mLockNameBanner;
    @InjectView(2131296600)
    FloatingLabelEditText mLockNotes;
    LockNotesKeySafePresenter mLockNotesKeySafePresenter;
    @InjectView(2131296422)
    TextView textDeviceId;

    public LockNotesKeySafeView(Context context) {
        this(context, null);
    }

    public LockNotesKeySafeView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        this.mLockNotesKeySafePresenter = new LockNotesKeySafePresenter(this);
    }

    /* access modifiers changed from: protected */
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!isInEditMode()) {
            ButterKnife.inject((View) this);
            this.mLockNotesKeySafePresenter.start();
            this.mLockNotes.getEditText().addTextChangedListener(ViewUtil.createHideErrorTextWatcher(this.mLockNotes));
        }
    }

    /* access modifiers changed from: protected */
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        this.mLockNotesKeySafePresenter.finish();
        ViewUtil.hideKeyboard(getContext(), this.mLockNotes.getWindowToken());
    }

    @OnClick({2131296352})
    public void onSaveClicked() {
        ViewUtil.hideKeyboard(getContext(), this.mLockNotes.getWindowToken());
        this.mLockNotesKeySafePresenter.updateNotes(this.mLockNotes.getText());
    }

    public void displayError(ApiError apiError) {
        Toast.makeText(getContext(), apiError.getMessage(), 1).show();
    }

    public void displaySuccess() {
        Toast.makeText(getContext(), getResources().getString(C1075R.string.lock_notes_successfully_changed), 1).show();
    }

    public void showPasscodeExpiredToast() {
        Toast.makeText(getContext(), getContext().getResources().getString(C1075R.string.password_timeout_message), 1).show();
    }

    public void updateNotes(String str) {
        this.mLockNotes.setText(str);
    }

    public void setLockName(String str) {
        this.mLockNameBanner.setText(String.format(getResources().getString(C1075R.string.about_lock_name), new Object[]{str}));
    }

    public void setDeviceId(String str) {
        this.textDeviceId.setText(str);
    }
}
