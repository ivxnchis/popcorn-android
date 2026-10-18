/*
 * This file is part of Butter.
 *
 * Butter is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Butter is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Butter. If not, see <http://www.gnu.org/licenses/>.
 */

package butter.droid.tv.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * What it takes to find out why the app misbehaved on someone's TV: crashes are kept across restarts, and together
 * with the app's log they can be read from another device.
 */
public final class AppLogs {

    private static final String CRASH_DIRECTORY = "crash-logs";
    private static final int KEEP_CRASHES = 5;
    private static final int LOG_LINES = 3000;
    private static final int MAX_LOG_SIZE = 2 * 1024 * 1024;

    private AppLogs() {
    }

    /**
     * Saves the stack trace of crashes, then lets them crash the app as before.
     */
    public static void install(final Context context) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    saveCrash(context, thread, throwable);
                } catch (Throwable ignored) {
                    // never get in the way of the crash itself
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
            }
        });
    }

    /**
     * @return the device and app version, the last crashes and the app's recent log
     */
    public static String collect(Context context) {
        StringBuilder report = new StringBuilder();
        report.append("App: ").append(getAppVersion(context)).append('\n');
        report.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")")
                .append(", ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        report.append("Collected: ").append(now()).append("\n\n");

        File[] crashes = getCrashFiles(context);
        report.append("=== Crashes (").append(crashes.length).append(", newest first) ===\n\n");
        for (File crash : crashes) {
            report.append(read(crash)).append('\n');
        }

        report.append("=== Log ===\n\n").append(readLog());
        return report.toString();
    }

    private static void saveCrash(Context context, Thread thread, Throwable throwable) throws IOException {
        File directory = new File(context.getFilesDir(), CRASH_DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            return;
        }

        StringWriter trace = new StringWriter();
        PrintWriter writer = new PrintWriter(trace);
        writer.println("Crash at " + now() + " on thread " + thread.getName() + ", app " + getAppVersion(context));
        throwable.printStackTrace(writer);
        writer.flush();

        OutputStream out = new FileOutputStream(new File(directory, "crash-" + System.currentTimeMillis() + ".txt"));
        try {
            out.write(trace.toString().getBytes("UTF-8"));
        } finally {
            out.close();
        }

        File[] crashes = getCrashFiles(context);
        for (int i = KEEP_CRASHES; i < crashes.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            crashes[i].delete();
        }
    }

    private static File[] getCrashFiles(Context context) {
        File[] files = new File(context.getFilesDir(), CRASH_DIRECTORY).listFiles();
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File first, File second) {
                return Long.compare(second.lastModified(), first.lastModified());
            }
        });
        return files;
    }

    /**
     * Apps can read their own entries in the system log, including the crash buffer, which also has the crashes of
     * earlier runs of the app.
     */
    private static String readLog() {
        try {
            Process process = new ProcessBuilder("logcat", "-d", "-v", "threadtime",
                    "-b", "main", "-b", "system", "-b", "crash", "-t", String.valueOf(LOG_LINES))
                    .redirectErrorStream(true)
                    .start();
            try {
                return readAtMost(process.getInputStream(), MAX_LOG_SIZE);
            } finally {
                process.destroy();
            }
        } catch (IOException e) {
            return "Unable to read the log: " + e + "\n";
        }
    }

    private static String read(File file) {
        try {
            InputStream in = new FileInputStream(file);
            try {
                return readAtMost(in, MAX_LOG_SIZE);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return "Unable to read " + file.getName() + ": " + e + "\n";
        }
    }

    private static String readAtMost(InputStream in, int maxSize) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1 && out.size() < maxSize) {
            out.write(buffer, 0, read);
        }
        return out.toString("UTF-8");
    }

    private static String getAppVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date());
    }
}
