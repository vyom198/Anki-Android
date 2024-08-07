/****************************************************************************************
 * Copyright (c) 2020 Mike Hardy <github@mikehardy.net>                                 *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.anki.multimediacard.fields

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CrashReportService
import com.ichi2.anki.R
import com.ichi2.anki.multimediacard.activity.MultimediaEditFieldActivity
import com.ichi2.anki.preferences.sharedPrefs
import com.ichi2.anki.showThemedToast
import com.ichi2.compat.CompatHelper
import com.ichi2.ui.FixedTextView
import com.ichi2.utils.ExceptionUtil.executeSafe
import com.ichi2.utils.FileUtil
import timber.log.Timber
import java.io.File

class BasicMediaClipFieldController : FieldControllerBase(), IFieldController {
    private var ankiCacheDirectory: String? = null

    private lateinit var tvAudioClip: FixedTextView

    private lateinit var selectMediaLauncher: ActivityResultLauncher<Intent>

    override fun createUI(context: Context, layout: LinearLayout) {
        ankiCacheDirectory = FileUtil.getAnkiCacheDirectory(context)
        // #9639: .opus is application/octet-stream in API 26,
        // requires a workaround as we don't want to enable application/octet-stream by default
        val btnLibrary = Button(_activity)
        btnLibrary.text = _activity.getText(R.string.multimedia_editor_import_audio)
        btnLibrary.setOnClickListener {
            openChooserPrompt(
                "audio/*",
                arrayOf("audio/*", "application/ogg"), // #9226: allows ogg on Android 8
                R.string.multimedia_editor_popup_audio_clip
            )
        }
        layout.addView(btnLibrary, ViewGroup.LayoutParams.MATCH_PARENT)
        val btnVideo = Button(_activity).apply {
            text = _activity.getText(R.string.multimedia_editor_import_video)
            setOnClickListener {
                openChooserPrompt(
                    "video/*",
                    emptyArray(),
                    R.string.multimedia_editor_popup_video_clip
                )
            }
        }
        layout.addView(btnVideo, ViewGroup.LayoutParams.MATCH_PARENT)
        tvAudioClip = FixedTextView(_activity)
        if (_field.mediaPath == null) {
            tvAudioClip.visibility = View.GONE
        } else {
            tvAudioClip.text = _field.mediaPath
            tvAudioClip.visibility = View.VISIBLE
        }
        layout.addView(tvAudioClip, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun openChooserPrompt(initialMimeType: String, extraMimeTypes: Array<String>, @StringRes prompt: Int) {
        val allowAllFiles =
            this._activity.sharedPrefs().getBoolean("mediaImportAllowAllFiles", false)
        val i = Intent()
        i.type = if (allowAllFiles) "*/*" else initialMimeType
        if (!allowAllFiles && extraMimeTypes.any()) {
            // application/ogg takes precedence over "*/*" for application/octet-stream
            // so don't add it if we're want */*
            i.putExtra(Intent.EXTRA_MIME_TYPES, extraMimeTypes)
        }
        i.action = Intent.ACTION_GET_CONTENT
        // Only get openable files, to avoid virtual files issues with Android 7+
        i.addCategory(Intent.CATEGORY_OPENABLE)
        val chooserPrompt = _activity.resources.getString(prompt)
        selectMediaLauncher.launch(Intent.createChooser(i, chooserPrompt))
    }

    override fun setEditingActivity(activity: MultimediaEditFieldActivity) {
        super.setEditingActivity(activity)
        val registry = this._activity.activityResultRegistry

        selectMediaLauncher =
            registry.register(SELECT_MEDIA_LAUNCHER_KEY, ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode != Activity.RESULT_CANCELED && result.data != null) {
                    executeSafe(this._activity, "handleMediaSelection:unhandled") {
                        handleMediaSelection(result.data!!)
                    }
                }
            }
    }

    private fun handleMediaSelection(data: Intent) {
        val selectedClip = data.data

        // Get information about the selected document
        val queryColumns = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE)
        var mediaClipFullNameParts: Array<String>
        _activity.contentResolver.query(selectedClip!!, queryColumns, null, null, null).use { cursor ->
            if (cursor == null) {
                showThemedToast(
                    AnkiDroidApp.instance.applicationContext,
                    AnkiDroidApp.instance.getString(R.string.multimedia_editor_something_wrong),
                    true
                )
                return
            }
            cursor.moveToFirst()
            val mediaClipFullName = cursor.getString(0)
            mediaClipFullNameParts = mediaClipFullName.split(".").toTypedArray()
            if (mediaClipFullNameParts.size < 2) {
                mediaClipFullNameParts = try {
                    Timber.i("Media clip name does not have extension, using second half of mime type")
                    arrayOf(mediaClipFullName, cursor.getString(2).split("/").toTypedArray()[1])
                } catch (e: Exception) {
                    Timber.w(e)
                    // This code is difficult to stabilize - it is not clear how to handle files with no extension
                    // and apparently we may fail to get MIME_TYPE information - in that case we will gather information
                    // about what people are experiencing in the real world and decide later, but without crashing at least
                    CrashReportService.sendExceptionReport(e, "Media Clip addition failed. Name " + mediaClipFullName + " / cursor mime type column type " + cursor.getType(2))
                    showThemedToast(
                        AnkiDroidApp.instance.applicationContext,
                        AnkiDroidApp.instance.getString(R.string.multimedia_editor_something_wrong),
                        true
                    )
                    return
                }
            } else if (mediaClipFullNameParts.size > 2) {
                // there's at least one extra point in the filename besides the point delimiter for extension
                val lastPointIndex = mediaClipFullName.lastIndexOf(".")
                mediaClipFullNameParts = arrayOf(
                    mediaClipFullName.substring(0 until lastPointIndex),
                    mediaClipFullName.substring(lastPointIndex + 1)
                )
            }
        }

        // We may receive documents we can't access directly, we have to copy to a temp file
        val clipCopy: File
        try {
            clipCopy = createCachedFile(mediaClipFullNameParts[0] + "." + mediaClipFullNameParts[1])
            Timber.d("media clip picker file path is: %s", clipCopy.absolutePath)
        } catch (e: Exception) {
            Timber.e(e, "Could not create temporary media file. ")
            CrashReportService.sendExceptionReport(e, "handleMediaSelection:tempFile")
            showThemedToast(
                AnkiDroidApp.instance.applicationContext,
                AnkiDroidApp.instance.getString(R.string.multimedia_editor_something_wrong),
                true
            )
            return
        }

        // Copy file contents into new temp file. Possibly check file size first and warn if large?
        try {
            _activity.contentResolver.openInputStream(selectedClip).use { inputStream ->
                CompatHelper.compat.copyFile(inputStream!!, clipCopy.absolutePath)

                // If everything worked, hand off the information
                _field.hasTemporaryMedia = true
                _field.mediaPath = clipCopy.absolutePath
                tvAudioClip.text = clipCopy.name
                tvAudioClip.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to copy media file from ContentProvider")
            CrashReportService.sendExceptionReport(e, "handleMediaSelection:copyFromProvider")
            showThemedToast(
                AnkiDroidApp.instance.applicationContext,
                AnkiDroidApp.instance.getString(R.string.multimedia_editor_something_wrong),
                true
            )
        }
    }

    private fun createCachedFile(filename: String): File {
        val file = File(ankiCacheDirectory, filename)
        file.deleteOnExit()
        return file
    }

    override fun onDone() {
        if (::selectMediaLauncher.isInitialized) {
            selectMediaLauncher.unregister()
        }
    }

    override fun onFocusLost() {
        /* nothing */
    }

    override fun onDestroy() {
        /* nothing */
    }

    companion object {
        private const val SELECT_MEDIA_LAUNCHER_KEY = "select_media_launcher_key"
    }
}
