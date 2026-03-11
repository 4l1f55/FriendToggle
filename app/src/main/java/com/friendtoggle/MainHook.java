package com.friendtoggle;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "FriendToggle";
    private static final String TARGET_PACKAGE = "com.android.camera";

    private static final String FRAGMENT_FRIEND_HOST =
            "com.android.camera2.compat.theme.custom.mm.friend.FragmentFriendHost";

    private static final String FRIEND_MODULE_ENTRY =
            "com.android.camera.features.mode.shothelper.FriendModuleEntry";

    private static final String PREFS_NAME = "friend_toggle_prefs";
    private static final String PREF_ENABLED = "rear_display_enabled";

    private boolean mRearDisplayEnabled = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": Loaded into " + TARGET_PACKAGE);

        hookSupportFriendMode(lpparam.classLoader);
        hookFragmentFriendHost(lpparam.classLoader);
    }

    // Force isSupportFriendMode() to always return true
    private void hookSupportFriendMode(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(FRIEND_MODULE_ENTRY, cl);
            XposedHelpers.findAndHookMethod(cls, "isSupportFriendMode", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(true);
                    XposedBridge.log(TAG + ": isSupportFriendMode -> true");
                }
            });
            XposedBridge.log(TAG + ": Hook 1 OK (isSupportFriendMode)");
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": Hook 1 skipped: " + e.getMessage());
        }
    }

    // Hook FragmentFriendHost.onViewCreated to inject our button
    private void hookFragmentFriendHost(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(FRAGMENT_FRIEND_HOST, cl);
            XposedHelpers.findAndHookMethod(cls, "onViewCreated", View.class, Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object fragment = param.thisObject;
                                View root = (View) param.args[0];
                                injectButton(fragment, root);
                            } catch (Throwable e) {
                                XposedBridge.log(TAG + ": onViewCreated inject error: " + e);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": Hook 2 OK (onViewCreated)");
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": Hook 2 failed, trying onResume: " + e.getMessage());
            hookOnResumeFallback(cl);
        }
    }

    // Fallback: hook onResume if onViewCreated doesn't work
    private void hookOnResumeFallback(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(FRAGMENT_FRIEND_HOST, cl);
            XposedHelpers.findAndHookMethod(cls, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object fragment = param.thisObject;
                        Method getView = fragment.getClass().getMethod("getView");
                        View root = (View) getView.invoke(fragment);
                        if (root != null && root.findViewWithTag("ft_btn") == null) {
                            injectButton(fragment, root);
                        }
                    } catch (Throwable e) {
                        XposedBridge.log(TAG + ": onResume inject error: " + e);
                    }
                }
            });
            XposedBridge.log(TAG + ": Hook 2 fallback OK (onResume)");
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": Hook 2 fallback also failed: " + e);
        }
    }

    private void injectButton(final Object fragment, View root) {
        // Find a FrameLayout to overlay on
        ViewGroup container = findFrameLayout(root);
        if (container == null && root instanceof ViewGroup) {
            container = (ViewGroup) root;
        }
        if (container == null) {
            XposedBridge.log(TAG + ": No suitable container found");
            return;
        }

        // Don't inject twice
        if (container.findViewWithTag("ft_btn") != null) return;

        final Context ctx = container.getContext();
        final SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mRearDisplayEnabled = prefs.getBoolean(PREF_ENABLED, false);

        // Build the floating button
        final ImageButton btn = new ImageButton(ctx);
        btn.setTag("ft_btn");
        btn.setImageDrawable(buildIcon(ctx, mRearDisplayEnabled));
        btn.setBackground(buildBg(ctx, mRearDisplayEnabled));
        btn.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(ctx, 8);
        btn.setPadding(pad, pad, pad, pad);

        // Position: top-left, matching v5.3 placement
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(ctx, 42), dp(ctx, 42));
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.topMargin = dp(ctx, 58);
        lp.leftMargin = dp(ctx, 10);

        final ViewGroup finalContainer = container;

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mRearDisplayEnabled = !mRearDisplayEnabled;
                prefs.edit().putBoolean(PREF_ENABLED, mRearDisplayEnabled).apply();
                btn.setImageDrawable(buildIcon(ctx, mRearDisplayEnabled));
                btn.setBackground(buildBg(ctx, mRearDisplayEnabled));
                callToggle(fragment, mRearDisplayEnabled);
                XposedBridge.log(TAG + ": Toggled -> " + mRearDisplayEnabled);
            }
        });

        btn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Toast.makeText(ctx,
                        "Rear Display: " + (mRearDisplayEnabled ? "ON" : "OFF"),
                        Toast.LENGTH_SHORT).show();
                return true;
            }
        });

        finalContainer.addView(btn, lp);
        XposedBridge.log(TAG + ": Button injected into " + container.getClass().getSimpleName());

        // Restore previous state
        if (mRearDisplayEnabled) {
            callToggle(fragment, true);
        }
    }

    // Call showOrHideFriendHostSign() via reflection - it still exists in v6!
    private void callToggle(Object fragment, boolean show) {
        try {
            Class<?> cls = fragment.getClass();
            while (cls != null) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getName().equals("showOrHideFriendHostSign")) {
                        m.setAccessible(true);
                        Class<?>[] types = m.getParameterTypes();
                        if (types.length == 1 && types[0] == boolean.class) {
                            m.invoke(fragment, show);
                        } else if (types.length == 0) {
                            m.invoke(fragment);
                        }
                        XposedBridge.log(TAG + ": showOrHideFriendHostSign(" + show + ") called");
                        return;
                    }
                }
                cls = cls.getSuperclass();
            }
            XposedBridge.log(TAG + ": showOrHideFriendHostSign NOT FOUND");
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": callToggle error: " + e);
        }
    }

    // Dual-screen icon drawn programmatically (no resource files needed)
    private Drawable buildIcon(Context ctx, boolean on) {
        GradientDrawable outer = new GradientDrawable();
        outer.setShape(GradientDrawable.RECTANGLE);
        outer.setCornerRadius(dp(ctx, 3));
        outer.setColor(Color.TRANSPARENT);
        outer.setStroke(dp(ctx, 2), on ? Color.WHITE : Color.argb(180, 200, 200, 200));

        GradientDrawable inner = new GradientDrawable();
        inner.setShape(GradientDrawable.RECTANGLE);
        inner.setCornerRadius(dp(ctx, 2));
        inner.setColor(on ? Color.argb(200, 80, 130, 255) : Color.argb(80, 180, 180, 180));

        LayerDrawable layer = new LayerDrawable(new Drawable[]{outer, inner});
        int inset = dp(ctx, 6);
        layer.setLayerInset(1, inset, inset, inset, inset);
        return layer;
    }

    private Drawable buildBg(Context ctx, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(ctx, 8));
        bg.setColor(on ? Color.argb(200, 255, 255, 255) : Color.argb(130, 20, 20, 20));
        bg.setStroke(dp(ctx, 1), on ? Color.argb(180, 80, 130, 255) : Color.argb(100, 160, 160, 160));
        return bg;
    }

    private ViewGroup findFrameLayout(View v) {
        if (v instanceof FrameLayout && ((ViewGroup) v).getChildCount() > 0) {
            return (ViewGroup) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                ViewGroup found = findFrameLayout(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
}
