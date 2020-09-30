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
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


@TargetApi(23)
class Camera2Api23 extends Camera2 {
    private  android.util.Size selectedSize = new android.util.Size(0,0);
    private Range<Integer> selectedFpsRanges = new Range<Integer>(0,0);
    private static final int BIT_RATE_1080P = 16000000;
    private static final int BIT_RATE_MIN = 64000;
    private static final int BIT_RATE_MAX = 40000000;

    Camera2Api23(Callback callback, PreviewImpl preview, Context context, Handler bgHandler) {
        super(callback, preview, context, bgHandler);
    }

    @Override
    public ArrayList<int[]> getSupportedPreviewFpsRange() {
        try{
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Range<Integer>[] fpsRange = map.getHighSpeedVideoFpsRanges(); // this range intends available fps range of device's camera.
            ArrayList<int[]> validValues = new ArrayList<int[]>();
            for(int i=0;i<fpsRange.length;i++)
                validValues.add(new int[]{fpsRange[i].getLower(),fpsRange[i].getUpper()});
            return validValues;
        }
        catch (CameraAccessException e) {
            throw new RuntimeException("Failed to get a list of camera ids", e);
        }
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
            android.util.Size[] sizes = getHighSpeedVideoSizes();
            for(int i=0;i<sizes.length;i++)
            {
                Range<Integer>[] fpsRanges = getHighSpeedVideoFpsRangesFor(sizes[i]);
                for(int j=0;j<fpsRanges.length;j++) {
                    if ((fpsRanges[j].getUpper() >= selectedFpsRanges.getUpper()  || fpsRanges[j].getUpper()>= fps)&& sizes[i].getWidth() >= selectedSize.getWidth()) {
                        selectedFpsRanges = fpsRanges[j];
                        selectedSize = sizes[i];
                    }
                }
            }
            setUpMediaRecorder(path, maxDuration, maxFileSize, recordAudio, profile);
            try {
                mMediaRecorder.prepare();

                if (mCaptureSession != null) {
                    mCaptureSession.close();
                    mCaptureSession = null;
                }

                android.util.Size size = selectedSize;
                mPreview.setBufferSize(size.getWidth(), size.getHeight());
                Surface surface = getPreviewSurface();
                Surface mMediaRecorderSurface = mMediaRecorder.getSurface();
                mPreviewRequestBuilder = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                mPreviewRequestBuilder.addTarget(surface);
                mPreviewRequestBuilder.addTarget(mMediaRecorderSurface);

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

    private void setUpMediaRecorder(String path, int maxDuration, int maxFileSize, boolean recordAudio, CamcorderProfile profile) {
        mMediaRecorder = new MediaRecorder();

        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        if (recordAudio) {
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        }

        mMediaRecorder.setOutputFile(path);
        mVideoPath = path;

        CamcorderProfile camProfile = profile;
        profile.quality = CamcorderProfile.QUALITY_HIGH_SPEED_HIGH;
        if (!CamcorderProfile.hasProfile(Integer.parseInt(mCameraId), profile.quality)) {
            camProfile = CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH);
        }
        setCamcorderProfile(camProfile, recordAudio);

        mMediaRecorder.setOrientationHint(getOutputRotation());

        if (maxDuration != -1) {
            mMediaRecorder.setMaxDuration(maxDuration);
        }
        if (maxFileSize != -1) {
            mMediaRecorder.setMaxFileSize(maxFileSize);
        }

        mMediaRecorder.setOnInfoListener(this);
        mMediaRecorder.setOnErrorListener(this);
    }

    private void setCamcorderProfile(CamcorderProfile profile, boolean recordAudio) {
        mMediaRecorder.setOutputFormat(profile.fileFormat);
        mMediaRecorder.setVideoFrameRate(selectedFpsRanges.getUpper());
        mMediaRecorder.setVideoSize(selectedSize.getWidth(), selectedSize.getHeight());
        mMediaRecorder.setVideoEncodingBitRate(getVideoBitRate());
        mMediaRecorder.setCaptureRate(selectedFpsRanges.getLower());
        //mMediaRecorder.setVideoEncodingBitRate(20000000);
        mMediaRecorder.setVideoEncoder(profile.videoCodec);
        if (recordAudio) {
            mMediaRecorder.setAudioEncodingBitRate(profile.audioBitRate);
            mMediaRecorder.setAudioChannels(profile.audioChannels);
            mMediaRecorder.setAudioSamplingRate(profile.audioSampleRate);
            mMediaRecorder.setAudioEncoder(profile.audioCodec);
        }
    }

    protected final CameraCaptureSession.StateCallback mSessionCallback
            = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            if (mCamera == null) {
                return;
            }
            mCaptureSession = session;

           // mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            //mPreviewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            try {
                if (mIsRecording) {
                    Range<Integer> fpsRange = Range.create(selectedFpsRanges.getUpper(), selectedFpsRanges.getUpper());
                    mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);

                    List<CaptureRequest>  mPreviewBuilderBurst = ((CameraConstrainedHighSpeedCaptureSession)mCaptureSession).createHighSpeedRequestList(mPreviewRequestBuilder.build());
                    ((CameraConstrainedHighSpeedCaptureSession)mCaptureSession).setRepeatingBurst(mPreviewBuilderBurst, mCaptureCallback, null);

                } else {
                    ((CameraConstrainedHighSpeedCaptureSession)mCaptureSession).setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback, null);

                }
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }

        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.e(TAG, "Failed to configure highspeed capture session.");
        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            if (mCaptureSession != null && mCaptureSession.equals(session)) {
                mCaptureSession = null;
            }
        }

    };

    private int getVideoBitRate() {
        int rate = BIT_RATE_1080P;
        float scaleFactor = selectedSize.getHeight() * selectedSize.getWidth() / (float)(1920 * 1080);
        rate = (int)(rate * scaleFactor);
        // Clamp to the MIN, MAX range.
        return Math.max(BIT_RATE_MIN, Math.min(BIT_RATE_MAX, rate));
    }

    private android.util.Size[]  getHighSpeedVideoSizes() {
        try{
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            android.util.Size[] videoSizes = map.getHighSpeedVideoSizes(); // this range intends available fps range of device's camera.
            return videoSizes;
        }
        catch (CameraAccessException e) {
            throw new RuntimeException("Failed to get a list of camera ids", e);
        }
    }

    private Range<Integer>[] getHighSpeedVideoFpsRangesFor(android.util.Size s) {
        try{
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            return map.getHighSpeedVideoFpsRangesFor(s);
        }
        catch (CameraAccessException e) {
            throw new RuntimeException("Failed to get a list of camera ids", e);
        }
    }
}
