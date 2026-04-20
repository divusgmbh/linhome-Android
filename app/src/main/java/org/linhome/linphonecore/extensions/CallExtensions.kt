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

package org.linhome.linphonecore.extensions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.linhome.LinhomeApplication.Companion.coreContext
import org.linhome.LinhomeApplication.Companion.corePreferences
import org.linhome.store.DeviceStore
import org.linhome.store.HistoryEventStore
import org.linhome.utils.DialogUtil
import org.linphone.core.Call
import org.linphone.core.CallParams
import org.linphone.core.MediaDirection
import android.os.Handler
import android.os.Looper
import org.linphone.core.CallListenerStub

private var rtRunnable: Runnable? = null
private val rtHandler = Handler(Looper.getMainLooper())

private var isRecordRunning: Boolean = false

fun Call.extendedAcceptEarlyMedia() {
    if (state == Call.State.IncomingReceived || state == Call.State.IncomingEarlyMedia) {
        val earlyMediaCallParams: CallParams? = coreContext.core.createCallParams(this)
        earlyMediaCallParams?.recordFile = callLog.historyEvent().mediaFileName

        // Optimize for early media
        earlyMediaCallParams?.setAudioEnabled(true) // MUST BE TRUE OTHERWISE RECORDING IS NOT DONE BY THE SDK
        earlyMediaCallParams?.setAudioDirection(MediaDirection.RecvOnly)
        earlyMediaCallParams?.setCameraEnabled(false)
        earlyMediaCallParams?.setEarlyMediaSendingEnabled(true)
        earlyMediaCallParams?.setMicEnabled(false)
        earlyMediaCallParams?.setVideoEnabled(true)
        earlyMediaCallParams?.setVideoDirection(MediaDirection.RecvOnly)

        acceptEarlyMediaWithParams(earlyMediaCallParams)
        GlobalScope.launch(context = Dispatchers.Main) {
            muteAudioPLayBack()
            extendedStartRecordingWithDuration()
        }
    }
}

fun Call.durationStopRecording(){
    stopRecordTimer()
    stopRecording()
    val filePath = params.recordFile
    // Temporary set the record file name empty to force stop recording
    params.recordFile = ""
    // Set the record file name again into call params
    params.recordFile = filePath
}

fun Call.extendedClose(){
    stopRecordTimer()
    stopRecording()
    HistoryEventStore.stripAudio(params.recordFile!!)
    HistoryEventStore.rotateRecordings(false)
    isRecordRunning = false
}

fun Call.extendedStartRecordingWithDuration() {
    val duration: Long = 5500 // recording max. duration im ms
    if (isRecordRunning) {
        return
    }
    isRecordRunning = true
    stopRecordTimer()
    startRecording()
    // Wait for first frame before starting timer
    this.addListener(object : CallListenerStub() {
        override fun onNextVideoFrameDecoded(call: Call) {
            org.linphone.core.tools.Log.i("[CallExtensions] start recording on first frame received")
            rtRunnable = Runnable {
                org.linphone.core.tools.Log.i("[CallExtensions] stop recording due max. duration reached: ${duration}")
                call.durationStopRecording()
            }
            rtRunnable?.let {
                rtHandler.postDelayed(it, duration)
            }
            // Remove listener so it doesn't fire for every single frame
            call.removeListener(this)
        }
    })
    // request frame ready event
    this.requestNotifyNextVideoFrameDecoded()


}

fun Call.stopRecordTimer() {
    rtRunnable?.let {
        rtHandler.removeCallbacks(it)
        rtRunnable = null
    }
}

fun Call.extendedAccept() {

    if (coreContext.gsmCallActive()) {
        DialogUtil.toast("unable_to_accept_call_gsm_call_in_progress")
        return
    }

    val inCallParams: CallParams? = coreContext.core.createCallParams(this)
    inCallParams?.recordFile = callLog.historyEvent().mediaFileName
    inCallParams?.setCameraEnabled(false)
    inCallParams?.setMicEnabled(true)
    inCallParams?.setAudioDirection(MediaDirection.SendRecv)
    inCallParams?.setVideoDirection(MediaDirection.SendRecv)
    microphoneVolumeGain = 1.0f
    unMuteAudioPLayBack()
    val device = DeviceStore.findDeviceByAddress(remoteAddress)
    if (device != null) {
        coreContext.core.useRfc2833ForDtmf = device.actionsMethodType == "method_dtmf_rfc_4733"
        coreContext.core.useInfoForDtmf = device.actionsMethodType == "method_dtmf_sip_info"
    } else {
        coreContext.core.useRfc2833ForDtmf = corePreferences.defaultActionsMethodType == "method_dtmf_rfc_4733"
        coreContext.core.useInfoForDtmf = corePreferences.defaultActionsMethodType == "method_dtmf_sip_info"
    }
    acceptWithParams(inCallParams)
    if (!isRecordRunning) {
        extendedStartRecordingWithDuration()
    }
}


// Early media phase - work around to avoid playing audio back to user, but still have the stream
fun Call.muteAudioPLayBack() {
    speakerVolumeGain = -1000.0f
}

fun Call.unMuteAudioPLayBack() {
    speakerVolumeGain = 0.0f
}