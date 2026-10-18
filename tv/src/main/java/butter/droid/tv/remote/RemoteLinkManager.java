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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import butter.droid.base.utils.LocaleUtils;
import butter.droid.tv.R;
import butter.droid.tv.utils.AppLogs;
import butter.droid.tv.utils.TorrentLinkOpener;
import timber.log.Timber;

/**
 * Runs the {@link RemoteLinkServer} while the app is in the foreground and opens the links it receives.
 */
public class RemoteLinkManager {

    public static final int DEFAULT_PORT = 8090;

    private static final int PORT_ATTEMPTS = 10;
    private static final int SOCKET_READ_TIMEOUT = 5000;
    private static final String PAGE_ASSET = "remote/index.html";

    public interface Listener {
        /**
         * Called on the main thread when the server starts or stops.
         */
        void onServerStateChanged();
    }

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    // starting and stopping touches sockets, which isn't allowed on the main thread
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();
    private volatile RemoteLinkServer mServer;

    private final RemoteLinkServer.Listener mLinkListener = new RemoteLinkServer.Listener() {
        @Override
        public void onLinkReceived(final String link) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    TorrentLinkOpener.open(mContext, link);
                }
            });
        }
    };

    private final RemoteLinkServer.Logs mLogs = new RemoteLinkServer.Logs() {
        @Override
        public String collect() {
            return AppLogs.collect(mContext);
        }
    };

    public RemoteLinkManager(Context context) {
        mContext = context.getApplicationContext();
    }

    public void start() {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (mServer != null) {
                    return;
                }

                String page;
                try {
                    page = loadPage();
                } catch (IOException e) {
                    Timber.e(e, "Unable to load remote link page");
                    return;
                }

                RemoteLinkServer.Messages messages = new RemoteLinkServer.Messages(
                        mContext.getString(R.string.remote_sent),
                        mContext.getString(R.string.remote_no_link),
                        mContext.getString(R.string.remote_not_torrent),
                        mContext.getString(R.string.remote_too_large),
                        mContext.getString(R.string.remote_failed));

                // the address is easier to remember when it stays the same, so only move on when the port is taken
                for (int port = DEFAULT_PORT; port < DEFAULT_PORT + PORT_ATTEMPTS; port++) {
                    RemoteLinkServer server = new RemoteLinkServer(port, page, messages,
                            TorrentLinkOpener.getTorrentDirectory(mContext), mLinkListener, mLogs);
                    try {
                        server.start(SOCKET_READ_TIMEOUT, true);
                    } catch (IOException e) {
                        Timber.d("Port %d is not available for remote links", port);
                        continue;
                    }
                    mServer = server;
                    notifyStateChanged();
                    return;
                }
                Timber.w("Unable to start remote link server");
            }
        });
    }

    public void stop() {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (mServer == null) {
                    return;
                }
                mServer.stop();
                mServer = null;
                notifyStateChanged();
            }
        });
    }

    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    /**
     * @return the address to open on another device, or null when the server isn't running or the TV has no network
     */
    @Nullable
    public String getAddress() {
        RemoteLinkServer server = mServer;
        if (server == null) {
            return null;
        }

        String host = getLocalAddress();
        if (host == null) {
            return null;
        }
        return "http://" + host + ":" + server.getListeningPort();
    }

    private void notifyStateChanged() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : mListeners) {
                    listener.onServerStateChanged();
                }
            }
        });
    }

    private String loadPage() throws IOException {
        Map<String, String> texts = new LinkedHashMap<>();
        String appName = mContext.getString(R.string.app_name);
        texts.put("lang", LocaleUtils.getCurrent().getLanguage());
        texts.put("title", mContext.getString(R.string.remote_page_title, appName));
        texts.put("heading", mContext.getString(R.string.remote_page_title, appName));
        texts.put("intro", mContext.getString(R.string.remote_page_intro));
        texts.put("link_label", mContext.getString(R.string.remote_page_link_label));
        texts.put("link_hint", mContext.getString(R.string.remote_page_link_hint));
        texts.put("send", mContext.getString(R.string.remote_page_send));
        texts.put("or", mContext.getString(R.string.remote_page_or));
        texts.put("choose_file", mContext.getString(R.string.remote_page_choose_file));
        texts.put("sending", mContext.getString(R.string.remote_page_sending));
        texts.put("failed", mContext.getString(R.string.remote_failed));
        texts.put("logs", mContext.getString(R.string.remote_page_logs));
        texts.put("logs_download", mContext.getString(R.string.remote_page_logs_download));

        String page = readAsset(PAGE_ASSET);
        for (Map.Entry<String, String> text : texts.entrySet()) {
            page = page.replace("{{" + text.getKey() + "}}", RemoteLinkServer.escapeHtml(text.getValue()));
        }
        return page;
    }

    private String readAsset(String name) throws IOException {
        InputStream in = mContext.getAssets().open(name);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    /**
     * TVs are often wired, so this looks at every network interface rather than asking the WifiManager. Addresses
     * on the local network win over others, such as the one of a VPN.
     */
    @Nullable
    private static String getLocalAddress() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return null;
            }

            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }

                boolean vpn = networkInterface.getName().startsWith("tun") || networkInterface.getName().startsWith("ppp");
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isLinkLocalAddress()) {
                        continue;
                    }
                    if (!vpn && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                    if (fallback == null) {
                        fallback = address.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            Timber.w(e, "Unable to list network interfaces");
        }
        return fallback;
    }
}
