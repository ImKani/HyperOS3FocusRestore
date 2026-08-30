package com.hyperos3.focusrestore.testsender;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class TestSenderActivity extends Activity {
    private static final String CHANNEL_ID = "hyperos3_focus_test";
    private static final int BASE_ID = 41000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private NotificationManager manager;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        manager = getSystemService(NotificationManager.class);
        setContentView(createContent());
        ensureChannel();
    }

    private View createContent() {
        int pad = dp(20);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(24), pad, pad);
        root.setBackgroundColor(Color.rgb(248, 249, 250));
        TextView title = text("超级岛 / 焦点通知测试", 24, Color.rgb(28, 28, 30));
        title.setTypeface(title.getTypeface(), 1);
        root.addView(title, params(dp(10)));
        root.addView(text("测试发送器独立于 LSPosed 模块，方便返回桌面后观察真实显示并抓取日志。", 14, Color.rgb(95, 99, 104)), params(dp(24)));
        Button all = new Button(this);
        all.setText("发送完整测试：焦点通知 → 超级岛");
        root.addView(all, params(dp(8)));
        root.addView(text("点击后 2 秒发送焦点通知；再间隔 2 秒发送 6 种超级岛模板，最后一条约在点击后 14 秒发送。点击后立即返回桌面。", 13, Color.rgb(95, 99, 104)), params(dp(18)));
        Button focus = new Button(this);
        focus.setText("仅发送焦点通知测试");
        root.addView(focus, params(dp(6)));
        Button island = new Button(this);
        island.setText("仅发送超级岛模板测试");
        root.addView(island, params(dp(6)));
        Button clear = new Button(this);
        clear.setText("清理测试通知");
        root.addView(clear, params(0));
        all.setOnClickListener(v -> sendAll());
        focus.setOnClickListener(v -> sendFocus(2000L));
        island.setOnClickListener(v -> sendIslands(2000L));
        clear.setOnClickListener(v -> clear());
        scroll.addView(root);
        return scroll;
    }

    private void sendAll() {
        if (!checkPermission()) return;
        sendFocus(2000L);
        handler.postDelayed(() -> manager.cancel(BASE_ID + 100), 3800L);
        sendIslands(4000L);
        Toast.makeText(this, "测试已排队，立即返回桌面即可", Toast.LENGTH_SHORT).show();
    }

    private void sendFocus(long delay) {
        if (!checkPermission()) return;
        handler.postDelayed(() -> {
            Notification n = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("焦点通知测试")
                    .setContentText("焦点通知正文：取件码 2468 · 测试显示与跑马灯")
                    .setAutoCancel(true).build();
            n.extras.putBoolean("miui.focus.isFocus", true);
            n.extras.putString("miui.focus.ticker", "焦点通知测试：取件码 2468");
            manager.notify(BASE_ID + 100, n);
        }, delay);
    }

    private void sendIslands(long startDelay) {
        if (!checkPermission()) return;
        final String[] params = {
                "{\"param_v2\":{\"business\":\"pickup\",\"baseInfo\":{\"type\":1,\"title\":\"待取件\",\"specialTitle\":\"取件码 2468\",\"content\":\"菜鸟驿站\"}}}",
                "{\"param_v2\":{\"business\":\"delivery\",\"baseInfo\":{\"type\":1,\"title\":\"配送中\",\"content\":\"预计 12:30 送达\"},\"multiProgressInfo\":{\"title\":\"配送进度\",\"progress\":40}}}",
                "{\"param_v2\":{\"business\":\"train\",\"highlightInfo\":{\"title\":\"G12 检票口\",\"content\":\"18:30 停止检票\"}}}",
                "{\"param_v2\":{\"business\":\"charge\",\"baseInfo\":{\"title\":\"充电中\",\"subTitle\":\"24%\",\"content\":\"剩 5 分钟\"}}}",
                "{\"param_v2\":{\"business\":\"chat\",\"chatInfo\":{\"title\":\"10086\",\"content\":\"您有一条新消息\"}}}",
                "{\"param_v2\":{\"business\":\"ticker\",\"ticker\":\"订单已确认：预计明天送达\"}}"
        };
        for (int i = 0; i < params.length; i++) {
            final int index = i;
            handler.postDelayed(() -> {
                Notification n = new Notification.Builder(this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("超级岛模板测试 " + (index + 1) + "/" + params.length)
                        .setContentText("官方 miui.focus.param 测试")
                        .setAutoCancel(true).build();
                n.extras.putString("miui.focus.param", params[index]);
                manager.notify(BASE_ID + index, n);
            }, startDelay + i * 2000L);
        }
    }

    private boolean checkPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
            Toast.makeText(this, "请允许通知权限后重新点击", Toast.LENGTH_SHORT).show();
            return false;
        }
        return manager != null;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26 && manager != null) manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "超级岛测试", NotificationManager.IMPORTANCE_DEFAULT));
    }

    private void clear() {
        if (manager == null) return;
        for (int i = 0; i <= 100; i++) manager.cancel(BASE_ID + i);
        Toast.makeText(this, "已清理测试通知", Toast.LENGTH_SHORT).show();
    }

    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private LinearLayout.LayoutParams params(int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = bottom;
        return p;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
