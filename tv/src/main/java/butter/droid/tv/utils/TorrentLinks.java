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
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a streamable torrent link in text that was typed, pasted or shared.
 */
public final class TorrentLinks {

    private static final String MAGNET_PREFIX = "magnet:";
    private static final String ENCODED_MAGNET_PREFIX = "magnet%3a";

    private static final Pattern MAGNET = Pattern.compile("magnet:\\?[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern INFO_HASH_URN = Pattern.compile("urn:btih:([0-9a-f]{40}|[a-z2-7]{32})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEB_URL = Pattern.compile("(https?)://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern INFO_HASH = Pattern.compile("[0-9a-f]{40}|[a-z2-7]{32}", Pattern.CASE_INSENSITIVE);

    private TorrentLinks() {
    }

    /**
     * @param text anything the user typed, pasted or shared
     * @return a magnet link or an http(s) link to a .torrent file, or null if the text contains neither. A bare
     * info hash is turned into a magnet link.
     */
    public static String extract(String text) {
        if (text == null) {
            return null;
        }

        text = text.trim();
        if (text.toLowerCase(Locale.US).startsWith(ENCODED_MAGNET_PREFIX)) {
            // some apps hand over the whole link percent-encoded
            text = percentDecode(text);
        }
        if (text.toLowerCase(Locale.US).startsWith(MAGNET_PREFIX)) {
            // magnet links never contain whitespace, but they get wrapped when copied from chats and emails
            text = text.replaceAll("\\s+", "");
        }

        Matcher magnet = MAGNET.matcher(text);
        if (magnet.find()) {
            // the streamer only recognises a lower case scheme
            return MAGNET_PREFIX + magnet.group().substring(MAGNET_PREFIX.length());
        }

        Matcher urn = INFO_HASH_URN.matcher(text);
        if (urn.find()) {
            return toMagnet(urn.group(1));
        }

        Matcher url = WEB_URL.matcher(text);
        if (url.find()) {
            return url.group(1).toLowerCase(Locale.US) + url.group().substring(url.group(1).length());
        }

        if (INFO_HASH.matcher(text).matches()) {
            return toMagnet(text);
        }

        return null;
    }

    public static boolean isMagnet(String link) {
        return link != null && link.startsWith(MAGNET_PREFIX);
    }

    private static String toMagnet(String infoHash) {
        return MAGNET_PREFIX + "?xt=urn:btih:" + infoHash;
    }

    /**
     * Like URLDecoder, but keeps '+' as is: in a magnet link it is part of the value.
     */
    private static String percentDecode(String text) {
        Charset utf8 = Charset.forName("UTF-8");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(text.length());
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '%' && i + 2 < text.length()) {
                int high = Character.digit(text.charAt(i + 1), 16);
                int low = Character.digit(text.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    bytes.write(high << 4 | low);
                    i += 3;
                    continue;
                }
            }
            int length = Character.charCount(text.codePointAt(i));
            byte[] encoded = text.substring(i, i + length).getBytes(utf8);
            bytes.write(encoded, 0, encoded.length);
            i += length;
        }
        return new String(bytes.toByteArray(), utf8);
    }
}
