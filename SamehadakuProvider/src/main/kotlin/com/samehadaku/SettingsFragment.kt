package com.samehadaku

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import androidx.core.content.edit
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.samehadaku.BuildConfig

class SettingsFragment(
    private val plugin: SamehadakuPlugin,
    private val sharedPref: SharedPreferences,
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(
            "samehadaku_settings_fragment", "layout", BuildConfig.LIBRARY_PACKAGE_NAME
        )
        return inflater.inflate(res.getLayout(id), container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val res = plugin.resources ?: return

        fun id(name: String) = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)

        val qualitySpinner = view.findViewById<Spinner>(id("quality_spinner"))
        val mirrorSpinner  = view.findViewById<Spinner>(id("mirror_spinner"))
        val saveBtn        = view.findViewById<Button>(id("save_button"))

        // Quality options — value maps to Qualities.value or -1 for "Best Available"
        val qualityLabels = listOf("Best Available", "1080p", "720p", "480p", "360p")
        val qualityValues = listOf("-1", "1080", "720", "480", "360")

        // Known mirrors on Samehadaku
        val mirrorLabels = listOf("Any", "Filedon", "Wibufile", "Streamtape", "Doodstream")
        val mirrorValues = listOf("", "filedon", "wibufile", "streamtape", "doodstream")

        fun setupSpinner(spinner: Spinner, labels: List<String>, values: List<String>, prefKey: String, default: String) {
            spinner.adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                labels
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val current = sharedPref.getString(prefKey, default)
            spinner.setSelection(values.indexOf(current).takeIf { it != -1 } ?: 0)
        }

        setupSpinner(qualitySpinner, qualityLabels, qualityValues, PREF_QUALITY, "-1")
        setupSpinner(mirrorSpinner,  mirrorLabels,  mirrorValues,  PREF_MIRROR,  "")

        saveBtn.setOnClickListener {
            sharedPref.edit(commit = true) {
                putString(PREF_QUALITY, qualityValues[qualitySpinner.selectedItemPosition])
                putString(PREF_MIRROR,  mirrorValues[mirrorSpinner.selectedItemPosition])
            }
            dismiss()
        }
    }

    companion object {
        const val PREF_QUALITY = "samehadaku_quality"
        const val PREF_MIRROR  = "samehadaku_mirror"
    }
}