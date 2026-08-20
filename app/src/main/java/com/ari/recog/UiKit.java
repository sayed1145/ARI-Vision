package com.ari.recog;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * UiKit — one look for the whole app. Deep navy + mint. No more stacked
 * default Buttons on a white Activity.
 */
public final class UiKit {
    public static final int BG       = 0xFF070B14;
    public static final int SURFACE  = 0xFF121A2A;
    public static final int CARD     = 0xFF182236;
    public static final int CARD2    = 0xFF1E2A42;
    public static final int PRIMARY  = 0xFF3DDC97;
    public static final int PRIMARY_D= 0xFF1FA971;
    public static final int ACCENT   = 0xFF6EA8FF;
    public static final int WARN     = 0xFFFFB020;
    public static final int DANGER   = 0xFFFF5C7A;
    public static final int TEXT     = 0xFFF2F6FF;
    public static final int MUTED    = 0xFF8E9BB3;
    public static final int LINE     = 0xFF2A3650;

    private UiKit() {}

    public static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable round(int color, int radiusDp, Activity a) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(a, radiusDp));
        return d;
    }

    public static GradientDrawable roundStroke(int fill, int stroke, int radiusDp, Activity a) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(a, radiusDp));
        d.setStroke(dp(a, 1), stroke);
        return d;
    }

    public static View rippleWrap(Activity a, View inner, int fill, int radius) {
        inner.setBackground(round(fill, radius, a));
        return inner;
    }

    public static ScrollView screen(Activity a, LinearLayout[] outCol) {
        a.getWindow().setStatusBarColor(BG);
        a.getWindow().setNavigationBarColor(BG);
        ScrollView sc = new ScrollView(a);
        sc.setFillViewport(true);
        sc.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 18);
        col.setPadding(p, dp(a, 28), p, dp(a, 36));
        sc.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        a.setContentView(sc);
        outCol[0] = col;
        return sc;
    }

    public static void appBar(Activity a, LinearLayout col, String title, String sub) {
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(a);
            s.setText(sub);
            s.setTextColor(MUTED);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            s.setPadding(0, dp(a, 4), 0, dp(a, 16));
            col.addView(s);
        } else {
            t.setPadding(0, 0, 0, dp(a, 16));
        }
    }

    public static LinearLayout card(Activity a) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 18, a));
        int p = dp(a, 16);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, 12);
        c.setLayoutParams(lp);
        return c;
    }

    public static TextView label(Activity a, String text, int color, int sp) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    public static Button primaryBtn(Activity a, String text, View.OnClickListener l) {
        return mkBtn(a, text, PRIMARY, 0xFF06281C, l);
    }

    public static Button accentBtn(Activity a, String text, View.OnClickListener l) {
        return mkBtn(a, text, ACCENT, 0xFF071428, l);
    }

    public static Button ghostBtn(Activity a, String text, View.OnClickListener l) {
        Button b = mkBtn(a, text, CARD2, TEXT, l);
        b.setBackground(roundStroke(CARD2, LINE, 14, a));
        return b;
    }

    public static Button dangerBtn(Activity a, String text, View.OnClickListener l) {
        return mkBtn(a, text, 0xFF3A1520, DANGER, l);
    }

    private static Button mkBtn(Activity a, String text, int bg, int fg, View.OnClickListener l) {
        Button b = new Button(a);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(round(bg, 14, a));
        b.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 6);
        lp.bottomMargin = dp(a, 6);
        b.setLayoutParams(lp);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    public static LinearLayout featureCard(Activity a, String emoji, String title, String sub,
                                           View.OnClickListener l) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 18, a));
        int p = dp(a, 14);
        c.setPadding(p, p, p, p);
        c.setClickable(true);
        c.setFocusable(true);
        TextView ic = label(a, emoji, TEXT, 26);
        TextView t = label(a, title, TEXT, 16);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, dp(a, 8), 0, 0);
        TextView s = label(a, sub, MUTED, 12);
        s.setPadding(0, dp(a, 2), 0, 0);
        c.addView(ic);
        c.addView(t);
        c.addView(s);
        if (l != null) c.setOnClickListener(l);
        return c;
    }

    public static LinearLayout row2(Activity a, View left, View right) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.rightMargin = dp(a, 6);
        lp2.leftMargin = dp(a, 6);
        left.setLayoutParams(lp);
        right.setLayoutParams(lp2);
        row.addView(left);
        row.addView(right);
        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrap.bottomMargin = dp(a, 12);
        row.setLayoutParams(wrap);
        return row;
    }

    public static TextView chip(Activity a, String text, int bg, int fg) {
        TextView t = label(a, text, fg, 12);
        t.setPadding(dp(a, 10), dp(a, 4), dp(a, 10), dp(a, 4));
        t.setBackground(round(bg, 20, a));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    public static void section(Activity a, LinearLayout col, String title) {
        TextView t = label(a, title, MUTED, 12);
        t.setPadding(dp(a, 4), dp(a, 10), 0, dp(a, 8));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(t);
    }
}
