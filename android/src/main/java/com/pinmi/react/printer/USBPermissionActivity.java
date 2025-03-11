package com.pinmi.react.printer;

import android.app.Activity;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Transparent activity that helps with USB permission handling on Android 14+
 */
public class USBPermissionActivity extends Activity {
    private static final String LOG_TAG = "USBPermissionActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Process USB permission intent
        Intent intent = getIntent();
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            Log.d(LOG_TAG, "USB device attached, handling permission request");
            // The intent will be handled by the broadcast receiver in USBPrinterAdapter
        }
        
        // Finish the activity - it's just a helper for handling intents
        finish();
    }
}
