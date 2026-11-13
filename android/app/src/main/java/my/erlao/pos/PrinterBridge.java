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

import java.io.OutputStream;
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
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                if ("lan".equals(target)) {
                    return sendLan(host, data);
                }
                return sendUsb(data);
            } catch (Throwable t) {
                return "Printer error: " + t.getMessage();
            }
        }
    }

    @JavascriptInterface
    public String version() {
        return "1.0";
    }

    /* ---------------- network ---------------- */

    private String sendLan(String hostArg, byte[] data) {
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
            s.connect(new InetSocketAddress(host, port), 4000);
            s.setSoTimeout(5000);
            s.setTcpNoDelay(true);
            OutputStream out = s.getOutputStream();
            out.write(data);
            out.flush();
            // give the printer a moment to drain its buffer before closing
            try {
                Thread.sleep(300);
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
