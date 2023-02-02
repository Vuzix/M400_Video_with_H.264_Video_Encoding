/*'*****************************************************************************
Copyright (c) 2018, Vuzix Corporation
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

*  Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.

*  Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

*  Neither the name of Vuzix Corporation nor the names of
   its contributors may be used to endorse or promote products derived
   from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*'*****************************************************************************/
package com.vuzix.sample.video_encoder;

import android.content.Context;
import android.hardware.SensorManager;
import android.view.OrientationEventListener;
import android.view.WindowManager;

/**
 * A class to monitor the rotation of the m300. Provides a callback whenever it changes
 */
public class RotationListener {

    /**
     * Interface for receiving callbacks from this listener class
     */
    public interface rotationCallbackFn {
        /**
         * Method that is called when the rotation of the M300 changes
         * @param newRotation int Either Surface.ROTATION_0 or Surface.ROTATION_180
         */
        void onRotationChanged(int newRotation);
    }

    private int lastRotation;
    private WindowManager mWindowManager;
    private OrientationEventListener mOrientationEventListener;

    private rotationCallbackFn mCallback;


    /**
     * Register a listener
     * @param context Context of your activity
     * @param callback rotationCallbackFn to be called when the rotation changes
     */
    public void listen(Context context, rotationCallbackFn callback) {
        // registering the listening only once.
        stop();
        mCallback = callback;
        mWindowManager = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);

        mOrientationEventListener = new OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override
            public void onOrientationChanged(int orientation) {
                onOrientationChangedHandler();
            }
        };
        mOrientationEventListener.enable();
        lastRotation = mWindowManager.getDefaultDisplay().getRotation();
    }

    /**
     * Handles the rotation change. Called for every degree.  Only calls the callback if it is significant
     */
    private void onOrientationChangedHandler() {
        if( mWindowManager != null && mCallback != null) {
            int newRotation = mWindowManager.getDefaultDisplay().getRotation();
            if (newRotation != lastRotation) {
                mCallback.onRotationChanged(newRotation);
                lastRotation = newRotation;
            }
        }
    }

    /**
     * Stop receiving rotation callbacks.  Call from your onPause()
     */
    public void stop() {
        if(mOrientationEventListener != null) {
            mOrientationEventListener.disable();
        }
        mOrientationEventListener = null;
        mWindowManager = null;
        mCallback = null;
    }    
}
