package com.pinmi.react.printer.adapter;

import static com.pinmi.react.printer.adapter.UtilsImage.getPixelsSlow;
import static com.pinmi.react.printer.adapter.UtilsImage.recollectSlice;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by xiesubin on 2017/9/20.
 */
public class USBPrinterAdapter implements PrinterAdapter {

    @SuppressLint("StaticFieldLeak")
    private static USBPrinterAdapter mInstance;

    private final String LOG_TAG = "RNUSBPrinter";
    private Context mContext;
    private UsbManager mUSBManager;
    private PendingIntent mPermissionIndent;
    private UsbDevice mUsbDevice;
    private UsbDeviceConnection mUsbDeviceConnection;
    private UsbInterface mUsbInterface;
    private UsbEndpoint mEndPoint;
    private Callback mPendingPermissionSuccessCallback;
    private Callback mPendingPermissionErrorCallback;
    private static final String ACTION_USB_PERMISSION = "com.pinmi.react.USBPrinter.USB_PERMISSION";
    private static final String EVENT_USB_DEVICE_ATTACHED = "usbAttached";

    private final static char ESC_CHAR = 0x1B;
    private static final byte[] SELECT_BIT_IMAGE_MODE = { 0x1B, 0x2A, 33 };
    private final static byte[] SET_LINE_SPACE_24 = new byte[] { ESC_CHAR, 0x33, 24 };
    private final static byte[] SET_LINE_SPACE_32 = new byte[] { ESC_CHAR, 0x33, 32 };
    private final static byte[] LINE_FEED = new byte[] { 0x0A };
    private static final byte[] CENTER_ALIGN = { 0x1B, 0X61, 0X31 };

    private USBPrinterAdapter() {
    }

    public static USBPrinterAdapter getInstance() {
        if (mInstance == null) {
            mInstance = new USBPrinterAdapter();
        }
        return mInstance;
    }

