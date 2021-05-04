package org.apache.cordova.camera;

import android.content.Intent;
import android.os.Bundle;

import org.apache.cordova.CallbackContext;
import org.json.JSONArray;
import org.json.JSONException;

public interface ICameraManager {
    boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException;

    Bundle onSaveInstanceState();

    void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException;

    void onRestoreStateForActivityResult(Bundle state, CallbackContext callbackContext);

    void onActivityResult(int requestCode, int resultCode, Intent intent);
}
