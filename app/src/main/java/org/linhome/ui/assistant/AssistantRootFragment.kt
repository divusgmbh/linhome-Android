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

package org.linhome.ui.assistant

import android.content.DialogInterface
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import org.linhome.GenericFragment
import org.linhome.R
import org.linhome.customisation.Texts
import org.linhome.databinding.FragmentAssistantRootBinding
import org.linhome.entities.Account
import org.linhome.utils.DialogUtil

class AssistantRootFragment : GenericFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val binding = FragmentAssistantRootBinding.inflate(inflater, container, false)

        binding.create.setOnClickListener {
            navigateToCompotent(R.id.navigation_assistant_create_linhome)
        }
        binding.use.setOnClickListener {
            navigateToCompotent(R.id.navigation_assistant_login_linhome)
        }
        binding.sip.setOnClickListener {
            navigateToCompotent(R.id.navigation_assistant_login_sip)
        }
        binding.remote.setOnClickListener {
            navigateToCompotent(R.id.navigation_assistant_remote_root)
        }
        binding.register_hint_url.paintFlags = binding.register_hint_url.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        binding.register_hint_url.setOnClickListener {
            val browserIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(Texts.get("register_hint_link"))
            )
            startActivity(browserIntent)
        }
        return binding.root
    }

    private fun navigateToCompotent(componentResource: Int)  {
        if (Account.configured()) {
            DialogUtil.confirm(
                "assistant_using_will_disconnect_title",
                "assistant_using_will_disconnect_message",
                { _: DialogInterface, _: Int ->
                    Account.disconnect()
                    mainactivity.navController.navigate(componentResource)
                })
        } else
            mainactivity.navController.navigate(componentResource)
    }

}
