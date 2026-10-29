package com.usbdacvolume.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.usbdacvolume.app.USB_PERMISSION";
    private static final String PREFS = "usb_dac_volume";
    private static final int VENDOR_APPLE = 0x05AC;
    private static final long AUTO_COOLDOWN_MS = 15_000L;
    /** Aynı cihaza kısa sürede tekrar otomatik uygulamayı engeller (döngü koruması). */
    private static final Map<String, Long> LAST_AUTO = new HashMap<>();

    private UsbManager usbManager;
    private SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView deviceInfo;
    private TextView status;
    private RadioButton modeCustom;
    private EditText dbInput;
    private CheckBox autoApply;
    private CheckBox quietMode;
    private Button applyBtn;

    private UsbDevice device;
    private UacParser.Info info;
    private boolean busy;
    private boolean pendingAuto;
    private boolean launchedByAttach;

    private final BroadcastReceiver permReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice d = usbDeviceExtra(intent);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (d == null || !granted) {
                pendingAuto = false;
                setStatus(getString(R.string.permission_denied));
                return;
            }
            device = d;
            loadDeviceInfo(d);
            if (pendingAuto) {
                pendingAuto = false;
                startApply(true);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        deviceInfo = findViewById(R.id.deviceInfo);
        status = findViewById(R.id.status);
        RadioGroup modeGroup = findViewById(R.id.modeGroup);
        modeCustom = findViewById(R.id.modeCustom);
        dbInput = findViewById(R.id.dbInput);
        autoApply = findViewById(R.id.autoApply);
        quietMode = findViewById(R.id.quietMode);
        applyBtn = findViewById(R.id.applyBtn);

        modeCustom.setChecked(prefs.getBoolean("custom", false));
        ((RadioButton) findViewById(R.id.modeMax)).setChecked(!modeCustom.isChecked());
        dbInput.setText(prefs.getString("db", "-6"));
        dbInput.setEnabled(modeCustom.isChecked());
        autoApply.setChecked(prefs.getBoolean("auto", false));
        quietMode.setChecked(prefs.getBoolean("quiet", true));

        modeGroup.setOnCheckedChangeListener((g, id) -> {
            dbInput.setEnabled(modeCustom.isChecked());
            savePrefs();
        });
        autoApply.setOnCheckedChangeListener((b, c) -> savePrefs());
        quietMode.setOnCheckedChangeListener((b, c) -> savePrefs());
        applyBtn.setOnClickListener(v -> startApply(false));
        applyBtn.setEnabled(false);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(permReceiver, filter);
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(permReceiver);
        worker.shutdown();
        super.onDestroy();
    }

    private void handleIntent(Intent in) {
        boolean attach = in != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(in.getAction());
        launchedByAttach = attach;
        refresh(attach ? usbDeviceExtra(in) : null, attach && autoApply.isChecked());
    }

    private void refresh(UsbDevice preferred, boolean autoApplyWhenReady) {
        UsbDevice target = preferred != null ? preferred : pickAudioDevice();
        if (target == null) {
            device = null;
            info = null;
            applyBtn.setEnabled(false);
            deviceInfo.setText(R.string.no_device);
            return;
        }
        device = target;
        if (!usbManager.hasPermission(target)) {
            pendingAuto = autoApplyWhenReady;
            deviceInfo.setText(R.string.waiting_permission);
            usbManager.requestPermission(target, permissionIntent());
            return;
        }
        loadDeviceInfo(target);
        if (autoApplyWhenReady) startApply(true);
    }

    private PendingIntent permissionIntent() {
        Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
        return PendingIntent.getBroadcast(this, 0, i, flags);
    }

    private UsbDevice pickAudioDevice() {
        UsbDevice first = null;
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (!hasAudioControl(d)) continue;
            if (d.getVendorId() == VENDOR_APPLE) return d;
            if (first == null) first = d;
        }
        return first;
    }

    private static boolean hasAudioControl(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface intf = d.getInterface(i);
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO && intf.getInterfaceSubclass() == 1) {
                return true;
            }
        }
        return false;
    }

    /** Yalnızca descriptor okur: arayüz sökülmez, ses kesilmez. */
    private void loadDeviceInfo(UsbDevice d) {
        byte[] raw = null;
        UsbDeviceConnection c = usbManager.openDevice(d);
        if (c != null) {
            try {
                raw = c.getRawDescriptors();
            } finally {
                c.close();
            }
        }
        info = UacParser.parse(raw);
        String name = d.getProductName() != null ? d.getProductName() : d.getDeviceName();
        deviceInfo.setText(String.format(Locale.US, "%s\nVID:PID %04X:%04X\n%s",
                name, d.getVendorId(), d.getProductId(), info.describe()));
        applyBtn.setEnabled(!busy);
    }

    private VolumeEngine.Request buildRequest() {
        if (!modeCustom.isChecked()) return VolumeEngine.Request.max();
        try {
            double db = Double.parseDouble(dbInput.getText().toString().trim().replace(',', '.'));
            if (db < -127 || db > 20) throw new NumberFormatException();
            return VolumeEngine.Request.db(db);
        } catch (NumberFormatException e) {
            setStatus(getString(R.string.invalid_db));
            return null;
        }
    }

    private void startApply(boolean auto) {
        if (busy || device == null) return;
        final VolumeEngine.Request rq = buildRequest();
        if (rq == null) return;

        if (auto) {
            String key = device.getDeviceName();
            long now = System.currentTimeMillis();
            Long last = LAST_AUTO.get(key);
            if (last != null && now - last < AUTO_COOLDOWN_MS) return;
            LAST_AUTO.put(key, now);
        }

        final UsbDevice dev = device;
        final UacParser.Info inf = info;
        final boolean quiet = quietMode.isChecked();
        busy = true;
        applyBtn.setEnabled(false);
        setStatus(getString(R.string.applying));

        worker.execute(() -> {
            VolumeEngine.Report rep;
            try {
                UsbDeviceConnection c = usbManager.openDevice(dev);
                if (c == null) {
                    rep = new VolumeEngine.Report();
                    rep.lines.add("Cihaz açılamadı (izin?).");
                } else {
                    try {
                        rep = VolumeEngine.apply(new AndroidUsbIo(dev, c), inf, rq, quiet);
                    } finally {
                        c.close();
                    }
                }
            } catch (Throwable t) {
                rep = new VolumeEngine.Report();
                rep.lines.add("Hata: " + t);
            }
            final VolumeEngine.Report result = rep;
            ui.post(() -> onApplied(result, auto));
        });
    }

    private void onApplied(VolumeEngine.Report rep, boolean auto) {
        busy = false;
        applyBtn.setEnabled(device != null);
        setStatus(rep.text());
        savePrefs();
        if (auto) {
            Toast.makeText(this, rep.ok ? getString(R.string.toast_ok) : getString(R.string.toast_fail),
                    Toast.LENGTH_SHORT).show();
            if (launchedByAttach && rep.ok) ui.postDelayed(this::finish, 1500);
        }
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    private void savePrefs() {
        prefs.edit()
                .putBoolean("custom", modeCustom.isChecked())
                .putString("db", dbInput.getText().toString())
                .putBoolean("auto", autoApply.isChecked())
                .putBoolean("quiet", quietMode.isChecked())
                .apply();
    }

    @SuppressWarnings("deprecation")
    private static UsbDevice usbDeviceExtra(Intent in) {
        if (in == null) return null;
        return Build.VERSION.SDK_INT >= 33
                ? in.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                : in.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }
}
