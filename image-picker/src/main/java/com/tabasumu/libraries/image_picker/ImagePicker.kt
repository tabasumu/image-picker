package com.tabasumu.libraries.image_picker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageView
import com.canhub.cropper.options
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tabasumu.libraries.image_picker.databinding.ImagePickerDialogBinding
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.*

/**
 * mobile-app
 * @author Victor Oyando
 * @email oyandovic@gmail.com
 * Created 6/7/22 at 9:06 AM
 */
class ImagePicker internal constructor(
    builder: Builder
) : BottomSheetDialogFragment() {

    private val mContext: Context = builder.context
    private val mCropType: CropType? = builder.type
    private val mPick: PickFrom = builder.pick
    private val callback: ((uri: Uri, image: File) -> Unit)? = builder.callback
    private var _binding: ImagePickerDialogBinding? = null
    private val binding get() = _binding!!

    private lateinit var currentImagePath: String
    private lateinit var uri: Uri

    class Builder constructor(private val activity: FragmentActivity) {

        @get:JvmSynthetic
        @set: JvmSynthetic
        internal var context: Context = activity.applicationContext

        @get:JvmSynthetic
        @set: JvmSynthetic
        internal var type: CropType? = null

        @get:JvmSynthetic
        @set: JvmSynthetic
        internal var pick: PickFrom = PickFrom.ALL

        @get:JvmSynthetic
        @set: JvmSynthetic
        internal var callback: ((uri: Uri, image: File) -> Unit)? = null


        fun cropType(type: CropType) = apply {
            this.type = type
        }

        fun pickFrom(from: PickFrom) = apply {
            this.pick = from
        }

        fun resultUri(callback: (uri: Uri, image: File) -> Unit) = apply {
            this.callback = callback
        }

        fun show() = apply {
            ImagePicker(this).show(activity)
        }

    }

    enum class PickFrom {
        CAMERA, GALLERY, ALL
    }

    enum class CropType {
        SQUARE, FREE, RECTANGLE_HORIZONTAL, RECTANGLE_VERTICAL
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ImagePickerDialogBinding.inflate(inflater, container, false)
        return binding.root.apply {
            isVisible = mPick == PickFrom.ALL
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        when (mPick) {
            PickFrom.CAMERA -> initPickFromCamera()
            PickFrom.GALLERY -> takePictureFromGallery()
            PickFrom.ALL -> {
                binding.camera.setOnClickListener {
                    initPickFromCamera()
                }

                binding.gallery.setOnClickListener {
                    takePictureFromGallery()
                }
            }
        }
    }

    private fun initPickFromCamera() {
        if (!hasCameraPermission())
            prepareCamera()
        else
            takePictureFromCamera()
    }

    /**
     * CROP IMAGE
     */
    private val cropPictureLauncher = registerForActivityResult(CropImageContract()) { result ->
        when (result.isSuccessful) {
            false -> {
                Snackbar.make(binding.root, "Failed Cropping Image", Snackbar.LENGTH_SHORT)
                    .show()
                this.dismiss()
            }
            true -> {
                val uri = result.uriContent

                if (uri == null) {
                    exitWithCroppingError()
                    return@registerForActivityResult
                }

                val defaultFile = File(uri.path)
                val file = if (defaultFile.exists()) defaultFile else getFile(mContext, uri)

                if (file == null) {
                    exitWithCroppingError()
                    return@registerForActivityResult
                }

                returnResult(uri)

            }
        }

    }

    private fun exitWithCroppingError() {
        Snackbar.make(binding.root, "Failed Cropping Image", Snackbar.LENGTH_SHORT)
            .show()
        this.dismiss()
    }

    /**
     * LAUNCH CAMERA
     */
    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            when {
                success.not() -> dismiss()
                mCropType != null -> uri.cropImage()
                else -> returnResult(uri)
            }
        }

    /**
     * LAUNCH GALLERY
     */
    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            when {
                uri == null -> dismiss()
                mCropType != null -> uri.cropImage()
                else -> returnResult(uri)
            }
        }

    /**
     *  PERMISSION CONFIRMATION DIALOG
     */
    private fun showConfirmDialog(content: String, requestPermission: () -> Unit) {

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Camera Permission")
            .setMessage(content)
            .setNegativeButton("deny") { _, _ -> }
            .setPositiveButton("grant") { _, _ -> requestPermission.invoke() }
            .show()

    }

    /**
     * CAMERA PERMISSION
     */
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                takePictureFromCamera()
            } else {
                prepareCamera()
            }
        }

    /**
     * PREPARE CAMERA
     */
    private fun prepareCamera() {

        val cameraPermission =
            ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)

        when (cameraPermission == PackageManager.PERMISSION_GRANTED) {
            true -> takePictureFromCamera()
            false -> {
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    requestCameraPermission(false)
                    StoreManager.rationaleShown(requireContext(), Manifest.permission.CAMERA)
                    return
                }

                val hadDenied = StoreManager.rationalesList(requireContext())
                    .contains(Manifest.permission.CAMERA)

                requestCameraPermission(hadDenied)

            }
        }

    }

    /**
     * TAKE PICTURE
     */
    private fun takePictureFromCamera() {

        Log.i("CAMERA", "takePictureFromCamera: get picture called ")

        val file: File? = try {
            createImageFile()
        } catch (exception: IOException) {
            exception.printStackTrace()
            null
        }

        file?.also {
            uri = FileProvider.getUriForFile(
                requireContext(),
                requireContext().applicationContext.packageName,
                it
            )

            cameraLauncher.launch(uri)
        }

    }

    /**
     * TAKE IMAGE FROM GALLERY
     */
    private fun takePictureFromGallery() {
        galleryLauncher.launch("image/*")
    }

    /**
     * REQUEST CAMERA PERMISSION
     */
    private fun requestCameraPermission(hadCompletelyDenied: Boolean) {

        val activity = requireActivity()

        if (hadCompletelyDenied)
            showConfirmDialog(
                content = getString(R.string.camera_permission_rationale)
            ) {
                openPermissionInSettings(activity)
            }
        else
            cameraPermissionLauncher.launch((Manifest.permission.CAMERA))
    }

    /**
     * CHECK CAMERA PERMISSION
     */
    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        requireContext(),
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * NAVIGATE TO APP SETTINGS
     */
    private fun openPermissionInSettings(activity: FragmentActivity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", activity.applicationContext.packageName, null)
        intent.data = uri
        activity.startActivity(intent)
    }

    /**
     * CREATE IMAGE TEMPORAL FILE
     */
    @Throws(IOException::class)
    private fun createImageFile(): File {

        val storageDir: File =
            requireActivity().getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
        return File.createTempFile(
            "_${Date().time}_",
            ".jpg",
            storageDir
        ).apply {
            currentImagePath = absolutePath
        }
    }

    private fun Uri.cropImage() {

        cropPictureLauncher.launch(
            options(uri = this) {
                setActivityMenuIconColor(Color.WHITE)
                setActivityTitle("Crop")
                setCropMenuCropButtonTitle("SAVE")
                setCropMenuCropButtonIcon(R.drawable.ic_baseline_done_24)
                setAllowRotation(true)
                setAllowCounterRotation(true)
                setAllowFlipping(true)
                setShowCropOverlay(true)
                setScaleType(CropImageView.ScaleType.CENTER)

                when (mCropType) {
                    CropType.SQUARE -> {
                        setAspectRatio(1, 1)
                        setCropShape(CropImageView.CropShape.OVAL)
                        setFixAspectRatio(true)
                    }
                    else -> {
                        setCropShape(CropImageView.CropShape.RECTANGLE)
                    }
                }

                setGuidelines(CropImageView.Guidelines.ON)
            }
        )

    }

    private fun getFile(context: Context, uri: Uri): File? {
        val destinationFilename =
            File(context.filesDir.path + File.separatorChar + queryName(context, uri))
        try {
            context.contentResolver.openInputStream(uri).use { ins ->
                createFileFromStream(
                    ins!!,
                    destinationFilename
                )
            }
        } catch (ex: Exception) {
            Log.e("Save File ", ex.localizedMessage)
            ex.printStackTrace()
        }
        return destinationFilename
    }

    private fun createFileFromStream(ins: InputStream, destination: File?) {
        try {
            FileOutputStream(destination).use { os ->
                val buffer = ByteArray(4096)
                var length: Int
                while (ins.read(buffer).also { length = it } > 0) {
                    os.write(buffer, 0, length)
                }
                os.flush()
            }
        } catch (ex: Exception) {
            Log.e("Save File", ex.localizedMessage)
            ex.printStackTrace()
        }
    }

    private fun queryName(context: Context, uri: Uri): String? {
        val returnCursor = context.contentResolver.query(uri, null, null, null, null)
        return if (returnCursor != null) {
            val nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            returnCursor.moveToFirst()
            val name = returnCursor.getString(nameIndex)
            returnCursor.close()
            name
        } else null
    }

    private fun returnResult(uri: Uri) {

        val defaultFile = File(uri.path)
        val file = if (defaultFile.exists()) defaultFile else getFile(mContext, uri)

        if (file == null) {
            exitWithCroppingError()
            return
        }

        callback?.invoke(uri, file)
        dismiss()

    }

}