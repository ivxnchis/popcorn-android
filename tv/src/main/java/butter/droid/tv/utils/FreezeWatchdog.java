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
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.Locale;

/**
 * Notices when the app freezes and saves what it was doing then for {@link AppLogs}. The main thread draws the
 * screen and handles the remote, so the app is frozen while it doesn't get to run a message posted to it.
 */
public final class FreezeWatchdog {

    private static final String TAG = "FreezeWatchdog";

    // long enough to be noticed, and Android only steps in after 5 seconds
    private static final long FREEZE_MS = 2000;
    private static final long CHECK_INTERVAL_MS = 500;
    private static final long POLL_MS = 100;
    // the main thread can be stuck in more than one place during a long freeze
    private static final long SAMPLE_INTERVAL_MS = 3000;
    private static final int MAX_SAMPLES = 5;

    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private Thread mThread;

    public FreezeWatchdog(Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * Called on the main thread.
     */
    public void start() {
        if (mThread != null) {
            return;
        }
        mThread = new Thread(new Watcher(), "Freeze watchdog");
        mThread.start();
    }

    /**
     * Called on the main thread.
     */
    public void stop() {
        if (mThread == null) {
            return;
        }
        mThread.interrupt();
        mThread = null;
    }

    private class Watcher implements Runnable {

        // the last tick the main thread ran
        private volatile long mAnswered;

        @Override
        public void run() {
            long tick = 0;
            try {
                while (true) {
                    final long thisTick = ++tick;
                    long posted = SystemClock.uptimeMillis();
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mAnswered = thisTick;
                        }
                    });
                    Thread.sleep(CHECK_INTERVAL_MS);

                    Freeze freeze = null;
                    while (mAnswered != thisTick) {
                        long frozenFor = SystemClock.uptimeMillis() - posted;
                        // a debugger stops the main thread at breakpoints
                        if (freeze == null && frozenFor >= FREEZE_MS && !Debug.isDebuggerConnected()) {
                            freeze = new Freeze(frozenFor);
                        } else if (freeze != null && freeze.wantsSample(frozenFor)) {
                            freeze.sample(frozenFor);
                        }
                        Thread.sleep(POLL_MS);
                    }
                    if (freeze != null) {
                        freeze.recovered(SystemClock.uptimeMillis() - posted);
                    }
                }
            } catch (InterruptedException e) {
                // stopped
            }
        }
    }

    /**
     * The report of one freeze, saved again whenever there's more to it, so it's kept even when the app is closed
     * before it responds again.
     */
    private class Freeze {

        private final File mFile;
        private final StringBuilder mReport = new StringBuilder();
        private int mSamples = 0;

        Freeze(long frozenFor) {
            mFile = AppLogs.newFreezeFile(mContext);
            mReport.append("Froze at ").append(AppLogs.formatTime(System.currentTimeMillis() - frozenFor))
                    .append(" on ").append(AppLogs.getScreen())
                    .append(", app ").append(AppLogs.getAppVersion(mContext)).append('\n');
            Log.w(TAG, "Not responding for " + seconds(frozenFor) + " on " + AppLogs.getScreen());
            sample(frozenFor);
        }

        boolean wantsSample(long frozenFor) {
            return mSamples < MAX_SAMPLES && frozenFor >= FREEZE_MS + mSamples * SAMPLE_INTERVAL_MS;
        }

        void sample(long frozenFor) {
            mSamples++;
            mReport.append("Not responding for ").append(seconds(frozenFor)).append(", the main thread was at:\n");
            for (StackTraceElement element : Looper.getMainLooper().getThread().getStackTrace()) {
                mReport.append("\tat ").append(element).append('\n');
            }
            save("Still not responding when this was saved");
        }

        void recovered(long frozenFor) {
            Log.w(TAG, "Responding again after " + seconds(frozenFor));
            save("Responding again after " + seconds(frozenFor));
        }

        private void save(String state) {
            AppLogs.write(mFile, mReport + state + "\n");
        }
    }

    private static String seconds(long millis) {
        return String.format(Locale.US, "%.1f s", millis / 1000f);
    }
}
