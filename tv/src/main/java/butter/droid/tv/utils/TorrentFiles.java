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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * Keeps local copies of .torrent files the streamer can't read where they are, such as files behind a content://
 * uri or uploaded from another device. The streamer only reads them once, so only the latest few are kept.
 */
public final class TorrentFiles {

    public static final long MAX_SIZE = 10 * 1024 * 1024;

    private static final int KEEP_FILES = 5;
    private static final String EXTENSION = ".torrent";
    private static final byte[] INFO_KEY = {'4', ':', 'i', 'n', 'f', 'o'};

    private TorrentFiles() {
    }

    public static class InvalidTorrentException extends IOException {

        private final boolean mTooLarge;

        InvalidTorrentException(boolean tooLarge) {
            super(tooLarge ? "File is too large to be a torrent" : "File is not a torrent");
            mTooLarge = tooLarge;
        }

        public boolean isTooLarge() {
            return mTooLarge;
        }
    }

    /**
     * Saves a .torrent file into {@code directory}.
     *
     * @throws InvalidTorrentException if the data is not a torrent file
     */
    public static File save(InputStream in, File directory) throws IOException {
        byte[] data = read(in);
        if (!isTorrent(data)) {
            throw new InvalidTorrentException(false);
        }

        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create " + directory);
        }

        File file = File.createTempFile("torrent", EXTENSION, directory);
        OutputStream out = new FileOutputStream(file);
        try {
            out.write(data);
        } finally {
            out.close();
        }

        deleteOldFiles(directory);
        return file;
    }

    /**
     * @return the link the streamer opens {@code file} with
     */
    public static String toLink(File file) {
        return "file://" + file.getAbsolutePath();
    }

    public static boolean hasTorrentExtension(String name) {
        return name != null && name.toLowerCase(Locale.US).endsWith(EXTENSION);
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_SIZE) {
                throw new InvalidTorrentException(true);
            }
        }
        return out.toByteArray();
    }

    /**
     * A torrent file is a bencoded dictionary with an info dictionary in it.
     */
    private static boolean isTorrent(byte[] data) {
        return data.length > 0 && data[0] == 'd' && indexOf(data, INFO_KEY) != -1;
    }

    private static int indexOf(byte[] data, byte[] part) {
        outer:
        for (int i = 0; i <= data.length - part.length; i++) {
            for (int j = 0; j < part.length; j++) {
                if (data[i + j] != part[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void deleteOldFiles(File directory) {
        File[] files = directory.listFiles();
        if (files == null || files.length <= KEEP_FILES) {
            return;
        }

        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File first, File second) {
                return Long.compare(second.lastModified(), first.lastModified());
            }
        });
        for (int i = KEEP_FILES; i < files.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }
}
