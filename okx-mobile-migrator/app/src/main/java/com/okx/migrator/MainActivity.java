package com.okx.migrator;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final String SELF_PACKAGE = "com.okx.migrator.v13";
    private static final String TARGET_PACKAGE = "com.okx.scanner.dem2";
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final String TMP_NEW = "/data/local/tmp/okx-v13-new.apk";
    private static final String TMP_OLD = "/data/local/tmp/okx-v13-old.apk";
    private static final int REQ_SHIZUKU = 3001;
    private static final int REQ_APK = 3002;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private TextView log;
    private Button grant;
    private Button select;
    private Button migrate;
    private File newApk;
    private File prefsBackup;
    private File oldApk;
    private volatile boolean busy;

    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode == REQ_SHIZUKU) runOnUiThread(this::refresh);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        newApk = new File(getFilesDir(), "okx-v13.apk");
        prefsBackup = new File(getFilesDir(), "okx-current-data.tar");
        oldApk = new File(getFilesDir(), "okx-current-old.apk");
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
        root.setBackgroundColor(Color.rgb(247, 249, 252));
        scroll.addView(root);

        root.addView(tv("OKX V13 一键升级助手", 25, Color.rgb(20, 30, 45)));
        root.addView(tv("先备份你现在 V12 的最新交易记录和普通设置，再安装 V13 并自动恢复。不会迁移 API 密钥。", 15, Color.DKGRAY));
        root.addView(tv("顺序：启动 Shizuku → 授权 → 选择 V13 APK → 一键升级。", 15, Color.DKGRAY));

        status = tv("正在检查 Shizuku…", 16, Color.rgb(18, 91, 155));
        status.setPadding(0, dp(14), 0, dp(10));
        root.addView(status);

        Button open = btn("1. 打开 Shizuku");
        open.setOnClickListener(v -> openShizuku());
        root.addView(open);

        grant = btn("2. 授权升级助手");
        grant.setOnClickListener(v -> requestPermission());
        root.addView(grant);

        select = btn("3. 选择 V13 APK");
        select.setOnClickListener(v -> chooseApk());
        root.addView(select);

        migrate = btn("4. 一键升级并恢复数据");
        migrate.setOnClickListener(v -> startMigration());
        root.addView(migrate);

        root.addView(tv("重要：看到“升级成功”之前不要卸载当前 OKX Scanner。API Key / Secret / Passphrase 需要升级后重新填写一次。", 14, Color.rgb(120, 70, 30)));

        log = tv("", 13, Color.rgb(55, 65, 75));
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
        else if (!granted) status.setText("Shizuku 已运行，请授权升级助手");
        else if (!newApk.exists()) status.setText("Shizuku 已授权，请选择 V13 APK");
        else status.setText("准备完成，可以一键升级");
        grant.setEnabled(running && !granted && !busy);
        select.setEnabled(!busy);
        migrate.setEnabled(granted && newApk.exists() && !busy);
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

    private void chooseApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.android.package-archive");
        startActivityForResult(i, REQ_APK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_APK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        worker.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(newApk)) {
                if (in == null) throw new IllegalStateException("无法读取所选 APK");
                copy(in, out);
            } catch (Throwable t) {
                newApk.delete();
                append("复制 APK 失败：" + t.getMessage());
                return;
            }
            try {
                PackageInfo pi = getPackageManager().getPackageArchiveInfo(newApk.getAbsolutePath(), 0);
                if (pi == null || !TARGET_PACKAGE.equals(pi.packageName)) {
                    newApk.delete();
                    append("所选文件不是 OKX Scanner V13 APK");
                    return;
                }
                ApplicationInfo ai = pi.applicationInfo;
                if (ai == null || (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
                    newApk.delete();
                    append("所选新版不支持安全恢复数据，请使用我给你的 V13 APK");
                    return;
                }
                append("V13 APK 已确认，可以升级");
                runOnUiThread(this::refresh);
            } catch (Throwable t) {
                newApk.delete();
                append("APK 校验失败：" + t.getMessage());
            }
        });
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

    private Result capture(String command, File destination) throws Exception {
        Process p = remote("/system/bin/sh", "-c", command);
        try (FileOutputStream out = new FileOutputStream(destination)) { copy(p.getInputStream(), out); }
        return new Result(p.waitFor(), "");
    }

    private void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) if (n > 0) out.write(buf, 0, n);
    }

    private boolean success(Result r) {
        return r.code == 0 && r.text.toLowerCase().contains("success");
    }

    private void startMigration() {
        if (busy || !ready() || !newApk.exists()) { refresh(); return; }
        busy = true;
        grant.setEnabled(false);
        select.setEnabled(false);
        migrate.setEnabled(false);
        log.setText("");
        worker.execute(this::performMigration);
    }

    private void performMigration() {
        boolean oldRemoved = false;
        try {
            append("正在确认当前 V12 数据可读取…");
            Result runAs = exec("run-as " + TARGET_PACKAGE + " id");
            if (runAs.code != 0 || !runAs.text.contains("uid=")) throw new IllegalStateException("当前版本不允许安全读取数据，已停止");

            append("正在备份最新交易记录和普通设置…");
            String prefFiles = "shared_prefs/app.xml shared_prefs/auto_trade_state.xml shared_prefs/reduction_state.xml shared_prefs/signal_alert_state.xml shared_prefs/sim_account_v1.xml shared_prefs/stable_auto_signals.xml shared_prefs/stable_auto_signals_v13.xml shared_prefs/ten_trade_guard.xml shared_prefs/trade_settings.xml";
            String backupCmd = "run-as " + TARGET_PACKAGE + " sh -c 'cd /data/user/0/" + TARGET_PACKAGE + " || exit 20; set --; for f in " + prefFiles + "; do [ -f \"$f\" ] && set -- \"$@\" \"$f\"; done; [ \"$#\" -gt 0 ] || exit 21; tar -cf - \"$@\"' 2>/dev/null";
            Result b = capture(backupCmd, prefsBackup);
            if (b.code != 0 || prefsBackup.length() < 100) throw new IllegalStateException("数据备份失败，当前版本保持不变");
            append("最新交易记录备份完成");

            append("正在备份当前 APK，作为自动回滚保险…");
            Result path = exec("pm path " + TARGET_PACKAGE);
            if (path.code != 0 || !path.text.contains("package:")) throw new IllegalStateException("找不到当前 APK");
            String apkPath = null;
            for (String line : path.text.split("\\n")) {
                if (line.startsWith("package:")) {
                    String p = line.substring("package:".length()).trim();
                    if (p.endsWith("base.apk")) { apkPath = p; break; }
                    if (apkPath == null) apkPath = p;
                }
            }
            if (apkPath == null) throw new IllegalStateException("当前 APK 路径无效");
            Result oldCopy = capture("cat '" + apkPath.replace("'", "") + "' 2>/dev/null", oldApk);
            if (oldCopy.code != 0 || oldApk.length() < 1024 * 1024) throw new IllegalStateException("当前 APK 保险备份失败");
            append("回滚保险备份完成");

            append("正在准备 V13 安装文件…");
            Result stage = exec("rm -f " + TMP_NEW + "; run-as " + SELF_PACKAGE + " cat /data/user/0/" + SELF_PACKAGE + "/files/okx-v13.apk > " + TMP_NEW + " && chmod 644 " + TMP_NEW + " && test -s " + TMP_NEW);
            if (stage.code != 0) throw new IllegalStateException("V13 安装文件准备失败：" + stage.text);

            append("备份全部成功，现在开始升级…");
            Result uninstall = exec("pm uninstall " + TARGET_PACKAGE);
            if (!success(uninstall)) throw new IllegalStateException("移除当前版本失败：" + uninstall.text);
            oldRemoved = true;

            append("正在安装 V13…");
            Result install = exec("pm install " + TMP_NEW);
            if (!success(install)) throw new IllegalStateException("V13 安装失败：" + install.text);

            Result newRunAs = exec("run-as " + TARGET_PACKAGE + " id");
            if (newRunAs.code != 0 || !newRunAs.text.contains("uid=")) throw new IllegalStateException("V13 已安装，但无法恢复数据");

            append("正在恢复交易记录和普通设置…");
            exec("am force-stop " + TARGET_PACKAGE);
            Result restore = exec("run-as " + SELF_PACKAGE + " cat /data/user/0/" + SELF_PACKAGE + "/files/okx-current-data.tar | run-as " + TARGET_PACKAGE + " sh -c 'cd /data/user/0/" + TARGET_PACKAGE + " && tar -xf -'");
            if (restore.code != 0) throw new IllegalStateException("数据恢复失败：" + restore.text);

            exec("run-as " + TARGET_PACKAGE + " rm -f /data/user/0/" + TARGET_PACKAGE + "/shared_prefs/secure_settings.xml");
            exec("am force-stop " + TARGET_PACKAGE);
            exec("rm -f " + TMP_NEW + " " + TMP_OLD);
            oldRemoved = false;
            append("升级成功：V13 已安装，交易记录和普通设置已恢复");
            append("正在打开 V13；请重新填写一次 OKX API 信息");
            exec("monkey -p " + TARGET_PACKAGE + " 1 >/dev/null 2>&1 || true");
        } catch (Throwable t) {
            append("升级中止：" + t.getMessage());
            if (oldRemoved && oldApk.exists() && prefsBackup.exists()) {
                append("正在自动回滚当前 V12，请不要关闭助手…");
                try {
                    exec("pm uninstall " + TARGET_PACKAGE + " >/dev/null 2>&1 || true");
                    Result stageOld = exec("rm -f " + TMP_OLD + "; run-as " + SELF_PACKAGE + " cat /data/user/0/" + SELF_PACKAGE + "/files/okx-current-old.apk > " + TMP_OLD + " && chmod 644 " + TMP_OLD + " && test -s " + TMP_OLD);
                    if (stageOld.code != 0) throw new IllegalStateException("旧版 APK 无法准备");
                    Result oldInstall = exec("pm install " + TMP_OLD);
                    if (!success(oldInstall)) throw new IllegalStateException("旧版安装失败：" + oldInstall.text);
                    Result restoreOld = exec("run-as " + SELF_PACKAGE + " cat /data/user/0/" + SELF_PACKAGE + "/files/okx-current-data.tar | run-as " + TARGET_PACKAGE + " sh -c 'cd /data/user/0/" + TARGET_PACKAGE + " && tar -xf -'");
                    if (restoreOld.code != 0) throw new IllegalStateException("旧版已装回，但数据恢复失败");
                    append("V12 和最新交易记录已自动恢复");
                    exec("monkey -p " + TARGET_PACKAGE + " 1 >/dev/null 2>&1 || true");
                } catch (Throwable rollback) {
                    append("自动回滚失败：" + rollback.getMessage());
                    append("不要卸载本升级助手；备份仍保存在助手里。");
                }
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
