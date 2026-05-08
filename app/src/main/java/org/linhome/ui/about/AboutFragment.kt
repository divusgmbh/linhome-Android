/*
 * Copyright (c) 2010-2020 Belledonne Communications SARL.
 *
 * This file is part of linhome-android
 * (see https://www.linhome.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.linhome.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.linhome.LinhomeApplication
import org.linhome.customisation.Texts
import org.linhome.databinding.FragmentAboutBinding
import org.linhome.BuildConfig

class AboutFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val binding = FragmentAboutBinding.inflate(inflater, container, false)
        binding.coreContext = LinhomeApplication.coreContext

        val appName = requireContext().applicationInfo
            .loadLabel(requireContext().packageManager).toString()
        val appVersion: String = BuildConfig.VERSION_NAME
        val appVersionCode: Int = BuildConfig.VERSION_CODE
        val appGitVersion: String = BuildConfig.GIT_VERSION
        binding.appversion.text = appName + " " + appVersion +" ("+appVersionCode+")\n"+appGitVersion.replace(".","\n")


        binding.licence.setOnClickListener {
            val browserIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(Texts.get("license_link"))
            )
            startActivity(browserIntent)
        }

        binding.privacyPolicy.setOnClickListener {
            val browserIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(Texts.get("privacy_policy_link"))
            )
            startActivity(browserIntent)
        }

        return binding.root
    }
}
