package se.gritacademy.maryam.rasouli.malmo.shakeApp;

import android.annotation.SuppressLint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Huvudaktivitet för ShakeApp.
 *
 * Denna aktivitet:
 *   Lyssnar på accelerometer och proximitiesensor.
 *   Visar X, Y, Z-värden från accelerometern.
 *   Ändrar UIkomponenter baserat på sensorvärden (färger, rotation, bakgrund).
 *   Registrerar shakehändelser med toast och logg.
 *   Färger påverkas av X, Y, Z samt närhetsvärde (allt blir vitt när sensorn täcks).
 */
public class MainActivity extends AppCompatActivity implements SensorEventListener {

    //Sensorer i appen
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor proximitySensor;

    //UIkomponenter i appen
    private TextView tvAx, tvAy, tvAz, tvProximity, tvThreshold;
    private ImageView ivPhone;
    private Switch swEnableSensors;
    private SeekBar sbThreshold;
    private Button btnCalibrate;
    private ProgressBar pbProximity;
    private View cardView;

    // Används för filtrering av accelerometer
    private static final float ALPHA = 0.8f;                 // lågpassfilter konstant
    private final float[] gravity = new float[]{0,0,0};     // lågpassad gravitation
    private final float[] linearAccel = new float[]{0,0,0}; // högpassad rörelse (utan gravitation)
    private float shakeThresholdG = 2.7f;                   // tröskel för shake i g

    private long lastShakeTimestamp = 0L;                  //debounce för shake
    private static final long SHAKE_COOLDOWN_MS = 800;     // minsta tid mellan shakes i ms

