/*
 * Copyright 2017 Akhil Kedia
 * This file is part of AllTrans.
 *
 * AllTrans is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AllTrans is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AllTrans. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package akhil.alltrans

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import java.util.Locale

/**
 * A simple [Fragment] subclass for displaying instructions in a WebView
 */
class InstructionsFragment : Fragment() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Create a FrameLayout to hold both WebView and ProgressBar
        val rootLayout = FrameLayout(requireContext())

        // Create and configure the WebView
        val webView = WebView(requireActivity()).apply {
            settings.apply {
                loadWithOverviewMode = true
                useWideViewPort = false
                javaScriptEnabled = true
            }
        }

        // Add WebView to the layout
        rootLayout.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Create and configure the ProgressBar
        val progressBar = ProgressBar(requireContext())
        val progressParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.CENTER
        }
        rootLayout.addView(progressBar, progressParams)

        // Set WebViewClient to hide progress bar when page is loaded
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
            }
        }

        // Get version name from PackageManager
        val versionName = try {
            val packageInfo = requireActivity().packageManager
                .getPackageInfo(requireActivity().packageName, 0)
            packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            "latest"
        }

        val systemLanguage = Locale.getDefault().language
        val allowedLanguages = listOf(
            "hi", "ko", "af", "sq", "am", "ar", "hy", "az", "eu", "be", "bn", "bs", "bg", "ca",
            "ceb", "ny", "zh", "zh-CN", "zh-TW", "co", "hr", "cs", "da", "nl", "eo", "et", "tl",
            "fi", "fr", "fy", "gl", "ka", "de", "el", "gu", "ht", "ha", "haw", "iw", "hi", "hmn",
            "hu", "is", "ig", "id", "ga", "it", "ja", "jw", "kn", "kk", "km", "rw", "ko", "ku",
            "ky", "lo", "la", "lv", "lt", "lb", "mk", "mg", "ms", "ml", "mt", "mi", "mr", "mn",
            "my", "ne", "no", "or", "ps", "fa", "pl", "pt", "pa", "ro", "ru", "sm", "gd", "sr",
            "st", "sn", "sd", "si", "sk", "sl", "so", "es", "su", "sw", "sv", "tg", "ta", "tt",
            "te", "th", "tr", "tk", "uk", "ur", "ug", "uz", "vi", "cy", "xh", "yi", "yo", "zu"
        )

        // Updated URL to point to the new repository
        val baseUrl = "https://github.com/pbzinwindows/AllTrans"

        val url = if (systemLanguage in allowedLanguages) {
            "https://translate.google.com/translate?sl=en&tl=$systemLanguage&u=$baseUrl"
        } else {
            baseUrl
        }

        webView.loadUrl(url)
        return rootLayout
    }
}