    private final BroadcastReceiver mUsbDeviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice usbDevice;
                    if (Build.VERSION.SDK_INT >= 34) {
                        usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
                    } else {
                        usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (usbDevice != null) {
                            Log.i(LOG_TAG,
                                    "success to grant permission for device " + usbDevice.getDeviceId()
                                            + ", vendor_id: " + usbDevice.getVendorId() + " product_id: "
                                            + usbDevice.getProductId());
                            mUsbDevice = usbDevice;
                            if (mPendingPermissionSuccessCallback != null) {
                                mPendingPermissionSuccessCallback.invoke(new USBPrinterDevice(usbDevice).toRNWritableMap());
                            }
                        }
                    } else {
                        if (usbDevice != null) {
                            Toast.makeText(context,
                                    "User refuses to obtain USB device permissions" + usbDevice.getDeviceName(),
                                    Toast.LENGTH_LONG).show();
                        }
                        if (mPendingPermissionErrorCallback != null) {
                            mPendingPermissionErrorCallback.invoke("USB permission denied");
                        }
                    }
                    clearPendingPermissionCallbacks();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (mUsbDevice != null) {
                    Toast.makeText(context, "USB device has been turned off", Toast.LENGTH_LONG).show();
                    closeConnectionIfExists();
                    mUsbDevice = null;
                }
                clearPendingPermissionCallbacks();
            } else if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action)
                    || UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                synchronized (this) {
                    if (mContext != null) {
                        ((ReactApplicationContext) mContext)
                                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                                .emit(EVENT_USB_DEVICE_ATTACHED, null);
                    }
                }
            }
        }
    };

    @SuppressLint("UnspecifiedImmutableFlag")
    public void init(ReactApplicationContext reactContext, Callback successCallback, Callback errorCallback) {
        this.mContext = reactContext;
        this.mUSBManager = (UsbManager) this.mContext.getSystemService(Context.USB_SERVICE);

        // Create explicit intent by setting the package and action
        Intent explicitIntent = new Intent(ACTION_USB_PERMISSION);
        explicitIntent.setPackage(mContext.getPackageName());

        // Use mutable flag for PendingIntent
        int pendingIntentFlag;
        if (Build.VERSION.SDK_INT >= 31) { // Android 12+
            pendingIntentFlag = PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        } else {
            pendingIntentFlag = PendingIntent.FLAG_UPDATE_CURRENT;
        }

        this.mPermissionIndent = PendingIntent.getBroadcast(mContext, 0, explicitIntent, pendingIntentFlag);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);

        // Register the receiver with proper flags based on the API level
        if (Build.VERSION.SDK_INT >= 34) { // Android 14+
            mContext.registerReceiver(mUsbDeviceReceiver, filter, Context.RECEIVER_EXPORTED);
        } else if (Build.VERSION.SDK_INT >= 33) { // Android 13
            mContext.registerReceiver(mUsbDeviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            mContext.registerReceiver(mUsbDeviceReceiver, filter);
        }

        Log.v(LOG_TAG, "RNUSBPrinter initialized");
        successCallback.invoke();
    }

    public void closeConnectionIfExists() {
        if (mUsbDeviceConnection != null) {
            mUsbDeviceConnection.releaseInterface(mUsbInterface);
            mUsbDeviceConnection.close();
            mUsbInterface = null;
            mEndPoint = null;
            mUsbDeviceConnection = null;
        }
    }

    private void clearPendingPermissionCallbacks() {
        mPendingPermissionSuccessCallback = null;
        mPendingPermissionErrorCallback = null;
    }

    public List<PrinterDevice> getDeviceList(Callback errorCallback) {
        List<PrinterDevice> lists = new ArrayList<>();
        if (mUSBManager == null) {
            errorCallback.invoke("USBManager is not initialized while get device list");
            return lists;
        }

        for (UsbDevice usbDevice : mUSBManager.getDeviceList().values()) {
            lists.add(new USBPrinterDevice(usbDevice));
        }
        return lists;
    }

    @Override
    public void selectDevice(PrinterDeviceId printerDeviceId, Callback successCallback, Callback errorCallback) {
        if (mUSBManager == null) {
            errorCallback.invoke("USBManager is not initialized before select device");
            return;
        }

        USBPrinterDeviceId usbPrinterDeviceId = (USBPrinterDeviceId) printerDeviceId;
        UsbDevice matchedUsbDevice = null;
        for (UsbDevice usbDevice : mUSBManager.getDeviceList().values()) {
            if (usbDevice.getVendorId() == usbPrinterDeviceId.getVendorId()
                    && usbDevice.getProductId() == usbPrinterDeviceId.getProductId()) {
                matchedUsbDevice = usbDevice;
                break;
            }
        }

        if (mUsbDevice != null && mUsbDevice.getVendorId() == usbPrinterDeviceId.getVendorId()
                && mUsbDevice.getProductId() == usbPrinterDeviceId.getProductId()
                && matchedUsbDevice != null
                && mUsbDevice.getDeviceId() == matchedUsbDevice.getDeviceId()) {
            Log.i(LOG_TAG, "already selected device, do not need repeat to connect");
            if (!mUSBManager.hasPermission(mUsbDevice)) {
                closeConnectionIfExists();
                mPendingPermissionSuccessCallback = successCallback;
                mPendingPermissionErrorCallback = errorCallback;
                // Request permission with additional logging
                Log.d(LOG_TAG, "Requesting permission for device: " + mUsbDevice.getDeviceName());
                mUSBManager.requestPermission(mUsbDevice, mPermissionIndent);
                if (Build.VERSION.SDK_INT >= 34) {
                    Log.i(LOG_TAG, "On Android 14+, you may need to manually grant USB permissions in settings");
                }
                return;
            }
            successCallback.invoke(new USBPrinterDevice(mUsbDevice).toRNWritableMap());
            return;
        }
        
        closeConnectionIfExists();
        if (mUSBManager.getDeviceList().size() == 0) {
            errorCallback.invoke("Device list is empty, can not choose device");
            return;
        }

        if (matchedUsbDevice != null) {
            Log.v(LOG_TAG, "request for device: vendor_id: " + usbPrinterDeviceId.getVendorId() + ", product_id: "
                    + usbPrinterDeviceId.getProductId());
            closeConnectionIfExists();
            mUsbDevice = matchedUsbDevice; // Always refresh the selected device after a re-plug.
            if (mUSBManager.hasPermission(matchedUsbDevice)) {
                successCallback.invoke(new USBPrinterDevice(matchedUsbDevice).toRNWritableMap());
                return;
            }
            mPendingPermissionSuccessCallback = successCallback;
            mPendingPermissionErrorCallback = errorCallback;
            Log.d(LOG_TAG, "Requesting permission for device: " + matchedUsbDevice.getDeviceName());
            mUSBManager.requestPermission(matchedUsbDevice, mPermissionIndent);
            if (Build.VERSION.SDK_INT >= 34) {
                Log.i(LOG_TAG, "On Android 14+, you may need to manually grant USB permissions in settings");
            }
            return;
        }

        errorCallback.invoke("can not find specified device");
    }

    private boolean openConnection(Callback errorCallback) {
        if (mUsbDevice == null) {
            String errorMsg = "USB Device is not initialized. WHYYY";
            Log.e(LOG_TAG, errorMsg);
            errorCallback.invoke(errorMsg); // Send error back to user
            return false;
        }
        if (mUSBManager == null) {
            String errorMsg = "USB Manager is not initialized";
            Log.e(LOG_TAG, errorMsg);
            errorCallback.invoke(errorMsg); // Send error back to user
            return false;
        }

        if (mUsbDeviceConnection != null) {
            Log.i(LOG_TAG, "USB Connection already connected");
            return true;
        }

        if (mUsbDevice.getInterfaceCount() <= 0) {
            String errorMsg = "USB device has no interfaces";
            Log.e(LOG_TAG, errorMsg);
            errorCallback.invoke(errorMsg);
            return false;
        }

        UsbDeviceConnection usbDeviceConnection = mUSBManager.openDevice(mUsbDevice);
        if (usbDeviceConnection == null) {
            String errorMsg = "Failed to open USB Connection";
            Log.e(LOG_TAG, errorMsg);
            errorCallback.invoke(errorMsg); // Send error back to user
            return false;
        }

        for (int interfaceIndex = 0; interfaceIndex < mUsbDevice.getInterfaceCount(); interfaceIndex++) {
            UsbInterface usbInterface = mUsbDevice.getInterface(interfaceIndex);
            if (usbInterface == null) {
                continue;
            }

            for (int endpointIndex = 0; endpointIndex < usbInterface.getEndpointCount(); endpointIndex++) {
                final UsbEndpoint ep = usbInterface.getEndpoint(endpointIndex);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                        && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    if (usbDeviceConnection.claimInterface(usbInterface, true)) {
                        mEndPoint = ep;
                        mUsbInterface = usbInterface;
                        mUsbDeviceConnection = usbDeviceConnection;
                        Log.i(LOG_TAG, "Device connected");
                        return true;
                    }
                }
            }
        }

        usbDeviceConnection.close();
        String errorMsg = "No writable bulk endpoint found on USB device";
        Log.e(LOG_TAG, errorMsg);
        errorCallback.invoke(errorMsg);
        return false;
    }

