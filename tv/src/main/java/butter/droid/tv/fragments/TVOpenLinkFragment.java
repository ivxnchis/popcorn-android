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

package butter.droid.tv.fragments;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.app.GuidedStepFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import butter.droid.tv.R;
import butter.droid.tv.TVButterApplication;
import butter.droid.tv.remote.RemoteLinkManager;
import butter.droid.tv.utils.QrCode;
import butter.droid.tv.utils.TorrentFiles;
import butter.droid.tv.utils.TorrentLinkOpener;
import butter.droid.tv.utils.TorrentLinks;

/**
 * Every way to get a magnet link or .torrent file onto the TV without typing it with the remote.
 */
public class TVOpenLinkFragment extends GuidedStepFragment implements RemoteLinkManager.Listener {

    private static final int REQUEST_TORRENT_FILE = 1;
    private static final int MAX_TORRENT_FILES = 20;

    private RemoteLinkManager mRemoteLinkManager;
    // listed as sub actions, the id of which is the index in here
    private final List<File> mTorrentFiles = new ArrayList<>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        mRemoteLinkManager = TVButterApplication.getAppContext().getRemoteLinkManager();
        super.onCreate(savedInstanceState);
    }

    @NonNull
    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(getString(R.string.open_link_title), getString(R.string.open_link_description), getString(R.string.app_name), null);
    }

    @Override
    public GuidanceStylist onCreateGuidanceStylist() {
        return new OpenLinkGuidanceStylist();
    }

    @Override
    public void onCreateActions(@NonNull List<GuidedAction> actions, Bundle savedInstanceState) {
        actions.add(new GuidedAction.Builder(getActivity())
                .id(R.id.action_type_link)
                .title(getString(R.string.type_link))
                .description(getString(R.string.type_link_description))
                .multilineDescription(true)
                .editable(true)
                .editTitle("")
                .editInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI)
                .build());
        actions.add(new GuidedAction.Builder(getActivity())
                .id(R.id.action_paste_link)
                .title(getString(R.string.paste_link))
                .description(getString(R.string.paste_link_description))
                .build());
        actions.add(new GuidedAction.Builder(getActivity())
                .id(R.id.action_torrent_files)
                .title(getString(R.string.open_torrent_file))
                .build());
        super.onCreateActions(actions, savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        mRemoteLinkManager.addListener(this);
        updateRemoteAddress();
        updateTorrentFiles();
    }

    @Override
    public void onPause() {
        super.onPause();
        mRemoteLinkManager.removeListener(this);
    }

    @Override
    public void onServerStateChanged() {
        updateRemoteAddress();
    }

    @Override
    public long onGuidedActionEditedAndProceed(GuidedAction action) {
        if (action.getId() == R.id.action_type_link) {
            CharSequence text = action.getEditTitle();
            action.setEditTitle("");
            if (text != null && text.toString().trim().length() > 0) {
                open(text.toString(), R.string.invalid_link);
            }
            return GuidedAction.ACTION_ID_CURRENT;
        }
        return super.onGuidedActionEditedAndProceed(action);
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        if (action.getId() == R.id.action_paste_link) {
            open(getClipboardText(), R.string.clipboard_no_link);
        } else if (action.getId() == R.id.action_torrent_files && !action.hasSubActions()) {
            Toast.makeText(getActivity(), R.string.no_torrent_files, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onSubGuidedActionClicked(GuidedAction action) {
        if (action.getId() == R.id.action_browse_files) {
            startActivityForResult(getBrowseIntent(), REQUEST_TORRENT_FILE);
        } else if (action.getId() >= 0 && action.getId() < mTorrentFiles.size()) {
            // names in Downloads can contain anything, such as a # which would end the path
            Uri file = Uri.fromFile(mTorrentFiles.get((int) action.getId()));
            TorrentLinkOpener.open(getActivity(), file.toString());
        }
        return true;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_TORRENT_FILE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                TorrentLinkOpener.openTorrentFile(getActivity(), data.getData(), null);
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void open(@Nullable String text, int errorMessage) {
        String link = TorrentLinks.extract(text);
        if (link == null) {
            Toast.makeText(getActivity(), errorMessage, Toast.LENGTH_LONG).show();
            return;
        }
        TorrentLinkOpener.open(getActivity(), link);
    }

    private void updateRemoteAddress() {
        OpenLinkGuidanceStylist stylist = (OpenLinkGuidanceStylist) getGuidanceStylist();
        if (stylist.getDescriptionView() == null) {
            return;
        }

        String address = mRemoteLinkManager.getAddress();
        if (address == null) {
            stylist.getDescriptionView().setText(R.string.open_link_description_offline);
            stylist.getAddressView().setText(R.string.open_link_no_network);
            stylist.getIconView().setVisibility(View.GONE);
            return;
        }

        int size = getResources().getDimensionPixelSize(R.dimen.open_link_qr_size);
        stylist.getDescriptionView().setText(R.string.open_link_description);
        stylist.getAddressView().setText(address);
        stylist.getIconView().setImageBitmap(QrCode.create(address, size));
        stylist.getIconView().setVisibility(View.VISIBLE);
    }

    private void updateTorrentFiles() {
        mTorrentFiles.clear();
        mTorrentFiles.addAll(findTorrentFiles());

        List<GuidedAction> subActions = new ArrayList<>();
        for (int i = 0; i < mTorrentFiles.size(); i++) {
            File file = mTorrentFiles.get(i);
            subActions.add(new GuidedAction.Builder(getActivity())
                    .id(i)
                    .title(file.getName())
                    .description(DateUtils.getRelativeTimeSpanString(file.lastModified()))
                    .build());
        }
        if (getBrowseIntent().resolveActivity(getActivity().getPackageManager()) != null) {
            subActions.add(new GuidedAction.Builder(getActivity())
                    .id(R.id.action_browse_files)
                    .title(getString(R.string.browse_files))
                    .build());
        }

        GuidedAction action = findActionById(R.id.action_torrent_files);
        action.setSubActions(subActions.isEmpty() ? null : subActions);
        action.setDescription(mTorrentFiles.isEmpty()
                ? getString(R.string.no_torrent_files)
                : getResources().getQuantityString(R.plurals.torrent_files_in_downloads, mTorrentFiles.size(), mTorrentFiles.size()));
        notifyActionChanged(findActionPositionById(R.id.action_torrent_files));
    }

    /**
     * Browsers on the TV save .torrent files they can't open right away in Downloads.
     */
    private static List<File> findTorrentFiles() {
        List<File> torrentFiles = new ArrayList<>();
        File[] files = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).listFiles();
        if (files == null) {
            // no storage permission or no storage
            return torrentFiles;
        }

        for (File file : files) {
            if (file.isFile() && TorrentFiles.hasTorrentExtension(file.getName())) {
                torrentFiles.add(file);
            }
        }
        Collections.sort(torrentFiles, new Comparator<File>() {
            @Override
            public int compare(File first, File second) {
                return Long.compare(second.lastModified(), first.lastModified());
            }
        });
        return new ArrayList<>(torrentFiles.subList(0, Math.min(torrentFiles.size(), MAX_TORRENT_FILES)));
    }

    private static Intent getBrowseIntent() {
        return new Intent(Intent.ACTION_GET_CONTENT)
                .setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
    }

    @Nullable
    private String getClipboardText() {
        ClipboardManager clipboard = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(getActivity());
        return text == null ? null : text.toString();
    }

    public static class OpenLinkGuidanceStylist extends GuidanceStylist {

        private TextView mAddressView;

        @Override
        public int onProvideLayoutId() {
            return R.layout.guidance_open_link;
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Guidance guidance) {
            View view = super.onCreateView(inflater, container, guidance);
            mAddressView = (TextView) view.findViewById(R.id.guidance_address);
            return view;
        }

        TextView getAddressView() {
            return mAddressView;
        }
    }
}