    private float calibrationOffsetDeg = 0f;              //offset för rotation
    private float lastAx = 0f;                             // senaste ax-värdet
    private boolean sensorsEnabled = true;                // flagga för sensorer på/av
    private float lastProximityValue = 0f;                // senaste proximitiesensornvärdet

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //Här hämtas sensorer
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);

        if (proximitySensor == null) {
            Toast.makeText(this, "Proximity/närhetsssensor saknas!", Toast.LENGTH_LONG).show();
        }

        // Här  knyts UI komponenter
        tvAx = findViewById(R.id.tvAx);
        tvAy = findViewById(R.id.tvAy);
        tvAz = findViewById(R.id.tvAz);
        tvProximity = findViewById(R.id.tvProximity);
        tvThreshold = findViewById(R.id.tvThreshold);
        ivPhone = findViewById(R.id.ivPhone);
        cardView = findViewById(R.id.cardView);
        swEnableSensors = findViewById(R.id.swEnableSensors);
        sbThreshold = findViewById(R.id.sbThreshold);
        btnCalibrate = findViewById(R.id.btnCalibrate);
        pbProximity = findViewById(R.id.pbProximity);

        //Switch för sensorer på/av
        swEnableSensors.setChecked(true);
        swEnableSensors.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sensorsEnabled = isChecked;
            if (isChecked) registerSensors();
            else unregisterSensors();
        });

        //SeekBar för shaketröskel
        sbThreshold.setMax(30);
        sbThreshold.setProgress((int)((shakeThresholdG - 2.0f)*10));
        updateThresholdLabel();
        sbThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                shakeThresholdG = 2.0f + (progress/10f);
                updateThresholdLabel();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        //Kalibreringsknapp
        btnCalibrate.setOnClickListener(v -> {
            calibrationOffsetDeg = -lastAx * 6f;
            ivPhone.setRotation(0f);
            Toast.makeText(this, "Kalibrerat: nuvarande lutning satt som 0°", Toast.LENGTH_SHORT).show();
        });

        //Proximity ProgressBar
        pbProximity.setMax(5);
    }

    /**
     * Uppdaterar labeln för shaketröskel
     */
    private void updateThresholdLabel() {
        tvThreshold.setText(String.format("Shaketröskel: %.1f g", shakeThresholdG));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorsEnabled) registerSensors();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterSensors();
    }

    /**
     * Registrerar sensorlyssnare
     */
    private void registerSensors() {
        if (accelerometer != null)
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        if (proximitySensor != null)
            sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    /**
     * Avregistrerar sensorlyssnare
     */
    private void unregisterSensors() {
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    /**
     * Hanterar inkommande sensorvärden
     * @param event SensorEvent från accelerometer eller proximity
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!sensorsEnabled) return;
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) handleAccelerometer(event.values);
        else if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) handleProximity(event.values[0]);
    }

    /**
     * Hanterar accelerometerdata, uppdaterar UI baserat på ax, ay, az
     * @param values float[3] med X, Y, Z
     */
    private void handleAccelerometer(float[] values) {
        float ax = values[0];
        float ay = values[1];
        float az = values[2];

        lastAx = ax;

        // Visa värden
        tvAx.setText(String.format("ax: %.2f m/s²", ax));
        tvAy.setText(String.format("ay: %.2f m/s²", ay));
        tvAz.setText(String.format("az: %.2f m/s²", az));

        // Lågpassfilter för gravitation
        gravity[0] = ALPHA*gravity[0] + (1-ALPHA)*ax;
        gravity[1] = ALPHA*gravity[1] + (1-ALPHA)*ay;
        gravity[2] = ALPHA*gravity[2] + (1-ALPHA)*az;

        // Högpassfilter för linjär acceleration
        linearAccel[0] = ax - gravity[0];
        linearAccel[1] = ay - gravity[1];
        linearAccel[2] = az - gravity[2];

        // Beräknar gForce
        float gX = linearAccel[0]/SensorManager.GRAVITY_EARTH;
        float gY = linearAccel[1]/SensorManager.GRAVITY_EARTH;
        float gZ = linearAccel[2]/SensorManager.GRAVITY_EARTH;
        float gForce = (float)Math.sqrt(gX*gX + gY*gY + gZ*gZ);

        // Registrerar shake om tröskel överskrids
        long now = System.currentTimeMillis();
        if (gForce > shakeThresholdG && (now-lastShakeTimestamp)>SHAKE_COOLDOWN_MS) {
            lastShakeTimestamp = now;
            String msg = String.format("SHAKE! g=%.2f (tröskel %.1f)", gForce, shakeThresholdG);
            Toast.makeText(this,msg,Toast.LENGTH_SHORT).show();
            Log.d("ShakeApp", msg);
            ivPhone.animate().rotationBy(20f).setDuration(120).withEndAction(() ->
                    ivPhone.animate().rotationBy(-20f).setDuration(120)).start();
        }

        // Rotation av bild baserat på ax
        float rotationDeg = -ax*6f;
        ivPhone.setRotation(rotationDeg - calibrationOffsetDeg);

        // Om proximitiesensorn är täckt ska allt behållas vitt
        if (proximitySensor != null && lastProximityValue < proximitySensor.getMaximumRange()) {
            setAllComponentsWhite();
            return;
        }

        // Röd färg baserat på ax
        int redIntensity = (int)Math.min(255, Math.abs(ax)*50);
        int redColor = 0xff000000 | (redIntensity << 16);
        tvAx.setTextColor(redColor);
        tvAy.setTextColor(redColor);
        tvAz.setTextColor(redColor);
        btnCalibrate.setBackgroundColor(redColor);
        sbThreshold.getProgressDrawable().setColorFilter(redColor, android.graphics.PorterDuff.Mode.SRC_IN);
        sbThreshold.getThumb().setColorFilter(redColor, android.graphics.PorterDuff.Mode.SRC_IN);

        //Grön färg baserat på az
        int greenIntensity = (int)Math.min(255, Math.abs(az)*50);
        int greenColor = 0xff000000 | (greenIntensity << 8);
        tvProximity.setTextColor(greenColor);
        swEnableSensors.setTextColor(greenColor);
        pbProximity.getProgressDrawable().setColorFilter(greenColor, android.graphics.PorterDuff.Mode.SRC_IN);

        //Blå färg baserat på ay
        int blueIntensity = (int)Math.min(255, Math.abs(ay)*50);
        int blueColor = 0xff000000 | blueIntensity;
        cardView.setBackgroundColor(blueColor);
    }

    /**
     * Hanterar proximitiesensordata, uppdaterar UI och kortets bakgrund
     * @param value avstånd i cm
     */
    private void handleProximity(float value) {
        lastProximityValue = value;

        tvProximity.setText(String.format("Proximity: %.1f cm", value));
        pbProximity.setProgress((int)Math.min(value,pbProximity.getMax()));

        // Om fingret täcker sensorn görs komponenter vita
        if (value < proximitySensor.getMaximumRange()) {
            setAllComponentsWhite();
            cardView.setBackgroundColor(0xffff0000); // kort rött
            ivPhone.setColorFilter(0xffffffff);
            return;
        }

        //Annars  blir färger baserat på ax/az
        int greenIntensity = (int)Math.min(255, Math.abs(lastAx)*50);
        int color = 0xff000000 | (greenIntensity << 8);
        tvProximity.setTextColor(color);
        swEnableSensors.setTextColor(color);
        pbProximity.getProgressDrawable().setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);

        cardView.setBackgroundResource(R.drawable.rounded_card);
        ivPhone.clearColorFilter();
    }

    /**
     * Sätter alla komponenter till vit färg
     */
    private void setAllComponentsWhite() {
        int white = 0xffffffff;
        tvAx.setTextColor(white);
        tvAy.setTextColor(white);
        tvAz.setTextColor(white);
        tvProximity.setTextColor(white);
        tvThreshold.setTextColor(white);
        swEnableSensors.setTextColor(white);
        btnCalibrate.setBackgroundColor(white);
        sbThreshold.getProgressDrawable().setColorFilter(white, android.graphics.PorterDuff.Mode.SRC_IN);
        sbThreshold.getThumb().setColorFilter(white, android.graphics.PorterDuff.Mode.SRC_IN);
        pbProximity.getProgressDrawable().setColorFilter(white, android.graphics.PorterDuff.Mode.SRC_IN);
    }
}


