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
import org.linphone.core.Recorder
import org.linphone.core.tools.Log
import org.linphone.core.MediaFileFormat

private var _snapshotTaken: Boolean = false
var Call.snapshotTaken: Boolean
    get() = _snapshotTaken
    set(value) {
        _snapshotTaken = value
    }
private var _recorder: Recorder? = null
var Call.recorder: Recorder?
    get() {
        return _recorder
    }
    set(value) {
        _recorder = value
    }
private var rtRunnable: Runnable? = null
private val rtHandler = Handler(Looper.getMainLooper())
private var isRecordRunning: Boolean = false
private var listenerCallState: CallListenerStub? = null
private var listenerNextVideoFrame: CallListenerStub? = null
private var listenerSnapshot: CallListenerStub? = null
private var rdRunnable: Runnable? = null
private val rdHandler = Handler(Looper.getMainLooper())

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
        initRecordStartListener()
        acceptEarlyMediaWithParams(earlyMediaCallParams)
        GlobalScope.launch(context = Dispatchers.Main) {
            muteAudioPLayBack()
            //extendedStartRecordingWithDuration()
        }


    }
}

fun Call.durationStopRecording(){
    stopRecordTimer()
    stopRecorderDurationCheck()
    stopRecording()
    val filePath = params.recordFile
    // Temporary set the record file name empty to force stop recording
    params.recordFile = ""
    // Set the record file name again into call params
    params.recordFile = filePath
}

fun Call.extendedClose(){
    stopRecordTimer()
    recorder?.close()
    stopRecording()
    recorder = null
    cleanUpListener()
    HistoryEventStore.stripAudio(params.recordFile!!)
    HistoryEventStore.rotateRecordings(false)
    isRecordRunning = false
    snapshotTaken = false
}

private fun Call.initRecordStartListener(){
    if(listenerCallState == null) {
        listenerCallState = object : CallListenerStub() {
            // Use CallListenerStub so you don't have to implement all 50+ methods
            override fun onStateChanged(call: Call, state: Call.State, message: String) {
                if (call.state == Call.State.StreamsRunning || call.state == Call.State.IncomingEarlyMedia) {
                    GlobalScope.launch(context = Dispatchers.Main) {
                        call.extendedStartRecordingWithDuration()
                    }
                    call.removeListener(this)
                }
            }
        }
        this.addListener(listenerCallState)
    }
}
private fun Call.cleanUpListener(){
    if(listenerCallState != null) {
        this.removeListener(listenerCallState)
        listenerCallState = null
    }
    if(listenerNextVideoFrame != null){
        this.removeListener(listenerNextVideoFrame)
        listenerNextVideoFrame = null
    }
    if(listenerSnapshot != null){
        this.removeListener(listenerSnapshot)
        listenerSnapshot = null
    }
}

