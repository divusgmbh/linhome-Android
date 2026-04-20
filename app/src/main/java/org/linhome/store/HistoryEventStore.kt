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

package org.linhome.store

import androidx.lifecycle.MutableLiveData
import org.linhome.LinhomeApplication
import org.linhome.entities.HistoryEvent
import org.linhome.store.StorageManager.historyEventsXml
import org.linphone.core.Config
import org.linhome.linphonecore.extensions.historyEvent
import org.linphone.core.Factory
import java.io.File
import org.linphone.core.tools.Log

import java.io.RandomAccessFile
import kotlin.collections.remove

object HistoryEventStore {

    private var historyEventsConfig: Config

    var historyEvents: HashMap<String, HistoryEvent> // CallId / History event

    val syncModel = MutableLiveData<Boolean>()

    init {
        if (!StorageManager.historyEventsXml.exists())
            StorageManager.historyEventsXml.createNewFile()
        historyEventsConfig = Factory.instance().createConfig(null)
        historyEventsConfig.loadFromXmlFile(StorageManager.historyEventsXml.absolutePath)
        historyEvents = readFromXml()
        // Cleanup
        rotateRecordings(true)
        org.linphone.core.tools.Log.i("[HistoryEventStore] Initialize store")
    }

    fun initialize(){
        // Empty function just to trigger the init() at startup
    }

    fun readFromXml(): HashMap<String, HistoryEvent> {
        val result = HashMap<String, HistoryEvent>()
        historyEventsConfig.sectionsNamesList.forEach {
            val mediaFile = historyEventsConfig.getString(it, "media_file_name", null)!!
            result.put(
                // WORKAROUND SU:
                // Check stored file for really find out if it contains video
                // linphone sdk may store hasVideo wrong
                historyEventsConfig.getString(it, "call_id", null)!!, HistoryEvent(
                    it,
                    historyEventsConfig.getString(it, "call_id", null),
                    historyEventsConfig.getBool(it, "viewed_by_user", false),
                    mediaFile,
                    historyEventsConfig.getString(it, "media_thumbnail_file_name", null)!!,
                    fileHasVideo(historyEventsConfig.getString(it, "media_file_name", null)!!)
                // WORKAROUND SU
                /*historyEventsConfig.getBool(it, "has_video", false) // original code*/
                )
            )
        }
        return result
    }

    fun sync() {
        historyEventsConfig.sectionsNamesList.forEach {
            historyEventsConfig.cleanSection(it)
        }
        historyEvents.forEach { entry ->
            historyEventsConfig.setBool(entry.value.id, "viewed_by_user", entry.value.viewedByUser)
            historyEventsConfig.setString(
                entry.value.id,
                "media_file_name",
                entry.value.mediaFileName
            )
            historyEventsConfig.setString(
                entry.value.id,
                "media_thumbnail_file_name",
                entry.value.mediaThumbnailFileName
            )
            historyEventsConfig.setString(entry.value.id, "call_id", entry.value.callId)
            historyEventsConfig.setBool(entry.value.id, "has_video", entry.value.hasVideo)
        }
        historyEventsXml.writeText(historyEventsConfig.dumpAsXml())
    }

    fun persistHistoryEvent(entry: HistoryEvent) {
        entry.callId?.let {
            // WORKAROUND SU:
            // Check stored file for really find out if it contains video
            // linphone sdk may store hasVideo wrong
            entry.hasVideo = fileHasVideo(entry.mediaFileName)
            // WORKAROUND SU
            historyEvents.put(it, entry)
            sync()
        }
    }

    fun removeHistoryEvent(entry: HistoryEvent) {
        entry.media.also {
            if (it.exists())
                it.delete()
        }
        entry.mediaThumbnail.also {
            if (it.exists())
                it.delete()
        }
        historyEvents.remove(entry.callId)
        sync()
    }

    fun removeHistoryEventByCallId(callId: String) {
        findHistoryEventByCallId(callId)?.also {
            removeHistoryEvent(it)
        }
    }

    fun findHistoryEventByCallId(callId: String): HistoryEvent? {
        return historyEvents.get(callId)
    }

    fun markAsRead(historyEventId: String) {
        historyEvents.filter { it.value.id ==  historyEventId }.forEach { event ->
            event.value.viewedByUser = true
            persistHistoryEvent(event.value)
        }
    }

