/*
 * *************************************************************************
 *  PreferencesSubtitles.java
 * **************************************************************************
 *  Copyright © 2016 VLC authors and VideoLAN
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */

package org.videolan.vlc.gui.preferences

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.SeekBarPreference
import com.jaredrummler.android.colorpicker.ColorPreferenceCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.resources.VLCInstance
import org.videolan.tools.KEY_SUBTITLES_BACKGROUND
import org.videolan.tools.KEY_SUBTITLES_BACKGROUND_COLOR
import org.videolan.tools.KEY_SUBTITLES_BACKGROUND_COLOR_OPACITY
import org.videolan.tools.KEY_SUBTITLES_BOLD
import org.videolan.tools.KEY_SUBTITLES_COLOR
import org.videolan.tools.KEY_SUBTITLES_COLOR_OPACITY
import org.videolan.tools.KEY_SUBTITLES_OUTLINE
import org.videolan.tools.KEY_SUBTITLES_OUTLINE_COLOR
import org.videolan.tools.KEY_SUBTITLES_OUTLINE_COLOR_OPACITY
import org.videolan.tools.KEY_SUBTITLES_OUTLINE_SIZE
import org.videolan.tools.KEY_SUBTITLES_POSITION
import org.videolan.tools.KEY_SUBTITLES_SHADOW
import org.videolan.tools.KEY_SUBTITLES_SHADOW_COLOR
import org.videolan.tools.KEY_SUBTITLES_SHADOW_COLOR_OPACITY
import org.videolan.tools.KEY_SUBTITLES_SIZE
import org.videolan.tools.KEY_SUBTITLE_PREFERRED_LANGUAGE
import org.videolan.tools.KEY_SUBTITLE_TEXT_ENCODING
import org.videolan.tools.LocaleUtils
import org.videolan.tools.LocaleUtils.getLocales
import org.videolan.tools.Settings
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.isVLC4

