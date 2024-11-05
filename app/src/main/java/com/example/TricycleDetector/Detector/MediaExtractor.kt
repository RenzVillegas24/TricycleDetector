package com.example.TricycleDetector.Detector

import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.MediaInformation


class MediaInfoExtractor {

    fun getMediaInfo(filePath: String, onInfoExtracted: (MediaFileInfo?) -> Unit) {
        try {
            val mediaInformation = FFprobeKit.getMediaInformation(filePath).mediaInformation
            onInfoExtracted(
                mediaInformation?.let { info ->
                    MediaFileInfo(
                        duration = info.duration.toDouble(),
                        bitrate = info.bitrate.toLong(),
                        format = info.format,
                        filename = info.filename,
                        startTime = info.startTime.toDouble(),
                        size = info.size.toLong(),
                        videoStreams = extractVideoStreams(info),
                        audioStreams = extractAudioStreams(info)
                    )
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            onInfoExtracted(null)
        }
    }

    private fun extractVideoStreams(mediaInfo: MediaInformation): List<VideoStream> {
        return mediaInfo.streams
            .filter { it.type == "video" }
            .map { stream ->
                VideoStream(
                    codec = stream.codec,
                    width = stream.width,
                    height = stream.height,
                    frameRate = stream.averageFrameRate.split("/").let { it[0].toDouble() / it[1].toDouble() },
                    bitrate = stream.bitrate.toLong()
                )
            }
    }

    private fun extractAudioStreams(mediaInfo: MediaInformation): List<AudioStream> {
        return mediaInfo.streams
            .filter { it.type == "audio" }
            .map { stream ->
                AudioStream(
                    codec = stream.codec,
                    sampleRate = stream.sampleRate,
                    channels = stream.allProperties["channels"] as Int?,
                    bitrate = stream.bitrate.toLong()
                )
            }
    }
}

// Data classes to hold the extracted information
data class MediaFileInfo(
    val duration: Double,
    val bitrate: Long?,
    val format: String?,
    val filename: String?,
    val startTime: Double?,
    val size: Long?,
    val videoStreams: List<VideoStream>,
    val audioStreams: List<AudioStream>
)

data class VideoStream(
    val codec: String?,
    val width: Long,
    val height: Long,
    val frameRate: Double,
    val bitrate: Long?
)

data class AudioStream(
    val codec: String?,
    val sampleRate: String,
    val channels: Int?,
    val bitrate: Long?
)