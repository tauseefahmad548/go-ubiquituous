/*
 * Copyright (C) 2014 The Android Open Source Project
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

package pk.smallapps.weatherwatchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }


    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        public static final String KEY_PATH = "/currentWeather";
        public static final String KEY_WEATHER_TYPE = "weather_type";
        public static final String KEY_MIN_TEMP = "min_temp";
        public static final String KEY_MAX_TEMP = "max_temp";

        private String mWeatherMaxTemp = "--";
        private String mWeatherMinTemp = "--";
        private Paint mBackgroundPaint;
        private Paint mTimePaint;
        private Paint mDatepaint;
        private Paint mWeatherIconPaint;
        private Paint mMinPaint;
        private Paint mMaxPaint;
        private Rect mTimeTextBounds;
        private Bitmap mWeatherIconBitmap;
        private float mVerticalMargin;
        private Calendar mCalendar;
        private Date mDate;
        private SimpleDateFormat mSimpleDateFormat;
        Time mTime;
        private float mXOffset;
        private float mYOffset;
        private boolean mAmbient;
        private boolean mLowBitAmbient;
        private boolean isDataAvailable = false;
        private boolean mRegisteredTimeZoneReceiver = false;
        private GoogleApiClient mGoogleApiClient;
        final Handler mUpdateTimeHandler = new EngineHandler(this);

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();

            }
        };


        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            mGoogleApiClient = new GoogleApiClient.Builder(getBaseContext())
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(Engine.this)
                    .addOnConnectionFailedListener(Engine.this)
                    .build();

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mVerticalMargin = resources.getDimension(R.dimen.digital_vertical_margin);
            mCalendar = Calendar.getInstance();
            mSimpleDateFormat = new SimpleDateFormat("E, MMM dd yyyy");
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background_interactive));
            mWeatherIconBitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_launcher);
            mWeatherIconBitmap = Bitmap.createScaledBitmap(mWeatherIconBitmap,48,48,false);
            mWeatherIconPaint = new Paint();

            mTimeTextBounds = new Rect();
            mTimePaint = createTextPaint(resources.getColor(R.color.digital_text));
            mDatepaint = createTextPaint(resources.getColor(R.color.digital_text));
            mMinPaint = createTextPaint(resources.getColor(R.color.digital_text_secondary));
            mMaxPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mMaxPaint.setTextSize(resources.getDimensionPixelSize(R.dimen.digital_temp_text_size));
            mMinPaint.setTextSize(resources.getDimensionPixelSize(R.dimen.digital_temp_text_size));

            mTime = new Time();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }


        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mGoogleApiClient.connect();

                Log.d("tauseef", "Visible");

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                Log.d("tauseef", "Invisible");
                unregisterReceiver();
                Wearable.DataApi.removeListener(mGoogleApiClient, this);
                mGoogleApiClient.disconnect();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }


        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTimePaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                    mDatepaint.setAntiAlias(!inAmbientMode);
                    mMinPaint.setAntiAlias(!inAmbientMode);
                    mMaxPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw HH:MM in ambient mode or HH:MM:SS in interactive mode.
            mTime.setToNow();
            String timeText = mAmbient
                    ? String.format("%02d:%02d", mTime.hour, mTime.minute)
                    : String.format("%02d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);
            String dateText = mSimpleDateFormat.format(new Date());
            canvas.drawText(timeText, mXOffset, mYOffset, mTimePaint);
            mTimePaint.getTextBounds(timeText, 0, timeText.length(), mTimeTextBounds);
            int timeTextHeight = mTimeTextBounds.height();
            canvas.drawText(dateText, mXOffset, mYOffset - timeTextHeight - mVerticalMargin, mDatepaint);
            canvas.drawText(mWeatherMaxTemp, mXOffset, mYOffset + timeTextHeight + mVerticalMargin, mMaxPaint);
            //temporary workaround to give same marginLeft to minimum Temperature as marginRight of maximum temp for symmetry
            Rect minTempbounds = new Rect();
            mMinPaint.getTextBounds("00°",0,3,minTempbounds);
            canvas.drawText(mWeatherMinTemp,bounds.width()-minTempbounds.width()-mXOffset , mYOffset + timeTextHeight + mVerticalMargin, mMinPaint);

            if (!mAmbient) {
                canvas.drawBitmap(mWeatherIconBitmap, bounds.width() / 2 - mWeatherIconBitmap.getHeight() / 2, mYOffset + timeTextHeight, mWeatherIconPaint);
            }
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }


        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {

            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo(KEY_PATH) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        DecimalFormat decimalFormat = new DecimalFormat("##");
                        String degreeSymbol = "\u00b0";
                        mWeatherMaxTemp = decimalFormat.format(dataMap.getDouble(KEY_MAX_TEMP))+degreeSymbol;
                        mWeatherMinTemp = decimalFormat.format(dataMap.getDouble(KEY_MIN_TEMP))+degreeSymbol;
                        mWeatherIconBitmap = BitmapFactory.decodeResource(getResources(), Util.getIconResourceForWeatherCondition(dataMap.getInt(KEY_WEATHER_TYPE)));
                        mWeatherIconBitmap = Bitmap.createScaledBitmap(mWeatherIconBitmap,48,48,false);
                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                    // DataItem deleted
                }
            }

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }
    }
}
