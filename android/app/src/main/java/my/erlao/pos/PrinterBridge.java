package my.erlao.pos;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Exposed to the POS web page as window.AndroidPrinter.
 * send(target, host, base64) writes raw ESC/POS bytes to a USB printer ("usb")
 * or a network printer on port 9100 ("lan"). Returns "OK" or a readable error.
 */
public class PrinterBridge {
    private static final String ACTION_USB_PERMISSION = "my.erlao.pos.USB_PERMISSION";
    private final Context ctx;
    private final UsbManager usb;
    private final Object lock = new Object();

    public PrinterBridge(Context ctx) {
        this.ctx = ctx;
        this.usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
    }

    @JavascriptInterface
    public String send(String target, String host, String b64) {
        synchronized (lock) {
            try {
                return dispatch(target, host, Base64.decode(b64, Base64.DEFAULT));
            } catch (Throwable t) {
                return "Printer error: " + t.getMessage();
            }
        }
    }

    /** Plain-text ticket (printer's own fonts, Chinese as GBK). json = {cols, ops:[...]} */
    @JavascriptInterface
    public String sendOps(String target, String host, String json) {
        synchronized (lock) {
            try {
                return dispatch(target, host, buildTicket(json));
            } catch (Throwable t) {
                return "Printer error: " + t.getMessage();
            }
        }
    }

    private String dispatch(String target, String host, byte[] data) {
        if ("lan".equals(target)) {
            return sendLan(host, data);
        }
        try {
            return sendUsb(data);
        } catch (InterruptedException e) {
            return "Printer error: interrupted";
        }
    }

    @JavascriptInterface
    public String version() {
        return "1.6";
    }


    /* ---------------- text tickets ---------------- */

    private static final Charset GBK = Charset.forName("GBK");

