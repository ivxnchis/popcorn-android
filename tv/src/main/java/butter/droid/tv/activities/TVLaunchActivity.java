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

package butter.droid.tv.activities;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import butter.droid.base.content.preferences.Prefs;
import butter.droid.base.utils.PrefUtils;
import butter.droid.tv.service.RecommendationService;
import butter.droid.tv.utils.TorrentLinkOpener;

public class TVLaunchActivity extends Activity {

	private static final int PERMISSIONS_REQUEST = 1232;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		Intent recommendationIntent = new Intent(this, RecommendationService.class);
		startService(recommendationIntent);

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSIONS_REQUEST);
			return;
		}

		proceedCreate();
	}

	private void proceedCreate() {
		//opened with a .torrent file, e.g. from a browser's downloads
		Uri torrentFile = TorrentLinkOpener.getTorrentFileUri(getIntent());
		if (torrentFile != null) {
			//we may only read the file until this activity finishes
			TorrentLinkOpener.openTorrentFile(this, torrentFile, new Runnable() {
				@Override
				public void run() {
					finish();
				}
			});
			return;
		}

		//opened with a magnet link, a link to a .torrent file or a shared text containing one
		String link = TorrentLinkOpener.getLink(getIntent());
		if (link != null) {
			TorrentLinkOpener.open(this, link);
			finish();
			return;
		}

		Boolean firstRun = PrefUtils.get(this, Prefs.FIRST_RUN, true);

		if (firstRun) {
			//run the welcome wizard
			TVWelcomeActivity.startActivity(this);
		} else {
			TVMainActivity.startActivity(this);
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
		switch (requestCode) {
			case PERMISSIONS_REQUEST: {
				if (grantResults.length == 2 && grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
					proceedCreate();
				} else {
					finish();
				}
			}
		}
	}

}