class PreferencesSubtitles : BasePreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var settings: SharedPreferences
    private lateinit var preferredSubtitleTrack: ListPreference

    private lateinit var subtitlesSize: ListPreference
    private lateinit var subtitlesBold: CheckBoxPreference
    private lateinit var subtitlesOpacity: SeekBarPreference
    private lateinit var subtitlesColor: ColorPreferenceCompat

    private lateinit var subtitlesBackgroundEnabled: CheckBoxPreference
    private lateinit var subtitlesBackgroundColor: ColorPreferenceCompat
    private lateinit var subtitlesBackgroundOpacity: SeekBarPreference

    private lateinit var subtitlesShadowEnabled: CheckBoxPreference
    private lateinit var subtitlesShadowColor: ColorPreferenceCompat
    private lateinit var subtitlesShadowOpacity: SeekBarPreference

    private lateinit var subtitlesOutlineEnabled: CheckBoxPreference
    private lateinit var subtitlesOutlineSize: ListPreference
    private lateinit var subtitlesOutlineColor: ColorPreferenceCompat
    private lateinit var subtitlesOutlineOpacity: SeekBarPreference

    private var valueChanged = false

    override fun getXml(): Int {
        return R.xml.preferences_subtitles
    }

    override fun getTitleId(): Int {
        return R.string.subtitles_prefs_category
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings.getInstance(requireActivity())
        preferredSubtitleTrack = findPreference(KEY_SUBTITLE_PREFERRED_LANGUAGE)!!

        //main
        subtitlesSize = findPreference(KEY_SUBTITLES_SIZE)!!
        subtitlesBold = findPreference(KEY_SUBTITLES_BOLD)!!
        subtitlesColor = findPreference(KEY_SUBTITLES_COLOR)!!
        subtitlesOpacity = findPreference(KEY_SUBTITLES_COLOR_OPACITY)!!

        //background
        subtitlesBackgroundEnabled = findPreference(KEY_SUBTITLES_BACKGROUND)!!
        subtitlesBackgroundColor = findPreference(KEY_SUBTITLES_BACKGROUND_COLOR)!!
        subtitlesBackgroundOpacity = findPreference(KEY_SUBTITLES_BACKGROUND_COLOR_OPACITY)!!

        //shadow
        subtitlesShadowEnabled = findPreference(KEY_SUBTITLES_SHADOW)!!
        subtitlesShadowColor = findPreference(KEY_SUBTITLES_SHADOW_COLOR)!!
        subtitlesShadowOpacity = findPreference(KEY_SUBTITLES_SHADOW_COLOR_OPACITY)!!

        //outline
        subtitlesOutlineEnabled = findPreference(KEY_SUBTITLES_OUTLINE)!!
        subtitlesOutlineSize = findPreference(KEY_SUBTITLES_OUTLINE_SIZE)!!
        subtitlesOutlineColor = findPreference(KEY_SUBTITLES_OUTLINE_COLOR)!!
        subtitlesOutlineOpacity = findPreference(KEY_SUBTITLES_OUTLINE_COLOR_OPACITY)!!

        val presetPreference = findPreference<ListPreference>("subtitles_presets")!!
        presetPreference.value = "-1"
        presetPreference.setOnPreferenceChangeListener { _, newValue ->
            resetAll()
            when (newValue) {
                "1" -> subtitlesSize.value = "13"
                "2" -> {
                    subtitlesSize.value = "10"
                    subtitlesBackgroundEnabled.isChecked = true
                    subtitlesBackgroundOpacity.value = 255
                    subtitlesShadowEnabled.isChecked = false
                    subtitlesOutlineEnabled.isChecked = false
                }
                "3" -> {
                    subtitlesBackgroundEnabled.isChecked = true
                    subtitlesBackgroundOpacity.value = 128
                    subtitlesShadowEnabled.isChecked = false
                }
                "4" -> subtitlesColor.saveValue(Color.YELLOW)
                "5" -> {
                    subtitlesColor.saveValue(Color.YELLOW)
                    subtitlesBackgroundEnabled.isChecked = true
                    subtitlesBackgroundOpacity.value = 128
                    subtitlesShadowEnabled.isChecked = false
                }
            }
            false
        }

        // The VLC 4 engine places text subtitles on the display, which allows moving them down into
        // the black bars; the VLC 3 engine keeps them inside the video image, so a negative value
        // could not be honoured and the range stays positive there.
        if (isVLC4()) findPreference<SeekBarPreference>(KEY_SUBTITLES_POSITION)?.min = -500

        updatePreferredSubtitleTrack()
        prepareLocaleList()
        managePreferenceVisibilities()
    }

    private fun resetAll() {
        subtitlesSize.value = "16"
        subtitlesBold.isChecked = false
        subtitlesColor.saveValue(ContextCompat.getColor(requireActivity(), R.color.white))
        subtitlesOpacity.value = 255

        subtitlesBackgroundEnabled.isChecked = false
        subtitlesBackgroundColor.saveValue(ContextCompat.getColor(requireActivity(), R.color.black))
        subtitlesBackgroundOpacity.value = 255

        subtitlesShadowEnabled.isChecked = true
        subtitlesShadowColor.saveValue(ContextCompat.getColor(requireActivity(), R.color.black))
        subtitlesShadowOpacity.value = 128

        subtitlesOutlineEnabled.isChecked = true
        subtitlesOutlineColor.saveValue(ContextCompat.getColor(requireActivity(), R.color.black))
        subtitlesOutlineOpacity.value = 255
    }

    private fun updatePreferredSubtitleTrack() {
        val value = Settings.getInstance(requireActivity()).getString(KEY_SUBTITLE_PREFERRED_LANGUAGE, null)
        if (value.isNullOrEmpty())
            preferredSubtitleTrack.summary = getString(R.string.no_track_preference)
        else
            preferredSubtitleTrack.summary = getString(R.string.track_preference, value)
    }

    override fun onStart() {
        super.onStart()
        preferenceScreen.sharedPreferences!!.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStop() {
        super.onStop()
        if (valueChanged) {
            valueChanged = false
            applySubtitleSettings()
        }
        preferenceScreen.sharedPreferences!!.unregisterOnSharedPreferenceChangeListener(this)
    }

    /**
     * Applies the subtitle settings that changed to the playback that is running.
     *
     * The libvlc options are only read when the player, its input and its video output are created,
     * so the change is applied by rebuilding the player and replaying the current media where it is
     * (see [PlaybackService.reloadPlayback]).
     *
     * This must not run in this fragment's lifecycle scope: the settings are usually changed from
     * the player's own subtitle dialog, which is dismissed - and this fragment destroyed - right
     * after [onStop] is called. The restart would then be cancelled before the player is rebuilt,
     * and the new settings would only show up once the video was closed and opened again.
     */
    private fun applySubtitleSettings() {
        GlobalScope.launch(Dispatchers.IO) {
            VLCInstance.restart()
            withContext(Dispatchers.Main) { PlaybackService.instance?.reloadPlayback() }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            KEY_SUBTITLES_SIZE, KEY_SUBTITLES_POSITION, KEY_SUBTITLES_BOLD, KEY_SUBTITLE_TEXT_ENCODING,
            KEY_SUBTITLES_COLOR, KEY_SUBTITLES_COLOR_OPACITY,
            KEY_SUBTITLES_BACKGROUND_COLOR, KEY_SUBTITLES_BACKGROUND_COLOR_OPACITY, KEY_SUBTITLES_BACKGROUND,
            KEY_SUBTITLES_OUTLINE, KEY_SUBTITLES_OUTLINE_SIZE, KEY_SUBTITLES_OUTLINE_COLOR, KEY_SUBTITLES_OUTLINE_COLOR_OPACITY,
            KEY_SUBTITLES_SHADOW, KEY_SUBTITLES_SHADOW_COLOR, KEY_SUBTITLES_SHADOW_COLOR_OPACITY -> {
                valueChanged = true
                managePreferenceVisibilities()
            }
            KEY_SUBTITLE_PREFERRED_LANGUAGE -> updatePreferredSubtitleTrack()
        }
    }

    private fun managePreferenceVisibilities() {
        val subtitleBackgroundEnabled = settings.getBoolean(KEY_SUBTITLES_BACKGROUND, false)
        subtitlesBackgroundColor.isVisible = subtitleBackgroundEnabled
        subtitlesBackgroundOpacity.isVisible = subtitleBackgroundEnabled

        val subtitleShadowEnabled = settings.getBoolean(KEY_SUBTITLES_SHADOW, true)
        subtitlesShadowColor.isVisible = subtitleShadowEnabled
        subtitlesShadowOpacity.isVisible = subtitleShadowEnabled

        val subtitleOutlineEnabled = settings.getBoolean(KEY_SUBTITLES_OUTLINE, true)
        //we disable the size for now as it causes some render issues. May be shown in the future
//        subtitlesOutlineSize.isVisible = subtitleOutlineEnabled
        subtitlesOutlineColor.isVisible = subtitleOutlineEnabled
        subtitlesOutlineOpacity.isVisible = subtitleOutlineEnabled
    }

    private fun prepareLocaleList() {
        val localePair = LocaleUtils.getLocalesUsedInProject(BuildConfig.TRANSLATION_ARRAY, getString(R.string.no_track_preference), requireActivity().getLocales())
        preferredSubtitleTrack.entries = localePair.localeEntries
        preferredSubtitleTrack.entryValues = localePair.localeEntryValues
    }

}
