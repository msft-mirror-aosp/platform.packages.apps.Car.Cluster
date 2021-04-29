/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.cluster.osdouble;

import static android.car.VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL;
import static android.car.cluster.ClusterHomeManager.UI_TYPE_CLUSTER_HOME;
import static android.car.cluster.ClusterHomeManager.UI_TYPE_CLUSTER_NONE;

import static com.android.car.cluster.osdouble.ClusterOsDoubleApplication.TAG;

import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.util.ArrayMap;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.cluster.sensors.Sensors;
import com.android.car.cluster.view.ClusterViewModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

/**
 * The Activity which plays the role of ClusterOs for the testing.
 */
public class ClusterOsDoubleActivity extends ComponentActivity {
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    // VehiclePropertyGroup
    private static final int SYSTEM = 0x10000000;
    private static final int VENDOR = 0x20000000;
    private static final int MASK = 0xf0000000;

    private static final int VENDOR_CLUSTER_REPORT_STATE = toVendorId(
            VehiclePropertyIds.CLUSTER_REPORT_STATE);
    private static final int VENDOR_SWITCH_UI = toVendorId(VehiclePropertyIds.CLUSTER_SWITCH_UI);

    private DisplayManager mDisplayManager;
    private CarPropertyManager mPropertyManager;

    private SurfaceView mSurfaceView;
    private Rect mUnobscuredBounds;
    private VirtualDisplay mVirtualDisplay;

    private ClusterViewModel mClusterViewModel;
    private final ArrayMap<Sensors.Gear, View> mGearsToIcon = new ArrayMap<>();
    private final ArrayList<View> mUiToButton = new ArrayList<>();
    int mCurrentUi = UI_TYPE_CLUSTER_HOME;
    int mTotalUiSize;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mDisplayManager = getSystemService(DisplayManager.class);

        Car.createCar(getApplicationContext(), /* handler= */ null,
                Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                (car, ready) -> {
                    if (!ready) return;
                    mPropertyManager = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
                    initClusterOsDouble();
                });

        View view = getLayoutInflater().inflate(R.layout.cluster_os_double_activity, null);
        mSurfaceView = view.findViewById(R.id.cluster_display);
        mSurfaceView.getHolder().addCallback(mSurfaceViewCallback);
        setContentView(view);

        registerGear(findViewById(R.id.gear_parked), Sensors.Gear.PARK);
        registerGear(findViewById(R.id.gear_reverse), Sensors.Gear.REVERSE);
        registerGear(findViewById(R.id.gear_neutral), Sensors.Gear.NEUTRAL);
        registerGear(findViewById(R.id.gear_drive), Sensors.Gear.DRIVE);

        mClusterViewModel = new ViewModelProvider(this).get(ClusterViewModel.class);
        mClusterViewModel.getSensor(Sensors.SENSOR_GEAR).observe(this, this::updateSelectedGear);

        registerSensor(findViewById(R.id.info_fuel), mClusterViewModel.getFuelLevel());
        registerSensor(findViewById(R.id.info_speed), mClusterViewModel.getSpeed());
        registerSensor(findViewById(R.id.info_range), mClusterViewModel.getRange());
        registerSensor(findViewById(R.id.info_rpm), mClusterViewModel.getRPM());