public void printRawData(String data, Callback errorCallback) {
        final String rawData = data;
        Log.v(LOG_TAG, "Start to print raw data " + data);
        boolean isConnected = openConnection(errorCallback); // Pass errorCallback
        if (!isConnected) {
            // Error handling already done in openConnection
            return; // Stop further execution if not connected
        }

        Log.v(LOG_TAG, "Connected to device");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mUsbDeviceConnection == null) {
                        String msg = "USB connection is null";
                        Log.e(LOG_TAG, msg);
                        errorCallback.invoke(msg);
                        return;
                    }

                    if (mEndPoint == null) {
                        String msg = "USB endpoint is null";
                        Log.e(LOG_TAG, msg);
                        errorCallback.invoke(msg);
                        return;
                    }

                    byte[] bytes = Base64.decode(rawData, Base64.DEFAULT);

                    mUsbDeviceConnection.bulkTransfer(
                            mEndPoint,
                            bytes,
                            bytes.length,
                            100000
                    );

                    // Log.i(LOG_TAG, "Print completed successfully");
                    // errorCallback.invoke(); // Success - callback with no args

                } catch (Exception e) {
                    Log.e(LOG_TAG, "Printing error", e);
                    errorCallback.invoke("Printing failed: " + e.getMessage());
                }

            }
        }).start();
    }

    public static Bitmap getBitmapFromURL(String src) {
        try {
            URL url = new URL(src);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.connect();
            InputStream input = connection.getInputStream();
            Bitmap myBitmap = BitmapFactory.decodeStream(input);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            myBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);

            return myBitmap;
        } catch (IOException e) {
            // Log exception
            return null;
        }
    }

    @Override
    public void printImageData(final String imageUrl, int imageWidth, int imageHeight, Callback errorCallback) {
        final Bitmap bitmapImage = getBitmapFromURL(imageUrl);

        if (bitmapImage == null) {
            errorCallback.invoke("image not found");
            return;
        }

        Log.v(LOG_TAG, "start to print image data " + bitmapImage);
        boolean isConnected = openConnection(errorCallback);
        if (isConnected) {
            Log.v(LOG_TAG, "Connected to device");
            int[][] pixels = getPixelsSlow(bitmapImage, imageWidth, imageHeight);

            int b = mUsbDeviceConnection.bulkTransfer(mEndPoint, SET_LINE_SPACE_24, SET_LINE_SPACE_24.length, 100000);

            b = mUsbDeviceConnection.bulkTransfer(mEndPoint, CENTER_ALIGN, CENTER_ALIGN.length, 100000);

            for (int y = 0; y < pixels.length; y += 24) {
                // Like I said before, when done sending data,
                // the printer will resume to normal text printing
                mUsbDeviceConnection.bulkTransfer(mEndPoint, SELECT_BIT_IMAGE_MODE, SELECT_BIT_IMAGE_MODE.length,
                        100000);

                // Set nL and nH based on the width of the image
                byte[] row = new byte[] { (byte) (0x00ff & pixels[y].length),
                        (byte) ((0xff00 & pixels[y].length) >> 8) };

                mUsbDeviceConnection.bulkTransfer(mEndPoint, row, row.length, 100000);

                for (int x = 0; x < pixels[y].length; x++) {
                    // for each stripe, recollect 3 bytes (3 bytes = 24 bits)
                    byte[] slice = recollectSlice(y, x, pixels);
                    mUsbDeviceConnection.bulkTransfer(mEndPoint, slice, slice.length, 100000);
                }

                // Do a line feed, if not the printing will resume on the same line
                mUsbDeviceConnection.bulkTransfer(mEndPoint, LINE_FEED, LINE_FEED.length, 100000);
            }

            mUsbDeviceConnection.bulkTransfer(mEndPoint, SET_LINE_SPACE_32, SET_LINE_SPACE_32.length, 100000);
            mUsbDeviceConnection.bulkTransfer(mEndPoint, LINE_FEED, LINE_FEED.length, 100000);
        } else {
            String msg = "failed to connected to device";
            Log.v(LOG_TAG, msg);
            errorCallback.invoke(msg);
        }

    }

    @Override
    public void printImageBase64(final Bitmap bitmapImage, int imageWidth, int imageHeight, Callback errorCallback) {
        if (bitmapImage == null) {
            errorCallback.invoke("image not found");
            return;
        }

        Log.v(LOG_TAG, "start to print image data " + bitmapImage);
        boolean isConnected = openConnection(errorCallback);
        if (isConnected) {
            Log.v(LOG_TAG, "Connected to device");
            int[][] pixels = getPixelsSlow(bitmapImage, imageWidth, imageHeight);

            int b = mUsbDeviceConnection.bulkTransfer(mEndPoint, SET_LINE_SPACE_24, SET_LINE_SPACE_24.length, 100000);

            b = mUsbDeviceConnection.bulkTransfer(mEndPoint, CENTER_ALIGN, CENTER_ALIGN.length, 100000);

            for (int y = 0; y < pixels.length; y += 24) {
                // Like I said before, when done sending data,
                // the printer will resume to normal text printing
                mUsbDeviceConnection.bulkTransfer(mEndPoint, SELECT_BIT_IMAGE_MODE, SELECT_BIT_IMAGE_MODE.length,
                        100000);

                // Set nL and nH based on the width of the image
                byte[] row = new byte[] { (byte) (0x00ff & pixels[y].length),
                        (byte) ((0xff00 & pixels[y].length) >> 8) };

                mUsbDeviceConnection.bulkTransfer(mEndPoint, row, row.length, 100000);

                for (int x = 0; x < pixels[y].length; x++) {
                    // for each stripe, recollect 3 bytes (3 bytes = 24 bits)
                    byte[] slice = recollectSlice(y, x, pixels);
                    mUsbDeviceConnection.bulkTransfer(mEndPoint, slice, slice.length, 100000);
                }

                // Do a line feed, if not the printing will resume on the same line
                mUsbDeviceConnection.bulkTransfer(mEndPoint, LINE_FEED, LINE_FEED.length, 100000);
            }

            mUsbDeviceConnection.bulkTransfer(mEndPoint, SET_LINE_SPACE_32, SET_LINE_SPACE_32.length, 100000);
            mUsbDeviceConnection.bulkTransfer(mEndPoint, LINE_FEED, LINE_FEED.length, 100000);
        } else {
            String msg = "failed to connected to device";
            Log.v(LOG_TAG, msg);
            errorCallback.invoke(msg);
        }

    }
}
