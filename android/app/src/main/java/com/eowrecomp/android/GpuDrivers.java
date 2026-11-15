package com.eowrecomp.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

// A Vulkan driver the player installed (Mesa Turnip for Adreno GPUs, or a Qualcomm build), used in place of the
// system's from the next start on: runtime/nvn/vulkan/device.c loads it through libadrenotools. A driver is the .zip
// other emulators use (meta.json with its libraryName, and the driver's .so files); one is kept, in the app's private
// storage (a driver can only be loaded from there, not from shared storage). Adapted from LASRecomp-Android-Plus (WindWakerHD-Android-Plus).
final class GpuDrivers {
    private static final String TAG = "EoWRecomp";
    private GpuDrivers() {}

    static final class Driver {
        String name, version, library;
        File dir;
    }

    // An installed driver loads through libadrenotools on any GPU: Mesa Turnip on Adreno, Mesa PanVK on Mali, a vendor
    // build on Xclipse... (one that doesn't start is replaced by the system's at the next start: config's probe)
    static boolean supported() { return true; }

    // An Adreno GPU (the kernel's kgsl device): the Turnip build the APK carries can run
    static boolean adreno() {
        // (the kgsl node can be hidden from stat() by SELinux on some builds: also its sysfs entry and the SoC's name)
        return new File("/dev/kgsl-3d0").exists() || new File("/sys/class/kgsl/kgsl-3d0").exists()
               || new File("/sys/devices/platform/soc/3d00000.qcom,kgsl-3d0").exists()
               || android.os.Build.HARDWARE.toLowerCase().contains("qcom");
    }

    // Why the last config() chose the system's driver (device.c logs it: the app's Java side has no log file)
    static String why = "not asked yet";

    static String why() { return why; }

    private static String assetCount(Context c) {
        try {
            String[] n = c.getAssets().list("turnip");
            return n == null ? "none" : String.valueOf(n.length);
        } catch (IOException e) {
            return "error " + e.getMessage();
        }
    }

    static File root(Context c) { return new File(c.getFilesDir(), "gpu_driver"); }

