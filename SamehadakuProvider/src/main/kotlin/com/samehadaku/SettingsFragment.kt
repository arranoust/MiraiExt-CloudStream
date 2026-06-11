package com.samehadaku

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.DialogFragment

class SettingsFragment(
    private val sharedPref: SharedPreferences,
) : DialogFragment() {

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(0xFF121212.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun sectionTitle(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 8 }
        }

        fun sectionDesc(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFFAAAAAA.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 8 }
        }

        fun spinner(labels: List<String>, values: List<String>, prefKey: String, default: String): Spinner {
            return Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, labels).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                val current = sharedPref.getString(prefKey, default)
                setSelection(values.indexOf(current).takeIf { it != -1 } ?: 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 24 }
            }
        }

        // Quality
        val qualityLabels = listOf("Best Available", "2160p (4K)", "1080p", "720p", "480p", "360p")
        val qualityValues = listOf("-1", "2160", "1080", "720", "480", "360")
        root.addView(sectionTitle("Quality Preference"))
        root.addView(sectionDesc("Prioritize links matching this resolution."))
        val qualitySpinner = spinner(qualityLabels, qualityValues, PREF_QUALITY, "-1")
        root.addView(qualitySpinner)

        // Mirror
        val mirrorLabels = listOf("Any", "Filedon", "Pixeldrain", "Krakenfiles", "Wibufile", "Streamtape", "Doodstream")
        val mirrorValues = listOf("", "filedon", "pixeldrain", "krakenfiles", "wibufile", "streamtape", "doodstream")
        root.addView(sectionTitle("Preferred Mirror"))
        root.addView(sectionDesc("Only show links from this mirror host."))
        val mirrorSpinner = spinner(mirrorLabels, mirrorValues, PREF_MIRROR, "")
        root.addView(mirrorSpinner)

        // Save button
        val saveBtn = Button(ctx).apply {
            text = "Save"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF6200EE.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        saveBtn.setOnClickListener {
            val editor = sharedPref.edit()
            editor.putString(PREF_QUALITY, qualityValues[qualitySpinner.selectedItemPosition])
            editor.putString(PREF_MIRROR, mirrorValues[mirrorSpinner.selectedItemPosition])
            editor.apply()
            dismiss()
        }
        root.addView(saveBtn)

        return root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog?.window?.setGravity(Gravity.BOTTOM)
    }

    companion object {
        const val PREF_QUALITY = "samehadaku_quality"
        const val PREF_MIRROR  = "samehadaku_mirror"
    }
}