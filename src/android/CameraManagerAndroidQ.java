package org.apache.cordova.camera;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.annotation.RequiresApi;

import com.theartofdev.edmodo.cropper.CropImage;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaPreferences;
import org.apache.cordova.LOG;
import org.apache.cordova.PermissionHelper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

import static android.content.ContentResolver.SCHEME_FILE;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class CameraManagerAndroidQ extends BaseCameraManager {
    private Uri compressedImage = null;

    public CameraManagerAndroidQ(CordovaPlugin cordovaPlugin, CordovaPreferences cordovaPreferences) {
        super(cordovaPlugin, cordovaPreferences);
        permissions = new String[]{Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    @Override
    protected void requestPermissionsAndTakePicture(int returnType, int encodingType) {
        boolean hasReadPermission = PermissionHelper.hasPermission(cordovaPlugin, Manifest.permission.READ_EXTERNAL_STORAGE);
        boolean hasCameraPermission = PermissionHelper.hasPermission(cordovaPlugin, Manifest.permission.CAMERA);

        if (hasCameraPermission && hasReadPermission) {
            takePicture(returnType, encodingType);
        } else if (hasReadPermission) {
            PermissionHelper.requestPermission(cordovaPlugin, TAKE_PIC_SEC, Manifest.permission.CAMERA);
        } else if (hasCameraPermission) {
            PermissionHelper.requestPermissions(cordovaPlugin, TAKE_PIC_SEC,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE});
        } else {
            PermissionHelper.requestPermissions(cordovaPlugin, TAKE_PIC_SEC, permissions);
        }
    }

    @Override
    protected void getImage(int srcType, int returnType, int encodingType) {
        //do nothing
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == CropImage.CROP_IMAGE_ACTIVITY_REQUEST_CODE) {
            croppedUri = CropImage.getActivityResult(intent).getUri();
            try {
                compressCameraResult();
            } catch (IOException e) {
                failPicture("Did not complete!");
            }
        }
        super.onActivityResult(requestCode, resultCode, intent);
    }

    @Override
    protected void handleCameraCropResult(int requestCode, int resultCode, Intent intent) {
    /*    if (resultCode == Activity.RESULT_OK) {
            try {
                compressCameraResult();
            } catch (IOException e) {
                e.printStackTrace();
                LOG.e(LOG_TAG, "Unable to write to file");
            }
        }// If cancelled
        else if (resultCode == Activity.RESULT_CANCELED) {
            failPicture("No Image Selected");
        }
        // If something else
        else {
            failPicture("Did not complete!");
        }*/
        //do nothing
    }


    @Override
    protected void handleCameraResult(int requestCode, int resultCode, Intent intent) {
        // If image available
        if (resultCode == Activity.RESULT_OK) {
            try {
                if (this.allowEdit) {
                    performCrop();
                } else {
                    compressCameraResult();
                }
            } catch (IOException e) {
                e.printStackTrace();
                this.failPicture("Error capturing image.");
            }
        }

        // If cancelled
        else if (resultCode == Activity.RESULT_CANCELED) {
            this.failPicture("No Image Selected");
        }

        // If something else
        else {
            this.failPicture("Did not complete!");
        }
    }

    /**
     * Applies all needed transformation to the image received from the camera.
     */
    private void compressCameraResult() throws IOException {
        // If all this is true we shouldn't compress the image.
        if (targetHeight == -1 && targetWidth == -1 && quality == 100) {
            compressedImage = copyToInternalStorage(allowEdit ? croppedUri : imageUri, "compressed");
        } else {
            Uri savedImageUri = null;
            if (allowEdit) {
                savedImageUri = croppedUri;
            } else {
                savedImageUri = imageUri;
            }
            Bitmap bitmap = getScaledAndRotatedBitmap(savedImageUri);
            File file = createFileInExternalStorage("compressed");
            file.createNewFile();
            //  compressedImage = CustomFileProvider.getUriForFile(getContext(), applicationId + ".cordova.plugin.camera.provider", file);
            compressedImage = Uri.fromFile(file);
            OutputStream outputStream = getContentResolver().openOutputStream(compressedImage);
            Bitmap.CompressFormat compressFormat = getCompressFormatForEncodingType();

            bitmap.compress(compressFormat, this.quality, outputStream);
            outputStream.close();
        }
        returnResultToApp();
    }

    private Bitmap getScaledAndRotatedBitmap(Uri imageUrl) {
        InputStream fileStream = null;
        Bitmap image = null;
        try {
            fileStream = getContentResolver().openInputStream(imageUrl);
            image = BitmapFactory.decodeStream(fileStream);
        } catch (OutOfMemoryError | Exception e) {
            callbackContext.error(e.getLocalizedMessage());
        } finally {
            if (fileStream != null) {
                try {
                    fileStream.close();
                } catch (IOException e) {
                    LOG.d(LOG_TAG, "Exception while closing file input stream.");
                }
            }
        }
        return image;
    }

    /**
     * Brings up the UI to perform crop on passed image URI
     */
    private void performCrop() throws IOException {
        File file = createFileInExternalStorage("cropped");
        file.createNewFile();
        croppedUri = Uri.fromFile(file);
        getCordova().setActivityResultCallback(cordovaPlugin);
        CropImage.activity(imageUri).start(getCordova().getActivity());
    }

    @Override
    protected void handleSaveGalleryResult(int requestCode, int resultCode, Intent intent) {
        //do nothing
    }

    private File createFileInExternalStorage(String postfix) {
        return new File(getAppExternalFilesDir(), generateFileName(postfix));
    }

    private File getAppExternalFilesDir() {
        File file = getContext().getExternalCacheDir();
        if (file != null && !file.exists()) {
            file.mkdirs();
        }
        return file;
    }

    public Uri createCaptureFile(String namePostfix) {
        String volume = getMediaStoreVolume();
        Uri imagesCollections = MediaStore.Images.Media.getContentUri(volume);
        ContentValues contentValues = new ContentValues();
        contentValues.clear();
        contentValues.put(MediaStore.Images.ImageColumns.DISPLAY_NAME, generateFileName(namePostfix));
        contentValues.put(MediaStore.Images.ImageColumns.MIME_TYPE, getMimetypeForFormat(encodingType));
        return getContentResolver().insert(imagesCollections, contentValues);
    }

    public static String getMediaStoreVolume() {
        // On API <= 28, use VOLUME_EXTERNAL instead
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            return MediaStore.VOLUME_EXTERNAL;
        } else {
            return MediaStore.VOLUME_EXTERNAL_PRIMARY;
        }
    }

    private static String getMimetypeForFormat(int outputFormat) {
        if (outputFormat == PNG) return PNG_MIME_TYPE;
        if (outputFormat == JPEG) return JPEG_MIME_TYPE;
        return "";
    }

    /**
     * call with permissions
     *
     * @param returnType
     * @param encodingType
     */
    @Override
    public void takePicture(int returnType, int encodingType) {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        this.imageUri = createCaptureFile("");
        intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        if (getCordova() != null) {
            getCordova().startActivityForResult(cordovaPlugin, intent, (CAMERA + 1) * 16 + returnType + 1);
        }
    }

    private void returnResultToApp() throws IOException {
        if (croppedUri != null) {
            new File(croppedUri.toString()).deleteOnExit();
        }
        croppedUri = null;
        if (imageUri != null) {
            deleteFileFromMediaStore(imageUri);
        }
        imageUri = null;
        if (saveToPhotoAlbum) {
            MediaStore.Images.Media.insertImage(getContentResolver(), compressedImage.toString(), compressedImage.getLastPathSegment(), "");
        }
        callbackContext.success(compressedImage.toString());
        compressedImage = null;
    }

    public Uri copyToInternalStorage(Uri sourceUri, String namePostfix) throws IOException {
        OutputStream os = null;
        InputStream inputStream = getContentResolver().openInputStream(sourceUri);
        Uri dest = createTempFile(namePostfix);
        try {
            os = getContentResolver().openOutputStream(dest);
            byte[] buffer = new byte[4096];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
            os.flush();
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException e) {
                    LOG.d(LOG_TAG, "Exception while closing output stream.");
                }
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    LOG.d(LOG_TAG, "Exception while closing file input stream.");
                }
            }
        }
        return dest;
    }

    public void deleteFileFromMediaStore(Uri uri) {
        if (uri == null) return;
        if (uri.getScheme().equalsIgnoreCase(SCHEME_FILE)) {
            File file = new File(uri.getPath());
            file.deleteOnExit();
        } else {
            getContentResolver().delete(uri, null, null);
        }
    }

    public Uri createTempFile(String namePostfix) {
        File file = new File(getTempDirectoryPath(getContext()) + "/" + generateFileName(namePostfix));
        return Uri.fromFile(file);
    }


    public static String getTempDirectoryPath(Context context) {
        File cache = context.getCacheDir();
        // Create the cache directory if it doesn't exist
        cache.mkdirs();
        return cache.getAbsolutePath();
    }

    private String generateFileName(String namePostfix) {
        String timeStamp = new SimpleDateFormat(TIME_FORMAT).format(new Date());
        return "IMG_" + timeStamp + "_" + namePostfix + (encodingType == JPEG ? JPEG_EXTENSION : PNG_EXTENSION);
    }
}
