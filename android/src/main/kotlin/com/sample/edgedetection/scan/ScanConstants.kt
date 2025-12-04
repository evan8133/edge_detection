package com.sample.edgedetection.scan

import org.opencv.core.Size

object ScanConstants {
    private const val MAX_IMAGE_HEIGHT = 6000.0 // Maximum allowable image height - increased for better quality
    private const val MAX_IMAGE_WIDTH = 4000.0 // Maximum allowable image width - increased for better quality
    val MAX_SIZE : Size = Size(MAX_IMAGE_WIDTH, MAX_IMAGE_HEIGHT)
}