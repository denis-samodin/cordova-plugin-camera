package org.apache.cordova.camera;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import androidx.annotation.RequiresApi;

import org.apache.cordova.BuildHelper;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaPreferences;
import org.apache.cordova.LOG;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class CameraManagerAndroidLollipop extends BaseCameraManager implements MediaScannerConnection.MediaScannerConnectionClient {
    public CameraManagerAndroidLollipop(CordovaPlugin cordovaPlugin, CordovaPreferences cordovaPreferences) {
        super(cordovaPlugin, cordovaPreferences);
    }

    //--------------------------------------------------------------------------
    // LOCAL METHODS
    //--------------------------------------------------------------------------

    /**
     * Take a picture with the camera.
     * When an image is captured or the camera view is cancelled, the result is returned
     * in CordovaActivity.onActivityResult, which forwards the result to this.onActivityResult.
     * <p>
     * The image can either be returned as a base64 string or a URI that points to the file.
     * To display base64 string in an img tag, set the source to:
     * img.src="data:image/jpeg;base64,"+result;
     * or to display URI in an img tag
     * img.src=result;
     *
     * @param returnType   Set the type of image to return.
     * @param encodingType Compression quality hint (0-100: 0=low quality & high compression, 100=compress of max quality)
     */
    @Override
    public void requestPermissionsAndTakePicture(int returnType, int encodingType) {
        boolean saveAlbumPermission = PermissionHelper.hasPermission(cordovaPlugin, Manifest.permission.READ_EXTERNAL_STORAGE)
                && PermissionHelper.hasPermission(cordovaPlugin, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        boolean takePicturePermission = PermissionHelper.hasPermission(cordovaPlugin, Manifest.permission.CAMERA);

        // CB-10120: The CAMERA permission does not need to be requested unless it is declared
        // in AndroidManifest.xml. This plugin does not declare it, but others may and so we must
        // check the package info to determine if the permission is present.

        if (!takePicturePermission) {
            takePicturePermission = true;
            try {
                PackageManager packageManager = getContext().getPackageManager();
                String[] permissionsInPackage = packageManager.getPackageInfo(getContext().getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
                if (permissionsInPackage != null) {
                    for (String permission : permissionsInPackage) {
                        if (permission.equals(Manifest.permission.CAMERA)) {
                            takePicturePermission = false;
                            break;
                        }
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                // We are requesting the info for our package, so this should
                // never be caught
            }
        }

        if (takePicturePermission && saveAlbumPermission) {
            takePicture(returnType, encodingType);
        } else if (saveAlbumPermission && !takePicturePermission) {
            PermissionHelper.requestPermission(cordovaPlugin, TAKE_PIC_SEC, Manifest.permission.CAMERA);
        } else if (!saveAlbumPermission && takePicturePermission && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            PermissionHelper.requestPermissions(cordovaPlugin, TAKE_PIC_SEC,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE});
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            PermissionHelper.requestPermissions(cordovaPlugin, TAKE_PIC_SEC, permissions);
        } else {
            PermissionHelper.requestPermission(cordovaPlugin, TAKE_PIC_SEC, Manifest.permission.CAMERA);
        }
    }

    public void takePicture(int returnType, int encodingType) {
        // Save the number of images currently on disk for later
        this.numPics = queryImgDB(whichContentStore()).getCount();

        // Let's use the intent and see what happens
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

        // Specify file so that large image is captured and returned
        this.imageUri = FileHelper.createCaptureFile(getContentResolver(), encodingType, "");
        intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
        //We can write to this URI, this will hopefully allow us to write files to get to the next step
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        if (getCordova() != null) {
            // Let's check to make sure the camera is actually installed. (Legacy Nexus 7 code)
            PackageManager mPm = this.getContext().getPackageManager();
            if (intent.resolveActivity(mPm) != null) {
                getCordova().startActivityForResult(cordovaPlugin, intent, (CAMERA + 1) * 16 + returnType + 1);
            } else {
                LOG.d(LOG_TAG, "Error: You don't have a default camera.  Your device may not be CTS complaint.");
            }
        }
    }


    /**
     * Get image from photo library.
     *
     * @param srcType      The album to get image from.
     * @param returnType   Set the type of image to return.
     * @param encodingType
     */
    // TODO: Images selected from SDCARD don't display correctly, but from CAMERA ALBUM do!
    // TODO: Images from kitkat filechooser not going into crop function
    public void getImage(int srcType, int returnType, int encodingType) {
        Intent intent = new Intent();
        String title = GET_PICTURE;
        croppedUri = null;
        if (this.mediaType == PICTURE) {
            intent.setType("image/*");
            if (this.allowEdit) {
                intent.setAction(Intent.ACTION_PICK);
                intent.putExtra("crop", "true");
                if (targetWidth > 0) {
                    intent.putExtra("outputX", targetWidth);
                }
                if (targetHeight > 0) {
                    intent.putExtra("outputY", targetHeight);
                }
                if (targetHeight > 0 && targetWidth > 0 && targetWidth == targetHeight) {
                    intent.putExtra("aspectX", 1);
                    intent.putExtra("aspectY", 1);
                }
                croppedUri = FileHelper.createCaptureFile(getContentResolver(), JPEG, "");
                intent.putExtra(MediaStore.EXTRA_OUTPUT, croppedUri);
            } else {
                intent.setAction(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
            }
        } else if (this.mediaType == VIDEO) {
            intent.setType("video/*");
            title = GET_VIDEO;
            intent.setAction(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
        } else if (this.mediaType == ALLMEDIA) {
            // I wanted to make the type 'image/*, video/*' but this does not work on all versions
            // of android so I had to go with the wildcard search.
            intent.setType("*/*");
            title = GET_All;
            intent.setAction(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
        }
        if (getCordova() != null) {
           getCordova().startActivityForResult(cordovaPlugin, Intent.createChooser(intent,
                    new String(title)), (srcType + 1) * 16 + returnType + 1);
        }
    }



    /**
     * Brings up the UI to perform crop on passed image URI
     *
     * @param picUri
     */
    private void performCrop(Uri picUri, int destType, Intent cameraIntent) {
        try {
            Intent cropIntent = new Intent("com.android.camera.action.CROP");
            // indicate image type and Uri

            cropIntent.setDataAndType(picUri, "image/*");
            // set crop properties
            cropIntent.putExtra("crop", "true");


            // indicate output X and Y
            if (targetWidth > 0) {
                cropIntent.putExtra("outputX", targetWidth);
            }
            if (targetHeight > 0) {
                cropIntent.putExtra("outputY", targetHeight);
            }
            if (targetHeight > 0 && targetWidth > 0 && targetWidth == targetHeight) {
                cropIntent.putExtra("aspectX", 1);
                cropIntent.putExtra("aspectY", 1);
            }
            // create new file handle to get full resolution crop
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                croppedUri = Uri.fromFile(new File(FileHelper.getRealPath(FileHelper.createCaptureFile(getContentResolver(), encodingType, ""), getCordova())));
            } else {
                croppedUri = FileHelper.createCaptureFile(getContentResolver(), encodingType, "");
            }
            cropIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            cropIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            cropIntent.putExtra("output", croppedUri);


            // start the activity - we handle returning in onActivityResult
            PackageManager packageManager = getContext().getPackageManager();
            List<ResolveInfo> list = packageManager.queryIntentActivities(cropIntent, 0);
            int size = list.size();
            if (getCordova() != null && cropIntent.resolveActivity(packageManager) != null && size > 0) {
               getCordova().startActivityForResult(cordovaPlugin,
                        cropIntent, CROP_CAMERA + destType);
            } else if (getCordova()!= null || size == 0) {
                int type = CROP_CAMERA + destType - CROP_CAMERA;
                try {
                    processResultFromCamera(type, cropIntent);
                } catch (IOException e) {
                    e.printStackTrace();
                    LOG.e(LOG_TAG, "Unable to write to file");
                }
            }
        } catch (ActivityNotFoundException anfe) {
            LOG.e(LOG_TAG, "Crop operation not supported on this device");
            try {
                processResultFromCamera(destType, cameraIntent);
            } catch (IOException e) {
                e.printStackTrace();
                LOG.e(LOG_TAG, "Unable to write to file");
            }
        }
    }

    /**
     * Applies all needed transformation to the image received from the camera.
     *
     * @param destType In which form should we return the image
     * @param intent   An Intent, which can return result data to the caller (various data can be attached to Intent "extras").
     */
    private void processResultFromCamera(int destType, Intent intent) throws IOException {
        int rotate = 0;

        // Create an ExifHelper to save the exif data that is lost during compression
        ExifHelper exif = new ExifHelper();

        Uri sourceUri = (this.allowEdit && this.croppedUri != null) ?
                this.croppedUri :
                this.imageUri;

        Bitmap bitmap = null;
        Uri savedImageUri = null;

        // CB-5479 When this option is given the unchanged image should be saved
        // in the gallery and the modified image is saved in the temporary
        // directory
        if (this.saveToPhotoAlbum) {
            if (saveToPhotoAlbum) {
                savedImageUri = allowEdit ? croppedUri : imageUri;
            }
        }

        // If sending base64 image back
        if (destType == DATA_URL) {
            saveCameraPhotoToFile(sourceUri, intent);
        }

        // If sending filename back
        else if (destType == FILE_URI) {
            // If all this is true we shouldn't compress the image.
            if (this.targetHeight == -1 && this.targetWidth == -1 && this.quality == 100 &&
                    !this.correctOrientation) {

                // If we saved the uncompressed photo to the album, we can just
                // return the URI we already created
                if (this.saveToPhotoAlbum) {
                    returnResultToApp(savedImageUri, encodingType);
                } else {
                    Uri uri = FileHelper.createCaptureFile(getContentResolver(), encodingType, "");
                    if (this.allowEdit && this.croppedUri != null) {
                        writeUncompressedImage(croppedUri, uri);
                    } else {
                        Uri imageUri = this.imageUri;
                        writeUncompressedImage(imageUri, uri);
                    }

                    returnResultToApp(uri, encodingType);
                }
            } else {
                Uri uri = FileHelper.createCaptureFile(getContentResolver(), encodingType, "");
                bitmap = getScaledAndRotatedBitmap(sourceUri);

                // Double-check the bitmap.
                if (bitmap == null) {
                    LOG.d(LOG_TAG, "I either have a null image path or bitmap");
                    this.failPicture("Unable to create bitmap!");
                    return;
                }


                // Add compressed version of captured image to returned media store Uri
                OutputStream outputStream = getContentResolver().openOutputStream(uri);
                Bitmap.CompressFormat compressFormat = getCompressFormatForEncodingType(encodingType);

                bitmap.compress(compressFormat, quality, outputStream);
                outputStream.close();

                // Restore orientation data to file
                if (encodingType == JPEG) {
                    FileHelper.copyOrientation(getCordova(), sourceUri, uri);
                }

                // Send Uri back to JavaScript for viewing image
                returnResultToApp(uri, encodingType);
            }
        } else {
            throw new IllegalStateException();
        }

        this.cleanup(FILE_URI, this.imageUri, savedImageUri, bitmap);
    }

    private void returnResultToApp(String uri, int encodingType) throws IOException {
        returnResultToApp(Uri.parse(uri), encodingType);
    }

    private void returnResultToApp(Uri uri, int encodingType) throws IOException {
        Uri destUri = FileHelper.copyToInternalStorage(getContext(), uri, encodingType);
        if (!saveToPhotoAlbum) {
            if (allowEdit) {
                FileHelper.deleteFileFromMediaStore(getContentResolver(), croppedUri);
            }
            FileHelper.deleteFileFromMediaStore(getContentResolver(), imageUri);
        } else {
            FileHelper.deleteFileFromMediaStore(getContentResolver(), allowEdit ? imageUri : croppedUri);
        }
        callbackContext.success(destUri.toString());
    }

    private void saveCameraPhotoToFile(Uri sourceUri, Intent intent) throws IOException {
        Bitmap bitmap = getScaledAndRotatedBitmap(sourceUri);

        if (bitmap == null) {
            // Try to get the bitmap from intent.
            bitmap = (Bitmap) intent.getExtras().get("data");
        }

        // Double-check the bitmap.
        if (bitmap == null) {
            LOG.d(LOG_TAG, "I either have a null image path or bitmap");
            this.failPicture("Unable to create bitmap!");
            return;
        }

        this.processPicture(bitmap, encodingType);

        if (!this.saveToPhotoAlbum) {
            checkForDuplicateImage(DATA_URL);
        }
    }

    private Bitmap.CompressFormat getCompressFormatForEncodingType(int encodingType) {
        return encodingType == JPEG ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG;
    }

    /**
     * Converts output image format int value to string value of mime type.
     *
     * @param outputFormat int Output format of camera API.
     *                     Must be value of either JPEG or PNG constant
     * @return String String value of mime type or empty string if mime type is not supported
     */
    private String getMimetypeForFormat(int outputFormat) {
        if (outputFormat == PNG) return PNG_MIME_TYPE;
        if (outputFormat == JPEG) return JPEG_MIME_TYPE;
        return "";
    }


    private Uri outputModifiedBitmap(Bitmap bitmap, Uri uri) throws IOException {
        Uri outputUri = FileHelper.createCaptureFile(getContentResolver(), encodingType, "modified");
        OutputStream os = getContext().getContentResolver().openOutputStream(outputUri);
        Bitmap.CompressFormat compressFormat = getCompressFormatForEncodingType(encodingType);
        bitmap.compress(compressFormat, this.quality, os);
        os.close();

        if (exifData != null && encodingType == JPEG) {
            try {
                if (this.correctOrientation && this.orientationCorrected) {
                    exifData.resetOrientation();
                }
                exifData.createOutFile(outputUri.toString());
                exifData.writeExifData();
                exifData = null;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return outputUri;
    }


    /**
     * Applies all needed transformation to the image received from the gallery.
     *
     * @param destType In which form should we return the image
     * @param intent   An Intent, which can return result data to the caller (various data can be attached to Intent "extras").
     */
    private void processResultFromGallery(int destType, Intent intent) throws IOException {
        Uri uri = intent.getData();
        if (uri == null) {
            if (croppedUri != null) {
                uri = croppedUri;
            } else {
                this.failPicture("null data from photo library");
                return;
            }
        }

        String fileLocation = FileHelper.getRealPath(uri, getCordova());
        LOG.d(LOG_TAG, "File location is: " + fileLocation);

        String uriString = uri.toString();
        String finalLocation = fileLocation != null ? fileLocation : uriString;
        String mimeType = FileHelper.getMimeType(uriString, getCordova());

        if (finalLocation == null) {
            this.failPicture("Error retrieving result.");
        } else {

            // If you ask for video or the selected file doesn't have JPEG or PNG mime type
            //  there will be no attempt to resize any returned data
            if (this.mediaType == VIDEO || !(JPEG_MIME_TYPE.equalsIgnoreCase(mimeType) || PNG_MIME_TYPE.equalsIgnoreCase(mimeType))) {
                returnResultToApp(finalLocation, encodingType);
            } else {

                // This is a special case to just return the path as no scaling,
                // rotating, nor compressing needs to be done
                if (this.targetHeight == -1 && this.targetWidth == -1 &&
                        destType == FILE_URI && !this.correctOrientation &&
                        mimeType != null && mimeType.equalsIgnoreCase(getMimetypeForFormat(encodingType))) {
                    returnResultToApp(finalLocation, encodingType);
                } else {
                    Bitmap bitmap = null;
                    try {
                        bitmap = getScaledAndRotatedBitmap(uri);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    if (bitmap == null) {
                        LOG.d(LOG_TAG, "I either have a null image path or bitmap");
                        this.failPicture("Unable to create bitmap!");
                        return;
                    }

                    // If sending base64 image back
                    if (destType == DATA_URL) {
                        this.processPicture(bitmap, encodingType);
                    }

                    // If sending filename back
                    else if (destType == FILE_URI) {
                        // Did we modify the image?
                        if ((this.targetHeight > 0 && this.targetWidth > 0) ||
                                (this.correctOrientation && this.orientationCorrected) ||
                                !mimeType.equalsIgnoreCase(getMimetypeForFormat(encodingType))) {
                            try {
                                returnResultToApp(this.outputModifiedBitmap(bitmap, uri), encodingType);
                            } catch (Exception e) {
                                e.printStackTrace();
                                this.failPicture("Error retrieving image.");
                            }
                        } else {
                            returnResultToApp(finalLocation, encodingType);
                        }
                    }
                    if (bitmap != null) {
                        bitmap.recycle();
                        bitmap = null;
                    }
                    System.gc();
                }
            }
        }

    }

    @Override
    protected void handleCameraCropResult(int requestCode, int resultCode, Intent intent) {
        if (resultCode == Activity.RESULT_OK) {

            // Because of the inability to pass through multiple intents, this hack will allow us
            // to pass arcane codes back.
            destType = requestCode - CROP_CAMERA;
            try {
                processResultFromCamera(destType, intent);
            } catch (IOException e) {
                e.printStackTrace();
                LOG.e(LOG_TAG, "Unable to write to file");
            }

        }// If cancelled
        else if (resultCode == Activity.RESULT_CANCELED) {
            this.failPicture("No Image Selected");
        }

        // If something else
        else {
            this.failPicture("Did not complete!");
        }
    }

    @Override
    protected void handleCameraResult(int requestCode, int resultCode, Intent intent) {
        // If image available
        if (resultCode == Activity.RESULT_OK) {
            try {
                if (this.allowEdit) {
                    if (destType != FILE_URI) {
                        Uri tmpFile = FileHelper.createCaptureFile(getContentResolver(), encodingType, "");
                        saveCameraPhotoToFile(tmpFile, intent);
                        performCrop(tmpFile, destType, intent);
                    } else {
                        if (FileHelper.createThumbnails(imageUri, getContentResolver()) != null || Build.VERSION.SDK_INT != Build.VERSION_CODES.Q) {
                            performCrop(imageUri, destType, intent);
                        } else {
                            this.processResultFromCamera(destType, intent);
                        }
                    }
                } else {
                    this.processResultFromCamera(destType, intent);
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

    @Override
    protected void handleSaveGalleryResult(int requestCode, int resultCode, Intent intent) {
        if (resultCode == Activity.RESULT_OK && intent != null) {
            final Intent i = intent;
            final int finalDestType = destType;
            getCordova().getThreadPool().execute(new Runnable() {
                public void run() {
                    try {
                        processResultFromGallery(finalDestType, i);
                    } catch (IOException e) {
                        e.printStackTrace();
                        CameraManagerAndroidLollipop.this.failPicture("No Image Selected");
                    }
                }
            });
        } else if (resultCode == Activity.RESULT_CANCELED) {
            this.failPicture("No Image Selected");
        } else {
            this.failPicture("Selection did not complete!");
        }
    }

    private int exifToDegrees(int exifOrientation) {
        if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_90) {
            return 90;
        } else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_180) {
            return 180;
        } else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_270) {
            return 270;
        } else {
            return 0;
        }
    }

    /**
     * Write an inputstream to local disk
     *
     * @param fis  - The InputStream to write
     * @param dest - Destination on disk to write to
     * @throws FileNotFoundException
     * @throws IOException
     */
    private void writeUncompressedImage(InputStream fis, Uri dest) throws FileNotFoundException,
            IOException {
        OutputStream os = null;
        try {
            os = getContentResolver().openOutputStream(dest);
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) != -1) {
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
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    LOG.d(LOG_TAG, "Exception while closing file input stream.");
                }
            }
        }
    }

    /**
     * In the special case where the default width, height and quality are unchanged
     * we just write the file out to disk saving the expensive Bitmap.compress function.
     *
     * @param src
     * @throws FileNotFoundException
     * @throws IOException
     */
    private void writeUncompressedImage(Uri src, Uri dest) throws FileNotFoundException,
            IOException {

        InputStream fis = FileHelper.getInputStreamFromUriString(src.toString(), getCordova());
        writeUncompressedImage(fis, dest);

    }

    /**
     * Return a scaled and rotated bitmap based on the target width and height
     *
     * @param imageUrl
     * @return
     * @throws IOException
     */
    private Bitmap getScaledAndRotatedBitmap(Uri imageUrl) throws IOException {
        // If no new width or height were specified, and orientation is not needed return the original bitmap
        if (this.targetWidth <= 0 && this.targetHeight <= 0 && !(this.correctOrientation)) {
            InputStream fileStream = null;
            Bitmap image = null;
            try {
                fileStream = getContentResolver().openInputStream(imageUrl);
                image = BitmapFactory.decodeStream(fileStream);
            } catch (OutOfMemoryError e) {
                callbackContext.error(e.getLocalizedMessage());
            } catch (Exception e) {
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


        /*  Copy the inputstream to a temporary file on the device.
            We then use this temporary file to determine the width/height/orientation.
            This is the only way to determine the orientation of the photo coming from 3rd party providers (Google Drive, Dropbox,etc)
            This also ensures we create a scaled bitmap with the correct orientation

             We delete the temporary file once we are done
         */
        File localFile = null;
        Uri galleryUri = null;
        int rotate = 0;
        try {
            InputStream fileStream = getContentResolver().openInputStream(imageUrl);
            if (fileStream != null) {
                // Generate a temporary file
                //Work in internal storage, don't need use Scoped Storage
                String timeStamp = new SimpleDateFormat(TIME_FORMAT).format(new Date());
                String fileName = "IMG_" + timeStamp + (encodingType == JPEG ? JPEG_EXTENSION : PNG_EXTENSION);
                localFile = new File(FileHelper.getTempDirectoryPath(getContext()) + fileName);
                galleryUri = Uri.fromFile(localFile);
                writeUncompressedImage(fileStream, galleryUri);
                try {
                    String mimeType = FileHelper.getMimeType(imageUrl.toString(), getCordova());
                    if (JPEG_MIME_TYPE.equalsIgnoreCase(mimeType)) {
                        //  ExifInterface doesn't like the file:// prefix
                        String filePath = galleryUri.toString().replace("file://", "");
                        // read exifData of source
                        exifData = new ExifHelper();
                        exifData.createInFile(filePath);
                        exifData.readExifData();
                        // Use ExifInterface to pull rotation information
                        if (this.correctOrientation) {
                            ExifInterface exif = new ExifInterface(filePath);
                            rotate = exifToDegrees(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED));
                        }
                    }
                } catch (Exception oe) {
                    LOG.w(LOG_TAG, "Unable to read Exif data: " + oe.toString());
                    rotate = 0;
                }
            }
        } catch (Exception e) {
            LOG.e(LOG_TAG, "Exception while getting input stream: " + e.toString());
            return null;
        }


        try {
            // figure out the original width and height of the image
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            InputStream fileStream = null;
            try {
                fileStream = FileHelper.getInputStreamFromUriString(galleryUri.toString(), getCordova());
                BitmapFactory.decodeStream(fileStream, null, options);
            } finally {
                if (fileStream != null) {
                    try {
                        fileStream.close();
                    } catch (IOException e) {
                        LOG.d(LOG_TAG, "Exception while closing file input stream.");
                    }
                }
            }


            //CB-2292: WTF? Why is the width null?
            if (options.outWidth == 0 || options.outHeight == 0) {
                return null;
            }

            // User didn't specify output dimensions, but they need orientation
            if (this.targetWidth <= 0 && this.targetHeight <= 0) {
                this.targetWidth = options.outWidth;
                this.targetHeight = options.outHeight;
            }

            // Setup target width/height based on orientation
            int rotatedWidth, rotatedHeight;
            boolean rotated = false;
            if (rotate == 90 || rotate == 270) {
                rotatedWidth = options.outHeight;
                rotatedHeight = options.outWidth;
                rotated = true;
            } else {
                rotatedWidth = options.outWidth;
                rotatedHeight = options.outHeight;
            }

            // determine the correct aspect ratio
            int[] widthHeight = calculateAspectRatio(rotatedWidth, rotatedHeight);


            // Load in the smallest bitmap possible that is closest to the size we want
            options.inJustDecodeBounds = false;
            options.inSampleSize = calculateSampleSize(rotatedWidth, rotatedHeight, widthHeight[0], widthHeight[1]);
            Bitmap unscaledBitmap = null;
            try {
                fileStream = FileHelper.getInputStreamFromUriString(galleryUri.toString(), getCordova());
                unscaledBitmap = BitmapFactory.decodeStream(fileStream, null, options);
            } finally {
                if (fileStream != null) {
                    try {
                        fileStream.close();
                    } catch (IOException e) {
                        LOG.d(LOG_TAG, "Exception while closing file input stream.");
                    }
                }
            }
            if (unscaledBitmap == null) {
                return null;
            }

            int scaledWidth = (!rotated) ? widthHeight[0] : widthHeight[1];
            int scaledHeight = (!rotated) ? widthHeight[1] : widthHeight[0];

            Bitmap scaledBitmap = Bitmap.createScaledBitmap(unscaledBitmap, scaledWidth, scaledHeight, true);
            if (scaledBitmap != unscaledBitmap) {
                unscaledBitmap.recycle();
                unscaledBitmap = null;
            }
            if (this.correctOrientation && (rotate != 0)) {
                Matrix matrix = new Matrix();
                matrix.setRotate(rotate);
                try {
                    scaledBitmap = Bitmap.createBitmap(scaledBitmap, 0, 0, scaledBitmap.getWidth(), scaledBitmap.getHeight(), matrix, true);
                    this.orientationCorrected = true;
                } catch (OutOfMemoryError oom) {
                    this.orientationCorrected = false;
                }
            }
            return scaledBitmap;
        } finally {
            // delete the temporary copy
            if (localFile != null) {
                localFile.delete();
            }
        }

    }

    /**
     * Maintain the aspect ratio so the resulting image does not look smooshed
     *
     * @param origWidth
     * @param origHeight
     * @return
     */
    public int[] calculateAspectRatio(int origWidth, int origHeight) {
        int newWidth = this.targetWidth;
        int newHeight = this.targetHeight;

        // If no new width or height were specified return the original bitmap
        if (newWidth <= 0 && newHeight <= 0) {
            newWidth = origWidth;
            newHeight = origHeight;
        }
        // Only the width was specified
        else if (newWidth > 0 && newHeight <= 0) {
            newHeight = (int) ((double) (newWidth / (double) origWidth) * origHeight);
        }
        // only the height was specified
        else if (newWidth <= 0 && newHeight > 0) {
            newWidth = (int) ((double) (newHeight / (double) origHeight) * origWidth);
        }
        // If the user specified both a positive width and height
        // (potentially different aspect ratio) then the width or height is
        // scaled so that the image fits while maintaining aspect ratio.
        // Alternatively, the specified width and height could have been
        // kept and Bitmap.SCALE_TO_FIT specified when scaling, but this
        // would result in whitespace in the new image.
        else {
            double newRatio = newWidth / (double) newHeight;
            double origRatio = origWidth / (double) origHeight;

            if (origRatio > newRatio) {
                newHeight = (newWidth * origHeight) / origWidth;
            } else if (origRatio < newRatio) {
                newWidth = (newHeight * origWidth) / origHeight;
            }
        }

        int[] retval = new int[2];
        retval[0] = newWidth;
        retval[1] = newHeight;
        return retval;
    }

    /**
     * Figure out what ratio we can load our image into memory at while still being bigger than
     * our desired width and height
     *
     * @param srcWidth
     * @param srcHeight
     * @param dstWidth
     * @param dstHeight
     * @return
     */
    public static int calculateSampleSize(int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        final float srcAspect = (float) srcWidth / (float) srcHeight;
        final float dstAspect = (float) dstWidth / (float) dstHeight;

        if (srcAspect > dstAspect) {
            return srcWidth / dstWidth;
        } else {
            return srcHeight / dstHeight;
        }
    }

    /**
     * Creates a cursor that can be used to determine how many images we have.
     *
     * @return a cursor
     */
    private Cursor queryImgDB(Uri contentStore) {
        return getContentResolver().query(
                contentStore,
                new String[]{MediaStore.Images.Media._ID},
                null,
                null,
                null);
    }

    /**
     * Cleans up after picture taking. Checking for duplicates and that kind of stuff.
     *
     * @param newImage
     */
    private void cleanup(int imageType, Uri oldImage, Uri newImage, Bitmap bitmap) {
        if (bitmap != null) {
            bitmap.recycle();
        }

        // Clean up initial camera-written image file.
        (new File(FileHelper.stripFileProtocol(oldImage.toString()))).delete();

        checkForDuplicateImage(imageType);
        // Scan for the gallery to update pic refs in gallery
        if (saveToPhotoAlbum && newImage != null) {
            this.scanForGallery(newImage);
        }

        System.gc();
    }

    /**
     * Used to find out if we are in a situation where the Camera Intent adds to images
     * to the content store. If we are using a FILE_URI and the number of images in the DB
     * increases by 2 we have a duplicate, when using a DATA_URL the number is 1.
     *
     * @param type FILE_URI or DATA_URL
     */
    private void checkForDuplicateImage(int type) {
        int diff = 1;
        Uri contentStore = whichContentStore();
        Cursor cursor = queryImgDB(contentStore);
        int currentNumOfImages = cursor.getCount();

        if (type == FILE_URI && this.saveToPhotoAlbum) {
            diff = 2;
        }

        // delete the duplicate file if the difference is 2 for file URI or 1 for Data URL
        if ((currentNumOfImages - numPics) == diff) {
            cursor.moveToLast();
            int id = Integer.valueOf(cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media._ID)));
            if (diff == 2) {
                id--;
            }
            Uri uri = Uri.parse(contentStore + "/" + id);
            getContentResolver().delete(uri, null, null);
            cursor.close();
        }
    }

    /**
     * Determine if we are storing the images in internal or external storage
     *
     * @return Uri
     */
    private Uri whichContentStore() {
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        } else {
            return MediaStore.Images.Media.INTERNAL_CONTENT_URI;
        }
    }

    /**
     * Compress bitmap using jpeg, convert to Base64 encoded string, and return to JavaScript.
     *
     * @param bitmap
     */
    public void processPicture(Bitmap bitmap, int encodingType) {
        ByteArrayOutputStream jpegData = new ByteArrayOutputStream();
        Bitmap.CompressFormat compressFormat = getCompressFormatForEncodingType(encodingType);

        try {
            if (bitmap.compress(compressFormat, quality, jpegData)) {
                byte[] code = jpegData.toByteArray();
                byte[] output = Base64.encode(code, Base64.NO_WRAP);
                String js_out = new String(output);
                returnResultToApp(js_out, encodingType);
            }
        } catch (Exception e) {
            this.failPicture("Error compressing image.");
        }
    }

    /**
     * Send error message to JavaScript.
     *
     * @param err
     */
    public void failPicture(String err) {
        this.callbackContext.error(err);
    }

    private void scanForGallery(Uri newImage) {
        this.scanMe = newImage;
        if (this.conn != null) {
            this.conn.disconnect();
        }
        this.conn = new MediaScannerConnection(getCordova().getActivity().getApplicationContext(), this);
        conn.connect();
    }

    public void onMediaScannerConnected() {
        try {
            this.conn.scanFile(this.scanMe.toString(), "image/*");
        } catch (IllegalStateException e) {
            LOG.e(LOG_TAG, "Can't scan file in MediaScanner after taking picture");
        }

    }

    public void onScanCompleted(String path, Uri uri) {
        this.conn.disconnect();
    }
}

