package bg.imotipremier.pozdravi;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class Bridge {
    private final Activity act;

    Bridge(Activity a) {
        act = a;
    }

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

    @JavascriptInterface
    public String load() {
        String s = readFile(act, "data.json");
        if (s.isEmpty()) s = readFile(act, "data.bak.json");
        return s;
    }

    @JavascriptInterface
    public boolean save(String json) {
        String old = readFile(act, "data.json");
        if (!old.isEmpty()) writeFile(act, "data.bak.json", old);
        return writeFile(act, "data.json", json);
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
}
