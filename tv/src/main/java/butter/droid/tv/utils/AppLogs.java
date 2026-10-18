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

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

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
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * What it takes to find out why the app misbehaved on someone's TV: crashes and freezes are kept across restarts,
 * and together with the app's log they can be read from another device.
 */
public final class AppLogs {

    private static final String TAG = "AppLogs";

    private static final String CRASH_DIRECTORY = "crash-logs";
    private static final String FREEZE_DIRECTORY = "freeze-logs";
    private static final int KEEP_CRASHES = 5;
    private static final int KEEP_FREEZES = 10;
    private static final int KEEP_EXITS = 10;
    private static final int LOG_LINES = 3000;
    private static final int MAX_LOG_SIZE = 2 * 1024 * 1024;
    private static final int MAX_TRACE_SIZE = 512 * 1024;
    // ApplicationExitInfo.REASON_ANR
    private static final int REASON_ANR = 6;

    private static volatile String sScreen = "no screen yet";

    private AppLogs() {
    }

    /**
     * Saves the stack trace of crashes, then lets them crash the app as before, and notes which screen is showing.
     */
    public static void install(final Application application) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    saveCrash(application, thread, throwable);
                } catch (Throwable ignored) {
                    // never get in the way of the crash itself
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
            }
        });
        application.registerActivityLifecycleCallbacks(new ScreenTracker());
    }

    /**
     * @return the device and app version, the last crashes and freezes, why the app closed and its recent log
     */
    public static String collect(Context context) {
        StringBuilder report = new StringBuilder();
        report.append("App: ").append(getAppVersion(context)).append('\n');
        report.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")")
                .append(", ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        report.append("Collected: ").append(formatTime(System.currentTimeMillis())).append("\n\n");

        appendReports(report, "Crashes", getReportFiles(context, CRASH_DIRECTORY));
        appendReports(report, "Freezes", getReportFiles(context, FREEZE_DIRECTORY));
        report.append("=== Why the app closed, newest first ===\n\n").append(readExitReasons(context)).append('\n');
        report.append("=== Log ===\n\n").append(readLog());
        return report.toString();
    }

    /**
     * @return the file to save a new freeze report in, making room for it
     */
    static File newFreezeFile(Context context) {
        return newReportFile(context, FREEZE_DIRECTORY, "freeze-", KEEP_FREEZES);
    }

    static void write(File file, String text) {
        try {
            OutputStream out = new FileOutputStream(file);
            try {
                out.write(text.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "Unable to save " + file.getName(), e);
        }
    }

    static String getScreen() {
        return sScreen;
    }

    static String getAppVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    static String formatTime(long time) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date(time));
    }

    private static void saveCrash(Context context, Thread thread, Throwable throwable) {
        StringWriter trace = new StringWriter();
        PrintWriter writer = new PrintWriter(trace);
        writer.println("Crash at " + formatTime(System.currentTimeMillis()) + " on " + sScreen + ", thread "
                + thread.getName() + ", app " + getAppVersion(context));
        throwable.printStackTrace(writer);
        writer.flush();

        write(newReportFile(context, CRASH_DIRECTORY, "crash-", KEEP_CRASHES), trace.toString());
    }

    private static File newReportFile(Context context, String directoryName, String prefix, int keep) {
        File directory = new File(context.getFilesDir(), directoryName);
        // saving fails later if it can't be made
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();

        File[] reports = getReportFiles(context, directoryName);
        for (int i = keep - 1; i < reports.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            reports[i].delete();
        }
        return new File(directory, prefix + System.currentTimeMillis() + ".txt");
    }

    /**
     * @return the reports, newest first, by the time in their names, as freeze reports change after they're made
     */
    private static File[] getReportFiles(Context context, String directoryName) {
        File[] files = new File(context.getFilesDir(), directoryName).listFiles();
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, Collections.reverseOrder());
        return files;
    }

    private static void appendReports(StringBuilder report, String title, File[] files) {
        report.append("=== ").append(title).append(" (").append(files.length).append(", newest first) ===\n\n");
        for (File file : files) {
            report.append(read(file)).append('\n');
        }
    }

    /**
     * Android 11 and later keep why the app's last runs ended, which also tells what the app can't see for itself:
     * being closed for not responding, crashes in native code and running out of memory.
     */
    private static String readExitReasons(Context context) {
        if (Build.VERSION.SDK_INT < 30) {
            return "Only kept by Android 11 and later\n";
        }

        StringBuilder reasons = new StringBuilder();
        try {
            // ApplicationExitInfo is newer than the SDK the app is compiled with
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<?> exits = (List<?>) ActivityManager.class
                    .getMethod("getHistoricalProcessExitReasons", String.class, int.class, int.class)
                    .invoke(activityManager, context.getPackageName(), 0, KEEP_EXITS);
            boolean traceAdded = false;
            for (Object exit : exits) {
                // describes itself with the time, the reason and what Android has to say about it
                reasons.append(exit).append('\n');

                int reason = (Integer) exit.getClass().getMethod("getReason").invoke(exit);
                if (reason == REASON_ANR && !traceAdded) {
                    InputStream trace = (InputStream) exit.getClass().getMethod("getTraceInputStream").invoke(exit);
                    if (trace != null) {
                        try {
                            reasons.append("Threads when it stopped responding:\n")
                                    .append(readAtMost(trace, MAX_TRACE_SIZE)).append('\n');
                        } finally {
                            trace.close();
                        }
                        traceAdded = true;
                    }
                }
            }
            if (exits.isEmpty()) {
                reasons.append("None kept\n");
            }
        } catch (Exception e) {
            reasons.append("Unable to read them: ").append(e).append('\n');
        }
        return reasons.toString();
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

    /**
     * Puts the screen in the log, crashes and freezes, as knowing where helps as much as knowing what.
     */
    private static class ScreenTracker implements Application.ActivityLifecycleCallbacks {

        @Override
        public void onActivityResumed(Activity activity) {
            sScreen = activity.getClass().getSimpleName();
            Log.i(TAG, "Showing " + sScreen);
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }
}