public enum class RecorderDurationMode{
    NO_VIDEO_RECORDING,
    UNLIMITED,
    DURATION_TIMER_START_IMMEDIATELY ,
    DURATION_TIMER_START_WITH_FIRST_FRAME,
    DURATION_TIMER_START_WITH_SNAPSHOT_PROCESSED,
    DURATION_RECORDER_START_IMMEDIATELY,
    DURATION_RECORDER_START_WITH_FIRST_FRAME,
    DURATION_RECORDER_START_WITH_SNAPSHOT_PROCESSED,
}
fun Call.extendedStartRecordingWithDuration() {
    val recorderTimerStartMode = RecorderDurationMode.NO_VIDEO_RECORDING
    if(recorderTimerStartMode == RecorderDurationMode.NO_VIDEO_RECORDING) return
    val duration: Long = 5500 // recording max. duration im ms

    if (isRecordRunning) {
        return
    }
    isRecordRunning = true
    stopRecordTimer()
    val startImmediately = recorderTimerStartMode != RecorderDurationMode.DURATION_TIMER_START_IMMEDIATELY && recorderTimerStartMode != RecorderDurationMode.DURATION_RECORDER_START_IMMEDIATELY
    if(recorder == null){
        val params = core.createRecorderParams()
        params.fileFormat = MediaFileFormat.Mkv
        params.videoCodec = "H264"
        recorder = core.createRecorder(params)
        val path = currentParams.recordFile //?.replace(".mkv","-2.mkv")
        recorder?.open(path!!)
        //recorder?.start()

        if(!startImmediately) {
            recorder?.start()
            startRecording()
        }
        Log.i("[CallExtension] start recording with duration, recorder state: ${recorder?.state}")
    }
    // startRecording()
    when (recorderTimerStartMode) {
        RecorderDurationMode.DURATION_TIMER_START_WITH_SNAPSHOT_PROCESSED, RecorderDurationMode.DURATION_RECORDER_START_WITH_SNAPSHOT_PROCESSED -> {
            if(listenerSnapshot == null) {
                listenerSnapshot = object : CallListenerStub() {
                    override fun onSnapshotTaken(call: Call, filePath: String) {
                        // Remove listener so it doesn't fire for every single frame
                        call.removeListener(this)
                        if(recorderTimerStartMode == RecorderDurationMode.DURATION_TIMER_START_WITH_SNAPSHOT_PROCESSED) {
                            org.linphone.core.tools.Log.i("[CallExtensions] start recording with timer duration on snapshot processed")
                            call.startRecordTimerWithDuration(duration, call)
                        }
                        if(recorderTimerStartMode == RecorderDurationMode.DURATION_RECORDER_START_WITH_SNAPSHOT_PROCESSED) {
                            org.linphone.core.tools.Log.i("[CallExtensions] start recording with recorder duration check on snapshot processed")
                            //call.sendVfuRequest()
                            call.startRecorderDurationCheck(duration, call)
                            call.recorder?.start()
                            call.startRecording()
                        }
                    }
                }
                this.addListener(listenerSnapshot)
            }
        }
        RecorderDurationMode.DURATION_TIMER_START_WITH_FIRST_FRAME, RecorderDurationMode.DURATION_RECORDER_START_WITH_FIRST_FRAME -> {
            // Wait for first frame
            if(listenerNextVideoFrame == null){
                listenerNextVideoFrame = object : CallListenerStub() {
                    override fun onNextVideoFrameDecoded(call: Call) {
                        // Remove listener so it doesn't fire for every single frame
                        call.removeListener(this)
                        if(recorderTimerStartMode == RecorderDurationMode.DURATION_TIMER_START_WITH_FIRST_FRAME) {
                            org.linphone.core.tools.Log.i("[CallExtensions] start recording with timer duration on first frame")
                            call.startRecordTimerWithDuration(duration, call)
                        }
                        if(recorderTimerStartMode == RecorderDurationMode.DURATION_RECORDER_START_WITH_FIRST_FRAME) {
                            //call.sendVfuRequest()
                            call.startRecorderDurationCheck(duration, call)
                            call.recorder?.start()
                            call.startRecording()
                            org.linphone.core.tools.Log.i("[CallExtensions] start recording with recorder duration check on first frame, recorder state: ${recorder?.state}")
                        }
                    }
                }
                this.addListener(listenerNextVideoFrame)
            }
            this.requestNotifyNextVideoFrameDecoded()
        }
        RecorderDurationMode.DURATION_TIMER_START_IMMEDIATELY -> {
            startRecordTimerWithDuration(duration, this)
        }
        RecorderDurationMode.DURATION_RECORDER_START_IMMEDIATELY ->{
            startRecorderDurationCheck(duration, this)
        }
        RecorderDurationMode.NO_VIDEO_RECORDING -> {

        }
        else -> {

        }
    }
}

private fun Call.startRecordTimerWithDuration(duration: Long, call: Call){
    rtRunnable = Runnable {
        org.linphone.core.tools.Log.i("[CallExtensions] stop recording due max. duration reached: ${duration}")
        call.durationStopRecording()
    }
    rtRunnable?.let {
        rtHandler.postDelayed(it, duration)
    }
}

fun Call.stopRecordTimer() {
    rtRunnable?.let {
        rtHandler.removeCallbacks(it)
        rtRunnable = null
    }
}

private fun Call.startRecorderDurationCheck(duration: Long, call: Call){
    rdRunnable = Runnable {
        if((call.recorder?.duration ?: 0) >= duration) {
            org.linphone.core.tools.Log.i("[CallExtensions] stop recording due recorder reached max. duration: ${call.recorder?.duration}ms of max.  ${duration}ms")
            call.durationStopRecording()
        }else{
            rdRunnable?.let {
                rdHandler.postDelayed(it, 250)
            }
        }
    }
    rdRunnable?.let {
        rdHandler.postDelayed(it, 250)
    }
}

fun Call.stopRecorderDurationCheck() {
    rdRunnable?.let {
        rdHandler.removeCallbacks(it)
        rdRunnable = null
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

    initRecordStartListener()

    acceptWithParams(inCallParams)
    /*if (!isRecordRunning) {
        extendedStartRecordingWithDuration()
    }*/
}


// Early media phase - work around to avoid playing audio back to user, but still have the stream
fun Call.muteAudioPLayBack() {
    speakerVolumeGain = -1000.0f
}

fun Call.unMuteAudioPLayBack() {
    speakerVolumeGain = 0.0f
}