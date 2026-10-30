package bg.imotipremier.pozdravi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@SuppressLint("MissingPermission")
public class Bridge {
    static final int REQ_BT = 3;
    private static final UUID BT_UUID = UUID.fromString("6b1f3a52-9d2e-4c7a-8f0e-2a6c5b4d9e11");

    private final Activity act;
    private final WebView web;

    // Unlocked session (memory only, cleared on lock / process end)
    private static byte[] key;
    private static String pass;
    private static String user;

    private volatile BluetoothServerSocket server;
    private volatile BluetoothSocket client;
    private volatile String received;

    Bridge(Activity a, WebView w) {
        act = a;
        web = w;
    }

    static boolean unlocked() {
        return key != null;
    }

    static void lock() {
        key = null;
        pass = null;
        user = null;
    }

    /* ---------------- files ---------------- */

    static String readFile(Context c, String name) {
        File f = new File(c.getFilesDir(), name);
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    static synchronized boolean writeFile(Context c, String name, String data) {
        File tmp = new File(c.getFilesDir(), name + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(data.getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
        } catch (IOException e) {
            return false;
        }
        return tmp.renameTo(new File(c.getFilesDir(), name));
    }

    private void del(String name) {
        //noinspection ResultOfMethodCallIgnored
        new File(act.getFilesDir(), name).delete();
    }

    private JSONObject account() {
        try {
            String s = readFile(act, "account.json");
            return s.isEmpty() ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------------- account ---------------- */

    @JavascriptInterface
    public String accountInfo() {
        JSONObject a = account();
        try {
            JSONObject o = new JSONObject();
            o.put("exists", a != null);
            o.put("user", a != null ? a.optString("user") : "");
            o.put("unlocked", unlocked());
            return o.toString();
        } catch (Exception e) {
            return "{\"exists\":false}";
        }
    }

    @JavascriptInterface
    public String createAccount(String u, String p) {
        if (account() != null) return "exists";
        try {
            byte[] salt = Crypto.random(16);
            byte[] k = Crypto.derive(p, salt);
            JSONObject a = new JSONObject();
            a.put("user", u.trim());
            a.put("salt", Crypto.b64(salt));
            a.put("check", Crypto.enc(k, "pozdravi-ok"));
            // migrate data from the unencrypted version
            String old = readFile(act, "data.json");
            if (old.isEmpty()) old = readFile(act, "data.bak.json");
            if (!old.isEmpty() && !writeFile(act, "data.enc", Crypto.enc(k, old))) return "error";
            if (!writeFile(act, "account.json", a.toString())) return "error";
            del("data.json");
            del("data.bak.json");
            key = k;
            pass = p;
            user = u.trim();
            return "ok";
        } catch (Exception e) {
            return "error";
        }
    }

    @JavascriptInterface
    public String login(String u, String p) {
        JSONObject a = account();
        if (a == null) return "noaccount";
        try {
            if (!a.optString("user").equalsIgnoreCase(u.trim())) {
                Thread.sleep(800);
                return "bad";
            }
            byte[] k = Crypto.derive(p, Crypto.unb64(a.getString("salt")));
            Crypto.dec(k, a.getString("check"));
            key = k;
            pass = p;
            user = a.optString("user");
            return "ok";
        } catch (Exception e) {
            try {
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
            }
            return "bad";
        }
    }

    @JavascriptInterface
    public void logout() {
        lock();
    }

    @JavascriptInterface
    public String changePassword(String oldP, String newP) {
        JSONObject a = account();
        if (a == null || key == null) return "error";
        try {
            byte[] oldK = Crypto.derive(oldP, Crypto.unb64(a.getString("salt")));
            Crypto.dec(oldK, a.getString("check"));
            String data = load();
            byte[] salt = Crypto.random(16);
            byte[] k = Crypto.derive(newP, salt);
            if (!data.isEmpty() && !writeFile(act, "data.enc", Crypto.enc(k, data))) return "error";
            del("data.bak.enc");
            a.put("salt", Crypto.b64(salt));
            a.put("check", Crypto.enc(k, "pozdravi-ok"));
            if (!writeFile(act, "account.json", a.toString())) return "error";
            key = k;
            pass = newP;
            return "ok";
        } catch (Exception e) {
            return "bad";
        }
    }

    @JavascriptInterface
    public void resetAll() {
        lock();
        for (String n : new String[]{"account.json", "data.enc", "data.bak.enc", "data.json", "data.bak.json", "schedule.json"}) del(n);
    }

    /* ---------------- data ---------------- */

    @JavascriptInterface
    public String load() {
        if (key == null) return "";
        for (String n : new String[]{"data.enc", "data.bak.enc"}) {
            String s = readFile(act, n);
            if (s.isEmpty()) continue;
            try {
                return Crypto.dec(key, s);
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    @JavascriptInterface
    public boolean save(String json) {
        if (key == null) return false;
        try {
            String old = readFile(act, "data.enc");
            if (!old.isEmpty()) writeFile(act, "data.bak.enc", old);
            return writeFile(act, "data.enc", Crypto.enc(key, json));
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public void setSchedule(String json) {
        writeFile(act, "schedule.json", json);
        Scheduler.scheduleNext(act);
    }

    @JavascriptInterface
    public void setReminderTime(int h, int m) {
        act.getSharedPreferences("p", Context.MODE_PRIVATE).edit().putInt("h", h).putInt("m", m).apply();
        Scheduler.scheduleNext(act);
    }

    @JavascriptInterface
    public void testNotify() {
        Scheduler.notifyDay(act, Scheduler.today());
    }

    /* ---------------- sending greetings ---------------- */

    @JavascriptInterface
    public void copy(String text) {
        act.runOnUiThread(() -> {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Поздрав", text));
        });
    }

    @JavascriptInterface
    public void toast(String msg) {
        act.runOnUiThread(() -> Toast.makeText(act, msg, Toast.LENGTH_LONG).show());
    }

    private boolean start(Intent i) {
        try {
            act.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public void openViber(String phone, String text) {
        copy(text);
        act.runOnUiThread(() -> {
            Intent chat = new Intent(Intent.ACTION_VIEW, Uri.parse("viber://chat?number=" + Uri.encode(phone)));
            if (start(chat)) {
                Toast.makeText(act, "Текстът е копиран. Задръж в полето и избери „Постави“.", Toast.LENGTH_LONG).show();
                return;
            }
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text).setPackage("com.viber.voip");
            if (!start(send)) Toast.makeText(act, "Viber не е инсталиран. Текстът е копиран.", Toast.LENGTH_LONG).show();
        });
    }

    @JavascriptInterface
    public void email(String to, String subject, String body) {
        act.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
            i.putExtra(Intent.EXTRA_EMAIL, new String[]{to});
            i.putExtra(Intent.EXTRA_SUBJECT, subject);
            i.putExtra(Intent.EXTRA_TEXT, body);
            if (!start(i)) Toast.makeText(act, "Няма приложение за имейл.", Toast.LENGTH_LONG).show();
        });
    }

    @JavascriptInterface
    public void sms(String phone, String body) {
        act.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone)));
            i.putExtra("sms_body", body);
            if (!start(i)) Toast.makeText(act, "Няма приложение за SMS.", Toast.LENGTH_LONG).show();
        });
    }

    @JavascriptInterface
    public void share(String text) {
        act.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
            start(Intent.createChooser(i, "Изпрати поздрав чрез"));
        });
    }

    @JavascriptInterface
    public String saveDownload(String name, String content) {
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download");
                Uri u = act.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) return "Не успях да запиша файла.";
                try (OutputStream o = act.getContentResolver().openOutputStream(u)) {
                    if (o == null) return "Не успях да запиша файла.";
                    o.write(bytes);
                }
                return "Запазено в „Изтегляния“: " + name;
            } else {
                File f = new File(act.getExternalFilesDir(null), name);
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(bytes);
                }
                return "Запазено: " + f.getAbsolutePath();
            }
        } catch (Exception e) {
            return "Не успях да запиша файла: " + e.getMessage();
        }
    }

    /* ---------------- Bluetooth transfer ---------------- */

    void emit(String type, String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("msg", msg == null ? "" : msg);
            String js = "window.onBt&&window.onBt(" + o.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")";
            act.runOnUiThread(() -> web.evaluateJavascript(js, null));
        } catch (Exception ignored) {
        }
    }

    private BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) act.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    /** "ok", "perm" (permission dialog shown), "off" (enable dialog shown) or "none". */
    @JavascriptInterface
    public String btEnsure() {
        BluetoothAdapter a = adapter();
        if (a == null) return "none";
        if (Build.VERSION.SDK_INT >= 31
                && act.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            act.runOnUiThread(() -> act.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT));
            return "perm";
        }
        if (!a.isEnabled()) {
            act.runOnUiThread(() -> start(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)));
            return "off";
        }
        return "ok";
    }

    @JavascriptInterface
    public String btName() {
        try {
            BluetoothAdapter a = adapter();
            return a == null ? "" : a.getName();
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public String btDevices() {
        JSONArray arr = new JSONArray();
        try {
            BluetoothAdapter a = adapter();
            if (a != null) for (BluetoothDevice d : a.getBondedDevices()) {
                JSONObject o = new JSONObject();
                o.put("name", d.getName() == null ? d.getAddress() : d.getName());
                o.put("addr", d.getAddress());
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        return arr.toString();
    }

    @JavascriptInterface
    public void btSend(String addr, String json) {
        if (key == null) {
            emit("error", "Влез в акаунта си.");
            return;
        }
        final String payload;
        try {
            JSONObject a = account();
            JSONObject o = new JSONObject();
            o.put("v", 1);
            o.put("user", user);
            o.put("salt", a.getString("salt"));
            o.put("data", Crypto.enc(key, json));
            payload = o.toString();
        } catch (Exception e) {
            emit("error", "Не успях да подготвя данните.");
            return;
        }
        new Thread(() -> {
            BluetoothSocket s = null;
            try {
                BluetoothAdapter a = adapter();
                BluetoothDevice d = a.getRemoteDevice(addr);
                emit("status", "Свързване с " + d.getName() + "…");
                s = d.createRfcommSocketToServiceRecord(BT_UUID);
                client = s;
                s.connect();
                emit("status", "Изпращане…");
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                out.writeInt(bytes.length);
                out.write(bytes);
                out.flush();
                DataInputStream in = new DataInputStream(s.getInputStream());
                int ack = in.readByte();
                if (ack == 1) emit("sent", "");
                else if (ack == 2) emit("error", "Другият телефон е с различен акаунт или парола. Нищо не е прехвърлено.");
                else emit("error", "Другият телефон отказа данните.");
            } catch (IOException e) {
                emit("error", "Няма връзка. На другия телефон отвори „Получи“ и опитай пак.");
            } catch (Exception e) {
                emit("error", "Грешка: " + e.getMessage());
            } finally {
                client = null;
                try {
                    if (s != null) s.close();
                } catch (IOException ignored) {
                }
            }
        }).start();
    }

    @JavascriptInterface
    public void btReceive() {
        if (key == null) {
            emit("error", "Влез в акаунта си.");
            return;
        }
        received = null;
        new Thread(() -> {
            BluetoothSocket s = null;
            try {
                BluetoothAdapter a = adapter();
                server = a.listenUsingRfcommWithServiceRecord("Pozdravi", BT_UUID);
                s = server.accept(180000);
                try {
                    server.close();
                } catch (IOException ignored) {
                }
                server = null;
                emit("status", "Получаване…");
                DataInputStream in = new DataInputStream(s.getInputStream());
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                int len = in.readInt();
                if (len <= 0 || len > 50_000_000) throw new IOException("bad length");
                byte[] buf = new byte[len];
                in.readFully(buf);
                JSONObject o = new JSONObject(new String(buf, StandardCharsets.UTF_8));
                String plain = null;
                if (user != null && user.equalsIgnoreCase(o.optString("user"))) {
                    try {
                        byte[] k = Crypto.derive(pass, Crypto.unb64(o.getString("salt")));
                        plain = Crypto.dec(k, o.getString("data"));
                    } catch (Exception ignored) {
                    }
                }
                out.writeByte(plain != null ? 1 : 2);
                out.flush();
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                }
                if (plain != null) {
                    received = plain;
                    emit("received", o.optString("user"));
                } else {
                    emit("error", "Данните са от друг акаунт или с друга парола. Отказани са.");
                }
            } catch (IOException e) {
                if (server != null || s != null) emit("error", "Времето изтече или връзката прекъсна.");
                else emit("cancelled", "");
            } catch (Exception e) {
                emit("error", "Грешка: " + e.getMessage());
            } finally {
                try {
                    if (server != null) server.close();
                } catch (IOException ignored) {
                }
                server = null;
                try {
                    if (s != null) s.close();
                } catch (IOException ignored) {
                }
            }
        }).start();
    }

    @JavascriptInterface
    public String btTakeReceived() {
        String r = received;
        received = null;
        return r == null ? "" : r;
    }

    @JavascriptInterface
    public void btCancel() {
        try {
            BluetoothServerSocket sv = server;
            server = null;
            if (sv != null) sv.close();
        } catch (IOException ignored) {
        }
        try {
            BluetoothSocket c = client;
            if (c != null) c.close();
        } catch (IOException ignored) {
        }
    }
}
