package com.pocketcontrol.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

/**
 * Pocket Control 1.5
 *
 * Base estabilizada do aplicativo de uso real:
 * - conexão automática com DJI HG210 / Osmo Pocket 1;
 * - leitor USB contínuo;
 * - roteador LogicLink 0x5749 (controle) / 0x574A (vídeo);
 * - troca FOTO / VÍDEO já validada;
 * - SurfaceView + MediaCodec preparados para H.264;
 * - layout já com cara de câmera, não de diagnóstico.
 *
 * IMPORTANTE:
 * O bootstrap específico que faz a HG210 começar a emitir o canal de vídeo
 * ainda não foi identificado. Por isso este código NÃO inventa comandos.
 * Quando 0x574A aparecer, o vídeo H.264 é encaminhado ao decoder.
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String ACTION_USB_PERMISSION =
            "com.pocketcontrol.app.USB_PERMISSION";

    private static final int PORT_DUML  = 0x5749;
    private static final int PORT_VIDEO = 0x574A;

    private UsbManager usbManager;
    private PendingIntent permissionIntent;
    private boolean receiverRegistered;

    private ParcelFileDescriptor accessoryDescriptor;
    private FileInputStream accessoryInput;
    private FileOutputStream accessoryOutput;

    private volatile boolean readerRunning;
    private Thread readerThread;

    private byte[] logicLinkPending = new byte[0];

    private int nextTxSequence = 1;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean connecting = false;
    private volatile boolean destroyed = false;
    private int connectionGeneration = 0;
    private UsbAccessory currentAccessory;

    // UI
    private TextView connectionView;
    private TextView cameraStateView;
    private TextView videoStateView;
    private TextView telemetryView;
    private TextView logView;
    private LinearLayout logPanel;
    private SurfaceView previewView;
    private Surface previewSurface;

    private Button photoModeButton;
    private Button videoModeButton;
    private Button shutterButton;

    private final StringBuilder log = new StringBuilder();

    // Estado observado
    private int currentCameraMode = -1;
    private long dumlPackets = 0;
    private long videoPackets = 0;
    private long videoBytes = 0;

    // H264
    private final H264AnnexBCollector h264Collector = new H264AnnexBCollector();
    private H264Decoder h264Decoder;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false);

                UsbAccessory accessory = getAccessoryFromIntent(intent);

                if (granted && accessory != null) {
                    appendLog("Permissão USB concedida.");
                    openAccessory(accessory);
                } else {
                    connecting = false;
                    appendLog("Permissão USB negada.");
                    setConnectionStatus("Permissão USB necessária", false);
                }
                return;
            }

            if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                UsbAccessory detached = getAccessoryFromIntent(intent);

                if (detached == null
                        || currentAccessory == null
                        || detached.equals(currentAccessory)) {
                    appendLog("Osmo desconectada / USB reenumerado.");
                    closeAccessory();
                    cameraStateView.setText("Pocket desconectada.");
                    videoStateView.setText("LIVE VIEW: aguardando HG210");
                }
            }
        }
    };

    private UsbAccessory getAccessoryFromIntent(Intent intent) {
        if (intent == null) return null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(
                    UsbManager.EXTRA_ACCESSORY,
                    UsbAccessory.class);
        }

        return intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        createPermissionIntent();
        registerPermissionReceiver();
        buildCameraUi();

        h264Decoder = new H264Decoder();

        mainHandler.postDelayed(this::autoConnectPocket, 300);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!destroyed) {
            mainHandler.postDelayed(this::autoConnectPocket, 250);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        appendLog("Evento USB: " + safe(intent.getAction()));

        if (!destroyed) {
            mainHandler.postDelayed(this::autoConnectPocket, 180);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);

        stopReader();
        closeAccessory();

        if (h264Decoder != null) {
            h264Decoder.release();
        }

        if (receiverRegistered) {
            try {
                unregisterReceiver(usbReceiver);
            } catch (Throwable ignored) {
            }
        }

        super.onDestroy();
    }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------

    private void buildCameraUi() {
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // Top bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        topBar.setBackgroundColor(Color.rgb(18, 18, 18));

        TextView appName = new TextView(this);
        appName.setText("Pocket Control");
        appName.setTextColor(Color.WHITE);
        appName.setTextSize(18f);
        appName.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(appName, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        connectionView = new TextView(this);
        connectionView.setText("DESCONECTADA");
        connectionView.setTextColor(Color.LTGRAY);
        connectionView.setTextSize(12f);
        connectionView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        topBar.addView(connectionView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(topBar);

        // Preview area
        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);

        previewView = new SurfaceView(this);
        previewView.getHolder().addCallback(this);
        previewFrame.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Overlay superior
        LinearLayout overlayTop = new LinearLayout(this);
        overlayTop.setOrientation(LinearLayout.VERTICAL);
        overlayTop.setPadding(dp(12), dp(10), dp(12), dp(10));
        overlayTop.setBackgroundColor(0x55000000);

        cameraStateView = new TextView(this);
        cameraStateView.setText("Aguardando câmera...");
        cameraStateView.setTextColor(Color.WHITE);
        cameraStateView.setTextSize(13f);
        overlayTop.addView(cameraStateView);

        videoStateView = new TextView(this);
        videoStateView.setText("LIVE VIEW: aguardando bootstrap HG210");
        videoStateView.setTextColor(Color.LTGRAY);
        videoStateView.setTextSize(12f);
        overlayTop.addView(videoStateView);

        FrameLayout.LayoutParams overlayTopLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        overlayTopLp.gravity = Gravity.TOP;
        previewFrame.addView(overlayTop, overlayTopLp);

        // Texto central enquanto não há vídeo
        TextView placeholder = new TextView(this);
        placeholder.setText(
                "OSMO POCKET 1\n\n" +
                "Canal de controle: pronto\n" +
                "Conexão AOA: estabilizada\n" +
                "Decoder H.264: pronto\n\n" +
                "Aguardando bootstrap de vídeo HG210");
        placeholder.setTextColor(Color.GRAY);
        placeholder.setTextSize(16f);
        placeholder.setGravity(Gravity.CENTER);

        FrameLayout.LayoutParams placeholderLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        previewFrame.addView(placeholder, placeholderLp);

        root.addView(previewFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Telemetria
        telemetryView = new TextView(this);
        telemetryView.setText("DUML 0 | VIDEO 0");
        telemetryView.setTextColor(Color.LTGRAY);
        telemetryView.setTextSize(11f);
        telemetryView.setGravity(Gravity.CENTER);
        telemetryView.setPadding(dp(8), dp(4), dp(8), dp(4));
        telemetryView.setBackgroundColor(Color.rgb(18, 18, 18));
        root.addView(telemetryView);

        // Mode row
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(dp(10), dp(8), dp(10), dp(4));
        modeRow.setBackgroundColor(Color.rgb(14, 14, 14));

        photoModeButton = new Button(this);
        photoModeButton.setText("FOTO");
        photoModeButton.setOnClickListener(v -> sendSetCameraMode(0));
        modeRow.addView(photoModeButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        videoModeButton = new Button(this);
        videoModeButton.setText("VÍDEO");
        videoModeButton.setOnClickListener(v -> sendSetCameraMode(1));
        modeRow.addView(videoModeButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(modeRow);

        // Main control row
        LinearLayout controlRow = new LinearLayout(this);
        controlRow.setOrientation(LinearLayout.HORIZONTAL);
        controlRow.setGravity(Gravity.CENTER);
        controlRow.setPadding(dp(10), dp(4), dp(10), dp(8));
        controlRow.setBackgroundColor(Color.rgb(14, 14, 14));

        Button settingsButton = new Button(this);
        settingsButton.setText("ISO / EV / WB");
        settingsButton.setEnabled(false);
        controlRow.addView(settingsButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        shutterButton = new Button(this);
        shutterButton.setText("●");
        shutterButton.setTextSize(26f);
        shutterButton.setEnabled(false);
        shutterButton.setOnClickListener(v ->
                Toast.makeText(this,
                        "FOTO/REC será ativado depois de validar o payload.",
                        Toast.LENGTH_SHORT).show());
        controlRow.addView(shutterButton, new LinearLayout.LayoutParams(
                0, dp(72), 1f));

        Button debugButton = new Button(this);
        debugButton.setText("LOG");
        debugButton.setOnClickListener(v -> toggleLog());
        controlRow.addView(debugButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(controlRow);

        // Log panel
        logPanel = new LinearLayout(this);
        logPanel.setOrientation(LinearLayout.VERTICAL);
        logPanel.setVisibility(View.GONE);
        logPanel.setBackgroundColor(Color.rgb(24, 24, 24));
        logPanel.setPadding(dp(8), dp(8), dp(8), dp(8));

        LinearLayout logActions = new LinearLayout(this);
        logActions.setOrientation(LinearLayout.HORIZONTAL);

        Button copyLogButton = new Button(this);
        copyLogButton.setText("COPIAR LOG");
        copyLogButton.setOnClickListener(v -> copyLog());
        logActions.addView(copyLogButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button reconnectButton = new Button(this);
        reconnectButton.setText("RECONECTAR");
        reconnectButton.setOnClickListener(v -> {
            appendLog("Reconexão manual solicitada.");
            closeAccessory();
            scheduleReconnect(500);
        });
        logActions.addView(reconnectButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        logPanel.addView(logActions);

        ScrollView logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextColor(Color.LTGRAY);
        logView.setTextSize(11f);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);

        logPanel.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));

        root.addView(logPanel);

        setContentView(root);
    }

    private void toggleLog() {
        logPanel.setVisibility(
                logPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void copyLog() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        String text =
                "Pocket Control 1.5\n" +
                "Android " + Build.VERSION.RELEASE +
                " / " + Build.MANUFACTURER + " " + Build.MODEL + "\n\n" +
                log;

        clipboard.setPrimaryClip(ClipData.newPlainText("Pocket Control log", text));
        Toast.makeText(this, "Log copiado.", Toast.LENGTH_SHORT).show();
    }

    private void appendLog(String text) {
        final String line = "[PC] " + text + "\n";

        synchronized (log) {
            log.append(line);

            if (log.length() > 80_000) {
                log.delete(0, 20_000);
            }
        }

        runOnUiThread(() -> {
            if (logView != null) {
                logView.setText(log.toString());
            }
        });
    }

    private void setConnectionStatus(String text, boolean ok) {
        runOnUiThread(() -> {
            connectionView.setText(text);
            connectionView.setTextColor(ok ? Color.GREEN : Color.LTGRAY);
        });
    }

    private void updateTelemetry() {
        runOnUiThread(() -> telemetryView.setText(
                "DUML " + dumlPackets +
                "  |  VIDEO " + videoPackets +
                "  |  " + formatBytes(videoBytes)));
    }

    // -------------------------------------------------------------------------
    // USB / AOA
    // -------------------------------------------------------------------------

    private void createPermissionIntent() {
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        intent.setPackage(getPackageName());

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }

        permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags);
    }

    private void registerPermissionReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                    usbReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        receiverRegistered = true;
    }

    private synchronized void autoConnectPocket() {
        if (destroyed) return;

        if (accessoryDescriptor != null || connecting) {
            return;
        }

        UsbAccessory[] list = usbManager.getAccessoryList();

        if (list == null || list.length == 0) {
            setConnectionStatus("DESCONECTADA", false);
            cameraStateView.setText("Conecte e ligue a Osmo Pocket.");
            return;
        }

        UsbAccessory target = null;

        for (UsbAccessory a : list) {
            if ("DJI".equalsIgnoreCase(safe(a.getManufacturer()))
                    && "HG210".equalsIgnoreCase(safe(a.getModel()))) {
                target = a;
                break;
            }
        }

        if (target == null) {
            setConnectionStatus("USB desconhecido", false);
            return;
        }

        connecting = true;

        appendLog("HG210 encontrada: "
                + safe(target.getManufacturer()) + " / "
                + safe(target.getModel()));

        if (usbManager.hasPermission(target)) {
            openAccessory(target);
        } else {
            appendLog("Solicitando permissão USB...");
            usbManager.requestPermission(target, permissionIntent);
        }
    }

    private synchronized void openAccessory(UsbAccessory accessory) {
        if (destroyed) {
            connecting = false;
            return;
        }

        if (accessoryDescriptor != null) {
            connecting = false;
            return;
        }

        try {
            ParcelFileDescriptor descriptor =
                    usbManager.openAccessory(accessory);

            if (descriptor == null) {
                connecting = false;
                appendLog("openAccessory() retornou null.");
                setConnectionStatus("FALHA USB", false);
                scheduleReconnect(800);
                return;
            }

            accessoryDescriptor = descriptor;
            accessoryInput =
                    new FileInputStream(descriptor.getFileDescriptor());
            accessoryOutput =
                    new FileOutputStream(descriptor.getFileDescriptor());

            currentAccessory = accessory;
            connectionGeneration++;
            connecting = false;

            logicLinkPending = new byte[0];
            currentCameraMode = -1;
            dumlPackets = 0;
            videoPackets = 0;
            videoBytes = 0;

            setConnectionStatus("HG210 CONECTADA", true);
            cameraStateView.setText("Osmo conectada. Lendo estado...");
            videoStateView.setText("LIVE VIEW: aguardando bootstrap HG210");

            appendLog("Canal AOA aberto. fd="
                    + descriptor.getFd()
                    + " geração=" + connectionGeneration);

            startReader(connectionGeneration);

        } catch (Throwable t) {
            connecting = false;

            appendLog("Erro abrindo AOA: "
                    + t.getClass().getSimpleName()
                    + " - " + safe(t.getMessage()));

            closeAccessory();
            scheduleReconnect(900);
        }
    }

    private synchronized void closeAccessory() {
        readerRunning = false;
        connecting = false;

        FileInputStream input = accessoryInput;
        FileOutputStream output = accessoryOutput;
        ParcelFileDescriptor descriptor = accessoryDescriptor;

        accessoryInput = null;
        accessoryOutput = null;
        accessoryDescriptor = null;
        currentAccessory = null;

        logicLinkPending = new byte[0];

        try {
            if (input != null) input.close();
        } catch (Throwable ignored) {
        }

        try {
            if (output != null) output.close();
        } catch (Throwable ignored) {
        }

        try {
            if (descriptor != null) descriptor.close();
        } catch (Throwable ignored) {
        }

        setConnectionStatus("DESCONECTADA", false);
    }

    private void scheduleReconnect(long delayMs) {
        if (destroyed) return;

        mainHandler.postDelayed(() -> {
            if (!destroyed
                    && accessoryDescriptor == null
                    && !connecting) {
                autoConnectPocket();
            }
        }, delayMs);
    }

    // -------------------------------------------------------------------------
    // Leitor contínuo LogicLink
    // -------------------------------------------------------------------------

    private synchronized void startReader(final int generation) {
        if (readerRunning) {
            appendLog("Leitor já ativo; novo start ignorado.");
            return;
        }

        if (accessoryInput == null || accessoryDescriptor == null) {
            return;
        }

        final FileInputStream inputForThisReader = accessoryInput;

        readerRunning = true;

        readerThread = new Thread(() -> {
            byte[] buffer = new byte[16 * 1024];

            try {
                while (readerRunning
                        && generation == connectionGeneration
                        && inputForThisReader == accessoryInput) {

                    int count = inputForThisReader.read(buffer);

                    if (count < 0) {
                        throw new java.io.IOException("EOF no canal AOA");
                    }

                    if (count == 0) continue;

                    feedLogicLink(buffer, count);
                }

            } catch (Throwable t) {
                boolean stale =
                        generation != connectionGeneration
                        || inputForThisReader != accessoryInput
                        || destroyed;

                if (!stale) {
                    appendLog("Leitor USB da geração "
                            + generation
                            + " terminou: "
                            + t.getClass().getSimpleName()
                            + " - " + safe(t.getMessage()));

                    runOnUiThread(() -> {
                        cameraStateView.setText(
                                "USB interrompido. Reconectando...");
                        videoStateView.setText(
                                "LIVE VIEW: aguardando reconexão");
                    });

                    synchronized (MainActivity.this) {
                        if (generation == connectionGeneration) {
                            closeAccessory();
                        }
                    }

                    scheduleReconnect(750);
                }

            } finally {
                if (generation == connectionGeneration) {
                    readerRunning = false;
                }
            }

        }, "PocketControl-LogicLinkReader-" + generation);

        readerThread.start();

        appendLog("Leitor LogicLink contínuo iniciado. geração="
                + generation);
    }

    private void stopReader() {
        readerRunning = false;

        Thread t = readerThread;
        readerThread = null;

        if (t != null) {
            try {
                t.interrupt();
            } catch (Throwable ignored) {
            }
        }
    }

    private void feedLogicLink(byte[] incoming, int length) {
        byte[] merged =
                new byte[logicLinkPending.length + length];

        System.arraycopy(
                logicLinkPending, 0,
                merged, 0,
                logicLinkPending.length);

        System.arraycopy(
                incoming, 0,
                merged, logicLinkPending.length,
                length);

        int p = 0;

        while (merged.length - p >= 8) {

            // resync
            if ((merged[p] & 0xFF) != 0x55
                    || (merged[p + 1] & 0xFF) != 0xCC) {
                p++;
                continue;
            }

            int port = readLe16(merged, p + 2);

            long payloadLenLong =
                    ((long) merged[p + 4] & 0xFFL)
                    | (((long) merged[p + 5] & 0xFFL) << 8)
                    | (((long) merged[p + 6] & 0xFFL) << 16)
                    | (((long) merged[p + 7] & 0xFFL) << 24);

            if (payloadLenLong < 0 || payloadLenLong > 8 * 1024 * 1024) {
                p++;
                continue;
            }

            int payloadLen = (int) payloadLenLong;
            int packetLen = 8 + payloadLen;

            if (merged.length - p < packetLen) {
                break;
            }

            byte[] payload =
                    Arrays.copyOfRange(
                            merged,
                            p + 8,
                            p + packetLen);

            routeLogicLinkPacket(port, payload);

            p += packetLen;
        }

        logicLinkPending =
                p < merged.length
                        ? Arrays.copyOfRange(merged, p, merged.length)
                        : new byte[0];

        // Evita crescimento infinito se perdermos sincronismo.
        if (logicLinkPending.length > 2 * 1024 * 1024) {
            appendLog("Resync LogicLink: buffer residual muito grande.");
            logicLinkPending = new byte[0];
        }
    }

    private void routeLogicLinkPacket(int port, byte[] payload) {
        if (port == PORT_DUML) {
            dumlPackets++;
            parseDuml(payload);

            if ((dumlPackets % 25) == 0) {
                updateTelemetry();
            }

        } else if (port == PORT_VIDEO) {
            videoPackets++;
            videoBytes += payload.length;

            if (videoPackets == 1) {
                appendLog("*** PORTA 0x574A APARECEU — VÍDEO RECEBIDO ***");
                runOnUiThread(() ->
                        videoStateView.setText("LIVE VIEW: recebendo 0x574A"));
            }

            h264Collector.push(payload);

            if ((videoPackets % 10) == 0) {
                updateTelemetry();
            }

        } else {
            appendLog(String.format(
                    Locale.US,
                    "LogicLink port desconhecida 0x%04X (%d bytes)",
                    port,
                    payload.length));
        }
    }

    // -------------------------------------------------------------------------
    // DUML — leitura de estado + comandos já validados
    // -------------------------------------------------------------------------

    private void parseDuml(byte[] data) {
        int p = 0;

        while (p + 13 <= data.length) {
            if ((data[p] & 0xFF) != 0x55) {
                p++;
                continue;
            }

            int frameLength =
                    (data[p + 1] & 0xFF)
                    | ((data[p + 2] & 0x03) << 8);

            if (frameLength < 13 || p + frameLength > data.length) {
                break;
            }

            int cmdSet = data[p + 9] & 0xFF;
            int cmdId  = data[p + 10] & 0xFF;

            int innerStart = p + 11;
            int innerLength = frameLength - 13;

            // Camera State Info
            if (cmdSet == 0x02
                    && cmdId == 0x80
                    && innerLength >= 5) {

                int mode = data[innerStart + 4] & 0xFF;

                if (mode != currentCameraMode) {
                    currentCameraMode = mode;

                    runOnUiThread(() -> {
                        String name =
                                currentCameraMode == 0 ? "FOTO"
                                : currentCameraMode == 1 ? "VÍDEO"
                                : "MODO " + currentCameraMode;

                        cameraStateView.setText(
                                "Câmera: " + name +
                                "  •  controle bidirecional ativo");

                        updateModeButtons();
                    });
                }
            }

            p += frameLength;
        }
    }

    private void updateModeButtons() {
        photoModeButton.setEnabled(currentCameraMode != 0);
        videoModeButton.setEnabled(currentCameraMode != 1);
    }

    private synchronized void sendSetCameraMode(int mode) {
        if (accessoryOutput == null) {
            Toast.makeText(this,
                    "Pocket não conectada.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (mode != 0 && mode != 1) return;

        try {
            int seq = nextSequence();

            byte[] duml = buildDumlPacket(
                    0x02,       // App
                    0x01,       // Camera
                    seq,
                    0,          // request
                    2,          // ACK after execution
                    0,          // no encryption
                    0x02,       // Camera command set
                    0x10,       // Camera Work Mode Set
                    new byte[] {(byte) mode});

            byte[] outer = wrapLogicLink(PORT_DUML, duml);

            accessoryOutput.write(outer);
            accessoryOutput.flush();

            appendLog(
                    "TX Camera Work Mode Set -> " +
                    (mode == 0 ? "FOTO" : "VÍDEO") +
                    ", seq=" + seq);

        } catch (Throwable t) {
            appendLog("Erro no TX: " +
                    t.getClass().getSimpleName() +
                    " - " + safe(t.getMessage()));
        }
    }

    private int nextSequence() {
        int seq = nextTxSequence & 0xFFFF;

        nextTxSequence = (nextTxSequence + 1) & 0xFFFF;
        if (nextTxSequence == 0) nextTxSequence = 1;

        return seq;
    }

    private byte[] buildDumlPacket(
            int sender,
            int receiver,
            int seq,
            int packetType,
            int ackType,
            int encryptType,
            int cmdSet,
            int cmdId,
            byte[] payload) {

        if (payload == null) payload = new byte[0];

        int length = 11 + payload.length + 2;
        byte[] out = new byte[length];

        out[0] = 0x55;

        int verLength = (1 << 10) | (length & 0x03FF);

        out[1] = (byte) (verLength & 0xFF);
        out[2] = (byte) ((verLength >> 8) & 0xFF);

        out[3] = (byte) crc8Dji(0x77, out, 0, 3);

        out[4] = (byte) sender;
        out[5] = (byte) receiver;

        out[6] = (byte) (seq & 0xFF);
        out[7] = (byte) ((seq >> 8) & 0xFF);

        out[8] = (byte) (
                ((packetType & 1) << 7)
                | ((ackType & 3) << 5)
                | (encryptType & 7));

        out[9]  = (byte) cmdSet;
        out[10] = (byte) cmdId;

        if (payload.length > 0) {
            System.arraycopy(payload, 0, out, 11, payload.length);
        }

        int crc16 = crc16Dji(out, 0, length - 2);

        out[length - 2] = (byte) (crc16 & 0xFF);
        out[length - 1] = (byte) ((crc16 >> 8) & 0xFF);

        return out;
    }

    private byte[] wrapLogicLink(int port, byte[] payload) {
        byte[] out = new byte[8 + payload.length];

        out[0] = 0x55;
        out[1] = (byte) 0xCC;

        out[2] = (byte) (port & 0xFF);
        out[3] = (byte) ((port >> 8) & 0xFF);

        int length = payload.length;

        out[4] = (byte) (length & 0xFF);
        out[5] = (byte) ((length >> 8) & 0xFF);
        out[6] = (byte) ((length >> 16) & 0xFF);
        out[7] = (byte) ((length >> 24) & 0xFF);

        System.arraycopy(payload, 0, out, 8, payload.length);

        return out;
    }

    // -------------------------------------------------------------------------
    // H.264 — pronto para quando a HG210 liberar 0x574A
    // -------------------------------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        previewSurface = holder.getSurface();

        if (h264Decoder != null) {
            h264Decoder.setSurface(previewSurface);
        }
    }

    @Override
    public void surfaceChanged(
            SurfaceHolder holder,
            int format,
            int width,
            int height) {

        previewSurface = holder.getSurface();

        if (h264Decoder != null) {
            h264Decoder.setSurface(previewSurface);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        previewSurface = null;

        if (h264Decoder != null) {
            h264Decoder.release();
        }
    }

    private final class H264AnnexBCollector {

        private byte[] pending = new byte[0];

        void push(byte[] chunk) {
            if (chunk == null || chunk.length == 0) return;

            byte[] merged =
                    new byte[pending.length + chunk.length];

            System.arraycopy(pending, 0, merged, 0, pending.length);
            System.arraycopy(chunk, 0, merged, pending.length, chunk.length);

            int first = findStartCode(merged, 0);

            if (first < 0) {
                // ainda não localizou Annex-B
                if (merged.length > 2 * 1024 * 1024) {
                    pending = Arrays.copyOfRange(
                            merged,
                            merged.length - 128 * 1024,
                            merged.length);
                } else {
                    pending = merged;
                }
                return;
            }

            int current = first;

            while (true) {
                int startCodeLen = startCodeLength(merged, current);
                int nalStart = current + startCodeLen;

                int next = findStartCode(merged, nalStart);

                if (next < 0) {
                    pending = Arrays.copyOfRange(
                            merged,
                            current,
                            merged.length);
                    return;
                }

                if (nalStart < next) {
                    byte[] nal =
                            Arrays.copyOfRange(
                                    merged,
                                    nalStart,
                                    next);

                    processNal(nal);
                }

                current = next;
            }
        }

        private void processNal(byte[] nal) {
            if (nal == null || nal.length == 0) return;

            int type = nal[0] & 0x1F;

            if (type == 7) {
                appendLog("H264 SPS recebido (" + nal.length + " bytes)");
            } else if (type == 8) {
                appendLog("H264 PPS recebido (" + nal.length + " bytes)");
            } else if (type == 5 && h264Decoder != null) {
                appendLog("H264 IDR recebido (" + nal.length + " bytes)");
            }

            if (h264Decoder != null) {
                h264Decoder.feedNal(nal, type);
            }
        }

        private int findStartCode(byte[] data, int from) {
            for (int i = Math.max(0, from); i + 3 < data.length; i++) {
                if (data[i] == 0
                        && data[i + 1] == 0
                        && data[i + 2] == 1) {
                    return i;
                }

                if (i + 4 < data.length
                        && data[i] == 0
                        && data[i + 1] == 0
                        && data[i + 2] == 0
                        && data[i + 3] == 1) {
                    return i;
                }
            }

            return -1;
        }

        private int startCodeLength(byte[] data, int pos) {
            if (pos + 3 < data.length
                    && data[pos] == 0
                    && data[pos + 1] == 0
                    && data[pos + 2] == 1) {
                return 3;
            }

            return 4;
        }
    }

    private final class H264Decoder {

        private MediaCodec codec;
        private Surface surface;

        private byte[] sps;
        private byte[] pps;

        private boolean started;
        private long frameIndex;

        synchronized void setSurface(Surface newSurface) {
            surface = newSurface;

            if (!started && sps != null && pps != null) {
                startCodecIfReady();
            }
        }

        synchronized void feedNal(byte[] nal, int type) {
            if (nal == null || nal.length == 0) return;

            if (type == 7) {
                sps = Arrays.copyOf(nal, nal.length);
                startCodecIfReady();
                return;
            }

            if (type == 8) {
                pps = Arrays.copyOf(nal, nal.length);
                startCodecIfReady();
                return;
            }

            if (!started) return;

            if (type != 1 && type != 5 && type != 6) {
                return;
            }

            try {
                byte[] data = withStartCode(nal);

                int inputIndex = codec.dequeueInputBuffer(0);

                if (inputIndex >= 0) {
                    ByteBuffer input = codec.getInputBuffer(inputIndex);

                    if (input != null) {
                        input.clear();

                        if (data.length <= input.remaining()) {
                            input.put(data);

                            long ptsUs =
                                    (frameIndex++ * 1_000_000L) / 30L;

                            codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    data.length,
                                    ptsUs,
                                    type == 5
                                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME
                                            : 0);
                        }
                    }
                }

                drain();

            } catch (Throwable t) {
                appendLog("Decoder H264: " +
                        t.getClass().getSimpleName() +
                        " - " + safe(t.getMessage()));
            }
        }

        private void startCodecIfReady() {
            if (started) return;
            if (surface == null || !surface.isValid()) return;
            if (sps == null || pps == null) return;

            try {
                codec =
                        MediaCodec.createDecoderByType(
                                MediaFormat.MIMETYPE_VIDEO_AVC);

                MediaFormat format =
                        MediaFormat.createVideoFormat(
                                MediaFormat.MIMETYPE_VIDEO_AVC,
                                1920,
                                1080);

                format.setByteBuffer(
                        "csd-0",
                        ByteBuffer.wrap(withStartCode(sps)));

                format.setByteBuffer(
                        "csd-1",
                        ByteBuffer.wrap(withStartCode(pps)));

                codec.configure(
                        format,
                        surface,
                        null,
                        0);

                codec.start();
                started = true;

                runOnUiThread(() ->
                        videoStateView.setText(
                                "LIVE VIEW: H.264 decoder ativo"));

                appendLog("MediaCodec AVC iniciado com SPS/PPS.");

            } catch (Throwable t) {
                appendLog("Falha iniciando MediaCodec: " +
                        t.getClass().getSimpleName() +
                        " - " + safe(t.getMessage()));

                release();
            }
        }

        private void drain() {
            if (!started || codec == null) return;

            MediaCodec.BufferInfo info =
                    new MediaCodec.BufferInfo();

            while (true) {
                int outIndex =
                        codec.dequeueOutputBuffer(info, 0);

                if (outIndex >= 0) {
                    codec.releaseOutputBuffer(outIndex, true);
                } else if (outIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                    MediaFormat f = codec.getOutputFormat();

                    appendLog(
                            "Formato de vídeo detectado: " +
                            f.toString());

                } else {
                    break;
                }
            }
        }

        synchronized void release() {
            started = false;

            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                }

                try {
                    codec.release();
                } catch (Throwable ignored) {
                }
            }

            codec = null;
        }

        private byte[] withStartCode(byte[] nal) {
            byte[] out = new byte[nal.length + 4];

            out[0] = 0;
            out[1] = 0;
            out[2] = 0;
            out[3] = 1;

            System.arraycopy(
                    nal, 0,
                    out, 4,
                    nal.length);

            return out;
        }
    }

    // -------------------------------------------------------------------------
    // CRC / util
    // -------------------------------------------------------------------------

    private int crc8Dji(
            int seed,
            byte[] data,
            int offset,
            int length) {

        int crc = seed & 0xFF;

        for (int i = 0; i < length; i++) {
            crc ^= data[offset + i] & 0xFF;

            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 1) != 0) {
                    crc = (crc >> 1) ^ 0x8C;
                } else {
                    crc >>= 1;
                }

                crc &= 0xFF;
            }
        }

        return crc;
    }

    private int crc16Dji(
            byte[] data,
            int offset,
            int length) {

        int crc = 0x3692;

        for (int i = 0; i < length; i++) {
            crc ^= data[offset + i] & 0xFF;

            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 1) != 0) {
                    crc = (crc >> 1) ^ 0x8408;
                } else {
                    crc >>= 1;
                }

                crc &= 0xFFFF;
            }
        }

        return crc;
    }

    private int readLe16(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8);
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String formatBytes(long value) {
        if (value < 1024) return value + " B";

        double kb = value / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }

        return String.format(
                Locale.US,
                "%.2f MB",
                kb / 1024.0);
    }
}