        // The order should be matched with ClusterHomeApplication.
        registerUi(findViewById(R.id.btn_car_info));
        registerUi(findViewById(R.id.btn_nav));
        registerUi(findViewById(R.id.btn_music));
        registerUi(findViewById(R.id.btn_phone));
    }

    @Override
    protected void onDestroy() {
        mVirtualDisplay.release();
        mVirtualDisplay = null;
        super.onDestroy();
    }

    private final SurfaceHolder.Callback mSurfaceViewCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.i(TAG, "surfaceCreated, holder: " + holder);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.i(TAG, "surfaceChanged, holder: " + holder + ", size:" + width + "x" + height
                    + ", format:" + format);

            // Create mock unobscured area to report to navigation activity.
            int obscuredWidth = (int) getResources()
                    .getDimension(R.dimen.speedometer_overlap_width);
            int obscuredHeight = (int) getResources()
                    .getDimension(R.dimen.navigation_gradient_height);
            mUnobscuredBounds = new Rect(
                    obscuredWidth,          /* left: size of gauge */
                    obscuredHeight,         /* top: gradient */
                    width - obscuredWidth,  /* right: size of the display - size of gauge */
                    height - obscuredHeight /* bottom: size of display - gradient */
            );

            if (mVirtualDisplay == null) {
                mVirtualDisplay = createVirtualDisplay(holder.getSurface(), width, height);
            } else {
                mVirtualDisplay.setSurface(holder.getSurface());
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.i(TAG, "surfaceDestroyed, holder: " + holder + ", detaching surface from"
                    + " display, surface: " + holder.getSurface());
            // detaching surface is similar to turning off the display
            mVirtualDisplay.setSurface(null);
        }
    };

    private VirtualDisplay createVirtualDisplay(Surface surface, int width, int height) {
        Log.i(TAG, "createVirtualDisplay, surface: " + surface + ", width: " + width
                + "x" + height);
        return mDisplayManager.createVirtualDisplay(/* projection= */ null, "ClusterOsDouble-VD",
                width, height, 160, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, /* callback= */
                null, /* handler= */ null, "ClusterDisplay");
    }

    private void initClusterOsDouble() {
        mPropertyManager.registerCallback(mPropertyEventCallback,
                VENDOR_CLUSTER_REPORT_STATE, CarPropertyManager.SENSOR_RATE_ONCHANGE);
    }

    private final CarPropertyEventCallback mPropertyEventCallback = new CarPropertyEventCallback() {
        @Override
        public void onChangeEvent(CarPropertyValue carProp) {
            int propertyId = carProp.getPropertyId();
            if (DBG) {
                Log.d(TAG, "onChangeEvent: " + propertyId + ", "
                        + Arrays.toString((Object[]) carProp.getValue()));
            }
            if (propertyId == VENDOR_CLUSTER_REPORT_STATE) {
                onClusterReportState((Object[]) carProp.getValue());
            }
        }

        @Override
        public void onErrorEvent(int propId, int zone) {

        }
    };

    private void onClusterReportState(Object[] values) {
        // CLUSTER_REPORT_STATE should have at least 11 elements, check vehicle/2.0/types.hal.
        if (values.length < 11) {
            // TODO(b/186455827): change this to throw the exception as soon as the bug is fixed.
            return;
        }
        // mainUI is the 10th element, refer to vehicle/2.0/types.hal.
        int mainUi = (Integer) values[9];
        if (mainUi >= 0 && mainUi < mTotalUiSize) {
            selectUiButton(mainUi);
        }
    }

    private void selectUiButton(int mainUi) {
        for (int i = 0; i < mTotalUiSize; ++i) {
            View button = mUiToButton.get(i);
            button.setSelected(i == mainUi);
        }
        mCurrentUi = mainUi;
    }

    private static int toVendorId(int propId) {
        return (propId & ~MASK) | VENDOR;
    }

    private <V> void registerSensor(TextView textView, LiveData<V> source) {
        String emptyValue = getString(R.string.info_value_empty);
        source.observe(this, value -> {
            // Need to check that the text is actually different, or else
            // it will generate a bunch of CONTENT_CHANGE_TYPE_TEXT accessability
            // actions. This will cause cts tests to fail when they waitForIdle(),
            // and the system never idles because it's constantly updating these
            // TextViews
            if (value != null && !value.toString().contentEquals(textView.getText())) {
                textView.setText(value.toString());
            }
            if (value == null && !emptyValue.contentEquals(textView.getText())) {
                textView.setText(emptyValue);
            }
        });
    }

    private void registerGear(View view, Sensors.Gear gear) {
        mGearsToIcon.put(gear, view);
    }

    private void updateSelectedGear(Sensors.Gear gear) {
        for (Map.Entry<Sensors.Gear, View> entry : mGearsToIcon.entrySet()) {
            entry.getValue().setSelected(entry.getKey() == gear);
        }
    }

    private void registerUi(View view) {
        int currentUi = mUiToButton.size();
        mUiToButton.add(view);
        mTotalUiSize = mUiToButton.size();
        view.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                Log.d(TAG, "onTouch: " + currentUi);
                switchUi(currentUi);
            }
            return true;
        });
    }

    private void switchUi(int mainUi) {
        mPropertyManager.setProperty(Integer[].class, VENDOR_SWITCH_UI, VEHICLE_AREA_TYPE_GLOBAL,
                new Integer[] {mainUi, UI_TYPE_CLUSTER_NONE});
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "onKeyDown: " + keyCode);
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            switchUi((mCurrentUi + 1) % mTotalUiSize);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
