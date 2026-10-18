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

import android.graphics.Color;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.leanback.app.GuidedStepFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;
import android.view.View;
import android.widget.Toast;

import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

import butter.droid.base.content.preferences.PrefItem;
import butter.droid.base.content.preferences.Prefs;
import butter.droid.base.content.preferences.PreferencesHandler;
import butter.droid.base.manager.updater.ButterUpdateManager;
import butter.droid.base.utils.LocaleUtils;
import butter.droid.tv.R;
import butter.droid.tv.TVButterApplication;
import butter.droid.tv.activities.TVUpdateActivity;

public class TVPreferencesFragment extends GuidedStepFragment implements PreferencesHandler {

    @Inject
    ButterUpdateManager butterUpdateManager;

    private List<GuidedAction> mActions;
    private List<PrefItem> mPrefs;
    private int mLastSelectedActionIndex = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TVButterApplication.getAppContext()
                .getComponent()
                .inject(this);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setSelectedActionPosition(0);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        butterUpdateManager.setListener(new ButterUpdateManager.Listener() {
            @Override
            public void updateAvailable(String filePath) {
                TVUpdateActivity.startActivity(getActivity());
            }
        });
    }

    @NonNull
    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(getString(R.string.preferences), null, getString(R.string.app_name), null);
    }

    @Override
    public void onCreateActions(@NonNull List<GuidedAction> actions, Bundle savedInstanceState) {

        int index = 0;
        mPrefs = PreferencesHandler.ItemsGenerator.generate(getActivity(), this, butterUpdateManager, true);
        addOpenSubtitlesAccount(mPrefs);

        for(PrefItem item : mPrefs) {
            actions.add(generateAction(index, item));
            index++;
        }

        mActions = actions;

        super.onCreateActions(actions, savedInstanceState);
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        super.onGuidedActionClicked(action);
        mLastSelectedActionIndex = getSelectedActionPosition();
        mPrefs.get(mLastSelectedActionIndex).onClick();
        setSelectedActionPosition(mLastSelectedActionIndex);
    }

    /**
     * Only the TV app can sign in to OpenSubtitles, so it isn't among the preferences shared with the phone app.
     */
    private void addOpenSubtitlesAccount(List<PrefItem> prefs) {
        int position = prefs.size();
        for (int i = 0; i < prefs.size(); i++) {
            if (Prefs.SUBTITLE_DEFAULT.equals(prefs.get(i).getPrefKey())) {
                position = i + 1;
            }
        }

        prefs.add(position, PrefItem.newBuilder(getActivity())
                .setIconResource(R.drawable.ic_prefs_subtitle_lang)
                .setTitleResource(R.string.opensubtitles_account)
                .setPreferenceKey(Prefs.OPENSUBTITLES_USERNAME)
                .setDefaultValue("")
                .hasNext(true)
                .setOnClickListener(new PrefItem.OnClickListener() {
                    @Override
                    public void onClick(PrefItem item) {
                        GuidedStepFragment.add(getFragmentManager(), TVOpenSubtitlesFragment.newInstance(new TVOpenSubtitlesFragment.Listener() {
                            @Override
                            public void onAccountChanged() {
                                updateAction(mLastSelectedActionIndex);
                            }
                        }));
                    }
                })
                .setSubtitleGenerator(new PrefItem.SubtitleGenerator() {
                    @Override
                    public String get(PrefItem item) {
                        String username = item.getValue().toString();
                        return username.isEmpty() ? getString(R.string.opensubtitles_not_signed_in) : getString(R.string.opensubtitles_signed_in_as, username);
                    }
                })
                .build());
    }

    private GuidedAction generateAction(long id, PrefItem item) {
        GuidedAction.Builder builder = new GuidedAction.Builder()
                .id(id)
                .hasNext(item.hasNext())
                .enabled(item.isClickable())
                .infoOnly(item.isTitle() || !item.isClickable())
                .title(item.isTitle() ? item.getTitle().toUpperCase(LocaleUtils.getCurrent()) : item.getTitle());

        if (!item.isTitle()) {
            builder.description(item.getSubtitle());
            if(item.getDefaultValue() instanceof Boolean) {
                builder.checked((Boolean) item.getValue());
            }
        }

        return builder.build();
    }

    @Override
    public void openListSelection(String title, String[] items, SelectionMode mode, Object currentValue, final int lowLimit, int highLimit, final OnSelectionListener onClickListener) {
        TVPreferencesListFragment fragment;

        switch (mode) {
            case SIMPLE_CHOICE:
            case ADVANCED_CHOICE:
                fragment = TVPreferencesListFragment.newInstance(title, items, (int) currentValue, new TVPreferencesListFragment.SelectionListener() {
                    @Override
                    public void onSelect(int position) {
                        onClickListener.onSelection(position, null);
                        updateAction(mLastSelectedActionIndex);
                    }
                });
                GuidedStepFragment.add(getFragmentManager(), fragment);
                break;
            case NUMBER:
            case NUMBER_SEEK:
                final String[] array = new String[highLimit - lowLimit + 1];
                for(int i = 0; i <= highLimit - lowLimit; i++) {
                    array[i] = Integer.toString(i + lowLimit);
                }

                fragment = TVPreferencesListFragment.newInstance(title, array, ((int) currentValue - lowLimit), new TVPreferencesListFragment.SelectionListener() {
                    @Override
                    public void onSelect(int position) {
                        onClickListener.onSelection(0, position + lowLimit);
                        updateAction(mLastSelectedActionIndex);
                    }
                });
                GuidedStepFragment.add(getFragmentManager(), fragment);
                break;
            case COLOR:
                String[] colors = getResources().getStringArray(R.array.subtitle_colors);
                final Integer[] colorCodes = new Integer[] {
                        Color.WHITE, Color.BLACK, Color.YELLOW, Color.RED, Color.BLUE, Color.MAGENTA, Color.GREEN, Color.DKGRAY, Color.LTGRAY
                };
                fragment = TVPreferencesListFragment.newInstance(title, colors, Arrays.asList(colorCodes).indexOf(currentValue), new TVPreferencesListFragment.SelectionListener() {
                    @Override
                    public void onSelect(int position) {
                        onClickListener.onSelection(position, colorCodes[position]);
                        updateAction(mLastSelectedActionIndex);
                    }
                });
                GuidedStepFragment.add(getFragmentManager(), fragment);
                break;

            case DIRECTORY:
            default:
                // Nothing
                break;
        }
    }

    @Override
    public void showMessage(String message) {
        if(message.equals(PreferencesHandler.ABOUT)) {
            // todo: show about activity or something
        } else {
            Toast.makeText(getActivity(), R.string.restart_effect, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateAction(int position) {
        GuidedAction action = mActions.get(position);
        action.setLabel2(mPrefs.get(position).getSubtitle());
        mActions.set(position, action);

        super.onCreateActions(mActions, null);
    }
}
