package com.luci.pano_touch.honghu_gamepad;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.InputDevice;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.databinding.DataBindingUtil;

import com.luci.pano_touch.Constants;
import com.luci.pano_touch.BR;
import com.luci.pano_touch.InputCenter;
import com.luci.pano_touch.R;
import com.luci.pano_touch.Utils;
import com.luci.pano_touch.databinding.SecondaryHonghuGamepadKeysBinding;

import java.util.HashMap;
import java.util.Map;


public class HonghuKeysService extends Service {
    private final static String TAG = "GamepadService";


    private final HonghuKeysBinder mBinder = new HonghuKeysBinder();
    public class HonghuKeysBinder extends Binder {

        public HonghuKeysService getService() {
            return HonghuKeysService.this;
        }
        public Map<String, HonghuKeyBean> getKeyBeanMap() {
            return sKeyBeanMap;
        }

    }
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }


    //    private Joystick2Screen sJoystick2ScreenLeft, sJoystick2ScreenRight;
    private static Context sContext;
    private static SecondaryHonghuGamepadKeysBinding sBinding;
    private static final Map<String, Long> sDownTimeMap = new HashMap<>();
    private static final Map<String, HonghuKeyBean> sKeyBeanMap = new HashMap<>();
    private static HonghuJoystickBeanHold sJoystickLeft;
    private static HonghuJoystickBean sJoystickRight;
    private static final Map<Integer, String> sKeyCodeMap = new HashMap<>();

    private static String spName;
    private static final boolean resetKeyLayoutUponStart = true;

    static {
        System.loadLibrary("native_pano_touch_honghu_gamepad");
    }

    public boolean native_honghu_gamepad_server_created = false;
    private static boolean isRTPressed = false;

    private static WindowManager sWindowManager;
    private static WindowManager.LayoutParams sLayoutParams;
    private static ViewGroup rootView;

    private static Handler sHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case 0:
                    setKeysLayout();
                    break;
                default:
                    break;
            }
        }
    };

    @Override
    public void onCreate() {
        Log.v(TAG, String.format("onCreate of HonghuKeysService"));

        Display mSecondaryDisplay = Utils.getSecondaryDisplay(this);
        Context mSecondaryContext = createDisplayContext(mSecondaryDisplay);
//        ??????????????????databinding: https://stackoverflow.com/a/24580870/9422455
        LayoutInflater mInflater = LayoutInflater.from(new ContextThemeWrapper(mSecondaryContext, R.style.Theme_AppCompat_Translucent));
        sBinding = DataBindingUtil.inflate(mInflater, R.layout.secondary_honghu_gamepad_keys, null, false);

        sBinding.btnSaveSettings.setOnClickListener(v -> {
            saveKeysLayout();
        });

        DisplayManager mSDisplayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display[] mDisplays = mSDisplayManager.getDisplays();
        Display secondDisplay = mDisplays[mDisplays.length - 1];
        if (secondDisplay.getDisplayId() == Display.DEFAULT_DISPLAY) {
            Log.e(TAG, "????????????????????????????????????????????????");
            return;
        }
        spName = getSpName(secondDisplay);
        sContext = createDisplayContext(secondDisplay);

//        ???????????????????????????????????????????????????????????????
        DisplayMetrics displayMetrics = new DisplayMetrics();
        secondDisplay.getMetrics(displayMetrics);
        int mScreenWidth = displayMetrics.widthPixels;
        int mScreenHeight = displayMetrics.heightPixels;
        if (mScreenWidth == 1920) {
            boolean isScreenSmall = false;
        }
        Log.v(TAG, String.format("[BASIC INFO] displayId: %d, width: %d, height: %d",
                sContext.getDisplay().getDisplayId(), mScreenWidth, mScreenHeight));

        Log.v(TAG, "loading local shared preference???");
        loadLocalSharedPreference();
        Log.v(TAG, "loaded local shared preference.");

        sWindowManager = (WindowManager) sContext.getSystemService(WINDOW_SERVICE);
        rootView = (ViewGroup) sBinding.getRoot();
        sLayoutParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        sWindowManager.addView(rootView, sLayoutParams);
        // todo: ?????????????????????????????????????????????????????????????????????
        saveKeysLayout();

        //        ????????????
//        new MainServiceEntryView(secondDisplayContext);
//        Log.v(TAG, "floating window set");

        if (!native_honghu_gamepad_server_created) {
            Log.v(TAG, "??????SOCKET?????????????????????");
            nativeCreateServer();
            nativeRegisterKeyEvent();
            nativeRegisterTouchEvent();
            native_honghu_gamepad_server_created = true;
            Log.v(TAG, "??????SOCKET????????????????????????");
        }
        nativeStartServer();
        Log.d(TAG, "[HonghuKeysService] ???????????????");


    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (native_honghu_gamepad_server_created) {
            nativeStopServer();
//            nativeDestroyServer();
            InputCenter.clear();
        }
    }

    /**
     * @param joystickX x????????????0-15?????????7??????8????????????15
     * @param joystickY y????????????0-15?????????7??????8????????????15
     * @param lr        0???????????????1????????????
     */
    private void handleTouchEvent(int joystickX, int joystickY, int lr) {
        Log.v(TAG, String.format("[joystick] (%d, %d, %d)", joystickX, joystickY, lr));

        if (lr == 1) {
//            Log.v(TAG, String.format("[joystick right] isPressed: %B", sJoystickRight.isPressed));
            sJoystickRight.dispatchJoystickMotionEvent(joystickX, joystickY);
        } else {
//            Log.v(TAG, String.format("[joystick left] isPressed: %B", sJoystickLeft.isPressed));
            sJoystickLeft.dispatchJoystickMotionEvent(joystickX, joystickY);
        }
    }


    /**
     * @param keyCode    ?????????
     * @param isActionUp 0????????????1?????????
     * @param lr         0???????????????1????????????
     */
    private void handleKeyEvent(int keyCode, int isActionUp, int lr) throws InterruptedException {
        Log.v(TAG, String.format("handled key code: %d, %d, %d", keyCode, isActionUp, lr));
        String key = sKeyCodeMap.get(keyCode);
        if (key == null) {
            Log.e(TAG, String.format("??????[%d]????????????", keyCode));
            return;
        }
        if (keyCode == 304) {
            if (isActionUp == 1) {
                Message mMessage = new Message();
                mMessage.what = 0;
                sHandler.sendMessage(mMessage);
            }
            return;
        }
        if (keyCode == 309) {
            if (isActionUp == 1) {
                isRTPressed = false;
            } else {
                isRTPressed = true;
            }
            return;
        }

//        if (keyCode == 304) {
////            LT
//            if (isActionUp == 1) {
//                int SLEEP_MILES = 100;
//                injectKeyEvent(sKeyBeanMap.get("B"), false);
//                Thread.sleep(SLEEP_MILES);
//                injectKeyEvent(sKeyBeanMap.get("B"), true);
//
//                injectKeyEvent(sKeyBeanMap.get("A"), false);
//                Thread.sleep(SLEEP_MILES);
//                injectKeyEvent(sKeyBeanMap.get("A"), true);
//                injectKeyEvent(sKeyBeanMap.get("B"), false);
//                Thread.sleep(SLEEP_MILES);
//                injectKeyEvent(sKeyBeanMap.get("B"), true);
//
//                injectKeyEvent(sKeyBeanMap.get("Y"), false);
//                Thread.sleep(SLEEP_MILES);
//                injectKeyEvent(sKeyBeanMap.get("Y"), true);
//                injectKeyEvent(sKeyBeanMap.get("B"), false);
//                Thread.sleep(SLEEP_MILES);
//                injectKeyEvent(sKeyBeanMap.get("B"), true);
//
//
//                injectKeyEvent(sKeyBeanMap.get("X"), false);
//                Thread.sleep(SLEEP_MILES);
//                injectKeyEvent(sKeyBeanMap.get("X"), true);
//                injectKeyEvent(sKeyBeanMap.get("B"), false);
//                Thread.sleep(SLEEP_MILES);
//                injectKeyEvent(sKeyBeanMap.get("B"), true);
//            }
//
//        } else {

        if (isRTPressed) {
            injectKeyEvent(sKeyBeanMap.get(key), isActionUp == 1, Constants.KEY_COMPOSITE_OFFSET_X, Constants.KEY_COMPOSITE_OFFSET_Y);
        } else {
            injectKeyEvent(sKeyBeanMap.get(key), isActionUp == 1, 0, 0);
        }
//        }
    }


    /**
     * @param bean
     * @param isUp
     * @param offsetX??????????????????????????????????????????????????????????????????
     * @param offsetY??????????????????????????????????????????????????????????????????
     */
    private void injectKeyEvent(HonghuKeyBean bean, boolean isUp, int offsetX, int offsetY) {
        if (!isUp) {
            sDownTimeMap.put(bean.s, SystemClock.uptimeMillis());
        }
        long downTime = sDownTimeMap.get(bean.s);
        long eventTime = SystemClock.uptimeMillis();
        int displayID = sContext.getDisplay().getDisplayId();

        PointerProperties[] mProperties = PointerProperties.createArray(1);
        mProperties[0].id = bean.id;
        mProperties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        PointerCoords[] mCoords = PointerCoords.createArray(1);
        mCoords[0].x = bean.x.get() + offsetX;
        mCoords[0].y = bean.y.get() + offsetY;
        MotionEvent mEvent = MotionEvent.obtain(
                downTime, eventTime, isUp ? MotionEvent.ACTION_UP : MotionEvent.ACTION_DOWN,
                1, mProperties, mCoords, 0, 0, Constants.X_PRECISION, Constants.Y_PRECISION,
                0, 0, InputDevice.SOURCE_TOUCHSCREEN, displayID, 0
        );
        unifiedDispatchMotionEvent(mEvent);
    }

    public void unifiedDispatchMotionEvent(MotionEvent motionEvent) {
        InputCenter.unifiedDispatchMotionEvent(motionEvent);
    }

    private native void nativeCreateServer();

    private native void nativeStartServer();

    private native void nativeStopServer();

    private native void nativeDestroyServer();

    private native void nativeRegisterKeyEvent();

    private native void nativeRegisterTouchEvent();

    public void loadLocalSharedPreference() {
        Map<String, HonghuKey> mHonghuKeyMap = UtilsLoadLayout.parseKeysBase(this, R.xml.keys_base);
        assert mHonghuKeyMap != null;
        // todo: ???????????????????????????????????????
        Map<String, Object> mHonghuKeysLayoutMap = UtilsLoadLayout.parseKeysLayout(this, R.xml.keys_layout_1920x1080);
        assert mHonghuKeysLayoutMap != null;

        Log.v(TAG, mHonghuKeysLayoutMap.toString());
        mHonghuKeysLayoutMap.forEach((k, v) -> {
            String s = k.split("_")[0];
            String property = k.split("_")[1];
//            Log.v(TAG, String.format("parsing key: [%s], property: [%s]", s, property));
            HonghuKey mKey = mHonghuKeyMap.get(s);
            assert mKey != null :
                    String.format("key [%s] not found in mHonghuKeyMap", s);
            switch (property) {
                case "x":
                    mKey.x = (int) v;
                    break;
                case "y":
                    mKey.y = (int) v;
                    break;
                case "visible":
                    mKey.visible = (boolean) v;
                    break;
                default:
                    throw new RuntimeException(String.format("??????????????????%s", property));
            }
        });

        mHonghuKeyMap.forEach((k, v) -> {
//            k ????????????v??????????????????HonghuKey?????????v?????????HonghuKeyBean
            HonghuKey mKey = mHonghuKeyMap.get(k);
            assert mKey != null;
            HonghuKeyBean mHonghuKeyBean;
            if (k.equals("keyJL")) {
                mHonghuKeyBean = new HonghuJoystickBeanHold(
                        this, spName, k, mKey.code, mKey.id,
                        mKey.x, mKey.y, mKey.icon, resetKeyLayoutUponStart);
                sJoystickLeft = (HonghuJoystickBeanHold) mHonghuKeyBean;
            } else if (k.equals("keyJR")) {
                mHonghuKeyBean = new HonghuJoystickBean(
                        this, spName, k, mKey.code, mKey.id,
                        mKey.x, mKey.y, mKey.icon, resetKeyLayoutUponStart);
                sJoystickRight = (HonghuJoystickBean) mHonghuKeyBean;
            } else {
                mHonghuKeyBean = new HonghuKeyBean(
                        this, spName, k, mKey.code, mKey.id,
                        mKey.x, mKey.y, mKey.icon, resetKeyLayoutUponStart);
            }
//            int id = this.getResources().getIdentifier("key" + k, "id", getPackageName());

            Log.v(TAG, String.format("[HonghuKeyBean] key: %s", k));

//            int id = sBinding.getClass().GetProperty(sBinding, null);
            int id = 0;
            try {
                id = (int) BR.class.getField(k).get(BR.class);
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (NoSuchFieldException e) {
                // todo: ?????????????????????????????????????????????????????? LB
//                e.printStackTrace();
            }
            Log.v(TAG, String.format("[HonghuKeyBean] id: %d", id));
            sBinding.setVariable(id, mHonghuKeyBean);
            sKeyCodeMap.put(mKey.code, k);
            sKeyBeanMap.put(k, mHonghuKeyBean);
        });
        Log.v(TAG, String.format("[sKeyCodeMap]: %s", sKeyCodeMap.toString()));
//        Log.v(TAG, String.format("[sKeyBeanMap]: %s", sKeyBeanMap.toString()));
    }

    @SuppressLint("DefaultLocale")
    public static String getSpName(Display display) {
        Point mPoint = new Point();
        display.getRealSize(mPoint);
        return String.format("keys_layout_%dx%d", mPoint.x, mPoint.y);
    }

    /**
     * ???????????????????????????handleKeyEvent?????????????????????????????????????????????UI??????????????????Handler
     */
    public static void setKeysLayout() {
        sLayoutParams.flags =
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        ;
        sWindowManager.updateViewLayout(rootView, sLayoutParams);
        sBinding.btnSaveSettings.setVisibility(View.VISIBLE);
        sBinding.btnInitSettings.setVisibility(View.VISIBLE);
        Toast.makeText(sContext, "???????????????", Toast.LENGTH_SHORT).show();
    }

    public void saveKeysLayout() {
        sLayoutParams.flags =
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        sWindowManager.updateViewLayout(rootView, sLayoutParams);
        sBinding.btnSaveSettings.setVisibility(View.INVISIBLE);
        sBinding.btnInitSettings.setVisibility(View.INVISIBLE);
        Toast.makeText(sContext, "??????????????????", Toast.LENGTH_SHORT).show();
    }
}

