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

package butter.droid.tv.remote;

import com.github.se_bastiaan.torrentstreamserver.nanohttpd.NanoHTTPD;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import butter.droid.tv.utils.TorrentFiles;
import butter.droid.tv.utils.TorrentLinks;

/**
 * Serves a page on the local network where a phone or computer can paste a magnet link or upload a .torrent file,
 * so nobody has to type one with a TV remote.
 */
public class RemoteLinkServer extends NanoHTTPD {

    static final String PATH_PAGE = "/";
    static final String PATH_SEND = "/send";
    static final String FIELD_LINK = "link";
    static final String FIELD_TORRENT = "torrent";

    private static final long MAX_REQUEST_SIZE = TorrentFiles.MAX_SIZE + 64 * 1024;
    private static final String MIME_JSON = "application/json";
    private static final String MIME_HTML_UTF8 = MIME_HTML + "; charset=utf-8";

    public interface Listener {
        /**
         * Called on a server thread with a magnet, http(s) or file link.
         */
        void onLinkReceived(String link);
    }

    /**
     * Status messages shown on the page, translated by the caller.
     */
    public static class Messages {
        String sent;
        String noLink;
        String notTorrent;
        String tooLarge;
        String failed;

        public Messages(String sent, String noLink, String notTorrent, String tooLarge, String failed) {
            this.sent = sent;
            this.noLink = noLink;
            this.notTorrent = notTorrent;
            this.tooLarge = tooLarge;
            this.failed = failed;
        }
    }

    private final String mPage;
    private final Messages mMessages;
    private final File mTorrentDirectory;
    private final Listener mListener;

    /**
     * @param page             html of the page, with {{status}} and {{status_class}} where a message goes
     * @param torrentDirectory where uploaded .torrent files are kept
     */
    public RemoteLinkServer(int port, String page, Messages messages, File torrentDirectory, Listener listener) {
        super(port);
        mPage = page;
        mMessages = messages;
        mTorrentDirectory = torrentDirectory;
        mListener = listener;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String path = session.getUri();
        Method method = session.getMethod();

        if (PATH_PAGE.equals(path) && (Method.GET.equals(method) || Method.HEAD.equals(method))) {
            return page(Response.Status.OK, null, false);
        }
        if (PATH_SEND.equals(path) && Method.POST.equals(method)) {
            return send(session);
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
    }

    private Response send(IHTTPSession session) {
        boolean json = wantsJson(session);

        if (!isSameOrigin(session)) {
            // a page on some other site tried to make the TV play something
            return result(json, Response.Status.FORBIDDEN, mMessages.failed, false);
        }
        if (getContentLength(session) > MAX_REQUEST_SIZE) {
            return result(json, Response.Status.PAYLOAD_TOO_LARGE, mMessages.tooLarge, false);
        }

        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
        } catch (ResponseException e) {
            // also what NanoHTTPD makes of a form sent without choosing a file
            return result(json, Response.Status.BAD_REQUEST, mMessages.noLink, false);
        } catch (IOException e) {
            return result(json, Response.Status.INTERNAL_ERROR, mMessages.failed, false);
        }

        String link;
        String torrentPath = files.get(FIELD_TORRENT);
        if (torrentPath != null && !torrentPath.isEmpty()) {
            try {
                link = saveTorrent(torrentPath);
            } catch (TorrentFiles.InvalidTorrentException e) {
                return result(json, Response.Status.BAD_REQUEST, e.isTooLarge() ? mMessages.tooLarge : mMessages.notTorrent, false);
            } catch (IOException e) {
                return result(json, Response.Status.INTERNAL_ERROR, mMessages.failed, false);
            }
        } else {
            List<String> text = session.getParameters().get(FIELD_LINK);
            link = text == null || text.isEmpty() ? null : TorrentLinks.extract(text.get(0));
        }

        if (link == null) {
            return result(json, Response.Status.BAD_REQUEST, mMessages.noLink, false);
        }

        mListener.onLinkReceived(link);
        return result(json, Response.Status.OK, mMessages.sent, true);
    }

    private String saveTorrent(String uploadedPath) throws IOException {
        // the upload is a temporary file which is deleted once the response is sent
        InputStream in = new FileInputStream(uploadedPath);
        try {
            return TorrentFiles.toLink(TorrentFiles.save(in, mTorrentDirectory));
        } finally {
            in.close();
        }
    }

    private Response result(boolean json, Response.Status status, String message, boolean success) {
        if (json) {
            String body = "{\"ok\":" + success + ",\"message\":\"" + escapeJson(message) + "\"}";
            return newFixedLengthResponse(status, MIME_JSON, body);
        }
        return page(status, message, success);
    }

    private Response page(Response.Status status, String message, boolean success) {
        String html = mPage
                .replace("{{status_class}}", message == null ? "" : success ? "ok" : "error")
                .replace("{{status}}", message == null ? "" : escapeHtml(message));
        Response response = newFixedLengthResponse(status, MIME_HTML_UTF8, html);
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private static boolean wantsJson(IHTTPSession session) {
        String accept = session.getHeaders().get("accept");
        return accept != null && accept.contains(MIME_JSON);
    }

    /**
     * Browsers always send an origin with a POST from a page, so only requests from our own page get through.
     */
    private static boolean isSameOrigin(IHTTPSession session) {
        String origin = session.getHeaders().get("origin");
        String host = session.getHeaders().get("host");
        return origin == null || (host != null && origin.equalsIgnoreCase("http://" + host));
    }

    private static long getContentLength(IHTTPSession session) {
        String length = session.getHeaders().get("content-length");
        if (length == null) {
            return 0;
        }
        try {
            return Long.parseLong(length.trim());
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    static String escapeHtml(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    escaped.append("&amp;");
                    break;
                case '<':
                    escaped.append("&lt;");
                    break;
                case '>':
                    escaped.append("&gt;");
                    break;
                case '"':
                    escaped.append("&quot;");
                    break;
                case '\'':
                    escaped.append("&#39;");
                    break;
                default:
                    escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static String escapeJson(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                escaped.append('\\').append(c);
            } else if (c < 0x20) {
                escaped.append(String.format("\\u%04x", (int) c));
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
