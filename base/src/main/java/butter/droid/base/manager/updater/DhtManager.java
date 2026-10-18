package butter.droid.base.manager.updater;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.se_bastiaan.torrentstream.TorrentStream;
import com.github.se_bastiaan.torrentstream.exceptions.NotInitializedException;

import org.json.JSONException;
import org.libtorrent4j.Hex;
import org.libtorrent4j.SessionManager;

import java.io.IOException;
import java.util.ArrayList;

import javax.inject.Inject;

import butter.droid.base.R;
import butter.droid.base.content.preferences.Prefs;
import butter.droid.base.utils.PrefUtils;

public class DhtManager implements Runnable {

    private Context mContext;
    private ObjectMapper mMapper;

    private static final int UPDATE_INTERVAL = 7 * 24 * 60 * 60;

    @Inject
    public DhtManager(Context context, ObjectMapper mapper)
    {
        this.mContext = context;
        this.mMapper = mapper;
    }

    public void updateSettings()
    {
        long updated = PrefUtils.get(mContext, Prefs.DHT_UPDATED, 0L);
        if (updated + UPDATE_INTERVAL > System.currentTimeMillis() / 1000) {
            return;
        }

        final SessionManager sm;
        try {
            sm = TorrentStream.getInstance().getSessionManager();
        } catch (NotInitializedException e) {
            Log.d("DHT", "NotInitializedException");
            final Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(this, 5000);
            return;
        }

        // asking the DHT waits for an answer for up to 20 seconds, which froze the app on the main thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                updateFromDHT(sm);
            }
        }, "DHT update").start();
    }

    @Override
    public void run() {
        this.updateSettings();
    }

    protected void updateFromDHT(SessionManager sm) {
        Log.d("DHT", "DHT contains " + sm.stats().dhtNodes() + " nodes");
        String dhtPublicKey = mContext.getResources().getString(R.string.dht_public_key);
        SessionManager.MutableItem data = sm.dhtGetItem(Hex.decode(dhtPublicKey), Hex.decode(""), 20);
        if (data == null || data.item == null) {
            // no answer in time, the next start asks again
            Log.d("DHT", "No answer from the DHT");
            return;
        }

        // checked, so the next check is in a week, whether or not anything changed
        PrefUtils.save(mContext, Prefs.DHT_UPDATED, System.currentTimeMillis() / 1000);
        long seq = PrefUtils.get(mContext, Prefs.DHT_SEQ, 0L);
        if (data.seq <= seq) {
            return;
        }
        Log.d("DHT", "DHT updated " + data.seq);
        PrefUtils.save(mContext, Prefs.DHT_DATA, data.item.string());
        PrefUtils.save(mContext, Prefs.DHT_SEQ, data.seq);
    }

    public String[] servers()
    {
        String dht = PrefUtils.get(mContext, Prefs.DHT_DATA, "");
        if (dht.isEmpty()) {
            return new String[0];
        }
        try {
            DhtObject obj = mMapper.readValue(dht, DhtObject.class);
            return obj.getServers();
        } catch (JsonMappingException e) {
            return new String[0];
        } catch (JsonParseException e) {
            return new String[0];
        } catch (IOException e) {
            return new String[0];
        }
    }
}