    private static int dispWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) > 0x7F ? 2 : 1;
        }
        return w;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    /** Split text into pieces no wider than max display columns. */
    private static java.util.List<String> wrap(String s, int max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int cw = c > 0x7F ? 2 : 1;
            if (w + cw > max && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
                w = 0;
                if (c == ' ') continue;
            }
            cur.append(c);
            w += cw;
        }
        if (cur.length() > 0) out.add(cur.toString());
        if (out.isEmpty()) out.add("");
        return out;
    }

    private static void line(ByteArrayOutputStream o, String text, int align, boolean dh, boolean dw, boolean bold) throws Exception {
        o.write(new byte[]{0x1B, 0x61, (byte) align});
        o.write(new byte[]{0x1D, 0x21, (byte) ((dw ? 0x10 : 0) | (dh ? 0x01 : 0))});
        o.write(new byte[]{0x1B, 0x45, (byte) (bold ? 1 : 0)});
        o.write(text.getBytes(GBK));
        o.write(0x0A);
    }

    private byte[] buildTicket(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        int cols = root.optInt("cols", 48);
        JSONArray ops = root.getJSONArray("ops");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(new byte[]{0x1B, 0x40});          // init
        o.write(new byte[]{0x1C, 0x26});          // Chinese (GBK) character mode
        o.write(new byte[]{0x1B, 0x4D, 0x01});    // condensed font B
        for (int i = 0; i < ops.length(); i++) {
            JSONObject op = ops.getJSONObject(i);
            String t = op.optString("t");
            boolean dh = op.optInt("h", 0) == 1, dw = op.optInt("w", 0) == 1, b = op.optInt("b", 0) == 1;
            int usable = dw ? cols / 2 : cols;
            if ("text".equals(t)) {
                String a = op.optString("a", "l");
                int align = "c".equals(a) ? 1 : ("r".equals(a) ? 2 : 0);
                for (String piece : wrap(op.optString("s"), usable)) {
                    line(o, piece, align, dh, dw, b);
                }
            } else if ("lr".equals(t)) {
                String l = op.optString("l"), r = op.optString("r");
                int rw = dispWidth(r);
                java.util.List<String> parts = wrap(l, Math.max(4, usable - rw - 1));
                String first = parts.get(0);
                int pad = Math.max(1, usable - dispWidth(first) - rw);
                line(o, first + repeat(' ', pad) + r, 0, dh, dw, b);
                for (int k = 1; k < parts.size(); k++) {
                    line(o, parts.get(k), 0, dh, dw, b);
                }
            } else if ("line".equals(t)) {
                line(o, repeat('-', cols), 0, false, false, false);
            } else if ("feed".equals(t)) {
                for (int k = 0; k < op.optInt("n", 1); k++) o.write(0x0A);
            }
        }
        o.write(new byte[]{0x1B, 0x61, 0x00, 0x1D, 0x21, 0x00, 0x1B, 0x45, 0x00});
        o.write(new byte[]{0x1D, 0x56, 0x42, 0x00}); // feed + cut
        return o.toByteArray();
    }

    /* ---------------- network ---------------- */

    /** Network printers sometimes refuse a connection while still busy: try up to 4 times. */
    private String sendLan(String hostArg, byte[] data) {
        String r = "";
        for (int attempt = 0; attempt < 4; attempt++) {
            r = sendLanOnce(hostArg, data);
            if ("OK".equals(r) || r.startsWith("No printer IP")) return r;
            try {
                Thread.sleep(600L * (attempt + 1));
            } catch (InterruptedException ignored) {
            }
        }
        return r + " (tried 4 times)";
    }

    private String sendLanOnce(String hostArg, byte[] data) {
        String host = hostArg == null ? "" : hostArg.trim();
        int port = 9100;
        int colon = host.lastIndexOf(':');
        if (colon > 0) {
            try {
                port = Integer.parseInt(host.substring(colon + 1));
                host = host.substring(0, colon);
            } catch (NumberFormatException ignored) {
            }
        }
        if (host.isEmpty()) {
            return "No printer IP set.";
        }
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setSoTimeout(5000);
            s.setTcpNoDelay(true);
            OutputStream out = s.getOutputStream();
            for (int off = 0; off < data.length; off += 2048) {
                out.write(data, off, Math.min(2048, data.length - off));
                out.flush();
                if (data.length > 4096) {
                    try {
                        Thread.sleep(15);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
            // let the printer take the whole job before we hang up (longer for big jobs)
            try {
                Thread.sleep(400 + Math.min(1500, data.length / 40));
            } catch (InterruptedException ignored) {
            }
            return "OK";
        } catch (SocketTimeoutException e) {
            return "Can't reach the printer at " + host + " (timed out). Check it is on, and on the same network.";
        } catch (Exception e) {
            return "Can't print to " + host + ": " + e.getMessage();
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /* ---------------- USB ---------------- */

    private UsbEndpoint bulkOut(UsbInterface i) {
        for (int e = 0; e < i.getEndpointCount(); e++) {
            UsbEndpoint ep = i.getEndpoint(e);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                return ep;
            }
        }
        return null;
    }

    private UsbInterface[] findPrinter(UsbDevice[] holder) {
        UsbInterface[] result = new UsbInterface[1];
        // first choice: a real USB printer-class interface
        for (UsbDevice d : usb.getDeviceList().values()) {
            for (int k = 0; k < d.getInterfaceCount(); k++) {
                UsbInterface i = d.getInterface(k);
                if (i.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER && bulkOut(i) != null) {
                    holder[0] = d;
                    result[0] = i;
                    return result;
                }
            }
        }
        // fallback: vendor-specific interface with a bulk OUT endpoint
        for (UsbDevice d : usb.getDeviceList().values()) {
            for (int k = 0; k < d.getInterfaceCount(); k++) {
                UsbInterface i = d.getInterface(k);
                int c = i.getInterfaceClass();
                if (c == UsbConstants.USB_CLASS_VENDOR_SPEC && bulkOut(i) != null) {
                    holder[0] = d;
                    result[0] = i;
                    return result;
                }
            }
        }
        return result;
    }

    private boolean ensurePermission(UsbDevice dev) throws InterruptedException {
        if (usb.hasPermission(dev)) {
            return true;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] granted = {false};
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                granted[0] = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                latch.countDown();
            }
        };
        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(r, f);
        }
        try {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            Intent intent = new Intent(ACTION_USB_PERMISSION);
            intent.setPackage(ctx.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, intent, flags);
            usb.requestPermission(dev, pi);
            latch.await(30, TimeUnit.SECONDS);
        } finally {
            try {
                ctx.unregisterReceiver(r);
            } catch (Exception ignored) {
            }
        }
        return granted[0] && usb.hasPermission(dev);
    }

    private String sendUsb(byte[] data) throws InterruptedException {
        UsbDevice[] holder = new UsbDevice[1];
        UsbInterface intf = findPrinter(holder)[0];
        UsbDevice dev = holder[0];
        if (dev == null || intf == null) {
            return "No USB printer found. Check the cable is plugged into the tablet.";
        }
        if (!ensurePermission(dev)) {
            return "USB permission was not allowed. Tap Print again and choose OK.";
        }
        UsbEndpoint ep = bulkOut(intf);
        UsbDeviceConnection conn = usb.openDevice(dev);
        if (conn == null) {
            return "Could not open the USB printer.";
        }
        try {
            if (!conn.claimInterface(intf, true)) {
                return "The USB printer is busy. Close other printing apps and try again.";
            }
            int off = 0;
            while (off < data.length) {
                int n = Math.min(16384, data.length - off);
                int w = conn.bulkTransfer(ep, data, off, n, 5000);
                if (w < 0) {
                    return "USB print failed part-way. Check the printer has paper and is on.";
                }
                off += w;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
            return "OK";
        } finally {
            try {
                conn.releaseInterface(intf);
            } catch (Exception ignored) {
            }
            conn.close();
        }
    }
}
