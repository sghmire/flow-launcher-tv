package com.gfs.flow;

import android.graphics.Color;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextClock;

/** A system-managed clock screensaver, isolated from the launcher's image caches. */
public final class ClockDreamService extends DreamService {
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false); // Any remote input dismisses the screensaver.
        setFullscreen(true);
        setScreenBright(false);

        FrameLayout background = new FrameLayout(this);
        background.setBackgroundColor(Color.BLACK);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(0xFFB8BDC6);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
        clock.setGravity(Gravity.CENTER);
        // No seconds: TextClock updates on minute ticks, with no rendering loop or images.
        background.addView(clock, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(background);
    }
}
