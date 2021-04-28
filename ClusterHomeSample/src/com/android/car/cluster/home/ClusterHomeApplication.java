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

package com.android.car.cluster.home;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.car.CarOccupantZoneManager.DISPLAY_TYPE_INSTRUMENT_CLUSTER;
import static android.car.cluster.ClusterHomeManager.ClusterHomeCallback;
import static android.car.cluster.ClusterHomeManager.UI_TYPE_CLUSTER_HOME;
import static android.car.cluster.ClusterHomeManager.UI_TYPE_CLUSTER_NONE;
import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_STARTING;
import static android.car.user.CarUserManager.USER_LIFECYCLE_EVENT_TYPE_UNLOCKED;
import static android.content.Intent.ACTION_MAIN;
import static android.hardware.input.InputManager.INJECT_INPUT_EVENT_MODE_ASYNC;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.Application;
import android.app.IActivityManager;
import android.app.IActivityTaskManager;
import android.app.TaskInfo;
import android.app.TaskStackListener;
import android.car.Car;
import android.car.CarOccupantZoneManager;
import android.car.cluster.ClusterHomeManager;
import android.car.cluster.ClusterState;
import android.car.input.CarInputManager;
import android.car.input.CarInputManager.CarInputCaptureCallback;
import android.car.user.CarUserManager;
import android.car.user.CarUserManager.UserLifecycleListener;
import android.content.ComponentName;
import android.content.Intent;
import android.hardware.input.InputManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;
import android.view.Display;
import android.view.KeyEvent;

import java.util.List;

public final class ClusterHomeApplication extends Application {
    public static final String TAG = "ClusterHome";
    private static final boolean DBG = false;
    private static final int UI_TYPE_HOME = UI_TYPE_CLUSTER_HOME;
    private static final int UI_TYPE_MAPS = UI_TYPE_HOME + 1;
    private static final int UI_TYPE_MUSIC = UI_TYPE_HOME + 2;
    private static final int UI_TYPE_PHONE = UI_TYPE_HOME + 3;

    private static final byte HOME_AVAILABILITY = 1;
    private static final byte MAPS_AVAILABILITY = 1;
    private static final byte PHONE_AVAILABILITY = 1;
    private static final byte MUSIC_AVAILABILITY = 1;

    private IActivityTaskManager mAtm;
    private InputManager mInputManager;
    private ClusterHomeManager mHomeManager;
    private CarOccupantZoneManager mOccupantZoneManager;
    private CarUserManager mUserManager;
    private CarInputManager mCarInputManager;
    private ClusterState mClusterState;
    private byte mUiAvailability[];
    private int mClusterDisplayId = Display.INVALID_DISPLAY;
    private int mUserLifeCycleEvent = USER_LIFECYCLE_EVENT_TYPE_STARTING;

    private ComponentName[] mClusterActivities;

    private int mMainUiType = UI_TYPE_CLUSTER_NONE;

    @Override
    public void onCreate() {
        super.onCreate();
        mClusterActivities = new ComponentName[] {
                new ComponentName(getApplicationContext(), ClusterHomeActivity.class),
                ComponentName.unflattenFromString(
                        getString(R.string.config_clusterMapActivity)),
                ComponentName.unflattenFromString(
                        getString(R.string.config_clusterMusicActivity)),
                ComponentName.unflattenFromString(
                        getString(R.string.config_clusterPhoneActivity)),
        };
        mAtm = ActivityTaskManager.getService();
        try {
            mAtm.registerTaskStackListener(mTaskStackListener);
        } catch (RemoteException e) {
            Slog.e(TAG, "remote exception from AM", e);
        }
        mInputManager = getApplicationContext().getSystemService(InputManager.class);

        Car.createCar(getApplicationContext(), /* handler= */ null,
                Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                (car, ready) -> {
                    if (!ready) return;
                    mHomeManager = (ClusterHomeManager) car.getCarManager(Car.CLUSTER_HOME_SERVICE);
                    mOccupantZoneManager = (CarOccupantZoneManager) car.getCarManager(
                            Car.CAR_OCCUPANT_ZONE_SERVICE);
                    mUserManager = (CarUserManager) car.getCarManager(Car.CAR_USER_SERVICE);
                    mCarInputManager = (CarInputManager) car.getCarManager(Car.CAR_INPUT_SERVICE);
                    initClusterHome();
                });
    }

    private void initClusterHome() {
        mHomeManager.registerClusterHomeCallback(getMainExecutor(),mClusterHomeCalback);
        mClusterState = mHomeManager.getClusterState();
        mUiAvailability = buildUiAvailability();
        mHomeManager.reportState(mClusterState.uiType, UI_TYPE_CLUSTER_NONE, mUiAvailability);
        mHomeManager.registerClusterHomeCallback(getMainExecutor(), mClusterHomeCalback);

        mClusterDisplayId = mOccupantZoneManager.getDisplayIdForDriver(
                DISPLAY_TYPE_INSTRUMENT_CLUSTER);

        mUserManager.addListener(getMainExecutor(), mUserLifecycleListener);

        int r = mCarInputManager.requestInputEventCapture(
                DISPLAY_TYPE_INSTRUMENT_CLUSTER,
                new int[]{CarInputManager.INPUT_TYPE_ALL_INPUTS},
                CarInputManager.CAPTURE_REQ_FLAGS_TAKE_ALL_EVENTS_FOR_DISPLAY,
                mInputCaptureCallback);
        if (r != CarInputManager.INPUT_CAPTURE_RESPONSE_SUCCEEDED) {
            Slog.e(TAG, "Failed to capture InputEvent on Cluster: r=" + r);
        }

        if (mClusterState.uiType != UI_TYPE_HOME) {
            startClusterActivity(mClusterState.uiType);
        }
    }

