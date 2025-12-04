package com.sample.edgedetection.scan
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import android.widget.Toast
import com.sample.edgedetection.EdgeDetectionHandler
import com.sample.edgedetection.REQUEST_CODE
import com.sample.edgedetection.SourceManager
import com.sample.edgedetection.crop.CropActivity
import com.sample.edgedetection.processor.Corners
import com.sample.edgedetection.processor.processPicture
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Core.ROTATE_90_CLOCKWISE
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import android.util.Size as SizeB

class ScanPresenter constructor(
    private val context: Context,
    private val iView: IScanView.Proxy,
    private val initialBundle: Bundle
) :
    SurfaceHolder.Callback, Camera.PictureCallback, Camera.PreviewCallback {
    private val TAG: String = "ScanPresenter"
    private var mCamera: Camera? = null
    private val mSurfaceHolder: SurfaceHolder = iView.getSurfaceView().holder
    private val executor: ExecutorService
    private val proxySchedule: Scheduler
    private var busy: Boolean = false
    private var mCameraLensFacing: String? = null
    private var flashEnabled: Boolean = false

    private var mLastClickTime = 0L
    private var shutted: Boolean = true

    init {
        mSurfaceHolder.addCallback(this)
        executor = Executors.newSingleThreadExecutor()
        proxySchedule = Schedulers.from(executor)
    }

    private fun isOpenRecently(): Boolean {
        if (SystemClock.elapsedRealtime() - mLastClickTime < 3000) {
            return true
        }
        mLastClickTime = SystemClock.elapsedRealtime()
        return false
    }

    fun start() {
        mCamera?.startPreview() ?:
        Log.i(TAG, "mCamera startPreview")
    }

    fun stop() {
        mCamera?.stopPreview() ?:
        Log.i(TAG, "mCamera stopPreview")
    }

    val canShut: Boolean get() = shutted

    fun shut() {
        if (isOpenRecently()) {
            Log.i(TAG, "NOT Taking click")
            return
        }
        busy = true
        shutted = false
        Log.i(TAG, "try to focus")

        mCamera?.autoFocus { b, _ ->
            Log.i(TAG, "focus result: $b")
            mCamera?.enableShutterSound(false)
            mCamera?.takePicture(null, null, this)
        }

    }

    fun toggleFlash() {
        try {
            flashEnabled = !flashEnabled
            val parameters = mCamera?.parameters
            parameters?.flashMode =
                if (flashEnabled) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
            mCamera?.parameters = parameters
            mCamera?.startPreview()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun updateCamera() {
        if (null == mCamera) {
            return
        }
        mCamera?.stopPreview()
        try {
            mCamera?.setPreviewDisplay(mSurfaceHolder)
        } catch (e: IOException) {
            e.printStackTrace()
            return
        }
        mCamera?.setPreviewCallback(this)
        mCamera?.startPreview()
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun getCameraCharacteristics(id: String): CameraCharacteristics {
        return cameraManager.getCameraCharacteristics(id)
    }

    private fun getBackFacingCameraId(): String? {
        for (camID in cameraManager.cameraIdList) {
            val lensFacing =
                getCameraCharacteristics(camID)?.get(CameraCharacteristics.LENS_FACING)!!
            if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                mCameraLensFacing = camID
                break
            }
        }
        return mCameraLensFacing
    }

    private fun initCamera() {

        try {
            mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK)
        } catch (e: RuntimeException) {
            e.stackTrace
            Toast.makeText(context, "cannot open camera, please grant camera", Toast.LENGTH_SHORT)
                .show()
            return
        }

        val cameraCharacteristics =
            cameraManager.getCameraCharacteristics(getBackFacingCameraId()!!)

        val size = iView.getCurrentDisplay()?.let {
            getPreviewOutputSize(
                it, cameraCharacteristics, SurfaceHolder::class.java
            )
        }

        Log.i(TAG, "Selected preview size: ${size?.width}x${size?.height}")

        size?.width?.toString()?.let { Log.i(TAG, it) }
        val param = mCamera?.parameters
        param?.setPreviewSize(size?.width ?: 1920, size?.height ?: 1080)
        val display = iView.getCurrentDisplay()
        val point = Point()

        display?.getRealSize(point)

        val displayWidth = minOf(point.x, point.y)
        val displayHeight = maxOf(point.x, point.y)
        val displayRatio = displayWidth.toFloat().div(displayHeight.toFloat())
        val previewRatio = size?.height?.toFloat()?.div(size.width.toFloat()) ?: displayRatio
        
        Log.i(TAG, "Display: ${displayWidth}x${displayHeight}, ratio: $displayRatio")
        Log.i(TAG, "Preview ratio: $previewRatio")
        
        // Don't adjust the surface view size - keep it full screen
        // The camera will handle the aspect ratio internally
        // This prevents the offset issue with the detection overlay

        val supportPicSize = mCamera?.parameters?.supportedPictureSizes
        
        // Log all supported picture sizes for debugging
        supportPicSize?.forEach {
            Log.d(TAG, "Supported picture size: ${it.width}x${it.height}")
        }
        
        supportPicSize?.sortByDescending { it.width.times(it.height) }
        
        // Find the highest resolution picture size for best quality
        var pictureSize = supportPicSize?.firstOrNull()

        if (null == pictureSize) {
            Log.e(TAG, "can not get picture size")
        } else {
            param?.setPictureSize(pictureSize.width, pictureSize.height)
            Log.i(TAG, "Picture size set to: ${pictureSize.width}x${pictureSize.height}")
        }
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_AUTOFOCUS)) {
            // Try to use macro mode for better document scanning
            if (mCamera!!.parameters.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                param?.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                Log.i(TAG, "enabling continuous autofocus")
            } else if (mCamera!!.parameters.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                param?.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
                Log.i(TAG, "enabling auto focus")
            }
        } else {
            Log.i(TAG, "autofocus not available")
        }
        
        // Set white balance and scene mode for better document scanning
        if (mCamera!!.parameters.supportedWhiteBalance?.contains(Camera.Parameters.WHITE_BALANCE_AUTO) == true) {
            param?.whiteBalance = Camera.Parameters.WHITE_BALANCE_AUTO
        }
        
        // Optimize JPEG quality for better results
        param?.jpegQuality = 100

        param?.flashMode = Camera.Parameters.FLASH_MODE_OFF

        mCamera?.parameters = param
        mCamera?.setDisplayOrientation(90)
        mCamera?.enableShutterSound(false)
    }

    private fun matrixResizer(sourceMatrix: Mat): Mat {
        val sourceSize: Size = sourceMatrix.size()
        Log.i(TAG, "matrixResizer input size: ${sourceSize.height} x ${sourceSize.width}")
        
        val copied: Mat
        if (sourceSize.height < sourceSize.width) {
            copied = Mat()
            Core.rotate(sourceMatrix, copied, ROTATE_90_CLOCKWISE)
            Log.i(TAG, "Rotated image, new size: ${copied.size().height} x ${copied.size().width}")
        } else {
            copied = sourceMatrix.clone()
        }
        
        val copiedSize: Size = copied.size()
        return if (copiedSize.width > ScanConstants.MAX_SIZE.width || copiedSize.height > ScanConstants.MAX_SIZE.height) {
            val widthRatio: Double = ScanConstants.MAX_SIZE.width / copiedSize.width
            val heightRatio: Double = ScanConstants.MAX_SIZE.height / copiedSize.height
            val useRatio = if(widthRatio < heightRatio) widthRatio else heightRatio
            val resizedImage = Mat()
            val newSize = Size(copiedSize.width * useRatio, copiedSize.height * useRatio)
            Log.i(TAG, "Resizing to: ${newSize.height} x ${newSize.width}")
            // Use INTER_AREA for downscaling (better quality than default)
            Imgproc.resize(copied, resizedImage, newSize, 0.0, 0.0, Imgproc.INTER_AREA)
            if (copied != sourceMatrix) {
                copied.release()
            }
            resizedImage
        } else {
            copied
        }
    }
    fun detectEdge(pic: Mat) {
        Log.i(TAG, "Original image size: ${pic.size().height} x ${pic.size().width}")
        
        if (pic.empty() || pic.size().height <= 0 || pic.size().width <= 0) {
            Log.e(TAG, "Invalid image matrix")
            return
        }
        
        val resizedMat = matrixResizer(pic)
        
        Log.i(TAG, "Resized image size: ${resizedMat.size().height} x ${resizedMat.size().width}")
        
        if (resizedMat.empty() || resizedMat.size().height <= 0 || resizedMat.size().width <= 0) {
            Log.e(TAG, "Failed to resize image")
            return
        }
        
        SourceManager.corners = processPicture(resizedMat)
        Imgproc.cvtColor(resizedMat, resizedMat, Imgproc.COLOR_RGB2BGRA)
        SourceManager.pic = resizedMat
        val cropIntent = Intent(context, CropActivity::class.java)
        cropIntent.putExtra(EdgeDetectionHandler.INITIAL_BUNDLE, this.initialBundle)
        (context as Activity).startActivityForResult(cropIntent, REQUEST_CODE)
    }

    override fun surfaceCreated(p0: SurfaceHolder) {
        initCamera()
    }

    override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) {
        updateCamera()
    }

    override fun surfaceDestroyed(p0: SurfaceHolder) {
        synchronized(this) {
            mCamera?.stopPreview()
            mCamera?.setPreviewCallback(null)
            mCamera?.release()
            mCamera = null
        }
    }

    override fun onPictureTaken(p0: ByteArray?, p1: Camera?) {
        Log.i(TAG, "on picture taken")
        Observable.just(p0)
            .subscribeOn(proxySchedule)
            .subscribe ({ data ->
                try {
                    val pictureSize = p1?.parameters?.pictureSize
                    Log.i(TAG, "picture size: " + pictureSize.toString())
                    val mat = Mat(
                        Size(
                            pictureSize?.width?.toDouble() ?: 1920.toDouble(),
                            pictureSize?.height?.toDouble() ?: 1080.toDouble()
                        ), CvType.CV_8U
                    )
                    mat.put(0, 0, data)
                    val pic = Imgcodecs.imdecode(mat, Imgcodecs.CV_LOAD_IMAGE_UNCHANGED)
                    mat.release()
                    
                    if (!pic.empty()) {
                        Core.rotate(pic, pic, Core.ROTATE_90_CLOCKWISE)
                        detectEdge(pic)
                    } else {
                        Log.e(TAG, "Failed to decode image")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing picture: ${e.message}")
                    e.printStackTrace()
                } finally {
                    shutted = true
                    busy = false
                }
            }, { error ->
                Log.e(TAG, "Error in onPictureTaken: ${error.message}")
                error.printStackTrace()
                shutted = true
                busy = false
            })
    }

    override fun onPreviewFrame(p0: ByteArray?, p1: Camera?) {
        if (busy) {
            return
        }
        busy = true
        try {
            Observable.just(p0)
                .observeOn(proxySchedule)
                .doOnError {}
                .subscribe({
                    val parameters = p1?.parameters
                    val width = parameters?.previewSize?.width
                    val height = parameters?.previewSize?.height
                    val yuv = YuvImage(
                        p0, parameters?.previewFormat ?: 0, width ?: 1080, height
                            ?: 1920, null
                    )
                    val out = ByteArrayOutputStream()
                    yuv.compressToJpeg(Rect(0, 0, width ?: 1080, height ?: 1920), 100, out)
                    val bytes = out.toByteArray()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val img = Mat()
                    Utils.bitmapToMat(bitmap, img)
                    bitmap.recycle()
                    Core.rotate(img, img, Core.ROTATE_90_CLOCKWISE)
                    try {
                        out.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }

                    Observable.create<Corners> {
                        val corner = processPicture(img)
                        busy = false
                        if (null != corner && corner.corners.size == 4) {
                            it.onNext(corner)
                        } else {
                            it.onError(Throwable("paper not detected"))
                        }
                    }.observeOn(AndroidSchedulers.mainThread())
                        .subscribe({
                            iView.getPaperRect().onCornersDetected(it)

                        }, {
                            iView.getPaperRect().onCornersNotDetected()
                        })
                }, { throwable -> Log.e(TAG, throwable.message!!) })
        } catch (e: Exception) {
            print(e.message)
        }

    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */

    class SmartSize(width: Int, height: Int) {
        var size = SizeB(width, height)
        var long = max(size.width, size.height)
        var short = min(size.width, size.height)
        override fun toString() = "SmartSize(${long}x${short})"
    }

    /** Standard High Definition size for pictures and video */
    private val SIZE_1080P: SmartSize = SmartSize(1920, 1080)

    /** Returns a [SmartSize] object for the given [Display] */
    private fun getDisplaySmartSize(display: Display): SmartSize {
        val outPoint = Point()
        display.getRealSize(outPoint)
        return SmartSize(outPoint.x, outPoint.y)
    }

    /**
     * Returns the largest available PREVIEW size. For more information, see:
     * https://d.android.com/reference/android/hardware/camera2/CameraDevice and
     * https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap
     */
    private fun <T> getPreviewOutputSize(
        display: Display,
        characteristics: CameraCharacteristics,
        targetClass: Class<T>,
        format: Int? = null
    ): SizeB {

        // Find which is smaller: screen or 1080p
        val screenSize = getDisplaySmartSize(display)
        val hdScreen = screenSize.long >= SIZE_1080P.long || screenSize.short >= SIZE_1080P.short
        val maxSize = if (hdScreen) SIZE_1080P else screenSize

        // If image format is provided, use it to determine supported sizes; else use target class
        val config = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )!!
        if (format == null)
            assert(StreamConfigurationMap.isOutputSupportedFor(targetClass))
        else
            assert(config.isOutputSupportedFor(format))
        val allSizes = if (format == null)
            config.getOutputSizes(targetClass) else config.getOutputSizes(format)

        // Get available sizes and sort them by area from largest to smallest
        val validSizes = allSizes
            .sortedWith(compareBy { it.height * it.width })
            .map { SmartSize(it.width, it.height) }.reversed()

        // Then, get the largest output size that is smaller or equal than our max size
        return validSizes.first { it.long <= maxSize.long && it.short <= maxSize.short }.size
    }
}
