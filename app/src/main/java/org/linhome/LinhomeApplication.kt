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

package org.linhome

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import org.linhome.customisation.Customisation
import org.linhome.customisation.Texts
import org.linhome.linphonecore.CoreContext
import org.linhome.linphonecore.CorePreferences
import org.linhome.store.DeviceStore
import org.linphone.core.Factory
import org.linphone.core.LogCollectionState
import org.linphone.mediastream.Log

class LinhomeApplication : Application() {

    companion object {
        lateinit var instance: LinhomeApplication
            private set
        lateinit var corePreferences: CorePreferences
        lateinit var coreContext: CoreContext

        var someActivityRunning: Boolean = false

        fun ensureCoreExists(context: Context) {
            if (::coreContext.isInitialized && !coreContext.stopped) {
                return
            }

            Factory.instance().setLogCollectionPath(context.filesDir.absolutePath)
            Factory.instance().enableLogCollection(LogCollectionState.Enabled)

            corePreferences = CorePreferences(context)
            corePreferences.copyAssetsFromPackage() // TODO Move in the zip - attention not to overwrite .linphone_rc

            val config = Factory.instance().createConfigWithFactory(
                corePreferences.configPath,
                corePreferences.factoryConfigPath
            )
            corePreferences.config = config

            Factory.instance().setDebugMode(corePreferences.debugLogs, Texts.appName)
            Log.i("[Application] Core context created")
            coreContext = CoreContext(context, config)
            coreContext.start()

            // work around https://bugs.linphone.org/view.php?id=7714 - for demo purpose
            if (corePreferences.config.getBool("app","first_launch", true)) {
                corePreferences.config.setBool("app","first_launch", false)
            }
            coreContext.core.setStaticPicture(context.filesDir.absolutePath+"/nowebcamCIF.jpg")
            coreContext.core.ring = context.filesDir.absolutePath+"/bell.wav"
            coreContext.core.ringDuringIncomingEarlyMedia = true

            setDefaultCodecs()

        }

        fun setDefaultCodecs() {
            if (corePreferences.config.getBool("app","default_codec_set", false)) {
                return
            }
            corePreferences.config.setBool("app","default_codec_set", true)

            val defaultVideoActive = arrayOf("h264")
            val defaultAudioActive = arrayOf("pcmu","pcma","opus")

            coreContext.core.videoPayloadTypes.forEach {
                it.enable(defaultVideoActive.contains(it.mimeType.toLowerCase()))
            }
            coreContext.core.audioPayloadTypes.forEach {
                it.enable(defaultAudioActive.contains(it.mimeType.toLowerCase()))
            }
            corePreferences.config.sync()
        }

    }


    override fun onCreate() {
        super.onCreate()
        instance = this
        Customisation
        Texts
        ensureCoreExists(applicationContext)
        DeviceStore
    }

    fun tablet(): Boolean {
        return resources.getBoolean(R.bool.tablet)
    }

    fun smartPhone(): Boolean {
        return !tablet()
    }

    fun landcape(): Boolean {
        val orientation = resources.configuration.orientation
        return orientation == Configuration.ORIENTATION_LANDSCAPE
    }

}