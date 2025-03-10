/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at
         http://www.apache.org/licenses/LICENSE-2.0
       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
 */
package org.apache.cordova.camera;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import org.apache.cordova.CordovaInterface;
import org.apache.cordova.LOG;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static org.apache.cordova.camera.BaseCameraManager.JPEG;
import static org.apache.cordova.camera.BaseCameraManager.JPEG_EXTENSION;
import static org.apache.cordova.camera.BaseCameraManager.JPEG_MIME_TYPE;
import static org.apache.cordova.camera.BaseCameraManager.PNG;
import static org.apache.cordova.camera.BaseCameraManager.PNG_EXTENSION;
import static org.apache.cordova.camera.BaseCameraManager.PNG_MIME_TYPE;

public class FileHelper {
    private static final String TIME_FORMAT = "yyyyMMdd_HHmmss";
    private static final String LOG_TAG = "FileHelper";
    private static final String SCHEME_FILE = "file";

    /**
     * Returns the real path of the given URI string.
     * If the given URI string represents a content:// URI, the real path is retrieved from the media store.
     *
     * @param uriString the URI string of the audio/image/video
     * @param cordova   the current application context
     * @return the full path to the file
     */
    @SuppressWarnings("deprecation")
    public static String getRealPath(Uri uri, CordovaInterface cordova) {
        return FileHelper.getRealPathFromURI(cordova.getActivity(), uri);
    }