    private static File installed(Context c) { return new File(root(c), "current"); }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("gpu_driver", Context.MODE_PRIVATE); }

    // The installed driver, or null
    static Driver find(Context c) { return read(installed(c)); }

    static boolean selected(Context c) { return prefs(c).getBoolean("use", false) && find(c) != null; }

    static void select(Context c, boolean use) {
        prefs(c).edit().putBoolean("use", use).commit();
        //noinspection ResultOfMethodCallIgnored
        new File(installed(c), ".probe").delete();  // (a fresh try)
    }

    private static Driver read(File dir) {
        try {
            JSONObject m = new JSONObject(new String(Files.readAllBytes(new File(dir, "meta.json").toPath()), StandardCharsets.UTF_8));
            Driver d = new Driver();
            d.name = m.optString("name", dir.getName());
            d.version = m.optString("driverVersion", "");
            d.library = m.getString("libraryName");
            d.dir = dir;
            if (d.library.contains("/") || !new File(dir, d.library).isFile()) return null;
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    // Installs the driver package at uri (replacing the one installed before) and selects it. Returns its name; throws
    // with what's wrong.
    static String install(Context c, Uri uri) throws IOException {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            return install(c, in);
        }
    }

    static String install(Context c, InputStream in) throws IOException {
        File tmp = new File(root(c), ".install");
        deleteTree(tmp);
        if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);
        long total = 0;
        try {
            if (in == null) throw new IOException("no data");
            ZipInputStream zip = new ZipInputStream(in);
            byte[] buf = new byte[1 << 16];
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                // only meta.json and libraries at the top level; nothing else is needed
                String n = e.getName();
                if (e.isDirectory() || n.contains("/") || n.contains("\\") || !(n.equals("meta.json") || n.endsWith(".so"))) continue;
                try (OutputStream out = new FileOutputStream(new File(tmp, n))) {
                    for (int k; (k = zip.read(buf)) > 0; ) {
                        total += k;
                        if (total > (512L << 20)) throw new IOException("too large");
                        out.write(buf, 0, k);
                    }
                }
            }
        } catch (IOException e) {
            deleteTree(tmp);
            throw e;
        }
        Driver d = read(tmp);
        if (d == null) {
            deleteTree(tmp);
            throw new IOException("not a driver package (no meta.json with its libraryName)");
        }
        File dest = installed(c);
        deleteTree(dest);
        if (!tmp.renameTo(dest)) {
            deleteTree(tmp);
            throw new IOException("cannot move " + tmp);
        }
        select(c, true);
        return d.version.isEmpty() ? d.name : d.name + " " + d.version;
    }

    // What device.c needs to load the selected driver ("" when the system's runs): its folder, its library's name, the
    // folder with libadrenotools' hooks and a temporary folder, one per line (each folder with a trailing /). A
    // marker file (.probe, removed by device.c once the driver drew its first frame) catches a driver that crashed
    // or hung at its last start: the system driver then runs, and failed() names it once.
    static String config(Context c) {
        why = "ok";
        if (!supported()) return why = "unsupported";
        // EoWRecomp's env.txt (the app's files folder): EOWRECOMP_GPU_DRIVER=system keeps the system's driver,
        // =turnip tries the APK's Turnip again after a failure
        String want = envDriver(c);
        if ("system".equals(want)) { why = "EOWRECOMP_GPU_DRIVER=system in env.txt"; return ""; }
        if ("turnip".equals(want)) prefs(c).edit().putBoolean("bundled_off", false).commit();
        installDropped(c);  // a driver .zip put in the app's files folder (driver.zip): installed and selected
        Driver d = selected(c) ? find(c) : auto(c) ? bundled(c) : null;
        if (d == null) {
            why = "no driver: adreno=" + adreno() + " bundled_off=" + prefs(c).getBoolean("bundled_off", false)
                  + " turnip assets=" + assetCount(c) + " selected=" + selected(c);
            return "";
        }
        File probe = new File(d.dir, ".probe");
        if (probe.exists()) {  // the last start with this driver never drew its frames: the system's runs now (the
            // system's driver can run this game, unlike on phones older than Vulkan 1.3)
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            if (selected(c)) select(c, false);
            prefs(c).edit().putString("failed", d.name).putBoolean("bundled_off", true).commit();
            why = "the last start with " + d.name + " never drew its frames";
            return "";
        }
        String hooks = prepareHooks(c);
        if (hooks == null) {
            why = "no libadrenotools hooks (libmain_hook.so / libhook_impl.so) in " + c.getApplicationInfo().nativeLibraryDir;
            return "";
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            probe.createNewFile();
        } catch (IOException e) {
            Log.w(TAG, "cannot create " + probe, e);
        }
        return d.dir.getAbsolutePath() + "/\n" + d.library + "\n" + hooks + "\n" + c.getCacheDir().getAbsolutePath() + "/\n"
               + probe.getAbsolutePath();
    }

    // No custom driver selected, and the system's driver is older than Vulkan 1.3, which the renderer needs (Adreno 6xx
    // phones that stopped getting updates, e.g. the Galaxy S20 FE's 1.1): the Turnip build the APK carries runs
    static boolean auto(Context c) {
        // EoWRecomp 0.6: the APK's Turnip runs on every Adreno, unless it failed at a start (bundled_off) or env.txt asks
        // for the system's driver
        return adreno() && !prefs(c).getBoolean("bundled_off", false);
    }

    // EOWRECOMP_GPU_DRIVER in env.txt (KEY=VALUE per line): "system", "turnip" or null
    private static String envDriver(Context c) {
        try {
            File f = new File(c.getExternalFilesDir(null), "env.txt");
            if (!f.isFile()) return null;
            for (String line : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\\r?\\n")) {
                line = line.trim();
                if (line.startsWith("EOWRECOMP_GPU_DRIVER=")) return line.substring(21).trim().toLowerCase();
            }
        } catch (Exception e) {
            Log.w(TAG, "env.txt", e);
        }
        return null;
    }

    // A driver .zip left in the app's files folder as driver.zip (adb push or a file manager): installed once, then
    // the file is removed. Lets another Turnip build be tried without building the APK again.
    private static void installDropped(Context c) {
        File z = new File(c.getExternalFilesDir(null), "driver.zip");
        if (!z.isFile()) return;
        try (InputStream in = new java.io.FileInputStream(z)) {
            String name = install(c, in);
            Log.i(TAG, "GPU driver installed from driver.zip: " + name);
            prefs(c).edit().putBoolean("bundled_off", false).commit();
        } catch (IOException e) {
            Log.e(TAG, "driver.zip", e);
        }
        //noinspection ResultOfMethodCallIgnored
        z.delete();
    }

    // The driver in the APK's assets (turnip/: meta.json and its library, tools/android/build_deps.sh), copied out once
    // per APK into gpu_driver/bundled (a driver loads only from the app's private storage). null if the APK has none.
    static Driver bundled(Context c) {
        File dir = new File(root(c), "bundled");
        String apk = c.getApplicationInfo().sourceDir;
        String stamp = apk + ":" + new File(apk).lastModified();
        File stampFile = new File(dir, ".apk");
        try {
            if (!(stampFile.isFile() && stamp.equals(new String(Files.readAllBytes(stampFile.toPath()), StandardCharsets.UTF_8)))) {
                String[] names = c.getAssets().list("turnip");
                if (names == null || names.length == 0) return null;
                deleteTree(dir);
                if (!dir.mkdirs()) throw new IOException("cannot create " + dir);
                for (String n : names)
                    try (InputStream in = c.getAssets().open("turnip/" + n); OutputStream out = new FileOutputStream(new File(dir, n))) {
                        byte[] buf = new byte[1 << 16];
                        for (int k; (k = in.read(buf)) > 0; ) out.write(buf, 0, k);
                    }
                Files.write(stampFile.toPath(), stamp.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.e(TAG, "bundled GPU driver", e);
            deleteTree(dir);
            return null;
        }
        return read(dir);
    }

    // The driver that failed at its last start (see config), once; else null
    static String failed(Context c) {
        String n = prefs(c).getString("failed", null);
        if (n != null) prefs(c).edit().remove("failed").commit();
        return n;
    }

    // libadrenotools loads its hooks (libmain_hook.so, libhook_impl.so) by name from a folder. The app's native library
    // folder has them only when libraries are extracted at install; this app maps them straight from the APK
    // (useLegacyPackaging false), so they're copied out of it once per APK. Returns the folder (trailing /) or null.
    private static String prepareHooks(Context c) {
        String[] libs = {"libmain_hook.so", "libhook_impl.so"};
        File lib = new File(c.getApplicationInfo().nativeLibraryDir);
        if (new File(lib, libs[0]).isFile() && new File(lib, libs[1]).isFile()) return lib.getAbsolutePath() + "/";
        File dir = new File(root(c), ".hooks");
        String apk = c.getApplicationInfo().sourceDir;
        String stamp = apk + ":" + new File(apk).lastModified();
        File stampFile = new File(dir, "apk");
        try {
            if (stampFile.isFile() && stamp.equals(new String(Files.readAllBytes(stampFile.toPath()), StandardCharsets.UTF_8)))
                return dir.getAbsolutePath() + "/";
            deleteTree(dir);
            if (!dir.mkdirs()) throw new IOException("cannot create " + dir);
            try (ZipFile z = new ZipFile(apk)) {
                for (String n : libs) {
                    ZipEntry e = z.getEntry("lib/arm64-v8a/" + n);
                    if (e == null) throw new IOException(n + " not in the APK");
                    try (InputStream in = z.getInputStream(e); OutputStream out = new FileOutputStream(new File(dir, n))) {
                        byte[] buf = new byte[1 << 16];
                        for (int k; (k = in.read(buf)) > 0; ) out.write(buf, 0, k);
                    }
                }
            }
            Files.write(stampFile.toPath(), stamp.getBytes(StandardCharsets.UTF_8));
            return dir.getAbsolutePath() + "/";
        } catch (IOException e) {
            Log.e(TAG, "GPU driver hooks", e);
            deleteTree(dir);
            return null;
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
