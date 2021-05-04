package org.apache.cordova.camera;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import org.apache.cordova.BuildHelper;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaPreferences;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

public abstract class BaseCameraManager implements ICameraManager {
    protected static final int DATA_URL = 0;              // Return base64 encoded string
    protected static final int FILE_URI = 1;              // Return file uri (content://media/external/images/media/2 for Android)

    protected static final int PHOTOLIBRARY = 0;          // Choose image from picture library (same as SAVEDPHOTOALBUM for Android)
    protected static final int CAMERA = 1;                // Take picture from camera
    protected static final int SAVEDPHOTOALBUM = 2;       // Choose image from picture library (same as PHOTOLIBRARY for Android)

    protected static final int PICTURE = 0;               // allow selection of still pictures only. DEFAULT. Will return format specified via DestinationType
    protected static final int VIDEO = 1;                 // allow selection of video only, ONLY RETURNS URL
    protected static final int ALLMEDIA = 2;              // allow selection from all media types

    public static final int JPEG = 0;                  // Take a picture of type JPEG
    public static final int PNG = 1;                   // Take a picture of type PNG
    public static final String JPEG_TYPE = "jpg";
    public static final String PNG_TYPE = "png";
    public static final String JPEG_EXTENSION = "." + JPEG_TYPE;
    public static final String PNG_EXTENSION = "." + PNG_TYPE;
    public static final String PNG_MIME_TYPE = "image/png";
    public static final String JPEG_MIME_TYPE = "image/jpeg";
    protected static final String GET_PICTURE = "Get Picture";
    protected static final String GET_VIDEO = "Get Video";
    protected static final String GET_All = "Get All";
    protected static final String CROPPED_URI_KEY = "croppedUri";
    protected static final String IMAGE_URI_KEY = "imageUri";

    private static final String TAKE_PICTURE_ACTION = "takePicture";

    public static final int PERMISSION_DENIED_ERROR = 20;
    public static final int TAKE_PIC_SEC = 0;
    public static final int SAVE_TO_ALBUM_SEC = 1;

    protected static final String LOG_TAG = "CameraLauncher";

    //Where did this come from?
    protected static final int CROP_CAMERA = 100;

    protected static final String TIME_FORMAT = "yyyyMMdd_HHmmss";

    protected int quality;                   // Compression quality hint (0-100: 0=low quality & high compression, 100=compress of max quality)
    protected int targetWidth;                // desired width of the image
    protected int targetHeight;               // desired height of the image
    protected Uri imageUri;                   // Uri of captured image
    protected int encodingType;               // Type of encoding to use
    protected int mediaType;                  // What type of media to retrieve
    protected int destType;                   // Source type (needs to be saved for the permission handling)
    protected int srcType;                    // Destination type (needs to be saved for permission handling)
    protected boolean saveToPhotoAlbum;       // Should the picture be saved to the device's photo album
    protected boolean correctOrientation;     // Should the pictures orientation be corrected
    protected boolean orientationCorrected;   // Has the picture's orientation been corrected
    protected boolean allowEdit;              // Should we allow the user to crop the image.

    protected static String[] permissions = {Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};

    public CallbackContext callbackContext;
    protected int numPics;

    protected MediaScannerConnection conn;    // Used to update gallery app with newly-written files
    protected Uri scanMe;                     // Uri of image to be added to content store
    protected Uri croppedUri;
    protected ExifHelper exifData;            // Exif data from source
    protected String applicationId;
    protected CordovaPlugin cordovaPlugin;
    protected CordovaPreferences cordovaPreferences;

    public BaseCameraManager(CordovaPlugin cordovaPlugin, CordovaPreferences cordovaPreferences) {
        this.cordovaPlugin = cordovaPlugin;
        this.cordovaPreferences = cordovaPreferences;
    }

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action          The action to execute.
     * @param args            JSONArry of arguments for the plugin.
     * @param callbackContext The callback id used when calling back into JavaScript.
     * @return A PluginResult object with a status and message.
     */
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        this.callbackContext = callbackContext;
        //Adding an API to CoreAndroid to get the BuildConfigValue
        //This allows us to not make this a breaking change to embedding
        this.applicationId = (String) BuildHelper.getBuildConfigValue(getCordova().getActivity(), "APPLICATION_ID");
        this.applicationId = cordovaPreferences.getString("applicationId", this.applicationId);


