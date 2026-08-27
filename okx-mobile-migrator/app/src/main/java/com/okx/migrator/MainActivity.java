package com.okx.migrator;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final String SOURCE_HELPER = "com.okx.migrator";
    private static final String TARGET_PACKAGE = "com.okx.scanner.dem2";
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final String TMP_NEW = "/data/local/tmp/okx-migrate-new.apk";
    private static final String TMP_OLD = "/data/local/tmp/okx-migrate-old.apk";
    private static final int REQ_SHIZUKU = 2001;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private TextView log;
    private Button grant;
    private Button rescue;
    private volatile boolean busy;

    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode == REQ_SHIZUKU) runOnUiThread(this::refresh);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        buildUi();
        refresh();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        worker.shutdownNow();
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView tv(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, dp(5), 0, dp(5));
        return t;
    }

    private Button btn(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(54));
        lp.setMargins(0, dp(7), 0, dp(7));
        b.setLayoutParams(lp);
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(30));
        root.setBackgroundColor(Color.rgb(247,249,252));
        scroll.addView(root);

        root.addView(tv("OKX 数据迁移修复助手", 25, Color.rgb(20,30,45)));
        root.addView(tv("用于修复刚才已经完成备份、但安装新版失败的情况。不会删除原迁移助手里的备份。", 15, Color.DKGRAY));
        root.addView(tv("它会直接读取原迁移助手里已经保存的交易记录、旧版 APK 和新版 APK，再用兼容方式完成安装与恢复。", 15, Color.DKGRAY));

        status = tv("正在检查 Shizuku…", 16, Color.rgb(18,91,155));
        status.setPadding(0, dp(14), 0, dp(10));
        root.addView(status);

        Button open = btn("1. 打开 Shizuku");
        open.setOnClickListener(v -> openShizuku());
        root.addView(open);

        grant = btn("2. 授权 Shizuku");
        grant.setOnClickListener(v -> requestPermission());
        root.addView(grant);

        rescue = btn("3. 修复并完成迁移");
        rescue.setOnClickListener(v -> startRescue());
        root.addView(rescue);

        TextView note = tv("重要：在这里显示“迁移成功”之前，不要卸载原来的“OKX 数据迁移助手”。API Key / Secret / Passphrase 仍需在新版中重新填写。", 14, Color.rgb(120,70,30));
        note.setPadding(0, dp(10), 0, dp(8));
        root.addView(note);

        log = tv("", 13, Color.rgb(55,65,75));
        log.setTextIsSelectable(true);
        root.addView(log);
        setContentView(scroll);
    }

    private void append(String s) {
        runOnUiThread(() -> {
            log.append(s + "\n");
            status.setText(s);
        });
    }

    private boolean ready() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) { return false; }
    }

    private void refresh() {
        boolean running = false, granted = false;
        try {
            running = Shizuku.pingBinder();
            granted = running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {}
        if (!running) status.setText("Shizuku 未运行，请先启动");
        else if (!granted) status.setText("Shizuku 已运行，请授权本修复助手");
        else status.setText("Shizuku 已授权，可以修复迁移");
        grant.setEnabled(running && !granted && !busy);
        rescue.setEnabled(granted && !busy);
    }

    private void openShizuku() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
            if (i != null) startActivity(i);
            else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + SHIZUKU_PACKAGE)));
        } catch (Throwable t) {
            status.setText("请手动打开 Shizuku");
        }
    }

    private void requestPermission() {
        try {
            if (!Shizuku.pingBinder()) { status.setText("Shizuku 还没有启动"); return; }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { refresh(); return; }
            Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (Throwable t) {
            status.setText("授权失败：" + t.getMessage());
        }
    }

    private Process remote(String... command) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process)m.invoke(null, command, null, null);
    }

    private Result exec(String command) throws Exception {
        Process p = remote("/system/bin/sh", "-c", command + " 2>&1");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(p.getInputStream(), out);
        int code = p.waitFor();
        return new Result(code, out.toString(StandardCharsets.UTF_8.name()).trim());
    }

    private void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) if (n > 0) out.write(buf, 0, n);
    }

    private boolean success(Result r) {
        return r.code == 0 && r.text.toLowerCase().contains("success");
    }

    private void startRescue() {
        if (busy || !ready()) { refresh(); return; }
        busy = true;
        grant.setEnabled(false);
        rescue.setEnabled(false);
        log.setText("");
        worker.execute(this::performRescue);
    }

    private void performRescue() {
        try {
            append("正在检查原迁移助手中的备份…");
            Result check = exec("run-as " + SOURCE_HELPER + " sh -c 'test -s files/okx-normal-settings.tar && test -s files/okx-old.apk && test -s files/okx-new.apk && echo READY'");
            if (check.code != 0 || !check.text.contains("READY")) {
                throw new IllegalStateException("没有找到完整备份，请不要删除原迁移助手");
            }
            append("备份完整，开始修复安装方式…");

            Result stageNew = exec("rm -f " + TMP_NEW + "; run-as " + SOURCE_HELPER + " cat /data/user/0/" + SOURCE_HELPER + "/files/okx-new.apk > " + TMP_NEW + " && chmod 644 " + TMP_NEW + " && test -s " + TMP_NEW);
            if (stageNew.code != 0) throw new IllegalStateException("无法准备新版 APK：" + stageNew.text);

            Result existing = exec("pm path " + TARGET_PACKAGE);
            if (existing.code == 0 && existing.text.contains("package:")) {
                append("检测到旧软件仍在，先安全移除后恢复备份…");
                Result u = exec("pm uninstall " + TARGET_PACKAGE);
                if (!success(u)) throw new IllegalStateException("移除旧软件失败：" + u.text);
            }

            append("正在安装新版…");
            Result install = exec("pm install " + TMP_NEW);
            if (!success(install)) throw new IllegalStateException("新版安装失败：" + install.text);

            Result runAs = exec("run-as " + TARGET_PACKAGE + " id");
            if (runAs.code != 0 || !runAs.text.contains("uid=")) throw new IllegalStateException("新版安装完成，但无法恢复数据");

            append("正在恢复交易记录和普通设置…");
            exec("am force-stop " + TARGET_PACKAGE);
            Result restore = exec("run-as " + SOURCE_HELPER + " cat /data/user/0/" + SOURCE_HELPER + "/files/okx-normal-settings.tar | run-as " + TARGET_PACKAGE + " sh -c 'cd /data/user/0/" + TARGET_PACKAGE + " && tar -xf -'");
            if (restore.code != 0) throw new IllegalStateException("数据恢复失败：" + restore.text);

            exec("run-as " + TARGET_PACKAGE + " rm -f /data/user/0/" + TARGET_PACKAGE + "/shared_prefs/secure_settings.xml");
            exec("rm -f " + TMP_NEW + " " + TMP_OLD);
            append("迁移成功：交易记录和普通设置已恢复");
            append("正在打开新版，请重新填写一次 OKX API 信息");
            exec("monkey -p " + TARGET_PACKAGE + " 1 >/dev/null 2>&1 || true");
        } catch (Throwable t) {
            append("修复迁移失败：" + t.getMessage());
            append("正在尝试从原迁移助手里的旧版 APK 自动回滚…");
            try {
                exec("pm uninstall " + TARGET_PACKAGE + " >/dev/null 2>&1 || true");
                Result stageOld = exec("rm -f " + TMP_OLD + "; run-as " + SOURCE_HELPER + " cat /data/user/0/" + SOURCE_HELPER + "/files/okx-old.apk > " + TMP_OLD + " && chmod 644 " + TMP_OLD + " && test -s " + TMP_OLD);
                if (stageOld.code != 0) throw new IllegalStateException("旧版 APK 无法准备");
                Result oldInstall = exec("pm install " + TMP_OLD);
                if (!success(oldInstall)) throw new IllegalStateException("旧版安装失败：" + oldInstall.text);
                Result restoreOld = exec("run-as " + SOURCE_HELPER + " cat /data/user/0/" + SOURCE_HELPER + "/files/okx-normal-settings.tar | run-as " + TARGET_PACKAGE + " sh -c 'cd /data/user/0/" + TARGET_PACKAGE + " && tar -xf -'");
                if (restoreOld.code != 0) throw new IllegalStateException("旧版已装回，但数据恢复失败");
                append("旧版和交易记录已恢复。请保留两个迁移助手并把页面截图发给我。");
                exec("monkey -p " + TARGET_PACKAGE + " 1 >/dev/null 2>&1 || true");
            } catch (Throwable rollback) {
                append("自动回滚仍失败：" + rollback.getMessage());
                append("不要卸载原迁移助手；你的备份仍保存在里面。");
            }
        } finally {
            try { exec("rm -f " + TMP_NEW + " " + TMP_OLD); } catch (Throwable ignored) {}
            busy = false;
            runOnUiThread(this::refresh);
        }
    }

    private static class Result {
        final int code;
        final String text;
        Result(int code, String text) { this.code = code; this.text = text == null ? "" : text; }
    }
}