    /**
     * Create a file in the applications temporary directory based upon the supplied encoding.
     *
     * @param encodingType of the image to be taken
     * @return a File object pointing to the temporary picture
     */
    public static Uri createCaptureFile(ContentResolver resolver, int encodingType, String namePostfix) {
        String volume = getMediaStoreVolume();
        Uri imagesCollections = MediaStore.Images.Media.getContentUri(volume);
        ContentValues contentValues = new ContentValues();
        String timeStamp = new SimpleDateFormat(TIME_FORMAT).format(new Date());
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_" + timeStamp + "_" + namePostfix + (encodingType == JPEG ? JPEG_EXTENSION : PNG_EXTENSION));
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, getMimetypeForFormat(encodingType));
        //    contentValues.put(MediaStore.Images.Thumbnails., getMimetypeForFormat(encodingType));
        return resolver.insert(imagesCollections, contentValues);
    }

    public static Uri copyToInternalStorage(Context context, Uri sourceUri, int encodingType) throws IOException {
        OutputStream os = null;
        InputStream inputStream = context.getContentResolver().openInputStream(sourceUri);
        Uri dest = createTempFile(context, encodingType);
        try {
            os = context.getContentResolver().openOutputStream(dest);
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

    public static Uri createThumbnails(Uri uri, ContentResolver contentResolver) throws IOException {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.Q) {
            return null;
        }
        Uri thumbnailsUri = saveThumbnails(uri, contentResolver);
        // linkThumbnailsToImage(uri,contentResolver,thumbnailsUri.getLastPathSegment());
        return thumbnailsUri;
    }

    private static Uri saveThumbnails(Uri uri, ContentResolver contentResolver) {
        String id = uri.getLastPathSegment();
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.Images.Thumbnails.WIDTH, 96);
        contentValues.put(MediaStore.Images.Thumbnails.HEIGHT, 96);
        contentValues.put(MediaStore.Images.Thumbnails.KIND, MediaStore.Images.Thumbnails.MICRO_KIND);
        contentValues.put(MediaStore.Images.Thumbnails.DATA, uri.toString());
        contentValues.put(MediaStore.Images.Thumbnails.IMAGE_ID, id);
        Uri volume = MediaStore.Images.Thumbnails.getContentUri(getMediaStoreVolume());
        Uri thumbnailsUri = contentResolver.insert(volume, contentValues);
        return thumbnailsUri;
    }

    private static String getMimetypeForFormat(int outputFormat) {
        if (outputFormat == PNG) return PNG_MIME_TYPE;
        if (outputFormat == JPEG) return JPEG_MIME_TYPE;
        return "";
    }

    public static Uri createTempFile(Context context, int encodingType) {
        String timeStamp = new SimpleDateFormat(TIME_FORMAT).format(new Date());
        String fileName = "IMG_" + timeStamp + (encodingType == JPEG ? JPEG_EXTENSION : PNG_EXTENSION);
        File file = new File(FileHelper.getTempDirectoryPath(context) + "/" + fileName);
        return Uri.fromFile(file);
    }

    public static String getTempDirectoryPath(Context context) {
        File cache = context.getCacheDir();
        // Create the cache directory if it doesn't exist
        cache.mkdirs();
        return cache.getAbsolutePath();
    }

    public static void deleteFileFromMediaStore(ContentResolver contentResolver, Uri uri) {
        if (uri == null) return;
        if (uri.getScheme().equalsIgnoreCase(SCHEME_FILE)) {
            File file = new File(uri.getPath());
            file.deleteOnExit();
        } else {
            contentResolver.delete(uri, null, null);
        }
    }

    /**
     * Returns the real path of the given URI.
     * If the given URI is a content:// URI, the real path is retrieved from the media store.
     *
     * @param uri     the URI of the audio/image/video
     * @param cordova the current application context
     * @return the full path to the file
     */
    public static String getRealPath(String uriString, CordovaInterface cordova) {
        return FileHelper.getRealPath(Uri.parse(uriString), cordova);
    }

    @SuppressLint("NewApi")
    public static String getRealPathFromURI(final Context context, final Uri uri) {
        // DocumentProvider
        if (DocumentsContract.isDocumentUri(context, uri)) {

            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                if ("primary".equalsIgnoreCase(type)) {
                    return Environment.getExternalStorageDirectory() + "/" + split[1];
                }

                // TODO handle non-primary volumes
            }
            // DownloadsProvider
            else if (isDownloadsDocument(uri)) {

                final String id = DocumentsContract.getDocumentId(uri);
                if (id != null && id.length() > 0) {
                    if (id.startsWith("raw:")) {
                        return id.replaceFirst("raw:", "");
                    }
                    try {
                        final Uri contentUri = ContentUris.withAppendedId(
                                Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));

                        return getDataColumn(context, contentUri, null, null);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                } else {
                    return null;
                }
            }
            // MediaProvider
            else if (isMediaDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }

                final String selection = "_id=?";
                final String[] selectionArgs = new String[]{
                        split[1]
                };

                return getDataColumn(context, contentUri, selection, selectionArgs);
            }
        }
        // MediaStore (and general)
        else if ("content".equalsIgnoreCase(uri.getScheme())) {

            // Return the remote address
            if (isGooglePhotosUri(uri))
                return uri.getLastPathSegment();

            return getDataColumn(context, uri, null, null);
        }
        // File
        else if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }

        return null;
    }

    public static String getRealPathFromURI_BelowAPI11(Context context, Uri contentUri) {
        String[] proj = {MediaStore.Images.Media.DATA};
        String result = null;

        try {
            Cursor cursor = context.getContentResolver().query(contentUri, proj, null, null, null);
            int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
            cursor.moveToFirst();
            result = cursor.getString(column_index);

        } catch (Exception e) {
            result = null;
        }
        return result;
    }

    /**
     * Returns an input stream based on given URI string.
     *
     * @param uriString the URI string from which to obtain the input stream
     * @param cordova   the current application context
     * @return an input stream into the data at the given URI or null if given an invalid URI string
     * @throws IOException
     */
    public static InputStream getInputStreamFromUriString(String uriString, CordovaInterface cordova)
            throws IOException {
        InputStream returnValue = null;
        if (uriString.startsWith("content")) {
            Uri uri = Uri.parse(uriString);
            returnValue = cordova.getActivity().getContentResolver().openInputStream(uri);
        } else if (uriString.startsWith("file://")) {
            int question = uriString.indexOf("?");
            if (question > -1) {
                uriString = uriString.substring(0, question);
            }
            if (uriString.startsWith("file:///android_asset/")) {
                Uri uri = Uri.parse(uriString);
                String relativePath = uri.getPath().substring(15);
                returnValue = cordova.getActivity().getAssets().open(relativePath);
            } else {
                // might still be content so try that first
                try {
                    returnValue = cordova.getActivity().getContentResolver().openInputStream(Uri.parse(uriString));
                } catch (Exception e) {
                    returnValue = null;
                }
                if (returnValue == null) {
                    returnValue = new FileInputStream(getRealPath(uriString, cordova));
                }
            }
        } else {
            returnValue = new FileInputStream(uriString);
        }
        return returnValue;
    }

    /**
     * Removes the "file://" prefix from the given URI string, if applicable.
     * If the given URI string doesn't have a "file://" prefix, it is returned unchanged.
     *
     * @param uriString the URI string to operate on
     * @return a path without the "file://" prefix
     */
    public static String stripFileProtocol(String uriString) {
        if (uriString.startsWith("file://")) {
            uriString = uriString.substring(7);
        }
        return uriString;
    }

    public static FileDescriptor createFileDescriptor(Context context, Uri uri, String mode) throws FileNotFoundException {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return context.getContentResolver().openFile(
                    uri, mode, null
            ).getFileDescriptor();
        } else {
            return context.getContentResolver().openFileDescriptor(
                    uri, mode
            ).getFileDescriptor();
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private static void copyOrientationOreo(CordovaInterface cordovaInterface, Uri source, Uri destination) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ExifHelper exif = new ExifHelper();
            int rotate = 0;
            try {
                exif.createInFile(FileHelper.getRealPathFromURI(cordovaInterface.getContext(), source));
                exif.readExifData();
                rotate = exif.getOrientation();

            } catch (IOException e) {
                e.printStackTrace();
            }
            if (rotate != ExifInterface.ORIENTATION_NORMAL) {
                exif.resetOrientation();
            }
            exif.createOutFile(FileHelper.getRealPathFromURI(cordovaInterface.getContext(), destination));
            exif.writeExifData();
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private static void copyOrientationQ(CordovaInterface cordovaInterface, Uri source, Uri destination) {
        int orientation = getOrientation(cordovaInterface.getContext().getContentResolver(), source);
        if (orientation <= 0) {
            return;
        }
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.ORIENTATION, orientation);
        cordovaInterface.getContext().getContentResolver().update(destination, contentValues, null, null);
    }

    public static void copyOrientation(CordovaInterface cordovaInterface, Uri source, Uri destination) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            copyOrientationOreo(cordovaInterface, source, destination);
            return;
        }
        copyOrientationQ(cordovaInterface, source, destination);
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private static int getOrientation(ContentResolver contentResolver, Uri uri) {
        int orientation = -1;
        Cursor cursor = contentResolver.query(uri, new String[]{MediaStore.Images.ImageColumns.ORIENTATION}, null, null);
        DatabaseUtils.dumpCursor(cursor);
        cursor.moveToFirst();
        orientation = cursor.getInt(0);
        cursor.close();
        return orientation;
    }

    public static String getMimeTypeForExtension(String path) {
        String extension = path;
        int lastDot = extension.lastIndexOf('.');
        if (lastDot != -1) {
            extension = extension.substring(lastDot + 1);
        }
        // Convert the URI string to lower case to ensure compatibility with MimeTypeMap (see CB-2185).
        extension = extension.toLowerCase(Locale.getDefault());
        if (extension.equals("3ga")) {
            return "audio/3gpp";
        }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
    }

    /**
     * Returns the mime type of the data specified by the given URI string.
     *
     * @param uriString the URI string of the data
     * @return the mime type of the specified data
     */
    public static String getMimeType(String uriString, CordovaInterface cordova) {
        String mimeType = null;

        Uri uri = Uri.parse(uriString);
        if (uriString.startsWith("content://")) {
            mimeType = cordova.getActivity().getContentResolver().getType(uri);
        } else {
            mimeType = getMimeTypeForExtension(uri.getPath());
        }

        return mimeType;
    }

    /**
     * Get the value of the data column for this Uri. This is useful for
     * MediaStore Uris, and other file-based ContentProviders.
     *
     * @param context       The context.
     * @param uri           The Uri to query.
     * @param selection     (Optional) Filter used in the query.
     * @param selectionArgs (Optional) Selection arguments used in the query.
     * @return The value of the _data column, which is typically a file path.
     * @author paulburke
     */
    public static String getDataColumn(Context context, Uri uri, String selection,
                                       String[] selectionArgs) {

        Cursor cursor = null;
        final String column = "_data";
        final String[] projection = {
                column
        };

        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs,
                    null);
            if (cursor != null && cursor.moveToFirst()) {

                final int column_index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(column_index);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (cursor != null)
                cursor.close();
        }
        return null;
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     * @author paulburke
     */
    public static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     * @author paulburke
     */
    public static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     * @author paulburke
     */
    public static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is Google Photos.
     */
    public static boolean isGooglePhotosUri(Uri uri) {
        return "com.google.android.apps.photos.content".equals(uri.getAuthority());
    }

    public static String getMediaStoreVolume() {
        // On API <= 28, use VOLUME_EXTERNAL instead
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            return MediaStore.VOLUME_EXTERNAL;
        } else {
            return MediaStore.VOLUME_EXTERNAL_PRIMARY;
        }
    }
}