        if (action.equals(TAKE_PICTURE_ACTION)) {
            this.srcType = CAMERA;
            this.destType = FILE_URI;
            this.saveToPhotoAlbum = false;
            this.targetHeight = 0;
            this.targetWidth = 0;
            encodingType = JPEG;
            this.mediaType = PICTURE;
            this.quality = 50;

            //Take the values from the arguments if they're not already defined (this is tricky)
            this.destType = args.getInt(1);
            this.srcType = args.getInt(2);
            this.quality = args.getInt(0);
            this.targetWidth = args.getInt(3);
            this.targetHeight = args.getInt(4);
            encodingType = args.getInt(5);
            this.mediaType = args.getInt(6);
            this.allowEdit = args.getBoolean(7);
            this.correctOrientation = args.getBoolean(8);
            this.saveToPhotoAlbum = args.getBoolean(9);

            // If the user specifies a 0 or smaller width/height
            // make it -1 so later comparisons succeed
            if (this.targetWidth < 1) {
                this.targetWidth = -1;
            }
            if (this.targetHeight < 1) {
                this.targetHeight = -1;
            }

            // We don't return full-quality PNG files. The camera outputs a JPEG
            // so requesting it as a PNG provides no actual benefit
            if (this.targetHeight == -1 && this.targetWidth == -1 && this.quality == 100 &&
                    !this.correctOrientation && encodingType == PNG && this.srcType == CAMERA) {
                encodingType = JPEG;
            }

            try {
                if (this.srcType == CAMERA) {
                    this.requestPermissionsAndTakePicture(destType, encodingType);
                } else if ((this.srcType == PHOTOLIBRARY) || (this.srcType == SAVEDPHOTOALBUM)) {
                    // FIXME: Stop always requesting the permission
                    if (!PermissionHelper.hasPermission(cordovaPlugin, Manifest.permission.READ_EXTERNAL_STORAGE)) {
                        PermissionHelper.requestPermission(cordovaPlugin, SAVE_TO_ALBUM_SEC, Manifest.permission.READ_EXTERNAL_STORAGE);
                    } else {
                        this.getImage(this.srcType, destType, encodingType);
                    }
                }
            } catch (IllegalArgumentException e) {
                callbackContext.error("Illegal Argument Exception");
                PluginResult r = new PluginResult(PluginResult.Status.ERROR);
                callbackContext.sendPluginResult(r);
                return true;
            }

            PluginResult r = new PluginResult(PluginResult.Status.NO_RESULT);
            r.setKeepCallback(true);
            callbackContext.sendPluginResult(r);

            return true;
        }
        return false;
    }

    protected Context getContext() {
        return cordovaPlugin.cordova.getContext();
    }

    protected ContentResolver getContentResolver() {
        return getContext().getContentResolver();
    }

    protected Bitmap.CompressFormat getCompressFormatForEncodingType() {
        return encodingType == JPEG ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG;
    }

    /**
     * Send error message to JavaScript.
     *
     * @param err
     */
    public void failPicture(String err) {
        this.callbackContext.error(err);
    }

    protected abstract void requestPermissionsAndTakePicture(int returnType, int encodingType);

    protected abstract void getImage(int srcType, int returnType, int encodingType);

    protected CordovaInterface getCordova() {
        return cordovaPlugin.cordova;
    }

    protected abstract void handleCameraCropResult(int requestCode, int resultCode, Intent intent);

    protected abstract void handleCameraResult(int requestCode, int resultCode, Intent intent);

    protected abstract void handleSaveGalleryResult(int requestCode, int resultCode, Intent intent);

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {

        // Get src and dest types from request code for a Camera Activity
        int srcType = (requestCode / 16) - 1;
        int destType = (requestCode % 16) - 1;

        // If Camera Crop
        if (requestCode >= CROP_CAMERA) {
            handleCameraCropResult(requestCode, resultCode, intent);
        }
        // If CAMERA
        else if (srcType == CAMERA) {
            handleCameraResult(requestCode, resultCode, intent);
        }
        // If retrieving photo from library
        else if ((srcType == PHOTOLIBRARY) || (srcType == SAVEDPHOTOALBUM)) {
            handleSaveGalleryResult(requestCode, resultCode, intent);
        }
    }

    /**
     * Taking or choosing a picture launches another Activity, so we need to implement the
     * save/restore APIs to handle the case where the CordovaActivity is killed by the OS
     * before we get the launched Activity's result.
     */
    public Bundle onSaveInstanceState() {
        Bundle state = new Bundle();
        state.putInt("destType", this.destType);
        state.putInt("srcType", this.srcType);
        state.putInt("mQuality", this.quality);
        state.putInt("targetWidth", this.targetWidth);
        state.putInt("targetHeight", this.targetHeight);
        state.putInt("encodingType", encodingType);
        state.putInt("mediaType", this.mediaType);
        state.putInt("numPics", this.numPics);
        state.putBoolean("allowEdit", this.allowEdit);
        state.putBoolean("correctOrientation", this.correctOrientation);
        state.putBoolean("saveToPhotoAlbum", this.saveToPhotoAlbum);

        if (this.croppedUri != null) {
            state.putString(CROPPED_URI_KEY, croppedUri.toString());
        }

        if (this.imageUri != null) {
            state.putString(IMAGE_URI_KEY, imageUri.toString());
        }

        return state;
    }

    public void onRestoreStateForActivityResult(Bundle state, CallbackContext callbackContext) {
        this.destType = state.getInt("destType");
        this.srcType = state.getInt("srcType");
        this.quality = state.getInt("mQuality");
        this.targetWidth = state.getInt("targetWidth");
        this.targetHeight = state.getInt("targetHeight");
        this.encodingType = state.getInt("encodingType");
        this.mediaType = state.getInt("mediaType");
        this.numPics = state.getInt("numPics");
        this.allowEdit = state.getBoolean("allowEdit");
        this.correctOrientation = state.getBoolean("correctOrientation");
        this.saveToPhotoAlbum = state.getBoolean("saveToPhotoAlbum");

        if (state.containsKey(CROPPED_URI_KEY)) {
            this.croppedUri = Uri.parse(state.getString(CROPPED_URI_KEY));
        }

        if (state.containsKey(IMAGE_URI_KEY)) {
            //I have no idea what type of URI is being passed in
            this.imageUri = Uri.parse(state.getString(IMAGE_URI_KEY));
        }

        this.callbackContext = callbackContext;
    }

    public abstract void takePicture(int returnType, int encodingType);

    public void onRequestPermissionResult(int requestCode, String[] permissions,
                                          int[] grantResults) throws JSONException {
        for (int r : grantResults) {
            if (r == PackageManager.PERMISSION_DENIED) {
                this.callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, PERMISSION_DENIED_ERROR));
                return;
            }
        }
        switch (requestCode) {
            case TAKE_PIC_SEC:
                takePicture(this.destType, encodingType);
                break;
            case SAVE_TO_ALBUM_SEC:
                this.getImage(this.srcType, this.destType, encodingType);
                break;
        }
    }
}
