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

package butter.droid.tv;

import android.content.Context;
import android.util.Log;

import com.sjl.foreground.Foreground;

import butter.droid.base.BaseApplicationModule;
import butter.droid.base.ButterApplication;
import butter.droid.base.utils.VersionUtils;
import butter.droid.tv.remote.RemoteLinkManager;
import butter.droid.tv.utils.AppLogs;
import timber.log.Timber;

public class TVButterApplication extends ButterApplication {

    private ApplicationComponent component;
    private RemoteLinkManager remoteLinkManager;

    // Foreground only keeps a weak reference to its listeners
    private final Foreground.Listener foregroundListener = new Foreground.Listener() {
        @Override
        public void onBecameForeground() {
            remoteLinkManager.start();
        }

        @Override
        public void onBecameBackground() {
            remoteLinkManager.stop();
        }
    };

    public static TVButterApplication getAppContext() {
        return (TVButterApplication) ButterApplication.getAppContext();
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }

    @Override
    public void onCreate() {
        // first, so crashes while starting are kept too
        AppLogs.install(this);
        if (!BuildConfig.DEBUG) {
            // debug builds log everything already, see ButterApplication
            Timber.plant(new Timber.DebugTree() {
                @Override
                protected boolean isLoggable(int priority) {
                    return priority >= Log.WARN;
                }
            });
        }

        component = DaggerApplicationComponent.builder()
                .baseApplicationModule(new BaseApplicationModule(this))
                .build();
        component.inject(this);
        super.onCreate();

        // lets phones and computers on the network send links while the app is open
        remoteLinkManager = new RemoteLinkManager(this);
        Foreground.get(this).addListener(foregroundListener);
    }

    @Override
    public void updateAvailable(String filePath) {
        if(!VersionUtils.isAndroidTV()) {
            super.updateAvailable(filePath);
        }
    }

    public ApplicationComponent getComponent() {
        return component;
    }

    public RemoteLinkManager getRemoteLinkManager() {
        return remoteLinkManager;
    }
}
