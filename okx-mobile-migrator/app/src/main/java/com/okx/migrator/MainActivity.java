package com.okx.migrator;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final String TARGET_PACKAGE = "com.okx.scanner.dem2";
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final int REQ_SHIZUKU = 1001;
    private static final int REQ_APK = 1002;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView statusView;
    private TextView logView;
    private Button grantButton;
    private Button selectButton;
    private Button migrateButton;
    private File selectedApk;
    private File prefsBackup;
    private File oldApkBackup;
    private volatile boolean busy;

    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode != REQ_SHIZUKU) return;
        runOnUiThread(() -> refreshShizukuStatus());
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        prefsBackup = new File(getFilesDir(), "okx-normal-settings.tar");
        oldApkBackup = new File(getFilesDir(), "okx-old.apk");
        selectedApk = new File(getFilesDir(), "okx-new.apk");
        buildUi();
        refreshShizukuStatus();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        worker.shutdownNow();
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, float size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, dp(5), 0, dp(5));
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        root.setBackgroundColor(Color.rgb(247, 249, 252));
        scroll.addView(root);

        TextView title = text("OKX 数据迁移助手", 26, Color.rgb(20, 30, 45));
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);
        root.addView(text("只迁移交易记录和普通设置，不读取或迁移 API 密钥。全程在本机完成。", 15, Color.DKGRAY));
        root.addView(text("使用方法：先启动 Shizuku → 授权 → 选择新版 APK → 一键迁移。", 15, Color.DKGRAY));

        statusView = text("正在检查 Shizuku…", 16, Color.rgb(18, 91, 155));
        statusView.setPadding(0, dp(14), 0, dp(10));
        root.addView(statusView);

        Button openShizuku = button("1. 打开 Shizuku");
        openShizuku.setOnClickListener(v -> openShizuku());
        root.addView(openShizuku);

        grantButton = button("2. 授权 Shizuku");
        grantButton.setOnClickListener(v -> requestShizukuPermission());
        root.addView(grantButton);

        selectButton = button("3. 选择新版 APK");
        selectButton.setOnClickListener(v -> chooseApk());
        root.addView(selectButton);

        migrateButton = button("4. 一键迁移升级");
        migrateButton.setEnabled(false);
        migrateButton.setOnClickListener(v -> startMigration());
        root.addView(migrateButton);

        TextView note = text("迁移成功后，交易历史和普通设置会恢复。由于 Android 安全机制，OKX API Key / Secret / Passphrase 需要重新填写一次。迁移确认完成前不要删除本助手。", 14, Color.rgb(90, 90, 90));
        note.setPadding(0, dp(12), 0, dp(8));
        root.addView(note);

        logView = text("", 13, Color.rgb(55, 65, 75));
        logView.setTextIsSelectable(true);
        root.addView(logView);

        setContentView(scroll);
    }

    private void log(String s) {
        runOnUiThread(() -> {
            logView.append(s + "\n");
            statusView.setText(s);
        });
    }

    private boolean shizukuReady() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void refreshShizukuStatus() {
        boolean running;
        boolean granted;
        try {
            running = Shizuku.pingBinder();
            granted = running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            running = false;
            granted = false;
        }
        if (!running) statusView.setText("Shizuku 未运行，请先打开并启动服务");
        else if (!granted) statusView.setText("Shizuku 已运行，请点击“授权 Shizuku”");
        else statusView.setText("Shizuku 已授权，可以开始迁移");
        grantButton.setEnabled(running && !granted && !busy);
        migrateButton.setEnabled(granted && selectedApk.exists() && !busy);
    }

    private void openShizuku() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setPackage(SHIZUKU_PACKAGE);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + SHIZUKU_PACKAGE)));
            } catch (Throwable ignored) {
                statusView.setText("请先安装并启动 Shizuku");
            }
        }
    }

    private void requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                statusView.setText("Shizuku 还没有启动");
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refreshShizukuStatus();
                return;
            }
            Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (Throwable t) {
            statusView.setText("授权失败：" + t.getMessage());
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
            try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(selectedApk)) {
                if (in == null) throw new IllegalStateException("无法读取所选 APK");
                copy(in, out);
            } catch (Throwable t) {
                log("复制 APK 失败：" + t.getMessage());
                return;
            }
            try {
                PackageInfo pi = getPackageManager().getPackageArchiveInfo(selectedApk.getAbsolutePath(), 0);
                if (pi == null || !TARGET_PACKAGE.equals(pi.packageName)) {
                    selectedApk.delete();
                    log("所选 APK 不是这套 OKX 软件，已停止");
                    return;
                }
                ApplicationInfo ai = pi.applicationInfo;
                if (ai == null || (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
                    selectedApk.delete();
                    log("所选新版不支持安全恢复数据，请换我给你的盈利优先版 APK");
                    return;
                }
                long version = android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                log("新版 APK 已确认，版本号：" + version);
                runOnUiThread(this::refreshShizukuStatus);
            } catch (Throwable t) {
                selectedApk.delete();
                log("APK 校验失败：" + t.getMessage());
            }
        });
    }

    private Process newRemoteProcess(String... command) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, command, null, null);
    }

    private Result execText(String command) throws Exception {
        Process p = newRemoteProcess("/system/bin/sh", "-c", command + " 2>&1");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(p.getInputStream(), out);
        int code = p.waitFor();
        return new Result(code, out.toString(StandardCharsets.UTF_8.name()).trim());
    }

    private Result captureBinary(String command, File destination) throws Exception {
        Process p = newRemoteProcess("/system/bin/sh", "-c", command);
        try (FileOutputStream out = new FileOutputStream(destination)) {
            copy(p.getInputStream(), out);
        }
        int code = p.waitFor();
        return new Result(code, "");
    }

    private Result feedFile(String command, File source) throws Exception {
        Process p = newRemoteProcess("/system/bin/sh", "-c", command + " 2>&1");
        Thread writer = new Thread(() -> {
            try (FileInputStream in = new FileInputStream(source); OutputStream out = p.getOutputStream()) {
                copy(in, out);
                out.flush();
            } catch (Throwable ignored) {
            }
        });
        writer.start();
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        copy(p.getInputStream(), response);
        writer.join();
        int code = p.waitFor();
        return new Result(code, response.toString(StandardCharsets.UTF_8.name()).trim());
    }

    private void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n > 0) out.write(buf, 0, n);
        }
    }

    private void startMigration() {
        if (busy) return;
        if (!shizukuReady()) {
            refreshShizukuStatus();
            return;
        }
        if (!selectedApk.exists()) {
            statusView.setText("请先选择新版 APK");
            return;
        }
        busy = true;
        grantButton.setEnabled(false);
        selectButton.setEnabled(false);
        migrateButton.setEnabled(false);
        logView.setText("");
        worker.execute(this::performMigration);
    }

    private void performMigration() {
        boolean oldRemoved = false;
        try {
            log("正在确认旧版数据可读取…");
            Result runAs = execText("run-as " + TARGET_PACKAGE + " id");
            if (runAs.code != 0 || !runAs.text.contains("uid=")) {
                throw new IllegalStateException("旧版不允许安全读取数据，未做任何删除");
            }

            log("正在备份交易记录和普通设置…");
            String prefFiles = "shared_prefs/app.xml shared_prefs/auto_trade_state.xml shared_prefs/reduction_state.xml shared_prefs/signal_alert_state.xml shared_prefs/sim_account_v1.xml shared_prefs/stable_auto_signals.xml shared_prefs/ten_trade_guard.xml shared_prefs/trade_settings.xml";
            String backupCmd = "run-as " + TARGET_PACKAGE + " sh -c 'cd /data/user/0/" + TARGET_PACKAGE + " || exit 20; set --; for f in " + prefFiles + "; do [ -f \"$f\" ] && set -- \"$@\" \"$f\"; done; [ \"$#\" -gt 0 ] || exit 21; tar -cf - \"$@\"' 2>/dev/null";
            Result b = captureBinary(backupCmd, prefsBackup);
            if (b.code != 0 || prefsBackup.length() < 100) throw new IllegalStateException("数据备份失败，旧版保持不变");
            log("交易记录备份完成");

            log("正在备份旧版 APK，作为自动回滚保险…");
            Result path = execText("pm path " + TARGET_PACKAGE);
            if (path.code != 0 || !path.text.contains("package:")) throw new IllegalStateException("无法找到旧版 APK");
            String apkPath = null;
            for (String line : path.text.split("\\n")) {
                if (line.startsWith("package:")) {
                    String p = line.substring("package:".length()).trim();
                    if (p.endsWith("base.apk")) { apkPath = p; break; }
                    if (apkPath == null) apkPath = p;
                }
            }
            if (apkPath == null) throw new IllegalStateException("旧版 APK 路径无效");
            Result oldCopy = captureBinary("cat '" + apkPath.replace("'", "") + "' 2>/dev/null", oldApkBackup);
            if (oldCopy.code != 0 || oldApkBackup.length() < 1024 * 1024) throw new IllegalStateException("旧版 APK 保险备份失败");
            log("旧版回滚备份完成");

            log("备份全部成功，现在开始升级…");
            Result uninstall = execText("pm uninstall " + TARGET_PACKAGE);
            if (uninstall.code != 0 || !uninstall.text.toLowerCase().contains("success")) throw new IllegalStateException("卸载旧版失败：" + uninstall.text);
            oldRemoved = true;

            log("正在安装新版…");
            Result install = feedFile("pm install -S " + selectedApk.length() + " -", selectedApk);
            if (install.code != 0 || !install.text.toLowerCase().contains("success")) throw new IllegalStateException("新版安装失败：" + install.text);

            Result newRunAs = execText("run-as " + TARGET_PACKAGE + " id");
            if (newRunAs.code != 0 || !newRunAs.text.contains("uid=")) throw new IllegalStateException("新版无法恢复数据");

            log("正在恢复交易记录和普通设置…");
            execText("am force-stop " + TARGET_PACKAGE);
            Result restore = feedFile("run-as " + TARGET_PACKAGE + " sh -c 'cd /data/user/0/" + TARGET_PACKAGE + " && tar -xf -'", prefsBackup);
            if (restore.code != 0) throw new IllegalStateException("数据恢复失败：" + restore.text);

            // API 密钥由 AndroidKeyStore 保护，不迁移加密文件，避免新安装后出现不可解密数据。
            execText("run-as " + TARGET_PACKAGE + " rm -f /data/user/0/" + TARGET_PACKAGE + "/shared_prefs/secure_settings.xml");
            execText("am force-stop " + TARGET_PACKAGE);
            log("迁移成功：交易记录和普通设置已恢复");
            log("请打开新版，并重新填写一次 OKX API 信息");
            execText("monkey -p " + TARGET_PACKAGE + " 1 >/dev/null 2>&1 || true");
            oldRemoved = false;
        } catch (Throwable t) {
            log("迁移中止：" + t.getMessage());
            if (oldRemoved && oldApkBackup.exists() && prefsBackup.exists()) {
                log("正在自动回滚到旧版，请不要关闭本助手…");
                try {
                    execText("pm uninstall " + TARGET_PACKAGE + " >/dev/null 2>&1 || true");
                    Result reinstallOld = feedFile("pm install -S " + oldApkBackup.length() + " -", oldApkBackup);
                    if (reinstallOld.code == 0 && reinstallOld.text.toLowerCase().contains("success")) {
                        Result restoreOld = feedFile("run-as " + TARGET_PACKAGE + " sh -c 'cd /data/user/0/" + TARGET_PACKAGE + " && tar -xf -'", prefsBackup);
                        if (restoreOld.code == 0) log("旧版和交易记录已自动恢复");
                        else log("旧版已恢复，但数据恢复需要继续处理");
                    } else {
                        log("自动回滚安装失败，请保留本助手并把这个页面截图发给我");
                    }
                } catch (Throwable rollbackError) {
                    log("自动回滚异常：" + rollbackError.getMessage());
                }
            }
        } finally {
            busy = false;
            runOnUiThread(() -> {
                selectButton.setEnabled(true);
                refreshShizukuStatus();
            });
        }
    }

    private static class Result {
        final int code;
        final String text;
        Result(int code, String text) { this.code = code; this.text = text == null ? "" : text; }
    }
}