    @Override
    public void onTerminate() {
        mCarInputManager.releaseInputEventCapture(DISPLAY_TYPE_INSTRUMENT_CLUSTER);
        mUserManager.removeListener(mUserLifecycleListener);
        mHomeManager.unregisterClusterHomeCallback(mClusterHomeCalback);
        try {
            mAtm.unregisterTaskStackListener(mTaskStackListener);
        } catch (RemoteException e) {
            Slog.e(TAG, "remote exception from AM", e);
        }
        super.onTerminate();
    }

    private void startClusterActivity(int uiType) {
        if (mUserLifeCycleEvent != USER_LIFECYCLE_EVENT_TYPE_UNLOCKED) {
            Slog.i(TAG, "Ignore to start Activity(" + uiType + ") during user-switching");
            return;
        }
        mClusterState.uiType = uiType;
        ComponentName activity = mClusterActivities[uiType];
        Intent intent = new Intent(ACTION_MAIN).setComponent(activity);
        ActivityOptions options = ActivityOptions.makeBasic();
        // This sample assumes the Activities in this package are running as the system user,
        // and the other Activities are running as a current user.
        int userId = ActivityManager.getCurrentUser();
        if (getApplicationContext().getPackageName().equals(activity.getPackageName())) {
            userId = UserHandle.USER_SYSTEM;
        }
        mHomeManager.startFixedActivityModeAsUser(intent, options.toBundle(), userId);
    }

    private byte[] buildUiAvailability() {
        // TODO(b/183115088): populate uiAvailability based on the package availability
        return new byte[] {
                HOME_AVAILABILITY, MAPS_AVAILABILITY, PHONE_AVAILABILITY, MUSIC_AVAILABILITY
        };
    }

    private final ClusterHomeCallback mClusterHomeCalback = new ClusterHomeCallback() {
        @Override
        public void onClusterStateChanged(
                ClusterState state, @ClusterHomeManager.Config int changes) {
            if ((changes & ClusterHomeManager.CONFIG_UI_TYPE) != 0
                    && mClusterState.uiType != state.uiType) {
                startClusterActivity(state.uiType);
            }
            // TODO(b/173454330): handle CONFIG_DISPLAY_XXX
        }
        @Override
        public void onNavigationState(byte[] navigationState) {
            // TODO(b/173454430): handle onNavigationState
        }
    };

    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        // onTaskMovedToFront isn't called when Activity-change happens within the same task.
        @Override
        public void onTaskStackChanged() throws RemoteException {
            TaskInfo taskInfo = mAtm.getRootTaskInfoOnDisplay(
                    WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_UNDEFINED, mClusterDisplayId);
            int uiType = identifyTopTask(taskInfo);
            if (uiType == UI_TYPE_CLUSTER_NONE) {
                Slog.w(TAG, "Unexpected top Activity on Cluster: " + taskInfo.topActivity);
                return;
            }
            if (mMainUiType == uiType) {
                // Don't report the same UI type repeatedly.
                return;
            }
            mMainUiType = uiType;
            mHomeManager.reportState(uiType, UI_TYPE_CLUSTER_NONE, mUiAvailability);
        }
    };

    private int identifyTopTask(TaskInfo taskInfo) {
        for (int i = mClusterActivities.length - 1; i >=0; --i) {
            if (mClusterActivities[i].equals(taskInfo.topActivity)) {
                return i;
            }
        }
        return UI_TYPE_CLUSTER_NONE;
    }

    private final UserLifecycleListener mUserLifecycleListener = (event) -> {
        mUserLifeCycleEvent = event.getEventType();
        if (mUserLifeCycleEvent == USER_LIFECYCLE_EVENT_TYPE_STARTING) {
            startClusterActivity(UI_TYPE_HOME);
        }
    };

    private final CarInputCaptureCallback mInputCaptureCallback = new CarInputCaptureCallback() {
        @Override
        public void onKeyEvents(@CarOccupantZoneManager.DisplayTypeEnum int targetDisplayType,
                List<KeyEvent> keyEvents) {
            keyEvents.forEach((keyEvent) -> onKeyEvent(keyEvent));
        }
    };

    private void onKeyEvent(KeyEvent keyEvent) {
        if (keyEvent.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (keyEvent.getAction() != KeyEvent.ACTION_DOWN) return;
            int nextUiType = (mClusterState.uiType + 1) % mUiAvailability.length;
            startClusterActivity(nextUiType);
            return;
        }
        // Use Android InputManager to forward KeyEvent.
        mInputManager.injectInputEvent(keyEvent, INJECT_INPUT_EVENT_MODE_ASYNC);
    }
}
