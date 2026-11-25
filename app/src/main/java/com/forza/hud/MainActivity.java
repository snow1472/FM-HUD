package com.forza.hud;

import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private float rpm = 0, maxRpm = 7000, speedKmh = 0;
    private int dynamicMaxSpeed = 280;
    private int gear = 0;
    private float posX = 0, posZ = 0, yaw = 0;
    private final List<PointF> trackPoints = new ArrayList<>();
    private String localIp = "获取中...";
    private boolean showFullMap = false;
    private HudView hudView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        localIp = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
        hudView = new HudView(this);
        setContentView(hudView);
        startUdpListener();
    }

    private void startUdpListener() {
        new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket(9996)) {
                byte[] buf = new byte;
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                while (true) {
                    socket.receive(packet);
                    if (packet.getLength() >= 311) {
                        ByteBuffer bb = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
                        bb.order(ByteOrder.LITTLE_ENDIAN);
                        if (bb.getInt(0) == 1) {
                            float incomingMaxRpm = bb.getFloat(8);
                            if (incomingMaxRpm > 1000f) maxRpm = incomingMaxRpm;

                            int pi = bb.getInt(216);
                            if (pi > 0) {
                                if (pi <= 500) dynamicMaxSpeed = 240;
                                else if (pi <= 700) dynamicMaxSpeed = 300;
                                else if (pi <= 800) dynamicMaxSpeed = 340;
                                else if (pi <= 900) dynamicMaxSpeed = 360;
                                else dynamicMaxSpeed = 420;
                            }

                            rpm = bb.getFloat(16);
                            yaw = bb.getFloat(56);
                            posX = bb.getFloat(232);
                            posZ = bb.getFloat(240);
                            speedKmh = Math.max(0f, bb.getFloat(244) * 3.6f);
                            gear = bb.get(307) & 0xFF;

                            if (speedKmh > dynamicMaxSpeed - 10) {
                                dynamicMaxSpeed = ((int)(speedKmh / 40) + 1) * 40;
                            }

                            synchronized (trackPoints) {
                                if (trackPoints.isEmpty()) {
                                    trackPoints.add(new PointF(posX, posZ));
                                } else {
                                    PointF last = trackPoints.get(trackPoints.size() - 1);
                                    double dist = Math.hypot(posX - last.x, posZ - last.y);
                                    if (dist > 400.0) {
                                        trackPoints.clear();
                                        trackPoints.add(new PointF(posX, posZ));
                                    } else if (dist > 5.0 && trackPoints.size() < 4500) {
                                        trackPoints.add(new PointF(posX, posZ));
                                    }
                                }
                            }
                            hudView.postInvalidate();
                        }
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    class HudView extends View {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float touchDownX = 0;

        public HudView(Context c) { super(c); }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    touchDownX = event.getX();
                    if (showFullMap && event.getX() > getWidth() * 0.80f && event.getY() < 120f) {
                        synchronized (trackPoints) { trackPoints.clear(); }
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = event.getX() - touchDownX;
                    if (dx > 110f && !showFullMap) {
                        showFullMap = true;
                        invalidate();
                    } else if (dx < -110f && showFullMap) {
                        showFullMap = false;
                        invalidate();
                    }
                    return true;
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawColor(Color.BLACK);

            float gaugeMaxRpm = (float)(Math.ceil(maxRpm / 1000.0) * 1000.0);
            float rpmRatio = gaugeMaxRpm > 0 ? Math.min(1f, rpm / gaugeMaxRpm) : 0f;
            float redlineStartRatio = (maxRpm * 0.88f) / gaugeMaxRpm;
            boolean inRedlineZone = rpm >= maxRpm * 0.88f;
            boolean atRevLimiter = rpm >= maxRpm * 0.95f;
            boolean pulse = atRevLimiter && ((System.currentTimeMillis() / 65) % 2 == 0);

            String gearStr = (gear == 0) ? "R" : (gear == 11 ? "N" : String.valueOf(gear));

            if (!showFullMap) {
                drawSplitScreen(c, w, h, gaugeMaxRpm, rpmRatio, redlineStartRatio, inRedlineZone, atRevLimiter, pulse, gearStr);
            } else {
                drawFullMapScreen(c, w, h, inRedlineZone, pulse, gearStr);
            }
        }

        private void drawSplitScreen(Canvas c, int w, int h, float gaugeMaxRpm, float rpmRatio, float redlineStartRatio, boolean inRedlineZone, boolean atRevLimiter, boolean pulse, String gearStr) {
            if (atRevLimiter) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(pulse ? 14f : 6f);
                p.setColor(pulse ? Color.rgb(255, 25, 25) : Color.rgb(110, 0, 0));
                c.drawRect(6, 6, w - 6, h - 6, p);
            }

            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(22f);
            p.setColor(Color.rgb(95, 95, 95));
            c.drawText("IP: " + localIp + " : 9996   |   右滑展开全屏地图 →", w * 0.5f, 32f, p);

            float radius = Math.min(w * 0.165f, h * 0.235f);
            float leftCx = w * 0.25f, rightCx = w * 0.75f, topCy = h * 0.30f;

            drawRpmGauge(c, leftCx, topCy, radius, gaugeMaxRpm, rpmRatio, redlineStartRatio, inRedlineZone, atRevLimiter, pulse);
            drawSpeedGauge(c, rightCx, topCy, radius, dynamicMaxSpeed, Math.min(1f, speedKmh / dynamicMaxSpeed), atRevLimiter, pulse);

            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            p.setTextSize(radius * 1.15f);
            p.setColor(atRevLimiter ? (pulse ? Color.rgb(255, 35, 35) : Color.WHITE) : (inRedlineZone ? Color.rgb(255, 90, 90) : Color.WHITE));
            c.drawText(gearStr, w * 0.5f, topCy + radius * 0.38f, p);

            p.setTextSize(22f);
            p.setColor(Color.rgb(190, 40, 40));
            c.drawText("GEAR", w * 0.5f, topCy - radius * 0.65f, p);

            float splitY = h * 0.56f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(Color.rgb(60, 15, 15));
            c.drawLine(w * 0.08f, splitY, w * 0.92f, splitY, p);

            RectF mapArea = new RectF(w * 0.06f, splitY + 18f, w * 0.94f, h - 20f);
            drawWhiteTrackMap(c, mapArea, 8f, 16f, false);
        }

        private void drawFullMapScreen(Canvas c, int w, int h, boolean inRedlineZone, boolean pulse, String gearStr) {
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.LEFT);
            p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            p.setTextSize(36f);
            p.setColor(inRedlineZone ? Color.RED : Color.WHITE);
            c.drawText("← 返回仪表   |   挡位: " + gearStr + "   |   " + (int)speedKmh + " KM/H   |   " + (int)rpm + " RPM", 40f, 54f, p);

            p.setTextAlign(Paint.Align.RIGHT);
            p.setTextSize(30f);
            p.setColor(Color.rgb(60, 170, 255));
            c.drawText("[ 重置地图 ]", w - 40f, 52f, p);

            RectF fullMapArea = new RectF(w * 0.05f, 80f, w * 0.95f, h - 30f);
            drawWhiteTrackMap(c, fullMapArea, 12f, 22f, true);
        }

        private void drawWhiteTrackMap(Canvas c, RectF area, float lineWidth, float arrowSize, boolean isFull) {
            synchronized (trackPoints) {
                if (trackPoints.size() < 3) {
                    p.setStyle(Paint.Style.FILL);
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setTextSize(isFull ? 34f : 26f);
                    p.setColor(Color.rgb(110, 110, 110));
                    c.drawText("行驶中自动生成白色赛道全貌（向右滑动屏幕可展开全屏地图）", area.centerX(), area.centerY(), p);
                    return;
                }

                float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
