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

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcelable;
import android.widget.Toast;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import butter.droid.base.content.preferences.Prefs;
import butter.droid.base.torrent.StreamInfo;
import butter.droid.base.utils.PrefUtils;
import butter.droid.base.utils.ThreadUtils;
import butter.droid.tv.R;
import butter.droid.tv.activities.TVMainActivity;
import butter.droid.tv.activities.TVStreamLoadingActivity;
import butter.droid.tv.activities.TVWelcomeActivity;
import timber.log.Timber;

/**
 * Starts streaming magnet links and .torrent files, wherever they come from: another app, another device on the
 * network or the open link screen.
 */
public final class TorrentLinkOpener {

    private static final String TORRENT_DIRECTORY = "torrent-files";

    private TorrentLinkOpener() {
    }

    /**
     * Streams a magnet, http(s) or file link on top of the main screen, replacing whatever is playing.
     */
    public static void open(Context context, String link) {
        if (PrefUtils.get(context, Prefs.FIRST_RUN, true)) {
            // the terms have to be accepted first, the welcome screen opens the link afterwards
            TVWelcomeActivity.startActivity(context, link);
            return;
        }

        Intent main = new Intent(context, TVMainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Intent loading = TVStreamLoadingActivity.newIntent(context, new StreamInfo(link));
        context.startActivities(new Intent[]{main, loading});
    }

    /**
     * Copies a .torrent file the streamer can't read directly, such as one behind a content:// uri, then streams it.
     *
     * @param onDone run on the main thread once the file has been read, successfully or not
     */
    public static void openTorrentFile(Context context, final Uri uri, @Nullable final Runnable onDone) {
        final Context appContext = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String link = copyTorrentFile(appContext, uri);
                ThreadUtils.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (link != null) {
                            open(appContext, link);
                        } else {
                            Toast.makeText(appContext, R.string.torrent_file_error, Toast.LENGTH_LONG).show();
                        }
                        if (onDone != null) {
                            onDone.run();
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * @return where copies of .torrent files are kept
     */
    public static File getTorrentDirectory(Context context) {
        return new File(context.getCacheDir(), TORRENT_DIRECTORY);
    }

    /**
     * @return the content:// uri of a .torrent file another app wants to open, which has to be copied with
     * {@link #openTorrentFile(Context, Uri, Runnable)}
     */
    @Nullable
    public static Uri getTorrentFileUri(Intent intent) {
        Uri uri = getUri(intent);
        return uri != null && ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) ? uri : null;
    }

    /**
     * @return the magnet, http(s) or file link another app wants to open, or null if there is none
     */
    @Nullable
    public static String getLink(Intent intent) {
        Uri uri = getUri(intent);
        if (uri != null) {
            if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
                return uri.toString();
            }
            String link = TorrentLinks.extract(uri.toString());
            if (link != null) {
                return link;
            }
        }

        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            return TorrentLinks.extract(intent.getStringExtra(Intent.EXTRA_TEXT));
        }
        return null;
    }

    @Nullable
    private static Uri getUri(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            return intent.getData();
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Parcelable stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            return stream instanceof Uri ? (Uri) stream : null;
        }
        return null;
    }

    @Nullable
    private static String copyTorrentFile(Context context, Uri uri) {
        try {
            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            try {
                return TorrentFiles.toLink(TorrentFiles.save(in, getTorrentDirectory(context)));
            } finally {
                in.close();
            }
        } catch (IOException | SecurityException e) {
            Timber.w(e, "Unable to read torrent file %s", uri);
            return null;
        }
    }
}
