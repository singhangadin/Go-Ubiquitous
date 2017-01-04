package com.example.wear;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.view.WindowManager;

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
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {

    private static final String PATH_WEATHER = "/weather";
    private static final String KEY_HIGH = "high_temp";
    private static final String KEY_LOW = "low_temp";
    private static final String KEY_ID = "weather_id";
    private int mWeatherId;
    private GoogleApiClient mGoogleApiClient;

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
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.ConnectionCallbacks,GoogleApiClient.OnConnectionFailedListener,DataApi.DataListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                mCalendar.setTimeInMillis(System.currentTimeMillis());
            }
        };
        private Paint hourPaint, datePaint, linePaint;
        private Paint minPaint, highTemp, lowTemp;
        private float centerX, centerY;
        private String hTemp, lTemp;

        private String month3[]={"JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC"};
        private String days3[]={"MON","TUE","WED","THU","FRI","SAT","SUN"};

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @SuppressWarnings("deprecation")
        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            mCalendar = Calendar.getInstance();
            Typeface ROBOTO_REGULAR = Typeface.createFromAsset(getBaseContext().getAssets(), "fonts/Roboto-Regular.ttf");
            Typeface ROBOTO_LIGHT = Typeface.createFromAsset(getAssets(), "fonts/Roboto-Light.ttf");
            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            WindowManager wm = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
            Display display = wm.getDefaultDisplay();
            Point size = new Point();
            display.getSize(size);
            centerX = size.x/2;
            centerY = size.y/2;

            linePaint = new Paint();
            linePaint.setColor(Color.parseColor("#CCCCCC"));
            linePaint.setAntiAlias(true);

            hourPaint = createTextPaint(Color.parseColor("#FFFFFF"), ROBOTO_REGULAR);
            minPaint = createTextPaint(Color.parseColor("#FFFFFF"), ROBOTO_LIGHT);
            datePaint = createTextPaint(Color.parseColor("#DDDDDD"), ROBOTO_LIGHT);
            highTemp = createTextPaint(Color.parseColor("#FFFFFF"), ROBOTO_REGULAR);
            lowTemp = createTextPaint(Color.parseColor("#DDDDDD"), ROBOTO_LIGHT);

            hourPaint.setTextSize(centerX/2.6f);
            minPaint.setTextSize(centerX/2.6f);
            highTemp.setTextSize(centerX/4f);
            lowTemp.setTextSize(centerX/4f);
            datePaint.setTextSize(centerX/6.75f);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                mCalendar.setTimeInMillis(System.currentTimeMillis());
            } else {
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
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
                    hourPaint.setAntiAlias(!inAmbientMode);
                    minPaint.setAntiAlias(!inAmbientMode);
                    datePaint.setAntiAlias(!inAmbientMode);
                    linePaint.setAntiAlias(!inAmbientMode);
                    highTemp.setAntiAlias(!inAmbientMode);
                    lowTemp.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @SuppressWarnings("deprecation")
        @SuppressLint("DefaultLocale")
        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawColor(Color.parseColor("#03A9F4"));
            }

            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String hour = String.format("%02d:", mCalendar.get(Calendar.HOUR_OF_DAY));
            String min = String.format("%02d",mCalendar.get(Calendar.MINUTE));
            canvas.drawText(
                    hour,
                    centerX - (hourPaint.measureText(hour + min)/2),
                    centerY - (0.3f * centerY),
                    hourPaint);

            canvas.drawText(
                    min,
                    centerX + (minPaint.measureText(":")/2),
                    centerY - (0.3f * centerY),
                    minPaint);

            String date = days3[mCalendar.get(Calendar.DAY_OF_WEEK)]+", "+
                    month3[mCalendar.get(Calendar.MONTH)]+" "+
                    String.format("%02d", mCalendar.get(Calendar.DAY_OF_MONTH))+" "+
                    mCalendar.get(Calendar.YEAR);

            canvas.drawText(
                    date,
                    centerX - (datePaint.measureText(date)/2),
                    centerY - (0.065f * centerY),
                    datePaint);

            canvas.drawLine(
                    centerX-(0.175f*centerX),
                    centerY +(0.125f*centerY),
                    centerX+(0.175f*centerX),
                    centerY +(0.125f*centerY),
                    linePaint);
            if(hTemp!=null&&lTemp!=null) {
                canvas.drawText(
                        String.valueOf(hTemp),
                        centerX - (highTemp.measureText(String.valueOf(hTemp)) / 2),
                        centerY + (0.5f * centerY),
                        highTemp);

                canvas.drawText(
                    String.valueOf(lTemp),
                    centerX + highTemp.measureText(String.valueOf(hTemp)),
                    centerY + (0.5f * centerY),
                    lowTemp);

                if(!isInAmbientMode())
                {   float highTextSize = hourPaint.measureText(hour);
                    Drawable b = getResources().getDrawable(Utility.getIconResourceForWeatherCondition(mWeatherId));
                    Bitmap icon = ((BitmapDrawable) b).getBitmap();
                    float scaledWidth = (hourPaint.getTextSize() / icon.getHeight()) * icon.getWidth();
                    Bitmap weatherIcon = Bitmap.createScaledBitmap(icon, (int) scaledWidth, (int) hourPaint.getTextSize(), true);
                    float iconXOffset = bounds.centerX() - ((highTextSize / 2) + weatherIcon.getWidth());
                    canvas.drawBitmap(weatherIcon, iconXOffset, centerY + (weatherIcon.getHeight()/2), null);
                }
            }
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
            Log.d("TAG", "Connected");
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d("TAG", "Connection Suspended");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d("TAG", "Connection Failed: " + connectionResult.getErrorMessage());
        }

        // Wearable Data Change Listener
        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = dataEvent.getDataItem();
                    if (dataItem.getUri().getPath().compareTo(PATH_WEATHER) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                        hTemp = dataMap.getString(KEY_HIGH);
                        lTemp = dataMap.getString(KEY_LOW);
                        mWeatherId = dataMap.getInt(KEY_ID);
                        Log.d("TAG", "\nHigh: " + hTemp + "\nLow: " + lTemp + "\nID: " + mWeatherId);
                        invalidate();
                    }
                }
            }
        }
    }
}