    fun fileHasVideo(path: String): Boolean {
        val filePath = path.replace(".jpg", ".mkv")
        val file = File(filePath)

        if (!file.exists()) return false

        return try {
            val raf = RandomAccessFile(file, "r")
            val buffer = ByteArray(8192) // Scan the first 8KB or tail

            // Scan head
            raf.read(buffer)
            val headStr = String(buffer, Charsets.US_ASCII)
            //if (headStr.contains("V_MPEG4")) return true
            //if (headStr.contains("V_VP8")) return true
            if (headStr.contains("V_")) return true // Generic Video indicator prefix

            // If not in head, scan the last 32KB (where Linphone often puts metadata)
            val fileSize = raf.length()
            val searchSize = 32768L
            val startPos = if (fileSize > searchSize) fileSize - searchSize else 0L
            raf.seek(startPos)
            val tailBuffer = ByteArray(searchSize.toInt())
            raf.readFully(tailBuffer)
            raf.close()

            //String(tailBuffer, Charsets.US_ASCII).contains("V_MPEG4") ||
            //String(tailBuffer, Charsets.US_ASCII).contains("V_VP8") ||
            String(tailBuffer, Charsets.US_ASCII).contains("V_")
        } catch (e: Exception) {
            false
        }
    }

    fun rotateRecordings(cleanup: Boolean) {
        org.linphone.core.tools.Log.i("[HistoryEventStore] rotating files now")
        val max = 20
        val directory = StorageManager.callsRecordingsDir
        val core = LinhomeApplication.coreContext.core

        val callIdsToRemove = mutableListOf<String>()
        val filesToKeep = mutableListOf<String>()
        core.callLogs.sortedByDescending { it.startDate }
            .forEachIndexed { index, log ->
                if(index < max){
                    filesToKeep.add(log.historyEvent().mediaFileName)
                    filesToKeep.add(log.historyEvent().mediaFileName.replace("mkv","jpg"))
                }else{
                    callIdsToRemove.add(log.callId ?: "")
                }
            }
        callIdsToRemove.forEach { callId ->
            // Remove from historyevent data
            historyEvents.remove(callId)
            Log.i("[HistoryEventStore] rotate data, remove obsolete event for call id: ${callId}")
            // remove rom call log
            val log = core.findCallLogFromCallId(callId)
            if (log != null) {
                core.removeCallLog(log)
                Log.i("[HistoryEventStore] rotate data, remove obsolete call log for call id: ${callId}")
            }
        }
        //Get all files in the directory
        val files = directory.listFiles { file ->
            file.isFile
        }
        if(files.count() > 0) {

            // Sort by modification time (Newest First)
            val sortedFiles = files.sortedByDescending { it.lastModified() }

            // Delete mkv files and according
            val filesToDelete = mutableListOf<String>()
            sortedFiles.forEach { file ->
                if (filesToKeep.contains(file.absolutePath)) {
                    if (file.absolutePath.endsWith(".mkv"))
                        stripAudio(file.absolutePath)
                } else {
                    filesToDelete.add(file.absolutePath)
                }
            }
            filesToDelete.forEach { path ->
                val file = File(path)
                if (cleanup) {
                    if (file.delete())
                        Log.i("[HistoryEventStore] cleanup files, deleting file: ${file.name}")
                } else {
                    if (!file.absolutePath.lowercase().endsWith(".part")) {
                        if (file.delete())
                            Log.i("[HistoryEventStore] rotating files, deleting obsolete file: ${file.name}")
                    }
                }
            }
        }
        if(callIdsToRemove.count() > 0){
            syncModel.postValue(true)
        }
    }

    fun stripAudio(filePath: String) {
        val file = RandomAccessFile(filePath, "rw")
        try {
            // Read the beginning of the file where track headers live
            val buffer = ByteArray(16384)
            file.read(buffer)

            for (i in 0 until buffer.size - 10) {
                // Find TrackEntry ID: 0xAE
                if (buffer[i] == 0xAE.toByte()) {
                    // Search within this entry for TrackType: 0x83
                    for (j in i until i + 100) {
                        // 0x83 followed by 0x01 (size) and 0x02 (Audio Type)
                        if (j + 2 < buffer.size && buffer[j] == 0x83.toByte() && buffer[j+2] == 0x02.toByte()) {

                            // Overwrite the 'TrackEntry' ID (0xAE) with 'Void' (0xEC)
                            file.seek(i.toLong())
                            file.writeByte(0xEC)

                            file.close()
                            org.linphone.core.tools.Log.i("[HistoryEventStore] stripped audio in file: ${filePath}")
                            return
                        }
                    }
                }
            }
        } finally {
            file.close()
        }
    }
}