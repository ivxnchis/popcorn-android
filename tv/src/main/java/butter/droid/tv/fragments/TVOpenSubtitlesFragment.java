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

import android.os.Bundle;
import android.text.InputType;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.leanback.app.GuidedStepFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import java.util.List;

import javax.inject.Inject;

import butter.droid.base.content.preferences.Prefs;
import butter.droid.base.providers.subs.open.OpenSubsProvider;
import butter.droid.base.utils.PrefUtils;
import butter.droid.base.utils.ThreadUtils;
import butter.droid.tv.R;
import butter.droid.tv.TVButterApplication;

/**
 * Signs in to OpenSubtitles, so subtitles come with the account's download limits.
 */
public class TVOpenSubtitlesFragment extends GuidedStepFragment {

    @Inject
    OpenSubsProvider openSubsProvider;

    private Listener mListener;
    private String mUsername;
    // OpenSubtitles takes the hash, so the password itself is never kept
    private String mPasswordHash;
    private boolean mSigningIn = false;

    interface Listener {
        /**
         * Called after signing in or out.
         */
        void onAccountChanged();
    }

    public static TVOpenSubtitlesFragment newInstance(@NonNull Listener listener) {
        TVOpenSubtitlesFragment fragment = new TVOpenSubtitlesFragment();
        fragment.mListener = listener;
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        TVButterApplication.getAppContext()
                .getComponent()
                .inject(this);
        mUsername = PrefUtils.get(getActivity(), Prefs.OPENSUBTITLES_USERNAME, "");
        mPasswordHash = PrefUtils.get(getActivity(), Prefs.OPENSUBTITLES_PASSWORD, "");
        super.onCreate(savedInstanceState);
    }

    @NonNull
    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(getString(R.string.opensubtitles_account), getString(R.string.opensubtitles_account_description), getString(R.string.preferences), null);
    }

    @Override
    public void onCreateActions(@NonNull List<GuidedAction> actions, Bundle savedInstanceState) {
        actions.add(new GuidedAction.Builder(getActivity())
                .id(R.id.action_opensubtitles_username)
                .title(getString(R.string.opensubtitles_username))
                .description(getUsernameDescription())
                .descriptionEditable(true)
                .editDescription(mUsername)
                .descriptionEditInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                .build());
        actions.add(new GuidedAction.Builder(getActivity())
                .id(R.id.action_opensubtitles_password)
                .title(getString(R.string.opensubtitles_password))
                .description(getPasswordDescription())
                .descriptionEditable(true)
                .editDescription("")
                .descriptionEditInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                .build());
        actions.add(new GuidedAction.Builder(getActivity())
                .id(R.id.action_opensubtitles_sign_in)
                .title(getString(R.string.opensubtitles_sign_in))
                .build());
        actions.add(new GuidedAction.Builder(getActivity())
                .id(R.id.action_opensubtitles_sign_out)
                .title(getString(R.string.opensubtitles_sign_out))
                .description(getString(R.string.opensubtitles_sign_out_description))
                .enabled(!PrefUtils.get(getActivity(), Prefs.OPENSUBTITLES_USERNAME, "").isEmpty())
                .build());
        super.onCreateActions(actions, savedInstanceState);
    }

    @Override
    public long onGuidedActionEditedAndProceed(GuidedAction action) {
        CharSequence edited = action.getEditDescription();
        String text = edited == null ? "" : edited.toString();

        if (action.getId() == R.id.action_opensubtitles_username) {
            mUsername = text.trim();
            action.setEditDescription(mUsername);
            action.setDescription(getUsernameDescription());
        } else if (action.getId() == R.id.action_opensubtitles_password) {
            mPasswordHash = text.isEmpty() ? "" : OpenSubsProvider.hashPassword(text);
            // don't show what was typed when it's edited again
            action.setEditDescription("");
            action.setDescription(getPasswordDescription());
        }
        return GuidedAction.ACTION_ID_NEXT;
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        if (action.getId() == R.id.action_opensubtitles_sign_in) {
            signIn();
        } else if (action.getId() == R.id.action_opensubtitles_sign_out) {
            PrefUtils.remove(getActivity(), Prefs.OPENSUBTITLES_USERNAME);
            PrefUtils.remove(getActivity(), Prefs.OPENSUBTITLES_PASSWORD);
            Toast.makeText(getActivity(), R.string.opensubtitles_signed_out, Toast.LENGTH_SHORT).show();
            close();
        }
    }

    private void signIn() {
        if (mSigningIn) {
            return;
        }
        if (mUsername.isEmpty() || mPasswordHash.isEmpty()) {
            Toast.makeText(getActivity(), R.string.opensubtitles_enter_account, Toast.LENGTH_SHORT).show();
            return;
        }

        mSigningIn = true;
        Toast.makeText(getActivity(), R.string.opensubtitles_signing_in, Toast.LENGTH_SHORT).show();
        final String username = mUsername;
        final String passwordHash = mPasswordHash;
        openSubsProvider.checkLogin(username, passwordHash, new OpenSubsProvider.LoginCallback() {
            @Override
            public void onSignedIn() {
                ThreadUtils.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mSigningIn = false;
                        // saved even when the screen is gone by now, the account works
                        PrefUtils.save(TVButterApplication.getAppContext(), Prefs.OPENSUBTITLES_USERNAME, username);
                        PrefUtils.save(TVButterApplication.getAppContext(), Prefs.OPENSUBTITLES_PASSWORD, passwordHash);
                        if (isAdded()) {
                            Toast.makeText(getActivity(), R.string.opensubtitles_signed_in, Toast.LENGTH_SHORT).show();
                            close();
                        }
                    }
                });
            }

            @Override
            public void onWrongCredentials() {
                showFailure(R.string.opensubtitles_wrong_account);
            }

            @Override
            public void onFailure(Exception e) {
                showFailure(R.string.opensubtitles_unreachable);
            }
        });
    }

    private void showFailure(final int message) {
        ThreadUtils.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mSigningIn = false;
                if (isAdded()) {
                    Toast.makeText(getActivity(), message, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void close() {
        if (mListener != null) {
            mListener.onAccountChanged();
        }
        getFragmentManager().popBackStack();
    }

    private String getUsernameDescription() {
        return mUsername.isEmpty() ? getString(R.string.opensubtitles_not_set) : mUsername;
    }

    private String getPasswordDescription() {
        return mPasswordHash.isEmpty() ? getString(R.string.opensubtitles_not_set) : getString(R.string.opensubtitles_password_saved);
    }
}
