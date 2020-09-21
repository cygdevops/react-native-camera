/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.google.android.cameraview;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.os.Handler;
import android.util.Range;
import android.view.Surface;

import java.io.IOException;
import java.util.Arrays;


@TargetApi(23)
class Camera2Api23 extends Camera2 {

    Camera2Api23(Callback callback, PreviewImpl preview, Context context, Handler bgHandler) {
        super(callback, preview, context, bgHandler);
    }

    @Override
    protected void collectPictureSizes(SizeMap sizes, StreamConfigurationMap map) {
        // Try to get hi-res output sizes
        android.util.Size[] outputSizes = map.getHighResolutionOutputSizes(ImageFormat.JPEG);
        if (outputSizes != null) {
            for (android.util.Size size : map.getHighResolutionOutputSizes(ImageFormat.JPEG)) {
                sizes.add(new Size(size.getWidth(), size.getHeight()));
            }
        }
        if (sizes.isEmpty()) {
            super.collectPictureSizes(sizes, map);
        }
    }


    @Override
    boolean record(String path, int maxDuration, int maxFileSize, boolean recordAudio, CamcorderProfile profile, int orientation, int fps) {
        if (!mIsRecording) {
            setUpMediaRecorder(path, maxDuration, maxFileSize, recordAudio, profile);
            try {
                mMediaRecorder.prepare();

                if (mCaptureSession != null) {
                    mCaptureSession.close();
                    mCaptureSession = null;
                }

                Size size = chooseOptimalSize();
                mPreview.setBufferSize(size.getWidth(), size.getHeight());
                Surface surface = getPreviewSurface();
                Surface mMediaRecorderSurface = mMediaRecorder.getSurface();

                mPreviewRequestBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                mPreviewRequestBuilder.addTarget(surface);
                mPreviewRequestBuilder.addTarget(mMediaRecorderSurface);
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,getHighestFpsRange());
                mCamera.createConstrainedHighSpeedCaptureSession(Arrays.asList(surface, mMediaRecorderSurface),
                        mSessionCallback, null);
                mMediaRecorder.start();
                mIsRecording = true;

                // @TODO: implement videoOrientation and deviceOrientation calculation
                // same TODO as onVideoRecorded
                mCallback.onRecordingStart(mVideoPath, 0, 0);

                return true;
            } catch (CameraAccessException | IOException e) {
                e.printStackTrace();
                return false;
            }
        }
        return false;
    }

    private Range<Integer> getHighestFpsRange() {

        Range<Integer>[] fpsRanges = mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);;
        Range fpsRange = Range.create(fpsRanges[0].getLower(), fpsRanges[0].getUpper());
        for (int i=0;i<fpsRanges.length;i++) {
            if ((int)fpsRanges[i].getUpper() > (int)fpsRange.getUpper()) {
                fpsRange = fpsRange.extend(fpsRange.getLower(), fpsRanges[i].getUpper());
            }
        }

        for (int i=0;i<fpsRanges.length;i++) {
            if (fpsRanges[i].getUpper() == fpsRange.getUpper() && (int)fpsRange.getLower() < (int)fpsRange.getLower()) {
                fpsRange = fpsRange.extend(fpsRanges[i].getLower(), fpsRange.getUpper());
            }
        }
        return fpsRange;
    }
}
